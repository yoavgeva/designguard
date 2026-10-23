package io.github.yoavgeva.designguard.model;

/** Java features some rules recommend, and the release that introduced each. */
public enum JavaFeature {

    /** {@code switch} expressions, which the compiler checks for exhaustiveness. */
    SWITCH_EXPRESSIONS(14, "switch expressions"),

    /** {@code record} classes. */
    RECORDS(16, "records"),

    /** {@code sealed} classes and interfaces. */
    SEALED_TYPES(17, "sealed types"),

    /** Type patterns in {@code switch}. */
    SWITCH_ON_TYPES(21, "switch on types"),

    /** Virtual threads. */
    VIRTUAL_THREADS(21, "virtual threads");

    private final int firstRelease;
    private final String name;

    JavaFeature(int firstRelease, String name) {
        this.firstRelease = firstRelease;
        this.name = name;
    }

    /**
     * The Java version that introduced the feature.
     *
     * @return the release, e.g. 16 for records
     */
    public int release() {
        return firstRelease;
    }

    /**
     * Names the feature for messages.
     *
     * @return e.g. {@code records}
     */
    public String description() {
        return name;
    }

    /**
     * Whether code compiled for a Java version can use the feature.
     *
     * @param javaRelease the version the code targets
     * @return true if the feature exists in that version
     */
    public boolean isAvailableIn(int javaRelease) {
        return javaRelease >= firstRelease;
    }
}
