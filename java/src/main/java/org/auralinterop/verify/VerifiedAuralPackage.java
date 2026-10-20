package org.auralinterop.verify;

import org.auralinterop.manifest.Manifest;

import java.util.List;
import java.util.Objects;

/** Complete successful result. No original recording bytes are retained or exposed. */
public record VerifiedAuralPackage(
        String version,
        Manifest manifest,
        String originalEntryName,
        long originalByteCount,
        String computedOriginalSha256,
        String declaredOriginalSha256,
        List<EntryCrc32Result> crc32Results,
        boolean usesZip64,
        CompletionStatus status) {

    public enum CompletionStatus { COMPLETE }

    public VerifiedAuralPackage {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(originalEntryName, "originalEntryName");
        Objects.requireNonNull(computedOriginalSha256, "computedOriginalSha256");
        Objects.requireNonNull(declaredOriginalSha256, "declaredOriginalSha256");
        crc32Results = List.copyOf(crc32Results);
        Objects.requireNonNull(status, "status");
        if (originalByteCount < 0) {
            throw new IllegalArgumentException("originalByteCount must be non-negative");
        }
    }
}
