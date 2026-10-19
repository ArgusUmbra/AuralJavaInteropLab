package org.auralinterop.io;

import java.io.IOException;

/** A controlled failure while applying caller-supplied read bounds. */
public final class BoundedReadException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        NEGATIVE_OFFSET,
        NEGATIVE_LENGTH,
        LENGTH_LIMIT,
        RANGE_OVERFLOW,
        UNEXPECTED_EOF,
        NO_PROGRESS,
        CLOSED
    }

    private final Reason reason;

    public BoundedReadException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
