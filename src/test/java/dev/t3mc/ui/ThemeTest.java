package dev.t3mc.ui;

import dev.t3mc.ui.model.ColorTheme;
import dev.t3mc.ui.model.ConnectionsView;
import dev.t3mc.ui.model.NotificationMode;
import dev.t3mc.ui.model.SettingsView;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ThemeTest {
    @Test void editingTheActiveCustomThemeRefreshesWidgetColors() {
        var ocean = custom(ColorTheme.OCEAN.palette());
        var light = custom(ColorTheme.LIGHT.palette());
        try {
            Theme.sync(ocean);
            assertEquals(ocean.effectivePalette().input(), Theme.INPUT);
            assertTrue(Theme.sync(light));
            assertTrue(Theme.isLight());
            assertEquals(light.effectivePalette().text(), Theme.TEXT);
            assertEquals(light.effectivePalette().accent(), Theme.ACCENT_DIM);
            assertFalse(Theme.sync(light));
        } finally {
            Theme.sync(SettingsView.defaults());
        }
    }

    @Test void snoozeCountdownRoundsUpAndHandlesMissingAndElapsedWakeTimes() {
        long now = 1_000;
        assertEquals("", Theme.timeUntil(0, now));
        assertEquals("now", Theme.timeUntil(now, now));
        assertEquals("1m", Theme.timeUntil(now + 1, now));
        assertEquals("1h", Theme.timeUntil(now + 3_600_000, now));
        assertEquals("7d", Theme.timeUntil(now + 7 * 86_400_000L - 1_000, now));
    }

    private static SettingsView custom(ColorTheme.Palette palette) {
        return new SettingsView("", false, NotificationMode.CHAT, ColorTheme.CUSTOM,
                SettingsView.DEFAULT_TRANSPARENCY, ConnectionsView.empty(), palette);
    }
}
