package dev.t3mc;

import static dev.t3mc.L10n.tr;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.t3mc.auth.T3OAuth;
import dev.t3mc.ui.model.*;
import java.io.IOException;
import java.net.URI;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

/** Local to the Minecraft instance. Never serialized into a game packet. */
record ClientConfig(List<Profile> profiles, String activeProfileId, NotificationMode notificationMode, ColorTheme theme,
                    List<ModelFavorite> favoriteModels, int transparency, ColorTheme.Palette customPalette) {
    record Profile(String endpoint, String name, String token, boolean includeLinked, boolean linksExpanded) {
        @Override public String toString() { return name; }
    }

    ClientConfig {
        profiles = List.copyOf(profiles);
        favoriteModels = favoriteModels == null ? List.of() : List.copyOf(favoriteModels);
        if (theme == null || theme == ColorTheme.CUSTOM && customPalette == null) theme = ColorTheme.DEFAULT;
        transparency = Math.clamp(transparency, 0, 100);
    }

    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();

    static ClientConfig defaults() {
        return new ClientConfig(List.of(), "", NotificationMode.POPUP, ColorTheme.DEFAULT, List.of(), SettingsView.DEFAULT_TRANSPARENCY, null);
    }

    Profile profile(String id) { return profiles.stream().filter(p -> p.endpoint.equals(id)).findFirst().orElse(null); }
    String httpUrl() { return activeProfileId; }
    String token() { var p = profile(activeProfileId); return p == null ? "" : p.token; }
    SettingsView view() { return view(ConnectionsView.empty()); }
    SettingsView view(ConnectionsView connections) {
        return new SettingsView(httpUrl(), !token().isBlank(), notificationMode, theme, transparency, connections, customPalette);
    }

    ClientConfig withTheme(ColorTheme next) {
        var colors = next == ColorTheme.CUSTOM && customPalette == null ? theme.palette() : customPalette;
        return new ClientConfig(profiles, activeProfileId, notificationMode, next, favoriteModels, transparency, colors);
    }
    ClientConfig withCustomTheme(ColorTheme.Palette palette) {
        return new ClientConfig(profiles, activeProfileId, notificationMode, ColorTheme.CUSTOM, favoriteModels, transparency,
                Objects.requireNonNull(palette));
    }
    ClientConfig withTransparency(int next) {
        return new ClientConfig(profiles, activeProfileId, notificationMode, theme, favoriteModels, next, customPalette);
    }
    ClientConfig withFavorites(List<ModelFavorite> favorites) {
        return new ClientConfig(profiles, activeProfileId, notificationMode, theme, favorites, transparency, customPalette);
    }
    boolean includeLinked() { return includeLinked(activeProfileId); }
    boolean includeLinked(String id) {
        var p = profile(id);
        return p != null && p.includeLinked;
    }
    ClientConfig withLinked(boolean include) {
        return withLinked(activeProfileId, include);
    }
    ClientConfig withLinked(String profileId, boolean include) {
        var profile = profile(profileId);
        return profile == null ? this : withLinkSettings(profileId, include, include && profile.linksExpanded);
    }
    ClientConfig withLinksExpanded(String profileId, boolean expanded) {
        var profile = profile(profileId);
        return profile == null ? this : withLinkSettings(profileId, profile.includeLinked || expanded, expanded);
    }
    private ClientConfig withLinkSettings(String profileId, boolean include, boolean expanded) {
        var updated = profiles.stream().map(p -> p.endpoint.equals(profileId)
                ? new Profile(p.endpoint, p.name, p.token, include, expanded) : p).toList();
        return new ClientConfig(updated, activeProfileId, notificationMode, theme, favoriteModels, transparency, customPalette);
    }
    ClientConfig withGrant(String grant) { return withGrant(activeProfileId, grant); }
    ClientConfig withGrant(String profileId, String grant) {
        var updated = profiles.stream().map(p -> p.endpoint.equals(profileId) ? new Profile(p.endpoint, p.name, grant, p.includeLinked, p.linksExpanded) : p).toList();
        return new ClientConfig(updated, activeProfileId, notificationMode, theme, favoriteModels, transparency, customPalette);
    }
    ClientConfig withConnection(String endpoint, NotificationMode notifications) { return withProfile("", endpoint, notifications); }
    ClientConfig withProfile(String label, String endpoint, NotificationMode notifications) {
        var previous = profile(endpoint);
        String name = label == null ? "" : label.strip();
        if (name.isEmpty()) name = previous == null ? URI.create(endpoint).getAuthority() : previous.name;
        var next = new Profile(endpoint, name, previous == null ? "" : previous.token, previous == null || previous.includeLinked,
                previous == null || previous.linksExpanded);
        var entries = new ArrayList<>(profiles);
        if (previous == null) entries.add(next);
        else entries.set(entries.indexOf(previous), next);
        return new ClientConfig(entries, endpoint, notifications, theme, favoriteModels, transparency, customPalette);
    }
    ClientConfig select(String id) {
        return new ClientConfig(profiles, id, notificationMode, theme, favoriteModels, transparency, customPalette);
    }
    ClientConfig remove(String id) {
        var rest = profiles.stream().filter(p -> !p.endpoint.equals(id)).toList();
        String active = activeProfileId.equals(id) ? (rest.isEmpty() ? "" : rest.getFirst().endpoint) : activeProfileId;
        return new ClientConfig(rest, active, notificationMode, theme, favoriteModels, transparency, customPalette);
    }

    static ClientConfig load(Path path) throws IOException {
        if (!Files.exists(path)) return defaults();
        try (var input = Files.newBufferedReader(path)) {
            JsonObject raw = JsonParser.parseReader(input).getAsJsonObject();
            var mode = JSON.fromJson(raw.get("notificationMode"), NotificationMode.class);
            if (mode == null) throw new IOException(tr("config.incomplete", "Incomplete T3 settings file."));
            var theme = JSON.fromJson(raw.get("theme"), ColorTheme.class);
            var favorites = JSON.fromJson(raw.get("favoriteModels"), ModelFavorite[].class);
            var entries = new LinkedHashMap<String, Profile>();
            String active;
            if (raw.has("profiles")) {
                for (var item : raw.getAsJsonArray("profiles")) {
                    Profile p = JSON.fromJson(item, Profile.class);
                    String endpoint = savedEndpoint(p.endpoint, "");
                    if (endpoint.isEmpty() || p.token == null || entries.containsKey(endpoint)) continue;
                    String name = p.name == null || p.name.isBlank() ? URI.create(endpoint).getAuthority() : p.name;
                    var fields = item.getAsJsonObject();
                    boolean include = !fields.has("includeLinked") || p.includeLinked;
                    boolean expanded = include && (!fields.has("linksExpanded") || p.linksExpanded);
                    entries.put(endpoint, new Profile(endpoint, name, p.token, include, expanded));
                }
                active = savedEndpoint(raw.has("activeProfileId") ? raw.get("activeProfileId").getAsString() : "", "");
            } else {
                if (!raw.has("httpUrl") || !raw.has("token")) throw new IOException(tr("config.incomplete", "Incomplete T3 settings file."));
                String legacyMode = raw.has("transport") ? raw.get("transport").getAsString() : "";
                active = savedEndpoint(raw.get("httpUrl").getAsString(), legacyMode);
                if (!active.isEmpty()) entries.put(active, new Profile(active, URI.create(active).getAuthority(), raw.get("token").getAsString(), true, true));
            }
            if (!entries.containsKey(active)) active = entries.isEmpty() ? "" : entries.keySet().iterator().next();
            return new ClientConfig(List.copyOf(entries.values()), active, mode, theme,
                    favorites == null ? List.of() : List.of(favorites), readTransparency(raw), readCustomPalette(raw));
        } catch (RuntimeException e) { throw new IOException(tr("config.invalid", "Invalid T3 settings file."), e); }
    }

    private static ColorTheme.Palette readCustomPalette(JsonObject settings) {
        var field = settings.get("customPalette");
        if (field == null || !field.isJsonObject()) return null;
        var colors = field.getAsJsonObject();
        try {
            return new ColorTheme.Palette(readColor(colors, "canvas"), readColor(colors, "sidebar"),
                    readColor(colors, "panel"), readColor(colors, "input"), readColor(colors, "text"), readColor(colors, "muted"),
                    readColor(colors, "border"), readColor(colors, "accent"), readColor(colors, "selected"),
                    readColor(colors, "userBubble"), readColor(colors, "hover"), readColor(colors, "primaryText"));
        } catch (IllegalArgumentException | ArithmeticException invalidPalette) {
            return null;
        }
    }

    private static int readColor(JsonObject colors, String role) {
        var value = colors.get(role);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(role);
        return value.getAsBigDecimal().intValueExact();
    }

    /** Only migrate a saved grant with its own endpoint. An invite may point at a different host. */
    private static String savedEndpoint(String url, String legacyMode) {
        if (url.isBlank()) return "";
        try {
            var stored = URI.create(url);
            String path = stored.getRawPath();
            if (path == null || stored.getRawQuery() != null || stored.getRawFragment() != null) return "";
            boolean mcpPath = path.endsWith("/mcp") || path.endsWith("/mcp/");
            boolean supported = switch (legacyMode) {
                case "LOCAL" -> mcpPath || path.isEmpty() || path.equals("/");
                case "", "MCP", "HTTP" -> mcpPath;
                default -> false;
            };
            return supported ? T3OAuth.connectionEndpoint(url) : "";
        } catch (IOException | IllegalArgumentException invalidAddress) {
            return "";
        }
    }

    private static int readTransparency(JsonObject settings) {
        var value = settings.get("transparency");
        if (value == null || !value.isJsonPrimitive()) return SettingsView.DEFAULT_TRANSPARENCY;
        try {
            double percent = value.getAsDouble();
            return Double.isFinite(percent) ? (int) Math.clamp(percent, 0, 100) : SettingsView.DEFAULT_TRANSPARENCY;
        } catch (NumberFormatException ignored) { return SettingsView.DEFAULT_TRANSPARENCY; }
    }

    void save(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        Path temp = Files.createTempFile(path.getParent(), ".t3mc-", ".tmp");
        try {
            if (Files.getFileStore(temp).supportsFileAttributeView("posix"))
                Files.setPosixFilePermissions(temp, PosixFilePermissions.fromString("rw-------"));
            Files.writeString(temp, JSON.toJson(this));
            try { Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }

    @Override public String toString() { return "T3 client configuration"; }
}
