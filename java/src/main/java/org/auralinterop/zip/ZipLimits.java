package org.auralinterop.zip;

/** Normative Draft 0.2 ZIP-related resource limits. */
public final class ZipLimits {
    public static final int ENTRY_COUNT = 2;
    public static final long MAXIMUM_MANIFEST_SIZE = 1_048_576L;
    public static final long MAXIMUM_ORIGINAL_SIZE = 8_589_934_592L;
    public static final int MAXIMUM_CENTRAL_DIRECTORY_SIZE = 4_194_304;
    public static final long MAXIMUM_ARCHIVE_SIZE = 8_606_711_808L;

    private ZipLimits() {
    }
}
