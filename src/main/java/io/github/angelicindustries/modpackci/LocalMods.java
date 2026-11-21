package io.github.angelicindustries.modpackci;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipFile;

/**
 * The localMods setting: Gradle projects or jars on this machine that runClient and runServer add
 * to the pack. A local jar with the same mod ID as a pack mod replaces it for the run. Builds,
 * releases and smoke tests never see them.
 */
public final class LocalMods {
    private static final String ADDED_LIST = ".modpackci-local";
    /** Pack jars a local mod replaced, outside mods/ so Forge doesn't load them. */
    private static final String REPLACED_DIR = ".modpackci-replaced";
    /** Gradle outputs that aren't the jar to play with, as named by the GTNH buildscript. */
    private static final Pattern NOT_RELEASE_JAR =
            Pattern.compile(".*-(dev|dev-preshadow|preshadow|predowngrade|sources|javadoc|api|slim)\\.jar");

    /** Where the fingerprints of the last builds are kept, in the pack's build folder. */
    private static final String FINGERPRINTS = "local-mods.json";
    /** Folders at a project's root that never feed its jar: Gradle's output, the dev runs and logs. */
    private static final Set<String> NOT_INPUTS = Set.of("build", "run", "out", "logs");

    private LocalMods() {}

    private record Entry(String spec, Path path, String side) {}

    /** Which Gradle projects runClient and runServer build: the changed ones, all, or none. */
    public enum Build {
        CHANGED,
        ALL,
        NONE;

        /** --rebuild builds every project, --no-build none. */
        public static Build of(List<String> args) {
            return args.contains("--no-build") ? NONE : args.contains("--rebuild") ? ALL : CHANGED;
        }
    }

