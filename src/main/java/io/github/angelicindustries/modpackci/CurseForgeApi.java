package io.github.angelicindustries.modpackci;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** CurseForge's upload API, which takes the CurseForge modpack zip. */
public final class CurseForgeApi {
    private static final String BASE = "https://minecraft.curseforge.com/api";
    private final Map<String, String> auth;

    public CurseForgeApi(String token) {
        this.auth = Map.of("X-Api-Token", token);
    }

    public void upload(String projectId, String name, String version, String changelog, Path zip) {
        if (!projectId.matches("\\d+")) {
            throw new Fail("curseForgeProjectId must be the numeric project ID, not " + projectId);
        }
        JsonObject metadata = new JsonObject();
        metadata.addProperty("changelog", changelog);
        metadata.addProperty("changelogType", "markdown");
        metadata.addProperty("displayName", name + " " + version);
        metadata.addProperty("releaseType", Publish.channel(version));
        metadata.add("gameVersions", gameVersionIds());
        Http.Multipart body = new Http.Multipart()
                .field("metadata", "application/json", metadata.toString())
                .file("file", zip.getFileName().toString(), "application/zip", FileUtil.read(zip));
        Map<String, String> headers = new java.util.HashMap<>(auth);
        headers.put("Content-Type", body.contentType());
        Http.Response r = Http.send("POST", BASE + "/projects/" + projectId + "/upload-file", headers, body.body());
        if (!r.ok()) {
            throw new Fail("CurseForge rejected the upload (" + r.status() + "): " + r.excerpt());
        }
        Log.info("uploaded " + name + " " + version + " to CurseForge");
    }

    /** The IDs CurseForge uses for Minecraft 1.7.10 and Forge. */
    private JsonArray gameVersionIds() {
        Set<Long> minecraftTypes = new HashSet<>();
        Set<Long> loaderTypes = new HashSet<>();
        for (JsonElement t : list("/game/version-types")) {
            String slug = t.getAsJsonObject().get("slug").getAsString();
            long id = t.getAsJsonObject().get("id").getAsLong();
            if (slug.startsWith("minecraft")) {
                minecraftTypes.add(id);
            } else if (slug.equals("modloader")) {
                loaderTypes.add(id);
            }
        }
        JsonArray ids = new JsonArray();
        for (JsonElement v : list("/game/versions")) {
            JsonObject version = v.getAsJsonObject();
            long type = version.get("gameVersionTypeID").getAsLong();
            String name = version.get("name").getAsString();
            if ((minecraftTypes.contains(type) && name.equals("1.7.10"))
                    || (loaderTypes.contains(type) && name.equals("Forge"))) {
                ids.add(version.get("id").getAsLong());
            }
        }
        if (ids.isEmpty()) {
            throw new Fail("couldn't find CurseForge's IDs for Minecraft 1.7.10 and Forge");
        }
        return ids;
    }

    private JsonArray list(String path) {
        Http.Response r = Http.get(BASE + path, auth);
        if (r.status() == 401 || r.status() == 403) {
            throw new Fail("CurseForge didn't accept CURSEFORGE_TOKEN");
        }
        if (!r.ok()) {
            throw new Fail("CurseForge returned " + r.status() + " for " + path + ": " + r.excerpt());
        }
        return r.json().getAsJsonArray();
    }
}
