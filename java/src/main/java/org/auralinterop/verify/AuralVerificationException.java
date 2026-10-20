package org.auralinterop.verify;

import java.util.Objects;

/** Controlled failure from complete Draft 0.2 package verification. */
public final class AuralVerificationException extends Exception {
    private static final long serialVersionUID = 1L;

    private final VerificationErrorCategory category;
    private final String detail;

    public AuralVerificationException(
            VerificationErrorCategory category, String detail, String message) {
        super(message);
        this.category = Objects.requireNonNull(category, "category");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public AuralVerificationException(
            VerificationErrorCategory category, String detail, String message, Throwable cause) {
        super(message, cause);
        this.category = Objects.requireNonNull(category, "category");
        this.detail = Objects.requireNonNull(detail, "detail");
    }

    public VerificationErrorCategory category() {
        return category;
    }

    /** Stable underlying stage/reason text when the delegated validator exposes one. */
    public String detail() {
        return detail;
    }
}
