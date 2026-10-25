package io.github.angelicindustries.modpackci;

import com.google.gson.JsonObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

/** GitHub releases, through the REST API with the token GitHub Actions provides. */
public final class GitHubApi {
    private final String repo;
    private final Map<String, String> headers;

    public GitHubApi(String repo, String token) {
        this.repo = repo;
        this.headers = Map.of("Authorization", "Bearer " + token, "Accept", "application/vnd.github+json",
                "X-GitHub-Api-Version", "2022-11-28");
    }

    public boolean releaseExists(String tag) {
        Http.Response r = Http.get(api("/releases/tags/" + enc(tag)), headers);
        if (r.status() == 404) {
            return false;
        }
        require(r, "looking up release " + tag);
        return true;
    }

    public String generateNotes(String tag) {
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        Http.Response r = post(api("/releases/generate-notes"), "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
        require(r, "generating release notes");
        return r.json().getAsJsonObject().get("body").getAsString();
    }

    public void createRelease(String tag, String name, String notes, boolean prerelease, Iterable<Path> assets) {
        JsonObject body = new JsonObject();
        body.addProperty("tag_name", tag);
        body.addProperty("name", name);
        body.addProperty("body", notes);
        body.addProperty("prerelease", prerelease);
        Http.Response r = post(api("/releases"), "application/json", body.toString().getBytes(StandardCharsets.UTF_8));
        require(r, "creating release " + tag);
        long id = r.json().getAsJsonObject().get("id").getAsLong();
        for (Path asset : assets) {
            String url = "https://uploads.github.com/repos/" + repo + "/releases/" + id + "/assets?name="
                    + enc(asset.getFileName().toString());
            require(post(url, "application/octet-stream", FileUtil.read(asset)), "uploading " + asset.getFileName());
            Log.info("attached " + asset.getFileName());
        }
    }

    private Http.Response post(String url, String contentType, byte[] body) {
        Map<String, String> h = new java.util.HashMap<>(headers);
        h.put("Content-Type", contentType);
        return Http.send("POST", url, h, body);
    }

    private String api(String path) {
        return "https://api.github.com/repos/" + repo + path;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private static void require(Http.Response r, String what) {
        if (!r.ok()) {
            throw new Fail("GitHub returned " + r.status() + " " + what + ": " + r.excerpt());
        }
    }
}
