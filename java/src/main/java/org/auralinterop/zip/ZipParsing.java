package org.auralinterop.zip;

import org.auralinterop.io.BoundedFileReader;
import org.auralinterop.io.BoundedReadException;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Arrays;

import static org.auralinterop.zip.ZipStructureException.Reason;

final class ZipParsing {
    static final long LOCAL_SIGNATURE = 0x04034b50L;
    static final long CENTRAL_SIGNATURE = 0x02014b50L;
    static final long EOCD_SIGNATURE = 0x06054b50L;
    static final long ZIP64_EOCD_SIGNATURE = 0x06064b50L;
    static final long ZIP64_LOCATOR_SIGNATURE = 0x07064b50L;
    static final int UTF8_FLAG = 1 << 11;
    static final long UINT32_SENTINEL = 0xffff_ffffL;
    static final int UINT16_SENTINEL = 0xffff;
    static final byte[] MANIFEST_NAME = ascii("manifest.json");
    static final byte[] ORIGINAL_NAME = ascii("audio/original.wav");

    private ZipParsing() {
    }

    static int unsignedShort(byte[] bytes, int offset) throws ZipStructureException {
        require(bytes, offset, 2);
        return Byte.toUnsignedInt(bytes[offset]) | (Byte.toUnsignedInt(bytes[offset + 1]) << 8);
    }

    static long unsignedInt(byte[] bytes, int offset) throws ZipStructureException {
        require(bytes, offset, 4);
        return Integer.toUnsignedLong(
                Byte.toUnsignedInt(bytes[offset])
                        | (Byte.toUnsignedInt(bytes[offset + 1]) << 8)
                        | (Byte.toUnsignedInt(bytes[offset + 2]) << 16)
                        | (Byte.toUnsignedInt(bytes[offset + 3]) << 24));
    }

    static long boundedUnsignedLong(byte[] bytes, int offset, long maximum, String field)
            throws ZipStructureException {
        require(bytes, offset, Long.BYTES);
        long value = 0;
        for (int index = Long.BYTES - 1; index >= 0; index--) {
            value = (value << 8) | Byte.toUnsignedLong(bytes[offset + index]);
        }
        if (value < 0) {
            throw new ZipStructureException(
                    Reason.ZIP64_VALUE, field + " has its unsigned 64-bit high bit set");
        }
        if (value > maximum) {
            throw new ZipStructureException(
                    Reason.ZIP64_VALUE, field + " exceeds the AURAL supported range");
        }
        return value;
    }

    static void require(byte[] bytes, int offset, int length) throws ZipStructureException {
        if (offset < 0 || length < 0 || offset > bytes.length - length) {
            throw new ZipStructureException(Reason.CENTRAL_DIRECTORY_LENGTH, "truncated ZIP record");
        }
    }

    static long add(long left, long right, Reason reason, String message) throws ZipStructureException {
        try {
            return BoundedFileReader.checkedRangeEnd(left, right);
        } catch (BoundedReadException failure) {
            throw new ZipStructureException(reason, message, failure);
        }
    }

    static int addInt(int left, int right, Reason reason, String message) throws ZipStructureException {
        try {
            return Math.addExact(left, right);
        } catch (ArithmeticException failure) {
            throw new ZipStructureException(reason, message, failure);
        }
    }

    static String allowedName(byte[] rawName) throws ZipStructureException {
        if (Arrays.equals(rawName, MANIFEST_NAME)) {
            return "manifest.json";
        }
        if (Arrays.equals(rawName, ORIGINAL_NAME)) {
            return "audio/original.wav";
        }
        throw new ZipStructureException(Reason.INVALID_NAME, "entry name is not an exact permitted ASCII name");
    }

    static void validateFlags(int flags) throws ZipStructureException {
        if ((flags & ~UTF8_FLAG) != 0) {
            throw new ZipStructureException(Reason.UNSUPPORTED_FLAGS, "only general-purpose bit 11 may be set");
        }
    }

    static void validateMethod(int method) throws ZipStructureException {
        if (method != 0) {
            throw new ZipStructureException(Reason.UNSUPPORTED_COMPRESSION, "only stored method 0 is permitted");
        }
    }

    static void validateVersionNeeded(int versionNeeded) throws ZipStructureException {
        if (versionNeeded != 10 && versionNeeded != 20 && versionNeeded != 45) {
            throw new ZipStructureException(
                    Reason.INVALID_VERSION_NEEDED, "version-needed must be 10, 20, or 45");
        }
    }

    static void validateDosTimestamp(int time, int date) throws ZipStructureException {
        int second = (time & 0x1f) * 2;
        int minute = (time >>> 5) & 0x3f;
        int hour = (time >>> 11) & 0x1f;
        int day = date & 0x1f;
        int month = (date >>> 5) & 0x0f;
        int year = 1980 + ((date >>> 9) & 0x7f);
        if (second > 59 || minute > 59 || hour > 23) {
            throw new ZipStructureException(Reason.INVALID_TIMESTAMP, "invalid MS-DOS modification time");
        }
        try {
            LocalDate.of(year, month, day);
        } catch (DateTimeException failure) {
            throw new ZipStructureException(
                    Reason.INVALID_TIMESTAMP, "invalid MS-DOS modification date", failure);
        }
    }

    static void validateExternalAttributes(long attributes) throws ZipStructureException {
        if ((attributes & 0x10L) != 0) {
            throw new ZipStructureException(Reason.FILE_TYPE, "DOS directory attribute is prohibited");
        }
        int unixFileType = ((int) (attributes >>> 16)) & 0xf000;
        if (unixFileType != 0 && unixFileType != 0x8000) {
            throw new ZipStructureException(Reason.FILE_TYPE, "non-regular Unix file type is prohibited");
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
