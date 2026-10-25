package io.github.angelicindustries.modpackci;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;

public final class Http {
    public static final String USER_AGENT =
            "ModpackCI/" + Main.VERSION + " (+https://github.com/AngelicIndustries/ModpackCI)";
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(30))
            .build();

    private Http() {}

    public record Response(int status, byte[] body, HttpResponse<byte[]> raw) {
        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }

        public JsonElement json() {
            return JsonParser.parseString(text());
        }

        public boolean ok() {
            return status / 100 == 2;
        }

        /** The body cut down to something that fits in an error message. */
        public String excerpt() {
            String t = text().strip();
            return t.length() > 500 ? t.substring(0, 500) + "..." : t;
        }
    }

    public static Response send(String method, String url, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("User-Agent", USER_AGENT)
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(req::header);
        try {
            HttpResponse<byte[]> resp = CLIENT.send(req.build(), HttpResponse.BodyHandlers.ofByteArray());
            return new Response(resp.statusCode(), resp.body(), resp);
        } catch (IOException e) {
            throw new Fail("couldn't reach " + url + ": " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new Fail("interrupted while talking to " + url, e);
        }
    }

    public static Response get(String url, Map<String, String> headers) {
        return send("GET", url, headers, null);
    }

    /** GET that fails unless the server answers 2xx. */
    public static byte[] fetch(String url) {
        Response r = get(url, Map.of());
        if (!r.ok()) {
            throw new Fail("GET " + url + " returned " + r.status());
        }
        return r.body();
    }

    public static JsonElement fetchJson(String url) {
        return JsonParser.parseString(new String(fetch(url), StandardCharsets.UTF_8));
    }

    /** The Content-Length a HEAD request reports, or -1 if the server doesn't say. */
    public static long contentLength(String url) {
        Response r = send("HEAD", url, Map.of(), null);
        if (!r.ok()) {
            throw new Fail("HEAD " + url + " returned " + r.status());
        }
        return r.raw().headers().firstValueAsLong("Content-Length").orElse(-1);
    }

    /**
     * Downloads to {@code target}, checking the hash when one is given. Skips the download if the
     * file is already there with the right hash, or already there at all when no hash is given.
     */
    public static Path download(String url, Path target, String algorithm, String expected) {
        if (Files.exists(target)
                && (expected == null || FileUtil.hash(algorithm, target).equalsIgnoreCase(expected))) {
            return target;
        }
        byte[] data = fetch(url);
        if (expected != null && !FileUtil.hash(algorithm, data).equalsIgnoreCase(expected)) {
            throw new Fail(url + " doesn't match its " + algorithm + " hash");
        }
        Path part = target.resolveSibling(target.getFileName() + ".part");
        FileUtil.write(part, data);
        try {
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new Fail("couldn't write " + target + ": " + e.getMessage(), e);
        }
        return target;
    }

    public static Path download(String url, Path target) {
        return download(url, target, null, null);
    }

    /** A multipart/form-data body, with parts in the order they're added. */
    public static final class Multipart {
        private final String boundary = UUID.randomUUID().toString().replace("-", "");
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();

        public Multipart field(String name, String contentType, String value) {
            return part(name, null, contentType, value.getBytes(StandardCharsets.UTF_8));
        }

        public Multipart file(String name, String fileName, String contentType, byte[] data) {
            return part(name, fileName, contentType, data);
        }

        private Multipart part(String name, String fileName, String contentType, byte[] data) {
            StringBuilder head = new StringBuilder("--").append(boundary)
                    .append("\r\nContent-Disposition: form-data; name=\"").append(name).append('"');
            if (fileName != null) {
                head.append("; filename=\"").append(fileName).append('"');
            }
            head.append("\r\nContent-Type: ").append(contentType).append("\r\n\r\n");
            out.writeBytes(head.toString().getBytes(StandardCharsets.UTF_8));
            out.writeBytes(data);
            out.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
            return this;
        }

        public String contentType() {
            return "multipart/form-data; boundary=" + boundary;
        }

        public byte[] body() {
            ByteArrayOutputStream copy = new ByteArrayOutputStream();
            copy.writeBytes(out.toByteArray());
            copy.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
            return copy.toByteArray();
        }
    }
}
