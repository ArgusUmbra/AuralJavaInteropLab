package org.auralinterop.tests;

import org.auralinterop.zip.ZipArchiveStructure;
import org.auralinterop.zip.ZipEntryStructure;
import org.auralinterop.zip.ZipLimits;
import org.auralinterop.zip.ZipStructureException;
import org.auralinterop.zip.ZipStructureParser;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;

final class ZipStructureParserTest {
    private static final int LOCAL_FIXED = 30;
    private static final int CENTRAL_FIXED = 46;
    private static final int EOCD_FIXED = 22;
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final ZipStructureParser PARSER = new ZipStructureParser();

    private ZipStructureParserTest() {
    }

    static void register(Path fixtureDirectory) {
        validCases();
        eocdCases();
        centralDirectoryCases();
        localHeaderCases();
        layoutCases();
        zip64BoundaryCases();
        publicFixtureSmokeCases(fixtureDirectory);
    }

    private static void validCases() {
        TestSupport.test("ZIP valid minimal classic archive", () -> {
            Build archive = new Builder().build();
            ZipArchiveStructure result = parse(archive.bytes());
            TestSupport.assertEquals(2, result.entries().size());
            TestSupport.assertEquals("manifest.json", result.entries().get(0).name());
            TestSupport.assertEquals("audio/original.wav", result.entries().get(1).name());
        });
        TestSupport.test("ZIP valid audio-first physical order", () -> {
            Builder builder = new Builder();
            builder.entries = new ArrayList<>(List.of(Entry.audio(), Entry.manifest()));
            builder.centralOrder = new ArrayList<>(List.of(0, 1));
            ZipArchiveStructure result = parse(builder.build().bytes());
            TestSupport.assertEquals("audio/original.wav", result.entries().get(0).name());
            TestSupport.assertEquals(0L, result.entries().get(0).localHeaderRange().startInclusive());
        });
        TestSupport.test("ZIP valid reversed central-directory order", () -> {
            Builder builder = new Builder();
            builder.centralOrder = new ArrayList<>(List.of(1, 0));
            ZipArchiveStructure result = parse(builder.build().bytes());
            TestSupport.assertEquals("manifest.json", result.entries().get(0).name());
            TestSupport.assertTrue(
                    result.entry("audio/original.wav").centralHeaderRange().startInclusive()
                            < result.entry("manifest.json").centralHeaderRange().startInclusive(),
                    "central order should be preserved in physical ranges, not result ordering");
        });
        TestSupport.test("ZIP valid both physical and central orders reversed", () -> {
            Builder builder = new Builder();
            builder.entries = new ArrayList<>(List.of(Entry.audio(), Entry.manifest()));
            builder.centralOrder = new ArrayList<>(List.of(1, 0));
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid UTF-8 name flag", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> entry.flags = 1 << 11);
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid version-needed 2.0", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> entry.versionNeeded = 20);
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid version-needed 4.5 without ZIP64", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> entry.versionNeeded = 45);
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid Unix creator regular file permissions", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> {
                entry.versionMadeBy = (3 << 8) | 20;
                entry.externalAttributes = 0x81a4_0000L;
            });
            ZipArchiveStructure result = parse(builder.build().bytes());
            TestSupport.assertEquals(3, result.entries().get(0).creatorSystem());
        });
        TestSupport.test("ZIP valid arbitrary creator and internal attributes", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> {
                entry.versionMadeBy = (19 << 8) | 63;
                entry.internalAttributes = 1;
            });
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid zero-length stored entries structurally", () -> {
            Builder builder = new Builder();
            builder.entries.forEach(entry -> entry.data = new byte[0]);
            assertAccept(builder.build().bytes());
        });
        TestSupport.test("ZIP valid manifest exact size boundary", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).data = new byte[(int) ZipLimits.MAXIMUM_MANIFEST_SIZE];
            ZipArchiveStructure result = parse(builder.build().bytes());
            TestSupport.assertEquals(
                    ZipLimits.MAXIMUM_MANIFEST_SIZE, result.entry("manifest.json").uncompressedSize());
        });
        TestSupport.test("ZIP structural ranges are exact and contiguous", () -> {
            ZipArchiveStructure result = parse(new Builder().build().bytes());
            ZipEntryStructure first = result.entries().get(0);
            ZipEntryStructure second = result.entries().get(1);
            TestSupport.assertEquals(0L, first.localHeaderRange().startInclusive());
            TestSupport.assertEquals(first.dataRange().endExclusive(), second.localHeaderRange().startInclusive());
            TestSupport.assertEquals(second.dataRange().endExclusive(), result.centralDirectoryRange().startInclusive());
            TestSupport.assertEquals(
                    result.centralDirectoryRange().endExclusive(),
                    result.endOfCentralDirectoryRange().startInclusive());
            TestSupport.assertEquals(result.archiveSize(), result.endOfCentralDirectoryRange().endExclusive());
        });
        TestSupport.test("ZIP structural result collections and raw names are protected", () -> {
            ZipArchiveStructure result = parse(new Builder().build().bytes());
            TestSupport.expectThrows(
                    UnsupportedOperationException.class,
                    () -> result.entries().add(result.entries().get(0)));
            byte[] first = result.entries().get(0).rawName();
            first[0] = 'X';
            TestSupport.assertEquals((byte) 'm', result.entries().get(0).rawName()[0]);
        });
        TestSupport.test("ZIP CRC declaration is exposed but not computed structurally", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.localOffsets()[0] + 14, 0x1234_5678L);
            put32(archive.bytes(), archive.centralOffsets()[0] + 16, 0x1234_5678L);
            ZipArchiveStructure result = parse(archive.bytes());
            TestSupport.assertEquals(0x1234_5678L, result.entry("manifest.json").crc32());
        });
    }

    private static void eocdCases() {
        TestSupport.test("ZIP missing EOCD", () -> {
            Build archive = new Builder().build();
            assertReject(Arrays.copyOf(archive.bytes(), archive.eocdOffset()), ZipStructureException.Reason.EOCD_NOT_FOUND);
        });
        TestSupport.test("ZIP truncated EOCD", () -> {
            Build archive = new Builder().build();
            assertReject(Arrays.copyOf(archive.bytes(), archive.bytes().length - 7), null);
        });
        TestSupport.test("ZIP invalid terminal EOCD signature", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset(), 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.EOCD_NOT_FOUND);
        });
        TestSupport.test("ZIP archive comment prohibited", () -> {
            Builder builder = new Builder();
            builder.archiveComment = bytes("x");
            assertReject(builder.build().bytes(), ZipStructureException.Reason.ARCHIVE_COMMENT);
        });
        TestSupport.test("ZIP nonzero current disk rejected", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.eocdOffset() + 4, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.MULTI_DISK);
        });
        TestSupport.test("ZIP nonzero central-directory disk rejected", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.eocdOffset() + 6, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.MULTI_DISK);
        });
        TestSupport.test("ZIP incorrect entries-on-disk count", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.eocdOffset() + 8, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.ENTRY_COUNT);
        });
        TestSupport.test("ZIP incorrect total entry count", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.eocdOffset() + 10, 3);
            assertReject(archive.bytes(), ZipStructureException.Reason.ENTRY_COUNT);
        });
        TestSupport.test("ZIP incorrect central-directory size", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset() + 12, archive.centralSize() - 1L);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_BOUNDS);
        });
        TestSupport.test("ZIP incorrect central-directory offset", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset() + 16, archive.centralDirectoryOffset() + 1L);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_BOUNDS);
        });
        TestSupport.test("ZIP central-directory declared size limit", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset() + 12, ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE + 1L);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_LIMIT);
        });
        TestSupport.test("ZIP duplicated terminal structure rejected", () -> {
            Build archive = new Builder().build();
            byte[] duplicate = insert(
                    archive.bytes(), archive.eocdOffset(),
                    Arrays.copyOfRange(archive.bytes(), archive.eocdOffset(), archive.bytes().length));
            assertReject(duplicate, ZipStructureException.Reason.CENTRAL_DIRECTORY_BOUNDS);
        });
        TestSupport.test("ZIP physical archive size limit checked before parsing", () -> {
            Path file = Files.createTempFile("aural-zip-oversize-", ".aural");
            try {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
                    channel.position(ZipLimits.MAXIMUM_ARCHIVE_SIZE);
                    channel.write(ByteBuffer.wrap(new byte[] {0}));
                }
                ZipStructureException failure = TestSupport.expectThrows(
                        ZipStructureException.class, () -> PARSER.parse(file));
                TestSupport.assertEquals(ZipStructureException.Reason.ARCHIVE_TOO_LARGE, failure.reason());
            } finally {
                Files.deleteIfExists(file);
            }
        });
    }

    private static void centralDirectoryCases() {
        TestSupport.test("ZIP invalid first central signature", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[0], 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_SIGNATURE);
        });
        TestSupport.test("ZIP invalid second central signature", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[1], 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_SIGNATURE);
        });
        TestSupport.test("ZIP central filename declared length truncation", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.centralOffsets()[1] + 28, 0xffff);
            assertReject(archive.bytes(), ZipStructureException.Reason.CENTRAL_DIRECTORY_LENGTH);
        });
        TestSupport.test("ZIP duplicate central names", () -> {
            Builder builder = new Builder();
            builder.entries.set(1, Entry.manifest());
            assertReject(builder.build().bytes(), ZipStructureException.Reason.DUPLICATE_NAME);
        });
        TestSupport.test("ZIP unexpected third entry", () -> {
            Builder builder = new Builder();
            builder.entries.add(new Entry("other.bin", bytes("x")));
            builder.centralOrder.add(2);
            assertReject(builder.build().bytes(), ZipStructureException.Reason.ENTRY_COUNT);
        });
        TestSupport.test("ZIP unexpected exact raw name", () -> {
            Builder builder = new Builder();
            builder.entries.set(0, new Entry("Manifest.json", bytes("{}")));
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_NAME);
        });
        TestSupport.test("ZIP empty central raw name", () -> {
            Builder builder = new Builder();
            builder.entries.set(0, new Entry("", bytes("{}")));
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_NAME);
        });
        TestSupport.test("ZIP embedded NUL raw name", () -> {
            Builder builder = new Builder();
            Entry entry = Entry.manifest();
            entry.name = new byte[] {'m', 'a', 'n', 0, 'f', 'e', 's', 't'};
            builder.entries.set(0, entry);
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_NAME);
        });
        TestSupport.test("ZIP unsupported central compression", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).method = 8;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.UNSUPPORTED_COMPRESSION);
        });
        TestSupport.test("ZIP encryption flag prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).flags = 1;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.UNSUPPORTED_FLAGS);
        });
        TestSupport.test("ZIP data-descriptor flag prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).flags = 1 << 3;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.UNSUPPORTED_FLAGS);
        });
        TestSupport.test("ZIP unsupported flag bit prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).flags = 1 << 6;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.UNSUPPORTED_FLAGS);
        });
        TestSupport.test("ZIP invalid version-needed value", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).versionNeeded = 11;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_VERSION_NEEDED);
        });
        TestSupport.test("ZIP invalid DOS time", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).modificationTime = 31 << 11;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_TIMESTAMP);
        });
        TestSupport.test("ZIP invalid DOS date", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).modificationDate = (2 << 5) | 30;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.INVALID_TIMESTAMP);
        });
        TestSupport.test("ZIP central entry comment prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).comment = bytes("comment");
            assertReject(builder.build().bytes(), ZipStructureException.Reason.ENTRY_COMMENT);
        });
        TestSupport.test("ZIP central non-ZIP64 extra field prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).centralExtra = extra(0x5455, new byte[] {0});
            assertReject(builder.build().bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
        TestSupport.test("ZIP unnecessary central ZIP64 extra prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).centralExtra = extra(1, new byte[16]);
            assertReject(builder.build().bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
        TestSupport.test("ZIP DOS directory attribute prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).externalAttributes = 0x10;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.FILE_TYPE);
        });
        TestSupport.test("ZIP Unix symlink type prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).externalAttributes = 0xa1ff_0000L;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.FILE_TYPE);
        });
        TestSupport.test("ZIP Unix directory type prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).externalAttributes = 0x41ed_0000L;
            assertReject(builder.build().bytes(), ZipStructureException.Reason.FILE_TYPE);
        });
        TestSupport.test("ZIP stored compressed and uncompressed sizes differ", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, 1);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, 2);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP manifest declared size exceeds limit", () -> {
            Build archive = new Builder().build();
            long excessive = ZipLimits.MAXIMUM_MANIFEST_SIZE + 1;
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, excessive);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, excessive);
            assertReject(archive.bytes(), ZipStructureException.Reason.SIZE_LIMIT);
        });
        TestSupport.test("ZIP entry starts on another disk", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.centralOffsets()[0] + 34, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.MULTI_DISK);
        });
    }

    private static void localHeaderCases() {
        TestSupport.test("ZIP invalid first local signature", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.localOffsets()[0], 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_SIGNATURE);
        });
        TestSupport.test("ZIP invalid second local signature", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.localOffsets()[1], 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_SIGNATURE);
        });
        TestSupport.test("ZIP local filename length overruns into central directory", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.localOffsets()[1] + 26, 0xffff);
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_LENGTH);
        });
        TestSupport.test("ZIP local and central filename mismatch", () -> {
            Build archive = new Builder().build();
            archive.bytes()[archive.localOffsets()[0] + LOCAL_FIXED] = 'M';
            assertReject(archive.bytes(), ZipStructureException.Reason.INVALID_NAME);
        });
        TestSupport.test("ZIP local and central CRC mismatch", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.localOffsets()[0] + 14, 0);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local and central compression mismatch", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.localOffsets()[0] + 8, 8);
            assertReject(archive.bytes(), ZipStructureException.Reason.UNSUPPORTED_COMPRESSION);
        });
        TestSupport.test("ZIP local and central flags mismatch", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.localOffsets()[0] + 6, 1 << 11);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local and central version-needed mismatch", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.localOffsets()[0] + 4, 20);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local and central timestamp mismatch", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.localOffsets()[0] + 10, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local and central compressed size mismatch", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.localOffsets()[0] + 18, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local non-ZIP64 extra field prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).localExtra = extra(0x5455, new byte[] {0});
            assertReject(builder.build().bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
        TestSupport.test("ZIP unnecessary local ZIP64 extra prohibited", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).localExtra = extra(1, new byte[16]);
            assertReject(builder.build().bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
    }

    private static void layoutCases() {
        TestSupport.test("ZIP archive prefix prohibited even with adjusted offsets", () -> {
            Builder builder = new Builder();
            builder.prefix = bytes("MZ");
            assertReject(builder.build().bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP undeclared gap between entries", () -> {
            Builder builder = new Builder();
            builder.gapAfterFirst = bytes("gap");
            assertReject(builder.build().bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP unexpected hidden local entry", () -> {
            Builder builder = new Builder();
            builder.entries.add(1, new Entry("hidden.bin", bytes("hidden")));
            builder.centralOrder = new ArrayList<>(List.of(0, 2));
            assertReject(builder.build().bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP arbitrary trailing bytes", () -> {
            Build archive = new Builder().build();
            assertReject(insert(archive.bytes(), archive.bytes().length, bytes("trailer")),
                    ZipStructureException.Reason.TRAILING_DATA);
        });
        TestSupport.test("ZIP local offset points into entry data", () -> {
            Build archive = new Builder().build();
            long firstData = archive.localOffsets()[0] + LOCAL_FIXED + "manifest.json".length();
            put32(archive.bytes(), archive.centralOffsets()[1] + 42, firstData);
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_SIGNATURE);
        });
        TestSupport.test("ZIP local offset points into another local header", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[1] + 42, archive.localOffsets()[0]);
            assertReject(archive.bytes(), ZipStructureException.Reason.HEADER_MISMATCH);
        });
        TestSupport.test("ZIP local offset points at central directory", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[1] + 42, archive.centralDirectoryOffset());
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_LENGTH);
        });
        TestSupport.test("ZIP high-bit local offset is parsed unsigned and rejected by bounds", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[1] + 42, 0x8000_0000L);
            assertReject(archive.bytes(), ZipStructureException.Reason.LOCAL_HEADER_LENGTH);
        });
        TestSupport.test("ZIP entry-data overlap with next header", () -> {
            Build archive = new Builder().build();
            long size = archive.entries().get(0).data.length + 1L;
            put32(archive.bytes(), archive.localOffsets()[0] + 18, size);
            put32(archive.bytes(), archive.localOffsets()[0] + 22, size);
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, size);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, size);
            assertReject(archive.bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP second entry data overruns central directory", () -> {
            Build archive = new Builder().build();
            long size = archive.entries().get(1).data.length + 1L;
            put32(archive.bytes(), archive.localOffsets()[1] + 18, size);
            put32(archive.bytes(), archive.localOffsets()[1] + 22, size);
            put32(archive.bytes(), archive.centralOffsets()[1] + 20, size);
            put32(archive.bytes(), archive.centralOffsets()[1] + 24, size);
            assertReject(archive.bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP undeclared descriptor-shaped bytes form a gap", () -> {
            Builder builder = new Builder();
            ByteArrayOutputStream descriptor = new ByteArrayOutputStream();
            write32(descriptor, 0x08074b50L);
            write32(descriptor, 0);
            write32(descriptor, 0);
            write32(descriptor, 0);
            builder.gapAfterFirst = descriptor.toByteArray();
            assertReject(builder.build().bytes(), ZipStructureException.Reason.LAYOUT);
        });
        TestSupport.test("ZIP central directory contains undeclared bytes", () -> {
            Build archive = new Builder().build();
            int endOfFirst = archive.centralOffsets()[0] + CENTRAL_FIXED + "manifest.json".length();
            archive.bytes()[endOfFirst] = 0;
            put16(archive.bytes(), archive.centralOffsets()[0] + 30, 1);
            assertReject(archive.bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
    }

    private static void zip64BoundaryCases() {
        TestSupport.test("ZIP64 EOCD entry-count sentinel is controlled unsupported", () -> {
            Build archive = new Builder().build();
            put16(archive.bytes(), archive.eocdOffset() + 8, 0xffff);
            put16(archive.bytes(), archive.eocdOffset() + 10, 0xffff);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 EOCD central-size sentinel is controlled unsupported", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset() + 12, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 EOCD central-offset sentinel is controlled unsupported", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.eocdOffset() + 16, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 central size sentinel with exact extra is controlled unsupported", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).centralExtra = extra(1, new byte[16]);
            Build archive = builder.build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, 0xffff_ffffL);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 central local-offset sentinel with exact extra is controlled unsupported", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).centralExtra = extra(1, new byte[8]);
            Build archive = builder.build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 42, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 local size sentinel with exact extra is controlled unsupported", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).localExtra = extra(1, new byte[16]);
            Build archive = builder.build();
            put32(archive.bytes(), archive.localOffsets()[0] + 18, 0xffff_ffffL);
            put32(archive.bytes(), archive.localOffsets()[0] + 22, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.ZIP64_UNSUPPORTED_IN_THIS_STAGE);
        });
        TestSupport.test("ZIP64 sentinel without required extra is malformed", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, 0xffff_ffffL);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
        TestSupport.test("ZIP64 malformed extra length remains invalid", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).centralExtra = extra(1, new byte[8]);
            Build archive = builder.build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 20, 0xffff_ffffL);
            put32(archive.bytes(), archive.centralOffsets()[0] + 24, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
        TestSupport.test("ZIP unsigned 32-bit sentinel is never treated as signed negative", () -> {
            Build archive = new Builder().build();
            put32(archive.bytes(), archive.centralOffsets()[0] + 42, 0xffff_ffffL);
            assertReject(archive.bytes(), ZipStructureException.Reason.EXTRA_FIELD);
        });
    }

    private static void publicFixtureSmokeCases(Path fixtureDirectory) {
        for (String name : List.of(
                "valid-minimal.aural",
                "valid-independent.aural",
                "valid-unicode.aural",
                "valid-unknown-optional.aural")) {
            TestSupport.test("ZIP fixture structural accept " + name, () -> {
                ZipArchiveStructure result = PARSER.parse(fixtureDirectory.resolve(name));
                TestSupport.assertEquals(2, result.entries().size());
            });
        }

        for (String name : List.of(
                "invalid-compression.aural",
                "invalid-duplicate-entry.aural",
                "invalid-entry-comment.aural",
                "invalid-extra-field.aural",
                "invalid-local-central-crc.aural",
                "invalid-malformed-local-header.aural",
                "invalid-special-file.aural",
                "invalid-trailer.aural",
                "invalid-unnecessary-zip64-extra.aural",
                "invalid-unsafe-filename.aural")) {
            TestSupport.test("ZIP fixture structural reject " + name, () -> {
                ZipStructureException failure = TestSupport.expectThrows(
                        ZipStructureException.class, () -> PARSER.parse(fixtureDirectory.resolve(name)));
                TestSupport.assertFalse(
                        failure.isZip64UnsupportedInThisStage(), "classic malformed fixture must be structurally rejected");
            });
        }

        for (String name : List.of(
                "invalid-altered-original.aural",
                "invalid-asset-hash.aural",
                "invalid-critical-extension.aural",
                "invalid-duplicate-key.aural",
                "invalid-excessive-declared-size.aural",
                "invalid-excessive-depth.aural",
                "invalid-fraction.aural",
                "invalid-malformed-json.aural",
                "invalid-negative-zero.aural",
                "invalid-noncanonical.aural",
                "invalid-noncharacter.aural",
                "invalid-unknown-member.aural",
                "invalid-unpaired-surrogate.aural",
                "invalid-unsafe-integer.aural",
                "invalid-utf8.aural",
                "invalid-version.aural")) {
            TestSupport.test("ZIP fixture NOT_YET_APPLICABLE " + name, () ->
                    TestSupport.assertEquals(2, PARSER.parse(fixtureDirectory.resolve(name)).entries().size()));
        }
    }

    private static ZipArchiveStructure parse(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("aural-zip-structure-", ".aural");
        try {
            Files.write(file, bytes);
            return PARSER.parse(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static void assertAccept(byte[] bytes) throws Exception {
        TestSupport.assertEquals(2, parse(bytes).entries().size());
    }

    private static void assertReject(byte[] bytes, ZipStructureException.Reason expectedReason) throws Exception {
        ZipStructureException failure = TestSupport.expectThrows(ZipStructureException.class, () -> parse(bytes));
        if (expectedReason != null) {
            TestSupport.assertEquals(expectedReason, failure.reason());
        }
    }

    private static byte[] insert(byte[] original, int offset, byte[] inserted) {
        byte[] result = new byte[original.length + inserted.length];
        System.arraycopy(original, 0, result, 0, offset);
        System.arraycopy(inserted, 0, result, offset, inserted.length);
        System.arraycopy(original, offset, result, offset + inserted.length, original.length - offset);
        return result;
    }

    private static byte[] extra(int identifier, byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write16(output, identifier);
        write16(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static byte[] bytes(String text) {
        byte[] result = new byte[text.length()];
        for (int index = 0; index < text.length(); index++) {
            result[index] = (byte) text.charAt(index);
        }
        return result;
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, long value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void write16(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
    }

    private static void write32(ByteArrayOutputStream output, long value) {
        output.write((int) value);
        output.write((int) (value >>> 8));
        output.write((int) (value >>> 16));
        output.write((int) (value >>> 24));
    }

    private static final class Entry {
        private byte[] name;
        private byte[] data;
        private int versionMadeBy = 20;
        private int versionNeeded = 10;
        private int flags;
        private int method;
        private int modificationTime;
        private int modificationDate = 0x21;
        private int internalAttributes;
        private long externalAttributes;
        private byte[] localExtra = new byte[0];
        private byte[] centralExtra = new byte[0];
        private byte[] comment = new byte[0];

        private Entry(String name, byte[] data) {
            this.name = bytes(name);
            this.data = data.clone();
        }

        private static Entry manifest() {
            return new Entry("manifest.json", bytes("{}"));
        }

        private static Entry audio() {
            return new Entry("audio/original.wav", bytes("RIFFdata"));
        }
    }

    private static final class Builder {
        private List<Entry> entries = new ArrayList<>(List.of(Entry.manifest(), Entry.audio()));
        private List<Integer> centralOrder = new ArrayList<>(List.of(0, 1));
        private byte[] prefix = new byte[0];
        private byte[] gapAfterFirst = new byte[0];
        private byte[] archiveComment = new byte[0];

        private Build build() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            output.writeBytes(prefix);
            int[] localOffsets = new int[entries.size()];
            for (int index = 0; index < entries.size(); index++) {
                Entry entry = entries.get(index);
                localOffsets[index] = output.size();
                long crc = crc32(entry.data);
                write32(output, LOCAL_SIGNATURE);
                write16(output, entry.versionNeeded);
                write16(output, entry.flags);
                write16(output, entry.method);
                write16(output, entry.modificationTime);
                write16(output, entry.modificationDate);
                write32(output, crc);
                write32(output, entry.data.length);
                write32(output, entry.data.length);
                write16(output, entry.name.length);
                write16(output, entry.localExtra.length);
                output.writeBytes(entry.name);
                output.writeBytes(entry.localExtra);
                output.writeBytes(entry.data);
                if (index == 0) {
                    output.writeBytes(gapAfterFirst);
                }
            }

            int centralDirectoryOffset = output.size();
            int[] centralOffsets = new int[entries.size()];
            Arrays.fill(centralOffsets, -1);
            for (int index : centralOrder) {
                Entry entry = entries.get(index);
                centralOffsets[index] = output.size();
                long crc = crc32(entry.data);
                write32(output, CENTRAL_SIGNATURE);
                write16(output, entry.versionMadeBy);
                write16(output, entry.versionNeeded);
                write16(output, entry.flags);
                write16(output, entry.method);
                write16(output, entry.modificationTime);
                write16(output, entry.modificationDate);
                write32(output, crc);
                write32(output, entry.data.length);
                write32(output, entry.data.length);
                write16(output, entry.name.length);
                write16(output, entry.centralExtra.length);
                write16(output, entry.comment.length);
                write16(output, 0);
                write16(output, entry.internalAttributes);
                write32(output, entry.externalAttributes);
                write32(output, localOffsets[index]);
                output.writeBytes(entry.name);
                output.writeBytes(entry.centralExtra);
                output.writeBytes(entry.comment);
            }
            int eocdOffset = output.size();
            int centralSize = eocdOffset - centralDirectoryOffset;
            write32(output, EOCD_SIGNATURE);
            write16(output, 0);
            write16(output, 0);
            write16(output, centralOrder.size());
            write16(output, centralOrder.size());
            write32(output, centralSize);
            write32(output, centralDirectoryOffset);
            write16(output, archiveComment.length);
            output.writeBytes(archiveComment);
            return new Build(
                    output.toByteArray(),
                    localOffsets,
                    centralOffsets,
                    centralDirectoryOffset,
                    centralSize,
                    eocdOffset,
                    List.copyOf(entries));
        }
    }

    private record Build(
            byte[] bytes,
            int[] localOffsets,
            int[] centralOffsets,
            int centralDirectoryOffset,
            int centralSize,
            int eocdOffset,
            List<Entry> entries) {
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }
}
