package com.example.probe.web;

import com.example.probe.Probe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * paper-embedded-http (Web plugin, its own jar; config.yml enables it on 127.0.0.1:25600 with 5 requests/minute):
 * real HTTP requests from a pool thread (never the main thread), then the port is free once Web is disabled.
 */
public final class WebProbe extends Probe {

    private static final int PORT = 25600;
    private static final String BASE = "http://127.0.0.1:" + PORT;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    /** What the pool thread saw; checked on the main thread. */
    private record Seen(HttpResponse<byte[]> players, HttpResponse<byte[]> notModified, HttpResponse<byte[]> post,
                        HttpResponse<byte[]> unknown, HttpResponse<byte[]> page, List<Integer> burst) {
    }

    @Override
    protected void start() {
        Plugin web = getServer().getPluginManager().getPlugin("Web");
        check("Web enabled", web != null && web.isEnabled());
        CompletableFuture.supplyAsync(this::requests).whenComplete((seen, error) ->
                getServer().getScheduler().runTask(this, () -> guard(() -> {
                    if (error != null) {
                        throw new IllegalStateException("HTTP requests failed", error);
                    }
                    verify(seen);
                    getServer().getPluginManager().disablePlugin(web);
                    CompletableFuture.supplyAsync(WebProbe::portFree).thenAccept(free ->
                            getServer().getScheduler().runTask(this, () -> guard(() -> {
                                check("after disabling Web: port " + PORT + " released", free);
                                finish();
                            })));
                })));
    }

    private Seen requests() {
        try {
            HttpResponse<byte[]> players = get("/api/players", null);
            String etag = players.headers().firstValue("ETag").orElse("");
            HttpResponse<byte[]> notModified = get("/api/players", etag);
            HttpResponse<byte[]> post = send(HttpRequest.newBuilder(URI.create(BASE + "/api/players"))
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build());
            HttpResponse<byte[]> unknown = get("/api/players/extra", null);
            HttpResponse<byte[]> page = get("/", null);
            List<Integer> burst = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                burst.add(get("/api/players", null).statusCode());
            }
            return new Seen(players, notModified, post, unknown, page, burst);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private void verify(Seen seen) {
        HttpResponse<byte[]> players = seen.players();
        check("GET /api/players -> 200", players.statusCode() == 200);
        check("JSON content type", players.headers().firstValue("Content-Type").orElse("").startsWith("application/json"));
        check("nosniff header", "nosniff".equals(players.headers().firstValue("X-Content-Type-Options").orElse("")));
        JsonObject body = JsonParser.parseString(new String(players.body(), StandardCharsets.UTF_8)).getAsJsonObject();
        check("snapshot built (online = 0, players = [])", body.get("online").getAsInt() == 0 && body.getAsJsonArray("players").isEmpty());
        check("If-None-Match with the ETag -> 304", seen.notModified().statusCode() == 304);
        check("POST -> 405 with Allow: GET, HEAD", seen.post().statusCode() == 405
                && "GET, HEAD".equals(seen.post().headers().firstValue("Allow").orElse("")));
        check("unknown sub-path -> 404", seen.unknown().statusCode() == 404);
        byte[] page = seen.page().body();
        check("GET / serves index.html with the BOM stripped", seen.page().statusCode() == 200 && page.length > 0 && page[0] == '<');
        // 5 tokens: 200, 304, 404, page, then the burst; 405 is refused before the limiter
        log("burst statuses: " + seen.burst());
        check("rate limit: 6th request -> 429", seen.burst().contains(429) && seen.burst().indexOf(429) == 1);
    }

    private HttpResponse<byte[]> get(String path, String ifNoneMatch) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(BASE + path)).GET();
        if (ifNoneMatch != null) {
            request.header("If-None-Match", ifNoneMatch);
        }
        return send(request.build());
    }

    private HttpResponse<byte[]> send(HttpRequest request) throws IOException, InterruptedException {
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private static boolean portFree() {
        try (ServerSocket socket = new ServerSocket(PORT, 1, InetAddress.getLoopbackAddress())) {
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
