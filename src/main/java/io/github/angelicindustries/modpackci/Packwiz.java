package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * packwiz itself. ModpackCI releases carry a packwiz build for each OS, so nobody has to install
 * Go or packwiz.
 */
public final class Packwiz {
    private Packwiz() {}

    public static Path binary(Project project) {
        String override = project.config.get(Config.Key.PACKWIZ);
        if (override != null) {
            Path path = Path.of(override);
            if (Files.isRegularFile(path)) {
                return path;
            }
            return Processes.which(override)
                    .orElseThrow(() -> new Fail("packwiz is set to " + override + ", which doesn't exist"));
        }
        if (Main.VERSION.equals("dev")) {
            return Processes.which("packwiz").orElseThrow(() -> new Fail(
                    "this is a development build of ModpackCI, so it can't download packwiz. "
                            + "Put packwiz on PATH or set packwiz in " + Config.LOCAL_FILE));
        }
        Platform platform = Platform.current();
        String asset = platform.exe("packwiz-" + platform.id + "-" + Platform.arch());
        Path target = project.cache("packwiz").resolve(Main.VERSION).resolve(platform.exe("packwiz"));
        if (!Files.exists(target)) {
            Http.download("https://github.com/" + project.repository + "/releases/download/v" + Main.VERSION
                    + "/" + asset, target);
            target.toFile().setExecutable(true, false);
        }
        return target;
    }

    public static int run(Project project, List<String> args) {
        List<String> command = new ArrayList<>();
        command.add(binary(project).toString());
        command.addAll(args);
        // Not project.pack(): that reads every metafile, and fixing a broken index is what refresh is for.
        return Processes.run(command, Pack.findDir(project.root));
    }

    public static void runOrFail(Project project, String... args) {
        int code = run(project, List.of(args));
        if (code != 0) {
            throw new Fail("packwiz " + args[0] + " failed (see its output above)");
        }
    }
}
