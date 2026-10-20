package org.auralinterop.tests;

import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.concurrent.TimeUnit;

final class AdapterProtocolTest {
    private static final String ADAPTER_CLASS = "org.auralinterop.adapter.AuralConformanceAdapter";
    private static final String PROTOCOL = "org.aural.conformance.adapter";

    private AdapterProtocolTest() {
    }

    static void register(Path fixtureDirectory) {
        Path valid = fixtureDirectory.resolve("valid-minimal.aural").toAbsolutePath();
        Path invalid = fixtureDirectory.resolve("invalid-asset-hash.aural").toAbsolutePath();

        TestSupport.test("adapter accepts a valid package", () -> {
            ProcessResult result = runAdapter(request("case/valid", valid));
            assertCompleted(result, "case/valid", "accept", false);
        });
        TestSupport.test("adapter accepts protocol JSON surrounding whitespace", () -> {
            ProcessResult result = runAdapter(" \t\r\n" + request("case/whitespace", valid) + "\r\n");
            assertCompleted(result, "case/whitespace", "accept", false);
        });
        TestSupport.test("adapter verdict ignores case labels and request metadata values", () -> {
            String caseId = "known-invalid-name/expected-reject";
            ProcessResult result = runAdapter(requestWithPath(
                    caseId, valid.toString(), 0, "0".repeat(64)));
            assertCompleted(result, caseId, "accept", false);
        });
        TestSupport.test("adapter reports controlled package rejection", () -> {
            ProcessResult result = runAdapter(request("case/invalid", invalid));
            assertCompleted(result, "case/invalid", "reject", true);
        });
        protocolError("adapter rejects malformed protocol JSON", "{");
        protocolError("adapter rejects a second JSON value", request("case/trailing", valid) + "{}");
        protocolError("adapter rejects duplicate request members",
                request("case/duplicate", valid).replace(
                        "\"protocol_version\":1", "\"protocol_version\":1,\"protocol_version\":1"));
        protocolError("adapter rejects missing required request fields",
                request("case/missing", valid).replace(",\"operation\":\"verify-package\"", ""));
        protocolError("adapter rejects unknown request fields",
                request("case/unknown", valid).replace(
                        "\"operation\":", "\"unexpected\":null,\"operation\":"));
        protocolError("adapter rejects unsupported protocol identifier",
                request("case/protocol", valid).replace(PROTOCOL, "org.example"));
        protocolError("adapter rejects unsupported protocol version",
                request("case/version", valid).replace("\"protocol_version\":1", "\"protocol_version\":2"));
        protocolError("adapter rejects unsupported operation",
                request("case/operation", valid).replace("verify-package", "verify-signed-event"));
        protocolError("adapter rejects unsupported input role",
                request("case/role", valid).replace(
                        "\"role\":\"package\"", "\"role\":\"subject-package\""));
        protocolError("adapter rejects invalid request field types",
                request("case/type", valid).replace(
                        "\"size\":" + fileSize(valid), "\"size\":\"wrong\""));
        protocolError("adapter rejects invalid SHA-256 syntax",
                request("case/hash", valid).replace(sha256(valid), "A".repeat(64)));
        protocolError("adapter enforces the request byte limit",
                request("x".repeat(17_000), valid));
        TestSupport.test("adapter rejects an invalid local archive path as a protocol error", () -> {
            ProcessResult result = runAdapter(requestWithPath("case/path", "bad\u0000path.aural", 0, "0".repeat(64)));
            assertError(result);
        });
        TestSupport.test("adapter reports a nonexistent archive as an execution error", () -> {
            Path missing = Path.of(System.getProperty("java.io.tmpdir"),
                    "aural-adapter-missing-008d-3f-7g.aural").toAbsolutePath();
            Files.deleteIfExists(missing);
            ProcessResult result = runAdapter(requestWithPath(
                    "case/missing-file", missing.toString(), 0, "0".repeat(64)));
            assertError(result);
        });
        TestSupport.test("adapter launcher handles spaces Unicode absolute paths and external working directory", () -> {
            Path workspace = fixtureDirectory.getParent().getParent().getParent().getParent();
            Path externalDirectory = workspace.resolve("build").resolve("adapter path \u00fc");
            Path copiedFixture = externalDirectory.resolve("valid package \u97f3.aural");
            Files.createDirectories(externalDirectory);
            if (Files.exists(copiedFixture)) {
                Files.setAttribute(copiedFixture, "dos:readonly", false);
            }
            try {
                Files.write(copiedFixture, Files.readAllBytes(valid));
                ProcessResult result = runLauncher(
                        fixtureDirectory, externalDirectory, request("case/launcher-\u97f3", copiedFixture));
                assertCompleted(result, "case/launcher-\u97f3", "accept", false);
            } finally {
                Files.deleteIfExists(copiedFixture);
            }
        });
        TestSupport.test("adapter rejects unexpected command-line arguments", () -> {
            ProcessResult result = runAdapter(request("case/argument", valid), "--unexpected");
            assertError(result);
        });
    }

    private static void protocolError(String name, String request) {
        TestSupport.test(name, () -> assertError(runAdapter(request)));
    }

