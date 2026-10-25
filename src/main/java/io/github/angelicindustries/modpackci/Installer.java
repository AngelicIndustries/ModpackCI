package io.github.angelicindustries.modpackci;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** packwiz-installer, which syncs a folder with the pack and only applies what changed. */
public final class Installer {
    private static final String BOOTSTRAP_URL =
            "https://github.com/packwiz/packwiz-installer-bootstrap/releases/download/v0.0.3/packwiz-installer-bootstrap.jar";

    private Installer() {}

    public static void sync(Project project, String side, Path folder, Path multimcFolder) {
        Path dir = project.cache("packwiz-installer");
        Path bootstrap = Http.download(BOOTSTRAP_URL, dir.resolve("packwiz-installer-bootstrap.jar"));
        List<String> command = new ArrayList<>(List.of(Processes.currentJava(), "-jar", bootstrap.toString(),
                "-g", "-s", side, "--bootstrap-main-jar", dir.resolve("packwiz-installer.jar").toString(),
                "--pack-folder", folder.toString()));
        if (multimcFolder != null) {
            command.addAll(List.of("--multimc-folder", multimcFolder.toString()));
        }
        command.add(project.pack().dir.resolve("pack.toml").toString());
        try {
            java.nio.file.Files.createDirectories(folder);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        Processes.runOrFail(command, folder, "packwiz-installer");
    }
}
