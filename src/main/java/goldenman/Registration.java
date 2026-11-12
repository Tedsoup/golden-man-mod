package goldenman;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.minecraft.network.chat.Component;

import java.math.BigInteger;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * One-click registration, no browser needed.
 * 1. Tells Mojang "I'm joining server X" (proves you own the account; no password is ever sent anywhere else).
 * 2. Sends your name, UUID, profile and X to the registration relay, which confirms it with Mojang
 *    and starts the GitHub checker.
 */
public final class Registration {
    private Registration() {}

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.ALWAYS) // Google Apps Script answers through a redirect
            .build();
    private static volatile boolean busy;

    public static void register(Config config, Guard guard) {
        String url = config.registerUrl();
        if (url.isBlank()) {
            GoldenMan.tell(Component.literal("Registration isn't set up yet. Ask the developer.").withStyle(ChatFormatting.RED));
            return;
        }
        if (guard.profileId() == null) {
            GoldenMan.tell(Component.literal("Join SkyBlock first so your profile can be detected.").withStyle(ChatFormatting.RED));
            return;
        }
        if (busy) return;
        busy = true;
        GoldenMan.tell(Component.literal("Registering for verification...").withStyle(ChatFormatting.YELLOW));

        Minecraft mc = Minecraft.getInstance();
        User user = mc.getUser();
        String profileId = guard.profileId();

        CompletableFuture.runAsync(() -> {
            try {
                String serverId = new BigInteger(130, new SecureRandom()).toString(16);
                mc.services().sessionService().joinServer(user.getProfileId(), user.getAccessToken(), serverId);

                JsonObject body = new JsonObject();
                body.addProperty("username", user.getName());
                body.addProperty("uuid", user.getProfileId().toString().replace("-", ""));
                body.addProperty("profile_id", profileId);
                body.addProperty("serverId", serverId);

                HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                        .build();
                HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
                JsonObject json = JsonParser.parseString(res.body()).getAsJsonObject();
                boolean ok = json.has("ok") && json.get("ok").getAsBoolean();
                String message = json.has("message") ? json.get("message").getAsString() : "No reply from the registration service.";
                mc.execute(() -> GoldenMan.tell(Component.literal(message).withStyle(ok ? ChatFormatting.GREEN : ChatFormatting.RED)));
                if (ok && GoldenMan.verified() != null) GoldenMan.verified().refreshSoon();
            } catch (Exception e) {
                GoldenMan.LOGGER.warn("Golden Man registration failed", e);
                mc.execute(() -> GoldenMan.tell(Component.literal("Registration failed: " + e.getMessage()).withStyle(ChatFormatting.RED)));
            } finally {
                busy = false;
            }
        });
    }
}
