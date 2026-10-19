package org.auralinterop.json;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.auralinterop.json.JsonParseException.Reason;
import static org.auralinterop.json.JsonValue.JsonArray;
import static org.auralinterop.json.JsonValue.JsonBoolean;
import static org.auralinterop.json.JsonValue.JsonInteger;
import static org.auralinterop.json.JsonValue.JsonNull;
import static org.auralinterop.json.JsonValue.JsonObject;
import static org.auralinterop.json.JsonValue.JsonString;

/** Strict parser for the integer-only JSON profile used by AURAL Draft 0.2. */
public final class StrictJsonParser {
    public static final int DEFAULT_MAXIMUM_INPUT_BYTES = 1_048_576;
    public static final int DEFAULT_MAXIMUM_DEPTH = 32;

    private final int maximumInputBytes;
    private final int maximumDepth;

    public StrictJsonParser() {
        this(DEFAULT_MAXIMUM_INPUT_BYTES, DEFAULT_MAXIMUM_DEPTH);
    }

    public StrictJsonParser(int maximumInputBytes, int maximumDepth) {
        if (maximumInputBytes < 0) {
            throw new IllegalArgumentException("maximumInputBytes must be non-negative");
        }
        if (maximumDepth < 1) {
            throw new IllegalArgumentException("maximumDepth must be positive");
        }
        this.maximumInputBytes = maximumInputBytes;
        this.maximumDepth = maximumDepth;
    }

    public JsonValue parse(byte[] utf8) throws JsonParseException {
        if (utf8.length > maximumInputBytes) {
            throw new JsonParseException(Reason.INPUT_LIMIT, 0, "JSON input exceeds configured byte limit");
        }
        if (utf8.length >= 3
                && Byte.toUnsignedInt(utf8[0]) == 0xEF
                && Byte.toUnsignedInt(utf8[1]) == 0xBB
                && Byte.toUnsignedInt(utf8[2]) == 0xBF) {
            throw new JsonParseException(Reason.UTF8_BOM, 0, "UTF-8 BOM is prohibited");
        }

        String decoded;
        try {
            CharBuffer chars = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(utf8));
            decoded = chars.toString();
        } catch (CharacterCodingException invalidUtf8) {
            throw new JsonParseException(Reason.INVALID_UTF8, 0, "invalid UTF-8", invalidUtf8);
        }

