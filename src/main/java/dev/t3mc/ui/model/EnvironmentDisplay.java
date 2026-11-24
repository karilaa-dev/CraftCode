package dev.t3mc.ui.model;

/** Per-environment preferences, including the primary environment (empty environment id). */
public record EnvironmentDisplay(boolean hidden, String icon) {
    public static final EnvironmentDisplay DEFAULT = new EnvironmentDisplay(false, "");

    public EnvironmentDisplay {
        icon = icon == null ? "" : icon.strip();
    }
}
