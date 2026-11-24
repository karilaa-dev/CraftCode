package dev.t3mc.ui;

import dev.t3mc.ui.model.ColorTheme;
import dev.t3mc.ui.model.ConnectionsView;
import dev.t3mc.ui.model.NotificationMode;
import dev.t3mc.ui.model.SettingsView;
import dev.t3mc.ui.model.ThreadStatus;
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

    @Test void derivedElevationAndInteractionTokensArePopulatedForEveryTheme() {
        try {
            for (var theme : ColorTheme.values()) {
                var settings = new SettingsView("", false, NotificationMode.CHAT, theme,
                        SettingsView.DEFAULT_TRANSPARENCY, ConnectionsView.empty(), theme.palette());
                Theme.sync(settings);
                assertOpaque(Theme.SURFACE_RAISED, theme + " SURFACE_RAISED");
                assertOpaque(Theme.BORDER_SUBTLE, theme + " BORDER_SUBTLE");
                assertOpaque(Theme.BORDER_STRONG, theme + " BORDER_STRONG");
                assertOpaque(Theme.BUTTON_PRESSED, theme + " BUTTON_PRESSED");
                assertOpaque(Theme.ACCENT_PRESSED, theme + " ACCENT_PRESSED");
                assertOpaque(Theme.ACCENT_SOFT, theme + " ACCENT_SOFT");
                assertOpaque(Theme.DANGER_PRESSED, theme + " DANGER_PRESSED");
                assertOpaque(Theme.SELECTED_PRESSED, theme + " SELECTED_PRESSED");
                assertOpaque(Theme.USER_BORDER, theme + " USER_BORDER");
                assertOpaque(Theme.CODE_BORDER, theme + " CODE_BORDER");
                assertOpaque(Theme.CODE_HEADER, theme + " CODE_HEADER");
                assertTrue((Theme.HIGHLIGHT_TOP >>> 24) > 0 && (Theme.HIGHLIGHT_TOP >>> 24) < 0xFF);
                assertTrue((Theme.SHADOW_EDGE >>> 24) > 0 && (Theme.SHADOW_EDGE >>> 24) < 0xFF);
                for (var status : ThreadStatus.values()) {
                    assertOpaque(Theme.statusBg(status), theme + " statusBg " + status);
                }
            }
        } finally {
            Theme.sync(SettingsView.defaults());
        }
    }

    private static void assertOpaque(int argb, String name) {
        assertEquals(0xFF, argb >>> 24, name + " should be opaque");
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
