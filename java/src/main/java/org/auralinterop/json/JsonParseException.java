package org.auralinterop.json;

/** Controlled rejection of JSON that is outside the AURAL Draft 0.2 profile. */
public final class JsonParseException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        INPUT_LIMIT,
        UTF8_BOM,
        INVALID_UTF8,
        UNEXPECTED_END,
        UNEXPECTED_TOKEN,
        TRAILING_CONTENT,
        DEPTH_LIMIT,
        DUPLICATE_KEY,
        INVALID_STRING,
        INVALID_ESCAPE,
        INVALID_SURROGATE,
        NONCHARACTER,
        INVALID_NUMBER,
        NEGATIVE_ZERO,
        UNSAFE_INTEGER
    }

    private final Reason reason;
    private final int position;

    public JsonParseException(Reason reason, int position, String message) {
        super(message + " at position " + position);
        this.reason = reason;
        this.position = position;
    }

    public JsonParseException(Reason reason, int position, String message, Throwable cause) {
        super(message + " at position " + position, cause);
        this.reason = reason;
        this.position = position;
    }

    public Reason reason() {
        return reason;
    }

    public int position() {
        return position;
    }
}
