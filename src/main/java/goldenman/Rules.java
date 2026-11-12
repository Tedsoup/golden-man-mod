package goldenman;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Golden Man rules, as plain string logic (no Minecraft classes), so they are easy to read and test.
 *
 * Rules:
 *  - Bazaar: instant-sell only. No buy orders, no sell offers, no instant buys, except instant-buying Booster Cookies.
 *  - Auction House: completely off-limits (no buying, bidding or creating auctions).
 *  - Trading: only with co-op members.
 *  - NPC shops, the Dark Auction and other NPC menus are untouched.
 *
 * Menu titles come from Hypixel's own menus (cross-checked against other 26.2 Bazaar/AH mods).
 */
public final class Rules {
    private Rules() {}

    public static final String ARROW = "➜"; // ➜, used in Hypixel Bazaar titles
    public static final String BOOSTER_COOKIE = "Booster Cookie";

    /** Bazaar menus that only exist to set up or confirm a buy order / sell offer. */
    private static final Set<String> ORDER_SETUP_TITLES = Set.of(
            "confirm buy order",
            "confirm sell offer"
    );
    /** Order-setup questions; only treated as Bazaar when we came from the Bazaar. */
    private static final Set<String> ORDER_QUESTION_TITLES = Set.of(
            "how many do you want?",
            "how much do you want to pay?",
            "at what price are you selling?"
    );
    /** Buttons that start a buy order / sell offer, or flip one. */
    private static final Set<String> ORDER_BUTTONS = Set.of(
            "create buy order",
            "create sell offer",
            "flip order"
    );
    private static final Set<String> INSTANT_BUY_BUTTONS = Set.of(
            "buy instantly",
            "buy only one!",
            "buy a stack!",
            "fill my inventory!",
            "custom amount"
    );

    private static final Pattern TRADE_TITLE = Pattern.compile("^You\\s+([A-Za-z0-9_]{1,16})$");

    // ---------- helpers ----------

    public static String clean(String s) {
        if (s == null) return "";
        return s.replaceAll("§.", "").trim();
    }

    private static String lower(String s) {
        return clean(s).toLowerCase(Locale.ROOT);
    }

    public static boolean isCookie(String itemName) {
        return itemName != null && clean(itemName).equalsIgnoreCase(BOOSTER_COOKIE);
    }

    // ---------- screen classification ----------

    public static boolean isAuctionTitle(String title) {
        String t = lower(title);
        return t.contains("auction") && !t.contains("dark auction");
    }

    /** True for titles that are clearly part of the Bazaar. */
    public static boolean isBazaarTitle(String title) {
        String t = clean(title);
        String l = t.toLowerCase(Locale.ROOT);
        return t.startsWith("Bazaar") || t.contains(ARROW)
                || ORDER_SETUP_TITLES.contains(l)
                || l.equals("confirm instant buy") || l.equals("confirm instant sell")
                || l.endsWith("bazaar orders") || l.equals("order options");
    }

    public static boolean isInstantBuyTitle(String title) {
        return clean(title).endsWith(ARROW + " Instant Buy");
    }

    /**
     * Works out which Bazaar product a menu is about, from its title.
     * "Booster Cookie ➜ Instant Buy" -> "Booster Cookie"; "Oddities ➜ Booster Cookie" -> "Booster Cookie".
     * Returns null when the title says nothing about a product.
     */
    public static String productFromTitle(String title) {
        String t = clean(title);
        if (!t.contains(ARROW)) return null;
        if (isInstantBuyTitle(t)) {
            return t.substring(0, t.lastIndexOf(ARROW)).trim();
        }
        if (t.startsWith("Bazaar " + ARROW)) return null; // category page
        String after = t.substring(t.lastIndexOf(ARROW) + 1).trim();
        if (after.equalsIgnoreCase("Instant Sell") || after.equalsIgnoreCase("Graphs") || after.equalsIgnoreCase("Settings")) {
            return t.substring(0, t.lastIndexOf(ARROW)).trim();
        }
        return after;
    }

