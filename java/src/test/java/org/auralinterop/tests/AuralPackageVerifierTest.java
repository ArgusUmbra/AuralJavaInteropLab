package org.auralinterop.tests;

import org.auralinterop.json.JsonValue;
import org.auralinterop.json.StrictJsonParser;
import org.auralinterop.verify.AuralPackageVerifier;
import org.auralinterop.verify.AuralVerificationException;
import org.auralinterop.verify.VerificationErrorCategory;
import org.auralinterop.verify.VerifiedAuralPackage;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.CRC32;

final class AuralPackageVerifierTest {
    private static final long LOCAL_SIGNATURE = 0x04034b50L;
    private static final long CENTRAL_SIGNATURE = 0x02014b50L;
    private static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;
    private static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    private static final long EOCD_SIGNATURE = 0x06054b50L;
    private static final long UINT32_MAX = 0xffff_ffffL;
    private static final AuralPackageVerifier VERIFIER = new AuralPackageVerifier();

    private AuralPackageVerifierTest() {
    }

    static void register(Path fixtureDirectory) {
        positiveIndependentCases();
        negativeIndependentCases();
        publicCoreReaderCases(fixtureDirectory);
        legacyCapabilityBoundary(fixtureDirectory);
    }

    private static void positiveIndependentCases() {
        accept("verifier valid minimal RIFF package", new PackageBuilder());
        accept("verifier valid RF64 identification", new PackageBuilder().audio(rf64("data")));
        accept("verifier valid optional title", new PackageBuilder().manifestTransform(
                json -> beforeVersion(json, "\"title\":\"Field recording\",")));
        accept("verifier valid UTC recorded_at", new PackageBuilder().manifestTransform(
                json -> beforeVersion(json, "\"recorded_at\":\"2026-10-19T12:34:56Z\",")));
        accept("verifier valid offset recorded_at", new PackageBuilder().manifestTransform(
                json -> beforeVersion(json, "\"recorded_at\":\"2026-10-19T12:34:56.123-06:00\",")));
        accept("verifier valid unknown optional extension", new PackageBuilder().manifestTransform(
                json -> afterAssets(json, "\"extensions\":{\"https://example.test/x\":{\"n\":1}},")));
        accept("verifier valid empty extensions", new PackageBuilder().manifestTransform(
                json -> afterAssets(json, "\"extensions\":{},")));
        accept("verifier valid audio-first physical order", new PackageBuilder().audioFirst());
        accept("verifier valid reversed central enumeration", new PackageBuilder().reverseCentral());
        accept("verifier valid both entry orders reversed", new PackageBuilder().audioFirst().reverseCentral());
        accept("verifier valid UTF-8 name flags", new PackageBuilder().flags(1 << 11));
        accept("verifier valid version-needed 2.0", new PackageBuilder().versionNeeded(20));
        accept("verifier valid classic version-needed 4.5", new PackageBuilder().versionNeeded(45));
        accept("verifier valid Unix regular-file metadata", new PackageBuilder().unixRegularFiles());
        accept("verifier accepts unusual opaque WAVE chunks", new PackageBuilder().audio(riff("JUNKodd!LISTmeta")));
        accept("verifier computes distinct payload integrity", new PackageBuilder().audio(riff("payload-two")));
        accept("verifier valid exact one-MiB manifest", new PackageBuilder().manifestTransform(json -> {
            String marker = "MANIFEST_FILL_MARKER";
            String template = afterAssets(json, "\"extensions\":{\"x:test\":\"" + marker + "\"},");
            int fillerLength = 1_048_576 - (template.length() - marker.length());
            return template.replace(marker, "x".repeat(fillerLength));
        }));
        TestSupport.test("verifier repeatable complete result", () -> {
            byte[] bytes = new PackageBuilder().build().bytes();
            VerifiedAuralPackage first = verify(bytes);
            VerifiedAuralPackage second = verify(bytes);
            TestSupport.assertEquals(first, second);
            TestSupport.assertEquals(VerifiedAuralPackage.CompletionStatus.COMPLETE, first.status());
        });
        TestSupport.test("verifier result CRC collection is immutable", () -> {
            VerifiedAuralPackage result = verify(new PackageBuilder().build().bytes());
            TestSupport.expectThrows(UnsupportedOperationException.class,
                    () -> result.crc32Results().clear());
        });
        acceptZip64("verifier valid small ZIP64 entry sizes", new PackageBuilder().zip64EntrySizes());
        acceptZip64("verifier valid small ZIP64 terminal envelope", new PackageBuilder().zip64Terminal());
    }

