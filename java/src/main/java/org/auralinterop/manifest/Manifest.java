package org.auralinterop.manifest;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable typed model of an AURAL Draft 0.2 manifest. */
public record Manifest(
        String format,
        String version,
        String original,
        Asset asset,
        Optional<String> title,
        Optional<RecordedAt> recordedAt,
        Optional<Map<String, ManifestValue>> extensions,
        Optional<List<String>> criticalExtensions) {

    public Manifest {
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(asset, "asset");
        title = Objects.requireNonNull(title, "title");
        recordedAt = Objects.requireNonNull(recordedAt, "recordedAt");
        extensions = immutableMapOptional(extensions);
        criticalExtensions = immutableListOptional(criticalExtensions);
    }

    private static Optional<Map<String, ManifestValue>> immutableMapOptional(
            Optional<Map<String, ManifestValue>> source) {
        Objects.requireNonNull(source, "extensions");
        if (source.isEmpty()) {
            return Optional.empty();
        }
        LinkedHashMap<String, ManifestValue> copy = new LinkedHashMap<>();
        for (Map.Entry<String, ManifestValue> entry : source.orElseThrow().entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "extension identifier"),
                    Objects.requireNonNull(entry.getValue(), "extension value"));
        }
        return Optional.of(Collections.unmodifiableMap(copy));
    }

    private static Optional<List<String>> immutableListOptional(Optional<List<String>> source) {
        Objects.requireNonNull(source, "criticalExtensions");
        return source.map(List::copyOf);
    }

    public record Asset(String path, String role, String mediaType, long size, String sha256) {
        public Asset {
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(role, "role");
            Objects.requireNonNull(mediaType, "mediaType");
            Objects.requireNonNull(sha256, "sha256");
        }
    }

    /** Both the exact accepted spelling and its parsed value are retained. */
    public record RecordedAt(String lexical, OffsetDateTime value) {
        public RecordedAt {
            Objects.requireNonNull(lexical, "lexical");
            Objects.requireNonNull(value, "value");
        }
    }
}