    private static ProcessResult runAdapter(String request, String... arguments) throws Exception {
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", "java.exe");
        String[] command = new String[4 + arguments.length];
        command[0] = javaExecutable.toString();
        command[1] = "-cp";
        command[2] = System.getProperty("java.class.path");
        command[3] = ADAPTER_CLASS;
        System.arraycopy(arguments, 0, command, 4, arguments.length);
        return runProcess(new ProcessBuilder(command), request);
    }

    private static ProcessResult runLauncher(
            Path fixtureDirectory, Path workingDirectory, String request) throws Exception {
        Path workspace = fixtureDirectory.getParent().getParent().getParent().getParent();
        Path launcher = workspace.resolve("java").resolve("aural-adapter.cmd").toAbsolutePath();
        ProcessBuilder process = new ProcessBuilder("cmd.exe", "/d", "/c", launcher.toString());
        process.directory(workingDirectory.toFile());
        return runProcess(process, request);
    }

    private static ProcessResult runProcess(ProcessBuilder builder, String request) throws Exception {
        Process process = builder.start();
        process.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().close();
        byte[] stdout = process.getInputStream().readAllBytes();
        byte[] stderr = process.getErrorStream().readAllBytes();
        boolean exited = process.waitFor(10, TimeUnit.SECONDS);
        if (!exited) {
            process.destroyForcibly();
            throw new AssertionError("adapter process exceeded test timeout");
        }
        return new ProcessResult(process.exitValue(), stdout, stderr);
    }

    private static void assertCompleted(
            ProcessResult result, String expectedCaseId, String expectedOutcome, boolean diagnosticExpected)
            throws Exception {
        TestSupport.assertEquals(0, result.exitCode());
        TestSupport.assertEquals("", new String(result.stderr(), StandardCharsets.UTF_8));
        JsonValue parsed = new StrictJsonParser(65_536, 8).parse(result.stdout());
        JsonValue.JsonObject response = object(parsed);
        Set<String> expectedMembers = diagnosticExpected
                ? Set.of("protocol", "protocol_version", "case_id", "outcome", "diagnostic", "implementation")
                : Set.of("protocol", "protocol_version", "case_id", "outcome", "implementation");
        TestSupport.assertEquals(expectedMembers, response.members().keySet());
        TestSupport.assertEquals(PROTOCOL, string(response, "protocol"));
        TestSupport.assertEquals(1L, integer(response, "protocol_version"));
        TestSupport.assertEquals(expectedCaseId, string(response, "case_id"));
        TestSupport.assertEquals(expectedOutcome, string(response, "outcome"));
        JsonValue.JsonObject implementation = object(response.members().get("implementation"));
        TestSupport.assertEquals(Set.of("name", "version"), implementation.members().keySet());
        TestSupport.assertTrue(!string(implementation, "name").isEmpty(), "implementation name must be nonempty");
        TestSupport.assertTrue(!string(implementation, "version").isEmpty(), "implementation version must be nonempty");
        if (diagnosticExpected) {
            JsonValue.JsonObject diagnostic = object(response.members().get("diagnostic"));
            TestSupport.assertEquals(Set.of("category", "detail"), diagnostic.members().keySet());
        }
    }

    private static void assertError(ProcessResult result) {
        TestSupport.assertTrue(result.exitCode() != 0, "protocol/execution error must exit nonzero");
        TestSupport.assertEquals(0, result.stdout().length);
        TestSupport.assertTrue(result.stderr().length > 0, "protocol/execution error must explain itself on stderr");
    }

    private static String request(String caseId, Path fixture) {
        return requestWithPath(caseId, fixture.toAbsolutePath().toString(), fileSize(fixture), sha256(fixture));
    }

    private static String requestWithPath(String caseId, String path, long size, String sha256) {
        return "{\"protocol\":\"" + PROTOCOL + "\",\"protocol_version\":1,"
                + "\"case_id\":" + jsonString(caseId) + ",\"operation\":\"verify-package\","
                + "\"input\":{\"role\":\"package\",\"path\":" + jsonString(path)
                + ",\"size\":" + size + ",\"sha256\":\"" + sha256 + "\"}}";
    }

    private static String jsonString(String value) {
        StringBuilder result = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> result.append("\\\"");
                case '\\' -> result.append("\\\\");
                default -> {
                    if (character < 0x20) {
                        result.append(String.format("\\u%04x", (int) character));
                    } else {
                        result.append(character);
                    }
                }
            }
        }
        return result.append('"').toString();
    }

    private static long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String sha256(Path path) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static JsonValue.JsonObject object(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            return object;
        }
        throw new AssertionError("expected JSON object");
    }

    private static String string(JsonValue.JsonObject object, String name) {
        if (object.members().get(name) instanceof JsonValue.JsonString string) {
            return string.value();
        }
        throw new AssertionError("expected JSON string member " + name);
    }

    private static long integer(JsonValue.JsonObject object, String name) {
        if (object.members().get(name) instanceof JsonValue.JsonInteger integer) {
            return integer.value();
        }
        throw new AssertionError("expected JSON integer member " + name);
    }

    private record ProcessResult(int exitCode, byte[] stdout, byte[] stderr) {
        private ProcessResult {
            stdout = stdout.clone();
            stderr = stderr.clone();
        }

        @Override
        public byte[] stdout() {
            return stdout.clone();
        }

        @Override
        public byte[] stderr() {
            return stderr.clone();
        }
    }
}
