package io.github.angelicindustries.modpackci;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** Modrinth's API, also used by 67minecraft.com, which runs its own copy of it. */
public final class LabrinthApi {
    /** MODPACKCI_MODRINTH_API can point at Modrinth's staging API. */
    public static final String MODRINTH = env("MODPACKCI_MODRINTH_API", "https://api.modrinth.com/v2");
    public static final String SIX_SEVEN = env("MODPACKCI_SIX_SEVEN_API", "https://67minecraft.com/_labrinth/v2");

    private final String site;
    private final String base;
    private final Map<String, String> auth;

    public LabrinthApi(String site, String base, String token) {
        this.site = site;
        this.base = base;
        this.auth = Map.of("Authorization", token);
    }

    /**
     * Uploads the files as one version, the first one as its primary file. Does nothing if the
     * project already has this version number, so rerunning a release job is safe.
     */
    public void publish(String projectRef, String name, String version, String changelog,
            List<Map.Entry<ReleaseType, Path>> files) {
        JsonObject project = get("/project/" + projectRef).getAsJsonObject();
        String projectId = project.get("id").getAsString();
        for (JsonElement v : get("/project/" + projectId + "/version").getAsJsonArray()) {
            if (v.getAsJsonObject().get("version_number").getAsString().equals(version)) {
                Log.notice(site + " already has " + name + " " + version + ", nothing uploaded");
                return;
            }
        }
        JsonArray parts = new JsonArray();
        for (int i = 0; i < files.size(); i++) {
            parts.add("file" + i);
        }
        JsonObject data = new JsonObject();
        data.addProperty("project_id", projectId);
        data.addProperty("name", name + " " + version);
        data.addProperty("version_number", version);
        data.addProperty("changelog", changelog);
        data.add("dependencies", new JsonArray());
        JsonArray gameVersions = new JsonArray();
        gameVersions.add("1.7.10");
        data.add("game_versions", gameVersions);
        data.addProperty("version_type", Publish.channel(version));
        JsonArray loaders = new JsonArray();
        loaders.add("forge");
        data.add("loaders", loaders);
        data.addProperty("featured", false);
        data.addProperty("status", "listed");
        data.add("file_parts", parts);
        data.addProperty("primary_file", "file0");
        // Labrinth wants the data part first.
        Http.Multipart body = new Http.Multipart().field("data", "application/json", data.toString());
        for (int i = 0; i < files.size(); i++) {
            Path file = files.get(i).getValue();
            body.file("file" + i, file.getFileName().toString(), files.get(i).getKey().contentType, FileUtil.read(file));
        }
        Map<String, String> headers = new java.util.HashMap<>(auth);
        headers.put("Content-Type", body.contentType());
        Http.Response r = Http.send("POST", base + "/version", headers, body.body());
        if (!r.ok()) {
            throw new Fail(site + " rejected the upload (" + r.status() + "): " + r.excerpt());
        }
        Log.info("uploaded " + name + " " + version + " to " + site);
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value;
    }

    private JsonElement get(String path) {
        Http.Response r = Http.get(base + path, auth);
        if (r.status() == 401) {
            throw new Fail(site + " didn't accept the token");
        }
        if (r.status() == 404) {
            throw new Fail(site + " has no " + path + " (check the project ID, and that the token can see it)");
        }
        if (!r.ok()) {
            throw new Fail(site + " returned " + r.status() + " for " + path + ": " + r.excerpt());
        }
        return r.json();
    }
}
