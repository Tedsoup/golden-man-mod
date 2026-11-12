package goldenman;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The public list of Golden Man players (players.json, published by the GitHub checker).
 * Downloaded every few minutes; the mod never uploads anything here.
 */
public final class Verified {
    public enum Status { VERIFIED, PENDING, FAILED }

    /** One player on the list. */
    public record Entry(String name, Status status, List<String> reasons, boolean dev) {}

    private static final long REFRESH_MS = 5 * 60 * 1000;
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    private final Config config;
    private final Map<String, Entry> byName = new ConcurrentHashMap<>();
    private final Map<String, Entry> byUuid = new ConcurrentHashMap<>();
    private volatile long lastFetch;
    private volatile boolean fetching;
    private volatile boolean loadedOnce;

    public Verified(Config config) {
        this.config = config;
    }

    public Map<String, Entry> all() {
        return byName;
    }

    public boolean loaded() {
        return loadedOnce;
    }

    /** Your own entry, or null if you aren't on the list (yet). */
    public Entry self() {
        String uuid = Minecraft.getInstance().getUser().getProfileId().toString().replace("-", "");
        return byUuid.get(uuid);
    }

    /** Check again soon (e.g. right after registering). */
    public void refreshSoon() {
        lastFetch = System.currentTimeMillis() - REFRESH_MS + 75_000;
    }

    public void tick() {
        long now = System.currentTimeMillis();
        if (fetching || now - lastFetch < REFRESH_MS) return;
        String url = config.listUrl();
        if (url.isBlank()) return;
        fetching = true;
        lastFetch = now;
        HttpRequest req = HttpRequest.newBuilder(URI.create(url + "?t=" + now)).timeout(Duration.ofSeconds(15)).GET().build();
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
            Map<String, Entry> names = new ConcurrentHashMap<>();
            Map<String, Entry> uuids = new ConcurrentHashMap<>();
            if (players != null) {
                for (Map.Entry<String, JsonElement> e : players.entrySet()) {
                    JsonObject p = e.getValue().getAsJsonObject();
                    if (!p.has("name") || p.get("name").isJsonNull()) continue;
                    String status = p.has("status") ? p.get("status").getAsString() : "pending";
                    Status s = switch (status.toLowerCase(Locale.ROOT)) {
                        case "verified" -> Status.VERIFIED;
                        case "failed" -> Status.FAILED;
                        default -> Status.PENDING;
                    };
                    boolean dev = p.has("dev") && p.get("dev").getAsBoolean();
                    List<String> reasons = new ArrayList<>();
                    JsonArray arr = p.has(dev ? "notes" : "reasons") && p.get(dev ? "notes" : "reasons").isJsonArray()
                            ? p.getAsJsonArray(dev ? "notes" : "reasons") : null;
                    if (arr != null) for (JsonElement r : arr) reasons.add(r.getAsString());
                    Entry entry = new Entry(p.get("name").getAsString(), s, reasons, dev);
                    names.put(entry.name().toLowerCase(Locale.ROOT), entry);
                    uuids.put(e.getKey().toLowerCase(Locale.ROOT), entry);
                }
            }
            Entry before = self();
            byName.clear();
            byName.putAll(names);
            byUuid.clear();
            byUuid.putAll(uuids);
            if (loadedOnce) notifyOwnChange(before, self());
            loadedOnce = true;
        } catch (Exception ex) {
            GoldenMan.LOGGER.warn("Golden Man list is malformed: {}", ex.toString());
        }
    }

    /** Tells you in chat when your own verification status changes. */
    private void notifyOwnChange(Entry before, Entry after) {
        if (after == null || (before != null && before.status() == after.status())) return;
        String text = switch (after.status()) {
            case VERIFIED -> "You are now a VERIFIED Golden Man!";
            case PENDING -> "Golden Man verification pending: " + String.join("; ", after.reasons());
            case FAILED -> "You FAILED Golden Man verification: " + String.join("; ", after.reasons());
        };
        ChatFormatting color = switch (after.status()) {
            case VERIFIED -> ChatFormatting.GOLD;
            case PENDING -> ChatFormatting.YELLOW;
            case FAILED -> ChatFormatting.DARK_RED;
        };
        Minecraft.getInstance().execute(() -> GoldenMan.tell(Component.literal(text).withStyle(color)));
    }
}
