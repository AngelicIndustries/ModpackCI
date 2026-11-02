package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * syncConfig: copies the pack's config files (the ones its index lists under config/) over the run folders' own, for
 * when a pack config changed and packwiz-installer won't put it back (it only installs what changed in the pack).
 */
final class ConfigSync {
    /** Run folder names, by the argument that picks one (as the run tasks are named). */
    private static final Map<String, String> RUNS = new LinkedHashMap<>();

    static {
        RUNS.put("client", "client-java8");
        RUNS.put("client25", "client-java25");
        RUNS.put("server", "server-java8");
        RUNS.put("server25", "server-java25");
    }

    private ConfigSync() {}

    static void run(Project project, List<String> args) {
        boolean dryRun = args.contains("--dry-run");
        List<String> picked = new ArrayList<>(args);
        picked.remove("--dry-run");
        for (String name : picked) {
            if (!RUNS.containsKey(name)) {
                throw new Fail("syncConfig takes " + String.join(", ", RUNS.keySet()) + " or nothing (all), not "
                        + name);
            }
        }
        Path packDir = Pack.findDir(project.root);
        List<String> configs = Pack.indexedFiles(packDir).stream().filter(f -> f.startsWith("config/")).toList();
        int synced = 0;
        for (Map.Entry<String, String> run : RUNS.entrySet()) {
            if (!picked.isEmpty() && !picked.contains(run.getKey())) {
                continue;
            }
            Path dir = project.root.resolve("run").resolve(run.getValue());
            if (!Files.isDirectory(dir)) {
                if (!picked.isEmpty()) {
                    throw new Fail("no run/" + run.getValue() + " yet");
                }
                continue;
            }
            synced++;
            List<String> changed = new ArrayList<>();
            for (String file : configs) {
                Path from = packDir.resolve(file);
                Path to = dir.resolve(file);
                if (Files.exists(to) && Arrays.equals(FileUtil.read(from), FileUtil.read(to))) {
                    continue;
                }
                changed.add(file.substring("config/".length()));
                if (!dryRun) {
                    FileUtil.copy(from, to);
                }
            }
            String verb = dryRun ? " would change " : " changed ";
            Log.info("run/" + run.getValue() + ":" + (changed.isEmpty() ? " up to date"
                    : verb + changed.size() + (changed.size() == 1 ? " file" : " files")));
            changed.forEach(f -> Log.info("  " + f));
        }
        if (synced == 0) {
            throw new Fail("no run folders yet: runClient or runServer makes them");
        }
    }
}
