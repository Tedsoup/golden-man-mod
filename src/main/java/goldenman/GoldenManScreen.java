package goldenman;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/** The Golden Man menu (G by default, or /goldenman). */
public final class GoldenManScreen extends Screen {
    private static final int PANEL_W = 260;
    private static final int GOLD = 0xFFFFC400;
    private static final int YELLOW = 0xFFFFFF55;
    private static final int GRAY = 0xFFAAAAAA;
    private static final int GREEN = 0xFF55FF55;
    private static final int RED = 0xFFFF5555;
    private static final int WHITE = 0xFFFFFFFF;

    private static final String[] RULES = {
            "Bazaar: insta-sell only",
            "Bazaar buys: Booster Cookies only",
            "Auction House: off-limits",
            "Trading: co-op members only",
    };
    private static final int MAX_COOP_ROWS = 4;

    private final Config config;
    private final Guard guard;
    private EditBox coopInput;

    /** Text lines drawn inside the status panel. */
    private final List<Line> lines = new ArrayList<>();
    private int panelTop;
    private int panelBottom;

    private record Line(String text, int color) {}

    public GoldenManScreen(Config config, Guard guard) {
        super(Component.literal("Golden Man"));
        this.config = config;
        this.guard = guard;
    }

    private int cx() {
        return width / 2;
    }

    @Override
    protected void init() {
        lines.clear();
        panelTop = 44;
        String id = guard.profileId();
        String profile = guard.profileName() != null ? guard.profileName() : "this profile";

        if (id == null) {
            lines.add(new Line("Join Hypixel SkyBlock to get started.", WHITE));
            lines.add(new Line("(If you're already in SkyBlock, use /hub once", GRAY));
            lines.add(new Line("so Hypixel shows which profile you're on.)", GRAY));
            int y = layoutPanel();
            addDone(y + 10);
            return;
        }

        if (!guard.enforcing()) {
            initNotStarted(profile);
        } else {
            initActive(profile);
        }
    }

    /** Computes the panel height from its lines and returns the first free y below it. */
    private int layoutPanel() {
        panelBottom = panelTop + 8 + lines.size() * 11 + 4;
        return panelBottom + 8;
    }

    // ---------- not started ----------

