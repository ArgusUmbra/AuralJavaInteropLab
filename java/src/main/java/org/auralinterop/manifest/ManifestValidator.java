package org.auralinterop.manifest;

import org.auralinterop.json.JcsCanonicalizer;
import org.auralinterop.json.JcsException;
import org.auralinterop.json.JsonParseException;
import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.auralinterop.manifest.ManifestValidationException.Stage;
import static org.auralinterop.manifest.ManifestValidationException.Reason;

/** Complete byte, schema, and semantic validator for AURAL Draft 0.2 manifests. */
public final class ManifestValidator {
    public static final int MAXIMUM_MANIFEST_BYTES = 1_048_576;
    public static final long MAXIMUM_ASSET_BYTES = 8_589_934_592L;

    private static final String FORMAT = "org.aural.audio-evidence";
    private static final String VERSION = "0.2-draft";
    private static final String ORIGINAL_PATH = "audio/original.wav";
    private static final String ORIGINAL_ROLE = "original";
    private static final String ORIGINAL_MEDIA_TYPE = "audio/wav";
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern EXTENSION_IDENTIFIER = Pattern.compile("[a-z][a-z0-9+.-]*:[!-~]+");
    private static final Pattern TIMESTAMP = Pattern.compile(
            "([0-9]{4})-([0-9]{2})-([0-9]{2})T([0-9]{2}):([0-9]{2}):([0-9]{2})"
                    + "(?:\\.([0-9]{1,9}))?(Z|([+-])([0-9]{2}):([0-9]{2}))");
    private static final Set<String> ROOT_PROPERTIES = Set.of(
            "assets", "critical_extensions", "extensions", "format", "original", "recorded_at", "title", "version");
    private static final Set<String> ASSET_PROPERTIES = Set.of("media_type", "path", "role", "sha256", "size");

    private final StrictJsonParser parser = new StrictJsonParser(MAXIMUM_MANIFEST_BYTES, 32);
    private final JcsCanonicalizer canonicalizer = new JcsCanonicalizer(MAXIMUM_MANIFEST_BYTES);

    /** Validate raw manifest bytes. Draft 0.2 defines no supported critical extensions. */
    public Manifest validate(byte[] manifestBytes) throws ManifestValidationException {
        if (manifestBytes == null) {
            throw failure(Stage.JSON_SYNTAX, "$", "manifest bytes must not be null");
        }

        JsonValue parsed;
        try {
            parsed = parser.parse(manifestBytes);
        } catch (JsonParseException invalidJson) {
            Reason reason = switch (invalidJson.reason()) {
                case INPUT_LIMIT, DEPTH_LIMIT -> Reason.RESOURCE_LIMIT;
                default -> Reason.GENERAL;
            };
            throw new ManifestValidationException(Stage.JSON_SYNTAX, reason, "$",
                    "invalid Draft 0.2 JSON profile: " + invalidJson.reason(), invalidJson);
        }

        Manifest manifest = validateModel(parsed);

        byte[] canonical;
        try {
            canonical = canonicalizer.canonicalize(parsed);
        } catch (JcsException cannotCanonicalize) {
            throw new ManifestValidationException(Stage.CANONICALIZATION, "$",
                    "manifest cannot be canonicalized: " + cannotCanonicalize.reason(), cannotCanonicalize);
        }
        if (!Arrays.equals(manifestBytes, canonical)) {
            throw failure(Stage.CANONICALIZATION, "$", "manifest bytes are not the exact JCS serialization");
        }
        return manifest;
    }

    private Manifest validateModel(JsonValue parsed) throws ManifestValidationException {
        JsonValue.JsonObject root = requireObject(parsed, "$", Stage.SCHEMA);
        rejectUnknown(root, ROOT_PROPERTIES, "$", Stage.SCHEMA);

        String format = requireExactString(root, "format", FORMAT, "$", Stage.SCHEMA);
        String version = requireExactString(root, "version", VERSION, "$", Stage.SCHEMA);
        String original = requireExactString(root, "original", ORIGINAL_PATH, "$", Stage.SCHEMA);

        JsonValue.JsonArray assets = requireArray(requireMember(root, "assets", "$"), "$.assets", Stage.SCHEMA);
        if (assets.values().size() != 1) {
            throw failure(Stage.SCHEMA, "$.assets", "must contain exactly one asset descriptor");
        }
        Manifest.Asset asset = validateAsset(assets.values().getFirst());

        Optional<String> title = optionalString(root, "title", "$.title");
        if (title.isPresent() && title.orElseThrow().codePointCount(0, title.orElseThrow().length()) > 4096) {
            throw failure(Stage.SCHEMA, "$.title", "must contain at most 4096 Unicode scalar values");
        }

        Optional<Manifest.RecordedAt> recordedAt = Optional.empty();
        if (root.members().containsKey("recorded_at")) {
            String lexical = requireString(root.members().get("recorded_at"), "$.recorded_at", Stage.SCHEMA);
            recordedAt = Optional.of(validateTimestamp(lexical));
        }

        Optional<Map<String, ManifestValue>> extensions = Optional.empty();
        Set<String> extensionNames = Set.of();
        if (root.members().containsKey("extensions")) {
            JsonValue.JsonObject extensionObject = requireObject(
                    root.members().get("extensions"), "$.extensions", Stage.SCHEMA);
            LinkedHashMap<String, ManifestValue> converted = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : extensionObject.members().entrySet()) {
                validateExtensionIdentifier(entry.getKey(), "$.extensions property");
                converted.put(entry.getKey(), convertValue(entry.getValue()));
            }
            extensions = Optional.of(converted);
            extensionNames = extensionObject.members().keySet();
        }

