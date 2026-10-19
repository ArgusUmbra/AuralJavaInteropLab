package org.auralinterop.tests;

import java.util.Arrays;
import java.util.Objects;

final class TestSupport {
    @FunctionalInterface
    interface ThrowingRunnable {
        void run() throws Exception;
    }

    private static int passed;
    private static int failed;

    private TestSupport() {
    }

    static void test(String name, ThrowingRunnable body) {
        try {
            body.run();
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable failure) {
            failed++;
            System.err.println("FAIL " + name + ": " + failure);
            failure.printStackTrace(System.err);
        }
    }

    static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    static void assertFalse(boolean condition, String message) {
        assertTrue(!condition, message);
    }

    static void assertEquals(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    static void assertBytes(byte[] expected, byte[] actual) {
        if (!Arrays.equals(expected, actual)) {
            throw new AssertionError("byte arrays differ; expected length " + expected.length
                    + ", actual length " + actual.length);
        }
    }

    static <T extends Throwable> T expectThrows(Class<T> type, ThrowingRunnable body) {
        try {
            body.run();
        } catch (Throwable failure) {
            if (type.isInstance(failure)) {
                return type.cast(failure);
            }
            throw new AssertionError("expected " + type.getName() + " but caught " + failure, failure);
        }
        throw new AssertionError("expected " + type.getName() + " but no exception was thrown");
    }

    static void finish() {
        System.out.println("RESULT passed=" + passed + " failed=" + failed);
        if (failed != 0) {
            throw new AssertionError(failed + " foundation test(s) failed");
        }
    }
}
