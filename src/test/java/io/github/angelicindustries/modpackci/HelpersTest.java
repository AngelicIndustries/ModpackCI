package io.github.angelicindustries.modpackci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HelpersTest {
    @Test
    void versionsCompareNumerically() {
        assertTrue(Updates.compare("1.10.0", "1.9.3") > 0);
        assertEquals(0, Updates.compare("1.2", "1.2.0"));
        assertTrue(Updates.compare("1.2.0", "1.2.1") < 0);
    }

    @Test
    void mavenPathsIncludeTheClassifier() {
        assertEquals("org/lwjgl/lwjgl/lwjgl-platform/2.9.4/lwjgl-platform-2.9.4-natives-linux.jar",
                Components.mavenPath("org.lwjgl.lwjgl:lwjgl-platform:2.9.4:natives-linux"));
    }

    @Test
    void curseforgeLinksMatchTheirCdn() {
        Pack.Meta meta = new Pack.Meta(Path.of("x"), "mods/A B.jar", "A", "A B.jar", "both", "metadata:curseforge",
                null, 2231443);
        assertEquals("https://edge.forgecdn.net/files/2231/443/A%20B.jar", Builds.curseforgeUrl(meta));
    }

    @Test
    void releaseChannelFollowsTheVersion() {
        assertEquals("beta", Publish.channel("1.0.0-beta.2"));
        assertEquals("release", Publish.channel("1.0.0"));
    }

    @Test
    void modernJavaModsBecomeOptionalOnTheSidesTheyRunOn() {
        JsonArray files = JsonParser.parseString("""
                [{"path": "mods/dh.jar", "env": {"client": "required", "server": "unsupported"}},
                 {"path": "mods/both.jar"},
                 {"path": "mods/other.jar", "env": {"client": "required", "server": "required"}}]
                """).getAsJsonArray();
        Builds.markOptional(Set.of("mods/dh.jar", "mods/both.jar"), files);
        JsonObject dh = files.get(0).getAsJsonObject().getAsJsonObject("env");
        assertEquals("optional", dh.get("client").getAsString());
        assertEquals("unsupported", dh.get("server").getAsString());
        JsonObject both = files.get(1).getAsJsonObject().getAsJsonObject("env");
        assertEquals("optional", both.get("client").getAsString());
        assertEquals("optional", both.get("server").getAsString());
        assertEquals("required", files.get(2).getAsJsonObject().getAsJsonObject("env").get("client").getAsString());
    }
}
