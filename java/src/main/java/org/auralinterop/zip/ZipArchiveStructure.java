package org.auralinterop.zip;

import java.util.List;
import java.util.Objects;

/**
 * Immutable result of structural ZIP validation.
 *
 * <p>This result proves only the validated container structure. It does not prove actual CRC32,
 * manifest semantics, WAVE identity, SHA-256, or complete AURAL package validity.</p>
 */
public final class ZipArchiveStructure {
    private final long archiveSize;
    private final List<ZipEntryStructure> entries;
    private final ByteRange centralDirectoryRange;
    private final ByteRange endOfCentralDirectoryRange;

    ZipArchiveStructure(
            long archiveSize,
            List<ZipEntryStructure> entries,
            ByteRange centralDirectoryRange,
            ByteRange endOfCentralDirectoryRange) {
        this.archiveSize = archiveSize;
        this.entries = List.copyOf(entries);
        this.centralDirectoryRange = Objects.requireNonNull(centralDirectoryRange, "centralDirectoryRange");
        this.endOfCentralDirectoryRange = Objects.requireNonNull(
                endOfCentralDirectoryRange, "endOfCentralDirectoryRange");
    }

    public long archiveSize() { return archiveSize; }
    public List<ZipEntryStructure> entries() { return entries; }
    public ByteRange centralDirectoryRange() { return centralDirectoryRange; }
    public ByteRange endOfCentralDirectoryRange() { return endOfCentralDirectoryRange; }

    public ZipEntryStructure entry(String exactName) {
        Objects.requireNonNull(exactName, "exactName");
        return entries.stream()
                .filter(entry -> entry.name().equals(exactName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such validated entry: " + exactName));
    }
}
