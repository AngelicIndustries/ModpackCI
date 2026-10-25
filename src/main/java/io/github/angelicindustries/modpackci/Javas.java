package io.github.angelicindustries.modpackci;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/** Java 8 and 25 for running the game, downloaded from foojay unless modpack-local.ini says otherwise. */
public final class Javas {
    private Javas() {}

    public static Path get(Project project, int version) {
        Config.Key key = version == 8 ? Config.Key.JAVA8 : Config.Key.JAVA25;
        String override = project.config.get(key);
        if (override != null) {
            Path path = Path.of(override);
            if (!Files.isRegularFile(path)) {
                throw new Fail(key.name + " points to " + path + ", which doesn't exist");
            }
            return path;
        }
        if (Runtime.version().feature() == version) {
            return Path.of(Processes.currentJava());
        }
        return download(project, version);
    }

    private static Path download(Project project, int version) {
        Platform platform = Platform.current();
        String arch = Platform.arch();
        Path dir = project.cache("jre").resolve("zulu-" + version + "-" + platform.id + "-" + arch);
        Optional<Path> existing = find(dir, platform);
        if (existing.isPresent()) {
            return existing.get();
        }
        String archive = platform == Platform.WINDOWS ? "zip" : "tar.gz";
        String url = "https://api.foojay.io/disco/v3.0/directuris?distro=zulu&package_type=jre"
                + "&javafx_bundled=false&latest=available&version=" + version
                + "&operating_system=" + platform.id + "&architecture=" + arch + "&archive_type=" + archive
                + (platform == Platform.LINUX ? "&lib_c_type=glibc" : "");
        Log.info("downloading Java " + version);
        Path tmp = dir.resolveSibling(dir.getFileName() + ".tmp");
        FileUtil.deleteTree(tmp);
        Path file = Http.download(url, tmp.resolve("jre." + archive));
        if (archive.equals("zip")) {
            FileUtil.unzip(file, tmp);
        } else {
            Processes.runOrFail(List.of("tar", "-xzf", file.toString(), "-C", tmp.toString()), tmp, "unpacking Java");
        }
        FileUtil.deleteTree(dir);
        try {
            Files.delete(file);
            Files.move(tmp, dir);
        } catch (java.io.IOException e) {
            throw new Fail("couldn't set up Java " + version + " in " + dir + ": " + e.getMessage(), e);
        }
        return find(dir, platform).orElseThrow(() -> new Fail("the Java " + version + " download has no java binary"));
    }

    private static Optional<Path> find(Path dir, Platform platform) {
        if (!Files.isDirectory(dir)) {
            return Optional.empty();
        }
        String exe = platform.exe("java");
        return FileUtil.walkFiles(dir).stream()
                .filter(p -> p.getFileName().toString().equals(exe) && p.getParent().getFileName().toString().equals("bin"))
                .findFirst();
    }
}
