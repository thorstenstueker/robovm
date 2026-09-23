package org.robovm.compiler;

import soot.SourceLocator;

import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Simple helper to resolve the boot classpath of the host JDK (9+) for tests.
 * The RoboVM toolchain requires JDK 17+ to build, so the legacy
 * {@code sun.boot.class.path} lookup for Java 8 is gone.
 * @author dkimitsa
 */
public class ClassPathUtils {

    public static String getBcPath() {
        return SourceLocator.DUMMY_CLASSPATH_JDK9_FS;
    }

    public static List<File> getBcPaths() {
        return Collections.singletonList(new File(SourceLocator.DUMMY_CLASSPATH_JDK9_FS));
    }
}
