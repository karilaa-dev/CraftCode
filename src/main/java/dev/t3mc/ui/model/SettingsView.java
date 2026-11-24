package dev.t3mc.ui.model;

import java.util.Objects;

/** Settings as shown to the UI. Never carries the HTTP token itself, only whether one is stored. */
public record SettingsView(String httpUrl, boolean hasSavedSession,
                           NotificationMode notificationMode, ColorTheme theme, int transparency, ConnectionsView connections) {
    public static final int DEFAULT_TRANSPARENCY = 35;

    public SettingsView(String url, boolean saved, NotificationMode notifications, ColorTheme theme, int transparency) {
        this(url, saved, notifications, theme, transparency, ConnectionsView.empty());
    }

    public SettingsView {
        theme = Objects.requireNonNullElse(theme, ColorTheme.DEFAULT);
        httpUrl = Objects.requireNonNullElse(httpUrl, "");
        notificationMode = Objects.requireNonNullElse(notificationMode, NotificationMode.CHAT);
        transparency = Math.clamp(transparency, 0, 100);
    }

    public static SettingsView defaults() {
        return new SettingsView("", false, NotificationMode.CHAT, ColorTheme.DEFAULT, DEFAULT_TRANSPARENCY);
    }
}
