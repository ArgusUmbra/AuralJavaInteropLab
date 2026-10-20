package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.auralinterop.zip.ZipStructureException.Reason;

final class CentralDirectoryParser {
    private static final int FIXED_SIZE = 46;

    Result parse(BoundedFileReader reader, Zip64EndParser.End eocd) throws IOException {
        byte[] directory = reader.readExact(eocd.centralDirectoryOffset(), eocd.centralDirectorySize());
        List<Entry> entries = new ArrayList<>(ZipLimits.ENTRY_COUNT);
        Set<String> names = new HashSet<>();
        int cursor = 0;

        for (int entryIndex = 0; entryIndex < ZipLimits.ENTRY_COUNT; entryIndex++) {
            ZipParsing.require(directory, cursor, FIXED_SIZE);
            if (ZipParsing.unsignedInt(directory, cursor) != ZipParsing.CENTRAL_SIGNATURE) {
                throw new ZipStructureException(
                        Reason.CENTRAL_DIRECTORY_SIGNATURE,
                        "invalid central-directory signature for entry " + entryIndex);
            }

            int nameLength = ZipParsing.unsignedShort(directory, cursor + 28);
            int extraLength = ZipParsing.unsignedShort(directory, cursor + 30);
            int commentLength = ZipParsing.unsignedShort(directory, cursor + 32);
            int recordLength = ZipParsing.addInt(
                    FIXED_SIZE,
                    nameLength,
                    Reason.CENTRAL_DIRECTORY_LENGTH,
                    "central header length overflows");
            recordLength = ZipParsing.addInt(
                    recordLength,
                    extraLength,
                    Reason.CENTRAL_DIRECTORY_LENGTH,
                    "central header length overflows");
            recordLength = ZipParsing.addInt(
                    recordLength,
                    commentLength,
                    Reason.CENTRAL_DIRECTORY_LENGTH,
                    "central header length overflows");
            ZipParsing.require(directory, cursor, recordLength);
            if (commentLength != 0) {
                throw new ZipStructureException(Reason.ENTRY_COMMENT, "ZIP entry comments are prohibited");
            }

            int versionMadeBy = ZipParsing.unsignedShort(directory, cursor + 4);
            int versionNeeded = ZipParsing.unsignedShort(directory, cursor + 6);
            int flags = ZipParsing.unsignedShort(directory, cursor + 8);
            int method = ZipParsing.unsignedShort(directory, cursor + 10);
            int modificationTime = ZipParsing.unsignedShort(directory, cursor + 12);
            int modificationDate = ZipParsing.unsignedShort(directory, cursor + 14);
            long crc32 = ZipParsing.unsignedInt(directory, cursor + 16);
            long rawCompressedSize = ZipParsing.unsignedInt(directory, cursor + 20);
            long rawUncompressedSize = ZipParsing.unsignedInt(directory, cursor + 24);
            int diskStart = ZipParsing.unsignedShort(directory, cursor + 34);
            int internalAttributes = ZipParsing.unsignedShort(directory, cursor + 36);
            long externalAttributes = ZipParsing.unsignedInt(directory, cursor + 38);
            long rawLocalHeaderOffset = ZipParsing.unsignedInt(directory, cursor + 42);

            ZipParsing.validateVersionNeeded(versionNeeded);
            ZipParsing.validateFlags(flags);
            ZipParsing.validateMethod(method);
            ZipParsing.validateDosTimestamp(modificationTime, modificationDate);
            if (diskStart != 0 && diskStart != ZipParsing.UINT16_SENTINEL) {
                throw new ZipStructureException(Reason.MULTI_DISK, "entry starts on a nonzero disk");
            }
            ZipParsing.validateExternalAttributes(externalAttributes);

            int nameStart = cursor + FIXED_SIZE;
            byte[] rawName = Arrays.copyOfRange(directory, nameStart, nameStart + nameLength);
            String name = ZipParsing.allowedName(rawName);
            if (!names.add(name)) {
                throw new ZipStructureException(Reason.DUPLICATE_NAME, "duplicate ZIP entry name: " + name);
            }
            int extraStart = nameStart + nameLength;
            byte[] extra = Arrays.copyOfRange(directory, extraStart, extraStart + extraLength);
            long sizeMaximum = name.equals("manifest.json")
                    ? ZipLimits.MAXIMUM_MANIFEST_SIZE
                    : ZipLimits.MAXIMUM_ORIGINAL_SIZE;
            Zip64ExtraField.Values resolved = Zip64ExtraField.resolveCentral(
                    extra,
                    rawUncompressedSize,
                    rawCompressedSize,
                    rawLocalHeaderOffset,
                    diskStart,
                    sizeMaximum,
                    eocd.archiveSize());
            if (resolved.usesZip64() && versionNeeded != 45) {
                throw new ZipStructureException(
                        Reason.INVALID_VERSION_NEEDED, "an entry using ZIP64 must require version 4.5");
            }
            validateSizes(name, resolved.compressedSize(), resolved.uncompressedSize());
            long absoluteStart = eocd.centralDirectoryOffset() + cursor;
            entries.add(new Entry(
                    name,
                    rawName,
                    versionMadeBy,
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
                    resolved.diskStart(),
                    internalAttributes,
                    externalAttributes,
                    rawLocalHeaderOffset,
                    resolved.localHeaderOffset(),
                    resolved.compressedFromZip64(),
                    resolved.uncompressedFromZip64(),
                    resolved.offsetFromZip64(),
                    new ByteRange(absoluteStart, absoluteStart + recordLength)));
            cursor += recordLength;
        }

        if (cursor != directory.length) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_LENGTH,
                    "central-directory size does not equal its two declared file headers");
        }
        if (!names.contains("manifest.json") || !names.contains("audio/original.wav")) {
            throw new ZipStructureException(Reason.INVALID_NAME, "both required exact entry names are required");
        }
        return new Result(List.copyOf(entries));
    }

    private static void validateSizes(String name, long compressedSize, long uncompressedSize)
            throws ZipStructureException {
        if (compressedSize != uncompressedSize) {
            throw new ZipStructureException(Reason.HEADER_MISMATCH, "stored entry sizes must be equal");
        }
        long maximum = name.equals("manifest.json")
                ? ZipLimits.MAXIMUM_MANIFEST_SIZE
                : ZipLimits.MAXIMUM_ORIGINAL_SIZE;
        if (uncompressedSize > maximum) {
            throw new ZipStructureException(Reason.SIZE_LIMIT, name + " exceeds its declared size limit");
        }
    }

    record Result(List<Entry> entries) {
    }

    record Entry(
            String name,
            byte[] rawName,
            int versionMadeBy,
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
            int diskStart,
            int internalAttributes,
            long externalAttributes,
            long rawLocalHeaderOffset,
            long localHeaderOffset,
            boolean compressedSizeFromZip64,
            boolean uncompressedSizeFromZip64,
            boolean localHeaderOffsetFromZip64,
            ByteRange centralHeaderRange) {
    }
}
