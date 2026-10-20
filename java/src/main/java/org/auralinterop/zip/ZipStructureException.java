package org.auralinterop.zip;

import java.io.IOException;

/** A controlled failure while validating the AURAL ZIP structural profile. */
public final class ZipStructureException extends IOException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        ARCHIVE_TOO_LARGE,
        EOCD_NOT_FOUND,
        EOCD_TRUNCATED,
        ARCHIVE_COMMENT,
        TRAILING_DATA,
        MULTI_DISK,
        ENTRY_COUNT,
        CENTRAL_DIRECTORY_LIMIT,
        CENTRAL_DIRECTORY_BOUNDS,
        CENTRAL_DIRECTORY_SIGNATURE,
        CENTRAL_DIRECTORY_LENGTH,
        ENTRY_COMMENT,
        UNSUPPORTED_FLAGS,
        UNSUPPORTED_COMPRESSION,
        INVALID_VERSION_NEEDED,
        INVALID_TIMESTAMP,
        INVALID_NAME,
        DUPLICATE_NAME,
        EXTRA_FIELD,
        FILE_TYPE,
        SIZE_LIMIT,
        LOCAL_HEADER_SIGNATURE,
        LOCAL_HEADER_LENGTH,
        HEADER_MISMATCH,
        LAYOUT,
        INVALID_RANGE,
        ZIP64_UNSUPPORTED_IN_THIS_STAGE
    }

    private final Reason reason;

    public ZipStructureException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public ZipStructureException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Returns true only for the temporary Assignment 008D-3F-7D capability boundary. */
    public boolean isZip64UnsupportedInThisStage() {
        return reason == Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE;
    }
}
