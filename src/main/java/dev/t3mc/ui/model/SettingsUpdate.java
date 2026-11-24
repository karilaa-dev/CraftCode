package dev.t3mc.ui.model;

import java.util.Objects;

/** A one-time connection code belongs only to this sign-in attempt. */
public record SettingsUpdate(String httpUrl, String connectionCode, NotificationMode notificationMode) {
    public SettingsUpdate {
        httpUrl = Objects.requireNonNullElse(httpUrl, "").strip();
        connectionCode = Objects.requireNonNullElse(connectionCode, "").strip();
        notificationMode = Objects.requireNonNullElse(notificationMode, NotificationMode.CHAT);
    }

    @Override public String toString() { return "T3 connection settings"; }
}
