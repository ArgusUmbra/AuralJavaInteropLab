package org.auralinterop.adapter;

import org.auralinterop.json.JsonParseException;
import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;
import org.auralinterop.verify.AuralPackageVerifier;
import org.auralinterop.verify.AuralVerificationException;
import org.auralinterop.verify.VerificationErrorCategory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Experimental AURAL Conformance Adapter Protocol 1 process entry point. */
public final class AuralConformanceAdapter {
    private static final String PROTOCOL = "org.aural.conformance.adapter";
    private static final long PROTOCOL_VERSION = 1;
    private static final String OPERATION = "verify-package";
    private static final String INPUT_ROLE = "package";
    private static final int MAXIMUM_REQUEST_BYTES = 16_384;
    private static final int MAXIMUM_RESPONSE_BYTES = 65_536;
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Set<String> REQUEST_MEMBERS = Set.of(
            "protocol", "protocol_version", "case_id", "operation", "input");
    private static final Set<String> INPUT_MEMBERS = Set.of("role", "path", "size", "sha256");

    private AuralConformanceAdapter() {
    }

    public static void main(String[] arguments) {
        int exitCode = run(arguments, System.in, System.out, System.err);
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    static int run(String[] arguments, InputStream input, OutputStream output, PrintStream error) {
        try {
            if (arguments.length != 0) {
                throw new ProtocolFailure("Adapter Protocol 1 does not accept command-line arguments");
            }
            Request request = parseRequest(readRequest(input));
            Path archive = usableArchivePath(request.path());

            String outcome;
            AuralVerificationException rejection = null;
            try {
                new AuralPackageVerifier().verify(archive);
                outcome = "accept";
            } catch (AuralVerificationException controlledRejection) {
                if (controlledRejection.category()
                        == VerificationErrorCategory.IO_OR_TRUNCATED_READ_FAILURE) {
                    throw new IOException("package verification could not complete archive I/O",
                            controlledRejection);
                }
                outcome = "reject";
                rejection = controlledRejection;
            }

            byte[] response = response(request.caseId(), outcome, rejection)
                    .getBytes(StandardCharsets.UTF_8);
            if (response.length > MAXIMUM_RESPONSE_BYTES) {
                throw new IllegalStateException("protocol response exceeds 65,536 bytes");
            }
            output.write(response);
            output.flush();
            return 0;
        } catch (ProtocolFailure failure) {
            error.println("AURAL adapter protocol error: " + failure.getMessage());
            return 2;
        } catch (IOException | SecurityException failure) {
            error.println("AURAL adapter execution error: " + conciseMessage(failure));
            return 3;
        } catch (RuntimeException failure) {
            error.println("AURAL adapter internal error: " + conciseMessage(failure));
            return 3;
        }
    }

    private static byte[] readRequest(InputStream input) throws IOException, ProtocolFailure {
        ByteArrayOutputStream collected = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        while (true) {
            int count = input.read(buffer);
            if (count < 0) {
                return collected.toByteArray();
            }
            if (count == 0) {
                continue;
            }
            if (collected.size() > MAXIMUM_REQUEST_BYTES - count) {
                throw new ProtocolFailure("request exceeds 16,384-byte limit");
            }
            collected.write(buffer, 0, count);
        }
    }

    private static Request parseRequest(byte[] bytes) throws ProtocolFailure {
        JsonValue parsed;
        try {
            parsed = new StrictJsonParser(MAXIMUM_REQUEST_BYTES, 8).parse(trimJsonWhitespace(bytes));
        } catch (JsonParseException invalidJson) {
            throw new ProtocolFailure("request is not valid protocol JSON: " + invalidJson.getMessage());
        }
        JsonValue.JsonObject root = requireObject(parsed, "request");
        requireExactMembers(root.members(), REQUEST_MEMBERS, "request");

        String protocol = requireString(root, "protocol", false);
        long version = requireInteger(root, "protocol_version");
        String caseId = requireString(root, "case_id", true);
        String operation = requireString(root, "operation", false);
        JsonValue.JsonObject input = requireObject(root.members().get("input"), "input");
        requireExactMembers(input.members(), INPUT_MEMBERS, "input");
        String role = requireString(input, "role", false);
        String path = requireString(input, "path", true);
        long size = requireInteger(input, "size");
        String sha256 = requireString(input, "sha256", false);

        if (!PROTOCOL.equals(protocol)) {
            throw new ProtocolFailure("unsupported protocol identifier");
        }
        if (version != PROTOCOL_VERSION) {
            throw new ProtocolFailure("unsupported protocol version");
        }
        if (!OPERATION.equals(operation)) {
            throw new ProtocolFailure("unsupported operation");
        }
        if (!INPUT_ROLE.equals(role)) {
            throw new ProtocolFailure("unsupported input role");
        }
        if (size < 0) {
            throw new ProtocolFailure("input.size must be non-negative");
        }
        if (!SHA256.matcher(sha256).matches()) {
            throw new ProtocolFailure("input.sha256 must be 64 lowercase hexadecimal digits");
        }
        return new Request(caseId, path);
    }

    private static byte[] trimJsonWhitespace(byte[] bytes) {
        int start = 0;
        int end = bytes.length;
        while (start < end && isJsonWhitespace(bytes[start])) {
            start++;
        }
        while (end > start && isJsonWhitespace(bytes[end - 1])) {
            end--;
        }
        return java.util.Arrays.copyOfRange(bytes, start, end);
    }

    private static boolean isJsonWhitespace(byte value) {
        return value == 0x20 || value == 0x09 || value == 0x0a || value == 0x0d;
    }

    private static Path usableArchivePath(String pathText) throws ProtocolFailure, IOException {
        final Path path;
        try {
            path = Path.of(pathText);
        } catch (InvalidPathException invalidPath) {
            throw new ProtocolFailure("input.path is not a valid local path");
        }
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
        if (!attributes.isRegularFile()) {
            throw new IOException("input.path does not identify a regular file");
        }
        return path;
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String location)
            throws ProtocolFailure {
        if (value instanceof JsonValue.JsonObject object) {
            return object;
        }
        throw new ProtocolFailure(location + " must be a JSON object");
    }

    private static void requireExactMembers(
            Map<String, JsonValue> actual, Set<String> expected, String location) throws ProtocolFailure {
        if (!actual.keySet().equals(expected)) {
            throw new ProtocolFailure(location + " must contain exactly " + expected);
        }
    }

    private static String requireString(JsonValue.JsonObject object, String name, boolean nonempty)
            throws ProtocolFailure {
        JsonValue value = object.members().get(name);
        if (!(value instanceof JsonValue.JsonString string)) {
            throw new ProtocolFailure(name + " must be a JSON string");
        }
        if (nonempty && string.value().isEmpty()) {
            throw new ProtocolFailure(name + " must be nonempty");
        }
        return string.value();
    }

    private static long requireInteger(JsonValue.JsonObject object, String name) throws ProtocolFailure {
        JsonValue value = object.members().get(name);
        if (value instanceof JsonValue.JsonInteger integer) {
            return integer.value();
        }
        throw new ProtocolFailure(name + " must be a JSON integer");
    }

    private static String response(
            String caseId, String outcome, AuralVerificationException rejection) {
        StringBuilder json = new StringBuilder(512);
        json.append("{\"protocol\":\"").append(PROTOCOL)
                .append("\",\"protocol_version\":1,\"case_id\":")
                .append(jsonString(caseId))
                .append(",\"outcome\":\"").append(outcome).append('"');
        if (rejection != null) {
            json.append(",\"diagnostic\":{\"category\":")
                    .append(jsonString(rejection.category().name()))
                    .append(",\"detail\":").append(jsonString(rejection.detail()))
                    .append('}');
        }
        json.append(",\"implementation\":{\"name\":\"AURAL Independent Java Core Reader\","
                + "\"version\":\"008D-3F-7G\"}}");
        return json.toString();
    }

    private static String jsonString(String value) {
        StringBuilder encoded = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            switch (current) {
                case '"' -> encoded.append("\\\"");
                case '\\' -> encoded.append("\\\\");
                case '\b' -> encoded.append("\\b");
                case '\f' -> encoded.append("\\f");
                case '\n' -> encoded.append("\\n");
                case '\r' -> encoded.append("\\r");
                case '\t' -> encoded.append("\\t");
                default -> {
                    if (current < 0x20) {
                        encoded.append(String.format("\\u%04x", (int) current));
                    } else {
                        encoded.append(current);
                    }
                }
            }
        }
        return encoded.append('"').toString();
    }

    private static String conciseMessage(Throwable failure) {
        String message = failure.getMessage();
        return failure.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private record Request(String caseId, String path) {
    }

    private static final class ProtocolFailure extends Exception {
        private static final long serialVersionUID = 1L;

        private ProtocolFailure(String message) {
            super(message);
        }
    }
}
