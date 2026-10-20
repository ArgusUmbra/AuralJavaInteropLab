package org.auralinterop.tests;

import org.auralinterop.zip.ZipArchiveStructure;
import org.auralinterop.zip.ZipEntryStructure;
import org.auralinterop.zip.ZipLimits;
import org.auralinterop.zip.ZipStructureException;
import org.auralinterop.zip.ZipStructureParser;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.zip.CRC32;

/** Independently assembled APPNOTE 6.3.10 ZIP64 records; no ZIP writer API is used. */
final class Zip64StructureParserTest {
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long U32 = 0xffff_ffffL;
    private static final int U16 = 0xffff;

    private static final int EXPAND_UNCOMPRESSED = 1;
    private static final int EXPAND_COMPRESSED = 2;
    private static final int EXPAND_OFFSET = 4;
    private static final int EXPAND_DISK = 8;

    private static final int END_DISK = 1;
    private static final int END_CENTRAL_DISK = 2;
    private static final int END_ENTRIES_ON_DISK = 4;
    private static final int END_TOTAL_ENTRIES = 8;
    private static final int END_CENTRAL_SIZE = 16;
    private static final int END_CENTRAL_OFFSET = 32;
    private static final int END_ALL = 63;

    private static final ZipStructureParser PARSER = new ZipStructureParser();

    private Zip64StructureParserTest() {
    }

    static void register() {
        positiveCases();
        terminalNegativeCases();
        extraAndBoundaryNegativeCases();
    }

