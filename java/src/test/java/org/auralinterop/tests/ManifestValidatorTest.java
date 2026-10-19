package org.auralinterop.tests;

import org.auralinterop.json.JcsCanonicalizer;
import org.auralinterop.json.StrictJsonParser;
import org.auralinterop.manifest.Manifest;
import org.auralinterop.manifest.ManifestValidationException;
import org.auralinterop.manifest.ManifestValidator;
import org.auralinterop.manifest.ManifestValue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneOffset;
import java.util.Map;

import static org.auralinterop.manifest.ManifestValidationException.Stage;
import static org.auralinterop.tests.TestSupport.assertEquals;
import static org.auralinterop.tests.TestSupport.assertFalse;
import static org.auralinterop.tests.TestSupport.assertTrue;
import static org.auralinterop.tests.TestSupport.expectThrows;
import static org.auralinterop.tests.TestSupport.test;

final class ManifestValidatorTest {
    private static final String ZERO_HASH = "0".repeat(64);
    private static final StrictJsonParser PARSER = new StrictJsonParser();
    private static final JcsCanonicalizer CANONICALIZER = new JcsCanonicalizer();
    private static final ManifestValidator VALIDATOR = new ManifestValidator();

    private ManifestValidatorTest() {
    }

    static void register(Path goldenManifest) {
        // Fourteen positive cases.
        test("manifest minimal model", ManifestValidatorTest::minimalModel);
        test("manifest published golden regression", () -> golden(goldenManifest));
        test("manifest maximum asset size", ManifestValidatorTest::maximumAssetSize);
        test("manifest title 4096 Unicode scalars", ManifestValidatorTest::maximumTitle);
        test("manifest UTC timestamp", () -> validTimestamp("2026-10-13T18:00:00Z"));
        test("manifest permitted timezone offset boundaries", ManifestValidatorTest::permittedOffsets);
        test("manifest leap-year calendar date", () -> validTimestamp("2024-02-29T23:59:59Z"));
        test("manifest timestamp fractional boundaries", ManifestValidatorTest::fractionalBoundaries);
        test("manifest unknown optional extension values", ManifestValidatorTest::unknownOptionalExtension);
        test("manifest empty extension profiles", ManifestValidatorTest::emptyExtensionProfiles);
        test("manifest 255-byte extension identifier", ManifestValidatorTest::maximumExtensionIdentifier);
        test("manifest Unicode normalization is preserved", ManifestValidatorTest::normalizationPreserved);
        test("manifest returned collections are immutable", ManifestValidatorTest::modelImmutability);
        test("manifest deterministic repeated validation", ManifestValidatorTest::deterministicValidation);

        // Forty negative cases.
        test("manifest malformed and null input controlled", ManifestValidatorTest::malformedAndNull);
        test("manifest root must be object", () -> rejectCanonical("[]", Stage.SCHEMA));
        test("manifest required root properties", ManifestValidatorTest::requiredRootProperties);
        test("manifest unexpected root property", () -> rejectCanonical(manifestWith(",\"unknown\":0"), Stage.SCHEMA));
        test("manifest exact identity tokens", ManifestValidatorTest::exactIdentityTokens);
        test("manifest title type and length", ManifestValidatorTest::titleConstraints);
        test("manifest assets must be array", ManifestValidatorTest::assetsType);
        test("manifest asset cardinality", ManifestValidatorTest::assetCardinality);
        test("manifest asset required and forbidden properties", ManifestValidatorTest::assetProperties);
        test("manifest asset exact constants", ManifestValidatorTest::assetConstants);
        test("manifest asset field types", ManifestValidatorTest::assetTypes);
        test("manifest asset size bounds", ManifestValidatorTest::assetSizeBounds);
        test("manifest SHA-256 syntax", ManifestValidatorTest::digestSyntax);
        test("manifest timestamp letter case", ManifestValidatorTest::timestampLetterCase);
        test("manifest timestamp required components", ManifestValidatorTest::timestampRequiredComponents);
        test("manifest timestamp fraction digit count", ManifestValidatorTest::timestampFractionDigits);
        test("manifest timestamp year zero", () -> rejectTimestamp("0000-01-01T00:00:00Z", Stage.SEMANTIC));
        test("manifest timestamp non-leap February", () -> rejectTimestamp("2023-02-29T00:00:00Z", Stage.SEMANTIC));
        test("manifest timestamp month and day combinations", ManifestValidatorTest::timestampCalendar);
        test("manifest timestamp clock fields", ManifestValidatorTest::timestampClock);
        test("manifest timestamp leap second", () -> rejectTimestamp("2024-12-31T23:59:60Z", Stage.SEMANTIC));
        test("manifest timestamp negative-zero offset", () -> rejectTimestamp("2024-01-01T00:00:00-00:00", Stage.SEMANTIC));
        test("manifest timestamp offset hour range", ManifestValidatorTest::timestampOffsetHours);
        test("manifest timestamp 14-hour minute restriction", ManifestValidatorTest::timestampFourteenMinutes);
        test("manifest timestamp offset minute range", () -> rejectTimestamp("2024-01-01T00:00:00+01:60", Stage.SEMANTIC));
        test("manifest extensions must be object", () -> rejectCanonical(manifestWith(",\"extensions\":[]"), Stage.SCHEMA));
        test("manifest extension identifier syntax", ManifestValidatorTest::extensionIdentifierSyntax);
        test("manifest extension identifier byte limit", ManifestValidatorTest::extensionIdentifierLimit);
        test("manifest critical_extensions must be array", () -> rejectCanonical(manifestWith(",\"critical_extensions\":{}"), Stage.SCHEMA));
        test("manifest critical identifier type", () -> rejectCanonical(manifestWith(",\"critical_extensions\":[1]"), Stage.SCHEMA));
        test("manifest duplicate critical identifiers", ManifestValidatorTest::duplicateCritical);
        test("manifest critical identifiers require extension map", ManifestValidatorTest::criticalRequiresMap);
        test("manifest critical identifiers require matching key", ManifestValidatorTest::criticalRequiresKey);
        test("manifest unsupported critical extension", ManifestValidatorTest::unsupportedCritical);
        test("manifest leading and trailing whitespace", ManifestValidatorTest::surroundingWhitespace);
        test("manifest noncanonical property ordering", ManifestValidatorTest::propertyOrdering);
        test("manifest alternate Unicode escaping", ManifestValidatorTest::alternateUnicodeEscaping);
        test("manifest redundant slash escaping", ManifestValidatorTest::redundantSlashEscaping);
        test("manifest noncanonical nested object ordering", ManifestValidatorTest::nestedOrdering);
        test("manifest JSON profile failures at API boundary", ManifestValidatorTest::jsonProfileFailures);
        test("manifest one-mebibyte input limit", ManifestValidatorTest::manifestInputLimit);
    }

