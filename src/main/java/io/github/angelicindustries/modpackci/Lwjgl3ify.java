package io.github.angelicindustries.modpackci;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * lwjgl3ify, which runs 1.7.10 on modern Java. The Java 25 flavor needs it, so it's added when
 * the pack doesn't list it. The Java 8 flavor can't have it: a Java 8 server exits on it and a
 * Java 8 client hands over to its relauncher.
 */
public final class Lwjgl3ify {
    public static final String DEFAULT_VERSION = "3.0.39";
    public static final String REQUIRED_FORGE = "10.13.4.1614";
    private static final String RELEASES = "https://github.com/GTNewHorizons/lwjgl3ify/releases/download/";
    private static final String UNIMIXINS_URL =
            "https://cdn.modrinth.com/data/ghjoiQAl/versions/SeOh2lEZ/%2Bunimixins-all-1.7.10-0.3.1.jar";
    private static final String UNIMIXINS_SHA512 = "305a13cdc0ea7bdc724c49ee5d7d32f307d602e8e4d7a3b86dcf66a2fc69e1134"
            + "eae113c1e9761fe7daec3d81f388886bf63e5d19438ef2e205819433f7e7112";
    private static final String ADDED_LIST = ".modpackci-added";

    private Lwjgl3ify() {}

    public static String version(Pack pack) {
        return pack.lwjgl3ifyVersion().orElse(DEFAULT_VERSION);
    }

    public static void requireForge(Pack pack) {
        if (!pack.forge.equals(REQUIRED_FORGE)) {
            throw new Fail("the Java 25 flavor needs Forge " + REQUIRED_FORGE + " (lwjgl3ify's patches are built for it), "
                    + "pack.toml says " + pack.forge);
        }
    }

    public static Path asset(Project project, String version, String part) {
        String name = "lwjgl3ify-" + version + part;
        return Http.download(RELEASES + version + "/" + name, project.cache("lwjgl3ify").resolve(version).resolve(name));
    }

    public static Path java9Args(Project project, String version) {
        return Http.download("https://raw.githubusercontent.com/GTNewHorizons/lwjgl3ify/" + version + "/java9args.txt",
                project.cache("lwjgl3ify").resolve(version).resolve("java9args.txt"));
    }

    /**
     * Fits a synced mods folder to the flavor: strips lwjgl3ify for Java 8, adds lwjgl3ify and
     * UniMixins for Java 25 when the pack doesn't list them. Files added on a previous run are
     * removed first, so switching versions never leaves two copies behind.
     */
    public static void adjustMods(Project project, Flavor flavor, Path modsDir) {
        Pack pack = project.pack();
        Path list = modsDir.resolve(ADDED_LIST);
        try {
            if (Files.exists(list)) {
                for (String name : Files.readAllLines(list)) {
                    if (!name.isBlank()) {
                        Files.deleteIfExists(modsDir.resolve(name));
                    }
                }
            }
            List<String> added = new ArrayList<>();
            if (flavor == Flavor.JAVA8) {
                try (Stream<Path> files = Files.list(modsDir)) {
                    for (Path f : files.filter(f -> f.getFileName().toString().matches("lwjgl3ify-\\d.*\\.jar")).toList()) {
                        Files.delete(f);
                    }
                }
                List<String> left = new ArrayList<>();
                for (Pack.Meta m : project.modernJavaMods()) {
                    if (m.path().startsWith("mods/") && Files.deleteIfExists(modsDir.resolve(m.filename()))) {
                        left.add(m.name());
                    }
                }
                if (!left.isEmpty()) {
                    Log.info("left out on Java 8: " + String.join(", ", left));
                }
            } else {
                if (pack.lwjgl3ifyVersion().isEmpty()) {
                    String name = "lwjgl3ify-" + DEFAULT_VERSION + ".jar";
                    FileUtil.copy(asset(project, DEFAULT_VERSION, ".jar"), modsDir.resolve(name));
                    added.add(name);
                }
                if (!pack.hasUniMixins()) {
                    String name = "+unimixins-all-1.7.10-0.3.1.jar";
                    Path jar = Http.download(UNIMIXINS_URL, project.cache("unimixins").resolve(name), "SHA-512",
                            UNIMIXINS_SHA512);
                    FileUtil.copy(jar, modsDir.resolve(name));
                    added.add(name);
                }
            }
            Files.write(list, added);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
