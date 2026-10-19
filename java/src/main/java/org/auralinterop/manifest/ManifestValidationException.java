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

    private final Stage stage;
    private final String location;

    public ManifestValidationException(Stage stage, String location, String message) {
        super(message);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.location = Objects.requireNonNull(location, "location");
    }

    public ManifestValidationException(Stage stage, String location, String message, Throwable cause) {
        super(message, cause);
        this.stage = Objects.requireNonNull(stage, "stage");
        this.location = Objects.requireNonNull(location, "location");
    }

    public Stage stage() {
        return stage;
    }

    public String location() {
        return location;
    }
}
