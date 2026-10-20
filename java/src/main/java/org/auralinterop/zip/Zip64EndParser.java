package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;

import java.io.IOException;

import static org.auralinterop.zip.ZipStructureException.Reason;

/** Resolves classic EOCD fields and the exact Draft 0.2 ZIP64 terminal envelope. */
final class Zip64EndParser {
    private static final int ZIP64_EOCD_PHYSICAL_SIZE = 56;
    private static final long ZIP64_EOCD_DATA_SIZE = 44;
    private static final int LOCATOR_SIZE = 20;

    End resolve(BoundedFileReader reader, ClassicEocdParser.Eocd classic) throws IOException {
        if (!classic.hasZip64Sentinel()) {
            rejectUnneededTerminalRecords(reader, classic);
            validateClassic(classic);
            return new End(
                    classic.centralDirectoryOffset(),
                    classic.centralDirectorySize(),
                    classic.eocdOffset(),
                    classic.archiveSize(),
                    false,
                    null,
                    null);
        }

        long locatorOffset = classic.eocdOffset() - LOCATOR_SIZE;
        if (locatorOffset < 0) {
            throw new ZipStructureException(Reason.ZIP64_LOCATOR, "ZIP64 locator is truncated or missing");
        }
        byte[] locator = reader.readExact(locatorOffset, LOCATOR_SIZE);
        if (ZipParsing.unsignedInt(locator, 0) != ZipParsing.ZIP64_LOCATOR_SIGNATURE) {
            throw new ZipStructureException(Reason.ZIP64_LOCATOR, "ZIP64 locator signature is missing or invalid");
        }
        long locatorDisk = ZipParsing.unsignedInt(locator, 4);
        long zip64Offset = ZipParsing.boundedUnsignedLong(
                locator, 8, ZipLimits.MAXIMUM_ARCHIVE_SIZE, "ZIP64 EOCD locator offset");
        long totalDisks = ZipParsing.unsignedInt(locator, 16);
        if (locatorDisk != 0 || totalDisks != 1) {
            throw new ZipStructureException(Reason.MULTI_DISK, "ZIP64 locator must describe exactly one disk");
        }

        long expectedZip64Offset = locatorOffset - ZIP64_EOCD_PHYSICAL_SIZE;
        if (expectedZip64Offset < 0 || zip64Offset != expectedZip64Offset) {
            throw new ZipStructureException(
                    Reason.ZIP64_LOCATOR,
                    "ZIP64 locator must reference the immediately preceding fixed-size ZIP64 EOCD");
        }
        byte[] record = reader.readExact(zip64Offset, ZIP64_EOCD_PHYSICAL_SIZE);
        if (ZipParsing.unsignedInt(record, 0) != ZipParsing.ZIP64_EOCD_SIGNATURE) {
            throw new ZipStructureException(Reason.ZIP64_EOCD, "invalid ZIP64 EOCD signature");
        }
        long recordSize = ZipParsing.boundedUnsignedLong(
                record, 4, ZipLimits.MAXIMUM_ARCHIVE_SIZE, "ZIP64 EOCD record-size field");
        if (recordSize != ZIP64_EOCD_DATA_SIZE) {
            throw new ZipStructureException(
                    Reason.ZIP64_EOCD,
                    "ZIP64 EOCD record-size field must be exactly 44 (56 physical bytes)");
        }

        int versionMadeBy = ZipParsing.unsignedShort(record, 12);
        int versionNeeded = ZipParsing.unsignedShort(record, 14);
        if ((versionMadeBy & 0xff) < 45) {
            throw new ZipStructureException(
                    Reason.ZIP64_EOCD, "ZIP64 EOCD version-made-by must advertise ZIP 4.5 or later");
        }
        if (versionNeeded != 45) {
            throw new ZipStructureException(
                    Reason.INVALID_VERSION_NEEDED, "ZIP64 EOCD version-needed must be 45");
        }
        long diskNumber = ZipParsing.unsignedInt(record, 16);
        long centralDisk = ZipParsing.unsignedInt(record, 20);
        long entriesOnDisk = ZipParsing.boundedUnsignedLong(record, 24, ZipLimits.ENTRY_COUNT, "ZIP64 entries on disk");
        long totalEntries = ZipParsing.boundedUnsignedLong(record, 32, ZipLimits.ENTRY_COUNT, "ZIP64 total entries");
        long centralSize = ZipParsing.boundedUnsignedLong(
                record, 40, ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE, "ZIP64 central-directory size");
        long centralOffset = ZipParsing.boundedUnsignedLong(
                record, 48, ZipLimits.MAXIMUM_ARCHIVE_SIZE, "ZIP64 central-directory offset");
        if (diskNumber != 0 || centralDisk != 0) {
            throw new ZipStructureException(Reason.MULTI_DISK, "ZIP64 EOCD disk fields must resolve to disk zero");
        }
        if (entriesOnDisk != ZipLimits.ENTRY_COUNT || totalEntries != ZipLimits.ENTRY_COUNT
                || entriesOnDisk != totalEntries) {
            throw new ZipStructureException(Reason.ENTRY_COUNT, "ZIP64 EOCD must consistently declare two entries");
        }
        requireClassicAgreement(classic, diskNumber, centralDisk, entriesOnDisk, totalEntries, centralSize, centralOffset);
        long centralEnd = ZipParsing.add(
                centralOffset, centralSize, Reason.CENTRAL_DIRECTORY_BOUNDS,
                "resolved ZIP64 central-directory range overflows");
        if (centralEnd != zip64Offset) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_BOUNDS,
                    "central directory must end immediately before the ZIP64 EOCD");
        }
        return new End(
                centralOffset,
                centralSize,
                zip64Offset,
                classic.archiveSize(),
                true,
                new ByteRange(zip64Offset, locatorOffset),
                new ByteRange(locatorOffset, classic.eocdOffset()));
    }

    private static void rejectUnneededTerminalRecords(
            BoundedFileReader reader, ClassicEocdParser.Eocd classic) throws IOException {
        if (classic.eocdOffset() < LOCATOR_SIZE) {
            return;
        }
        byte[] signature = reader.readExact(classic.eocdOffset() - LOCATOR_SIZE, Integer.BYTES);
        if (ZipParsing.unsignedInt(signature, 0) == ZipParsing.ZIP64_LOCATOR_SIGNATURE) {
            throw new ZipStructureException(
                    Reason.ZIP64_LOCATOR,
                    "experimental policy rejects ZIP64 terminal records when the classic EOCD has no sentinel");
        }
    }

    private static void validateClassic(ClassicEocdParser.Eocd classic) throws ZipStructureException {
        if (classic.diskNumber() != 0 || classic.centralDirectoryDisk() != 0) {
            throw new ZipStructureException(Reason.MULTI_DISK, "classic EOCD must describe disk zero");
        }
        if (classic.entriesOnDisk() != ZipLimits.ENTRY_COUNT
                || classic.totalEntries() != ZipLimits.ENTRY_COUNT
                || classic.entriesOnDisk() != classic.totalEntries()) {
            throw new ZipStructureException(Reason.ENTRY_COUNT, "classic EOCD must consistently declare two entries");
        }
        if (classic.centralDirectorySize() > ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_LIMIT, "central directory exceeds the 4 MiB limit");
        }
        long centralEnd = ZipParsing.add(
                classic.centralDirectoryOffset(), classic.centralDirectorySize(),
                Reason.CENTRAL_DIRECTORY_BOUNDS, "central-directory range overflows");
        if (centralEnd != classic.eocdOffset()) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_BOUNDS,
                    "central directory must end immediately before the classic EOCD");
        }
    }

    private static void requireClassicAgreement(
            ClassicEocdParser.Eocd classic,
            long diskNumber,
            long centralDisk,
            long entriesOnDisk,
            long totalEntries,
            long centralSize,
            long centralOffset) throws ZipStructureException {
        require16(classic.diskNumber(), diskNumber, "current disk");
        require16(classic.centralDirectoryDisk(), centralDisk, "central-directory disk");
        require16(classic.entriesOnDisk(), entriesOnDisk, "entries on disk");
        require16(classic.totalEntries(), totalEntries, "total entries");
        require32(classic.centralDirectorySize(), centralSize, "central-directory size");
        require32(classic.centralDirectoryOffset(), centralOffset, "central-directory offset");
    }

    private static void require16(int classic, long resolved, String field) throws ZipStructureException {
        if (classic != ZipParsing.UINT16_SENTINEL && classic != resolved) {
            throw new ZipStructureException(Reason.ZIP64_EOCD, "classic and ZIP64 " + field + " disagree");
        }
    }

    private static void require32(long classic, long resolved, String field) throws ZipStructureException {
        if (classic != ZipParsing.UINT32_SENTINEL && classic != resolved) {
            throw new ZipStructureException(Reason.ZIP64_EOCD, "classic and ZIP64 " + field + " disagree");
        }
    }

    record End(
            long centralDirectoryOffset,
            long centralDirectorySize,
            long terminalOffset,
            long archiveSize,
            boolean usesZip64,
            ByteRange zip64EocdRange,
            ByteRange zip64LocatorRange) {
    }
}
