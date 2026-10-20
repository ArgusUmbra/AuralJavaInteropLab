package org.auralinterop.manifest;

import java.util.Objects;

/** Controlled manifest rejection with a stable validation stage. */
public final class ManifestValidationException extends Exception {
    private static final long serialVersionUID = 1L;

    public enum Stage {
        JSON_SYNTAX,
        SCHEMA,
        SEMANTIC,
        UNSUPPORTED_CRITICAL_EXTENSION,
        CANONICALIZATION
    }

    public enum Reason {
        GENERAL,
        RESOURCE_LIMIT
    }

    private final Stage stage;
    private final Reason reason;
    private final String location;

    public ManifestValidationException(Stage stage, String location, String message) {
        this(stage, Reason.GENERAL, location, message);
    }

    public ManifestValidationException(Stage stage, Reason reason, String location, String message) {
        super(message);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.location = Objects.requireNonNull(location, "location");
    }

    public ManifestValidationException(Stage stage, String location, String message, Throwable cause) {
        this(stage, Reason.GENERAL, location, message, cause);
    }

    public ManifestValidationException(
            Stage stage, Reason reason, String location, String message, Throwable cause) {
        super(message, cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.reason = Objects.requireNonNull(reason, "reason");
        this.location = Objects.requireNonNull(location, "location");
    }

    public Stage stage() {
        return stage;
    }

    public Reason reason() {
        return reason;
    }

    public String location() {
        return location;
    }
}
