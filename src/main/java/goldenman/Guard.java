package goldenman;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.component.ItemLore;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Tracks where the player is (profile, Bazaar flow, AH flow, trades) and enforces the rules. */
public final class Guard {
    private static final Pattern PROFILE_ID = Pattern.compile("Profile ID: ([0-9a-fA-F-]{32,36})");
    private static final Pattern PROFILE_NAME = Pattern.compile("You are playing on profile: ([A-Za-z]+)");
    private static final Pattern TRADE_SENT = Pattern.compile("^You have sent a trade request to ([A-Za-z0-9_]{1,16})\\.$");
    private static final Pattern TRADE_RECEIVED = Pattern.compile("^(?:\\[[^\\]]+\\] )?([A-Za-z0-9_]{1,16}) has sent you a trade request!$");
    private static final long TRADE_REQUEST_TTL_MS = 60_000;

    private final Config config;

    private String profileId;
    private String profileName;

    private boolean inBazaar;
    private boolean inAuction;
    private String lastProduct;

    private String recentTradePartner;
    private long recentTradeAt;

    private String lastMessage;
    private long lastMessageAt;

    private Screen scannedScreen;
    private final Set<String> devAlertsShown = new HashSet<>();

    public Guard(Config config) {
        this.config = config;
    }

    // ---------- state ----------

    public String profileId() { return profileId; }
    public String profileName() { return profileName; }

    public boolean enforcing() {
        return config.isEnrolled(profileId);
    }

    public boolean glowEnabled() {
        return config.glow();
    }

    public String localFailure() {
        return config.failure(profileId);
    }

    /** True when you are the mod's creator (tedsoup): checks still run, but failures only show as alerts. */
    public boolean isDev() {
        Minecraft mc = Minecraft.getInstance();
        return Dev.isDev(mc.getUser().getProfileId(), mc.getUser().getName());
    }

    // ---------- verification checks (done on your own client) ----------

    private static final Pattern TAB_LEVEL = Pattern.compile("^\\[(\\d+)\\]\\s+([A-Za-z0-9_]{1,16})");

