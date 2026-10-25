package io.github.angelicindustries.modpackci;

import java.nio.file.Path;
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

    /** Reread pack.toml and the index, after something changed them. */
    public void reloadPack() {
        pack = null;
    }

    public Path cache(String sub) {
        return home.resolve(sub);
    }
}
