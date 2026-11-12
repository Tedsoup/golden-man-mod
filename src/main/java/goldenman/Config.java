package goldenman;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

/** Saved settings in config/goldenman.properties. */
public final class Config {
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("goldenman.properties");
    private final Properties props = new Properties();

    public void load() {
        if (!Files.exists(FILE)) return;
        try (InputStream in = Files.newInputStream(FILE)) {
            props.load(in);
        } catch (IOException e) {
            GoldenMan.LOGGER.error("Could not read {}", FILE, e);
        }
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (OutputStream out = Files.newOutputStream(FILE)) {
                props.store(out, "Golden Man settings");
            }
        } catch (IOException e) {
            GoldenMan.LOGGER.error("Could not write {}", FILE, e);
        }
    }

    private Set<String> getSet(String key) {
        String raw = props.getProperty(key, "");
        if (raw.isBlank()) return new LinkedHashSet<>();
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private void putSet(String key, Set<String> values) {
        if (values.isEmpty()) props.remove(key);
        else props.setProperty(key, String.join(",", values));
        save();
    }

    // --- Golden Man profiles ---

    public boolean isEnrolled(String profileId) {
        return profileId != null && getSet("enrolledProfiles").contains(profileId);
    }

    public void setEnrolled(String profileId, boolean enrolled) {
        Set<String> set = getSet("enrolledProfiles");
        if (enrolled) set.add(profileId);
        else set.remove(profileId);
        putSet("enrolledProfiles", set);
    }

    public Set<String> enrolledProfiles() {
        return Collections.unmodifiableSet(getSet("enrolledProfiles"));
    }

    // --- co-op members (per profile) ---

    public Set<String> coopMembers(String profileId) {
        return getSet("coop." + profileId);
    }

    public Set<String> coopMembersLower(String profileId) {
        return coopMembers(profileId).stream()
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    public boolean addCoopMember(String profileId, String name) {
        Set<String> set = coopMembers(profileId);
        boolean exists = set.stream().anyMatch(s -> s.equalsIgnoreCase(name));
        if (exists) return false;
        set.add(name);
        putSet("coop." + profileId, set);
        return true;
    }

    public boolean removeCoopMember(String profileId, String name) {
        Set<String> set = coopMembers(profileId);
        boolean removed = set.removeIf(s -> s.equalsIgnoreCase(name));
        if (removed) putSet("coop." + profileId, set);
        return removed;
    }

    // --- local check failures (per profile) ---

    public String failure(String profileId) {
        return profileId == null ? null : props.getProperty("failed." + profileId);
    }

    public void setFailure(String profileId, String reason) {
        if (reason == null) props.remove("failed." + profileId);
        else props.setProperty("failed." + profileId, reason);
        save();
    }

    // --- verification list / registration ---

    /** GitHub repo (owner/name) that hosts players.json and the registration form. */
    public String repo() {
        return props.getProperty("repo", "tedsoup/GoldenMan").trim();
    }

    public String listUrl() {
        String r = repo();
        return r.isEmpty() ? "" : "https://raw.githubusercontent.com/" + r + "/main/players.json";
    }

    // --- name glow ---

    public boolean glow() {
        return Boolean.parseBoolean(props.getProperty("glow", "true"));
    }

    public void setGlow(boolean value) {
        props.setProperty("glow", Boolean.toString(value));
        save();
    }

    // --- debug ---

    public boolean debug() {
        return Boolean.parseBoolean(props.getProperty("debug", "false"));
    }

    public void setDebug(boolean value) {
        props.setProperty("debug", Boolean.toString(value));
        save();
    }
}
