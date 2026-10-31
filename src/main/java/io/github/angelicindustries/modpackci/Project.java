package io.github.angelicindustries.modpackci;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The pack repo ModpackCI runs in, plus the shared cache in ~/.modpackci. */
public final class Project {
    public static final String WRAPPER_PROPERTIES = "modpackw.properties";
    public static final String DEFAULT_REPOSITORY = "AngelicIndustries/ModpackCI";

    public final Path root;
    public final Path home;
    public final Path buildDir;
    public final Config config;
    /** The GitHub repo ModpackCI releases come from, so forks can point the wrapper elsewhere. */
    public final String repository;
    private Pack pack;

    public Project(Path root) {
        this.root = root.toAbsolutePath().normalize();
        String homeEnv = System.getenv("MODPACKCI_HOME");
        this.home = homeEnv != null && !homeEnv.isBlank()
                ? Path.of(homeEnv)
                : Platform.home().resolve(".modpackci");
        this.buildDir = this.root.resolve("build");
        this.config = Config.load(this.root);
        Map<String, String> wrapper = Config.parse(this.root.resolve(WRAPPER_PROPERTIES));
        this.repository = wrapper.getOrDefault("repository", DEFAULT_REPOSITORY);
    }

    public static Project current() {
        String dir = System.getenv("MODPACKCI_PROJECT_DIR");
        return new Project(Path.of(dir != null && !dir.isBlank() ? dir : "."));
    }

    public Pack pack() {
        if (pack == null) {
            pack = Pack.load(root);
        }
        return pack;
    }

    /** The pack mods {@code modernJavaMods} names, which need lwjgl3ify and so Java 17 or newer. */
    public List<Pack.Meta> modernJavaMods() {
        String value = config.get(Config.Key.MODERN_JAVA_MODS);
        List<Pack.Meta> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String raw : value.split(",")) {
            String name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            out.add(pack().metas.stream()
                    .filter(m -> m.metaFile().getFileName().toString().equals(name + ".pw.toml"))
                    .findFirst()
                    .orElseThrow(() -> new Fail(Config.Key.MODERN_JAVA_MODS.name + ": the pack has no " + name + ".pw.toml")));
        }
        return out;
    }

    /** The extra JVM arguments modpack.ini sets for a flavor. */
    public List<String> jvmArgs(Flavor flavor) {
        String value = config.get(flavor == Flavor.JAVA8 ? Config.Key.JAVA8_JVM_ARGS : Config.Key.JAVA25_JVM_ARGS);
        List<String> out = new ArrayList<>();
        if (value != null) {
            for (String arg : value.trim().split("\\s+")) {
                if (!arg.isEmpty()) {
                    out.add(arg);
                }
            }
        }
        return out;
    }

    /** Reread pack.toml and the index, after something changed them. */
    public void reloadPack() {
        pack = null;
    }

    public Path cache(String sub) {
        return home.resolve(sub);
    }
}
