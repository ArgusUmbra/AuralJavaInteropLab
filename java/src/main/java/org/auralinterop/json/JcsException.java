package org.auralinterop.json;

/** Controlled failure while serializing the AURAL RFC 8785 profile. */
public final class JcsException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        OUTPUT_LIMIT,
        INVALID_UNICODE,
        UNSUPPORTED_VALUE
    }

    private final Reason reason;

    public JcsException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public JcsException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
