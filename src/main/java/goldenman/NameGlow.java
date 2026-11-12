package goldenman;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.UnaryOperator;

/**
 * Recolors player names inside tab-list/chat text:
 *  - the mod's creator: dark red
 *  - Golden Man players (verified, or you while active): yellow (MVP++ is gold, so this stays distinct)
 *  - confirmed cheaters: their name gets a line through it
 * Only Minecraft's built-in colors are used, because some tab/chat mods turn custom RGB colors into "<#hex>" text.
 */
public final class NameGlow {
    private NameGlow() {}

    public static final ChatFormatting CREATOR = ChatFormatting.DARK_RED;
    public static final ChatFormatting GOLDEN = ChatFormatting.YELLOW;

    // Used for our own menu title only (drawn by us, so RGB is safe there)
    private static final int[] PALETTE = {0xE69500, 0xFFC400, 0xFFE680, 0xFFC400};

    public static int goldAt(int i, long timeMs) {
        double t = (timeMs / 1600.0) - i * 0.09;
        double pos = (t - Math.floor(t)) * PALETTE.length;
        int a = (int) Math.floor(pos) % PALETTE.length;
        int b = (a + 1) % PALETTE.length;
        double f = pos - Math.floor(pos);
        int c1 = PALETTE[a], c2 = PALETTE[b];
        int r = (int) Math.round(((c1 >> 16) & 0xFF) * (1 - f) + ((c2 >> 16) & 0xFF) * f);
        int g = (int) Math.round(((c1 >> 8) & 0xFF) * (1 - f) + ((c2 >> 8) & 0xFF) * f);
        int bl = (int) Math.round((c1 & 0xFF) * (1 - f) + (c2 & 0xFF) * f);
        return (r << 16) | (g << 8) | bl;
    }

    public static MutableComponent gold(String text, long timeMs) {
        MutableComponent out = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            out.append(Component.literal(String.valueOf(text.charAt(i))).withStyle(Style.EMPTY.withColor(goldAt(i, timeMs))));
        }
        return out;
    }

    /** Which names to restyle right now, and how. Keys are lowercase names. */
    public static Map<String, UnaryOperator<Style>> currentRules(Guard guard, Verified verified) {
        Map<String, UnaryOperator<Style>> rules = new HashMap<>();
        if (verified != null) {
            for (Map.Entry<String, Verified.Status> e : verified.all().entrySet()) {
                switch (e.getValue()) {
                    case VERIFIED -> rules.put(e.getKey(), s -> s.withColor(GOLDEN));
                    case FAILED -> rules.put(e.getKey(), s -> s.withStrikethrough(true));
                    default -> { }
                }
            }
        }
        String me = Minecraft.getInstance().getUser().getName();
        if (me != null && guard != null && guard.enforcing() && guard.glowEnabled()) {
            String failed = guard.localFailure();
            if (failed != null && !guard.isDev()) rules.put(me.toLowerCase(Locale.ROOT), s -> s.withStrikethrough(true));
            else rules.put(me.toLowerCase(Locale.ROOT), s -> s.withColor(GOLDEN));
        }
        // The creator is always dark red
        rules.put(Dev.NAME, s -> s.withColor(CREATOR));
        return rules;
    }

    private static boolean isNameChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** Restyles every whole-word occurrence of the given names. Returns the input unchanged if none appear. */
    public static Component decorate(Component in, Map<String, UnaryOperator<Style>> rules) {
        if (in == null || rules.isEmpty()) return in;
        String plain = in.getString().toLowerCase(Locale.ROOT);
        boolean any = false;
        for (String n : rules.keySet()) {
            if (plain.contains(n)) { any = true; break; }
        }
        if (!any) return in;

        MutableComponent out = Component.empty();
        in.visit((style, text) -> {
            String lower = text.toLowerCase(Locale.ROOT);
            int i = 0;
            int plainStart = 0;
            while (i < text.length()) {
                String hit = null;
                if (i == 0 || !isNameChar(text.charAt(i - 1))) {
                    for (String n : rules.keySet()) {
                        int end = i + n.length();
                        if (lower.startsWith(n, i) && (end == text.length() || !isNameChar(text.charAt(end)))) {
                            hit = n;
                            break;
                        }
                    }
                }
                if (hit != null) {
                    if (i > plainStart) out.append(Component.literal(text.substring(plainStart, i)).withStyle(style));
                    out.append(Component.literal(text.substring(i, i + hit.length())).withStyle(rules.get(hit).apply(style)));
                    i += hit.length();
                    plainStart = i;
                } else {
                    i++;
                }
            }
            if (plainStart < text.length()) out.append(Component.literal(text.substring(plainStart)).withStyle(style));
            return Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    public static Component decorate(Component in) {
        return decorate(in, GoldenMan.nameRules());
    }
}
