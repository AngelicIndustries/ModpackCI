package io.github.angelicindustries.modpackci;

import java.util.Arrays;
import java.util.stream.Collectors;

/** The files ModpackCI can build, named as they're written in modpack.ini. */
public enum ReleaseType {
    MRPACK_SLIM("mrpack-slim", "-slim.mrpack", "application/x-modrinth-modpack+zip"),
    MRPACK_FAT("mrpack-fat", "-fat.mrpack", "application/x-modrinth-modpack+zip"),
    PRISM_JAVA8("prism-java8", "-prism-java8.zip", "application/zip"),
    PRISM_JAVA17("prism-java17", "-prism-java17.zip", "application/zip"),
    CURSEFORGE_ZIP("curseforge-zip", "-curseforge.zip", "application/zip");

    public final String id;
    public final String suffix;
    public final String contentType;

    ReleaseType(String id, String suffix, String contentType) {
        this.id = id;
        this.suffix = suffix;
        this.contentType = contentType;
    }

    public boolean isMrpack() {
        return this == MRPACK_SLIM || this == MRPACK_FAT;
    }

    public static ReleaseType parse(String id) {
        for (ReleaseType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }

    public static String known() {
        return Arrays.stream(values()).map(t -> t.id).collect(Collectors.joining(", "));
    }
}