    /** Your SkyBlock level, read from the tab list ("[172] tedsoup"), or null if it isn't visible. */
    public Integer ownLevel() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.getConnection() == null) return null;
        String me = mc.getUser().getName();
        for (PlayerInfo info : mc.getConnection().getListedOnlinePlayers()) {
            Component dn = info.getTabListDisplayName();
            if (dn == null) continue;
            Matcher m = TAB_LEVEL.matcher(Rules.clean(dn.getString()));
            if (m.find() && m.group(2).equalsIgnoreCase(me)) {
                try { return Integer.parseInt(m.group(1)); } catch (NumberFormatException ignored) { }
            }
        }
        return null;
    }

    /**
     * Checks that must pass to start Golden Man.
     * @return an error to show, or null when you may start.
     */
    public String checkCanStart() {
        if (profileId == null) return "Join SkyBlock first so your profile can be detected.";
        Integer level = ownLevel();
        if (level != null && level >= 7) {
            String reason = "Golden Man has to start on a fresh profile below SkyBlock level 7 (this one is level " + level + ").";
            if (isDev()) {
                devAlert(reason);
                return null;
            }
            return reason;
        }
        return null;
    }

    /** Records a failed check. For the developer it's only an alert. */
    public void fail(String reason) {
        if (isDev()) {
            devAlert(reason);
            return;
        }
        if (config.failure(profileId) != null) return;
        config.setFailure(profileId, reason);
        GoldenMan.tell(Component.literal("You FAILED a Golden Man check: " + reason).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD));
    }

    private void devAlert(String reason) {
        if (!devAlertsShown.add(reason)) return;
        GoldenMan.tell(Component.literal("[DEV] Check failed: " + reason + " (ignored because you're the developer)")
                .withStyle(ChatFormatting.LIGHT_PURPLE));
    }

    /** Link that opens the prefilled GitHub registration form for this profile. */
    public String registrationUrl() {
        Minecraft mc = Minecraft.getInstance();
        String uuid = mc.getUser().getProfileId().toString().replace("-", "");
        String name = mc.getUser().getName();
        String title = "Register " + name;
        return "https://github.com/" + config.repo() + "/issues/new?template=register.yml"
                + "&title=" + enc(title)
                + "&username=" + enc(name)
                + "&uuid=" + enc(uuid)
                + "&profile_id=" + enc(profileId == null ? "" : profileId);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Looks through Bazaar order/history menus once their items have loaded. */
    private void scanBazaarMenus(Minecraft client) {
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?> acs) || screen == scannedScreen || !enforcing()) return;
        String title = Rules.clean(screen.getTitle().getString());
        String lower = title.toLowerCase(Locale.ROOT);
        boolean orders = lower.endsWith("bazaar orders");
        boolean history = inBazaar && lower.contains("history");
        if (!orders && !history) return;

        boolean anyItem = false;
        for (Slot slot : acs.getMenu().slots) {
            if (slot.container instanceof Inventory) continue;
            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) continue;
            anyItem = true;
            String name = Rules.clean(stack.getHoverName().getString());
            String reason = orders ? Rules.orderViolation(name) : null;
            if (reason == null && history) {
                StringBuilder lore = new StringBuilder();
                ItemLore il = stack.get(DataComponents.LORE);
                if (il != null) for (Component line : il.lines()) lore.append(Rules.clean(line.getString())).append('\n');
                reason = Rules.historyViolation(name, lore.toString());
            }
            if (reason != null) {
                fail(reason);
                break;
            }
        }
        if (anyItem) scannedScreen = screen; // items arrive a moment after the menu opens
    }

    public void onDisconnect() {
        profileId = null;
        profileName = null;
        resetMenus();
    }

    private void resetMenus() {
        inBazaar = false;
        inAuction = false;
        lastProduct = null;
    }

    /** Called every tick: once no menu is open, the Bazaar/AH "flow" is over. */
    public void onTick(Minecraft client) {
        if (client.gui.screen() == null && (inBazaar || inAuction || lastProduct != null)) resetMenus();
        scanBazaarMenus(client);
    }

    public void onChat(Component message) {
        String text = Rules.clean(message.getString());
        Matcher m = PROFILE_ID.matcher(text);
        if (m.find()) {
            profileId = m.group(1).toLowerCase();
            if (enforcing()) {
                GoldenMan.tell(Component.literal("Golden Man is active on this profile.").withStyle(ChatFormatting.GOLD));
            }
            return;
        }
        m = PROFILE_NAME.matcher(text);
        if (m.find()) {
            profileName = m.group(1);
            return;
        }
        m = TRADE_SENT.matcher(text);
        if (!m.matches()) m = TRADE_RECEIVED.matcher(text);
        if (m.matches()) {
            recentTradePartner = m.group(1);
            recentTradeAt = System.currentTimeMillis();
        }
    }

    // ---------- menus opening ----------

    public void onScreenOpened(Minecraft client, Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?>)) return;
        String title = Rules.clean(screen.getTitle().getString());
        debug("Menu opened: \"" + title + "\"");

        boolean cameFromBazaar = inBazaar;
        boolean cameFromAuction = inAuction;

        if (Rules.isBazaarTitle(title)) inBazaar = true;
        inAuction = Rules.isAuctionTitle(title);
        String product = Rules.productFromTitle(title);
        if (product != null) lastProduct = product;

        if (!enforcing()) return;

        String reason = Rules.blockScreen(title, cameFromBazaar, cameFromAuction, lastProduct);

        if (reason == null) {
            String partner = Rules.tradePartnerFromTitle(title);
            if (partner != null) {
                if (partner.isEmpty() && recentTradePartner != null
                        && System.currentTimeMillis() - recentTradeAt < TRADE_REQUEST_TTL_MS) {
                    partner = recentTradePartner;
                }
                reason = Rules.blockTrade(partner, config.coopMembersLower(profileId));
            }
        }

        if (reason != null) {
            warn(reason);
            client.execute(() -> {
                if (client.player != null && client.gui.screen() == screen) client.player.closeContainer();
            });
        }
    }

    // ---------- clicks ----------

    /** @return true if the click must be cancelled. */
    public boolean onContainerClick(int containerId, int slotNum, ContainerInput input, Player player) {
        Minecraft client = Minecraft.getInstance();
        Screen screen = client.gui.screen();
        if (!(screen instanceof AbstractContainerScreen<?>)) return false;

        String title = Rules.clean(screen.getTitle().getString());
        String buttonName = "";
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != null && slotNum >= 0 && slotNum < menu.slots.size()) {
            Slot slot = menu.getSlot(slotNum);
            ItemStack stack = slot.getItem();
            if (!stack.isEmpty()) buttonName = Rules.clean(stack.getHoverName().getString());
        }
        debug("Click in \"" + title + "\" on slot " + slotNum + " \"" + buttonName + "\" (" + input + ")");

        if (!enforcing()) return false;

        String product = Rules.productFromTitle(title);
        if (product == null) product = lastProduct;

        String reason = Rules.blockClick(title, buttonName, inBazaar, product);
        if (reason == null) {
            String partner = Rules.tradePartnerFromTitle(title);
            if (partner != null) {
                if (partner.isEmpty() && recentTradePartner != null) partner = recentTradePartner;
                reason = Rules.blockTrade(partner, config.coopMembersLower(profileId));
            }
        }
        if (reason != null) {
            warn(reason);
            return true;
        }
        return false;
    }

    // ---------- hiding Bazaar buttons ----------

    /** True if this item is a Bazaar button Golden Man doesn't allow, so the menu should not even draw it. */
    public boolean isHiddenButton(Screen screen, ItemStack stack) {
        if (stack == null || stack.isEmpty() || !enforcing()) return false;
        String title = Rules.clean(screen.getTitle().getString());
        if (!inBazaar && !Rules.isBazaarTitle(title)) return false;
        String product = Rules.productFromTitle(title);
        if (product == null) product = lastProduct;
        return Rules.blockClick(title, Rules.clean(stack.getHoverName().getString()), true, product) != null;
    }

    /** Your name glows while Golden Man is active on this profile (and the glow setting is on). */
    public boolean shouldGlow() {
        return enforcing() && config.glow();
    }

    // ---------- messages ----------

    private void warn(String reason) {
        long now = System.currentTimeMillis();
        if (reason.equals(lastMessage) && now - lastMessageAt < 1500) return;
        lastMessage = reason;
        lastMessageAt = now;
        GoldenMan.tell(Component.literal(reason).withStyle(ChatFormatting.RED));
    }

    private void debug(String text) {
        if (config.debug()) {
            GoldenMan.tell(Component.literal("[debug] " + text).withStyle(ChatFormatting.GRAY));
        }
    }
}
