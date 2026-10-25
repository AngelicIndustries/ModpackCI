package io.github.angelicindustries.modpackci;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Starts the client straight from Java, the way GTNH's runClient does, without a launcher.
 * Libraries and assets are shared across packs in ~/.modpackci/minecraft.
 */
public final class ClientRun {
    private ClientRun() {}

    /** runClient: plays the pack in run/client-java8 or run/client-java25, with the local mods. */
    public static void run(Project project, Flavor flavor, boolean buildLocalMods) {
        Path game = project.root.resolve("run").resolve("client-" + flavor.id);
        LocalMods.restore(game);
        List<Path> local = LocalMods.jars(project, "client", buildLocalMods);
        List<String> command = prepare(project, game, flavor, "Player");
        LocalMods.install(game, local);
        Processes.run(command, game);
    }

    /**
     * Syncs the pack into {@code game}, makes sure every library and asset is there, and returns
     * the command that starts the game with {@code game} as its folder.
     */
    public static List<String> prepare(Project project, Path game, Flavor flavor, String username) {
        Installer.sync(project, "client", game, null);
        Lwjgl3ify.adjustMods(project, flavor, game.resolve("mods"));
        Path minecraft = project.cache("minecraft");
        Components.Launch launch = Components.merge(Components.load(project, flavor), minecraft.resolve("libraries"));
        download(launch, minecraft);
        Path natives = game.resolve("natives");
        FileUtil.deleteTree(natives);
        List<String> classpath = new ArrayList<>();
        for (Components.Library lib : launch.libraries()) {
            if (lib.natives()) {
                unpackNatives(lib.path(), natives);
            } else {
                classpath.add(lib.path().toString());
            }
        }
        List<String> command = new ArrayList<>(List.of(Javas.get(project, flavor.javaVersion).toString(),
                "-Xms512m", "-Xmx4G", "-Djava.library.path=" + natives,
                "-Dfml.ignoreInvalidMinecraftCertificates=true", "-Dfml.ignorePatchDiscrepancies=true"));
        if (Platform.current() == Platform.MACOS && launch.traits().contains("FirstThreadOnMacOS")) {
            command.add("-XstartOnFirstThread");
        }
        command.addAll(launch.jvmArgs());
        command.addAll(List.of("-cp", String.join(java.io.File.pathSeparator, classpath), launch.mainClass()));
        Map<String, String> vars = Map.of(
                "auth_player_name", username,
                "version_name", "1.7.10",
                "game_directory", game.toString(),
                "assets_root", minecraft.resolve("assets").toString(),
                "assets_index_name", launch.assetIndex().get("id").getAsString(),
                "auth_uuid", UUID.nameUUIDFromBytes(("OfflinePlayer:" + username).getBytes(StandardCharsets.UTF_8))
                        .toString().replace("-", ""),
                "auth_access_token", "0",
                "auth_session", "0",
                "user_properties", "{}",
                "user_type", "legacy");
        for (String arg : launch.minecraftArguments().split(" ")) {
            String value = arg;
            for (Map.Entry<String, String> v : vars.entrySet()) {
                value = value.replace("${" + v.getKey() + "}", v.getValue());
            }
            command.add(value);
        }
        for (String tweaker : launch.tweakers()) {
            command.addAll(List.of("--tweakClass", tweaker));
        }
        return command;
    }

    /** Downloads the libraries, the client jar and the assets, 16 at a time. */
    private static void download(Components.Launch launch, Path minecraft) {
        Map<Path, String[]> files = new LinkedHashMap<>();
        for (Components.Library lib : launch.libraries()) {
            if (lib.url() != null) {
                files.put(lib.path(), new String[] {lib.url(), lib.sha1()});
            } else if (!Files.exists(lib.path())) {
                throw new Fail("missing " + lib.path());
            }
        }
        JsonObject index = launch.assetIndex();
        Path indexFile = minecraft.resolve("assets/indexes/" + index.get("id").getAsString() + ".json");
        Http.download(index.get("url").getAsString(), indexFile, "SHA-1", index.get("sha1").getAsString());
        JsonObject objects = JsonParser.parseString(FileUtil.readString(indexFile)).getAsJsonObject()
                .getAsJsonObject("objects");
        for (Map.Entry<String, JsonElement> e : objects.entrySet()) {
            String hash = e.getValue().getAsJsonObject().get("hash").getAsString();
            String sub = hash.substring(0, 2) + "/" + hash;
            files.put(minecraft.resolve("assets/objects/" + sub),
                    new String[] {"https://resources.download.minecraft.net/" + sub, hash});
        }
        ExecutorService pool = Executors.newFixedThreadPool(16);
        try {
            List<Future<?>> jobs = new ArrayList<>();
            files.forEach((path, dl) -> jobs.add(pool.submit(() ->
                    Http.download(dl[0], path, dl[1] == null ? null : "SHA-1", dl[1]))));
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
    }

    private static void unpackNatives(Path jar, Path into) {
        try (ZipInputStream in = new ZipInputStream(Files.newInputStream(jar))) {
            for (ZipEntry e; (e = in.getNextEntry()) != null; ) {
                if (e.isDirectory() || e.getName().startsWith("META-INF/")) {
                    continue;
                }
                Path target = into.resolve(Path.of(e.getName()).getFileName().toString());
                Files.createDirectories(into);
                Files.copy(in, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
