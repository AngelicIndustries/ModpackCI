package io.github.angelicindustries.modpackci;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Downloads what Prism would fetch on an online launch: the libraries, client jar and assets
 * listed in an instance's patches. An offline launch downloads nothing.
 */
public final class Prefetch {
    private Prefetch() {}

    private record Download(String url, String sha1) {}

    public static void instance(Path prism, Path inst) {
        Map<Path, Download> files = new LinkedHashMap<>();
        for (Path patch : FileUtil.walkFiles(inst.resolve("patches"))) {
            JsonObject data = JsonParser.parseString(FileUtil.readString(patch)).getAsJsonObject();
            List<JsonElement> libs = new ArrayList<>();
            if (data.has("libraries")) {
                data.getAsJsonArray("libraries").forEach(libs::add);
            }
            if (data.has("mainJar")) {
                libs.add(data.get("mainJar"));
            }
            for (JsonElement el : libs) {
                addLibrary(prism, el.getAsJsonObject(), files);
            }
            if (data.has("assetIndex")) {
                JsonObject index = data.getAsJsonObject("assetIndex");
                Path indexFile = prism.resolve("assets/indexes/" + index.get("id").getAsString() + ".json");
                Http.download(index.get("url").getAsString(), indexFile, "SHA-1", index.get("sha1").getAsString());
                JsonObject objects = JsonParser.parseString(FileUtil.readString(indexFile)).getAsJsonObject()
                        .getAsJsonObject("objects");
                for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
                    String hash = e.getValue().getAsJsonObject().get("hash").getAsString();
                    String sub = hash.substring(0, 2) + "/" + hash;
                    files.put(prism.resolve("assets/objects/" + sub),
                            new Download("https://resources.download.minecraft.net/" + sub, hash));
                }
            }
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            files.forEach((path, dl) -> jobs.add(pool.submit(() -> Http.download(dl.url, path,
                    dl.sha1 == null ? null : "SHA-1", dl.sha1))));
            for (Future<?> job : jobs) {
                try {
                    job.get();
                } catch (java.util.concurrent.ExecutionException e) {
                    throw e.getCause() instanceof Fail f ? f : new Fail("download failed: " + e.getCause(), e.getCause());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new Fail("interrupted", e);
                }
            }
        } finally {
            pool.shutdownNow();
        }
        Log.info(files.size() + " libraries and assets ready");
    }

    private static void addLibrary(Path prism, JsonObject lib, Map<Path, Download> files) {
        if (!lib.has("name") || "local".equals(str(lib, "MMC-hint")) || !allowedOnThisOs(lib)) {
            return;
        }
        String name = lib.get("name").getAsString();
        JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : new JsonObject();
        if (downloads.has("artifact")) {
            JsonObject a = downloads.getAsJsonObject("artifact");
            files.put(prism.resolve("libraries/" + mavenPath(name)), new Download(str(a, "url"), str(a, "sha1")));
        } else if (!lib.has("natives")) {
            String base = lib.has("url") ? str(lib, "url") : "https://libraries.minecraft.net/";
            files.put(prism.resolve("libraries/" + mavenPath(name)),
                    new Download((base.endsWith("/") ? base : base + "/") + mavenPath(name), null));
        }
        String natives = lib.has("natives") ? str(lib.getAsJsonObject("natives"), osName()) : null;
        if (natives != null) {
            natives = natives.replace("${arch}", Platform.arch().equals("x64") ? "64" : "32");
            JsonObject classifiers = downloads.has("classifiers") ? downloads.getAsJsonObject("classifiers") : null;
            if (classifiers != null && classifiers.has(natives)) {
                JsonObject a = classifiers.getAsJsonObject(natives);
                files.put(prism.resolve("libraries/" + mavenPath(name + ":" + natives)),
                        new Download(str(a, "url"), str(a, "sha1")));
            }
        }
    }

    /** The OS name Prism uses in library rules: linux, osx, windows, with -arm64 on ARM. */
    private static String osName() {
        String base = switch (Platform.current()) {
            case LINUX -> "linux";
            case MACOS -> "osx";
            case WINDOWS -> "windows";
        };
        return Platform.arch().equals("aarch64") ? base + "-arm64" : base;
    }

    private static boolean allowedOnThisOs(JsonObject lib) {
        if (!lib.has("rules")) {
            return true;
        }
        boolean allowed = false;
        for (JsonElement r : lib.getAsJsonArray("rules")) {
            JsonObject rule = r.getAsJsonObject();
            String os = rule.has("os") ? str(rule.getAsJsonObject("os"), "name") : null;
            if (os == null || os.equals(osName())) {
                allowed = "allow".equals(str(rule, "action"));
            }
        }
        return allowed;
    }

    static String mavenPath(String name) {
        String[] parts = name.split(":");
        String classifier = parts.length > 3 ? "-" + parts[3] : "";
        return parts[0].replace('.', '/') + "/" + parts[1] + "/" + parts[2] + "/" + parts[1] + "-" + parts[2]
                + classifier + ".jar";
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

}