    private void initNotStarted(String profile) {
        lines.add(new Line("Start a Golden Man run on " + profile, GOLD));
        lines.add(new Line("", WHITE));
        for (String r : RULES) lines.add(new Line("• " + r, YELLOW));
        lines.add(new Line("", WHITE));

        Integer level = guard.ownLevel();
        boolean levelOk = level == null || level < 7 || guard.isDev();
        if (level == null) {
            lines.add(new Line("? Fresh profile (below SkyBlock level 7)", GRAY));
        } else if (level < 7) {
            lines.add(new Line("✔ Fresh profile (level " + level + ")", GREEN));
        } else {
            lines.add(new Line("✘ Needs a fresh profile below level 7 (this is " + level + ")", guard.isDev() ? GRAY : RED));
        }
        lines.add(new Line("• Turn ON all API settings: SkyBlock Menu > Settings", WHITE));
        int y = layoutPanel();

        Button start = Button.builder(Component.literal("Start Golden Man").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD),
                b -> minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        String err = guard.checkCanStart();
                        if (err == null) {
                            config.setEnrolled(guard.profileId(), true);
                            GoldenMan.tell(Component.literal("Golden Man started. Good luck!").withStyle(ChatFormatting.GOLD));
                            Registration.register(config, guard);
                        } else {
                            GoldenMan.tell(Component.literal(err).withStyle(ChatFormatting.RED));
                        }
                    }
                    minecraft.gui.setScreen(new GoldenManScreen(config, guard));
                },
                        Component.literal("Start Golden Man on " + profile + "?").withStyle(ChatFormatting.GOLD),
                        Component.literal("From now on: no Auction House, no Bazaar buying except Booster Cookies, "
                                + "and trading only with your co-op. You'll be registered for verification automatically."),
                        Component.literal("Start"), Component.literal("Cancel"))))
                .bounds(cx() - 100, y, 200, 24).build();
        start.active = levelOk;
        if (!levelOk) start.setTooltip(Tooltip.create(Component.literal("Golden Man has to start on a fresh profile below SkyBlock level 7.")));
        addRenderableWidget(start);
        addDone(y + 30);
    }

    // ---------- active ----------

    private void initActive(String profile) {
        String id = guard.profileId();
        lines.add(new Line("Golden Man · " + profile + (guard.isDev() ? " · developer" : ""), GOLD));

        Verified.Entry me = GoldenMan.verified() != null ? GoldenMan.verified().self() : null;
        boolean needsRegister = false;
        if (guard.localFailure() != null && !guard.isDev()) {
            lines.add(new Line("✘ Failed: " + guard.localFailure(), RED));
        } else if (me == null) {
            boolean loaded = GoldenMan.verified() != null && GoldenMan.verified().loaded();
            lines.add(new Line(loaded ? "Not verified yet: not registered" : "Checking verification...", GRAY));
            needsRegister = loaded;
        } else {
            switch (me.status()) {
                case VERIFIED -> lines.add(new Line("✔ Verified" + (me.dev() ? " (developer)" : ""), GREEN));
                case PENDING -> lines.add(new Line("… Verification pending", YELLOW));
                case FAILED -> lines.add(new Line("✘ Verification failed", RED));
            }
            for (String r : me.reasons()) lines.add(new Line("  - " + r, me.dev() ? GRAY : (me.status() == Verified.Status.FAILED ? RED : YELLOW)));
            needsRegister = me.status() == Verified.Status.PENDING;
        }
        lines.add(new Line("", WHITE));
        lines.add(new Line("Co-op members you can trade with:", WHITE));
        int y = layoutPanel();

        if (needsRegister) {
            addRenderableWidget(Button.builder(Component.literal(me == null ? "Register for verification" : "Check again"),
                    b -> { Registration.register(config, guard); onClose(); }).bounds(cx() - 100, y, 200, 20).build());
            y += 26;
        }

        coopInput = new EditBox(font, cx() - 100, y, 146, 20, Component.literal("Co-op member"));
        coopInput.setMaxLength(16);
        coopInput.setHint(Component.literal("Username").withStyle(ChatFormatting.DARK_GRAY));
        addRenderableWidget(coopInput);
        addRenderableWidget(Button.builder(Component.literal("Add"), b -> {
            String name = coopInput.getValue().trim();
            if (name.matches("[A-Za-z0-9_]{1,16}")) {
                config.addCoopMember(id, name);
                rebuildWidgets();
            }
        }).bounds(cx() + 50, y, 50, 20).build());
        y += 24;

        List<String> members = new ArrayList<>(config.coopMembers(id));
        for (int i = 0; i < Math.min(members.size(), MAX_COOP_ROWS); i++) {
            String m = members.get(i);
            int col = i % 2;
            int bx = cx() - 100 + col * 102;
            Button b = Button.builder(Component.literal(m + "  ✕"), btn -> {
                config.removeCoopMember(id, m);
                rebuildWidgets();
            }).bounds(bx, y, 98, 18).build();
            b.setTooltip(Tooltip.create(Component.literal("Remove " + m)));
            addRenderableWidget(b);
            if (col == 1 || i == Math.min(members.size(), MAX_COOP_ROWS) - 1) y += 20;
        }
        addDone(y + 8);

        // Tucked away in the corner, with a confirmation
        addRenderableWidget(Button.builder(Component.literal("End run...").withStyle(ChatFormatting.GRAY),
                b -> minecraft.gui.setScreen(new ConfirmScreen(confirmed -> {
                    if (confirmed) {
                        config.setEnrolled(guard.profileId(), false);
                        Registration.quit(config, guard);
                        GoldenMan.tell(Component.literal("Your Golden Man run has ended.").withStyle(ChatFormatting.GRAY));
                    }
                    minecraft.gui.setScreen(confirmed ? null : new GoldenManScreen(config, guard));
                },
                        Component.literal("End your Golden Man run?").withStyle(ChatFormatting.RED),
                        Component.literal("The restrictions turn off and you're removed from the Golden Man list. This can't be undone."),
                        Component.literal("End run").withStyle(ChatFormatting.RED), Component.literal("Keep playing"))))
                .bounds(6, height - 26, 70, 20).build());
    }

    private void addDone(int y) {
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx() - 100, y, 200, 20).build());
        // Small debug toggle in the corner (shows menu titles and clicked buttons in chat)
        addRenderableWidget(Button.builder(Component.literal("Debug: " + (config.debug() ? "on" : "off")).withStyle(ChatFormatting.DARK_GRAY),
                b -> { config.setDebug(!config.debug()); rebuildWidgets(); }).bounds(width - 76, height - 26, 70, 20).build());
    }

    // ---------- drawing ----------

    @Override
    public void extractBackground(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractBackground(g, mouseX, mouseY, delta);
        int left = cx() - PANEL_W / 2;
        g.fill(left, panelTop, left + PANEL_W, panelBottom, 0xB0000000);
        g.fill(left, panelTop, left + PANEL_W, panelTop + 1, 0xFFFFC400);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float delta) {
        super.extractRenderState(g, mouseX, mouseY, delta);
        long now = System.currentTimeMillis();
        g.centeredText(font, NameGlow.gold("GOLDEN MAN", now), cx(), 14, WHITE);
        g.centeredText(font, "Ironman, but you can insta-sell to the Bazaar", cx(), 27, GRAY);

        int y = panelTop + 8;
        int left = cx() - PANEL_W / 2 + 8;
        int maxW = PANEL_W - 16;
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            String text = font.width(l.text()) > maxW ? font.plainSubstrByWidth(l.text(), maxW - 6) + "..." : l.text();
            if (i == 0) g.centeredText(font, text, cx(), y, l.color());
            else g.text(font, text, left, y, l.color());
            y += 11;
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
