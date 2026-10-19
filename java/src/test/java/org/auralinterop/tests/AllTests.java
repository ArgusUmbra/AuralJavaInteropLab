package org.auralinterop.tests;

import java.nio.file.Path;

public final class AllTests {
    private AllTests() {
    }

    public static void main(String[] arguments) {
        if (arguments.length != 1) {
            System.err.println("usage: AllTests <golden-manifest.json>");
            System.exit(2);
        }

        BoundedFileReaderTest.register();
        StrictJsonParserTest.register();
        JcsCanonicalizerTest.register(Path.of(arguments[0]));
        TestSupport.finish();
    }
}
