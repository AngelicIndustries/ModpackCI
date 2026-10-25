package io.github.angelicindustries.modpackci;

import java.nio.file.Path;
import java.util.Locale;

/** The OS and CPU, named the way foojay and the ModpackCI release assets name them. */
public enum Platform {
    LINUX("linux"),
    MACOS("macos"),
    WINDOWS("windows");

    public final String id;

    Platform(String id) {
        this.id = id;
    }

    public static Platform current() {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        if (os.contains("win")) {
            return WINDOWS;
        }
        if (os.contains("mac")) {
            return MACOS;
        }
        if (os.contains("linux")) {
            return LINUX;
        }
        throw new Fail("unsupported OS: " + System.getProperty("os.name"));
    }

    public static String arch() {
        String arch = System.getProperty("os.arch").toLowerCase(Locale.ROOT);
        return switch (arch) {
            case "amd64", "x86_64" -> "x64";
            case "aarch64", "arm64" -> "aarch64";
            default -> throw new Fail("unsupported CPU architecture: " + arch);
        };
    }

    public String exe(String name) {
        return this == WINDOWS ? name + ".exe" : name;
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home"));
    }
}
