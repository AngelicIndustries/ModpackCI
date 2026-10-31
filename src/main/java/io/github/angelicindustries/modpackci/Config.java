package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Settings from modpack.ini (committed), modpack-local.ini (gitignored) and the environment.
 * The environment wins over modpack-local.ini, which wins over modpack.ini. Empty values count
 * as unset. Both files are gradle.properties style: {@code key = value}, {@code #} comments.
 */
public final class Config {
    public static final String SHARED_FILE = "modpack.ini";
    public static final String LOCAL_FILE = "modpack-local.ini";

    public enum Key {
        CURSEFORGE_PROJECT_ID("curseForgeProjectId", "CURSEFORGE_PROJECT_ID", true,
                "CurseForge project ID, the number in the About Project box."),
        MODRINTH_PROJECT_ID("modrinthProjectId", "MODRINTH_PROJECT_ID", true,
                "Modrinth project ID or slug."),
        SIX_SEVEN_PROJECT_ID("sixSevenProjectId", "SIX_SEVEN_PROJECT_ID", true,
                "67minecraft.com project ID or slug."),
        GITHUB_RELEASE_TYPES("githubReleaseTypes", "GH_RELEASE_TYPES", true,
                "Files attached to GitHub releases."),
        SIX_SEVEN_RELEASE_TYPES("sixSevenReleaseTypes", "SIX_SEVEN_RELEASE_TYPES", true,
                "Files uploaded to 67minecraft.com. The first one is the version's primary file."),
        MODRINTH_RELEASE_TYPES("modrinthReleaseTypes", "MODRINTH_RELEASE_TYPES", true,
                "Files uploaded to Modrinth. The first one is the version's primary file."),
        CURSEFORGE_RELEASE_TYPES("curseForgeReleaseTypes", "CURSEFORGE_RELEASE_TYPES", true,
                "Files uploaded to CurseForge."),
        MODERN_JAVA_MODS("modernJavaMods", "MODPACKCI_MODERN_JAVA_MODS", true,
                "Pack mods that need lwjgl3ify, so Java 17 or newer: their .pw.toml names without the extension,"
                        + " comma-separated. Java 8 runs, tests and prism-java8 zips leave them out, and an .mrpack"
                        + " offers them as optional unless the pack lists lwjgl3ify."),
        JAVA8_JVM_ARGS("java8JvmArgs", "MODPACKCI_JAVA8_JVM_ARGS", true,
                "Extra JVM arguments for Java 8 runs, tests and prism-java8 instances, separated by spaces."),
        JAVA25_JVM_ARGS("java25JvmArgs", "MODPACKCI_JAVA25_JVM_ARGS", true,
                "Extra JVM arguments for Java 25 runs, tests and prism-java17 instances, separated by spaces, for"
                        + " example -XX:+UseZGC. An .mrpack can't carry JVM arguments."),
        JAVA8("java8", "MODPACKCI_JAVA8", false, "Java 8 binary to use instead of the downloaded one."),
        JAVA25("java25", "MODPACKCI_JAVA25", false, "Java 25 binary to use instead of the downloaded one."),
        PACKWIZ("packwiz", "MODPACKCI_PACKWIZ", false, "packwiz binary to use instead of the downloaded one."),
        LOCAL_MODS("localMods", "MODPACKCI_LOCAL_MODS", false,
                "Mods on this machine that runClient and runServer add: Gradle projects (built with gradlew assemble)"
                        + " or jars, comma-separated, relative to this folder. One with the same mod ID as a pack mod"
                        + " replaces it. Add @client or @server for one side only. --no-build skips the builds."),
        CURSEFORGE_TOKEN("curseForgeToken", "CURSEFORGE_TOKEN", false, "CurseForge upload token."),
        MODRINTH_TOKEN("modrinthToken", "MODRINTH_TOKEN", false, "Modrinth token."),
        SIX_SEVEN_TOKEN("sixSevenToken", "SIX_SEVEN_TOKEN", false, "67minecraft.com token.");

        public final String name;
        public final String env;
        /** Whether the key belongs in modpack.ini. The others only make sense locally. */
        public final boolean shared;
        public final String doc;

        Key(String name, String env, boolean shared, String doc) {
            this.name = name;
            this.env = env;
            this.shared = shared;
            this.doc = doc;
        }

        public boolean isToken() {
            return this == CURSEFORGE_TOKEN || this == MODRINTH_TOKEN || this == SIX_SEVEN_TOKEN;
        }

        static Key byName(String name) {
            for (Key k : values()) {
                if (k.name.equals(name)) {
                    return k;
                }
            }
            return null;
        }
    }

    private final Map<String, String> shared;
    private final Map<String, String> local;
    private final Function<String, String> env;

    Config(Map<String, String> shared, Map<String, String> local, Function<String, String> env) {
        this.shared = shared;
        this.local = local;
        this.env = env;
    }

    public static Config load(Path root) {
        Map<String, String> shared = parse(root.resolve(SHARED_FILE));
        Map<String, String> local = parse(root.resolve(LOCAL_FILE));
        check(SHARED_FILE, shared, true);
        check(LOCAL_FILE, local, false);
        return new Config(shared, local, System::getenv);
    }

    public static Map<String, String> parse(Path file) {
        Map<String, String> out = new LinkedHashMap<>();
        if (!Files.exists(file)) {
            return out;
        }
        int lineNo = 0;
        for (String raw : FileUtil.readString(file).split("\\R")) {
            lineNo++;
            String line = raw.strip();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith(";")) {
                continue;
            }
            int eq = line.indexOf('=');
            if (eq < 0) {
                throw new Fail(file.getFileName() + " line " + lineNo + ": expected key = value, got: " + line);
            }
            String value = line.substring(eq + 1).strip();
            if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                value = value.substring(1, value.length() - 1);
            }
            out.put(line.substring(0, eq).strip(), value);
        }
        return out;
    }

    private static void check(String file, Map<String, String> values, boolean isShared) {
        for (String name : values.keySet()) {
            Key key = Key.byName(name);
            if (key == null) {
                Log.warn(file + ": unknown key " + name);
            } else if (isShared && !key.shared && !values.get(name).isEmpty()) {
                Log.warn(file + ": " + name + " belongs in " + LOCAL_FILE
                        + (key.isToken() ? " or the environment, not in a committed file" : ""));
            }
        }
    }

    /** The value, or null when it's unset everywhere. */
    public String get(Key key) {
        for (String value : new String[] {env.apply(key.env), local.get(key.name), shared.get(key.name)}) {
            if (value != null && !value.isBlank()) {
                return value.strip();
            }
        }
        return null;
    }

    /** The release types configured for a destination, checked against what it accepts. */
    public List<ReleaseType> releaseTypes(Destination dest) {
        String value = get(dest.typesKey);
        LinkedHashSet<ReleaseType> types = new LinkedHashSet<>();
        if (value == null) {
            return List.of();
        }
        for (String id : value.split("[,\\s]+")) {
            if (id.isEmpty()) {
                continue;
            }
            ReleaseType type = ReleaseType.parse(id);
            if (type == null) {
                throw new Fail(dest.typesKey.name + ": unknown type '" + id + "'. Known types: " + ReleaseType.known());
            }
            if (!dest.accepts.contains(type)) {
                throw new Fail(dest.typesKey.name + ": " + dest.displayName + " doesn't accept " + id);
            }
            types.add(type);
        }
        return new ArrayList<>(types);
    }
}
