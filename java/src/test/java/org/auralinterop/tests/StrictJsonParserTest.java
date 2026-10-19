package org.auralinterop.tests;

import org.auralinterop.json.JsonParseException;
import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;

import java.nio.charset.StandardCharsets;

import static org.auralinterop.tests.TestSupport.assertEquals;
import static org.auralinterop.tests.TestSupport.assertFalse;
import static org.auralinterop.tests.TestSupport.expectThrows;
import static org.auralinterop.tests.TestSupport.test;

final class StrictJsonParserTest {
    private static final StrictJsonParser PARSER = new StrictJsonParser();

    private StrictJsonParserTest() {
    }

    static void register() {
        test("JSON ordinary profile values", StrictJsonParserTest::ordinaryValues);
        test("JSON duplicate literal keys", () -> expectReason("{\"x\":1,\"x\":2}", JsonParseException.Reason.DUPLICATE_KEY));
        test("JSON duplicate escaped-equivalent keys", () -> expectReason("{\"a\":1,\"\\u0061\":2}", JsonParseException.Reason.DUPLICATE_KEY));
        test("JSON invalid UTF-8", StrictJsonParserTest::invalidUtf8);
        test("JSON UTF-8 BOM", StrictJsonParserTest::bom);
        test("JSON unterminated string", () -> expectReason("{\"x\":\"open}", JsonParseException.Reason.UNEXPECTED_END));
        test("JSON illegal escape", () -> expectReason("{\"x\":\"\\x\"}", JsonParseException.Reason.INVALID_ESCAPE));
        test("JSON isolated escaped high surrogate", () -> expectReason("{\"x\":\"\\uD834\"}", JsonParseException.Reason.INVALID_SURROGATE));
        test("JSON isolated escaped low surrogate", () -> expectReason("{\"x\":\"\\uDD1E\"}", JsonParseException.Reason.INVALID_SURROGATE));
        test("JSON valid supplementary literal", StrictJsonParserTest::supplementaryLiteral);
        test("JSON valid supplementary escape pair", StrictJsonParserTest::supplementaryEscape);
        test("JSON BMP noncharacter", () -> expectReason("{\"x\":\"\\uFDD0\"}", JsonParseException.Reason.NONCHARACTER));
        test("JSON supplementary-plane noncharacter", StrictJsonParserTest::supplementaryNoncharacter);
        test("JSON depth 32 accepted", () -> PARSER.parse(utf8(nestedArrays(32))));
        test("JSON depth 33 rejected", () -> expectReason(nestedArrays(33), JsonParseException.Reason.DEPTH_LIMIT));
        test("JSON safe integer lower boundary", () -> PARSER.parse(utf8("-9007199254740991")));
        test("JSON safe integer upper boundary", () -> PARSER.parse(utf8("9007199254740991")));
        test("JSON unsafe integer below boundary", () -> expectReason("-9007199254740992", JsonParseException.Reason.UNSAFE_INTEGER));
        test("JSON unsafe integer above boundary", () -> expectReason("9007199254740992", JsonParseException.Reason.UNSAFE_INTEGER));
        test("JSON negative zero", () -> expectReason("-0", JsonParseException.Reason.NEGATIVE_ZERO));
        test("JSON fraction", () -> expectReason("1.0", JsonParseException.Reason.INVALID_NUMBER));
        test("JSON exponent", () -> expectReason("1e0", JsonParseException.Reason.INVALID_NUMBER));
        test("JSON leading zero", () -> expectReason("01", JsonParseException.Reason.INVALID_NUMBER));
        test("JSON trailing garbage", () -> expectReason("{}x", JsonParseException.Reason.TRAILING_CONTENT));
        test("JSON trailing line ending", () -> expectReason("{}\n", JsonParseException.Reason.TRAILING_CONTENT));
        test("JSON preserves normalization distinctions", StrictJsonParserTest::normalizationPreserved);
        test("JSON configured input limit", StrictJsonParserTest::inputLimit);
    }

    private static void ordinaryValues() throws Exception {
        JsonValue value = PARSER.parse(utf8("{\"a\":1,\"b\":[true,false,null,\"x\"]}"));
        JsonValue.JsonObject object = (JsonValue.JsonObject) value;
        assertEquals(2, object.members().size());
        assertEquals(new JsonValue.JsonInteger(1), object.members().get("a"));
    }

    private static void invalidUtf8() {
        JsonParseException failure = expectThrows(JsonParseException.class,
                () -> PARSER.parse(new byte[] {'{', '"', 'x', '"', ':', '"', (byte) 0xC3, 0x28, '"', '}'}));
        assertEquals(JsonParseException.Reason.INVALID_UTF8, failure.reason());
    }

    private static void bom() {
        JsonParseException failure = expectThrows(JsonParseException.class,
                () -> PARSER.parse(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '{', '}'}));
        assertEquals(JsonParseException.Reason.UTF8_BOM, failure.reason());
    }

    private static void supplementaryLiteral() throws Exception {
        String symbol = new String(Character.toChars(0x1D11E));
        JsonValue.JsonObject object = (JsonValue.JsonObject) PARSER.parse(utf8("{\"x\":\"" + symbol + "\"}"));
        assertEquals(new JsonValue.JsonString(symbol), object.members().get("x"));
    }

    private static void supplementaryEscape() throws Exception {
        String symbol = new String(Character.toChars(0x1D11E));
        JsonValue.JsonObject object = (JsonValue.JsonObject) PARSER.parse(utf8("{\"x\":\"\\uD834\\uDD1E\"}"));
        assertEquals(new JsonValue.JsonString(symbol), object.members().get("x"));
    }

    private static void supplementaryNoncharacter() {
        String invalid = new String(Character.toChars(0x1FFFE));
        JsonParseException failure = expectThrows(JsonParseException.class,
                () -> PARSER.parse(utf8("{\"x\":\"" + invalid + "\"}")));
        assertEquals(JsonParseException.Reason.NONCHARACTER, failure.reason());
    }

    private static void normalizationPreserved() throws Exception {
        JsonValue.JsonString composed = (JsonValue.JsonString) PARSER.parse(utf8("\"\\u00e9\""));
        JsonValue.JsonString decomposed = (JsonValue.JsonString) PARSER.parse(utf8("\"e\\u0301\""));
        assertFalse(composed.value().equals(decomposed.value()), "parser must not normalize Unicode");
    }

    private static void inputLimit() {
        StrictJsonParser small = new StrictJsonParser(2, 32);
        JsonParseException failure = expectThrows(JsonParseException.class,
                () -> small.parse(utf8("{} ")));
        assertEquals(JsonParseException.Reason.INPUT_LIMIT, failure.reason());
    }

    private static void expectReason(String json, JsonParseException.Reason expected) {
        JsonParseException failure = expectThrows(JsonParseException.class, () -> PARSER.parse(utf8(json)));
        assertEquals(expected, failure.reason());
    }

    private static byte[] utf8(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String nestedArrays(int depth) {
        return "[".repeat(depth) + "0" + "]".repeat(depth);
    }
}