    private static void minimalModel() throws Exception {
        Manifest manifest = VALIDATOR.validate(canonical(minimal()));
        assertEquals("org.aural.audio-evidence", manifest.format());
        assertEquals("0.2-draft", manifest.version());
        assertEquals("audio/original.wav", manifest.original());
        assertEquals(0L, manifest.asset().size());
        assertEquals(ZERO_HASH, manifest.asset().sha256());
        assertTrue(manifest.title().isEmpty(), "minimal title must be absent");
        assertTrue(manifest.extensions().isEmpty(), "minimal extensions must be absent");
    }

    private static void golden(Path goldenManifest) throws Exception {
        byte[] bytes = Files.readAllBytes(goldenManifest);
        Manifest manifest = VALIDATOR.validate(bytes);
        assertEquals(402, bytes.length);
        assertEquals(52L, manifest.asset().size());
        assertTrue(manifest.recordedAt().isPresent(), "golden timestamp must be present");
    }

    private static void maximumAssetSize() throws Exception {
        Manifest manifest = validateCanonical(rootWithAssets(asset("8589934592", ZERO_HASH, ""), ""));
        assertEquals(ManifestValidator.MAXIMUM_ASSET_BYTES, manifest.asset().size());
    }

    private static void maximumTitle() throws Exception {
        String title = new String(Character.toChars(0x1F600)).repeat(4096);
        Manifest manifest = validateCanonical(manifestWith(",\"title\":\"" + title + "\""));
        assertEquals(4096, manifest.title().orElseThrow().codePointCount(0, manifest.title().orElseThrow().length()));
    }

