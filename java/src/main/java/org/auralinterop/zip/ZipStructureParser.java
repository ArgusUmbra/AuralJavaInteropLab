package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;
import org.auralinterop.io.BoundedReadException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import static org.auralinterop.zip.ZipStructureException.Reason;

/**
 * Direct, read-only parser for the classic and ZIP64 AURAL Draft 0.2 structural profile.
 *
 * <p>The parser does not decompress, extract, calculate CRC32, inspect JSON or WAVE data, or
 * calculate SHA-256. A successful result is therefore structural acceptance, not complete AURAL
 * package verification. One {@link BoundedFileReader} and its single read-only channel are used for
 * the complete parse.</p>
 */
public final class ZipStructureParser {
    private final ClassicEocdParser eocdParser = new ClassicEocdParser();
    private final Zip64EndParser zip64EndParser = new Zip64EndParser();
    private final CentralDirectoryParser centralDirectoryParser = new CentralDirectoryParser();
    private final LocalFileHeaderParser localFileHeaderParser = new LocalFileHeaderParser();

    public ZipArchiveStructure parse(Path archive) throws IOException {
        Objects.requireNonNull(archive, "archive");
        try (BoundedFileReader reader = BoundedFileReader.open(
                archive, ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE)) {
            try {
                return parse(reader);
            } catch (BoundedReadException failure) {
                throw new ZipStructureException(
                        Reason.INVALID_RANGE, "bounded ZIP read failed: " + failure.reason(), failure);
            }
        }
    }

    private ZipArchiveStructure parse(BoundedFileReader reader) throws IOException {
        ClassicEocdParser.Eocd classicEocd = eocdParser.parse(reader);
        Zip64EndParser.End eocd = zip64EndParser.resolve(reader, classicEocd);
        CentralDirectoryParser.Result central = centralDirectoryParser.parse(reader, eocd);

        List<CentralDirectoryParser.Entry> physicalOrder = new ArrayList<>(central.entries());
        physicalOrder.sort(Comparator.comparingLong(CentralDirectoryParser.Entry::localHeaderOffset));
        List<ParsedPair> pairs = new ArrayList<>(ZipLimits.ENTRY_COUNT);
        for (CentralDirectoryParser.Entry centralEntry : physicalOrder) {
            LocalFileHeaderParser.Entry local = localFileHeaderParser.parse(
                    reader, centralEntry.localHeaderOffset(), eocd.centralDirectoryOffset());
            validateAgreement(local, centralEntry);
            pairs.add(new ParsedPair(local, centralEntry));
        }

        long cursor = 0;
        List<ZipEntryStructure> resultEntries = new ArrayList<>(ZipLimits.ENTRY_COUNT);
        for (ParsedPair pair : pairs) {
            LocalFileHeaderParser.Entry local = pair.local();
            CentralDirectoryParser.Entry centralEntry = pair.central();
            if (local.headerRange().startInclusive() != cursor) {
                throw new ZipStructureException(
                        Reason.LAYOUT, "local entries must be contiguous and the first must start at offset zero");
            }
            long dataEnd = ZipParsing.add(
                    local.headerRange().endExclusive(),
                    centralEntry.compressedSize(),
                    Reason.LAYOUT,
                    "entry-data range overflows");
            if (dataEnd > eocd.centralDirectoryOffset()) {
                throw new ZipStructureException(Reason.LAYOUT, "entry data extends into the central directory");
            }
            ByteRange dataRange = new ByteRange(local.headerRange().endExclusive(), dataEnd);
            resultEntries.add(new ZipEntryStructure(
                    centralEntry.name(),
                    centralEntry.rawName(),
                    centralEntry.versionMadeBy(),
                    centralEntry.versionNeeded(),
                    centralEntry.flags(),
                    centralEntry.method(),
                    centralEntry.modificationTime(),
                    centralEntry.modificationDate(),
                    centralEntry.crc32(),
                    centralEntry.rawCompressedSize(),
                    centralEntry.rawUncompressedSize(),
                    centralEntry.compressedSize(),
                    centralEntry.uncompressedSize(),
                    centralEntry.rawLocalHeaderOffset(),
                    centralEntry.compressedSizeFromZip64(),
                    centralEntry.uncompressedSizeFromZip64(),
                    centralEntry.localHeaderOffsetFromZip64(),
                    centralEntry.internalAttributes(),
                    centralEntry.externalAttributes(),
                    local.headerRange(),
                    dataRange,
                    centralEntry.centralHeaderRange()));
            cursor = dataEnd;
        }
        if (cursor != eocd.centralDirectoryOffset()) {
            throw new ZipStructureException(
                    Reason.LAYOUT, "central directory must immediately follow the second entry data");
        }

        return new ZipArchiveStructure(
                eocd.archiveSize(),
                resultEntries,
                new ByteRange(eocd.centralDirectoryOffset(), eocd.terminalOffset()),
                new ByteRange(eocd.terminalOffset(), eocd.archiveSize()),
                eocd.usesZip64(),
                eocd.zip64EocdRange(),
                eocd.zip64LocatorRange());
    }

    private static void validateAgreement(
            LocalFileHeaderParser.Entry local,
            CentralDirectoryParser.Entry central) throws ZipStructureException {
        if (!local.name().equals(central.name()) || !Arrays.equals(local.rawName(), central.rawName())) {
            throw mismatch("file-name bytes");
        }
        if (local.versionNeeded() != central.versionNeeded()) {
            throw mismatch("version-needed");
        }
        if (local.flags() != central.flags()) {
            throw mismatch("general-purpose flags");
        }
        if (local.method() != central.method()) {
            throw mismatch("compression method");
        }
        if (local.modificationTime() != central.modificationTime()
                || local.modificationDate() != central.modificationDate()) {
            throw mismatch("modification timestamp");
        }
        if (local.crc32() != central.crc32()) {
            throw mismatch("CRC32 declaration");
        }
        if (local.compressedSize() != central.compressedSize()
                || local.uncompressedSize() != central.uncompressedSize()) {
            throw mismatch("declared sizes");
        }
    }

    private static ZipStructureException mismatch(String field) {
        return new ZipStructureException(Reason.HEADER_MISMATCH, "local and central " + field + " disagree");
    }

    private record ParsedPair(LocalFileHeaderParser.Entry local, CentralDirectoryParser.Entry central) {
    }
}