        return new Parser(decoded, maximumDepth).parseDocument();
    }

    private static final class Parser {
        private final String input;
        private final int maximumDepth;
        private int position;

        private Parser(String input, int maximumDepth) {
            this.input = input;
            this.maximumDepth = maximumDepth;
        }

        private JsonValue parseDocument() throws JsonParseException {
            skipWhitespace();
            if (atEnd()) {
                throw error(Reason.UNEXPECTED_END, "expected a JSON value");
            }
            JsonValue value = parseValue(0);
            if (!atEnd()) {
                throw error(Reason.TRAILING_CONTENT, "bytes follow the root JSON value");
            }
            return value;
        }

        private JsonValue parseValue(int containerDepth) throws JsonParseException {
            if (atEnd()) {
                throw error(Reason.UNEXPECTED_END, "expected a JSON value");
            }
            return switch (input.charAt(position)) {
                case '{' -> parseObject(containerDepth);
                case '[' -> parseArray(containerDepth);
                case '"' -> new JsonString(parseString());
                case 't' -> {
                    consumeLiteral("true");
                    yield new JsonBoolean(true);
                }
                case 'f' -> {
                    consumeLiteral("false");
                    yield new JsonBoolean(false);
                }
                case 'n' -> {
                    consumeLiteral("null");
                    yield JsonNull.INSTANCE;
                }
                default -> {
                    char next = input.charAt(position);
                    if (next == '-' || (next >= '0' && next <= '9')) {
                        yield parseInteger();
                    }
                    throw error(Reason.UNEXPECTED_TOKEN, "unexpected token");
                }
            };
        }

        private JsonObject parseObject(int parentDepth) throws JsonParseException {
            int depth = enterContainer(parentDepth);
            position++;
            skipWhitespace();
            Map<String, JsonValue> members = new LinkedHashMap<>();
            if (consumeIf('}')) {
                return new JsonObject(members);
            }

            while (true) {
                if (atEnd() || input.charAt(position) != '"') {
                    throw error(Reason.UNEXPECTED_TOKEN, "object member name must be a string");
                }
                String key = parseString();
                if (members.containsKey(key)) {
                    throw error(Reason.DUPLICATE_KEY, "duplicate decoded object member name");
                }
                skipWhitespace();
                require(':');
                skipWhitespace();
                members.put(key, parseValue(depth));
                skipWhitespace();
                if (consumeIf('}')) {
                    return new JsonObject(members);
                }
                require(',');
                skipWhitespace();
            }
        }

        private JsonArray parseArray(int parentDepth) throws JsonParseException {
            int depth = enterContainer(parentDepth);
            position++;
            skipWhitespace();
            List<JsonValue> values = new ArrayList<>();
            if (consumeIf(']')) {
                return new JsonArray(values);
            }

            while (true) {
                values.add(parseValue(depth));
                skipWhitespace();
                if (consumeIf(']')) {
                    return new JsonArray(values);
                }
                require(',');
                skipWhitespace();
            }
        }

        private int enterContainer(int parentDepth) throws JsonParseException {
            int depth = parentDepth + 1;
            if (depth > maximumDepth) {
                throw error(Reason.DEPTH_LIMIT, "JSON container depth exceeds " + maximumDepth);
            }
            return depth;
        }

        private String parseString() throws JsonParseException {
            require('"');
            StringBuilder result = new StringBuilder();
            while (!atEnd()) {
                char current = input.charAt(position++);
                if (current == '"') {
                    return result.toString();
                }
                if (current == '\\') {
                    parseEscape(result);
                    continue;
                }
                if (current < 0x20) {
                    throw error(Reason.INVALID_STRING, "unescaped control character in string");
                }
                if (Character.isHighSurrogate(current)) {
                    if (atEnd() || !Character.isLowSurrogate(input.charAt(position))) {
                        throw error(Reason.INVALID_SURROGATE, "isolated high surrogate");
                    }
                    char low = input.charAt(position++);
                    appendCodePoint(result, Character.toCodePoint(current, low));
                } else if (Character.isLowSurrogate(current)) {
                    throw error(Reason.INVALID_SURROGATE, "isolated low surrogate");
                } else {
                    appendCodePoint(result, current);
                }
            }
            throw error(Reason.UNEXPECTED_END, "unterminated string");
        }

        private void parseEscape(StringBuilder result) throws JsonParseException {
            if (atEnd()) {
                throw error(Reason.UNEXPECTED_END, "unterminated string escape");
            }
            char escape = input.charAt(position++);
            switch (escape) {
                case '"' -> result.append('"');
                case '\\' -> result.append('\\');
                case '/' -> result.append('/');
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> parseUnicodeEscape(result);
                default -> throw error(Reason.INVALID_ESCAPE, "illegal string escape");
            }
        }

        private void parseUnicodeEscape(StringBuilder result) throws JsonParseException {
            char first = parseHexCodeUnit();
            if (Character.isHighSurrogate(first)) {
                if (position + 2 > input.length()
                        || input.charAt(position) != '\\'
                        || input.charAt(position + 1) != 'u') {
                    throw error(Reason.INVALID_SURROGATE, "escaped high surrogate lacks escaped low surrogate");
                }
                position += 2;
                char low = parseHexCodeUnit();
                if (!Character.isLowSurrogate(low)) {
                    throw error(Reason.INVALID_SURROGATE, "escaped high surrogate is followed by a non-low surrogate");
                }
                appendCodePoint(result, Character.toCodePoint(first, low));
            } else if (Character.isLowSurrogate(first)) {
                throw error(Reason.INVALID_SURROGATE, "isolated escaped low surrogate");
            } else {
                appendCodePoint(result, first);
            }
        }

        private char parseHexCodeUnit() throws JsonParseException {
            if (position + 4 > input.length()) {
                throw error(Reason.UNEXPECTED_END, "incomplete Unicode escape");
            }
            int value = 0;
            for (int count = 0; count < 4; count++) {
                char digit = input.charAt(position++);
                int numeric = Character.digit(digit, 16);
                if (numeric < 0 || digit > 0x7F) {
                    throw error(Reason.INVALID_ESCAPE, "Unicode escape must contain four ASCII hexadecimal digits");
                }
                value = (value << 4) | numeric;
            }
            return (char) value;
        }

        private void appendCodePoint(StringBuilder result, int codePoint) throws JsonParseException {
            if (UnicodeProfile.isNoncharacter(codePoint)) {
                throw error(Reason.NONCHARACTER, "Unicode noncharacter is prohibited");
            }
            result.appendCodePoint(codePoint);
        }

        private JsonInteger parseInteger() throws JsonParseException {
            int start = position;
            boolean negative = consumeIf('-');
            if (atEnd()) {
                throw error(Reason.INVALID_NUMBER, "minus sign must be followed by digits");
            }

            char first = input.charAt(position);
            if (first == '0') {
                position++;
                if (!atEnd() && isDigit(input.charAt(position))) {
                    throw error(Reason.INVALID_NUMBER, "leading zero is prohibited");
                }
            } else if (first >= '1' && first <= '9') {
                do {
                    position++;
                } while (!atEnd() && isDigit(input.charAt(position)));
            } else {
                throw error(Reason.INVALID_NUMBER, "integer must contain decimal digits");
            }

            if (!atEnd()) {
                char suffix = input.charAt(position);
                if (suffix == '.' || suffix == 'e' || suffix == 'E') {
                    throw error(Reason.INVALID_NUMBER, "fractions and exponent notation are prohibited");
                }
            }

            String token = input.substring(start, position);
            if (negative && token.equals("-0")) {
                throw new JsonParseException(Reason.NEGATIVE_ZERO, start, "negative zero is prohibited");
            }

            long value;
            try {
                value = Long.parseLong(token);
            } catch (NumberFormatException invalidLong) {
                throw new JsonParseException(Reason.UNSAFE_INTEGER, start, "integer is outside the supported range", invalidLong);
            }
            if (value < JsonValue.MIN_SAFE_INTEGER || value > JsonValue.MAX_SAFE_INTEGER) {
                throw new JsonParseException(Reason.UNSAFE_INTEGER, start, "integer is outside the AURAL safe range");
            }
            return new JsonInteger(value);
        }

        private void consumeLiteral(String literal) throws JsonParseException {
            if (!input.startsWith(literal, position)) {
                throw error(Reason.UNEXPECTED_TOKEN, "invalid JSON literal");
            }
            position += literal.length();
        }

        private void require(char expected) throws JsonParseException {
            if (atEnd()) {
                throw error(Reason.UNEXPECTED_END, "expected '" + expected + "'");
            }
            if (input.charAt(position) != expected) {
                throw error(Reason.UNEXPECTED_TOKEN, "expected '" + expected + "'");
            }
            position++;
        }

        private boolean consumeIf(char expected) {
            if (!atEnd() && input.charAt(position) == expected) {
                position++;
                return true;
            }
            return false;
        }

        private void skipWhitespace() {
            while (!atEnd()) {
                char current = input.charAt(position);
                if (current == ' ' || current == '\t' || current == '\r' || current == '\n') {
                    position++;
                } else {
                    return;
                }
            }
        }

        private boolean atEnd() {
            return position >= input.length();
        }

        private static boolean isDigit(char value) {
            return value >= '0' && value <= '9';
        }

        private JsonParseException error(Reason reason, String message) {
            return new JsonParseException(reason, position, message);
        }
    }
}
