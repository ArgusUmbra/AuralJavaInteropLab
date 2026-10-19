package org.auralinterop.json;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Map;

import static org.auralinterop.json.JsonValue.JsonArray;
import static org.auralinterop.json.JsonValue.JsonBoolean;
import static org.auralinterop.json.JsonValue.JsonInteger;
import static org.auralinterop.json.JsonValue.JsonNull;
import static org.auralinterop.json.JsonValue.JsonObject;
import static org.auralinterop.json.JsonValue.JsonString;

/** RFC 8785 canonical serialization for AURAL's safe-integer-only data profile. */
public final class JcsCanonicalizer {
    public static final int DEFAULT_MAXIMUM_OUTPUT_BYTES = 1_048_576;
    private static final char[] LOWER_HEX = "0123456789abcdef".toCharArray();

    private final int maximumOutputBytes;

    public JcsCanonicalizer() {
        this(DEFAULT_MAXIMUM_OUTPUT_BYTES);
    }

    public JcsCanonicalizer(int maximumOutputBytes) {
        if (maximumOutputBytes < 0) {
            throw new IllegalArgumentException("maximumOutputBytes must be non-negative");
        }
        this.maximumOutputBytes = maximumOutputBytes;
    }

    public byte[] canonicalize(JsonValue value) throws JcsException {
        BoundedOutput output = new BoundedOutput(maximumOutputBytes);
        writeValue(value, output);
        return output.toByteArray();
    }

    private void writeValue(JsonValue value, BoundedOutput output) throws JcsException {
        if (value instanceof JsonObject object) {
            writeObject(object, output);
        } else if (value instanceof JsonArray array) {
            writeArray(array, output);
        } else if (value instanceof JsonString string) {
            writeString(string.value(), output);
        } else if (value instanceof JsonInteger integer) {
            output.writeAscii(Long.toString(integer.value()));
        } else if (value instanceof JsonBoolean bool) {
            output.writeAscii(bool.value() ? "true" : "false");
        } else if (value == JsonNull.INSTANCE) {
            output.writeAscii("null");
        } else {
            throw new JcsException(JcsException.Reason.UNSUPPORTED_VALUE, "unsupported JSON value implementation");
        }
    }

    private void writeObject(JsonObject object, BoundedOutput output) throws JcsException {
        output.writeByte('{');
        ArrayList<Map.Entry<String, JsonValue>> entries = new ArrayList<>(object.members().entrySet());
        entries.sort(Map.Entry.comparingByKey(Comparator.naturalOrder()));
        for (int index = 0; index < entries.size(); index++) {
            if (index != 0) {
                output.writeByte(',');
            }
            Map.Entry<String, JsonValue> entry = entries.get(index);
            writeString(entry.getKey(), output);
            output.writeByte(':');
            writeValue(entry.getValue(), output);
        }
        output.writeByte('}');
    }

    private void writeArray(JsonArray array, BoundedOutput output) throws JcsException {
        output.writeByte('[');
        for (int index = 0; index < array.values().size(); index++) {
            if (index != 0) {
                output.writeByte(',');
            }
            writeValue(array.values().get(index), output);
        }
        output.writeByte(']');
    }

    private void writeString(String value, BoundedOutput output) throws JcsException {
        try {
            UnicodeProfile.validateString(value);
        } catch (IllegalArgumentException invalidUnicode) {
            throw new JcsException(JcsException.Reason.INVALID_UNICODE, "string contains invalid Unicode", invalidUnicode);
        }

        output.writeByte('"');
        for (int index = 0; index < value.length();) {
            int codePoint = value.codePointAt(index);
            index += Character.charCount(codePoint);
            switch (codePoint) {
                case 0x08 -> output.writeAscii("\\b");
                case 0x09 -> output.writeAscii("\\t");
                case 0x0A -> output.writeAscii("\\n");
                case 0x0C -> output.writeAscii("\\f");
                case 0x0D -> output.writeAscii("\\r");
                case '"' -> output.writeAscii("\\\"");
                case '\\' -> output.writeAscii("\\\\");
                default -> {
                    if (codePoint >= 0 && codePoint <= 0x1F) {
                        output.writeByte('\\');
                        output.writeByte('u');
                        output.writeByte('0');
                        output.writeByte('0');
                        output.writeByte(LOWER_HEX[(codePoint >>> 4) & 0x0F]);
                        output.writeByte(LOWER_HEX[codePoint & 0x0F]);
                    } else {
                        output.writeUtf8CodePoint(codePoint);
                    }
                }
            }
        }
        output.writeByte('"');
    }

    private static final class BoundedOutput {
        private final byte[] bytes;
        private int size;

        private BoundedOutput(int maximumBytes) {
            bytes = new byte[maximumBytes];
        }

        private void writeByte(int value) throws JcsException {
            ensureCapacity(1);
            bytes[size++] = (byte) value;
        }

        private void writeAscii(String value) throws JcsException {
            byte[] ascii = value.getBytes(StandardCharsets.US_ASCII);
            writeBytes(ascii);
        }

        private void writeUtf8CodePoint(int codePoint) throws JcsException {
            if (codePoint <= 0x7F) {
                writeByte(codePoint);
            } else if (codePoint <= 0x7FF) {
                ensureCapacity(2);
                bytes[size++] = (byte) (0xC0 | (codePoint >>> 6));
                bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
            } else if (codePoint <= 0xFFFF) {
                ensureCapacity(3);
                bytes[size++] = (byte) (0xE0 | (codePoint >>> 12));
                bytes[size++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
                bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
            } else {
                ensureCapacity(4);
                bytes[size++] = (byte) (0xF0 | (codePoint >>> 18));
                bytes[size++] = (byte) (0x80 | ((codePoint >>> 12) & 0x3F));
                bytes[size++] = (byte) (0x80 | ((codePoint >>> 6) & 0x3F));
                bytes[size++] = (byte) (0x80 | (codePoint & 0x3F));
            }
        }

        private void writeBytes(byte[] value) throws JcsException {
            ensureCapacity(value.length);
            System.arraycopy(value, 0, bytes, size, value.length);
            size += value.length;
        }

        private void ensureCapacity(int additional) throws JcsException {
            if (additional < 0 || additional > bytes.length - size) {
                throw new JcsException(JcsException.Reason.OUTPUT_LIMIT,
                        "canonical output exceeds configured byte limit");
            }
        }

        private byte[] toByteArray() {
            return Arrays.copyOf(bytes, size);
        }
    }
}
