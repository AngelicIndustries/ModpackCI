package io.github.angelicindustries.modpackci;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComponentsTest {
    private static Components.Component component(String uid, String json) {
        return new Components.Component(uid, JsonParser.parseString(json).getAsJsonObject(), Path.of("local"));
    }

    @Test
    void laterComponentsReplaceLibrariesInPlaceAndAddArguments() {
        Components.Component minecraft = component("net.minecraft", """
                {"mainClass": "net.minecraft.client.main.Main", "minecraftArguments": "--username ${auth_player_name}",
                 "assetIndex": {"id": "1.7.10"},
                 "mainJar": {"name": "com.mojang:minecraft:1.7.10:client"},
                 "libraries": [{"name": "com.google.guava:guava:15.0"}, {"name": "io.netty:netty-all:4.0.10"}]}
                """);
        Components.Component forge = component("net.minecraftforge", """
                {"mainClass": "net.minecraft.launchwrapper.Launch", "+tweakers": ["cpw.mods.fml.common.launcher.FMLTweaker"],
                 "+jvmArgs": ["-Dx=y"],
                 "libraries": [{"name": "net.minecraftforge:forge:1.7.10:universal", "url": "https://maven.example/"},
                               {"name": "com.google.guava:guava:17.0"}]}
                """);
        Components.Launch launch = Components.merge(List.of(minecraft, forge), Path.of("libs"));
        assertEquals("net.minecraft.launchwrapper.Launch", launch.mainClass());
        assertEquals(List.of("cpw.mods.fml.common.launcher.FMLTweaker"), launch.tweakers());
        assertEquals(List.of("-Dx=y"), launch.jvmArgs());
        List<String> jars = launch.libraries().stream().map(l -> l.path().getFileName().toString()).toList();
        assertEquals(List.of("guava-17.0.jar", "netty-all-4.0.10.jar", "forge-1.7.10-universal.jar",
                "minecraft-1.7.10-client.jar"), jars);
        assertEquals("https://maven.example/net/minecraftforge/forge/1.7.10/forge-1.7.10-universal.jar",
                launch.libraries().get(2).url());
    }

    @Test
    void localLibrariesComeFromTheComponentFolder() {
        Components.Component c = component("x", """
                {"mainClass": "m", "minecraftArguments": "", "assetIndex": {"id": "1"},
                 "mainJar": {"name": "a:b:1"},
                 "libraries": [{"name": "com.github.GTNewHorizons:lwjgl3ify:3.0.39:forgePatches", "MMC-hint": "local"}]}
                """);
        Components.Library lib = Components.merge(List.of(c), Path.of("libs")).libraries().get(0);
        assertEquals(Path.of("local", "lwjgl3ify-3.0.39-forgePatches.jar"), lib.path());
    }

}
