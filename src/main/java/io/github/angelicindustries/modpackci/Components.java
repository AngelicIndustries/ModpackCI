package io.github.angelicindustries.modpackci;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The launcher components a client is made of, in the format Prism and MultiMC use: Minecraft,
 * LWJGL and Forge from Prism's metadata for Java 8, or lwjgl3ify's patches for Java 25. Merging
 * them the way those launchers do gives the classpath, main class and arguments, so the game can
 * be started without a launcher.
 */
public final class Components {
    private static final String META = "https://meta.prismlauncher.org/v1/";

    private Components() {}

    /** One component file, plus where its "local" libraries live. */
    public record Component(String uid, JsonObject json, Path localLibraries) {}

    /** A library to put on the classpath, or to unpack natives from. */
    public record Library(String key, Path path, String url, String sha1, boolean natives) {}

    /** Everything a launch needs, merged from the components. */
    public record Launch(List<Library> libraries, String mainClass, List<String> jvmArgs, String minecraftArguments,
            List<String> tweakers, List<String> traits, JsonObject assetIndex) {}

    public static List<Component> load(Project project, Flavor flavor) {
        List<Component> components = new ArrayList<>();
        if (flavor == Flavor.JAVA25) {
            Lwjgl3ify.requireForge(project.pack());
            String version = Lwjgl3ify.version(project.pack());
            Path dir = project.cache("lwjgl3ify").resolve(version).resolve("multimc");
            if (!Files.exists(dir.resolve("mmc-pack.json"))) {
                FileUtil.unzip(Lwjgl3ify.asset(project, version, "-multimc.zip"), dir);
            }
            JsonObject pack = parse(dir.resolve("mmc-pack.json"));
            for (JsonElement c : pack.getAsJsonArray("components")) {
                String uid = c.getAsJsonObject().get("uid").getAsString();
                components.add(new Component(uid, parse(dir.resolve("patches/" + uid + ".json")), dir.resolve("libraries")));
            }
        } else {
            JsonObject minecraft = meta(project, "net.minecraft", "1.7.10");
            String lwjgl = minecraft.getAsJsonArray("requires").get(0).getAsJsonObject().get("suggests").getAsString();
            components.add(new Component("net.minecraft", minecraft, null));
            components.add(new Component("org.lwjgl", meta(project, "org.lwjgl", lwjgl), null));
            components.add(new Component("net.minecraftforge", meta(project, "net.minecraftforge", project.pack().forge), null));
        }
        components.sort(Comparator.comparingInt(c -> c.json.has("order") ? c.json.get("order").getAsInt() : 0));
        return components;
    }

    private static JsonObject meta(Project project, String uid, String version) {
        Path file = project.cache("minecraft/meta").resolve(uid).resolve(version + ".json");
        return parse(Http.download(META + uid + "/" + version + ".json", file));
    }

    private static JsonObject parse(Path file) {
        return JsonParser.parseString(FileUtil.readString(file)).getAsJsonObject();
    }

