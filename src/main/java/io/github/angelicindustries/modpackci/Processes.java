package io.github.angelicindustries.modpackci;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

public final class Processes {
    private Processes() {}

    /** Runs a command with the console attached and returns its exit code. */
    public static int run(List<String> command, Path workDir) {
        try {
            return new ProcessBuilder(command).directory(workDir.toFile()).inheritIO().start().waitFor();
        } catch (IOException e) {
            throw new Fail("couldn't run " + command.get(0) + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted while running " + command.get(0), e);
        }
    }

    public static void runOrFail(List<String> command, Path workDir, String what) {
        int code = run(command, workDir);
        if (code != 0) {
            throw new Fail(what + " failed with exit code " + code + " (see its output above)");
        }
    }

    /** The java binary this JVM runs on. */
    public static String currentJava() {
        return ProcessHandle.current().info().command().orElse("java");
    }

    /** Finds an executable on PATH, the way a shell would. */
    public static Optional<Path> which(String name) {
        String path = System.getenv("PATH");
        if (path == null) {
            return Optional.empty();
        }
        List<String> exts = Platform.current() == Platform.WINDOWS
                ? List.of(".exe", ".bat", ".cmd", "")
                : List.of("");
        for (String dir : path.split(File.pathSeparator)) {
            for (String ext : exts) {
                Path candidate = Path.of(dir, name + ext);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) {
                    return Optional.of(candidate);
                }
            }
        }
        return Optional.empty();
    }
}