    private static void validTimestamp(String timestamp) throws Exception {
        Manifest manifest = validateCanonical(manifestWith(",\"recorded_at\":\"" + timestamp + "\""));
        assertEquals(timestamp, manifest.recordedAt().orElseThrow().lexical());
    }

    private static void permittedOffsets() throws Exception {
        validTimestamp("2024-01-01T00:00:00+14:00");
        validTimestamp("2024-01-01T00:00:00-14:00");
        validTimestamp("2024-01-01T00:00:00+13:59");
        Manifest zero = validateCanonical(manifestWith(",\"recorded_at\":\"2024-01-01T00:00:00+00:00\""));
        assertEquals(ZoneOffset.UTC, zero.recordedAt().orElseThrow().value().getOffset());
    }

    private static void fractionalBoundaries() throws Exception {
        validTimestamp("2024-01-01T00:00:00.1Z");
        Manifest nine = validateCanonical(manifestWith(",\"recorded_at\":\"2024-01-01T00:00:00.123456789Z\""));
        assertEquals(123_456_789, nine.recordedAt().orElseThrow().value().getNano());
    }

    private static void unknownOptionalExtension() throws Exception {
        String extension = ",\"extensions\":{\"https://example.test/future\":{"
                + "\"array\":[null,true,false,-9007199254740991,9007199254740991,\"x\"],"
                + "\"object\":{\"nested\":1}}}";
        Manifest manifest = validateCanonical(manifestWith(extension));
        ManifestValue value = manifest.extensions().orElseThrow().get("https://example.test/future");
        assertTrue(value instanceof ManifestValue.ObjectValue, "extension object must be preserved");
    }

    private static void emptyExtensionProfiles() throws Exception {
        Manifest manifest = validateCanonical(manifestWith(",\"critical_extensions\":[],\"extensions\":{}"));
        assertTrue(manifest.extensions().isPresent(), "present empty map must remain distinguishable");
        assertTrue(manifest.criticalExtensions().isPresent(), "present empty list must remain distinguishable");
    }

    private static void maximumExtensionIdentifier() throws Exception {
        String identifier = "a:" + "x".repeat(253);
        Manifest manifest = validateCanonical(manifestWith(",\"extensions\":{\"" + identifier + "\":null}"));
        assertTrue(manifest.extensions().orElseThrow().containsKey(identifier), "255-byte identifier must be accepted");
    }

    private static void normalizationPreserved() throws Exception {
        String composed = "\u00e9";
        String decomposed = "e\u0301";
        Manifest first = validateCanonical(manifestWith(",\"title\":\"" + composed + "\""));
        Manifest second = validateCanonical(manifestWith(",\"title\":\"" + decomposed + "\""));
        assertFalse(first.title().equals(second.title()), "validator must not normalize title text");
    }

    private static void modelImmutability() throws Exception {
        Manifest manifest = validateCanonical(manifestWith(",\"critical_extensions\":[],\"extensions\":{\"a:x\":1}"));
        Map<String, ManifestValue> extensions = manifest.extensions().orElseThrow();
        expectThrows(UnsupportedOperationException.class,
                () -> extensions.put("a:y", ManifestValue.NullValue.INSTANCE));
        expectThrows(UnsupportedOperationException.class,
                () -> manifest.criticalExtensions().orElseThrow().add("a:y"));
    }

