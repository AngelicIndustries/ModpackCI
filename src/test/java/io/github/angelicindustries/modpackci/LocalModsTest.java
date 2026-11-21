package io.github.angelicindustries.modpackci;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalModsTest {
    @TempDir
    Path project;

    private void write(String path, String text) throws Exception {
        Path file = project.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000));
    }

    @Test
    void fingerprintFollowsTheSources() throws Exception {
        write("src/main/java/a/A.java", "class A {}");
        write("build.gradle.kts", "plugins {}");
        String before = LocalMods.fingerprint(project);
        assertEquals(before, LocalMods.fingerprint(project));

        write("src/main/java/a/A.java", "class A { int x; }");
        assertNotEquals(before, LocalMods.fingerprint(project));
    }

    @Test
    void fingerprintIgnoresOutputsAndDotFolders() throws Exception {
        write("src/main/java/a/A.java", "class A {}");
        String before = LocalMods.fingerprint(project);

        write("build/libs/a.jar", "jar");
        write("run/client/options.txt", "x");
        write(".git/HEAD", "ref");
        write(".gradle/cache", "x");
        assertEquals(before, LocalMods.fingerprint(project));
    }

    @Test
    void sourcePackagesNamedLikeOutputsStillCount() throws Exception {
        write("src/main/java/a/A.java", "class A {}");
        String before = LocalMods.fingerprint(project);

        write("src/main/java/a/build/B.java", "class B {}");
        assertNotEquals(before, LocalMods.fingerprint(project));
    }

    @Test
    void buildModesFollowTheFlags() {
        assertEquals(LocalMods.Build.CHANGED, LocalMods.Build.of(List.of()));
        assertEquals(LocalMods.Build.ALL, LocalMods.Build.of(List.of("--rebuild")));
        assertEquals(LocalMods.Build.NONE, LocalMods.Build.of(List.of("--no-build")));
    }
}
