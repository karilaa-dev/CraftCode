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

    private static SettingsView custom(ColorTheme.Palette palette) {
        return new SettingsView("", false, NotificationMode.CHAT, ColorTheme.CUSTOM,
                SettingsView.DEFAULT_TRANSPARENCY, ConnectionsView.empty(), palette);
    }
}
