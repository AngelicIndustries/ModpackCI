package io.github.angelicindustries.modpackci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigTest {
    private static Config config(Map<String, String> shared, Map<String, String> local, Map<String, String> env) {
        return new Config(shared, local, env::get);
    }

    @Test
    void environmentBeatsLocalBeatsShared() {
        Config c = config(Map.of("modrinthProjectId", "shared"), Map.of("modrinthProjectId", "local"),
                Map.of("MODRINTH_PROJECT_ID", "env"));
        assertEquals("env", c.get(Config.Key.MODRINTH_PROJECT_ID));
        c = config(Map.of("modrinthProjectId", "shared"), Map.of("modrinthProjectId", "local"), Map.of());
        assertEquals("local", c.get(Config.Key.MODRINTH_PROJECT_ID));
    }

    @Test
    void emptyValuesCountAsUnset() {
        Config c = config(Map.of("modrinthProjectId", "shared"), Map.of("modrinthProjectId", ""),
                Map.of("MODRINTH_PROJECT_ID", " "));
        assertEquals("shared", c.get(Config.Key.MODRINTH_PROJECT_ID));
        assertNull(config(Map.of(), Map.of(), Map.of()).get(Config.Key.MODRINTH_PROJECT_ID));
    }

    @Test
    void releaseTypesKeepOrderAndDropDuplicates() {
        Config c = config(Map.of("sixSevenReleaseTypes", "mrpack-fat, mrpack-slim mrpack-fat"), Map.of(), Map.of());
        assertEquals(List.of(ReleaseType.MRPACK_FAT, ReleaseType.MRPACK_SLIM), c.releaseTypes(Destination.SIX_SEVEN));
    }

    @Test
    void releaseTypesRejectWhatADestinationCantTake() {
        Config c = config(Map.of("modrinthReleaseTypes", "prism-java8"), Map.of(), Map.of());
        Fail f = assertThrows(Fail.class, () -> c.releaseTypes(Destination.MODRINTH));
        assertTrue(f.getMessage().contains("Modrinth doesn't accept prism-java8"), f.getMessage());
        Config typo = config(Map.of("githubReleaseTypes", "mrpack"), Map.of(), Map.of());
        assertThrows(Fail.class, () -> typo.releaseTypes(Destination.GITHUB));
    }

    @Test
    void releaseNeedsProjectIdAndToken() {
        Config noToken = config(Map.of("modrinthProjectId", "abc", "modrinthReleaseTypes", "mrpack-fat"), Map.of(), Map.of());
        Fail f = assertThrows(Fail.class, () -> Publish.plan(noToken, false, true));
        assertEquals("modrinthReleaseTypes is set but MODRINTH_TOKEN is missing. Add it as a repository secret, "
                + "or empty modrinthReleaseTypes.", f.getMessage());
        Config noId = config(Map.of("modrinthReleaseTypes", "mrpack-fat"), Map.of(), Map.of("MODRINTH_TOKEN", "t"));
        f = assertThrows(Fail.class, () -> Publish.plan(noId, false, true));
        assertTrue(f.getMessage().startsWith("modrinthReleaseTypes is set but modrinthProjectId isn't"), f.getMessage());
    }

    @Test
    void snapshotsOnlyGoToGitHub() {
        Config c = config(Map.of("githubReleaseTypes", "mrpack-slim", "modrinthReleaseTypes", "mrpack-fat"),
                Map.of(), Map.of());
        assertEquals(List.of(Destination.GITHUB), List.copyOf(Publish.plan(c, true, true).keySet()));
    }
}
