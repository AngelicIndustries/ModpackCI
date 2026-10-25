package io.github.angelicindustries.modpackci;

/** How the game runs: plain Forge on Java 8, or lwjgl3ify on Java 25. */
public enum Flavor {
    JAVA8(8, "Java 8", "", "java8"),
    JAVA25(25, "Java 25", "25", "java25");

    public final int javaVersion;
    public final String label;
    /** Appended to task names, like GTNH's runClient / runClient25. */
    public final String taskSuffix;
    public final String id;

    Flavor(int javaVersion, String label, String taskSuffix, String id) {
        this.javaVersion = javaVersion;
        this.label = label;
        this.taskSuffix = taskSuffix;
        this.id = id;
    }
}
