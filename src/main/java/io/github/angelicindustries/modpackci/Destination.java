package io.github.angelicindustries.modpackci;

import java.util.EnumSet;
import java.util.Set;

/** Where releases can go, with the modpack.ini keys that configure each. */
public enum Destination {
    GITHUB("GitHub", Config.Key.GITHUB_RELEASE_TYPES, null, null, EnumSet.allOf(ReleaseType.class)),
    SIX_SEVEN("67minecraft", Config.Key.SIX_SEVEN_RELEASE_TYPES, Config.Key.SIX_SEVEN_PROJECT_ID,
            Config.Key.SIX_SEVEN_TOKEN, EnumSet.of(ReleaseType.MRPACK_SLIM, ReleaseType.MRPACK_FAT,
                    ReleaseType.PRISM_JAVA8, ReleaseType.PRISM_JAVA17)),
    MODRINTH("Modrinth", Config.Key.MODRINTH_RELEASE_TYPES, Config.Key.MODRINTH_PROJECT_ID,
            Config.Key.MODRINTH_TOKEN, EnumSet.of(ReleaseType.MRPACK_SLIM, ReleaseType.MRPACK_FAT)),
    CURSEFORGE("CurseForge", Config.Key.CURSEFORGE_RELEASE_TYPES, Config.Key.CURSEFORGE_PROJECT_ID,
            Config.Key.CURSEFORGE_TOKEN, EnumSet.of(ReleaseType.CURSEFORGE_ZIP));

    public final String displayName;
    public final Config.Key typesKey;
    public final Config.Key projectKey;
    public final Config.Key tokenKey;
    public final Set<ReleaseType> accepts;

    Destination(String displayName, Config.Key typesKey, Config.Key projectKey, Config.Key tokenKey,
            Set<ReleaseType> accepts) {
        this.displayName = displayName;
        this.typesKey = typesKey;
        this.projectKey = projectKey;
        this.tokenKey = tokenKey;
        this.accepts = accepts;
    }
}
