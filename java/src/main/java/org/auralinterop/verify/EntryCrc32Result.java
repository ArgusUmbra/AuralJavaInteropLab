package org.auralinterop.verify;

import java.util.Objects;

/** Successful unsigned ZIP CRC32 comparison for one required entry. */
public record EntryCrc32Result(String entryName, long declaredCrc32, long computedCrc32) {
    public EntryCrc32Result {
        Objects.requireNonNull(entryName, "entryName");
        requireUnsigned32(declaredCrc32, "declaredCrc32");
        requireUnsigned32(computedCrc32, "computedCrc32");
        if (declaredCrc32 != computedCrc32) {
            throw new IllegalArgumentException("a successful CRC32 result must match");
        }
    }

    public boolean verified() {
        return true;
    }

    private static void requireUnsigned32(long value, String name) {
        if (value < 0 || value > 0xffff_ffffL) {
            throw new IllegalArgumentException(name + " must be an unsigned 32-bit value");
        }
    }
}
