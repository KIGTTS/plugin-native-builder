package javax.lang.model;

/**
 * Android does not ship the Java compiler API. ECJ's batch FileSystem probes
 * for RELEASE_12 to decide whether to use the desktop JRT filesystem.
 * The Android builder supplies an explicit boot classpath and targets Java 8.
 */
public enum SourceVersion {
    RELEASE_0, RELEASE_1, RELEASE_2, RELEASE_3, RELEASE_4,
    RELEASE_5, RELEASE_6, RELEASE_7, RELEASE_8;

    public static SourceVersion latest() {
        return RELEASE_8;
    }

    public static SourceVersion latestSupported() {
        return RELEASE_8;
    }
}
