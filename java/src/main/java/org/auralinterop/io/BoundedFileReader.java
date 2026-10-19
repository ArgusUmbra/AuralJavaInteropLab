package org.auralinterop.io;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

import static org.auralinterop.io.BoundedReadException.Reason;

/**
 * Small read-only positional file reader with explicit allocation and range bounds.
 * The size captured at open time is the maximum readable extent for this instance.
 */
public final class BoundedFileReader implements AutoCloseable {
    private final FileChannel channel;
    private final long openedSize;
    private final int maximumAllocation;
    private boolean closed;

    private BoundedFileReader(FileChannel channel, long openedSize, int maximumAllocation) {
        this.channel = channel;
        this.openedSize = openedSize;
        this.maximumAllocation = maximumAllocation;
    }

    public static BoundedFileReader open(Path path, int maximumAllocation) throws IOException {
        Objects.requireNonNull(path, "path");
        if (maximumAllocation < 0) {
            throw new IllegalArgumentException("maximumAllocation must be non-negative");
        }

        FileChannel channel = FileChannel.open(path, StandardOpenOption.READ);
        try {
            return new BoundedFileReader(channel, channel.size(), maximumAllocation);
        } catch (Throwable failure) {
            channel.close();
            throw failure;
        }
    }

    public long size() throws BoundedReadException {
        ensureOpen();
        return openedSize;
    }

    public int maximumAllocation() {
        return maximumAllocation;
    }

    public boolean isOpen() {
        return !closed && channel.isOpen();
    }

    public byte[] readExact(long offset, long length) throws IOException {
        ensureOpen();
        long end = checkedRangeEnd(offset, length);
        if (length > maximumAllocation || length > Integer.MAX_VALUE) {
            throw new BoundedReadException(
                    Reason.LENGTH_LIMIT,
                    "requested length " + length + " exceeds allocation limit " + maximumAllocation);
        }
        if (end > openedSize) {
            throw new BoundedReadException(
                    Reason.UNEXPECTED_EOF,
                    "requested range ends at " + end + " beyond opened size " + openedSize);
        }

        byte[] result = new byte[(int) length];
        if (result.length == 0) {
            return result;
        }

        ByteBuffer destination = ByteBuffer.wrap(result);
        long position = offset;
        while (destination.hasRemaining()) {
            int count = channel.read(destination, position);
            if (count < 0) {
                throw new BoundedReadException(Reason.UNEXPECTED_EOF, "unexpected EOF at offset " + position);
            }
            if (count == 0) {
                throw new BoundedReadException(Reason.NO_PROGRESS, "file read made no progress at offset " + position);
            }
            position = Math.addExact(position, count);
        }
        return result;
    }

    public int readUnsignedByte(long offset) throws IOException {
        return Byte.toUnsignedInt(readExact(offset, 1)[0]);
    }

    public int readUnsignedShortLE(long offset) throws IOException {
        byte[] bytes = readExact(offset, 2);
        return Byte.toUnsignedInt(bytes[0])
                | (Byte.toUnsignedInt(bytes[1]) << 8);
    }

    public long readUnsignedIntLE(long offset) throws IOException {
        byte[] bytes = readExact(offset, 4);
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(bytes[0])
                        | (Byte.toUnsignedInt(bytes[1]) << 8)
                        | (Byte.toUnsignedInt(bytes[2]) << 16)
                        | (Byte.toUnsignedInt(bytes[3]) << 24));
    }

    public BigInteger readUnsignedLongLE(long offset) throws IOException {
        byte[] littleEndian = readExact(offset, 8);
        byte[] positiveBigEndian = new byte[9];
        for (int index = 0; index < littleEndian.length; index++) {
            positiveBigEndian[8 - index] = littleEndian[index];
        }
        return new BigInteger(positiveBigEndian);
    }

    public static long checkedRangeEnd(long offset, long length) throws BoundedReadException {
        if (offset < 0) {
            throw new BoundedReadException(Reason.NEGATIVE_OFFSET, "offset must be non-negative");
        }
        if (length < 0) {
            throw new BoundedReadException(Reason.NEGATIVE_LENGTH, "length must be non-negative");
        }
        try {
            return Math.addExact(offset, length);
        } catch (ArithmeticException overflow) {
            throw new BoundedReadException(Reason.RANGE_OVERFLOW, "offset plus length overflows signed long");
        }
    }

    public static long checkedLengthAdd(long left, long right, long maximum) throws BoundedReadException {
        if (left < 0 || right < 0 || maximum < 0) {
            throw new BoundedReadException(Reason.NEGATIVE_LENGTH, "length operands and maximum must be non-negative");
        }
        long sum;
        try {
            sum = Math.addExact(left, right);
        } catch (ArithmeticException overflow) {
            throw new BoundedReadException(Reason.RANGE_OVERFLOW, "length addition overflows signed long");
        }
        if (sum > maximum) {
            throw new BoundedReadException(Reason.LENGTH_LIMIT, "combined length exceeds configured maximum");
        }
        return sum;
    }

    private void ensureOpen() throws BoundedReadException {
        if (closed || !channel.isOpen()) {
            throw new BoundedReadException(Reason.CLOSED, "reader is closed");
        }
    }

    @Override
    public void close() throws IOException {
        if (!closed) {
            closed = true;
            channel.close();
        }
    }
}
