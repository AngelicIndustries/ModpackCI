package io.github.angelicindustries.modpackci;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/** Builds the release files into build/. */
public final class Builds {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String INDEX = "modrinth.index.json";
    private static final List<String> OVERRIDES = List.of("overrides/", "client-overrides/", "server-overrides/");

    private Builds() {}

    public static Path output(Project project, ReleaseType type) {
        Pack pack = project.pack();
        return project.buildDir.resolve(pack.fileName() + "-" + pack.version + type.suffix);
    }

    /** Builds each type once. Returns where each one ended up. */
    public static Map<ReleaseType, Path> build(Project project, Collection<ReleaseType> types) {
        Map<ReleaseType, Path> out = new LinkedHashMap<>();
        for (ReleaseType type : ReleaseType.values()) {
            if (types.contains(type)) {
                out.put(type, build(project, type));
            }
        }
        return out;
    }

    public static Path build(Project project, ReleaseType type) {
        Path out = output(project, type);
        try {
            Files.createDirectories(out.getParent());
            Files.deleteIfExists(out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Log.info("building " + out.getFileName());
        switch (type) {
            case MRPACK_SLIM -> mrpackSlim(project, out);
            case MRPACK_FAT -> mrpackFat(project, out);
            case PRISM_JAVA8 -> prismZip(project, Flavor.JAVA8, out);
            case PRISM_JAVA17 -> prismZip(project, Flavor.JAVA25, out);
            case CURSEFORGE_ZIP -> Packwiz.runOrFail(project, "curseforge", "export", "-o", out.toString());
        }
        Log.info("built " + project.root.relativize(out));
        return out;
    }

    /**
     * Every mod is a download link. packwiz exports CurseForge mods into the archive, so it runs
     * against an empty cache (a CurseForge file can only end up in the export if CurseForge's API
     * handed out its URL, which it does only when the author allows third-party downloads) and
     * those files are then swapped for links to the same file on CurseForge's CDN.
     */
    private static void mrpackSlim(Project project, Path out) {
        Path tmp = FileUtil.tempDir("modpackci-");
        try {
            Path raw = tmp.resolve("packwiz.mrpack");
            Packwiz.runOrFail(project, "--cache", tmp.resolve("cache").toString(), "modrinth", "export",
                    "--restrictDomains=false", "-o", raw.toString());
            Map<String, Pack.Meta> curseforge = new HashMap<>();
            for (Pack.Meta m : project.pack().metas) {
                if (m.fromCurseforge()) {
                    curseforge.put(m.path(), m);
                }
            }
            List<String> bundled = new ArrayList<>();
            try (ZipFile src = new ZipFile(raw.toFile());
                    ZipOutputStream dst = new ZipOutputStream(Files.newOutputStream(out))) {
                JsonObject index = JsonParser.parseString(read(src, INDEX)).getAsJsonObject();
                JsonArray files = index.getAsJsonArray("files");
                for (ZipEntry e : list(src)) {
                    if (e.getName().equals(INDEX) || e.isDirectory()) {
                        continue;
                    }
                    byte[] data = src.getInputStream(e).readAllBytes();
                    String rel = overridePath(e.getName());
                    Pack.Meta meta = rel == null ? null : curseforge.remove(rel);
                    if (meta != null) {
                        String url = curseforgeUrl(meta);
                        long length = Http.contentLength(url);
                        if (length != data.length) {
                            throw new Fail(url + " serves " + length + " bytes, expected " + data.length);
                        }
                        files.add(fileEntry(rel, data, url, env(meta.side())));
                        continue;
                    }
                    if (rel != null && (rel.endsWith(".jar") || rel.endsWith(".zip"))) {
                        bundled.add(rel);
                    }
                    FileUtil.addToZip(dst, e.getName(), data);
                }
                if (!curseforge.isEmpty()) {
                    throw new Fail("packwiz didn't export these CurseForge files: " + curseforge.keySet());
                }
                sortFiles(files);
                FileUtil.addToZip(dst, INDEX, GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
            }
            if (!bundled.isEmpty()) {
                Log.info("shipped inside the slim pack: " + String.join(", ", bundled));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            FileUtil.deleteTree(tmp);
        }
    }

    /** Everything ships inside the archive; installing it downloads nothing. */
    private static void mrpackFat(Project project, Path out) {
        Path tmp = FileUtil.tempDir("modpackci-");
        try {
            Path raw = tmp.resolve("packwiz.mrpack");
            Packwiz.runOrFail(project, "modrinth", "export", "--restrictDomains=false", "-o", raw.toString());
            try (ZipFile src = new ZipFile(raw.toFile());
                    ZipOutputStream dst = new ZipOutputStream(Files.newOutputStream(out))) {
                JsonObject index = JsonParser.parseString(read(src, INDEX)).getAsJsonObject();
                for (ZipEntry e : list(src)) {
                    if (!e.getName().equals(INDEX) && !e.isDirectory()) {
                        FileUtil.addToZip(dst, e.getName(), src.getInputStream(e));
                    }
                }
                for (JsonElement el : index.getAsJsonArray("files")) {
                    JsonObject f = el.getAsJsonObject();
                    JsonObject env = f.has("env") ? f.getAsJsonObject("env") : new JsonObject();
                    String prefix = "unsupported".equals(str(env, "server")) ? "client-overrides/"
                            : "unsupported".equals(str(env, "client")) ? "server-overrides/" : "overrides/";
                    if ("optional".equals(str(env, "client")) || "optional".equals(str(env, "server"))) {
                        Log.warn(str(f, "path") + " is optional, but fat packs always install it");
                    }
                    String sha512 = f.getAsJsonObject("hashes").get("sha512").getAsString();
                    byte[] data = Http.fetch(f.getAsJsonArray("downloads").get(0).getAsString());
                    if (!FileUtil.hash("SHA-512", data).equals(sha512)) {
                        throw new Fail(str(f, "path") + " doesn't match its sha512");
                    }
                    FileUtil.addToZip(dst, prefix + str(f, "path"), data);
                }
                index.add("files", new JsonArray());
                FileUtil.addToZip(dst, INDEX, GSON.toJson(index).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            FileUtil.deleteTree(tmp);
        }
    }

    /** A Prism instance zip. No Java is pinned, so the launcher picks a compatible one. */
    private static void prismZip(Project project, Flavor flavor, Path out) {
        Path inst = FileUtil.tempDir("modpackci-instance-");
        try {
            String name = project.pack().name + (flavor == Flavor.JAVA8 ? " (Java 8)" : " (Java 17+)");
            ClientInstance.setup(project, inst, flavor, name, null, false);
            try (ZipOutputStream dst = new ZipOutputStream(Files.newOutputStream(out))) {
                for (Path file : FileUtil.walkFiles(inst)) {
                    String rel = inst.relativize(file).toString().replace('\\', '/');
                    if (ClientInstance.distributable(rel)) {
                        FileUtil.addToZip(dst, rel, FileUtil.read(file));
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            FileUtil.deleteTree(inst);
        }
    }

    static String curseforgeUrl(Pack.Meta meta) {
        long id = meta.curseforgeFileId();
        String name = URLEncoder.encode(meta.filename(), StandardCharsets.UTF_8).replace("+", "%20");
        return "https://edge.forgecdn.net/files/" + (id / 1000) + "/" + (id % 1000) + "/" + name;
    }

    private static JsonObject env(String side) {
        JsonObject env = new JsonObject();
        env.addProperty("client", side.equals("server") ? "unsupported" : "required");
        env.addProperty("server", side.equals("client") ? "unsupported" : "required");
        return env;
    }

    private static JsonObject fileEntry(String path, byte[] data, String url, JsonObject env) {
        JsonObject hashes = new JsonObject();
        hashes.addProperty("sha1", FileUtil.hash("SHA-1", data));
        hashes.addProperty("sha512", FileUtil.hash("SHA-512", data));
        JsonArray downloads = new JsonArray();
        downloads.add(url);
        JsonObject f = new JsonObject();
        f.addProperty("path", path);
        f.add("hashes", hashes);
        f.add("env", env);
        f.add("downloads", downloads);
        f.addProperty("fileSize", data.length);
        return f;
    }

    private static void sortFiles(JsonArray files) {
        List<JsonElement> sorted = new ArrayList<>();
        files.forEach(sorted::add);
        sorted.sort(Comparator.comparing(e -> e.getAsJsonObject().get("path").getAsString()));
        while (!files.isEmpty()) {
            files.remove(0);
        }
        sorted.forEach(files::add);
    }

    private static String overridePath(String name) {
        for (String prefix : OVERRIDES) {
            if (name.startsWith(prefix)) {
                return name.substring(prefix.length());
            }
        }
        return null;
    }

    private static List<ZipEntry> list(ZipFile zip) {
        return zip.stream().map(e -> (ZipEntry) e).toList();
    }

    private static String read(ZipFile zip, String name) throws IOException {
        ZipEntry e = zip.getEntry(name);
        if (e == null) {
            throw new Fail("packwiz's export has no " + name);
        }
        return new String(zip.getInputStream(e).readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }
}
