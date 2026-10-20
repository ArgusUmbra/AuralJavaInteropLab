package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;

import java.io.IOException;

import static org.auralinterop.zip.ZipStructureException.Reason;

final class ClassicEocdParser {
    private static final int FIXED_SIZE = 22;
    private static final int MAXIMUM_TAIL = FIXED_SIZE + 0xffff;

    Eocd parse(BoundedFileReader reader) throws IOException {
        long archiveSize = reader.size();
        if (archiveSize > ZipLimits.MAXIMUM_ARCHIVE_SIZE) {
            throw new ZipStructureException(Reason.ARCHIVE_TOO_LARGE, "archive exceeds the Draft 0.2 physical limit");
        }
        if (archiveSize < FIXED_SIZE) {
            throw new ZipStructureException(Reason.EOCD_TRUNCATED, "archive is shorter than a classic EOCD");
        }

        long eocdOffset = archiveSize - FIXED_SIZE;
        byte[] fixed = reader.readExact(eocdOffset, FIXED_SIZE);
        if (ZipParsing.unsignedInt(fixed, 0) != ZipParsing.EOCD_SIGNATURE) {
            classifyMissingTerminalRecord(reader, archiveSize);
        }
        int commentLength = ZipParsing.unsignedShort(fixed, 20);
        if (commentLength != 0) {
            throw new ZipStructureException(Reason.ARCHIVE_COMMENT, "AURAL requires an empty ZIP comment");
        }

        int diskNumber = ZipParsing.unsignedShort(fixed, 4);
        int centralDirectoryDisk = ZipParsing.unsignedShort(fixed, 6);
        int entriesOnDisk = ZipParsing.unsignedShort(fixed, 8);
        int totalEntries = ZipParsing.unsignedShort(fixed, 10);
        long centralDirectorySize = ZipParsing.unsignedInt(fixed, 12);
        long centralDirectoryOffset = ZipParsing.unsignedInt(fixed, 16);

        if ((diskNumber != 0 && diskNumber != ZipParsing.UINT16_SENTINEL)
                || (centralDirectoryDisk != 0 && centralDirectoryDisk != ZipParsing.UINT16_SENTINEL)) {
            throw new ZipStructureException(Reason.MULTI_DISK, "multi-disk ZIP metadata is prohibited");
        }
        if (diskNumber == ZipParsing.UINT16_SENTINEL
                || centralDirectoryDisk == ZipParsing.UINT16_SENTINEL
                || entriesOnDisk == ZipParsing.UINT16_SENTINEL
                || totalEntries == ZipParsing.UINT16_SENTINEL
                || centralDirectorySize == ZipParsing.UINT32_SENTINEL
                || centralDirectoryOffset == ZipParsing.UINT32_SENTINEL) {
            throw new ZipStructureException(
                    Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE,
                    "classic EOCD contains a ZIP64 sentinel; ZIP64 parsing is deferred to Assignment 7E");
        }
        if (entriesOnDisk != ZipLimits.ENTRY_COUNT || totalEntries != ZipLimits.ENTRY_COUNT) {
            throw new ZipStructureException(Reason.ENTRY_COUNT, "classic EOCD must declare exactly two entries");
        }
        if (entriesOnDisk != totalEntries) {
            throw new ZipStructureException(Reason.ENTRY_COUNT, "EOCD entry counts disagree");
        }
        if (centralDirectorySize > ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_LIMIT, "central directory exceeds the 4 MiB limit");
        }
        long centralDirectoryEnd = ZipParsing.add(
                centralDirectoryOffset,
                centralDirectorySize,
                Reason.CENTRAL_DIRECTORY_BOUNDS,
                "central-directory range overflows");
        if (centralDirectoryEnd != eocdOffset) {
            throw new ZipStructureException(
                    Reason.CENTRAL_DIRECTORY_BOUNDS,
                    "central directory must end immediately before the terminal EOCD");
        }
        return new Eocd(
                centralDirectoryOffset,
                centralDirectorySize,
                eocdOffset,
                archiveSize);
    }

    private static void classifyMissingTerminalRecord(BoundedFileReader reader, long archiveSize) throws IOException {
        int tailLength = (int) Math.min(archiveSize, MAXIMUM_TAIL);
        long tailOffset = archiveSize - tailLength;
        byte[] tail = reader.readExact(tailOffset, tailLength);
        for (int index = tail.length - FIXED_SIZE; index >= 0; index--) {
            if (ZipParsing.unsignedInt(tail, index) != ZipParsing.EOCD_SIGNATURE) {
                continue;
            }
            int commentLength;
            try {
                commentLength = ZipParsing.unsignedShort(tail, index + 20);
            } catch (ZipStructureException ignored) {
                continue;
            }
            long candidateEnd = tailOffset + index + FIXED_SIZE + commentLength;
            if (candidateEnd == archiveSize && commentLength != 0) {
                throw new ZipStructureException(Reason.ARCHIVE_COMMENT, "AURAL requires an empty ZIP comment");
            }
            if (candidateEnd <= archiveSize) {
                throw new ZipStructureException(Reason.TRAILING_DATA, "bytes follow an EOCD candidate");
            }
        }
        throw new ZipStructureException(Reason.EOCD_NOT_FOUND, "terminal classic EOCD signature not found");
    }

    record Eocd(
            long centralDirectoryOffset,
            long centralDirectorySize,
            long eocdOffset,
            long archiveSize) {
    }
}
