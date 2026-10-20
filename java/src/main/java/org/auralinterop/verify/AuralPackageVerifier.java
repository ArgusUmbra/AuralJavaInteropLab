package org.auralinterop.verify;

import org.auralinterop.io.BoundedFileReader;
import org.auralinterop.manifest.Manifest;
import org.auralinterop.manifest.ManifestValidationException;
import org.auralinterop.manifest.ManifestValidator;
import org.auralinterop.zip.ZipArchiveStructure;
import org.auralinterop.zip.ZipEntryStructure;
import org.auralinterop.zip.ZipLimits;
import org.auralinterop.zip.ZipStructureException;
import org.auralinterop.zip.ZipStructureParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.zip.CRC32;

import static org.auralinterop.verify.VerificationErrorCategory.CRC32_MISMATCH;
import static org.auralinterop.verify.VerificationErrorCategory.INVALID_WAV_IDENTIFICATION;
import static org.auralinterop.verify.VerificationErrorCategory.IO_OR_TRUNCATED_READ_FAILURE;
import static org.auralinterop.verify.VerificationErrorCategory.MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE;
import static org.auralinterop.verify.VerificationErrorCategory.MANIFEST_SEMANTIC_FAILURE;
import static org.auralinterop.verify.VerificationErrorCategory.ORIGINAL_BYTE_COUNT_MISMATCH;
import static org.auralinterop.verify.VerificationErrorCategory.ORIGINAL_SHA256_MISMATCH;
import static org.auralinterop.verify.VerificationErrorCategory.REQUIRED_ENTRY_MISSING_OR_MISMATCHED;
import static org.auralinterop.verify.VerificationErrorCategory.RESOURCE_LIMIT_VIOLATION;
import static org.auralinterop.verify.VerificationErrorCategory.ZIP_STRUCTURAL_FAILURE;

/** Complete, read-only AURAL Draft 0.2 Core package verifier. */
public final class AuralPackageVerifier {
    private static final String MANIFEST_NAME = "manifest.json";
    private static final String ORIGINAL_NAME = "audio/original.wav";
    private static final int STREAM_BUFFER_SIZE = 64 * 1024;

    private final ZipStructureParser zipParser = new ZipStructureParser();
    private final ManifestValidator manifestValidator = new ManifestValidator();

    /**
     * Verify one package through a single caller-owned bounded reader and its read-only channel.
     * The operation performs no extraction and retains no original payload bytes.
     */
    public VerifiedAuralPackage verify(Path archivePath) throws AuralVerificationException {
        Objects.requireNonNull(archivePath, "archivePath");
        try (BoundedFileReader reader = BoundedFileReader.open(
                archivePath, ZipLimits.MAXIMUM_CENTRAL_DIRECTORY_SIZE)) {
            return verifyOpenReader(reader);
        } catch (AuralVerificationException controlled) {
            throw controlled;
        } catch (IOException failure) {
            throw failure(IO_OR_TRUNCATED_READ_FAILURE, "IO", "archive I/O failed", failure);
        }
    }