    private static void positiveCases() {
        positive("entry sizes in local and central headers",
                "AURAL 6.4 and APPNOTE 4.5.3 permit sentinel-expanded sizes", builder -> {
                    builder.entries.get(0).localSizes = true;
                    builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
                });
        positive("central uncompressed size only",
                "APPNOTE 4.5.3 emits only the central fields whose classic fields are sentinels", builder ->
                        builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED);
        positive("central compressed size only",
                "APPNOTE 4.5.3 fixes conditional field ordering", builder ->
                        builder.entries.get(0).centralExpansions = EXPAND_COMPRESSED);
        positive("local sizes with classic central sizes",
                "AURAL 6.3 compares resolved rather than raw size fields", builder ->
                        builder.entries.get(0).localSizes = true);
        positive("manifest local-header offset",
                "AURAL 6.4 permits a matching ZIP64 offset sentinel", builder ->
                        builder.entries.get(0).centralExpansions = EXPAND_OFFSET);
        positive("audio local-header offset",
                "APPNOTE 4.5.3 supplies the 64-bit offset in the central extra", builder ->
                        builder.entries.get(1).centralExpansions = EXPAND_OFFSET);
        positive("sizes and offset in one ordered extra",
                "APPNOTE 4.5.3 order is uncompressed, compressed, then offset", builder -> {
                    builder.entries.get(1).localSizes = true;
                    builder.entries.get(1).centralExpansions =
                            EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED | EXPAND_OFFSET;
                });
        positive("both entries use ZIP64 sizes",
                "AURAL 6.4 permits one exact ZIP64 extra per applicable header", builder -> {
                    for (Entry entry : builder.entries) {
                        entry.localSizes = true;
                        entry.centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
                    }
                });
        positive("audio-first physical order",
                "AURAL 6.3 says entry order is not significant", builder -> {
                    builder.physicalOrder = new ArrayList<>(List.of(1, 0));
                    builder.entries.get(1).localSizes = true;
                    builder.entries.get(1).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
                });
        positive("reversed central enumeration",
                "APPNOTE 4.4.1.3 allows central order to differ from local order", builder -> {
                    builder.centralOrder = new ArrayList<>(List.of(1, 0));
                    builder.entries.get(0).centralExpansions = EXPAND_OFFSET;
                });
        positive("terminal entry counts",
                "AURAL 6.4 requires the version-1 EOCD and locator for classic sentinels", builder ->
                        builder.withTerminal(END_ENTRIES_ON_DISK | END_TOTAL_ENTRIES));
        positive("terminal central-directory size",
                "APPNOTE 4.4.23 expands the central-directory size", builder ->
                        builder.withTerminal(END_CENTRAL_SIZE));
        positive("terminal central-directory offset",
                "APPNOTE 4.4.24 expands the central-directory offset", builder ->
                        builder.withTerminal(END_CENTRAL_OFFSET));
        positive("terminal current-disk field",
                "APPNOTE 4.4.19 expands the disk field and AURAL requires resolution to disk zero", builder ->
                        builder.withTerminal(END_DISK));
        positive("terminal central-disk field",
                "APPNOTE 4.4.20 expands the central-directory disk field", builder ->
                        builder.withTerminal(END_CENTRAL_DISK));
        positive("all classic EOCD sentinels",
                "AURAL 6.4 requires all non-sentinel and expanded values to remain consistent", builder ->
                        builder.withTerminal(END_ALL));
        positive("one count sentinel with matching classic count",
                "AURAL 6.4 requires a present non-sentinel count to equal ZIP64", builder ->
                        builder.withTerminal(END_ENTRIES_ON_DISK));
        positive("entry and terminal ZIP64 together",
                "AURAL 6.3 places entry ZIP64 metadata before a ZIP64 terminal envelope", builder -> {
                    builder.entries.get(1).localSizes = true;
                    builder.entries.get(1).centralExpansions =
                            EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED | EXPAND_OFFSET;
                    builder.withTerminal(END_CENTRAL_OFFSET);
                });
        positive("zero-byte sentinel-expanded entry",
                "ZIP64 expanded values may resolve to zero when classic fields carry sentinels", builder -> {
                    builder.entries.get(0).data = new byte[0];
                    builder.entries.get(0).localSizes = true;
                    builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
                });
        positive("manifest exact 1 MiB expanded-size boundary",
                "AURAL 6.6 permits exactly 1,048,576 manifest bytes", builder -> {
                    builder.entries.get(0).data = new byte[(int) ZipLimits.MAXIMUM_MANIFEST_SIZE];
                    builder.entries.get(0).localSizes = true;
                    builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
                });
        positive("UTF-8 flag and non-DOS creator with ZIP64",
                "AURAL 6.1 and 6.5 permit bit 11 and variable creator platform metadata", builder -> {
                    builder.entries.get(0).flags = 1 << 11;
                    builder.entries.get(0).versionMadeBy = (19 << 8) | 45;
                    builder.entries.get(0).centralExpansions = EXPAND_OFFSET;
                });

        TestSupport.test("ZIP64 valid output exposes resolved and raw metadata [AURAL 6.3]", () -> {
            Builder builder = new Builder();
            builder.entries.get(0).localSizes = true;
            builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED | EXPAND_OFFSET;
            builder.withTerminal(END_CENTRAL_SIZE);
            ZipArchiveStructure result = parse(builder.build().bytes);
            ZipEntryStructure entry = result.entry("manifest.json");
            TestSupport.assertEquals(U32, entry.rawCompressedSize32());
            TestSupport.assertEquals(2L, entry.compressedSize());
            TestSupport.assertTrue(entry.usesZip64Extra(), "entry must report ZIP64 resolution");
            TestSupport.assertTrue(result.usesZip64EndRecords(), "archive must report ZIP64 terminal records");
            TestSupport.assertEquals(56L, result.zip64EocdRange().orElseThrow().length());
            TestSupport.assertEquals(20L, result.zip64LocatorRange().orElseThrow().length());
        });
    }

