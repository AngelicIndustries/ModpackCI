package io.github.angelicindustries.modpackci;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/** Helpers the reusable workflow calls. */
public final class Ci {
    private Ci() {}

    /**
     * The flavors the configured release files run on, which are the ones worth smoke-testing.
     * An .mrpack or CurseForge zip runs on Java 25 when the pack lists lwjgl3ify, Java 8 otherwise.
     */
    public static Set<Flavor> flavors(Project project) {
        Flavor packFlavor = project.pack().lwjgl3ifyVersion().isPresent() ? Flavor.JAVA25 : Flavor.JAVA8;
        Set<Flavor> flavors = EnumSet.noneOf(Flavor.class);
        for (Destination dest : Destination.values()) {
            for (ReleaseType type : project.config.releaseTypes(dest)) {
                flavors.add(switch (type) {
                    case PRISM_JAVA8 -> Flavor.JAVA8;
                    case PRISM_JAVA17 -> Flavor.JAVA25;
                    default -> packFlavor;
                });
            }
        }
        if (flavors.isEmpty()) {
            flavors.add(packFlavor);
        }
        return flavors;
    }

    /** Prints the smoke test matrix, and hands it to GitHub Actions as the "matrix" output. */
    public static void plan(Project project) {
        JsonArray matrix = new JsonArray();
        for (Flavor flavor : flavors(project)) {
            for (String side : List.of("Server", "Client")) {
                JsonObject job = new JsonObject();
                job.addProperty("task", "smoke" + side + flavor.taskSuffix);
                job.addProperty("name", side.toLowerCase(java.util.Locale.ROOT) + " (" + flavor.label + ")");
                job.addProperty("client", side.equals("Client"));
                matrix.add(job);
            }
        }
        Log.info("smoke tests: " + matrix);
        String output = System.getenv("GITHUB_OUTPUT");
        if (output != null) {
            String existing = java.nio.file.Files.exists(Path.of(output)) ? FileUtil.readString(Path.of(output)) : "";
            FileUtil.write(Path.of(output), existing + "matrix=" + matrix + "\n");
        }
    }

    /** Fails if pack/index.toml doesn't match the pack, i.e. someone forgot packwiz refresh. */
    public static void checkIndex(Project project) {
        Path dir = project.pack().dir;
        Path tmp = FileUtil.tempDir("modpackci-index-");
        try {
            List<Path> files = new ArrayList<>(FileUtil.walkFiles(dir));
            for (Path f : files) {
                FileUtil.copy(f, tmp.resolve(dir.relativize(f).toString()));
            }
            List<String> command = List.of(Packwiz.binary(project).toString(), "refresh");
            Processes.runOrFail(command, tmp, "packwiz refresh");
            for (String name : List.of("index.toml", "pack.toml")) {
                if (!FileUtil.readString(dir.resolve(name)).equals(FileUtil.readString(tmp.resolve(name)))) {
                    throw new Fail(project.root.relativize(dir.resolve(name))
                            + " is out of date. Run: modpackw packwiz refresh");
                }
            }
            Log.info("the pack index is up to date");
        } finally {
            FileUtil.deleteTree(tmp);
        }
    }
}
