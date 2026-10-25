package io.github.angelicindustries.modpackci;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/** The files a pack repo gets from ModpackCI: wrapper, settings, CI workflow and a starter pack. */
public final class Templates {
    private Templates() {}

    public static String resource(String name) {
        try (InputStream in = Templates.class.getResourceAsStream("/templates/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing template " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String keyBlock(Config.Key key) {
        return "# " + key.doc + " Environment variable: " + key.env + "\n" + key.name + " =\n";
    }

    public static String sharedIni() {
        StringBuilder out = new StringBuilder("""
                # ModpackCI settings. Environment variables override them, and so does
                # modpack-local.ini, which is gitignored and also holds machine-specific settings.
                #
                # Release types: %s
                # A destination with an empty list gets nothing.

                """.formatted(ReleaseType.known()));
        for (Config.Key key : Config.Key.values()) {
            if (key.shared) {
                out.append(keyBlock(key)).append('\n');
            }
        }
        return out.toString().stripTrailing() + "\n";
    }

    public static String localIniExample() {
        StringBuilder out = new StringBuilder("""
                # Copy to modpack-local.ini (gitignored) to override settings on this machine.
                # Any modpack.ini key works here too.

                """);
        for (Config.Key key : Config.Key.values()) {
            if (!key.shared) {
                out.append(keyBlock(key)).append('\n');
            }
        }
        return out.toString().stripTrailing() + "\n";
    }

    public static String wrapperProperties(String version, String repository) {
        String props = "version=" + version + "\n";
        if (!repository.equals(Project.DEFAULT_REPOSITORY)) {
            props += "repository=" + repository + "\n";
        }
        return props;
    }

    /** Writes the wrapper scripts, made executable where that matters. */
    public static void writeWrapper(Path root) {
        FileUtil.write(root.resolve("modpackw"), resource("modpackw"));
        root.resolve("modpackw").toFile().setExecutable(true, false);
        FileUtil.write(root.resolve("modpackw.bat"), resource("modpackw.bat").replace("\n", "\r\n"));
    }

    /** The scaffold that ModpackCI releases ship as starter.zip. */
    public static void writeStarter(Path dir) {
        writeWrapper(dir);
        FileUtil.write(dir.resolve(Project.WRAPPER_PROPERTIES), wrapperProperties(Main.VERSION, Project.DEFAULT_REPOSITORY));
        FileUtil.write(dir.resolve(Config.SHARED_FILE), sharedIni());
        FileUtil.write(dir.resolve(Config.LOCAL_FILE + ".example"), localIniExample());
        FileUtil.write(dir.resolve(".github/workflows/ci.yml"), resource("ci.yml"));
        FileUtil.write(dir.resolve(".gitignore"), resource("gitignore"));
        FileUtil.write(dir.resolve("README.md"), resource("README.md"));
        // Exactly what packwiz refresh writes, so checkIndex passes on a fresh starter.
        String index = "hash-format = \"sha256\"\nfiles = []\n";
        FileUtil.write(dir.resolve("pack/index.toml"), index);
        FileUtil.write(dir.resolve("pack/pack.toml"), """
                name = "My Modpack"
                version = "0.1.0"
                pack-format = "packwiz:1.1.0"

                [index]
                file = "index.toml"
                hash-format = "sha256"
                hash = "%s"

                [versions]
                forge = "%s"
                minecraft = "1.7.10"
                """.formatted(FileUtil.hash("SHA-256", index.getBytes(StandardCharsets.UTF_8)), Lwjgl3ify.REQUIRED_FORGE));
    }
}
