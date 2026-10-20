package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;

import java.io.IOException;
import java.util.Arrays;

import static org.auralinterop.zip.ZipStructureException.Reason;

final class LocalFileHeaderParser {
    private static final int FIXED_SIZE = 30;

    Entry parse(BoundedFileReader reader, long offset, long centralDirectoryOffset) throws IOException {
        long fixedEnd = ZipParsing.add(
                offset, FIXED_SIZE, Reason.LOCAL_HEADER_LENGTH, "local-header range overflows");
        if (fixedEnd > centralDirectoryOffset) {
            throw new ZipStructureException(Reason.LOCAL_HEADER_LENGTH, "local header extends into central directory");
        }
        byte[] fixed = reader.readExact(offset, FIXED_SIZE);
        if (ZipParsing.unsignedInt(fixed, 0) != ZipParsing.LOCAL_SIGNATURE) {
            throw new ZipStructureException(Reason.LOCAL_HEADER_SIGNATURE, "invalid local-file-header signature");
        }

        int versionNeeded = ZipParsing.unsignedShort(fixed, 4);
        int flags = ZipParsing.unsignedShort(fixed, 6);
        int method = ZipParsing.unsignedShort(fixed, 8);
        int modificationTime = ZipParsing.unsignedShort(fixed, 10);
        int modificationDate = ZipParsing.unsignedShort(fixed, 12);
        long crc32 = ZipParsing.unsignedInt(fixed, 14);
        long rawCompressedSize = ZipParsing.unsignedInt(fixed, 18);
        long rawUncompressedSize = ZipParsing.unsignedInt(fixed, 22);
        int nameLength = ZipParsing.unsignedShort(fixed, 26);
        int extraLength = ZipParsing.unsignedShort(fixed, 28);

        ZipParsing.validateVersionNeeded(versionNeeded);
        ZipParsing.validateFlags(flags);
        ZipParsing.validateMethod(method);
        ZipParsing.validateDosTimestamp(modificationTime, modificationDate);

        long variableLength = (long) nameLength + extraLength;
        long headerEnd = ZipParsing.add(
                fixedEnd, variableLength, Reason.LOCAL_HEADER_LENGTH, "local-header variable range overflows");
        if (headerEnd > centralDirectoryOffset) {
            throw new ZipStructureException(
                    Reason.LOCAL_HEADER_LENGTH, "local header name or extra data extends into central directory");
        }
        byte[] variable = reader.readExact(fixedEnd, variableLength);
        byte[] rawName = Arrays.copyOfRange(variable, 0, nameLength);
        String name = ZipParsing.allowedName(rawName);
        byte[] extra = Arrays.copyOfRange(variable, nameLength, variable.length);
        long sizeMaximum = name.equals("manifest.json")
                ? ZipLimits.MAXIMUM_MANIFEST_SIZE
                : ZipLimits.MAXIMUM_ORIGINAL_SIZE;
        Zip64ExtraField.Values resolved = Zip64ExtraField.resolveLocal(
                extra, rawUncompressedSize, rawCompressedSize, sizeMaximum);
        if (resolved.usesZip64() && versionNeeded != 45) {
            throw new ZipStructureException(
                    Reason.INVALID_VERSION_NEEDED, "a local header using ZIP64 must require version 4.5");
        }

        return new Entry(
                name,
                rawName,
                versionNeeded,
                flags,
                method,
                modificationTime,
                modificationDate,
                crc32,
                rawCompressedSize,
                rawUncompressedSize,
                resolved.compressedSize(),
                resolved.uncompressedSize(),
                new ByteRange(offset, headerEnd),
                resolved.compressedFromZip64(),
                resolved.uncompressedFromZip64());
    }

    record Entry(
            String name,
            byte[] rawName,
            int versionNeeded,
            int flags,
            int method,
            int modificationTime,
            int modificationDate,
            long crc32,
            long rawCompressedSize,
            long rawUncompressedSize,
            long compressedSize,
            long uncompressedSize,
            ByteRange headerRange,
            boolean compressedSizeFromZip64,
            boolean uncompressedSizeFromZip64) {
        boolean usesZip64() {
            return compressedSizeFromZip64 || uncompressedSizeFromZip64;
        }
    }
}
