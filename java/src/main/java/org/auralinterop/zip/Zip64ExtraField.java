package org.auralinterop.zip;

import static org.auralinterop.zip.ZipStructureException.Reason;

/** Strict resolver for the sole entry extra field permitted by Draft 0.2. */
final class Zip64ExtraField {
    private static final int HEADER_SIZE = 4;

    private Zip64ExtraField() {
    }

    static Values resolveLocal(
            byte[] extra, long rawUncompressedSize, long rawCompressedSize, long sizeMaximum)
            throws ZipStructureException {
        boolean uncompressedSentinel = rawUncompressedSize == ZipParsing.UINT32_SENTINEL;
        boolean compressedSentinel = rawCompressedSize == ZipParsing.UINT32_SENTINEL;
        if (uncompressedSentinel != compressedSentinel) {
            throw new ZipStructureException(
                    Reason.EXTRA_FIELD,
                    "a ZIP64 local header must sentinel-expand both size fields together");
        }
        if (!uncompressedSentinel) {
            requireAbsent(extra);
            return new Values(rawUncompressedSize, rawCompressedSize, 0, 0, false, false, false);
        }
        requireExact(extra, 16);
        long uncompressed = ZipParsing.boundedUnsignedLong(extra, HEADER_SIZE, sizeMaximum, "ZIP64 uncompressed size");
        long compressed = ZipParsing.boundedUnsignedLong(
                extra, HEADER_SIZE + Long.BYTES, sizeMaximum, "ZIP64 compressed size");
        return new Values(uncompressed, compressed, 0, 0, true, true, false);
    }

    static Values resolveCentral(
            byte[] extra,
            long rawUncompressedSize,
            long rawCompressedSize,
            long rawLocalHeaderOffset,
            int rawDiskStart,
            long sizeMaximum,
            long offsetMaximum) throws ZipStructureException {
        boolean uncompressedSentinel = rawUncompressedSize == ZipParsing.UINT32_SENTINEL;
        boolean compressedSentinel = rawCompressedSize == ZipParsing.UINT32_SENTINEL;
        boolean offsetSentinel = rawLocalHeaderOffset == ZipParsing.UINT32_SENTINEL;
        boolean diskSentinel = rawDiskStart == ZipParsing.UINT16_SENTINEL;
        if (diskSentinel) {
            throw new ZipStructureException(
                    Reason.MULTI_DISK,
                    "ZIP64 disk-start expansion is unnecessary and prohibited for a single-disk AURAL archive");
        }
        int requiredLength = (uncompressedSentinel ? Long.BYTES : 0)
                + (compressedSentinel ? Long.BYTES : 0)
                + (offsetSentinel ? Long.BYTES : 0);
        if (requiredLength == 0) {
            requireAbsent(extra);
            return new Values(
                    rawUncompressedSize, rawCompressedSize, rawLocalHeaderOffset, rawDiskStart,
                    false, false, false);
        }
        requireExact(extra, requiredLength);
        int cursor = HEADER_SIZE;
        long uncompressed = rawUncompressedSize;
        long compressed = rawCompressedSize;
        long offset = rawLocalHeaderOffset;
        if (uncompressedSentinel) {
            uncompressed = ZipParsing.boundedUnsignedLong(extra, cursor, sizeMaximum, "ZIP64 uncompressed size");
            cursor += Long.BYTES;
        }
        if (compressedSentinel) {
            compressed = ZipParsing.boundedUnsignedLong(extra, cursor, sizeMaximum, "ZIP64 compressed size");
            cursor += Long.BYTES;
        }
        if (offsetSentinel) {
            offset = ZipParsing.boundedUnsignedLong(extra, cursor, offsetMaximum, "ZIP64 local-header offset");
        }
        return new Values(
                uncompressed, compressed, offset, rawDiskStart,
                uncompressedSentinel, compressedSentinel, offsetSentinel);
    }

    private static void requireAbsent(byte[] extra) throws ZipStructureException {
        if (extra.length != 0) {
            throw new ZipStructureException(
                    Reason.EXTRA_FIELD, "extra fields require a corresponding ZIP64 sentinel");
        }
    }

    private static void requireExact(byte[] extra, int payloadLength) throws ZipStructureException {
        if (extra.length != HEADER_SIZE + payloadLength) {
            throw new ZipStructureException(
                    Reason.EXTRA_FIELD, "ZIP64 extra field has an incorrect exact length");
        }
        if (ZipParsing.unsignedShort(extra, 0) != 0x0001
                || ZipParsing.unsignedShort(extra, 2) != payloadLength) {
            throw new ZipStructureException(
                    Reason.EXTRA_FIELD, "required ZIP64 extra field is missing or malformed");
        }
    }

    record Values(
            long uncompressedSize,
            long compressedSize,
            long localHeaderOffset,
            int diskStart,
            boolean uncompressedFromZip64,
            boolean compressedFromZip64,
            boolean offsetFromZip64) {
        boolean usesZip64() {
            return uncompressedFromZip64 || compressedFromZip64 || offsetFromZip64;
        }
    }
}
