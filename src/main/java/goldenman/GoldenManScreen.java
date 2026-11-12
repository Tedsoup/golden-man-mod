package goldenman;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The Golden Man menu (opened with G or /goldenman). */
public final class GoldenManScreen extends Screen {
    private static final String[] RULES = {
            "Bazaar: insta-sell only. No buy orders or sell offers.",
            "Bazaar buys: Booster Cookies only (instant buy).",
            "Auction House: completely off-limits.",
            "Trading: only with your co-op members.",
            "NPC shops and the Dark Auction are fine."
    };
    private static final int MAX_COOP_ROWS = 4;

    private final Config config;
    private final Guard guard;
    private boolean confirmStart;
    private boolean confirmStop;
    private EditBox coopInput;
    private String error;

    public GoldenManScreen(Config config, Guard guard) {
        super(Component.literal("Golden Man"));
        this.config = config;
        this.guard = guard;
    }

    @Override
    protected void init() {
        int cx = width / 2;
        int y = 40 + RULES.length * 11 + 34;
        String id = guard.profileId();
        boolean active = guard.enforcing();

        if (!active) {
            Button start = Button.builder(
                    Component.literal(confirmStart ? "Click again to confirm" : "Start Golden Man")
                            .withStyle(confirmStart ? ChatFormatting.RED : ChatFormatting.GOLD),
                    b -> {
                        error = guard.checkCanStart();
                        if (error != null) {
                            confirmStart = false;
                        } else if (!confirmStart) {
                            confirmStart = true;
                        } else {
                            config.setEnrolled(id, true);
                            confirmStart = false;
                            GoldenMan.tell(Component.literal("Golden Man is now ACTIVE on this profile. Good luck!").withStyle(ChatFormatting.GOLD));
                            GoldenMan.tell(Component.literal("To get verified: turn ON all API settings (SkyBlock Menu > Settings > API Settings), then click \"Register for verification\" in this menu.").withStyle(ChatFormatting.YELLOW));
                        }
                        rebuildWidgets();
                    }).bounds(cx - 100, y, 200, 20).build();
            start.active = id != null;
            addRenderableWidget(start);
            y += 28;
        } else {
            // Co-op members you may trade with
            coopInput = new EditBox(font, cx - 100, y, 140, 20, Component.literal("Co-op member"));
            coopInput.setMaxLength(16);
            coopInput.setHint(Component.literal("Co-op member name").withStyle(ChatFormatting.DARK_GRAY));
            addRenderableWidget(coopInput);
            addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
                String name = coopInput.getValue().trim();
                if (name.matches("[A-Za-z0-9_]{1,16}")) {
                    config.addCoopMember(id, name);
                    rebuildWidgets();
                }
            }).bounds(cx + 44, y, 56, 20).build());
            y += 24;

            List<String> members = new ArrayList<>(config.coopMembers(id));
            for (int i = 0; i < Math.min(members.size(), MAX_COOP_ROWS); i++) {
                String m = members.get(i);
                addRenderableWidget(Button.builder(Component.literal("Remove " + m), b -> {
                    config.removeCoopMember(id, m);
                    rebuildWidgets();
                }).bounds(cx - 100, y, 200, 18).build());
                y += 20;
            }
            y += 6;

            addRenderableWidget(Button.builder(Component.literal("Register for verification").withStyle(ChatFormatting.GOLD), b ->
                    net.minecraft.util.Util.getPlatform().openUri(guard.registrationUrl())
            ).bounds(cx - 100, y, 200, 20).build());
            y += 24;

            addRenderableWidget(Button.builder(
                    Component.literal(confirmStop ? "Click again to end your run" : "Stop Golden Man")
                            .withStyle(ChatFormatting.RED),
                    b -> {
                        if (!confirmStop) {
                            confirmStop = true;
                        } else {
                            config.setEnrolled(id, false);
                            confirmStop = false;
                            GoldenMan.tell(Component.literal("Golden Man turned off for this profile.").withStyle(ChatFormatting.GRAY));
                        }
                        rebuildWidgets();
                    }).bounds(cx - 100, y, 200, 20).build());
            y += 28;
        }

        addRenderableWidget(Button.builder(
                Component.literal("Name glow: " + (config.glow() ? "ON" : "OFF")),
                b -> { config.setGlow(!config.glow()); rebuildWidgets(); }).bounds(cx - 100, y, 98, 20).build());
        addRenderableWidget(Button.builder(
                Component.literal("Debug: " + (config.debug() ? "ON" : "OFF")),
                b -> { config.setDebug(!config.debug()); rebuildWidgets(); }).bounds(cx + 2, y, 98, 20).build());
        y += 24;
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        int cx = width / 2;
        long now = System.currentTimeMillis();

        g.centeredText(font, NameGlow.gold("GOLDEN MAN", now), cx, 12, 0xFFFFFFFF);

        String id = guard.profileId();
        String status;
        int statusColor;
        if (id == null) {
            status = "Join SkyBlock so your profile can be detected.";
            statusColor = 0xFFFF5555;
        } else {
            String name = guard.profileName() != null ? guard.profileName() : "this profile";
            if (guard.enforcing() && guard.localFailure() != null && !guard.isDev()) {
                status = "FAILED on " + name + ": " + guard.localFailure();
                statusColor = 0xFFAA0000;
            } else if (guard.enforcing()) {
                status = "ACTIVE on " + name + (guard.isDev() ? " (developer)" : "");
                statusColor = 0xFFFFFF55;
            } else {
                status = "Not active on " + name;
                statusColor = 0xFFAAAAAA;
            }
        }
        g.centeredText(font, status, cx, 26, statusColor);

        if (error != null) {
            g.centeredText(font, error, cx, height - 14, 0xFFFF5555);
        }

        int y = 44;
        for (String rule : RULES) {
            g.centeredText(font, rule, cx, y, 0xFFFFFF55);
            y += 11;
        }
        if (guard.enforcing()) {
            g.centeredText(font, "Co-op members you can trade with:", cx, 40 + RULES.length * 11 + 20, 0xFFFFFFFF);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
