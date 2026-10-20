package org.auralinterop.tests;

import org.auralinterop.zip.ZipArchiveStructure;
import org.auralinterop.zip.ZipStructureParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.zip.CRC32;

/**
 * Opt-in, streaming, genuine-over-4-GiB structural test. It is deliberately not registered by
 * {@link AllTests}; invoke its main method explicitly after reviewing the printed estimates.
 */
public final class Zip64LargeFileTest {
    private static final long AUDIO_SIZE = 0x1_0000_0000L;
    private static final long SAFETY_MARGIN = 512L * 1024 * 1024;
    private static final int BUFFER_SIZE = 1024 * 1024;

    private Zip64LargeFileTest() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 2 || !arguments[0].equals("--run")) {
            System.err.println("usage: Zip64LargeFileTest --run <dedicated-temporary-directory>");
            System.err.println("apparent size: about 4.01 GiB; worst-case physical consumption: about 4.01 GiB");
            System.exit(2);
        }
        Path directory = Path.of(arguments[1]).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        FileStore store = Files.getFileStore(directory);
        long required = Math.addExact(AUDIO_SIZE, SAFETY_MARGIN);
        System.out.println("AURAL ZIP64 opt-in large-file test");
        System.out.println("apparent size estimate: " + AUDIO_SIZE + " bytes plus less than 1 MiB metadata");
        System.out.println("worst-case physical estimate: " + required + " bytes including safety margin");
        System.out.println("available space: " + store.getUsableSpace() + " bytes");
        if (store.getUsableSpace() < required) {
            throw new IOException("insufficient disk space for the opt-in streaming test");
        }

        Path archive = Files.createTempFile(directory, "aural-zip64-large-", ".aural");
        System.out.println("dedicated temporary archive: " + archive);
        try {
            buildArchive(archive);
            ZipArchiveStructure result = new ZipStructureParser().parse(archive);
            if (result.entry("audio/original.wav").uncompressedSize() != AUDIO_SIZE
                    || result.entry("manifest.json").localHeaderOffset() <= 0xffff_ffffL
                    || !result.usesZip64EndRecords()) {
                throw new AssertionError("large ZIP64 archive did not resolve expected 64-bit metadata");
            }
            System.out.println("PASS genuine >4 GiB ZIP64 size and offset accepted structurally");
        } finally {
            Files.deleteIfExists(archive);
            System.out.println("cleanup: temporary archive deleted");
        }
    }

    private static void buildArchive(Path archive) throws IOException {
        byte[] audioName = ascii("audio/original.wav");
        byte[] manifestName = ascii("manifest.json");
        byte[] manifest = ascii("{}");
        byte[] audioExtra = zip64Extra(AUDIO_SIZE, AUDIO_SIZE);
        byte[] zeroBuffer = new byte[BUFFER_SIZE];
        CRC32 audioCrc = new CRC32();

        try (FileChannel channel = FileChannel.open(
                archive, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            long audioLocalOffset = channel.position();
            write(channel, localHeader(audioName, 0, 0xffff_ffffL, 0xffff_ffffL, audioExtra));
            for (long remaining = AUDIO_SIZE; remaining != 0; ) {
                int count = (int) Math.min(remaining, zeroBuffer.length);
                write(channel, ByteBuffer.wrap(zeroBuffer, 0, count));
                audioCrc.update(zeroBuffer, 0, count);
                remaining -= count;
            }

            long manifestLocalOffset = channel.position();
            CRC32 manifestCrc = new CRC32();
            manifestCrc.update(manifest);
            write(channel, localHeader(manifestName, manifestCrc.getValue(), manifest.length, manifest.length, new byte[0]));
            write(channel, ByteBuffer.wrap(manifest));

            long centralOffset = channel.position();
            write(channel, centralHeader(
                    audioName, audioCrc.getValue(), 0xffff_ffffL, 0xffff_ffffL, audioLocalOffset,
                    zip64Extra(AUDIO_SIZE, AUDIO_SIZE)));
            write(channel, centralHeader(
                    manifestName, manifestCrc.getValue(), manifest.length, manifest.length, 0xffff_ffffL,
                    zip64Extra(manifestLocalOffset)));
            long centralSize = channel.position() - centralOffset;
            long zip64Offset = channel.position();
            write(channel, zip64End(centralSize, centralOffset));
            write(channel, zip64Locator(zip64Offset));
            write(channel, classicEnd(centralSize));

            ByteBuffer crc = littleEndian(4);
            put32(crc, audioCrc.getValue());
            crc.flip();
            write(channel, crc, audioLocalOffset + 14);
        }
    }

    private static ByteBuffer localHeader(
            byte[] name, long crc, long compressed, long uncompressed, byte[] extra) {
        ByteBuffer buffer = littleEndian(30 + name.length + extra.length);
        put32(buffer, 0x04034b50L);
        put16(buffer, 45);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 0x21);
        put32(buffer, crc);
        put32(buffer, compressed);
        put32(buffer, uncompressed);
        put16(buffer, name.length);
        put16(buffer, extra.length);
        buffer.put(name).put(extra).flip();
        return buffer;
    }

    private static ByteBuffer centralHeader(
            byte[] name, long crc, long compressed, long uncompressed, long offset, byte[] extra) {
        ByteBuffer buffer = littleEndian(46 + name.length + extra.length);
        put32(buffer, 0x02014b50L);
        put16(buffer, (3 << 8) | 45);
        put16(buffer, 45);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 0x21);
        put32(buffer, crc);
        put32(buffer, compressed);
        put32(buffer, uncompressed);
        put16(buffer, name.length);
        put16(buffer, extra.length);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 0);
        put32(buffer, 0);
        put32(buffer, offset);
        buffer.put(name).put(extra).flip();
        return buffer;
    }

    private static ByteBuffer zip64End(long centralSize, long centralOffset) {
        ByteBuffer buffer = littleEndian(56);
        put32(buffer, 0x06064b50L);
        put64(buffer, 44);
        put16(buffer, (3 << 8) | 45);
        put16(buffer, 45);
        put32(buffer, 0);
        put32(buffer, 0);
        put64(buffer, 2);
        put64(buffer, 2);
        put64(buffer, centralSize);
        put64(buffer, centralOffset);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer zip64Locator(long zip64Offset) {
        ByteBuffer buffer = littleEndian(20);
        put32(buffer, 0x07064b50L);
        put32(buffer, 0);
        put64(buffer, zip64Offset);
        put32(buffer, 1);
        buffer.flip();
        return buffer;
    }

    private static ByteBuffer classicEnd(long centralSize) {
        ByteBuffer buffer = littleEndian(22);
        put32(buffer, 0x06054b50L);
        put16(buffer, 0);
        put16(buffer, 0);
        put16(buffer, 2);
        put16(buffer, 2);
        put32(buffer, centralSize);
        put32(buffer, 0xffff_ffffL);
        put16(buffer, 0);
        buffer.flip();
        return buffer;
    }

    private static byte[] zip64Extra(long... values) {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(1);
        output.write(0);
        output.write(values.length * Long.BYTES);
        output.write(0);
        for (long value : values) {
            for (int index = 0; index < Long.BYTES; index++) {
                output.write((int) (value >>> (8 * index)));
            }
        }
        return output.toByteArray();
    }

    private static ByteBuffer littleEndian(int capacity) {
        return ByteBuffer.allocate(capacity);
    }

    private static void put16(ByteBuffer buffer, int value) {
        buffer.put((byte) value).put((byte) (value >>> 8));
    }

    private static void put32(ByteBuffer buffer, long value) {
        for (int index = 0; index < Integer.BYTES; index++) {
            buffer.put((byte) (value >>> (8 * index)));
        }
    }

    private static void put64(ByteBuffer buffer, long value) {
        for (int index = 0; index < Long.BYTES; index++) {
            buffer.put((byte) (value >>> (8 * index)));
        }
    }

    private static void write(FileChannel channel, ByteBuffer buffer) throws IOException {
        while (buffer.hasRemaining()) {
            channel.write(buffer);
        }
    }

    private static void write(FileChannel channel, ByteBuffer buffer, long position) throws IOException {
        long cursor = position;
        while (buffer.hasRemaining()) {
            cursor += channel.write(buffer, cursor);
        }
    }

    private static byte[] ascii(String value) {
        byte[] result = new byte[value.length()];
        for (int index = 0; index < value.length(); index++) {
            result[index] = (byte) value.charAt(index);
        }
        return result;
    }
}