        Optional<List<String>> criticalExtensions = Optional.empty();
        if (root.members().containsKey("critical_extensions")) {
            JsonValue.JsonArray critical = requireArray(
                    root.members().get("critical_extensions"), "$.critical_extensions", Stage.SCHEMA);
            ArrayList<String> identifiers = new ArrayList<>(critical.values().size());
            HashSet<String> unique = new HashSet<>();
            for (int index = 0; index < critical.values().size(); index++) {
                String location = "$.critical_extensions[" + index + "]";
                String identifier = requireString(critical.values().get(index), location, Stage.SCHEMA);
                validateExtensionIdentifier(identifier, location);
                if (!unique.add(identifier)) {
                    throw failure(Stage.SCHEMA, location, "critical extension identifiers must be unique");
                }
                identifiers.add(identifier);
            }
            criticalExtensions = Optional.of(identifiers);
            for (String identifier : identifiers) {
                if (!extensionNames.contains(identifier)) {
                    throw failure(Stage.SEMANTIC, "$.critical_extensions",
                            "critical identifier is absent from extensions: " + identifier);
                }
            }
            if (!identifiers.isEmpty()) {
                throw failure(Stage.UNSUPPORTED_CRITICAL_EXTENSION, "$.critical_extensions",
                        "Draft 0.2 defines no supported critical extensions: " + identifiers.getFirst());
            }
        }

