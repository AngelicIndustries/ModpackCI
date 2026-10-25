package io.github.angelicindustries.modpackci;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A Prism-format instance (also used by Fjord Launcher, PolyMC and the like), synced from the pack. */
public final class ClientInstance {
    static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String STATE = ".modpackci-instance";
    private static final String META = "https://meta.prismlauncher.org/v1/";

    private ClientInstance() {}

    /**
     * @param java the Java to pin in instance.cfg, or null to let the launcher pick a compatible one
     * @param localMeta write every component into patches/ so an offline launch needs no metadata
     *     downloads (the CI smoke test launches offline)
     */
    public static void setup(Project project, Path inst, Flavor flavor, String name, Path java, boolean localMeta) {
        Pack pack = project.pack();
        String state = flavor.id + " " + (flavor == Flavor.JAVA25 ? Lwjgl3ify.version(pack) : pack.forge)
                + (localMeta ? " local-meta" : "");
        Path stateFile = inst.resolve(STATE);
        if (!Files.exists(stateFile) || !FileUtil.readString(stateFile).equals(state)) {
            FileUtil.deleteTree(inst.resolve("patches"));
            FileUtil.deleteTree(inst.resolve("libraries"));
            if (flavor == Flavor.JAVA25) {
                Lwjgl3ify.requireForge(pack);
                FileUtil.unzip(Lwjgl3ify.asset(project, Lwjgl3ify.version(pack), "-multimc.zip"), inst);
            } else {
                writeJava8Components(inst, pack.forge, localMeta);
            }
            FileUtil.write(stateFile, state);
        }
        Map<String, String> cfg = new LinkedHashMap<>();
        cfg.put("InstanceType", "OneSix");
        cfg.put("name", name);
        if (java != null) {
            cfg.put("OverrideJavaLocation", "true");
            cfg.put("JavaPath", java.toString());
        }
        setKeys(inst.resolve("instance.cfg"), cfg);
        Path game = inst.resolve(".minecraft");
        Installer.sync(project, "client", game, inst);
        Lwjgl3ify.adjustMods(project, flavor, game.resolve("mods"));
    }

    private static void writeJava8Components(Path inst, String forge, boolean localMeta) {
        JsonArray components = new JsonArray();
        components.add(component("net.minecraft", "1.7.10", true));
        if (localMeta) {
            JsonObject minecraft = Http.fetchJson(META + "net.minecraft/1.7.10.json").getAsJsonObject();
            String lwjgl = minecraft.getAsJsonArray("requires").get(0).getAsJsonObject().get("suggests").getAsString();
            FileUtil.write(inst.resolve("patches/net.minecraft.json"), GSON.toJson(minecraft));
            FileUtil.write(inst.resolve("patches/org.lwjgl.json"),
                    GSON.toJson(Http.fetchJson(META + "org.lwjgl/" + lwjgl + ".json")));
            FileUtil.write(inst.resolve("patches/net.minecraftforge.json"),
                    GSON.toJson(Http.fetchJson(META + "net.minecraftforge/" + forge + ".json")));
            JsonObject lwjglComponent = component("org.lwjgl", lwjgl, false);
            lwjglComponent.addProperty("dependencyOnly", true);
            components.add(lwjglComponent);
        }
        components.add(component("net.minecraftforge", forge, false));
        JsonObject pack = new JsonObject();
        pack.add("components", components);
        pack.addProperty("formatVersion", 1);
        FileUtil.write(inst.resolve("mmc-pack.json"), GSON.toJson(pack));
    }

    private static JsonObject component(String uid, String version, boolean important) {
        JsonObject c = new JsonObject();
        c.addProperty("uid", uid);
        c.addProperty("version", version);
        if (important) {
            c.addProperty("important", true);
        }
        return c;
    }

    /** Sets key=value lines in an INI-style file, keeping every other line as it is. */
    public static void setKeys(Path file, Map<String, String> keys) {
        List<String> lines = new java.util.ArrayList<>(Files.exists(file)
                ? List.of(FileUtil.readString(file).split("\\R"))
                : List.of("[General]"));
        keys.forEach((key, value) -> {
            String line = key + "=" + value;
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).split("=", 2)[0].equals(key)) {
                    lines.set(i, line);
                    return;
                }
            }
            lines.add(line);
        });
        FileUtil.write(file, String.join("\n", lines) + "\n");
    }

    /** The files of an instance that belong in a distributable zip. */
    public static boolean distributable(String relative) {
        return !relative.equals(STATE)
                && !relative.equals(".minecraft/packwiz.json")
                && !relative.endsWith("/.modpackci-added");
    }
}