    /**
     * Undoes the last run: removes the local jars it added and puts back the pack jars they
     * replaced, so the sync finds them and doesn't download them again.
     */
    public static void restore(Path game) {
        Path mods = game.resolve("mods");
        Path list = mods.resolve(ADDED_LIST);
        try {
            if (Files.exists(list)) {
                for (String name : Files.readAllLines(list)) {
                    if (!name.isBlank()) {
                        Files.deleteIfExists(mods.resolve(name));
                    }
                }
                Files.delete(list);
            }
            Path replaced = game.resolve(REPLACED_DIR);
            if (Files.isDirectory(replaced)) {
                for (Path jar : list(replaced)) {
                    Path back = mods.resolve(jar.getFileName().toString());
                    if (Files.exists(back)) {
                        Files.delete(jar);
                    } else {
                        Files.createDirectories(mods);
                        Files.move(jar, back);
                    }
                }
                Files.delete(replaced);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * The jars for a run on {@code side}, building Gradle projects first as {@code build} says. A
     * project whose files are the same as at its last build (see {@link #fingerprint}) isn't built
     * again. Runs before the sync, so a broken build stops the run early.
     */
    public static List<Path> jars(Project project, String side, Build build) {
        List<Path> out = new ArrayList<>();
        Path fingerprintFile = project.buildDir.resolve(FINGERPRINTS);
        Map<String, String> fingerprints = readFingerprints(fingerprintFile);
        boolean built = false;
        for (Entry entry : entries(project)) {
            if (!entry.side.equals("both") && !entry.side.equals(side)) {
                continue;
            }
            if (Files.isRegularFile(entry.path)) {
                out.add(entry.path);
                continue;
            }
            Path gradlew = entry.path.resolve(Platform.current() == Platform.WINDOWS ? "gradlew.bat" : "gradlew");
            if (!Files.isRegularFile(gradlew)) {
                throw new Fail(Config.Key.LOCAL_MODS.name + ": " + entry.spec + " is neither a jar nor a Gradle project with a wrapper");
            }
            String key = entry.path.toString();
            if (build == Build.CHANGED && fingerprint(entry.path).equals(fingerprints.get(key))
                    && newestJar(entry).isPresent()) {
                Log.info(entry.spec + " is unchanged since its last build");
            } else if (build != Build.NONE) {
                Log.info("building " + entry.spec);
                Processes.runOrFail(List.of(gradlew.toString(), "assemble"), entry.path, "building " + entry.spec);
                // Taken after the build, so files the build itself rewrites don't count as changes
                fingerprints.put(key, fingerprint(entry.path));
                built = true;
            }
            Path jar = newestJar(entry).orElseThrow(() -> new Fail(Config.Key.LOCAL_MODS.name + ": no jar in "
                    + entry.spec + "/build/libs" + (build != Build.NONE ? "" : ". Run without --no-build to build it.")));
            if (build == Build.NONE) {
                // Dev runs only update the -dev jar, which can't run outside the dev environment. A
                // build writes its -dev jar before the release jar, so a newer one means a later dev run.
                list(jar.getParent()).stream()
                        .filter(f -> f.getFileName().toString().endsWith("-dev.jar")
                                && f.toFile().lastModified() > jar.toFile().lastModified())
                        .max(Comparator.comparing(f -> f.toFile().lastModified()))
                        .ifPresent(f -> Log.warn(entry.spec + ": " + f.getFileName() + " is newer than "
                                + jar.getFileName() + ", so the jar used may be out of date. Run without --no-build."));
            }
            out.add(jar);
        }
        if (built) {
            writeFingerprints(fingerprintFile, fingerprints);
        }
        return out;
    }

    /**
     * What a Gradle project's jar is built from, cheaply: every file's path, size and modification
     * time, leaving out dot folders (.git, .gradle) and the root's build, run, out and logs folders.
     * It changes whenever a file that could change the jar does, and sometimes when none did, which
     * only costs a build. Files outside the project, like a sibling jar it compiles against, aren't
     * seen: --rebuild covers those.
     */
    static String fingerprint(Path dir) {
        List<String> files = new ArrayList<>();
        try {
            Files.walkFileTree(dir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path folder, BasicFileAttributes attributes) {
                    if (folder.equals(dir)) {
                        return FileVisitResult.CONTINUE;
                    }
                    String name = folder.getFileName().toString();
                    boolean skip = name.startsWith(".") || folder.getParent().equals(dir) && NOT_INPUTS.contains(name);
                    return skip ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                    files.add(dir.relativize(file).toString().replace('\\', '/') + "\t" + attributes.size() + "\t"
                            + attributes.lastModifiedTime().toMillis());
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Collections.sort(files);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            for (String file : files) {
                sha.update((file + "\n").getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Map<String, String> readFingerprints(Path file) {
        Map<String, String> out = new TreeMap<>();
        if (!Files.isRegularFile(file)) {
            return out;
        }
        try {
            JsonElement root = JsonParser.parseString(Files.readString(file));
            if (root.isJsonObject()) {
                for (Map.Entry<String, JsonElement> e : root.getAsJsonObject().entrySet()) {
                    if (e.getValue().isJsonPrimitive()) {
                        out.put(e.getKey(), e.getValue().getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Unreadable: every project gets built once and the file is written again.
        }
        return out;
    }

    private static void writeFingerprints(Path file, Map<String, String> fingerprints) {
        JsonObject root = new JsonObject();
        fingerprints.forEach(root::addProperty);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, root.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Copies the jars into the synced mods folder, moving aside pack jars with the same mod IDs. */
    public static void install(Path game, List<Path> jars) {
        if (jars.isEmpty()) {
            return;
        }
        Path mods = game.resolve("mods");
        List<String> added = new ArrayList<>();
        try {
            for (Path jar : jars) {
                Set<String> ids = modIds(jar);
                String name = jar.getFileName().toString();
                for (Path packJar : list(mods)) {
                    String packName = packJar.getFileName().toString();
                    if (!packName.equals(name) && !added.contains(packName) && !ids.isEmpty()
                            && modIds(packJar).stream().anyMatch(ids::contains)) {
                        Log.info("local " + name + " replaces " + packJar.getFileName());
                        Path aside = game.resolve(REPLACED_DIR).resolve(packName);
                        Files.createDirectories(aside.getParent());
                        Files.move(packJar, aside, StandardCopyOption.REPLACE_EXISTING);
                    }
                }
                FileUtil.copy(jar, mods.resolve(name));
                added.add(name);
            }
            Files.write(mods.resolve(ADDED_LIST), added);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Log.info("added " + added.size() + " local mod" + (added.size() == 1 ? "" : "s"));
    }

    private static List<Entry> entries(Project project) {
        String value = project.config.get(Config.Key.LOCAL_MODS);
        List<Entry> out = new ArrayList<>();
        if (value == null) {
            return out;
        }
        for (String raw : value.split(",")) {
            String spec = raw.strip();
            if (spec.isEmpty()) {
                continue;
            }
            String side = "both";
            int at = spec.lastIndexOf('@');
            if (at > 0) {
                side = spec.substring(at + 1);
                if (!side.equals("client") && !side.equals("server")) {
                    throw new Fail(Config.Key.LOCAL_MODS.name + ": " + spec + ": expected @client or @server");
                }
                spec = spec.substring(0, at);
            }
            Path path = project.root.resolve(spec).normalize();
            if (!Files.exists(path)) {
                throw new Fail(Config.Key.LOCAL_MODS.name + ": " + spec + " doesn't exist (" + path + ")");
            }
            out.add(new Entry(spec, path, side));
        }
        return out;
    }

    /**
     * The newest release jar in build/libs: one with a -sources jar next to it, since a version
     * can end in -dev itself (foo-0.1.0-dev.jar beside foo-0.1.0-dev-dev.jar). Builds without
     * sources jars fall back to skipping the usual classifiers.
     */
    private static Optional<Path> newestJar(Entry entry) {
        List<Path> jars = list(entry.path.resolve("build/libs"));
        Set<String> names = new HashSet<>();
        jars.forEach(f -> names.add(f.getFileName().toString()));
        List<Path> withSources = jars.stream()
                .filter(f -> names.contains(f.getFileName().toString().replaceFirst("\\.jar$", "-sources.jar")))
                .toList();
        List<Path> candidates = !withSources.isEmpty() ? withSources : jars.stream()
                .filter(f -> !NOT_RELEASE_JAR.matcher(f.getFileName().toString()).matches())
                .toList();
        return candidates.stream().max(Comparator.comparing(f -> f.toFile().lastModified()));
    }

    /** The jars directly in {@code dir}. */
    private static List<Path> list(Path dir) {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.filter(f -> Files.isRegularFile(f) && f.getFileName().toString().endsWith(".jar")).toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The mod IDs in a jar's mcmod.info; empty when it has none or it doesn't parse. */
    static Set<String> modIds(Path jar) {
        Set<String> ids = new HashSet<>();
        try (ZipFile zip = new ZipFile(jar.toFile())) {
            var entry = zip.getEntry("mcmod.info");
            if (entry == null) {
                return ids;
            }
            JsonElement root = JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(),
                    StandardCharsets.UTF_8));
            JsonArray mods = root.isJsonArray() ? root.getAsJsonArray()
                    : root.isJsonObject() && root.getAsJsonObject().has("modList")
                            ? root.getAsJsonObject().getAsJsonArray("modList") : new JsonArray();
            for (JsonElement mod : mods) {
                if (mod.isJsonObject()) {
                    JsonObject o = mod.getAsJsonObject();
                    if (o.has("modid") && o.get("modid").isJsonPrimitive()) {
                        ids.add(o.get("modid").getAsString());
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            // Not a readable jar, or mcmod.info isn't JSON: treat it as having no mod IDs.
        }
        return ids;
    }
}
