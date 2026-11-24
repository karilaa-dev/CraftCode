package dev.craftcode.ui.model;

import java.util.Objects;

/** Settings as shown to the UI. Never carries the HTTP token itself, only whether one is stored. */
public record SettingsView(String httpUrl, boolean hasSavedSession, NotificationMode notificationMode, ColorTheme theme,
                           int transparency, ConnectionsView connections, ColorTheme.Palette storedCustomPalette) {
    public static final int DEFAULT_TRANSPARENCY = 35;

    public SettingsView(String url, boolean saved, NotificationMode notifications, ColorTheme theme, int transparency) {
        this(url, saved, notifications, theme, transparency, ConnectionsView.empty());
    }

    public SettingsView(String url, boolean saved, NotificationMode notifications, ColorTheme theme, int transparency,
                        ConnectionsView connections) {
        this(url, saved, notifications, theme, transparency, connections, null);
    }

    public SettingsView {
        theme = Objects.requireNonNullElse(theme, ColorTheme.DEFAULT);
        httpUrl = Objects.requireNonNullElse(httpUrl, "");
        notificationMode = Objects.requireNonNullElse(notificationMode, NotificationMode.CHAT);
        transparency = Math.clamp(transparency, 0, 100);
    }

    public boolean hasCustomPalette() { return storedCustomPalette != null; }

    /** Opening the editor for the first time starts with the selected preset. */
    public ColorTheme.Palette customPalette() {
        return hasCustomPalette() ? storedCustomPalette : theme.palette();
    }

    public ColorTheme.Palette effectivePalette() {
        return theme == ColorTheme.CUSTOM ? customPalette() : theme.palette();
    }

    public static SettingsView defaults() {
        return new SettingsView("", false, NotificationMode.CHAT, ColorTheme.DEFAULT, DEFAULT_TRANSPARENCY);
    }
}
