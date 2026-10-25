package io.github.angelicindustries.modpackci;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
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
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * The CI smoke tests. The server test starts the server, sends "stop" once it's up and checks it
 * shut down cleanly. The client test launches the pack in a portable Prism under Xvfb with a
 * helper mod that opens a new singleplayer world, runs it for 30 seconds and quits.
 *
 * <p>The client logs in with an offline account. Prism only allows that next to a Microsoft
 * account that owns the game, so a fake one goes into accounts.json, and Prism goes through
 * {@link LoginBlockingProxy} so its refresh of that account fails as offline.
 */
public final class Smoke {
    public static final String PRISM_VERSION = "11.1.1";
    private static final String FAKE_CLIENT_ID = "modpackci-smoke-test";
    private static final String WORLD_RAN = "ModpackCI smoke test: world ran for ";
    private static final List<String> FATAL_LINES = List.of(
            "Fatal errors were detected",
            "forced into state SERVER_STOPPED",
            "Exception stopping the server",
            "FAILED TO BIND TO PORT");
    private static final List<String> REPORT_DIRS = List.of("crash-reports");

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
            waitFor(proc, log, "For help, type \"help\"", timeoutSeconds);
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
            proc.destroyForcibly();
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
        Path prism = prism(project);
        String instanceId = "smoke-" + flavor.id;
        Path inst = prism.resolve("instances").resolve(instanceId);
        Path java = Javas.get(project, flavor.javaVersion);
        ClientInstance.setup(project, inst, flavor, project.pack().name + " smoke test (" + flavor.label + ")", java, true);
        Path game = inst.resolve(".minecraft");
        Path log = game.resolve("logs/fml-client-latest.log");
        deleteQuietly(log);
        clearReports(game);
        FileUtil.deleteTree(game.resolve("saves/modpackci-smoketest"));
        try (InputStream helper = Smoke.class.getResourceAsStream("/smoketest/modpackci-smoketest.jar")) {
            FileUtil.write(game.resolve("mods/modpackci-smoketest.jar"), helper.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        Prefetch.instance(prism, inst);
        try (LoginBlockingProxy proxy = new LoginBlockingProxy()) {
            writePrismConfig(prism, proxy.port());
            // Prism's own log goes to prism/logs, so its console output stays out of ours.
            Process proc = start(new ProcessBuilder(xvfbRun.toString(), "-a", "-s", "-screen 0 1280x720x24",
                    prism.resolve("PrismLauncher").toString(), "-d", prism.toString(), "-l", instanceId)
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD));
            try {
                waitFor(proc, log, WORLD_RAN, timeoutSeconds);
                // The helper quits the game, and Prism exits with it.
                if (!proc.waitFor(180, TimeUnit.SECONDS)) {
                    throw new Fail("the game didn't exit within 180s after the world test");
                }
            } finally {
                proc.descendants().forEach(ProcessHandle::destroyForcibly);
                proc.destroyForcibly();
                if (Files.exists(log)) {
                    Log.info(FileUtil.readString(log));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted", e);
        }
        check(game, log);
        if (!Files.exists(game.resolve("saves/modpackci-smoketest/level.dat"))) {
            throw new Fail("the world wasn't saved on shutdown");
        }
        Log.info(flavor.label + " client smoke test passed");
    }

    private static Path prism(Project project) {
        Path prism = dir(project).resolve("prism");
        if (!Files.exists(prism.resolve("PrismLauncher"))) {
            String arch = Platform.arch().equals("x64") ? "" : "aarch64-";
            String url = "https://github.com/PrismLauncher/PrismLauncher/releases/download/" + PRISM_VERSION
                    + "/PrismLauncher-Linux-" + arch + "Qt6-Portable-" + PRISM_VERSION + ".tar.gz";
            Path archive = Http.download(url, project.cache("prism").resolve(PRISM_VERSION + "-" + Platform.arch() + ".tar.gz"));
            try {
                Files.createDirectories(prism);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            Processes.runOrFail(List.of("tar", "-xzf", archive.toString(), "-C", prism.toString()), prism,
                    "unpacking Prism");
        }
        return prism;
    }

    private static void writePrismConfig(Path prism, int proxyPort) {
        long now = System.currentTimeMillis() / 1000;
        long year = now + 365L * 24 * 3600;
        JsonObject owner = new JsonObject();
        owner.addProperty("type", "MSA");
        owner.addProperty("msa-client-id", FAKE_CLIENT_ID);
        owner.add("msa", token("ci", "ci", now, year));
        owner.add("ygg", token("ci", null, now, year));
        owner.add("profile", profile("CIOwner"));
        JsonObject entitlement = new JsonObject();
        entitlement.addProperty("ownsMinecraft", true);
        entitlement.addProperty("canPlayMinecraft", true);
        owner.add("entitlement", entitlement);
        JsonObject offline = new JsonObject();
        offline.addProperty("type", "Offline");
        JsonObject ygg = token("0", null, now, 0);
        JsonObject extra = new JsonObject();
        extra.addProperty("userName", "Tester");
        extra.addProperty("clientToken", UUID.randomUUID().toString().replace("-", ""));
        ygg.add("extra", extra);
        offline.add("ygg", ygg);
        offline.add("profile", profile("Tester"));
        offline.addProperty("active", true);
        JsonArray accounts = new JsonArray();
        accounts.add(owner);
        accounts.add(offline);
        JsonObject root = new JsonObject();
        root.addProperty("formatVersion", 3);
        root.add("accounts", accounts);
        FileUtil.write(prism.resolve("accounts.json"), root.toString());
        FileUtil.write(prism.resolve("prismlauncher.cfg"), String.join("\n",
                "[General]",
                "Language=en_US",
                "ApplicationTheme=dark",
                "IconTheme=pe_colored",
                "IgnoreJavaWizard=true",
                "AutomaticJavaDownload=false",
                "AutomaticJavaSwitch=false",
                "UserAskedAboutAutomaticJavaDownload=true",
                "MSAClientIDOverride=" + FAKE_CLIENT_ID,
                "QuitAfterGameStop=true",
                "ProxyType=HTTP",
                "ProxyAddr=127.0.0.1",
                "ProxyPort=" + proxyPort) + "\n");
        // Skips the "migrate your old data" prompt on machines that ran PolyMC or MultiMC.
        for (String old : List.of("PolyMC", "MultiMC")) {
            FileUtil.write(prism.resolve(old + "_nomigrate.txt"), "");
        }
    }

    private static JsonObject token(String token, String refresh, long issued, long expires) {
        JsonObject t = new JsonObject();
        t.addProperty("token", token);
        if (refresh != null) {
            t.addProperty("refresh_token", refresh);
        }
        t.addProperty("iat", issued);
        if (expires > 0) {
            t.addProperty("exp", expires);
        }
        return t;
    }

    private static JsonObject profile(String name) {
        JsonObject skin = new JsonObject();
        skin.addProperty("id", "");
        skin.addProperty("url", "");
        skin.addProperty("variant", "");
        JsonObject p = new JsonObject();
        p.addProperty("id", UUID.randomUUID().toString().replace("-", ""));
        p.addProperty("name", name);
        p.add("skin", skin);
        p.add("capes", new JsonArray());
        return p;
    }

    private static Process start(ProcessBuilder builder) {
        try {
            return builder.start();
        } catch (IOException e) {
            throw new Fail("couldn't start " + builder.command().get(0) + ": " + e.getMessage(), e);
        }
    }

    private static void waitFor(Process proc, Path log, String marker, int timeoutSeconds) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds);
        while (System.nanoTime() < deadline) {
            // Check for exit first: the game flushes its log while exiting.
            boolean exited = !proc.isAlive();
            if (Files.exists(log) && FileUtil.readString(log).contains(marker)) {
                return;
            }
            if (exited) {
                throw new Fail("exited with code " + proc.exitValue() + " before logging '" + marker + "'");
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

    private static void clearReports(Path gameDir) {
        for (Path report : reports(gameDir)) {
            deleteQuietly(report);
        }
    }

    private static List<Path> reports(Path gameDir) {
        List<Path> out = new ArrayList<>();
        for (String sub : REPORT_DIRS) {
            Path d = gameDir.resolve(sub);
            if (Files.isDirectory(d)) {
                out.addAll(FileUtil.walkFiles(d));
            }
        }
        try (Stream<Path> files = Files.list(gameDir)) {
            files.filter(f -> f.getFileName().toString().startsWith("hs_err_pid")).forEach(out::add);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
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
