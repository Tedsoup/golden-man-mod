package goldenman;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
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

import java.util.Set;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommands.argument;
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

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, buildContext) -> dispatcher.register(
                literal("goldenman")
                        .executes(ctx -> { openMenuNextTick = true; return 1; })
                        .then(literal("menu").executes(ctx -> { openMenuNextTick = true; return 1; }))
                        .then(literal("status").executes(GoldenMan::status))
                        .then(literal("rules").executes(GoldenMan::rules))
                        .then(literal("start")
                                .executes(ctx -> start(ctx, false))
                                .then(literal("confirm").executes(ctx -> start(ctx, true))))
                        .then(literal("stop")
                                .executes(ctx -> stop(ctx, false))
                                .then(literal("confirm").executes(ctx -> stop(ctx, true))))
                        .then(literal("coop")
                                .then(literal("list").executes(GoldenMan::coopList))
                                .then(literal("add").then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> coopAdd(ctx, StringArgumentType.getString(ctx, "name")))))
                                .then(literal("remove").then(argument("name", StringArgumentType.word())
                                        .executes(ctx -> coopRemove(ctx, StringArgumentType.getString(ctx, "name"))))))
                        .then(literal("debug").executes(GoldenMan::toggleDebug))
        ));

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

    private static void say(CommandContext<FabricClientCommandSource> ctx, String text, ChatFormatting color) {
        ctx.getSource().sendFeedback(prefix().append(Component.literal(text).withStyle(color)));
    }

    private static boolean needProfile(CommandContext<FabricClientCommandSource> ctx) {
        if (guard.profileId() != null) return true;
        say(ctx, "I don't know which SkyBlock profile you're on yet. Join SkyBlock (or switch servers with /hub) so Hypixel shows your Profile ID, then try again.", ChatFormatting.RED);
        return false;
    }

    // ---------- commands ----------

    private static int status(CommandContext<FabricClientCommandSource> ctx) {
        String id = guard.profileId();
        String name = guard.profileName() != null ? guard.profileName() : "unknown";
        if (id == null) {
            say(ctx, "Profile: not detected yet (join SkyBlock first).", ChatFormatting.YELLOW);
        } else {
            say(ctx, "Profile: " + name + " (" + id + ")", ChatFormatting.YELLOW);
            if (guard.enforcing()) {
                say(ctx, "Golden Man is ACTIVE on this profile.", ChatFormatting.GOLD);
                Set<String> coop = config.coopMembers(id);
                say(ctx, "Co-op trade list: " + (coop.isEmpty() ? "(empty)" : String.join(", ", coop)), ChatFormatting.YELLOW);
            } else {
                say(ctx, "Golden Man is not active on this profile. Use /goldenman start to begin.", ChatFormatting.GRAY);
            }
        }
        say(ctx, "Commands: /goldenman rules | start | stop | coop add/remove/list <name> | debug", ChatFormatting.GRAY);
        return 1;
    }

    private static int rules(CommandContext<FabricClientCommandSource> ctx) {
        say(ctx, "Golden Man rules:", ChatFormatting.GOLD);
        say(ctx, "- Bazaar: insta-sell only. No buy orders, no sell offers.", ChatFormatting.YELLOW);
        say(ctx, "- Bazaar: the only thing you may buy is Booster Cookies (instant buy only).", ChatFormatting.YELLOW);
        say(ctx, "- Auction House: completely off-limits.", ChatFormatting.YELLOW);
        say(ctx, "- Trading: only with your co-op members.", ChatFormatting.YELLOW);
        say(ctx, "- NPC shops and the Dark Auction are fine.", ChatFormatting.YELLOW);
        return 1;
    }

    private static int start(CommandContext<FabricClientCommandSource> ctx, boolean confirmed) {
        if (!needProfile(ctx)) return 0;
        String id = guard.profileId();
        if (config.isEnrolled(id)) {
            say(ctx, "Golden Man is already active on this profile.", ChatFormatting.GOLD);
            return 1;
        }
        if (!confirmed) {
            rules(ctx);
            say(ctx, "This turns Golden Man on for profile " + (guard.profileName() != null ? guard.profileName() : id)
                    + ". Type /goldenman start confirm to begin.", ChatFormatting.GOLD);
            return 1;
        }
        config.setEnrolled(id, true);
        say(ctx, "Golden Man is now ACTIVE on this profile. Good luck!", ChatFormatting.GOLD);
        say(ctx, "If you have co-op members, add them so you can trade: /goldenman coop add <name>", ChatFormatting.YELLOW);
        return 1;
    }

    private static int stop(CommandContext<FabricClientCommandSource> ctx, boolean confirmed) {
        if (!needProfile(ctx)) return 0;
        String id = guard.profileId();
        if (!config.isEnrolled(id)) {
            say(ctx, "Golden Man isn't active on this profile.", ChatFormatting.GRAY);
            return 1;
        }
        if (!confirmed) {
            say(ctx, "This ends your Golden Man run on this profile. Type /goldenman stop confirm if you're sure.", ChatFormatting.RED);
            return 1;
        }
        config.setEnrolled(id, false);
        say(ctx, "Golden Man turned off for this profile.", ChatFormatting.GRAY);
        return 1;
    }

    private static int coopList(CommandContext<FabricClientCommandSource> ctx) {
        if (!needProfile(ctx)) return 0;
        Set<String> coop = config.coopMembers(guard.profileId());
        say(ctx, "Co-op trade list: " + (coop.isEmpty() ? "(empty)" : String.join(", ", coop)), ChatFormatting.YELLOW);
        return 1;
    }

    private static int coopAdd(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (!needProfile(ctx)) return 0;
        if (config.addCoopMember(guard.profileId(), name)) say(ctx, "Added " + name + " to your co-op trade list.", ChatFormatting.GREEN);
        else say(ctx, name + " is already on your co-op trade list.", ChatFormatting.GRAY);
        return 1;
    }

    private static int coopRemove(CommandContext<FabricClientCommandSource> ctx, String name) {
        if (!needProfile(ctx)) return 0;
        if (config.removeCoopMember(guard.profileId(), name)) say(ctx, "Removed " + name + " from your co-op trade list.", ChatFormatting.YELLOW);
        else say(ctx, name + " isn't on your co-op trade list.", ChatFormatting.GRAY);
        return 1;
    }

    private static int toggleDebug(CommandContext<FabricClientCommandSource> ctx) {
        boolean on = !config.debug();
        config.setDebug(on);
        say(ctx, "Debug messages " + (on ? "ON: menu titles and clicked buttons will show in chat." : "OFF."), ChatFormatting.GRAY);
        return 1;
    }
}