    /**
     * Merges components in order. Later ones replace the main class and Minecraft arguments, add
     * JVM arguments and tweakers, and replace libraries with the same group and artifact, the way
     * Prism applies them.
     */
    public static Launch merge(List<Component> components, Path libraryRoot) {
        Map<String, Library> libraries = new LinkedHashMap<>();
        Library mainJar = null;
        String mainClass = null;
        String minecraftArguments = null;
        List<String> jvmArgs = new ArrayList<>();
        List<String> tweakers = new ArrayList<>();
        List<String> traits = new ArrayList<>();
        JsonObject assetIndex = null;
        for (Component c : components) {
            JsonObject j = c.json;
            if (j.has("libraries")) {
                for (JsonElement el : j.getAsJsonArray("libraries")) {
                    for (Library lib : resolve(el.getAsJsonObject(), c, libraryRoot)) {
                        libraries.put(lib.key, lib);
                    }
                }
            }
            if (j.has("mainJar")) {
                List<Library> resolved = resolve(j.getAsJsonObject("mainJar"), c, libraryRoot);
                mainJar = resolved.isEmpty() ? mainJar : resolved.get(0);
            }
            mainClass = j.has("mainClass") ? j.get("mainClass").getAsString() : mainClass;
            minecraftArguments = j.has("minecraftArguments") ? j.get("minecraftArguments").getAsString() : minecraftArguments;
            assetIndex = j.has("assetIndex") ? j.getAsJsonObject("assetIndex") : assetIndex;
            addAll(j, "+jvmArgs", jvmArgs);
            addAll(j, "+tweakers", tweakers);
            addAll(j, "+traits", traits);
        }
        if (mainClass == null || minecraftArguments == null || mainJar == null || assetIndex == null) {
            throw new Fail("the launcher components don't add up to a launchable game");
        }
        List<Library> all = new ArrayList<>(libraries.values());
        all.add(mainJar);
        return new Launch(all, mainClass, jvmArgs, minecraftArguments, tweakers, traits, assetIndex);
    }

    private static void addAll(JsonObject j, String key, List<String> into) {
        if (j.has(key)) {
            for (JsonElement e : j.getAsJsonArray(key)) {
                into.add(e.getAsString());
            }
        }
    }

    /** A library entry as files for this OS: the jar itself and/or its natives jar. */
    private static List<Library> resolve(JsonObject lib, Component c, Path root) {
        List<Library> out = new ArrayList<>();
        if (!lib.has("name") || !allowedOnThisOs(lib)) {
            return out;
        }
        String name = lib.get("name").getAsString();
        String[] parts = name.split(":");
        String key = parts[0] + ":" + parts[1] + (parts.length > 3 ? ":" + parts[3] : "");
        JsonObject downloads = lib.has("downloads") ? lib.getAsJsonObject("downloads") : new JsonObject();
        if ("local".equals(str(lib, "MMC-hint"))) {
            Path local = c.localLibraries.resolve(parts[1] + "-" + parts[2] + (parts.length > 3 ? "-" + parts[3] : "") + ".jar");
            out.add(new Library(key, local, null, null, false));
        } else if (downloads.has("artifact")) {
            JsonObject a = downloads.getAsJsonObject("artifact");
            out.add(new Library(key, root.resolve(mavenPath(name)), str(a, "url"), str(a, "sha1"), false));
        } else if (!lib.has("natives")) {
            String base = lib.has("url") ? str(lib, "url") : "https://libraries.minecraft.net/";
            out.add(new Library(key, root.resolve(mavenPath(name)),
                    (base.endsWith("/") ? base : base + "/") + mavenPath(name), null, false));
        }
        String classifier = lib.has("natives") ? str(lib.getAsJsonObject("natives"), osName()) : null;
        if (classifier != null) {
            classifier = classifier.replace("${arch}", Platform.arch().equals("x64") ? "64" : "32");
            JsonObject classifiers = downloads.has("classifiers") ? downloads.getAsJsonObject("classifiers") : null;
            if (classifiers != null && classifiers.has(classifier)) {
                JsonObject a = classifiers.getAsJsonObject(classifier);
                out.add(new Library(key + ":" + classifier, root.resolve(mavenPath(name + ":" + classifier)),
                        str(a, "url"), str(a, "sha1"), true));
            }
        }
        return out;
    }

    /** The OS name in launcher library rules: linux, osx, windows, with -arm64 on ARM. */
    static String osName() {
        String base = switch (Platform.current()) {
            case LINUX -> "linux";
            case MACOS -> "osx";
            case WINDOWS -> "windows";
        };
        return Platform.arch().equals("aarch64") ? base + "-arm64" : base;
    }

    static boolean allowedOnThisOs(JsonObject lib) {
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

    static String str(JsonObject o, String key) {
        JsonElement e = o.get(key);
        return e == null || e.isJsonNull() ? null : e.getAsString();
    }

}
