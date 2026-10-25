package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** A Forge 1.7.10 server, synced from the pack. */
public final class ServerInstall {
    private ServerInstall() {}

    public static void setup(Project project, Path dir, Flavor flavor) {
        Pack pack = project.pack();
        String forge = pack.forgeMavenVersion();
        try {
            Files.createDirectories(dir);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        if (!Files.exists(dir.resolve(universalJar(pack)))) {
            String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + forge + "/forge-" + forge
                    + "-installer.jar";
            Path installer = Http.download(url, project.cache("forge").resolve("forge-" + forge + "-installer.jar"));
            Log.info("installing the Forge server");
            Processes.runOrFail(List.of(Processes.currentJava(), "-jar", installer.toString(), "--installServer"),
                    dir, "the Forge installer");
        }
        if (flavor == Flavor.JAVA25) {
            Lwjgl3ify.requireForge(pack);
            String version = Lwjgl3ify.version(pack);
            FileUtil.copy(Lwjgl3ify.asset(project, version, "-forgePatches.jar"), dir.resolve("lwjgl3ify-forgePatches.jar"));
            FileUtil.copy(Lwjgl3ify.java9Args(project, version), dir.resolve("java9args.txt"));
        }
        FileUtil.write(dir.resolve("eula.txt"), "eula=true\n");
        Path properties = dir.resolve("server.properties");
        if (!Files.exists(properties)) {
            FileUtil.write(properties, "");
        }
        ClientInstance.setKeys(properties, Map.of("online-mode", "false"));
        Installer.sync(project, "server", dir, null);
        Lwjgl3ify.adjustMods(project, flavor, dir.resolve("mods"));
    }

    /** runServer: a local server in run/, with its console attached, like GTNH's runServer. */
    public static void run(Project project, Flavor flavor) {
        Path dir = project.root.resolve("run").resolve("server-" + flavor.id);
        setup(project, dir, flavor);
        Processes.run(command(project, flavor), dir);
    }

    public static String universalJar(Pack pack) {
        return "forge-" + pack.forgeMavenVersion() + "-universal.jar";
    }

    public static List<String> command(Project project, Flavor flavor) {
        String java = Javas.get(project, flavor.javaVersion).toString();
        return flavor == Flavor.JAVA25
                ? List.of(java, "-Xmx2G", "@java9args.txt", "-jar", "lwjgl3ify-forgePatches.jar", "nogui")
                : List.of(java, "-Xmx2G", "-jar", universalJar(project.pack()), "nogui");
    }
}
