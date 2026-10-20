package org.auralinterop.zip;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

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
    private final boolean usesZip64EndRecords;
    private final ByteRange zip64EocdRange;
    private final ByteRange zip64LocatorRange;

    ZipArchiveStructure(
            long archiveSize,
            List<ZipEntryStructure> entries,
            ByteRange centralDirectoryRange,
            ByteRange endOfCentralDirectoryRange,
            boolean usesZip64EndRecords,
            ByteRange zip64EocdRange,
            ByteRange zip64LocatorRange) {
        this.archiveSize = archiveSize;
        this.entries = List.copyOf(entries);
        this.centralDirectoryRange = Objects.requireNonNull(centralDirectoryRange, "centralDirectoryRange");
        this.endOfCentralDirectoryRange = Objects.requireNonNull(
                endOfCentralDirectoryRange, "endOfCentralDirectoryRange");
        this.usesZip64EndRecords = usesZip64EndRecords;
        this.zip64EocdRange = zip64EocdRange;
        this.zip64LocatorRange = zip64LocatorRange;
    }

    public long archiveSize() { return archiveSize; }
    public List<ZipEntryStructure> entries() { return entries; }
    public ByteRange centralDirectoryRange() { return centralDirectoryRange; }
    public ByteRange endOfCentralDirectoryRange() { return endOfCentralDirectoryRange; }
    public boolean usesZip64EndRecords() { return usesZip64EndRecords; }
    public boolean usesZip64() {
        return usesZip64EndRecords || entries.stream().anyMatch(ZipEntryStructure::usesZip64Extra);
    }
    public Optional<ByteRange> zip64EocdRange() { return Optional.ofNullable(zip64EocdRange); }
    public Optional<ByteRange> zip64LocatorRange() { return Optional.ofNullable(zip64LocatorRange); }

    public ZipEntryStructure entry(String exactName) {
        Objects.requireNonNull(exactName, "exactName");
        return entries.stream()
                .filter(entry -> entry.name().equals(exactName))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no such validated entry: " + exactName));
    }
}