    private VerifiedAuralPackage verifyOpenReader(BoundedFileReader reader)
            throws IOException, AuralVerificationException {
        ZipArchiveStructure structure;
        try {
            structure = zipParser.parse(reader);
        } catch (ZipStructureException invalidZip) {
            throw mapZipFailure(invalidZip);
        }
        requireUnchangedSize(reader, structure.archiveSize(), "after ZIP structural validation");

        ZipEntryStructure manifestEntry = findEntry(structure, MANIFEST_NAME);
        ZipEntryStructure originalEntry = findEntry(structure, ORIGINAL_NAME);
        if (manifestEntry.uncompressedSize() > ManifestValidator.MAXIMUM_MANIFEST_BYTES) {
            throw failure(RESOURCE_LIMIT_VIOLATION, "MANIFEST_SIZE",
                    "manifest declaration exceeds the 1 MiB limit");
        }

        byte[] manifestBytes = reader.readExact(manifestEntry.dataOffset(), manifestEntry.uncompressedSize());
        Manifest manifest = validateManifest(manifestBytes);
        validateRequiredIdentity(manifest, originalEntry);
        if (manifest.asset().size() != originalEntry.uncompressedSize()) {
            throw failure(ORIGINAL_BYTE_COUNT_MISMATCH, "MANIFEST_ZIP_SIZE",
                    "manifest original size " + manifest.asset().size()
                            + " differs from ZIP original size " + originalEntry.uncompressedSize());
        }

        ArrayList<EntryCrc32Result> crcResults = new ArrayList<>(2);
        long manifestCrc = crc32(manifestBytes);
        requireCrc(manifestEntry, manifestCrc);
        crcResults.add(new EntryCrc32Result(MANIFEST_NAME, manifestEntry.crc32(), manifestCrc));

        OriginalIntegrity original = streamOriginal(reader, originalEntry);
        if (original.byteCount() != originalEntry.uncompressedSize()) {
            throw failure(ORIGINAL_BYTE_COUNT_MISMATCH, "STREAMED_ZIP_SIZE",
                    "streamed original byte count differs from its validated ZIP size");
        }
        requireCrc(originalEntry, original.crc32());
        crcResults.add(new EntryCrc32Result(ORIGINAL_NAME, originalEntry.crc32(), original.crc32()));
        requireWaveIdentification(original.prefix(), original.byteCount());
        if (!original.sha256().equals(manifest.asset().sha256())) {
            throw failure(ORIGINAL_SHA256_MISMATCH, "MANIFEST_SHA256",
                    "computed original SHA-256 differs from the manifest declaration");
        }

        requireUnchangedSize(reader, structure.archiveSize(), "at verification completion");
        return new VerifiedAuralPackage(
                manifest.version(),
                manifest,
                originalEntry.name(),
                original.byteCount(),
                original.sha256(),
                manifest.asset().sha256(),
                crcResults,
                structure.usesZip64(),
                VerifiedAuralPackage.CompletionStatus.COMPLETE);
    }

    private Manifest validateManifest(byte[] bytes) throws AuralVerificationException {
        try {
            return manifestValidator.validate(bytes);
        } catch (ManifestValidationException invalidManifest) {
            VerificationErrorCategory category = switch (invalidManifest.stage()) {
                case JSON_SYNTAX, CANONICALIZATION -> MANIFEST_PARSING_OR_CANONICALIZATION_FAILURE;
                case SCHEMA, SEMANTIC, UNSUPPORTED_CRITICAL_EXTENSION -> MANIFEST_SEMANTIC_FAILURE;
            };
            if (invalidManifest.reason() == ManifestValidationException.Reason.RESOURCE_LIMIT) {
                category = RESOURCE_LIMIT_VIOLATION;
            }
            throw failure(category, invalidManifest.stage() + ":" + invalidManifest.location(),
                    "manifest validation failed: " + invalidManifest.getMessage(), invalidManifest);
        }
    }

    private static ZipEntryStructure findEntry(ZipArchiveStructure structure, String name)
            throws AuralVerificationException {
        return structure.entries().stream()
                .filter(entry -> entry.name().equals(name))
                .findFirst()
                .orElseThrow(() -> failure(REQUIRED_ENTRY_MISSING_OR_MISMATCHED, "MISSING_ENTRY",
                        "required archive entry is missing: " + name));
    }

    private static void validateRequiredIdentity(Manifest manifest, ZipEntryStructure originalEntry)
            throws AuralVerificationException {
        Manifest.Asset asset = manifest.asset();
        if (!manifest.original().equals(asset.path()) || !asset.path().equals(originalEntry.name())
                || !asset.role().equals("original") || !asset.mediaType().equals("audio/wav")) {
            throw failure(REQUIRED_ENTRY_MISSING_OR_MISMATCHED, "ASSET_IDENTITY",
                    "manifest asset identity does not exactly match the required original entry");
        }
    }

