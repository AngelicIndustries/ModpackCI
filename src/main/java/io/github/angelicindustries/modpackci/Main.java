package io.github.angelicindustries.modpackci;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Main {
    public static final String VERSION = version();

    private record Task(String help, Action action) {}

    @FunctionalInterface
    private interface Action {
        int run(Project project, List<String> args);
    }

    private static final Map<String, Task> TASKS = new LinkedHashMap<>();

    static {
        task("runClient", "Plays the pack on Java 8, in run/client-java8", (p, a) -> {
            ClientRun.run(p, Flavor.JAVA8);
            return 0;
        });
        task("runClient25", "Plays the pack on Java 25 with lwjgl3ify, in run/client-java25", (p, a) -> {
            ClientRun.run(p, Flavor.JAVA25);
            return 0;
        });
        task("runServer", "Runs a local server in run/server-java8", (p, a) -> {
            ServerInstall.run(p, Flavor.JAVA8);
            return 0;
        });
        task("runServer25", "Same, on Java 25 in run/server-java25", (p, a) -> {
            ServerInstall.run(p, Flavor.JAVA25);
            return 0;
        });
        for (Flavor flavor : Flavor.values()) {
            task("smokeServer" + flavor.taskSuffix, "CI test: starts and stops a " + flavor.label + " server",
                    (p, a) -> {
                        Smoke.server(p, flavor, timeout(a));
                        return 0;
                    });
            task("smokeClient" + flavor.taskSuffix, "CI test: opens a world in a " + flavor.label
                    + " client under Xvfb (Linux)", (p, a) -> {
                        Smoke.client(p, flavor, timeout(a));
                        return 0;
                    });
        }
        task("build", "Builds every release type modpack.ini lists, into build/", (p, a) -> {
            java.util.Set<ReleaseType> types = java.util.EnumSet.noneOf(ReleaseType.class);
            for (Destination d : Destination.values()) {
                types.addAll(p.config.releaseTypes(d));
            }
            if (types.isEmpty()) {
                throw new Fail("no release types in " + Config.SHARED_FILE + ". Use a build<Type> task instead.");
            }
            Builds.build(p, types);
            return 0;
        });
        for (ReleaseType type : ReleaseType.values()) {
            String name = "build" + Arrays.stream(type.id.split("-"))
                    .map(w -> Character.toUpperCase(w.charAt(0)) + w.substring(1)).reduce("", String::concat);
            task(name, "Builds " + type.id, (p, a) -> {
                Builds.build(p, type);
                return 0;
            });
        }
        task("publish", "Builds and releases a tag (CI): publish [tag]", (p, a) -> {
            String tag = !a.isEmpty() ? a.get(0)
                    : "tag".equals(System.getenv("GITHUB_REF_TYPE")) ? System.getenv("GITHUB_REF_NAME") : null;
            if (tag == null) {
                throw new Fail("publish needs a tag: modpackw publish v1.0.0");
            }
            Publish.run(p, tag);
            return 0;
        });
        task("checkIndex", "Fails if the packwiz index is out of date (CI)", (p, a) -> {
            Ci.checkIndex(p);
            return 0;
        });
        task("ciPlan", "Prints the smoke tests the release types call for (CI)", (p, a) -> {
            Ci.plan(p);
            return 0;
        });
        task("packwiz", "Runs packwiz in the pack folder: packwiz <args>", (p, a) -> Packwiz.run(p, a));
        task("updateModpackCI", "Moves this repo to the latest ModpackCI release", (p, a) -> {
            Updates.updateModpackCI(p);
            return 0;
        });
    }

    private Main() {}

    private static void task(String name, String help, Action action) {
        TASKS.put(name, new Task(help, action));
    }

    private static String version() {
        String v = Main.class.getPackage().getImplementationVersion();
        return v == null ? "dev" : v;
    }

    private static int timeout(List<String> args) {
        int i = args.indexOf("--timeout");
        return i >= 0 && i + 1 < args.size() ? Integer.parseInt(args.get(i + 1)) : 900;
    }

    public static void main(String[] args) {
        System.exit(run(args));
    }

    static int run(String[] args) {
        try {
            if (args.length == 0 || List.of("help", "--help", "-h").contains(args[0])) {
                help();
                return 0;
            }
            String name = args[0];
            List<String> rest = List.of(args).subList(1, args.length);
            switch (name) {
                case "version", "--version" -> {
                    Log.info("ModpackCI " + VERSION);
                    return 0;
                }
                case "starter" -> {
                    Templates.writeStarter(Path.of(rest.isEmpty() ? "starter" : rest.get(0)));
                    return 0;
                }
                case "migrate" -> {
                    int i = rest.indexOf("--from");
                    Updates.migrate(Project.current(), i >= 0 && i + 1 < rest.size() ? rest.get(i + 1) : "?");
                    return 0;
                }
                default -> { }
            }
            Task task = TASKS.get(name);
            if (task == null) {
                throw new Fail("unknown task " + name + ". Run modpackw help for the list.");
            }
            Project project = Project.current();
            if (!name.equals("packwiz")) {
                Updates.check(project);
            }
            return task.action.run(project, rest);
        } catch (Fail f) {
            System.err.println("error: " + f.getMessage());
            return 1;
        }
    }

    private static void help() {
        Log.info("ModpackCI " + VERSION + ", for Minecraft 1.7.10 packs built with packwiz.\n");
        Log.info("Usage: modpackw <task> [args]\n");
        int width = TASKS.keySet().stream().mapToInt(String::length).max().orElse(10);
        TASKS.forEach((name, task) -> Log.info("  " + name + " ".repeat(width - name.length() + 2) + task.help));
        Log.info("\nSettings: modpack.ini (committed) and modpack-local.ini (this machine only).");
    }
}
