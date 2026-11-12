package goldenman;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The public list of Golden Man players, published by the GitHub checker as players.json.
 * Downloaded every few minutes; nothing is ever sent from the mod.
 */
public final class Verified {
    public enum Status { VERIFIED, PENDING, FAILED }

    private static final long REFRESH_MS = 5 * 60 * 1000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Config config;
    private final Map<String, Status> byName = new ConcurrentHashMap<>();
    private volatile long lastFetch;
    private volatile boolean fetching;

    public Verified(Config config) {
        this.config = config;
    }

    public Status statusOf(String name) {
        return name == null ? null : byName.get(name.toLowerCase(Locale.ROOT));
    }

    public Map<String, Status> all() {
        return byName;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < REFRESH_MS) return;
        String url = config.listUrl();
        if (url.isBlank()) return;
        fetching = true;
        lastFetch = now;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET().build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString())
                .thenAccept(res -> {
                    if (res.statusCode() == 200) parse(res.body());
                    else GoldenMan.LOGGER.warn("Golden Man list returned HTTP {}", res.statusCode());
                })
                .exceptionally(e -> {
                    GoldenMan.LOGGER.warn("Couldn't download the Golden Man list: {}", e.toString());
                    return null;
                })
                .whenComplete((v, e) -> fetching = false);
    }

    void parse(String body) {
        try {
            JsonObject root = JsonParser.parseString(body).getAsJsonObject();
            JsonObject players = root.getAsJsonObject("players");
            Map<String, Status> fresh = new ConcurrentHashMap<>();
            if (players != null) {
                for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    if (!p.has("name")) continue;
                    String status = p.has("status") ? p.get("status").getAsString() : "pending";
                    Status s = switch (status.toLowerCase(Locale.ROOT)) {
                        case "verified" -> Status.VERIFIED;
                        case "failed" -> Status.FAILED;
                        default -> Status.PENDING;
                    };
                    fresh.put(p.get("name").getAsString().toLowerCase(Locale.ROOT), s);
                }
            }
            byName.clear();
            byName.putAll(fresh);
        } catch (Exception ex) {
            GoldenMan.LOGGER.warn("Golden Man list is malformed: {}", ex.toString());
        }
    }
}