    /** Partner name if this is a player trade window ("You        Name"), "" if it is a trade window with an unreadable name, null otherwise. */
    public static String tradePartnerFromTitle(String title) {
        String t = clean(title);
        Matcher m = TRADE_TITLE.matcher(t);
        if (m.matches()) return m.group(1);
        if (t.startsWith("You ") && t.length() > 4 && t.substring(3).startsWith("  ")) return "";
        return null;
    }

    // ---------- decisions ----------

    /**
     * Should this menu be closed as soon as it opens?
     * @return a reason to show the player, or null to allow it.
     */
    public static String blockScreen(String title, boolean cameFromBazaar, boolean cameFromAuction, String lastProduct) {
        String l = lower(title);

        if (isAuctionTitle(title)) return "The Auction House is off-limits in Golden Man.";
        if (cameFromAuction && l.equals("confirm purchase")) return "The Auction House is off-limits in Golden Man.";

        if (ORDER_SETUP_TITLES.contains(l)) return "Golden Man: no buy orders or sell offers. Insta-sell only!";
        if (cameFromBazaar && ORDER_QUESTION_TITLES.contains(l)) return "Golden Man: no buy orders or sell offers. Insta-sell only!";

        if (isInstantBuyTitle(title) && !isCookie(productFromTitle(title))) {
            return "Golden Man: the only thing you can buy from the Bazaar is Booster Cookies.";
        }
        if (l.equals("confirm instant buy") && !isCookie(lastProduct)) {
            return "Golden Man: the only thing you can buy from the Bazaar is Booster Cookies.";
        }
        return null;
    }

    /**
     * Should a click on a button with this name, inside a menu with this title, be cancelled?
     * @return a reason to show the player, or null to allow it.
     */
    public static String blockClick(String title, String buttonName, boolean inBazaar, String currentProduct) {
        if (isAuctionTitle(title)) return "The Auction House is off-limits in Golden Man.";
        if (!inBazaar && !isBazaarTitle(title)) return null;

        String b = lower(buttonName);
        if (b.isEmpty()) return null;
        if (ORDER_BUTTONS.contains(b)) return "Golden Man: no buy orders or sell offers. Insta-sell only!";
        if (INSTANT_BUY_BUTTONS.contains(b) && !isCookie(currentProduct)) {
            return "Golden Man: the only thing you can buy from the Bazaar is Booster Cookies.";
        }
        return null;
    }

    /** Any open Bazaar order means a buy order or sell offer was made. Order items are named "BUY Item" / "SELL Item". */
    public static String orderViolation(String itemName) {
        String n = clean(itemName);
        String l = n.toLowerCase(Locale.ROOT);
        if (l.startsWith("buy ")) return "had a Bazaar buy order (" + n.substring(4).trim() + ")";
        if (l.startsWith("sell ")) return "had a Bazaar sell offer (" + n.substring(5).trim() + ")";
        return null;
    }

    /** Bazaar history entries: any order, or any instant buy that isn't a Booster Cookie. */
    public static String historyViolation(String itemName, String lore) {
        String all = (clean(itemName) + "\n" + clean(lore)).toLowerCase(Locale.ROOT);
        if (all.contains("buy order")) return "Bazaar history shows a buy order";
        if (all.contains("sell offer")) return "Bazaar history shows a sell offer";
        boolean bought = all.contains("instant buy") || all.contains("bought");
        if (bought && !all.contains("booster cookie")) return "Bazaar history shows an instant buy that isn't a Booster Cookie";
        return null;
    }

    /** Trades are allowed only with a known co-op member. */
    public static String blockTrade(String partner, Set<String> coopMembersLower) {
        if (partner == null || partner.isEmpty()) {
            return "Golden Man: couldn't tell who this trade is with, so it was closed. You can only trade with co-op members.";
        }
        if (coopMembersLower.contains(partner.toLowerCase(Locale.ROOT))) return null;
        return "Golden Man: you can only trade with your co-op members (" + partner + " isn't on your list). Add them with /goldenman coop add <name>.";
    }
}
