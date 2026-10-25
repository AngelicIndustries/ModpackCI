package io.github.angelicindustries.modpackci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
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
                Prefetch.mavenPath("org.lwjgl.lwjgl:lwjgl-platform:2.9.4:natives-linux"));
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
}
