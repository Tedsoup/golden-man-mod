package goldenman;

import java.util.Locale;
import java.util.UUID;

/** The mod's creator. Shown in dark red, and exempt from verification checks (failures only show as alerts). */
public final class Dev {
    private Dev() {}

    public static final String NAME = "tedsoup";
    /** tedsoup's Minecraft UUID (dashless). */
    public static final String UUID_NODASH = "1452663962ef444181bcd4c37d9200cc";

    public static boolean isDev(UUID uuid, String name) {
        if (uuid != null && uuid.toString().replace("-", "").equalsIgnoreCase(UUID_NODASH)) return true;
        return name != null && name.toLowerCase(Locale.ROOT).equals(NAME);
    }

    public static boolean isDevName(String name) {
        return name != null && name.equalsIgnoreCase(NAME);
    }
}