    private static OriginalIntegrity streamOriginal(BoundedFileReader reader, ZipEntryStructure entry)
            throws IOException {
        CRC32 crc = new CRC32();
        MessageDigest digest = sha256Digest();
        byte[] prefix = new byte[12];
        ByteBuffer buffer = ByteBuffer.allocate(STREAM_BUFFER_SIZE);
        long remaining = entry.dataRange().length();
        long position = entry.dataOffset();
        long count = 0;
        int prefixLength = 0;

        while (remaining > 0) {
            int chunk = (int) Math.min(buffer.capacity(), remaining);
            buffer.clear();
            buffer.limit(chunk);
            reader.readExact(position, buffer);
            buffer.flip();
            int copy = Math.min(prefix.length - prefixLength, chunk);
            for (int index = 0; index < copy; index++) {
                prefix[prefixLength + index] = buffer.get(index);
            }
            prefixLength += copy;
            crc.update(buffer.asReadOnlyBuffer());
            digest.update(buffer.asReadOnlyBuffer());
            position = Math.addExact(position, chunk);
            count = Math.addExact(count, chunk);
            remaining -= chunk;
        }
        return new OriginalIntegrity(count, crc.getValue(), HexFormat.of().formatHex(digest.digest()), prefix);
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossibleOnJava21) {
            throw new IllegalStateException("Java 21 runtime does not provide required SHA-256", impossibleOnJava21);
        }
    }

    private static long crc32(byte[] bytes) {
        CRC32 crc = new CRC32();
        crc.update(bytes);
        return crc.getValue();
    }

    private static void requireCrc(ZipEntryStructure entry, long computed)
            throws AuralVerificationException {
        if (entry.crc32() != computed) {
            throw failure(CRC32_MISMATCH, entry.name(),
                    "computed ZIP CRC32 does not match the declaration for " + entry.name());
        }
    }

    private static void requireWaveIdentification(byte[] prefix, long byteCount)
            throws AuralVerificationException {
        boolean family = matches(prefix, 0, "RIFF") || matches(prefix, 0, "RF64");
        if (byteCount < 12 || !family || !matches(prefix, 8, "WAVE")) {
            throw failure(INVALID_WAV_IDENTIFICATION, "WAVE_PREFIX",
                    "original must have RIFF or RF64 at offsets 0-3 and WAVE at offsets 8-11");
        }
    }

    private static boolean matches(byte[] bytes, int offset, String ascii) {
        if (bytes.length < offset + ascii.length()) {
            return false;
        }
        for (int index = 0; index < ascii.length(); index++) {
            if (bytes[offset + index] != (byte) ascii.charAt(index)) {
                return false;
            }
        }
        return true;
    }

    private static void requireUnchangedSize(BoundedFileReader reader, long expected, String stage)
            throws IOException, AuralVerificationException {
        long actual = reader.currentSize();
        if (actual != expected) {
            throw failure(IO_OR_TRUNCATED_READ_FAILURE, "CONCURRENT_SIZE_CHANGE",
                    "archive size changed " + stage + ": expected " + expected + ", observed " + actual);
        }
    }

    private static AuralVerificationException mapZipFailure(ZipStructureException failure) {
        VerificationErrorCategory category = switch (failure.reason()) {
            case ARCHIVE_TOO_LARGE, ENTRY_COUNT, CENTRAL_DIRECTORY_LIMIT, SIZE_LIMIT ->
                    RESOURCE_LIMIT_VIOLATION;
            case INVALID_NAME, DUPLICATE_NAME -> REQUIRED_ENTRY_MISSING_OR_MISMATCHED;
            default -> ZIP_STRUCTURAL_FAILURE;
        };
        return failure(category, failure.reason().name(),
                "ZIP structural validation failed: " + failure.getMessage(), failure);
    }

    private static AuralVerificationException failure(
            VerificationErrorCategory category, String detail, String message) {
        return new AuralVerificationException(category, detail, message);
    }

    private static AuralVerificationException failure(
            VerificationErrorCategory category, String detail, String message, Throwable cause) {
        return new AuralVerificationException(category, detail, message, cause);
    }

    private record OriginalIntegrity(long byteCount, long crc32, String sha256, byte[] prefix) {
        private OriginalIntegrity {
            prefix = prefix.clone();
        }

        @Override
        public byte[] prefix() {
            return prefix.clone();
        }
    }
}
