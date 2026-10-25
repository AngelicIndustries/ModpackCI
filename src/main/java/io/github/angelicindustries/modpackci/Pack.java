package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

/** A packwiz pack, read-only. */
public final class Pack {
    private static final Pattern LWJGL3IFY = Pattern.compile("lwjgl3ify-(\\d[^/]*)\\.jar");

    public final Path dir;
    public final String name;
    public final String version;
    public final String forge;
    public final List<Meta> metas;

    /** One .pw.toml entry. {@code path} is where the file gets installed, relative to the pack. */
    public record Meta(Path metaFile, String path, String name, String filename, String side, String mode,
            String url, long curseforgeFileId) {
        public boolean fromCurseforge() {
            return "metadata:curseforge".equals(mode);
        }
    }

    private Pack(Path dir, String name, String version, String forge, List<Meta> metas) {
        this.dir = dir;
        this.name = name;
        this.version = version;
        this.forge = forge;
        this.metas = metas;
    }

    /** pack.toml lives either at the root of the repo or in pack/. */
    public static Path findDir(Path root) {
        for (Path dir : List.of(root, root.resolve("pack"))) {
            if (Files.exists(dir.resolve("pack.toml"))) {
                return dir;
            }
        }
        throw new Fail("no pack.toml in " + root + " or " + root.resolve("pack"));
    }

    public static Pack load(Path root) {
        Path dir = findDir(root);
        TomlParseResult pack = parse(dir.resolve("pack.toml"));
        String minecraft = pack.getString("versions.minecraft");
        if (!"1.7.10".equals(minecraft)) {
            throw new Fail("ModpackCI only supports Minecraft 1.7.10, pack.toml says " + minecraft);
        }
        String forge = pack.getString("versions.forge");
        if (forge == null) {
            throw new Fail("pack.toml has no Forge version under [versions]");
        }
        TomlParseResult index = parse(dir.resolve(pack.getString("index.file", () -> "index.toml")));
        List<Meta> metas = new ArrayList<>();
        TomlArray files = index.getArrayOrEmpty("files");
        for (int i = 0; i < files.size(); i++) {
            TomlTable entry = files.getTable(i);
            if (!entry.getBoolean("metafile", () -> false)) {
                continue;
            }
            Path metaFile = dir.resolve(entry.getString("file"));
            TomlParseResult meta = parse(metaFile);
            String filename = meta.getString("filename");
            Path parent = Path.of(entry.getString("file")).getParent();
            String path = (parent == null ? "" : parent.toString().replace('\\', '/') + "/") + filename;
            metas.add(new Meta(metaFile, path, meta.getString("name"), filename,
                    meta.getString("side", () -> "both"), meta.getString("download.mode", () -> ""),
                    meta.getString("download.url"), meta.getLong("update.curseforge.file-id", () -> 0L)));
        }
        return new Pack(dir, pack.getString("name"), pack.getString("version", () -> "0.0.0"), forge, metas);
    }

    private static TomlParseResult parse(Path file) {
        if (!Files.exists(file)) {
            throw new Fail("missing " + file);
        }
        TomlParseResult result = Toml.parse(FileUtil.readString(file));
        if (result.hasErrors()) {
            throw new Fail(file + ": " + result.errors().get(0));
        }
        return result;
    }

    /** The lwjgl3ify version the pack lists, if it lists lwjgl3ify. */
    public Optional<String> lwjgl3ifyVersion() {
        for (Meta m : metas) {
            Matcher matcher = LWJGL3IFY.matcher(m.filename());
            if (matcher.matches()) {
                return Optional.of(matcher.group(1));
            }
        }
        return Optional.empty();
    }

    public boolean hasUniMixins() {
        return metas.stream().anyMatch(m -> m.filename().toLowerCase(Locale.ROOT).contains("unimixins"));
    }

    /** The name, safe to use in file names. */
    public String fileName() {
        return name.replaceAll("[^A-Za-z0-9._-]+", "-");
    }

    /** Forge's Maven version, e.g. 1.7.10-10.13.4.1614-1.7.10. Builds after 1291 carry the suffix. */
    public String forgeMavenVersion() {
        String[] parts = forge.split("\\.");
        int build = Integer.parseInt(parts[parts.length - 1]);
        return "1.7.10-" + forge + (build > 1291 ? "-1.7.10" : "");
    }

    /**
     * Writes {@code newVersion} into pack.toml until the returned handle is closed, so packwiz
     * exports carry the release version.
     */
    public AutoCloseable overrideVersion(String newVersion) {
        Path file = dir.resolve("pack.toml");
        String original = FileUtil.readString(file);
        String line = "version = \"" + newVersion + "\"";
        String changed = Pattern.compile("(?m)^version\\s*=").matcher(original).find()
                ? original.replaceFirst("(?m)^version\\s*=.*$", Matcher.quoteReplacement(line))
                : original.replaceFirst("(?m)^(name\\s*=.*)$", "$1\n" + Matcher.quoteReplacement(line));
        FileUtil.write(file, changed);
        return () -> FileUtil.write(file, original);
    }
}
