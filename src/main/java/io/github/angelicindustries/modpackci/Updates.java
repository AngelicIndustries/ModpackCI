package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeping a pack repo on the latest ModpackCI, like GTNH's buildscript updater: every run checks
 * for a newer release (at most once a day), and updateModpackCI moves the repo to it. The new
 * version does the actual migration, since it knows what changed.
 */
public final class Updates {
    private static final long DAY = 24 * 3600;

    private Updates() {}

    public static void check(Project project) {
        if (Main.VERSION.equals("dev") || System.getenv("DISABLE_MODPACKCI_UPDATE_CHECK") != null) {
            return;
        }
        Path state = project.cache("update-check");
        long now = System.currentTimeMillis() / 1000;
        String latest = null;
        if (Files.exists(state)) {
            String[] parts = FileUtil.readString(state).strip().split(" ");
            if (parts.length == 2 && now - Long.parseLong(parts[0]) < DAY) {
                latest = parts[1];
            }
        }
        if (latest == null) {
            try {
                latest = latestVersion(project);
            } catch (RuntimeException e) {
                return; // offline, rate limited or similar: try again next time
            }
            FileUtil.write(state, now + " " + latest);
        }
        if (compare(latest, Main.VERSION) > 0) {
            Log.notice("ModpackCI update from " + Main.VERSION + " to " + latest
                    + " available! Run modpackw updateModpackCI");
        }
    }

    public static String latestVersion(Project project) {
        Http.Response r = Http.get("https://api.github.com/repos/" + project.repository + "/releases/latest",
                Map.of("Accept", "application/vnd.github+json"));
        if (!r.ok()) {
            throw new Fail("couldn't look up the latest ModpackCI release (" + r.status() + ")");
        }
        String tag = r.json().getAsJsonObject().get("tag_name").getAsString();
        return tag.startsWith("v") ? tag.substring(1) : tag;
    }

    public static void updateModpackCI(Project project) {
        String latest = latestVersion(project);
        if (compare(latest, Main.VERSION) <= 0) {
            Log.info("ModpackCI " + Main.VERSION + " is already the latest version");
            return;
        }
        Path jar = project.cache("dists").resolve(latest).resolve("modpackci-" + latest + ".jar");
        Http.download("https://github.com/" + project.repository + "/releases/download/v" + latest + "/modpackci-"
                + latest + ".jar", jar);
        ProcessBuilder pb = new ProcessBuilder(Processes.currentJava(), "-jar", jar.toString(), "migrate", "--from",
                Main.VERSION).directory(project.root.toFile()).inheritIO();
        pb.environment().put("MODPACKCI_PROJECT_DIR", project.root.toString());
        try {
            int code = pb.start().waitFor();
            if (code != 0) {
                throw new Fail("ModpackCI " + latest + " couldn't migrate this repo");
            }
        } catch (java.io.IOException e) {
            throw new Fail("couldn't run ModpackCI " + latest + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted", e);
        }
    }

    /** Run by the new version: brings the repo's ModpackCI files up to date. */
    public static void migrate(Project project, String from) {
        List<String> changed = new ArrayList<>();
        Path root = project.root;
        String before = read(root.resolve("modpackw")) + read(root.resolve("modpackw.bat"));
        Templates.writeWrapper(root);
        if (!before.equals(read(root.resolve("modpackw")) + read(root.resolve("modpackw.bat")))) {
            changed.add("modpackw, modpackw.bat");
        }
        Path props = root.resolve(Project.WRAPPER_PROPERTIES);
        String newProps = Templates.wrapperProperties(Main.VERSION, project.repository);
        if (!newProps.equals(read(props))) {
            FileUtil.write(props, newProps);
            changed.add(Project.WRAPPER_PROPERTIES);
        }
        Path ini = root.resolve(Config.SHARED_FILE);
        if (!Files.exists(ini)) {
            FileUtil.write(ini, Templates.sharedIni());
            changed.add(Config.SHARED_FILE);
        } else {
            Map<String, String> existing = Config.parse(ini);
            StringBuilder added = new StringBuilder();
            for (Config.Key key : Config.Key.values()) {
                if (key.shared && !existing.containsKey(key.name)) {
                    added.append('\n').append(Templates.keyBlock(key));
                }
            }
            if (!added.isEmpty()) {
                FileUtil.write(ini, read(ini).stripTrailing() + "\n" + added);
                changed.add(Config.SHARED_FILE + " (new keys added at the end)");
            }
        }
        // Generated from the keys, so it's rewritten rather than merged.
        Path example = root.resolve(Config.LOCAL_FILE + ".example");
        if (!Templates.localIniExample().equals(read(example))) {
            FileUtil.write(example, Templates.localIniExample());
            changed.add(Config.LOCAL_FILE + ".example");
        }
        String major = Main.VERSION.split("\\.")[0];
        Pattern workflowRef = Pattern.compile(Pattern.quote(project.repository + "/.github/workflows/modpack.yml@v") + "\\d+\\b");
        Path workflows = root.resolve(".github/workflows");
        if (Files.isDirectory(workflows) && !Main.VERSION.equals("dev")) {
            for (Path file : FileUtil.walkFiles(workflows)) {
                String text = read(file);
                Matcher m = workflowRef.matcher(text);
                String updated = m.replaceAll(Matcher.quoteReplacement(project.repository
                        + "/.github/workflows/modpack.yml@v" + major));
                if (!updated.equals(text)) {
                    FileUtil.write(file, updated);
                    changed.add(root.relativize(file).toString());
                }
            }
        }
        Log.info("moved from ModpackCI " + from + " to " + Main.VERSION);
        if (changed.isEmpty()) {
            Log.info("no files changed");
        } else {
            Log.info("changed, review and commit:");
            changed.forEach(c -> Log.info("  " + c));
        }
    }

    private static String read(Path file) {
        return Files.exists(file) ? FileUtil.readString(file) : "";
    }

    /** Compares dotted versions numerically, falling back to text for non-numeric parts. */
    static int compare(String a, String b) {
        String[] x = a.split("[.-]");
        String[] y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            String p = i < x.length ? x[i] : "0";
            String q = i < y.length ? y[i] : "0";
            int c = p.matches("\\d+") && q.matches("\\d+")
                    ? Long.compare(Long.parseLong(p), Long.parseLong(q))
                    : p.compareTo(q);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }
}