    private static void terminalNegativeCases() {
        negative("missing ZIP64 EOCD and locator", builder -> builder.endMask = END_CENTRAL_SIZE, build -> build.bytes);
        negative("missing locator signature", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.locatorOffset, 0);
            return build.bytes;
        });
        negative("incorrect ZIP64 EOCD signature", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.zip64EocdOffset, 0);
            return build.bytes;
        });
        negative("truncated ZIP64 EOCD body", builder -> builder.withTerminal(END_CENTRAL_SIZE), build ->
                deleteRange(build.bytes, build.zip64EocdOffset + 20, 5));
        negative("truncated locator body", builder -> builder.withTerminal(END_CENTRAL_SIZE), build ->
                deleteRange(build.bytes, build.locatorOffset + 8, 3));
        negative("ZIP64 EOCD record-size field 43", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 4, 43);
            return build.bytes;
        });
        negative("ZIP64 EOCD record-size field 45 extensible data claim", builder ->
                builder.withTerminal(END_CENTRAL_SIZE), build -> {
                    put64(build.bytes, build.zip64EocdOffset + 4, 45);
                    return build.bytes;
                });
        negative("ZIP64 EOCD record-size unsigned high bit", builder ->
                builder.withTerminal(END_CENTRAL_SIZE), build -> {
                    put64(build.bytes, build.zip64EocdOffset + 4, Long.MIN_VALUE);
                    return build.bytes;
                });
        negative("ZIP64 EOCD version-made-by below 4.5", builder -> {
            builder.zip64VersionMadeBy = 20;
            builder.withTerminal(END_CENTRAL_SIZE);
        }, build -> build.bytes);
        negative("ZIP64 EOCD version-needed 2.0", builder -> {
            builder.zip64VersionNeeded = 20;
            builder.withTerminal(END_CENTRAL_SIZE);
        }, build -> build.bytes);
        negative("ZIP64 EOCD current disk nonzero", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.zip64EocdOffset + 16, 1);
            return build.bytes;
        });
        negative("ZIP64 EOCD central disk nonzero", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.zip64EocdOffset + 20, 1);
            return build.bytes;
        });
        negative("ZIP64 EOCD entries-on-disk wrong", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 24, 1);
            return build.bytes;
        });
        negative("ZIP64 EOCD total entries wrong", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 32, 3);
            return build.bytes;
        });
        negative("ZIP64 EOCD count unsigned high bit", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 24, Long.MIN_VALUE);
            return build.bytes;
        });
        negative("ZIP64 central-directory size contradiction", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 40, build.centralSize + 1L);
            return build.bytes;
        });
        negative("ZIP64 central-directory size exceeds 4 MiB", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.zip64EocdOffset + 40, ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE + 1L);
            return build.bytes;
        });
        negative("ZIP64 central-directory offset contradiction", builder -> builder.withTerminal(END_CENTRAL_OFFSET), build -> {
            put64(build.bytes, build.zip64EocdOffset + 48, build.centralOffset + 1L);
            return build.bytes;
        });
        negative("ZIP64 central-directory offset beyond archive", builder -> builder.withTerminal(END_CENTRAL_OFFSET), build -> {
            put64(build.bytes, build.zip64EocdOffset + 48, build.bytes.length + 1L);
            return build.bytes;
        });
        negative("ZIP64 central-directory offset unsigned high bit", builder ->
                builder.withTerminal(END_CENTRAL_OFFSET), build -> {
                    put64(build.bytes, build.zip64EocdOffset + 48, Long.MIN_VALUE);
                    return build.bytes;
                });
        negative("locator disk nonzero", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.locatorOffset + 4, 1);
            return build.bytes;
        });
        negative("locator total disks zero", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.locatorOffset + 16, 0);
            return build.bytes;
        });
        negative("locator total disks two", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put32(build.bytes, build.locatorOffset + 16, 2);
            return build.bytes;
        });
        negative("locator points into ZIP64 EOCD", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.locatorOffset + 8, build.zip64EocdOffset + 4L);
            return build.bytes;
        });
        negative("locator offset beyond actual file", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.locatorOffset + 8, build.bytes.length + 100L);
            return build.bytes;
        });
        negative("locator offset unsigned high bit", builder -> builder.withTerminal(END_CENTRAL_SIZE), build -> {
            put64(build.bytes, build.locatorOffset + 8, Long.MIN_VALUE);
            return build.bytes;
        });
        negative("classic non-sentinel entry count conflicts with ZIP64", builder ->
                builder.withTerminal(END_ENTRIES_ON_DISK), build -> {
                    put16(build.bytes, build.eocdOffset + 10, 1);
                    return build.bytes;
                });
        negative("classic non-sentinel central size conflicts with ZIP64", builder ->
                builder.withTerminal(END_CENTRAL_OFFSET), build -> {
                    put32(build.bytes, build.eocdOffset + 12, build.centralSize + 1L);
                    return build.bytes;
                });
        negative("classic non-sentinel central offset conflicts with ZIP64", builder ->
                builder.withTerminal(END_CENTRAL_SIZE), build -> {
                    put32(build.bytes, build.eocdOffset + 16, build.centralOffset + 1L);
                    return build.bytes;
                });
        negative("unneeded ZIP64 terminal envelope experimental rejection", builder ->
                builder.terminal = true, build -> build.bytes);
        negative("duplicated ZIP64 terminal structure creates undeclared gap", builder ->
                builder.withTerminal(END_CENTRAL_SIZE), build -> {
                    byte[] duplicate = Arrays.copyOfRange(
                            build.bytes, build.zip64EocdOffset, build.eocdOffset);
                    byte[] result = insert(build.bytes, build.zip64EocdOffset, duplicate);
                    int shiftedLocator = build.locatorOffset + duplicate.length;
                    put64(result, shiftedLocator + 8, build.zip64EocdOffset + duplicate.length);
                    return result;
                });
    }

    private static void extraAndBoundaryNegativeCases() {
        negative("missing required central ZIP64 extra", builder -> { }, build -> {
            put32(build.bytes, build.centralOffsets[0] + 20, U32);
            put32(build.bytes, build.centralOffsets[0] + 24, U32);
            return build.bytes;
        });
        negative("missing required local ZIP64 extra", builder -> { }, build -> {
            put32(build.bytes, build.localOffsets[0] + 18, U32);
            put32(build.bytes, build.localOffsets[0] + 22, U32);
            return build.bytes;
        });
        negative("duplicate ZIP64 extra field", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int central = build.centralOffsets[0];
                    int extraStart = central + 46 + bytes("manifest.json").length;
                    byte[] duplicate = Arrays.copyOfRange(build.bytes, extraStart, extraStart + 12);
                    byte[] result = insert(build.bytes, extraStart + 12, duplicate);
                    put16(result, central + 30, 24);
                    int shiftedEocd = build.eocdOffset + duplicate.length;
                    put32(result, shiftedEocd + 12, build.centralSize + duplicate.length);
                    return result;
                });
        negative("non-ZIP64 extra identifier", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int extraStart = build.centralOffsets[0] + 46 + bytes("manifest.json").length;
                    put16(build.bytes, extraStart, 2);
                    return build.bytes;
                });
        negative("ZIP64 extra declared payload too short", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int extraStart = build.centralOffsets[0] + 46 + bytes("manifest.json").length;
                    put16(build.bytes, extraStart + 2, 4);
                    return build.bytes;
                });
        negative("ZIP64 extra unexpected surplus value", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED, build -> {
                    int central = build.centralOffsets[0];
                    put32(build.bytes, central + 20, 2);
                    return build.bytes;
                });
        negative("unnecessary central ZIP64 extra", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    put32(build.bytes, build.centralOffsets[0] + 42, build.localOffsets[0]);
                    return build.bytes;
                });
        negative("unnecessary local ZIP64 extra", builder ->
                builder.entries.get(0).localSizes = true, build -> {
                    put32(build.bytes, build.localOffsets[0] + 18, 2);
                    put32(build.bytes, build.localOffsets[0] + 22, 2);
                    return build.bytes;
                });
        negative("unsupported disk-start expansion", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_DISK, build -> build.bytes);
        negative("ZIP64 entry with version-needed 2.0", builder -> {
            builder.entries.get(0).versionNeeded = 20;
            builder.entries.get(0).centralExpansions = EXPAND_OFFSET;
        }, build -> build.bytes);
        negative("local ZIP64 with only compressed sentinel", builder ->
                builder.entries.get(0).localSizes = true, build -> {
                    put32(build.bytes, build.localOffsets[0] + 22, 2);
                    return build.bytes;
                });
        negative("local ZIP64 with only uncompressed sentinel", builder ->
                builder.entries.get(0).localSizes = true, build -> {
                    put32(build.bytes, build.localOffsets[0] + 18, 2);
                    return build.bytes;
                });
        negative("central ZIP64 sizes in wrong order", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, 3);
                    put64(build.bytes, payload + 8, 2);
                    return build.bytes;
                });
        negative("local and central expanded sizes disagree", builder -> {
            builder.entries.get(0).localSizes = true;
            builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
        }, build -> {
            int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
            put64(build.bytes, payload, 1);
            put64(build.bytes, payload + 8, 1);
            return build.bytes;
        });
        negative("stored expanded compressed and uncompressed sizes differ", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload + 8, 1);
                    return build.bytes;
                });
        negative("manifest expanded size exceeds 1 MiB", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, ZipLimits.MAXIMUM_MANIFEST_SIZE + 1);
                    put64(build.bytes, payload + 8, ZipLimits.MAXIMUM_MANIFEST_SIZE + 1);
                    return build.bytes;
                });
        negative("original expanded size exceeds 8 GiB", builder ->
                builder.entries.get(1).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED, build -> {
                    int payload = build.centralOffsets[1] + 46 + bytes("audio/original.wav").length + 4;
                    put64(build.bytes, payload, ZipLimits.MAXIMUM_ORIGINAL_SIZE + 1);
                    put64(build.bytes, payload + 8, ZipLimits.MAXIMUM_ORIGINAL_SIZE + 1);
                    return build.bytes;
                });
        negative("declared size just below the classic 4 GiB sentinel", builder -> {
            builder.entries.get(1).localSizes = true;
            builder.entries.get(1).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
        }, build -> {
            long declared = 0xffff_fffeL;
            int localPayload = build.localOffsets[1] + 30 + bytes("audio/original.wav").length + 4;
            int centralPayload = build.centralOffsets[1] + 46 + bytes("audio/original.wav").length + 4;
            put64(build.bytes, localPayload, declared);
            put64(build.bytes, localPayload + 8, declared);
            put64(build.bytes, centralPayload, declared);
            put64(build.bytes, centralPayload + 8, declared);
            return build.bytes;
        });
        negative("declared size at 4 GiB resolves without 32-bit truncation", builder -> {
            builder.entries.get(1).localSizes = true;
            builder.entries.get(1).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
        }, build -> {
            long declared = 0x1_0000_0000L;
            int localPayload = build.localOffsets[1] + 30 + bytes("audio/original.wav").length + 4;
            int centralPayload = build.centralOffsets[1] + 46 + bytes("audio/original.wav").length + 4;
            put64(build.bytes, localPayload, declared);
            put64(build.bytes, localPayload + 8, declared);
            put64(build.bytes, centralPayload, declared);
            put64(build.bytes, centralPayload + 8, declared);
            return build.bytes;
        });
        negative("entry expanded size unsigned high bit", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, Long.MIN_VALUE);
                    return build.bytes;
                });
        negative("entry offset unsigned high bit", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, Long.MIN_VALUE);
                    return build.bytes;
                });
        negative("expanded local offset beyond file", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, build.bytes.length);
                    return build.bytes;
                });
        negative("expanded local offset at 4 GiB is retained as 64-bit", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, 0x1_0000_0000L);
                    return build.bytes;
                });
        negative("expanded local offset points into entry data", builder ->
                builder.entries.get(1).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[1] + 46 + bytes("audio/original.wav").length + 4;
                    put64(build.bytes, payload, build.localOffsets[0] + 31L);
                    return build.bytes;
                });
        negative("expanded local offset aliases another header", builder ->
                builder.entries.get(1).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[1] + 46 + bytes("audio/original.wav").length + 4;
                    put64(build.bytes, payload, build.localOffsets[0]);
                    return build.bytes;
                });
        negative("expanded local offset points into central directory", builder ->
                builder.entries.get(0).centralExpansions = EXPAND_OFFSET, build -> {
                    int payload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
                    put64(build.bytes, payload, build.centralOffset);
                    return build.bytes;
                });
        negative("ZIP64 data range overruns next local header", builder -> {
            builder.entries.get(0).localSizes = true;
            builder.entries.get(0).centralExpansions = EXPAND_UNCOMPRESSED | EXPAND_COMPRESSED;
        }, build -> {
            int localPayload = build.localOffsets[0] + 30 + bytes("manifest.json").length + 4;
            int centralPayload = build.centralOffsets[0] + 46 + bytes("manifest.json").length + 4;
            put64(build.bytes, localPayload, 3);
            put64(build.bytes, localPayload + 8, 3);
            put64(build.bytes, centralPayload, 3);
            put64(build.bytes, centralPayload + 8, 3);
            return build.bytes;
        });
        negative("ZIP64 local extra malformed subfield boundary", builder ->
                builder.entries.get(0).localSizes = true, build -> {
                    int extra = build.localOffsets[0] + 30 + bytes("manifest.json").length;
                    put16(build.bytes, extra + 2, 15);
                    return build.bytes;
                });
    }

    private static void positive(String feature, String rule, Consumer<Builder> configure) {
        TestSupport.test("ZIP64 valid " + feature + " [" + rule + "]", () -> {
            Builder builder = new Builder();
            configure.accept(builder);
            ZipArchiveStructure result = parse(builder.build().bytes);
            TestSupport.assertEquals(2, result.entries().size());
        });
    }

    private static void negative(
            String condition, Consumer<Builder> configure, ConsumerWithResult<Build, byte[]> mutate) {
        TestSupport.test("ZIP64 rejects " + condition, () -> {
            Builder builder = new Builder();
            configure.accept(builder);
            Build build = builder.build();
            byte[] bytes = mutate.apply(build);
            TestSupport.expectThrows(ZipStructureException.class, () -> parse(bytes));
        });
    }

    private static ZipArchiveStructure parse(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("aural-zip64-", ".aural");
        try {
            Files.write(file, bytes);
            return PARSER.parse(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    @FunctionalInterface
    private interface ConsumerWithResult<T, R> {
        R apply(T value) throws Exception;
    }

    private static final class Entry {
        private final byte[] name;
        private byte[] data;
        private int versionMadeBy = (3 << 8) | 45;
        private int versionNeeded = 45;
        private int flags;
        private boolean localSizes;
        private int centralExpansions;

        private Entry(String name, byte[] data) {
            this.name = bytes(name);
            this.data = data.clone();
        }
    }

    private static final class Builder {
        private final List<Entry> entries = new ArrayList<>(List.of(
                new Entry("manifest.json", bytes("{}")),
                new Entry("audio/original.wav", bytes("RIFFdata"))));
        private List<Integer> physicalOrder = new ArrayList<>(List.of(0, 1));
        private List<Integer> centralOrder = new ArrayList<>(List.of(0, 1));
        private boolean terminal;
        private int endMask;
        private int zip64VersionMadeBy = (3 << 8) | 45;
        private int zip64VersionNeeded = 45;

        private void withTerminal(int mask) {
            terminal = true;
            endMask = mask;
        }

        private Build build() {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            int[] localOffsets = {-1, -1};
            for (int entryIndex : physicalOrder) {
                Entry entry = entries.get(entryIndex);
                localOffsets[entryIndex] = output.size();
                byte[] localExtra = entry.localSizes
                        ? zip64Extra(entry.data.length, entry.data.length)
                        : new byte[0];
                write32(output, LOCAL_SIGNATURE);
                write16(output, entry.versionNeeded);
                write16(output, entry.flags);
                write16(output, 0);
                write16(output, 0);
                write16(output, 0x21);
                write32(output, crc32(entry.data));
                write32(output, entry.localSizes ? U32 : entry.data.length);
                write32(output, entry.localSizes ? U32 : entry.data.length);
                write16(output, entry.name.length);
                write16(output, localExtra.length);
                output.writeBytes(entry.name);
                output.writeBytes(localExtra);
                output.writeBytes(entry.data);
            }

            int centralOffset = output.size();
            int[] centralOffsets = {-1, -1};
            for (int entryIndex : centralOrder) {
                Entry entry = entries.get(entryIndex);
                int mask = entry.centralExpansions;
                centralOffsets[entryIndex] = output.size();
                ByteArrayOutputStream payload = new ByteArrayOutputStream();
                if ((mask & EXPAND_UNCOMPRESSED) != 0) {
                    write64(payload, entry.data.length);
                }
                if ((mask & EXPAND_COMPRESSED) != 0) {
                    write64(payload, entry.data.length);
                }
                if ((mask & EXPAND_OFFSET) != 0) {
                    write64(payload, localOffsets[entryIndex]);
                }
                if ((mask & EXPAND_DISK) != 0) {
                    write32(payload, 0);
                }
                byte[] centralExtra = mask == 0 ? new byte[0] : zip64Extra(payload.toByteArray());
                write32(output, CENTRAL_SIGNATURE);
                write16(output, entry.versionMadeBy);
                write16(output, entry.versionNeeded);
                write16(output, entry.flags);
                write16(output, 0);
                write16(output, 0);
                write16(output, 0x21);
                write32(output, crc32(entry.data));
                write32(output, (mask & EXPAND_COMPRESSED) != 0 ? U32 : entry.data.length);
                write32(output, (mask & EXPAND_UNCOMPRESSED) != 0 ? U32 : entry.data.length);
                write16(output, entry.name.length);
                write16(output, centralExtra.length);
                write16(output, 0);
                write16(output, (mask & EXPAND_DISK) != 0 ? U16 : 0);
                write16(output, 0);
                write32(output, 0);
                write32(output, (mask & EXPAND_OFFSET) != 0 ? U32 : localOffsets[entryIndex]);
                output.writeBytes(entry.name);
                output.writeBytes(centralExtra);
            }
            int centralSize = output.size() - centralOffset;

            int zip64EocdOffset = -1;
            int locatorOffset = -1;
            if (terminal) {
                zip64EocdOffset = output.size();
                write32(output, ZIP64_EOCD_SIGNATURE);
                write64(output, 44);
                write16(output, zip64VersionMadeBy);
                write16(output, zip64VersionNeeded);
                write32(output, 0);
                write32(output, 0);
                write64(output, 2);
                write64(output, 2);
                write64(output, centralSize);
                write64(output, centralOffset);
                locatorOffset = output.size();
                write32(output, ZIP64_LOCATOR_SIGNATURE);
                write32(output, 0);
                write64(output, zip64EocdOffset);
                write32(output, 1);
            }

            int eocdOffset = output.size();
            write32(output, EOCD_SIGNATURE);
            write16(output, (endMask & END_DISK) != 0 ? U16 : 0);
            write16(output, (endMask & END_CENTRAL_DISK) != 0 ? U16 : 0);
            write16(output, (endMask & END_ENTRIES_ON_DISK) != 0 ? U16 : 2);
            write16(output, (endMask & END_TOTAL_ENTRIES) != 0 ? U16 : 2);
            write32(output, (endMask & END_CENTRAL_SIZE) != 0 ? U32 : centralSize);
            write32(output, (endMask & END_CENTRAL_OFFSET) != 0 ? U32 : centralOffset);
            write16(output, 0);
            return new Build(
                    output.toByteArray(), localOffsets, centralOffsets, centralOffset, centralSize,
                    zip64EocdOffset, locatorOffset, eocdOffset);
        }
    }

    private record Build(
            byte[] bytes,
            int[] localOffsets,
            int[] centralOffsets,
            int centralOffset,
            int centralSize,
            int zip64EocdOffset,
            int locatorOffset,
            int eocdOffset) {
    }

    private static byte[] zip64Extra(long first, long second) {
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        write64(payload, first);
        write64(payload, second);
        return zip64Extra(payload.toByteArray());
    }

    private static byte[] zip64Extra(byte[] payload) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write16(output, 1);
        write16(output, payload.length);
        output.writeBytes(payload);
        return output.toByteArray();
    }

    private static byte[] insert(byte[] original, int offset, byte[] inserted) {
        byte[] result = new byte[original.length + inserted.length];
        System.arraycopy(original, 0, result, 0, offset);
        System.arraycopy(inserted, 0, result, offset, inserted.length);
        System.arraycopy(original, offset, result, offset + inserted.length, original.length - offset);
        return result;
    }

    private static byte[] deleteRange(byte[] original, int offset, int length) {
        byte[] result = new byte[original.length - length];
        System.arraycopy(original, 0, result, 0, offset);
        System.arraycopy(original, offset + length, result, offset, original.length - offset - length);
        return result;
    }

    private static byte[] bytes(String text) {
        byte[] result = new byte[text.length()];
        for (int index = 0; index < text.length(); index++) {
            result[index] = (byte) text.charAt(index);
        }
        return result;
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static void put32(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (8 * index));
        }
    }

    private static void put64(byte[] bytes, int offset, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            bytes[offset + index] = (byte) (value >>> (8 * index));
        }
    }

    private static void write16(ByteArrayOutputStream output, int value) {
        output.write(value);
        output.write(value >>> 8);
    }

    private static void write32(ByteArrayOutputStream output, long value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            output.write((int) (value >>> (8 * index)));
        }
    }

    private static void write64(ByteArrayOutputStream output, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            output.write((int) (value >>> (8 * index)));
        }
    }
}