    private static void deterministicValidation() throws Exception {
        byte[] bytes = canonical(manifestWith(",\"extensions\":{\"a:x\":[1,2,3]},\"title\":\"repeat\""));
        Manifest expected = VALIDATOR.validate(bytes);
        for (int index = 0; index < 100; index++) {
            assertEquals(expected, VALIDATOR.validate(bytes));
        }
    }

    private static void malformedAndNull() {
        expectStage(null, Stage.JSON_SYNTAX);
        expectStage(utf8("{\"assets\":"), Stage.JSON_SYNTAX);
    }

    private static void requiredRootProperties() throws Exception {
        for (String name : new String[] {"assets", "format", "original", "version"}) {
            String json = minimal().replace(rootFragment(name), "");
            rejectCanonical(json, Stage.SCHEMA);
        }
    }

    private static String rootFragment(String name) {
        return switch (name) {
            case "assets" -> "\"assets\":[" + asset("0", ZERO_HASH, "") + "],";
            case "format" -> "\"format\":\"org.aural.audio-evidence\",";
            case "original" -> "\"original\":\"audio/original.wav\",";
            case "version" -> ",\"version\":\"0.2-draft\"";
            default -> throw new IllegalArgumentException(name);
        };
    }

    private static void exactIdentityTokens() throws Exception {
        rejectCanonical(minimal().replace("org.aural.audio-evidence", "org.example"), Stage.SCHEMA);
        rejectCanonical(minimal().replace("0.2-draft", "0.2"), Stage.SCHEMA);
        rejectCanonical(minimal().replace("\"original\":\"audio/original.wav\"", "\"original\":\"audio/other.wav\""), Stage.SCHEMA);
    }

    private static void titleConstraints() throws Exception {
        rejectCanonical(manifestWith(",\"title\":null"), Stage.SCHEMA);
        rejectCanonical(manifestWith(",\"title\":\"" + "x".repeat(4097) + "\""), Stage.SCHEMA);
    }

