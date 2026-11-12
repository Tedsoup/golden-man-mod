package goldenman;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.literal;

public final class GoldenMan implements ClientModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("goldenman");

    private static Config config;
    private static Guard guard;
    private static Verified verified;
    private static volatile java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.network.chat.Style>> nameRules = java.util.Map.of();
    private static KeyMapping openMenuKey;
    private static boolean openMenuNextTick;

    public static Guard guard() {
        return guard;
    }

    public static Verified verified() {
        return verified;
    }

    /** Name colors for the tab list and chat, recomputed once per tick. */
    public static java.util.Map<String, java.util.function.UnaryOperator<net.minecraft.network.chat.Style>> nameRules() {
        return nameRules;
    }

    @Override
    public void onInitializeClient() {
        config = new Config();
        config.load();
        guard = new Guard(config);
        verified = new Verified(config);
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            verified.tick();
            if (client.player != null) nameRules = NameGlow.currentRules(guard, verified);
        });

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) guard.onChat(message);
        });
        // Golden Man / creator / failed name colors in chat
        ClientReceiveMessageEvents.MODIFY_GAME.register((message, overlay) ->
                overlay ? message : NameGlow.decorate(message));

        KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath("goldenman", "main"));
        openMenuKey = KeyMappingHelper.registerKeyMapping(
                new KeyMapping("key.goldenman.open", InputConstants.Type.KEYSYM, InputConstants.KEY_G, category));
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            boolean pressed = false;
            while (openMenuKey.consumeClick()) pressed = true;
            if ((pressed || openMenuNextTick) && client.player != null) {
                openMenuNextTick = false;
                client.gui.setScreen(new GoldenManScreen(config, guard));
            }
        });
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> guard.onScreenOpened(client, screen));
        ClientTickEvents.END_CLIENT_TICK.register(guard::onTick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> guard.onDisconnect());

        // Everything happens in the menu; /goldenman is just another way to open it.
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                literal("goldenman").executes(ctx -> { openMenuNextTick = true; return 1; })));

        LOGGER.info("Golden Man loaded");
    }

    // ---------- chat helpers ----------

    private static MutableComponent prefix() {
        return Component.literal("[Golden Man] ").withStyle(ChatFormatting.GOLD);
    }

    public static void tell(Component message) {
        Minecraft client = Minecraft.getInstance();
        if (client.player != null) client.player.sendSystemMessage(prefix().append(message));
    }


    /** The key that opens the menu, as text (e.g. "G"). */
    public static String menuKeyName() {
        return openMenuKey == null ? "G" : openMenuKey.getTranslatedKeyMessage().getString();
    }
}
