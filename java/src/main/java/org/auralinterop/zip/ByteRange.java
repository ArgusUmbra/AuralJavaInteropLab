package org.auralinterop.zip;

/** A validated half-open physical byte range in the opened archive snapshot. */
public record ByteRange(long startInclusive, long endExclusive) {
    public ByteRange {
        if (startInclusive < 0 || endExclusive < startInclusive) {
            throw new IllegalArgumentException("invalid byte range");
        }
    }

    public long length() {
        return endExclusive - startInclusive;
    }
}
