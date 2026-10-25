package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Finds a Prism-family launcher (Prism Launcher, Fjord Launcher, PolyMC). They share the instance
 * format and the --launch flag. modpack-local.ini can point at one explicitly with launcher and
 * launcherData.
 */
public final class Launchers {
    /** A launcher: its data folder, and the command that starts it. */
    public record Launcher(String name, Path data, List<String> command, boolean flatpak, boolean customData) {}

    private record Known(String name, String binary, String flatpakId, String macApp) {}

    private static final List<Known> KNOWN = List.of(
            new Known("PrismLauncher", "prismlauncher", "org.prismlauncher.PrismLauncher", "Prism Launcher"),
            new Known("FjordLauncher", "fjordlauncher", "io.github.unmojang.FjordLauncher", "Fjord Launcher"),
            new Known("PolyMC", "polymc", "org.polymc.PolyMC", "PolyMC"));

    private Launchers() {}

    public static Launcher find(Project project) {
        String binary = project.config.get(Config.Key.LAUNCHER);
        String data = project.config.get(Config.Key.LAUNCHER_DATA);
        if (binary != null) {
            Path bin = Path.of(binary);
            if (!Files.isRegularFile(bin)) {
                throw new Fail("launcher points to " + bin + ", which doesn't exist");
            }
            Path dataDir = data != null ? Path.of(data)
                    : Files.exists(bin.toAbsolutePath().getParent().resolve("portable.txt"))
                            ? bin.toAbsolutePath().getParent()
                            : null;
            if (dataDir == null) {
                throw new Fail("launcher is set, so also set launcherData in " + Config.LOCAL_FILE
                        + " (the folder with instances/ in it)");
            }
            return new Launcher(bin.getFileName().toString(), dataDir, List.of(bin.toString()), false, true);
        }
        List<Launcher> found = new ArrayList<>();
        for (Known k : KNOWN) {
            found.addAll(candidates(k));
        }
        for (Launcher l : found) {
            if ((data == null && Files.isDirectory(l.data)) || (data != null && l.data.equals(Path.of(data)))) {
                return l;
            }
        }
        if (data != null && !found.isEmpty()) {
            Launcher first = found.get(0);
            return new Launcher(first.name, Path.of(data), first.command, first.flatpak, true);
        }
        throw new Fail("couldn't find Prism Launcher, Fjord Launcher or PolyMC. Set launcher and launcherData in "
                + Config.LOCAL_FILE);
    }

    /** Installed launchers, each paired with the data folder it uses. */
    private static List<Launcher> candidates(Known k) {
        List<Launcher> out = new ArrayList<>();
        Path home = Platform.home();
        switch (Platform.current()) {
            case LINUX -> {
                String xdg = System.getenv("XDG_DATA_HOME");
                Path dataHome = xdg != null && !xdg.isBlank() ? Path.of(xdg) : home.resolve(".local/share");
                Processes.which(k.binary).ifPresent(bin ->
                        out.add(new Launcher(k.name, dataHome.resolve(k.name), List.of(bin.toString()), false, false)));
                Processes.which("flatpak").ifPresent(fp -> out.add(new Launcher(k.name,
                        home.resolve(".var/app/" + k.flatpakId + "/data/" + k.name),
                        List.of(fp.toString(), "run", k.flatpakId), true, false)));
            }
            case WINDOWS -> {
                Path appData = Path.of(System.getenv("APPDATA"));
                Path installed = Path.of(System.getenv("LOCALAPPDATA"), "Programs", k.name, k.binary + ".exe");
                Path bin = Files.exists(installed) ? installed : Processes.which(k.binary).orElse(null);
                if (bin != null) {
                    Path portable = bin.getParent();
                    Path data = Files.exists(portable.resolve("portable.txt")) ? portable : appData.resolve(k.name);
                    out.add(new Launcher(k.name, data, List.of(bin.toString()), false, false));
                }
            }
            case MACOS -> {
                for (Path apps : List.of(Path.of("/Applications"), home.resolve("Applications"))) {
                    Path bin = apps.resolve(k.macApp + ".app/Contents/MacOS/" + k.binary);
                    if (Files.exists(bin)) {
                        out.add(new Launcher(k.name, home.resolve("Library/Application Support/" + k.name),
                                List.of(bin.toString()), false, false));
                    }
                }
            }
        }
        return out;
    }

    /** Syncs a dev instance into the launcher and starts it, like GTNH's runClient. */
    public static void runClient(Project project, Flavor flavor) {
        Launcher launcher = find(project);
        Pack pack = project.pack();
        // A Flatpak launcher can't see Java installs outside its sandbox, so it picks its own.
        Path java = launcher.flatpak ? null : Javas.get(project, flavor.javaVersion);
        String id = pack.fileName() + "-dev-" + flavor.id;
        Log.info("using " + launcher.name + " (" + launcher.data + ")");
        ClientInstance.setup(project, launcher.data.resolve("instances").resolve(id), flavor,
                pack.name + " (dev, " + flavor.label + ")", java, false);
        List<String> command = new ArrayList<>(launcher.command);
        if (launcher.customData) {
            command.addAll(List.of("-d", launcher.data.toString()));
        }
        command.addAll(List.of("--launch", id));
        Processes.run(command, project.root);
    }

    /** Runs a local server in run/, with its console attached, like GTNH's runServer. */
    public static void runServer(Project project, Flavor flavor) {
        Path dir = project.root.resolve("run").resolve("server-" + flavor.id);
        ServerInstall.setup(project, dir, flavor);
        Processes.run(ServerInstall.command(project, flavor), dir);
    }
}
