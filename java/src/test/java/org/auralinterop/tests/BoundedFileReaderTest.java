package org.auralinterop.tests;

import org.auralinterop.io.BoundedFileReader;
import org.auralinterop.io.BoundedReadException;

import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.auralinterop.tests.TestSupport.assertBytes;
import static org.auralinterop.tests.TestSupport.assertEquals;
import static org.auralinterop.tests.TestSupport.assertFalse;
import static org.auralinterop.tests.TestSupport.assertTrue;
import static org.auralinterop.tests.TestSupport.expectThrows;
import static org.auralinterop.tests.TestSupport.test;

final class BoundedFileReaderTest {
    private BoundedFileReaderTest() {
    }

    static void register() {
        test("binary little-endian primitives", BoundedFileReaderTest::littleEndianPrimitives);
        test("binary zero-length reads", BoundedFileReaderTest::zeroLengthReads);
        test("binary read near EOF", BoundedFileReaderTest::readNearEnd);
        test("binary truncated input", BoundedFileReaderTest::truncatedInput);
        test("binary short read after truncation", BoundedFileReaderTest::shortReadAfterTruncation);
        test("binary oversized requested length", BoundedFileReaderTest::oversizedLength);
        test("binary negative offset", BoundedFileReaderTest::negativeOffset);
        test("binary negative length", BoundedFileReaderTest::negativeLength);
        test("binary range addition overflow", BoundedFileReaderTest::rangeOverflow);
        test("binary checked length overflow", BoundedFileReaderTest::lengthOverflow);
        test("binary exact file boundary", BoundedFileReaderTest::exactBoundary);
        test("binary repeated positional reads", BoundedFileReaderTest::repeatedReads);
        test("binary deterministic descriptor closure", BoundedFileReaderTest::descriptorClosure);
    }

    private static byte[] sampleBytes() {
        return new byte[] {
                0x01, 0x02, 0x03, 0x04,
                0x05, 0x06, 0x07, 0x08,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF,
                (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF
        };
    }

    private static Path temporarySample() throws Exception {
        Path path = Files.createTempFile("aural-bounded-reader-", ".bin");
        Files.write(path, sampleBytes());
        return path;
    }

    private static void littleEndianPrimitives() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            assertEquals(1, reader.readUnsignedByte(0));
            assertEquals(0x0201, reader.readUnsignedShortLE(0));
            assertEquals(0x04030201L, reader.readUnsignedIntLE(0));
            assertEquals(new BigInteger("18446744073709551615"), reader.readUnsignedLongLE(8));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void zeroLengthReads() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            assertEquals(0, reader.readExact(0, 0).length);
            assertEquals(0, reader.readExact(reader.size(), 0).length);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void readNearEnd() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            assertBytes(new byte[] {(byte) 0xFF, (byte) 0xFF}, reader.readExact(14, 2));
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void truncatedInput() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            BoundedReadException failure = expectThrows(BoundedReadException.class,
                    () -> reader.readExact(15, 2));
            assertEquals(BoundedReadException.Reason.UNEXPECTED_EOF, failure.reason());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void shortReadAfterTruncation() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            try (FileChannel writer = FileChannel.open(path, StandardOpenOption.WRITE)) {
                writer.truncate(8);
            }
            BoundedReadException failure = expectThrows(BoundedReadException.class,
                    () -> reader.readExact(0, 16));
            assertEquals(BoundedReadException.Reason.UNEXPECTED_EOF, failure.reason());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void oversizedLength() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 8)) {
            BoundedReadException failure = expectThrows(BoundedReadException.class,
                    () -> reader.readExact(0, 9));
            assertEquals(BoundedReadException.Reason.LENGTH_LIMIT, failure.reason());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void negativeOffset() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            BoundedReadException failure = expectThrows(BoundedReadException.class,
                    () -> reader.readExact(-1, 1));
            assertEquals(BoundedReadException.Reason.NEGATIVE_OFFSET, failure.reason());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void negativeLength() throws Exception {
        BoundedReadException failure = expectThrows(BoundedReadException.class,
                () -> BoundedFileReader.checkedRangeEnd(0, -1));
        assertEquals(BoundedReadException.Reason.NEGATIVE_LENGTH, failure.reason());
    }

    private static void rangeOverflow() throws Exception {
        BoundedReadException failure = expectThrows(BoundedReadException.class,
                () -> BoundedFileReader.checkedRangeEnd(Long.MAX_VALUE, 1));
        assertEquals(BoundedReadException.Reason.RANGE_OVERFLOW, failure.reason());
    }

    private static void lengthOverflow() throws Exception {
        BoundedReadException failure = expectThrows(BoundedReadException.class,
                () -> BoundedFileReader.checkedLengthAdd(Long.MAX_VALUE, 1, Long.MAX_VALUE));
        assertEquals(BoundedReadException.Reason.RANGE_OVERFLOW, failure.reason());

        BoundedReadException limited = expectThrows(BoundedReadException.class,
                () -> BoundedFileReader.checkedLengthAdd(6, 5, 10));
        assertEquals(BoundedReadException.Reason.LENGTH_LIMIT, limited.reason());
    }

    private static void exactBoundary() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            assertBytes(sampleBytes(), reader.readExact(0, 16));
            assertEquals(16L, reader.size());
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void repeatedReads() throws Exception {
        Path path = temporarySample();
        try (BoundedFileReader reader = BoundedFileReader.open(path, 16)) {
            byte[] first = reader.readExact(2, 5);
            byte[] second = reader.readExact(2, 5);
            assertBytes(first, second);
            assertBytes(new byte[] {0x03, 0x04, 0x05, 0x06, 0x07}, second);
        } finally {
            Files.deleteIfExists(path);
        }
    }

    private static void descriptorClosure() throws Exception {
        Path path = temporarySample();
        BoundedFileReader reader = BoundedFileReader.open(path, 16);
        assertTrue(reader.isOpen(), "reader should start open");
        reader.close();
        reader.close();
        assertFalse(reader.isOpen(), "reader should report closed");
        BoundedReadException failure = expectThrows(BoundedReadException.class,
                () -> reader.readExact(0, 1));
        assertEquals(BoundedReadException.Reason.CLOSED, failure.reason());
        Files.delete(path);
    }
}
