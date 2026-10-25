package io.github.angelicindustries.modpackci;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The CI smoke tests. The server test starts the server, sends "stop" once it's up and checks it
 * shut down cleanly. The client test starts the game under Xvfb with a helper mod that opens a new
 * singleplayer world, runs it for 30 seconds and quits.
 */
public final class Smoke {
    private static final String WORLD_RAN = "ModpackCI smoke test: world ran for ";
    private static final String WORLD = "modpackci-smoketest";
    private static final List<String> FATAL_LINES = List.of(
            "Fatal errors were detected",
            "forced into state SERVER_STOPPED",
            "Exception stopping the server",
            "FAILED TO BIND TO PORT");

    private Smoke() {}

    public static Path dir(Project project) {
        return project.buildDir.resolve("smoke");
    }

    public static void server(Project project, Flavor flavor, int timeoutSeconds) {
        Path srv = dir(project).resolve("server-" + flavor.id);
        ServerInstall.setup(project, srv, flavor);
        ClientInstance.setKeys(srv.resolve("server.properties"), Map.of("server-port", String.valueOf(freePort())));
        clearReports(srv);
        Path log = srv.resolve("smoke.log");
        Process proc = start(new ProcessBuilder(ServerInstall.command(project, flavor)).directory(srv.toFile())
                .redirectErrorStream(true).redirectOutput(log.toFile()));
        try {
            waitFor(proc, log, log, "For help, type \"help\"", timeoutSeconds);
            try (OutputStream stdin = proc.getOutputStream()) {
                stdin.write("stop\n".getBytes(StandardCharsets.UTF_8));
            }
            if (!proc.waitFor(120, TimeUnit.SECONDS)) {
                throw new Fail("the server didn't stop within 120s");
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted", e);
        } finally {
            kill(proc);
            Log.info(FileUtil.readString(log));
        }
        if (proc.exitValue() != 0) {
            throw new Fail("the server exited with code " + proc.exitValue());
        }
        check(srv, log);
        Log.info(flavor.label + " server smoke test passed");
    }

    public static void client(Project project, Flavor flavor, int timeoutSeconds) {
        if (Platform.current() != Platform.LINUX) {
            throw new Fail("the client smoke test needs Linux with Xvfb");
        }
        Path xvfbRun = Processes.which("xvfb-run").orElseThrow(() -> new Fail(
                "the client smoke test needs xvfb-run (on Debian and Ubuntu: apt install xvfb)"));
        Path game = dir(project).resolve("client-" + flavor.id);
        Path log = game.resolve("logs/fml-client-latest.log");
        deleteQuietly(log);
        clearReports(game);
        FileUtil.deleteTree(game.resolve("saves").resolve(WORLD));
        List<String> command = new ArrayList<>(List.of(xvfbRun.toString(), "-a", "-s", "-screen 0 1280x720x24"));
        command.addAll(ClientRun.prepare(project, game, flavor, "Tester"));
        try (InputStream helper = Smoke.class.getResourceAsStream("/smoketest/modpackci-smoketest.jar")) {
            FileUtil.write(game.resolve("mods/modpackci-smoketest.jar"), helper.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Path console = game.resolve("console.log");
        ProcessBuilder builder = new ProcessBuilder(command).directory(game.toFile())
                .redirectErrorStream(true).redirectOutput(console.toFile());
        // lwjgl3ify writes a desktop entry there on Linux and quits if the folder is missing, as it
        // is on CI machines. This also keeps test runs out of the real ~/.local/share.
        Path xdgData = game.resolve("xdg-data");
        FileUtil.write(xdgData.resolve(".keep"), "");
        builder.environment().put("XDG_DATA_HOME", xdgData.toString());
        Process proc = start(builder);
        try {
            waitFor(proc, console, log, WORLD_RAN, timeoutSeconds);
            // The helper quits the game once the world has run.
            if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                throw new Fail("the game didn't exit within 180s after the world test");
            }
        } catch (Fail f) {
            Log.info("game console (last 200 lines):\n" + tail(console, 200));
            throw f;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted", e);
        } finally {
            kill(proc);
        }
        if (proc.exitValue() != 0) {
            Log.info("game console (last 200 lines):\n" + tail(console, 200));
            throw new Fail("the game exited with code " + proc.exitValue());
        }
        check(game, log);
        if (!Files.exists(game.resolve("saves").resolve(WORLD).resolve("level.dat"))) {
            throw new Fail("the world wasn't saved on shutdown");
        }
        Log.info(flavor.label + " client smoke test passed");
    }

    /**
     * Waits for {@code marker} in {@code output}, failing as soon as the process exits without it.
     * Every 30 seconds it prints the latest line of {@code progress}, so a slow run looks
     * different from a stuck one.
     */
    private static void waitFor(Process proc, Path output, Path progress, String marker, int timeoutSeconds) {
        long start = System.nanoTime();
        long deadline = start + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long nextProgress = start + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            // Check for exit first: the game flushes its output while exiting.
            boolean exited = !proc.isAlive();
            if (Files.exists(output) && FileUtil.readString(output).contains(marker)) {
                return;
            }
            if (exited) {
                throw new Fail("exited with code " + proc.exitValue() + " before logging '" + marker + "'");
            }
            if (System.nanoTime() > nextProgress) {
                long seconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
                Log.info("still waiting after " + seconds + "s: " + lastLine(Files.exists(progress) ? progress : output));
                nextProgress += TimeUnit.SECONDS.toNanos(30);
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new Fail("interrupted", e);
            }
        }
        throw new Fail("'" + marker + "' not logged within " + timeoutSeconds + "s");
    }

    private static String lastLine(Path file) {
        String last = tail(file, 1);
        return last.length() > 200 ? last.substring(0, 200) + "..." : last;
    }

    private static String tail(Path file, int lines) {
        if (!Files.exists(file)) {
            return "(no output)";
        }
        List<String> all = List.of(FileUtil.readString(file).strip().split("\\R"));
        return String.join("\n", all.subList(Math.max(0, all.size() - lines), all.size()));
    }

    private static void kill(Process proc) {
        proc.descendants().forEach(ProcessHandle::destroyForcibly);
        proc.destroyForcibly();
    }

    private static Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new Fail("couldn't start " + builder.command().get(0) + ": " + e.getMessage(), e);
        }
    }

    private static List<Path> reports(Path gameDir) {
        List<Path> out = new ArrayList<>();
        Path crashes = gameDir.resolve("crash-reports");
        if (Files.isDirectory(crashes)) {
            out.addAll(FileUtil.walkFiles(crashes));
        }
        if (Files.isDirectory(gameDir)) {
            try (Stream<Path> files = Files.list(gameDir)) {
                files.filter(f -> f.getFileName().toString().startsWith("hs_err_pid")).forEach(out::add);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return out;
    }

    private static void clearReports(Path gameDir) {
        reports(gameDir).forEach(Smoke::deleteQuietly);
    }

    private static void check(Path gameDir, Path log) {
        List<String> problems = new ArrayList<>();
        for (Path report : reports(gameDir)) {
            problems.add(report.getFileName() + ":\n" + FileUtil.readString(report));
        }
        String text = Files.exists(log) ? FileUtil.readString(log) : "";
        for (String line : FATAL_LINES) {
            if (text.contains(line)) {
                problems.add("log contains: " + line);
            }
        }
        if (!problems.isEmpty()) {
            throw new Fail("smoke test failed:\n" + String.join("\n", problems));
        }
    }

    private static int freePort() {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteQuietly(Path p) {
        try {
            Files.deleteIfExists(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