        return new Manifest(format, version, original, asset, title, recordedAt, extensions, criticalExtensions);
    }

    private static Manifest.Asset validateAsset(JsonValue value) throws ManifestValidationException {
        JsonValue.JsonObject asset = requireObject(value, "$.assets[0]", Stage.SCHEMA);
        rejectUnknown(asset, ASSET_PROPERTIES, "$.assets[0]", Stage.SCHEMA);
        String path = requireExactString(asset, "path", ORIGINAL_PATH, "$.assets[0]", Stage.SCHEMA);
        String role = requireExactString(asset, "role", ORIGINAL_ROLE, "$.assets[0]", Stage.SCHEMA);
        String mediaType = requireExactString(asset, "media_type", ORIGINAL_MEDIA_TYPE, "$.assets[0]", Stage.SCHEMA);
        JsonValue sizeValue = requireMember(asset, "size", "$.assets[0]");
        if (!(sizeValue instanceof JsonValue.JsonInteger integer)) {
            throw failure(Stage.SCHEMA, "$.assets[0].size", "must be an integer");
        }
        if (integer.value() < 0) {
            throw failure(Stage.SCHEMA, "$.assets[0].size", "must be between 0 and 8589934592 inclusive");
        }
        if (integer.value() > MAXIMUM_ASSET_BYTES) {
            throw new ManifestValidationException(Stage.SCHEMA, Reason.RESOURCE_LIMIT,
                    "$.assets[0].size", "must be between 0 and 8589934592 inclusive");
        }
        String digest = requireString(requireMember(asset, "sha256", "$.assets[0]"),
                "$.assets[0].sha256", Stage.SCHEMA);
        if (!SHA256.matcher(digest).matches()) {
            throw failure(Stage.SCHEMA, "$.assets[0].sha256", "must be 64 lowercase ASCII hexadecimal characters");
        }
        return new Manifest.Asset(path, role, mediaType, integer.value(), digest);
    }

    private static Manifest.RecordedAt validateTimestamp(String value) throws ManifestValidationException {
        Matcher matcher = TIMESTAMP.matcher(value);
        if (!matcher.matches()) {
            throw failure(Stage.SCHEMA, "$.recorded_at", "does not match the required timestamp lexical profile");
        }

        int year = decimal(matcher.group(1));
        if (year == 0) {
            throw failure(Stage.SEMANTIC, "$.recorded_at", "year zero is prohibited");
        }
        int month = decimal(matcher.group(2));
        int day = decimal(matcher.group(3));
        int hour = decimal(matcher.group(4));
        int minute = decimal(matcher.group(5));
        int second = decimal(matcher.group(6));
        int nanos = fractionToNanos(matcher.group(7));

        String offsetToken = matcher.group(8);
        ZoneOffset offset;
        if (offsetToken.equals("Z")) {
            offset = ZoneOffset.UTC;
        } else {
            int offsetHour = decimal(matcher.group(10));
            int offsetMinute = decimal(matcher.group(11));
            if (offsetToken.equals("-00:00")) {
                throw failure(Stage.SEMANTIC, "$.recorded_at", "unknown negative-zero offset is prohibited");
            }
            if (offsetHour > 14 || offsetMinute > 59 || (offsetHour == 14 && offsetMinute != 0)) {
                throw failure(Stage.SEMANTIC, "$.recorded_at", "offset must be within plus or minus 14:00");
            }
            int sign = matcher.group(9).equals("-") ? -1 : 1;
            offset = ZoneOffset.ofTotalSeconds(sign * (offsetHour * 3600 + offsetMinute * 60));
        }

        try {
            LocalDateTime local = LocalDateTime.of(year, month, day, hour, minute, second, nanos);
            return new Manifest.RecordedAt(value, OffsetDateTime.of(local, offset));
        } catch (DateTimeException invalidCalendar) {
            throw new ManifestValidationException(Stage.SEMANTIC, "$.recorded_at",
                    "timestamp calendar or clock fields are invalid", invalidCalendar);
        }
    }

    private static int decimal(String digits) {
        return Integer.parseInt(digits);
    }

    private static int fractionToNanos(String fraction) {
        if (fraction == null) {
            return 0;
        }
        return decimal(fraction + "0".repeat(9 - fraction.length()));
    }

    private static void validateExtensionIdentifier(String identifier, String location)
            throws ManifestValidationException {
        int byteLength = identifier.getBytes(StandardCharsets.UTF_8).length;
        if (byteLength < 1 || byteLength > 255 || !EXTENSION_IDENTIFIER.matcher(identifier).matches()) {
            throw failure(Stage.SCHEMA, location,
                    "extension identifier must be 1-255 UTF-8 bytes and match the Draft 0.2 syntax");
        }
    }

    private static ManifestValue convertValue(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            LinkedHashMap<String, ManifestValue> result = new LinkedHashMap<>();
            object.members().forEach((key, member) -> result.put(key, convertValue(member)));
            return new ManifestValue.ObjectValue(result);
        }
        if (value instanceof JsonValue.JsonArray array) {
            return new ManifestValue.ArrayValue(array.values().stream().map(ManifestValidator::convertValue).toList());
        }
        if (value instanceof JsonValue.JsonString string) {
            return new ManifestValue.StringValue(string.value());
        }
        if (value instanceof JsonValue.JsonInteger integer) {
            return new ManifestValue.IntegerValue(integer.value());
        }
        if (value instanceof JsonValue.JsonBoolean bool) {
            return new ManifestValue.BooleanValue(bool.value());
        }
        return ManifestValue.NullValue.INSTANCE;
    }

    private static Optional<String> optionalString(JsonValue.JsonObject object, String name, String location)
            throws ManifestValidationException {
        if (!object.members().containsKey(name)) {
            return Optional.empty();
        }
        return Optional.of(requireString(object.members().get(name), location, Stage.SCHEMA));
    }

    private static String requireExactString(JsonValue.JsonObject object, String name, String expected,
            String parentLocation, Stage stage) throws ManifestValidationException {
        String location = parentLocation + "." + name;
        String actual = requireString(requireMember(object, name, parentLocation), location, stage);
        if (!actual.equals(expected)) {
            throw failure(stage, location, "must equal " + expected);
        }
        return actual;
    }

    private static JsonValue requireMember(JsonValue.JsonObject object, String name, String parentLocation)
            throws ManifestValidationException {
        JsonValue value = object.members().get(name);
        if (value == null) {
            throw failure(Stage.SCHEMA, parentLocation + "." + name, "required member is missing");
        }
        return value;
    }

    private static String requireString(JsonValue value, String location, Stage stage)
            throws ManifestValidationException {
        if (!(value instanceof JsonValue.JsonString string)) {
            throw failure(stage, location, "must be a string");
        }
        return string.value();
    }

    private static JsonValue.JsonObject requireObject(JsonValue value, String location, Stage stage)
            throws ManifestValidationException {
        if (!(value instanceof JsonValue.JsonObject object)) {
            throw failure(stage, location, "must be an object");
        }
        return object;
    }

    private static JsonValue.JsonArray requireArray(JsonValue value, String location, Stage stage)
            throws ManifestValidationException {
        if (!(value instanceof JsonValue.JsonArray array)) {
            throw failure(stage, location, "must be an array");
        }
        return array;
    }

    private static void rejectUnknown(JsonValue.JsonObject object, Set<String> permitted,
            String location, Stage stage) throws ManifestValidationException {
        for (String name : object.members().keySet()) {
            if (!permitted.contains(name)) {
                throw failure(stage, location + "." + name, "property is not permitted");
            }
        }
    }

    private static ManifestValidationException failure(Stage stage, String location, String message) {
        return new ManifestValidationException(stage, location, message);
    }
}
