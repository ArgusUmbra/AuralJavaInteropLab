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
    private final long rawCompressedSize32;
    private final long rawUncompressedSize32;
    private final long compressedSize;
    private final long uncompressedSize;
    private final long rawLocalHeaderOffset32;
    private final boolean compressedSizeFromZip64;
    private final boolean uncompressedSizeFromZip64;
    private final boolean localHeaderOffsetFromZip64;
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
            long rawCompressedSize32,
            long rawUncompressedSize32,
            long compressedSize,
            long uncompressedSize,
            long rawLocalHeaderOffset32,
            boolean compressedSizeFromZip64,
            boolean uncompressedSizeFromZip64,
            boolean localHeaderOffsetFromZip64,
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
        this.rawCompressedSize32 = rawCompressedSize32;
        this.rawUncompressedSize32 = rawUncompressedSize32;
        this.compressedSize = compressedSize;
        this.uncompressedSize = uncompressedSize;
        this.rawLocalHeaderOffset32 = rawLocalHeaderOffset32;
        this.compressedSizeFromZip64 = compressedSizeFromZip64;
        this.uncompressedSizeFromZip64 = uncompressedSizeFromZip64;
        this.localHeaderOffsetFromZip64 = localHeaderOffsetFromZip64;
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
    public long rawCompressedSize32() { return rawCompressedSize32; }
    public long rawUncompressedSize32() { return rawUncompressedSize32; }
    public long compressedSize() { return compressedSize; }
    public long uncompressedSize() { return uncompressedSize; }
    public long rawLocalHeaderOffset32() { return rawLocalHeaderOffset32; }
    public boolean compressedSizeFromZip64() { return compressedSizeFromZip64; }
    public boolean uncompressedSizeFromZip64() { return uncompressedSizeFromZip64; }
    public boolean localHeaderOffsetFromZip64() { return localHeaderOffsetFromZip64; }
    public boolean usesZip64Extra() {
        return compressedSizeFromZip64 || uncompressedSizeFromZip64 || localHeaderOffsetFromZip64;
    }
    public int internalAttributes() { return internalAttributes; }
    public long externalAttributes() { return externalAttributes; }
    public ByteRange localHeaderRange() { return localHeaderRange; }
    public ByteRange dataRange() { return dataRange; }
    public ByteRange centralHeaderRange() { return centralHeaderRange; }
    public long dataOffset() { return dataRange.startInclusive(); }
    public long localHeaderOffset() { return localHeaderRange.startInclusive(); }
}