    private static void assetCardinality() throws Exception {
        rejectCanonical(rootWithAssets("", ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "") + "," + asset("0", ZERO_HASH, ""), ""), Stage.SCHEMA);
    }

    private static void assetsType() throws Exception {
        String json = minimal().replace("\"assets\":[" + asset("0", ZERO_HASH, "") + "]", "\"assets\":{}");
        rejectCanonical(json, Stage.SCHEMA);
    }

    private static void assetProperties() throws Exception {
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "").replace(",\"size\":0", ""), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, ",\"extra\":true"), ""), Stage.SCHEMA);
    }

    private static void assetConstants() throws Exception {
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "").replace("audio/wav", "audio/x-wav"), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "").replace("audio/original.wav", "original.wav"), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "").replace("\"role\":\"original\"", "\"role\":\"derived\""), ""), Stage.SCHEMA);
    }

    private static void assetTypes() throws Exception {
        rejectCanonical(rootWithAssets(asset("\"0\"", ZERO_HASH, ""), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", ZERO_HASH, "").replace("\"sha256\":\"" + ZERO_HASH + "\"", "\"sha256\":null"), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets("null", ""), Stage.SCHEMA);
    }

    private static void assetSizeBounds() throws Exception {
        rejectCanonical(rootWithAssets(asset("-1", ZERO_HASH, ""), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("8589934593", ZERO_HASH, ""), ""), Stage.SCHEMA);
    }

    private static void digestSyntax() throws Exception {
        rejectCanonical(rootWithAssets(asset("0", "A".repeat(64), ""), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", "0".repeat(63), ""), ""), Stage.SCHEMA);
        rejectCanonical(rootWithAssets(asset("0", "g" + "0".repeat(63), ""), ""), Stage.SCHEMA);
    }

    private static void timestampLetterCase() throws Exception {
        rejectTimestamp("2024-01-01t00:00:00Z", Stage.SCHEMA);
        rejectTimestamp("2024-01-01T00:00:00z", Stage.SCHEMA);
    }

    private static void timestampRequiredComponents() throws Exception {
        rejectCanonical(manifestWith(",\"recorded_at\":null"), Stage.SCHEMA);
        rejectTimestamp("2024-01-01T00:00Z", Stage.SCHEMA);
        rejectTimestamp("2024-01-01T00:00:00", Stage.SCHEMA);
        rejectTimestamp("2024-01-01", Stage.SCHEMA);
    }

    private static void timestampFractionDigits() throws Exception {
        rejectTimestamp("2024-01-01T00:00:00.Z", Stage.SCHEMA);
        rejectTimestamp("2024-01-01T00:00:00.1234567890Z", Stage.SCHEMA);
    }

    private static void timestampCalendar() throws Exception {
        rejectTimestamp("2024-00-01T00:00:00Z", Stage.SEMANTIC);
        rejectTimestamp("2024-13-01T00:00:00Z", Stage.SEMANTIC);
        rejectTimestamp("2024-04-31T00:00:00Z", Stage.SEMANTIC);
        rejectTimestamp("2024-01-00T00:00:00Z", Stage.SEMANTIC);
    }

    private static void timestampClock() throws Exception {
        rejectTimestamp("2024-01-01T24:00:00Z", Stage.SEMANTIC);
        rejectTimestamp("2024-01-01T00:60:00Z", Stage.SEMANTIC);
    }

    private static void timestampOffsetHours() throws Exception {
        rejectTimestamp("2024-01-01T00:00:00+15:00", Stage.SEMANTIC);
        rejectTimestamp("2024-01-01T00:00:00-15:00", Stage.SEMANTIC);
    }

    private static void timestampFourteenMinutes() throws Exception {
        rejectTimestamp("2024-01-01T00:00:00+14:01", Stage.SEMANTIC);
        rejectTimestamp("2024-01-01T00:00:00-14:01", Stage.SEMANTIC);
    }

    private static void extensionIdentifierSyntax() throws Exception {
        for (String identifier : new String[] {"HTTPS://example.test/x", "noscheme", "1a:x", "a:has space", "a:\u00e9"}) {
            rejectCanonical(manifestWith(",\"extensions\":{\"" + identifier + "\":null}"), Stage.SCHEMA);
        }
    }

    private static void extensionIdentifierLimit() throws Exception {
        String identifier = "a:" + "x".repeat(254);
        rejectCanonical(manifestWith(",\"extensions\":{\"" + identifier + "\":null}"), Stage.SCHEMA);
    }

    private static void duplicateCritical() throws Exception {
        String extra = ",\"critical_extensions\":[\"a:x\",\"a:x\"],\"extensions\":{\"a:x\":null}";
        rejectCanonical(manifestWith(extra), Stage.SCHEMA);
    }

    private static void criticalRequiresMap() throws Exception {
        rejectCanonical(manifestWith(",\"critical_extensions\":[\"a:x\"]"), Stage.SEMANTIC);
    }

    private static void criticalRequiresKey() throws Exception {
        String extra = ",\"critical_extensions\":[\"a:x\"],\"extensions\":{\"a:y\":null}";
        rejectCanonical(manifestWith(extra), Stage.SEMANTIC);
    }

    private static void unsupportedCritical() throws Exception {
        String extra = ",\"critical_extensions\":[\"a:x\"],\"extensions\":{\"a:x\":null}";
        rejectCanonical(manifestWith(extra), Stage.UNSUPPORTED_CRITICAL_EXTENSION);
    }

    private static void surroundingWhitespace() throws Exception {
        byte[] bytes = canonical(minimal());
        expectStage(utf8(" " + text(bytes)), Stage.CANONICALIZATION);
        expectStage(utf8(text(bytes) + "\n"), Stage.JSON_SYNTAX);
    }

    private static void propertyOrdering() throws Exception {
        String orderedWrong = "{\"format\":\"org.aural.audio-evidence\",\"assets\":[" + asset("0", ZERO_HASH, "")
                + "],\"original\":\"audio/original.wav\",\"version\":\"0.2-draft\"}";
        expectStage(utf8(orderedWrong), Stage.CANONICALIZATION);
    }

    private static void alternateUnicodeEscaping() throws Exception {
        String raw = text(canonical(manifestWith(",\"title\":\"a\""))).replace("\"title\":\"a\"", "\"title\":\"\\u0061\"");
        expectStage(utf8(raw), Stage.CANONICALIZATION);
        String emoji = new String(Character.toChars(0x1F600));
        raw = text(canonical(manifestWith(",\"title\":\"" + emoji + "\""))).replace(emoji, "\\uD83D\\uDE00");
        expectStage(utf8(raw), Stage.CANONICALIZATION);
    }

    private static void redundantSlashEscaping() throws Exception {
        String raw = text(canonical(minimal())).replace("audio/original.wav", "audio\\/original.wav");
        expectStage(utf8(raw), Stage.CANONICALIZATION);
    }

    private static void nestedOrdering() throws Exception {
        String raw = text(canonical(manifestWith(",\"extensions\":{\"a:x\":{\"a\":2,\"b\":1}}")))
                .replace("{\"a\":2,\"b\":1}", "{\"b\":1,\"a\":2}");
        expectStage(utf8(raw), Stage.CANONICALIZATION);
    }

    private static void jsonProfileFailures() throws Exception {
        String base = text(canonical(minimal()));
        expectStage(utf8(base.replace("\"size\":0", "\"size\":00")), Stage.JSON_SYNTAX);
        expectStage(utf8(base.replace("\"size\":0", "\"size\":-0")), Stage.JSON_SYNTAX);
        expectStage(utf8(base.replace("\"size\":0", "\"size\":9007199254740992")), Stage.JSON_SYNTAX);
        expectStage(utf8("{\"x\":1,\"x\":2}"), Stage.JSON_SYNTAX);
        expectStage(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}, Stage.JSON_SYNTAX);
        expectStage(new byte[] {'{', '"', (byte) 0xC3, 0x28, '"', ':', '0', '}'}, Stage.JSON_SYNTAX);
    }

    private static void manifestInputLimit() {
        expectStage(new byte[ManifestValidator.MAXIMUM_MANIFEST_BYTES + 1], Stage.JSON_SYNTAX);
    }

    private static void rejectTimestamp(String timestamp, Stage stage) throws Exception {
        rejectCanonical(manifestWith(",\"recorded_at\":\"" + timestamp + "\""), stage);
    }

    private static void rejectCanonical(String json, Stage stage) throws Exception {
        expectStage(canonical(json), stage);
    }

    private static Manifest validateCanonical(String json) throws Exception {
        return VALIDATOR.validate(canonical(json));
    }

    private static byte[] canonical(String json) throws Exception {
        return CANONICALIZER.canonicalize(PARSER.parse(utf8(json)));
    }

    private static void expectStage(byte[] bytes, Stage expected) {
        ManifestValidationException failure = expectThrows(ManifestValidationException.class,
                () -> VALIDATOR.validate(bytes));
        assertEquals(expected, failure.stage());
        assertTrue(!failure.location().isEmpty(), "failure location must be populated");
    }

    private static String minimal() {
        return rootWithAssets(asset("0", ZERO_HASH, ""), "");
    }

    private static String manifestWith(String extraRootMembers) {
        return rootWithAssets(asset("0", ZERO_HASH, ""), extraRootMembers);
    }

    private static String rootWithAssets(String assets, String extraRootMembers) {
        return "{\"assets\":[" + assets + "],\"format\":\"org.aural.audio-evidence\","
                + "\"original\":\"audio/original.wav\"" + extraRootMembers + ",\"version\":\"0.2-draft\"}";
    }

    private static String asset(String sizeToken, String hash, String extraMembers) {
        return "{\"media_type\":\"audio/wav\",\"path\":\"audio/original.wav\",\"role\":\"original\","
                + "\"sha256\":\"" + hash + "\",\"size\":" + sizeToken + extraMembers + "}";
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
