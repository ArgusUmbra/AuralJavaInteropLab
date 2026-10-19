package org.auralinterop.tests;

import org.auralinterop.json.JcsCanonicalizer;
import org.auralinterop.json.JcsException;
import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.auralinterop.tests.TestSupport.assertBytes;
import static org.auralinterop.tests.TestSupport.assertEquals;
import static org.auralinterop.tests.TestSupport.expectThrows;
import static org.auralinterop.tests.TestSupport.test;

final class JcsCanonicalizerTest {
    private static final StrictJsonParser PARSER = new StrictJsonParser();
    private static final JcsCanonicalizer CANONICALIZER = new JcsCanonicalizer();

    private JcsCanonicalizerTest() {
    }

    static void register(Path goldenManifest) {
        test("JCS UTF-16 Unicode key ordering", JcsCanonicalizerTest::unicodeKeyOrdering);
        test("JCS BMP versus supplementary ordering", JcsCanonicalizerTest::bmpVersusSupplementary);
        test("JCS control-character escaping", JcsCanonicalizerTest::controlEscaping);
        test("JCS escaped versus literal Unicode", JcsCanonicalizerTest::escapedVersusLiteralUnicode);
        test("JCS slash remains unescaped", JcsCanonicalizerTest::slashEscaping);
        test("JCS backslash escaping", JcsCanonicalizerTest::backslashEscaping);
        test("JCS recursively sorts nested objects", JcsCanonicalizerTest::nestedObjects);
        test("JCS preserves array order", JcsCanonicalizerTest::arrayOrder);
        test("JCS integer boundaries", JcsCanonicalizerTest::integerBoundaries);
        test("JCS zero and negative integers", JcsCanonicalizerTest::zeroAndNegative);
        test("JCS deterministic repeated output", JcsCanonicalizerTest::determinism);
        test("JCS rejects constructed invalid Unicode", JcsCanonicalizerTest::invalidConstructedUnicode);
        test("JCS enforces output bound", JcsCanonicalizerTest::outputBound);
        test("JCS published golden manifest bytes and SHA-256", () -> goldenManifest(goldenManifest));
    }

    private static void unicodeKeyOrdering() throws Exception {
        String musical = new String(Character.toChars(0x1D11E));
        String privateUse = "\uE000";
        Map<String, JsonValue> members = new LinkedHashMap<>();
        members.put(privateUse, new JsonValue.JsonInteger(2));
        members.put(musical, new JsonValue.JsonInteger(1));
        String actual = text(CANONICALIZER.canonicalize(new JsonValue.JsonObject(members)));
        assertEquals("{\"" + musical + "\":1,\"" + privateUse + "\":2}", actual);
    }

    private static void bmpVersusSupplementary() throws Exception {
        String face = new String(Character.toChars(0x1F600));
        String bmp = "\uE001";
        String actual = canonical("{\"" + bmp + "\":2,\"" + face + "\":1}");
        assertEquals("{\"" + face + "\":1,\"" + bmp + "\":2}", actual);
    }

    private static void controlEscaping() throws Exception {
        JsonValue.JsonString controls = new JsonValue.JsonString("\u0000\b\t\n\f\r\u001f");
        assertEquals("\"\\u0000\\b\\t\\n\\f\\r\\u001f\"", text(CANONICALIZER.canonicalize(controls)));
    }

    private static void escapedVersusLiteralUnicode() throws Exception {
        byte[] escaped = CANONICALIZER.canonicalize(PARSER.parse(utf8("\"\\u00e9\"")));
        byte[] literal = CANONICALIZER.canonicalize(PARSER.parse(utf8("\"\u00e9\"")));
        assertBytes(literal, escaped);
        assertEquals("\"\u00e9\"", text(literal));
    }

    private static void slashEscaping() throws Exception {
        assertEquals("\"/\"", canonical("\"\\/\""));
    }

    private static void backslashEscaping() throws Exception {
        assertEquals("\"\\\\\"", canonical("\"\\\\\""));
    }

    private static void nestedObjects() throws Exception {
        String actual = canonical("{ \"z\" : [{\"b\":2,\"a\":1}], \"a\" : 0 }");
        assertEquals("{\"a\":0,\"z\":[{\"a\":1,\"b\":2}]}", actual);
    }

    private static void arrayOrder() throws Exception {
        assertEquals("[3,1,2,{\"a\":1,\"b\":2}]", canonical("[3,1,2,{\"b\":2,\"a\":1}]"));
    }

    private static void integerBoundaries() throws Exception {
        assertEquals("[-9007199254740991,9007199254740991]",
                canonical("[-9007199254740991,9007199254740991]"));
    }

    private static void zeroAndNegative() throws Exception {
        assertEquals("[0,-1,-42]", canonical("[0,-1,-42]"));
    }

    private static void determinism() throws Exception {
        JsonValue value = PARSER.parse(utf8("{\"z\":0,\"a\":[3,2,1],\"m\":{\"y\":2,\"x\":1}}"));
        byte[] expected = CANONICALIZER.canonicalize(value);
        for (int count = 0; count < 100; count++) {
            assertBytes(expected, CANONICALIZER.canonicalize(value));
        }
    }

    private static void invalidConstructedUnicode() {
        JsonValue.JsonString invalid = new JsonValue.JsonString(String.valueOf('\uD800'));
        JcsException failure = expectThrows(JcsException.class, () -> CANONICALIZER.canonicalize(invalid));
        assertEquals(JcsException.Reason.INVALID_UNICODE, failure.reason());
    }

    private static void outputBound() {
        JcsCanonicalizer tiny = new JcsCanonicalizer(3);
        JcsException failure = expectThrows(JcsException.class,
                () -> tiny.canonicalize(new JsonValue.JsonString("abcd")));
        assertEquals(JcsException.Reason.OUTPUT_LIMIT, failure.reason());
    }

    private static void goldenManifest(Path goldenManifest) throws Exception {
        byte[] published = Files.readAllBytes(goldenManifest);
        JsonValue parsed = PARSER.parse(published);
        byte[] canonical = CANONICALIZER.canonicalize(parsed);
        assertEquals(402, canonical.length);
        assertBytes(published, canonical);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        assertEquals("83ff69181aa5a1eb7b04a042eee89081a155a61736838615444f60c95672c3da", digest);
    }

    private static String canonical(String input) throws Exception {
        return text(CANONICALIZER.canonicalize(PARSER.parse(utf8(input))));
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