    private static void negativeIndependentCases() {
        reject("verifier rejects incorrect manifest CRC32", new PackageBuilder().manifestCrc(1),
                VerificationErrorCategory.CRC32_MISMATCH);
        reject("verifier rejects incorrect original CRC32", new PackageBuilder().audioCrc(1),
                VerificationErrorCategory.CRC32_MISMATCH);
        TestSupport.test("verifier rejects one-bit original payload alteration", () -> {
            Build build = new PackageBuilder().build();
            build.bytes()[build.dataOffsets()[1] + 12] ^= 1;
            reject(build.bytes(), VerificationErrorCategory.CRC32_MISMATCH);
        });
        reject("verifier rejects matching wrong manifest CRC declarations", new PackageBuilder().manifestCrc(0x1234),
                VerificationErrorCategory.CRC32_MISMATCH);
        reject("verifier rejects matching wrong original CRC declarations", new PackageBuilder().audioCrc(0x89ab),
                VerificationErrorCategory.CRC32_MISMATCH);
        reject("verifier compares CRC32 as unsigned 32-bit", new PackageBuilder().audioCrc(0xffff_ffffL),
                VerificationErrorCategory.CRC32_MISMATCH);
        reject("verifier rejects zero-length manifest", new PackageBuilder().rawManifest(new byte[0]),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        reject("verifier rejects zero-length original", new PackageBuilder().audio(new byte[0]),
                VerificationErrorCategory.INVALID_WAV_IDENTIFICATION);
        reject("verifier rejects truncated WAVE prefix", new PackageBuilder().audio(ascii("RIFF123WAVE")),
                VerificationErrorCategory.INVALID_WAV_IDENTIFICATION);
        reject("verifier rejects incorrect RIFF signature", new PackageBuilder().audio(ascii("NOPE0000WAVE")),
                VerificationErrorCategory.INVALID_WAV_IDENTIFICATION);
        reject("verifier rejects incorrect WAVE identifier", new PackageBuilder().audio(ascii("RIFF0000NOPE")),
                VerificationErrorCategory.INVALID_WAV_IDENTIFICATION);
        reject("verifier rejects WAV filename with non-WAV bytes", new PackageBuilder().audio(ascii("not a wave file")),
                VerificationErrorCategory.INVALID_WAV_IDENTIFICATION);
        reject("verifier rejects incorrect declared SHA-256", new PackageBuilder().manifestTransform(
                json -> replaceHash(json, "0".repeat(64))), VerificationErrorCategory.ORIGINAL_SHA256_MISMATCH);
        reject("verifier rejects manifest byte size one too large", new PackageBuilder().manifestTransform(
                json -> replaceSize(json, defaultAudio().length + 1L)),
                VerificationErrorCategory.ORIGINAL_BYTE_COUNT_MISMATCH);
        reject("verifier rejects manifest byte size one too small", new PackageBuilder().manifestTransform(
                json -> replaceSize(json, defaultAudio().length - 1L)),
                VerificationErrorCategory.ORIGINAL_BYTE_COUNT_MISMATCH);
        reject("verifier rejects different manifest original path", new PackageBuilder().manifestTransform(
                json -> json.replace("\"original\":\"audio/original.wav\"",
                        "\"original\":\"audio/other.wav\"")), VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects different asset path", new PackageBuilder().manifestTransform(
                json -> json.replace("\"path\":\"audio/original.wav\"", "\"path\":\"audio/other.wav\"")),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects different asset role", new PackageBuilder().manifestTransform(
                json -> json.replace("\"role\":\"original\"", "\"role\":\"derived\"")),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects different asset media type", new PackageBuilder().manifestTransform(
                json -> json.replace("audio/wav", "audio/x-wav")), VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects uppercase digest syntax", new PackageBuilder().manifestTransform(
                json -> replaceHash(json, sha256(defaultAudio()).toUpperCase())),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects short digest syntax", new PackageBuilder().manifestTransform(
                json -> replaceHash(json, "0".repeat(63))), VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects unsupported manifest version", new PackageBuilder().manifestTransform(
                json -> json.replace("0.2-draft", "0.1")), VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects wrong format identifier", new PackageBuilder().manifestTransform(
                json -> json.replace("org.aural.audio-evidence", "org.example")),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects unsupported critical extension", new PackageBuilder().manifestTransform(json ->
                afterAssets(json, "\"critical_extensions\":[\"https://example.test/c\"],"
                        + "\"extensions\":{\"https://example.test/c\":null},")),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects unknown core member", new PackageBuilder().manifestTransform(
                json -> beforeVersion(json, "\"unknown\":true,")), VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects invalid timestamp calendar", new PackageBuilder().manifestTransform(
                json -> beforeVersion(json, "\"recorded_at\":\"2025-02-29T00:00:00Z\",")),
                VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE);
        reject("verifier rejects leading manifest whitespace", new PackageBuilder().manifestTransform(json -> " " + json),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        reject("verifier rejects trailing manifest line feed", new PackageBuilder().manifestTransform(json -> json + "\n"),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        reject("verifier rejects duplicate JSON member", new PackageBuilder().manifestTransform(
                json -> json.replace("\"format\":", "\"format\":\"org.aural.audio-evidence\",\"format\":")),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        reject("verifier rejects malformed manifest JSON", new PackageBuilder().rawManifest(ascii("{\"assets\":")),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        TestSupport.test("verifier rejects invalid UTF-8 manifest", () -> {
            PackageBuilder builder = new PackageBuilder();
            byte[] manifest = builder.generatedManifest();
            int index = indexOf(manifest, ascii("org.aural"));
            manifest[index] = (byte) 0xc3;
            manifest[index + 1] = 0x28;
            reject(builder.rawManifest(manifest).build().bytes(),
                    VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        });
        reject("verifier rejects fractional asset size", new PackageBuilder().manifestTransform(
                json -> json.replace("\"size\":" + defaultAudio().length,
                        "\"size\":" + defaultAudio().length + ".0")),
                VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        reject("verifier rejects excessive manifest depth", new PackageBuilder().manifestTransform(json -> {
            String nested = "[".repeat(32) + "null" + "]".repeat(32);
            return afterAssets(json, "\"extensions\":{\"x:test\":" + nested + "},");
        }), VerificationErrorCategory.RESOURCE_LIMIT_VIOLATION);
        reject("verifier rejects compressed original entry", new PackageBuilder().audioMethod(8),
                VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE);
        reject("verifier rejects unexpected third entry", new PackageBuilder().thirdEntry(),
                VerificationErrorCategory.RESOURCE_LIMIT_VIOLATION);
        reject("verifier rejects duplicate required entry", new PackageBuilder().duplicateManifestName(),
                VerificationErrorCategory.REQUIRED_ENTRY_MISSING_OR_MISMATCHED);
        reject("verifier rejects local-central CRC disagreement", new PackageBuilder().audioCrcPair(1, 2),
                VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE);
        reject("verifier rejects entry comment", new PackageBuilder().audioComment(ascii("x")),
                VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE);
        TestSupport.test("verifier rejects truncated archive read", () -> {
            byte[] complete = new PackageBuilder().build().bytes();
            reject(Arrays.copyOf(complete, complete.length - 9), VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE);
        });
        TestSupport.test("verifier rejects empty archive", () ->
                reject(new byte[0], VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE));
        TestSupport.test("verifier reports missing archive as controlled I/O failure", () -> {
            Path missing = Path.of(System.getProperty("java.io.tmpdir"),
                    "aural-file-that-does-not-exist-008d-3f-7f.aural");
            Files.deleteIfExists(missing);
            AuralVerificationException failure = TestSupport.expectThrows(
                    AuralVerificationException.class, () -> VERIFIER.verify(missing));
            TestSupport.assertEquals(
                    VerificationErrorCategory.IO_OR_TRUNCATED_READ_FAILURE, failure.category());
        });
        TestSupport.test("verifier rejects malformed ZIP64 entry extra", () -> {
            Build build = new PackageBuilder().zip64EntrySizes().build();
            int extraOffset = build.localOffsets()[0] + 30 + ascii("manifest.json").length;
            put16(build.bytes(), extraOffset, 2);
            reject(build.bytes(), VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE);
        });
        reject("verifier rejects declared original above 8 GiB", new PackageBuilder().manifestTransform(
                json -> replaceSize(json, 8_589_934_593L)), VerificationErrorCategory.RESOURCE_LIMIT_VIOLATION);
        TestSupport.test("verifier rejects one-bit manifest payload corruption", () -> {
            Build build = new PackageBuilder().build();
            build.bytes()[build.dataOffsets()[0]] ^= 1;
            reject(build.bytes(), VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE);
        });
    }

    private static void publicCoreReaderCases(Path fixtureDirectory) {
        Path inventoryPath = fixtureDirectory.resolve("core-reader-0.2-cases.v1.json");
        JsonValue inventory;
        try {
            String inventoryText = Files.readString(inventoryPath, StandardCharsets.UTF_8).stripTrailing();
            inventory = new StrictJsonParser(1_048_576, 32).parse(
                    inventoryText.getBytes(StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new AssertionError("cannot read frozen Core Reader inventory", failure);
        }
        JsonValue.JsonObject root = object(inventory);
        JsonValue.JsonArray cases = array(root.members().get("cases"));
        int required = 0;
        for (JsonValue value : cases.values()) {
            JsonValue.JsonObject testCase = object(value);
            if (!string(testCase, "status").equals("required")) {
                continue;
            }
            required++;
            String id = string(testCase, "id");
            String path = string(object(testCase.members().get("input")), "path");
            String expected = string(object(testCase.members().get("expected")), "outcome");
            TestSupport.test("public Core Reader " + id, () -> {
                boolean accepted;
                VerificationErrorCategory category = null;
                try {
                    VERIFIER.verify(fixtureDirectory.resolve(path));
                    accepted = true;
                } catch (AuralVerificationException rejected) {
                    accepted = false;
                    category = rejected.category();
                }
                String actual = accepted ? "accept" : "reject";
                boolean pass = expected.equals(actual)
                        || (expected.equals("accept-0.2") && accepted);
                System.out.println("CORE_CASE id=" + id + " expected=" + expected
                        + " actual=" + actual + " result=" + (pass ? "PASS" : "FAIL")
                        + (category == null ? "" : " category=" + category));
                TestSupport.assertTrue(pass, "public outcome mismatch for " + id);
            });
        }
        TestSupport.assertEquals(30, required);
    }

    private static void legacyCapabilityBoundary(Path fixtureDirectory) {
        for (String name : List.of("legacy-0.1.aural", "legacy-0.1-zip64-extra.aural")) {
            TestSupport.test("verifier explicitly does not accept legacy " + name, () -> {
                AuralVerificationException failure = TestSupport.expectThrows(
                        AuralVerificationException.class, () -> VERIFIER.verify(fixtureDirectory.resolve(name)));
                TestSupport.assertTrue(failure.category() != null, "legacy rejection must be controlled");
            });
        }
    }

    private static void accept(String name, PackageBuilder builder) {
        TestSupport.test(name, () -> {
            VerifiedAuralPackage result = verify(builder.build().bytes());
            TestSupport.assertEquals("0.2-draft", result.version());
            TestSupport.assertEquals("audio/original.wav", result.originalEntryName());
            TestSupport.assertEquals(result.computedOriginalSha256(), result.declaredOriginalSha256());
            TestSupport.assertEquals(2, result.crc32Results().size());
            TestSupport.assertTrue(result.crc32Results().stream().allMatch(value -> value.verified()),
                    "all CRC32 results must be verified");
            TestSupport.assertEquals(VerifiedAuralPackage.CompletionStatus.COMPLETE, result.status());
        });
    }

    private static void acceptZip64(String name, PackageBuilder builder) {
        TestSupport.test(name, () -> TestSupport.assertTrue(
                verify(builder.build().bytes()).usesZip64(), "ZIP64 use must be reported"));
    }

    private static void reject(String name, PackageBuilder builder, VerificationErrorCategory category) {
        TestSupport.test(name, () -> reject(builder.build().bytes(), category));
    }

    private static void reject(byte[] bytes, VerificationErrorCategory category) throws Exception {
        AuralVerificationException failure = TestSupport.expectThrows(
                AuralVerificationException.class, () -> verify(bytes));
        TestSupport.assertEquals(category, failure.category());
        TestSupport.assertTrue(!failure.detail().isEmpty(), "controlled failure must retain detail");
    }

    private static VerifiedAuralPackage verify(byte[] bytes) throws Exception {
        Path file = Files.createTempFile("aural-complete-verifier-", ".aural");
        try {
            Files.write(file, bytes);
            return VERIFIER.verify(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private static String beforeVersion(String json, String member) {
        return json.replace("\"version\":", member + "\"version\":");
    }

    private static String afterAssets(String json, String member) {
        return json.replace("],\"format\":", "]," + member + "\"format\":");
    }

    private static String replaceHash(String json, String value) {
        int marker = json.indexOf("\"sha256\":\"") + 10;
        int end = json.indexOf('"', marker);
        return json.substring(0, marker) + value + json.substring(end);
    }

    private static String replaceSize(String json, long value) {
        return json.replace("\"size\":" + defaultAudio().length, "\"size\":" + value);
    }

    private static byte[] defaultAudio() {
        return riff("fmt data");
    }

    private static byte[] riff(String tail) {
        return ascii("RIFF0000WAVE" + tail);
    }

    private static byte[] rf64(String tail) {
        return ascii("RF640000WAVE" + tail);
    }

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private static long crc32(byte[] data) {
        CRC32 crc = new CRC32();
        crc.update(data);
        return crc.getValue();
    }

    private static int indexOf(byte[] source, byte[] target) {
        outer: for (int index = 0; index <= source.length - target.length; index++) {
            for (int inner = 0; inner < target.length; inner++) {
                if (source[index + inner] != target[inner]) {
                    continue outer;
                }
            }
            return index;
        }
        throw new AssertionError("target bytes not found");
    }

    private static JsonValue.JsonObject object(JsonValue value) {
        if (value instanceof JsonValue.JsonObject object) {
            return object;
        }
        throw new AssertionError("expected JSON object");
    }

    private static JsonValue.JsonArray array(JsonValue value) {
        if (value instanceof JsonValue.JsonArray array) {
            return array;
        }
        throw new AssertionError("expected JSON array");
    }

    private static String string(JsonValue.JsonObject object, String name) {
        if (object.members().get(name) instanceof JsonValue.JsonString string) {
            return string.value();
        }
        throw new AssertionError("expected JSON string member " + name);
    }

    @FunctionalInterface
    private interface ManifestTransform {
        String apply(String json);
    }

    private static final class Entry {
        private byte[] name;
        private byte[] data;
        private int versionMadeBy = 20;
        private int versionNeeded = 10;
        private int flags;
        private int method;
        private int modificationDate = 0x21;
        private long externalAttributes;
        private byte[] comment = new byte[0];
        private Long localCrc;
        private Long centralCrc;
        private boolean zip64Sizes;

        private Entry(String name, byte[] data) {
            this.name = ascii(name);
            this.data = data.clone();
        }
    }

    private static final class PackageBuilder {
        private byte[] audio = defaultAudio();
        private byte[] rawManifest;
        private ManifestTransform transform = json -> json;
        private boolean audioFirst;
        private boolean reverseCentral;
        private int flags;
        private int versionNeeded = 10;
        private boolean unixRegular;
        private Long manifestLocalCrc;
        private Long manifestCentralCrc;
        private Long audioLocalCrc;
        private Long audioCentralCrc;
        private int audioMethod;
        private byte[] audioComment = new byte[0];
        private boolean thirdEntry;
        private boolean duplicateManifestName;
        private boolean zip64EntrySizes;
        private boolean zip64Terminal;

        private PackageBuilder audio(byte[] value) { audio = value.clone(); return this; }
        private PackageBuilder rawManifest(byte[] value) { rawManifest = value.clone(); return this; }
        private PackageBuilder manifestTransform(ManifestTransform value) { transform = value; return this; }
        private PackageBuilder audioFirst() { audioFirst = true; return this; }
        private PackageBuilder reverseCentral() { reverseCentral = true; return this; }
        private PackageBuilder flags(int value) { flags = value; return this; }
        private PackageBuilder versionNeeded(int value) { versionNeeded = value; return this; }
        private PackageBuilder unixRegularFiles() { unixRegular = true; return this; }
        private PackageBuilder manifestCrc(long value) {
            manifestLocalCrc = value; manifestCentralCrc = value; return this;
        }
        private PackageBuilder audioCrc(long value) {
            audioLocalCrc = value; audioCentralCrc = value; return this;
        }
        private PackageBuilder audioCrcPair(long local, long central) {
            audioLocalCrc = local; audioCentralCrc = central; return this;
        }
        private PackageBuilder audioMethod(int value) { audioMethod = value; return this; }
        private PackageBuilder audioComment(byte[] value) { audioComment = value.clone(); return this; }
        private PackageBuilder thirdEntry() { thirdEntry = true; return this; }
        private PackageBuilder duplicateManifestName() { duplicateManifestName = true; return this; }
        private PackageBuilder zip64EntrySizes() { zip64EntrySizes = true; versionNeeded = 45; return this; }
        private PackageBuilder zip64Terminal() { zip64Terminal = true; return this; }

        private byte[] generatedManifest() {
            String json = "{\"assets\":[{\"media_type\":\"audio/wav\","
                    + "\"path\":\"audio/original.wav\",\"role\":\"original\","
                    + "\"sha256\":\"" + sha256(audio) + "\",\"size\":" + audio.length + "}],"
                    + "\"format\":\"org.aural.audio-evidence\","
                    + "\"original\":\"audio/original.wav\",\"version\":\"0.2-draft\"}";
            return transform.apply(json).getBytes(StandardCharsets.UTF_8);
        }

        private Build build() {
            byte[] manifest = rawManifest == null ? generatedManifest() : rawManifest.clone();
            Entry manifestEntry = new Entry("manifest.json", manifest);
            Entry audioEntry = new Entry(duplicateManifestName ? "manifest.json" : "audio/original.wav", audio);
            configure(manifestEntry, manifestLocalCrc, manifestCentralCrc, 0, new byte[0]);
            configure(audioEntry, audioLocalCrc, audioCentralCrc, audioMethod, audioComment);
            ArrayList<Entry> entries = new ArrayList<>();
            if (audioFirst) {
                entries.add(audioEntry);
                entries.add(manifestEntry);
            } else {
                entries.add(manifestEntry);
                entries.add(audioEntry);
            }
            if (thirdEntry) {
                entries.add(new Entry("extra.bin", ascii("x")));
            }
            List<Integer> centralOrder = new ArrayList<>();
            for (int index = 0; index < entries.size(); index++) {
                centralOrder.add(index);
            }
            if (reverseCentral && centralOrder.size() == 2) {
                centralOrder = List.of(1, 0);
            }
            return writeArchive(entries, centralOrder, zip64Terminal);
        }

        private void configure(Entry entry, Long localCrc, Long centralCrc, int method, byte[] comment) {
            entry.flags = flags;
            entry.versionNeeded = versionNeeded;
            entry.method = method;
            entry.comment = comment.clone();
            entry.localCrc = localCrc;
            entry.centralCrc = centralCrc;
            entry.zip64Sizes = zip64EntrySizes;
            if (unixRegular) {
                entry.versionMadeBy = (3 << 8) | 20;
                entry.externalAttributes = 0x81a4_0000L;
            }
        }
    }

    private static Build writeArchive(List<Entry> entries, List<Integer> centralOrder, boolean zip64Terminal) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        int[] localOffsets = new int[entries.size()];
        int[] dataOffsets = new int[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            Entry entry = entries.get(index);
            localOffsets[index] = output.size();
            byte[] extra = entry.zip64Sizes ? zip64SizeExtra(entry.data.length) : new byte[0];
            write32(output, LOCAL_SIGNATURE);
            write16(output, entry.versionNeeded);
            write16(output, entry.flags);
            write16(output, entry.method);
            write16(output, 0);
            write16(output, entry.modificationDate);
            write32(output, entry.localCrc == null ? crc32(entry.data) : entry.localCrc);
            write32(output, entry.zip64Sizes ? UINT32_MAX : entry.data.length);
            write32(output, entry.zip64Sizes ? UINT32_MAX : entry.data.length);
            write16(output, entry.name.length);
            write16(output, extra.length);
            output.writeBytes(entry.name);
            output.writeBytes(extra);
            dataOffsets[index] = output.size();
            output.writeBytes(entry.data);
        }

        int centralOffset = output.size();
        int[] centralOffsets = new int[entries.size()];
        for (int index : centralOrder) {
            Entry entry = entries.get(index);
            centralOffsets[index] = output.size();
            byte[] extra = entry.zip64Sizes ? zip64SizeExtra(entry.data.length) : new byte[0];
            write32(output, CENTRAL_SIGNATURE);
            write16(output, entry.versionMadeBy);
            write16(output, entry.versionNeeded);
            write16(output, entry.flags);
            write16(output, entry.method);
            write16(output, 0);
            write16(output, entry.modificationDate);
            write32(output, entry.centralCrc == null ? crc32(entry.data) : entry.centralCrc);
            write32(output, entry.zip64Sizes ? UINT32_MAX : entry.data.length);
            write32(output, entry.zip64Sizes ? UINT32_MAX : entry.data.length);
            write16(output, entry.name.length);
            write16(output, extra.length);
            write16(output, entry.comment.length);
            write16(output, 0);
            write16(output, 0);
            write32(output, entry.externalAttributes);
            write32(output, localOffsets[index]);
            output.writeBytes(entry.name);
            output.writeBytes(extra);
            output.writeBytes(entry.comment);
        }
        int centralSize = output.size() - centralOffset;
        if (zip64Terminal) {
            int zip64EocdOffset = output.size();
            write32(output, ZIP64_EOCD_SIGNATURE);
            write64(output, 44);
            write16(output, 45);
            write16(output, 45);
            write32(output, 0);
            write32(output, 0);
            write64(output, entries.size());
            write64(output, entries.size());
            write64(output, centralSize);
            write64(output, centralOffset);
            write32(output, ZIP64_LOCATOR_SIGNATURE);
            write32(output, 0);
            write64(output, zip64EocdOffset);
            write32(output, 1);
        }
        int eocdOffset = output.size();
        write32(output, EOCD_SIGNATURE);
        write16(output, 0);
        write16(output, 0);
        write16(output, zip64Terminal ? 0xffff : entries.size());
        write16(output, zip64Terminal ? 0xffff : entries.size());
        write32(output, zip64Terminal ? UINT32_MAX : centralSize);
        write32(output, zip64Terminal ? UINT32_MAX : centralOffset);
        write16(output, 0);
        return new Build(output.toByteArray(), localOffsets, dataOffsets, centralOffsets, eocdOffset);
    }

    private static byte[] zip64SizeExtra(long size) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        write16(output, 1);
        write16(output, 16);
        write64(output, size);
        write64(output, size);
        return output.toByteArray();
    }

    private static void put16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
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

    private static void write64(ByteArrayOutputStream output, long value) {
        for (int shift = 0; shift < 64; shift += 8) {
            output.write((int) (value >>> shift));
        }
    }

    private record Build(
            byte[] bytes, int[] localOffsets, int[] dataOffsets, int[] centralOffsets, int eocdOffset) {
    }
}
