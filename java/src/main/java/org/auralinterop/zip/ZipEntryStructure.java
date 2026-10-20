package org.auralinterop.zip;

import java.util.Objects;

/** Immutable structural metadata for one validated AURAL ZIP entry. */
public final class ZipEntryStructure {
    private final String name;
    private final byte[] rawName;
    private final int versionMadeBy;
    private final int versionNeeded;
    private final int flags;
    private final int compressionMethod;
    private final int modificationTime;
    private final int modificationDate;
    private final long crc32;
    private final long compressedSize;
    private final long uncompressedSize;
    private final int internalAttributes;
    private final long externalAttributes;
    private final ByteRange localHeaderRange;
    private final ByteRange dataRange;
    private final ByteRange centralHeaderRange;

    ZipEntryStructure(
            String name,
            byte[] rawName,
            int versionMadeBy,
            int versionNeeded,
            int flags,
            int compressionMethod,
            int modificationTime,
            int modificationDate,
            long crc32,
            long compressedSize,
            long uncompressedSize,
            int internalAttributes,
            long externalAttributes,
            ByteRange localHeaderRange,
            ByteRange dataRange,
            ByteRange centralHeaderRange) {
        this.name = Objects.requireNonNull(name, "name");
        this.rawName = rawName.clone();
        this.versionMadeBy = versionMadeBy;
        this.versionNeeded = versionNeeded;
        this.flags = flags;
        this.compressionMethod = compressionMethod;
        this.modificationTime = modificationTime;
        this.modificationDate = modificationDate;
        this.crc32 = crc32;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.internalAttributes = internalAttributes;
        this.externalAttributes = externalAttributes;
        this.localHeaderRange = Objects.requireNonNull(localHeaderRange, "localHeaderRange");
        this.dataRange = Objects.requireNonNull(dataRange, "dataRange");
        this.centralHeaderRange = Objects.requireNonNull(centralHeaderRange, "centralHeaderRange");
    }

    public String name() { return name; }
    public byte[] rawName() { return rawName.clone(); }
    public int versionMadeBy() { return versionMadeBy; }
    public int creatorSystem() { return versionMadeBy >>> 8; }
    public int versionNeeded() { return versionNeeded; }
    public int flags() { return flags; }
    public int compressionMethod() { return compressionMethod; }
    public int modificationTime() { return modificationTime; }
    public int modificationDate() { return modificationDate; }
    public long crc32() { return crc32; }
    public long compressedSize() { return compressedSize; }
    public long uncompressedSize() { return uncompressedSize; }
    public int internalAttributes() { return internalAttributes; }
    public long externalAttributes() { return externalAttributes; }
    public ByteRange localHeaderRange() { return localHeaderRange; }
    public ByteRange dataRange() { return dataRange; }
    public ByteRange centralHeaderRange() { return centralHeaderRange; }
    public long dataOffset() { return dataRange.startInclusive(); }
}
