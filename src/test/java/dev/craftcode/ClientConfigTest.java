package dev.craftcode;

import dev.craftcode.ui.model.*;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ClientConfigTest {
    @TempDir Path dir;

    @Test void upgradesKeepTheirProfilesAndCachedImagesAndNewInstallsUseCraftCode() throws Exception {
        Path current = dir.resolve("craftcode.json");
        Path legacy = dir.resolve("t3mc.json");
        assertEquals(current, ClientConfig.instancePath(dir, ".json"));
        assertEquals(dir.resolve("craftcode-image-cache"), ClientConfig.instancePath(dir, "-image-cache"));
        var previous = ClientConfig.defaults().withConnection("https://example.org/mcp", NotificationMode.OFF)
                .withGrant("saved-test-grant").withTheme(ColorTheme.OCEAN);
        previous.save(legacy);
        Path cache = Files.createDirectory(dir.resolve("t3mc-image-cache"));
        assertEquals(previous, ClientConfig.load(ClientConfig.instancePath(dir, ".json")));
        assertEquals(cache, ClientConfig.instancePath(dir, "-image-cache"));
        ClientConfig.defaults().save(current);
        assertEquals(current, ClientConfig.instancePath(dir, ".json"));
    }

    @Test void stockDesktopPaletteIsFirstAndSavedPalettesKeepTheirNames() throws Exception {
        var path = dir.resolve("craftcode.json");
        assertEquals(ColorTheme.T3_CODE_DARK, ColorTheme.values()[0]);
        assertEquals(ColorTheme.T3_CODE_DARK, ClientConfig.load(path).theme());
        assertEquals(ColorTheme.T3_CODE_DARK, SettingsView.defaults().theme());

        ClientConfig.defaults().withTheme(ColorTheme.EMBER).save(path);
        assertEquals("EMBER", com.google.gson.JsonParser.parseString(Files.readString(path))
                .getAsJsonObject().get("theme").getAsString());
        assertEquals(ColorTheme.EMBER, ClientConfig.load(path).theme());
    }

    @Test void customColorsAreSeededFromThePresetAndSurviveSwitchingThemesAndProfiles() throws Exception {
        var path = dir.resolve("craftcode.json");
        var first = "https://first.example/mcp";
        var second = "https://second.example/mcp";
        var custom = new ColorTheme.Palette(0x010203, 0x111213, 0x212223, 0x313233, 0x414243, 0x515253,
                0x616263, 0x717273, 0x818283, 0x919293, 0xa1a2a3, 0xb1b2b3);
        assertEquals(0xff010203, custom.canvas());
        ClientConfig.defaults().withConnection(first, NotificationMode.OFF).withGrant("grant").save(path);
        try (var controller = new ClientController(path)) {
            assertTrue(controller.setTheme(ColorTheme.GROVE));
            var preset = controller.snapshot().settings();
            assertFalse(preset.hasCustomPalette());
            assertEquals(ColorTheme.GROVE.palette(), preset.customPalette());
            assertTrue(controller.setCustomTheme(custom));
            var active = controller.snapshot().settings();
            assertTrue(active.hasCustomPalette());
            assertEquals(ColorTheme.CUSTOM, active.theme());
            assertEquals(custom, active.effectivePalette());
            assertTrue(controller.setTheme(ColorTheme.OCEAN));
            assertEquals(ColorTheme.OCEAN.palette(), controller.snapshot().settings().effectivePalette());
            assertEquals(custom, controller.snapshot().settings().customPalette());
            assertTrue(controller.setTransparency(70));
            assertTrue(controller.toggleFavoriteModel("local", "model"));
        }
        var config = ClientConfig.load(path).withConnection(second, NotificationMode.CHAT)
                .withLinksExpanded(second, false).withLinked(second, false).withGrant("second grant").select(first).remove(second);
        config.save(path);
        try (var controller = new ClientController(path)) {
            var restored = controller.snapshot().settings();
            assertEquals(ColorTheme.OCEAN, restored.theme());
            assertTrue(restored.hasCustomPalette());
            assertEquals(custom, restored.customPalette());
            assertEquals(70, restored.transparency());
            assertTrue(controller.setTheme(ColorTheme.CUSTOM));
            assertEquals(custom, controller.snapshot().settings().effectivePalette());
        }
        assertEquals(custom, ClientConfig.load(path).view().effectivePalette());
    }

    @Test void incompleteCustomColorsRecoverWithoutLosingTheConnectionOrOtherSettings() throws Exception {
        var path = dir.resolve("craftcode.json");
        var config = ClientConfig.defaults().withConnection("https://first.example/mcp", NotificationMode.OFF)
                .withGrant("fixture-token").withTheme(ColorTheme.IRIS).withTheme(ColorTheme.CUSTOM);
        config.save(path);
        assertEquals(ColorTheme.IRIS.palette(), ClientConfig.load(path).view().effectivePalette());
        var valid = Files.readString(path);
        for (String replacement : List.of("null", "[]", "{}", "\"#fff\"", "1.5", "999999999999")) {
            var raw = JsonParser.parseString(valid).getAsJsonObject();
            raw.getAsJsonObject("customPalette").add("muted", JsonParser.parseString(replacement));
            Files.writeString(path, raw.toString());
            var recovered = ClientConfig.load(path);
            assertFalse(recovered.view().hasCustomPalette());
            assertEquals(ColorTheme.DEFAULT, recovered.theme());
            assertEquals("fixture-token", recovered.token());
            assertEquals(NotificationMode.OFF, recovered.notificationMode());
        }
    }

    @Test void aFailedCustomThemeSaveKeepsThePreviousSelectionAndColors() throws Exception {
        var path = dir.resolve("settings/craftcode.json");
        try (var controller = new ClientController(path)) {
            var original = controller.snapshot().settings();
            Files.writeString(path.getParent(), "fixture");
            assertFalse(controller.setCustomTheme(ColorTheme.OCEAN.palette()));
            assertEquals(original, controller.snapshot().settings());
        }
    }

    @Test void aSavedTokenStaysBoundToItsEndpoint() throws Exception {
        var path = dir.resolve("config/craftcode.json");
        var first = ClientConfig.defaults().withConnection("https://first.example/mcp", NotificationMode.POPUP).withGrant("fixture-token");
        first.save(path);
        var saved = ClientConfig.load(path);
        assertTrue(saved.view().hasSavedSession());
        assertFalse(saved.view().toString().contains("fixture-token"));
        assertEquals("fixture-token", saved.withConnection(saved.httpUrl(), NotificationMode.CHAT).token());
        assertEquals("", saved.withConnection("https://other.example/mcp", NotificationMode.POPUP).token());
        try (var controller = new ClientController(path)) {
            var paired = new SettingsUpdate("http://127.0.0.1:1", "single-use-code", NotificationMode.POPUP);
            assertFalse(paired.toString().contains("single-use-code"));
            controller.applySettings(paired);
            assertEquals("http://127.0.0.1:1/mcp", controller.snapshot().settings().httpUrl());
            assertFalse(controller.snapshot().settings().hasSavedSession());
            assertFalse(Files.readString(path).contains("single-use-code"));
        }
    }

    @Test void changingThemeOnALegacyConfigPreservesSignInAndWorksOffline() throws Exception {
        var path = dir.resolve("craftcode.json");
        Files.writeString(path, """
                {"transport":"HTTP","stdioCommand":"","stdioArgs":[],"httpUrl":"https://first.example/mcp",
                 "token":"fixture-token","notificationMode":"OFF"}
                """);
        try (var controller = new ClientController(path)) {
            assertEquals(ColorTheme.T3_CODE_DARK, controller.snapshot().settings().theme());
            assertEquals(35, controller.snapshot().settings().transparency());
            assertTrue(controller.setTransparency(65));
            controller.setTheme(ColorTheme.OCEAN);
            assertEquals(ColorTheme.OCEAN, controller.snapshot().settings().theme());
            assertEquals(ConnectionState.DISCONNECTED, controller.snapshot().connection().state());
        }
        var saved = ClientConfig.load(path);
        assertEquals(ColorTheme.OCEAN, saved.theme());
        assertEquals(65, saved.transparency());
        assertEquals(NotificationMode.OFF, saved.notificationMode());
        assertEquals("fixture-token", saved.token());
        assertFalse(Files.readString(path).contains("\"transport\""));
        assertEquals(ColorTheme.OCEAN, saved.withConnection(saved.httpUrl(), NotificationMode.POPUP).theme());
    }

    @Test void transparencySurvivesConnectionEditsNewGrantsAndFavorites() throws Exception {
        var path = dir.resolve("craftcode.json");
        try (var controller = new ClientController(path)) {
            assertEquals(35, controller.snapshot().settings().transparency());
            assertTrue(controller.setTransparency(75));
            assertEquals(75, controller.snapshot().settings().transparency());
            assertTrue(controller.toggleFavoriteModel("local", "model"));
        }
        var updated = ClientConfig.load(path).withConnection("https://example.com/mcp", NotificationMode.POPUP).withGrant("test");
        updated.save(path);
        try (var controller = new ClientController(path)) {
            var settings = controller.snapshot().settings();
            assertEquals(75, settings.transparency());
            assertTrue(settings.hasSavedSession());
            assertTrue(controller.isFavoriteModel("local", "model"));
            assertTrue(controller.setTransparency(150));
            assertEquals(100, controller.snapshot().settings().transparency());
            assertTrue(controller.setTransparency(-5));
            assertEquals(0, controller.snapshot().settings().transparency());
        }
    }

    @Test void invalidStoredTransparencyKeepsOtherPreferencesAndSignIn() throws Exception {
        var path = dir.resolve("craftcode.json");
        for (var entry : Map.of("125", 100, "-8", 0, "null", 35, "\"bad\"", 35, "[]", 35).entrySet()) {
            Files.writeString(path, """
                    {"transport":"MCP","httpUrl":"https://example.com/mcp","token":"fixture-token",
                     "notificationMode":"OFF","theme":"GROVE","transparency":%s}
                    """.formatted(entry.getKey()));
            var restored = ClientConfig.load(path);
            assertEquals(entry.getValue(), restored.view().transparency());
            assertEquals(ColorTheme.GROVE, restored.theme());
            assertEquals("fixture-token", restored.token());
        }
    }

    @Test void legacyStdioPreferencesMigrateWithoutStartingAProcess() throws Exception {
        var path = dir.resolve("old.json");
        Files.writeString(path, """
                {"transport":"STDIO","stdioCommand":"old-bridge","stdioArgs":[],"httpUrl":"https://old.example/mcp",
                 "token":"unrelated-login","notificationMode":"CHAT","theme":"OCEAN"}
                """);
        var settings = ClientConfig.load(path);
        assertEquals("", settings.httpUrl());
        assertEquals("", settings.token());
        assertEquals(ColorTheme.OCEAN, settings.theme());
        settings.save(path);
        assertFalse(Files.readString(path).contains("old-bridge"));
    }

    @Test void favoritesDistinguishTheSameModelOnDifferentProvidersAndSurviveReload() {
        var path = dir.resolve("config/craftcode.json");
        try (var controller = new ClientController(path)) {
            assertTrue(controller.toggleFavoriteModel("local", "shared"));
            assertTrue(controller.toggleFavoriteModel("remote", "shared"));
            assertTrue(controller.toggleFavoriteModel("local", "shared"));
            assertTrue(controller.setTheme(ColorTheme.GROVE));
        }
        try (var controller = new ClientController(path)) {
            assertFalse(controller.isFavoriteModel("local", "shared"));
            assertEquals(List.of(new ModelFavorite("remote", "shared")), controller.favoriteModels());
        }
    }

    @Test void bothPreviousConnectionModesKeepTheirLoginAndExplicitTransparency() throws Exception {
        var path = dir.resolve("previous.json");
        for (String mode : List.of("MCP", "LOCAL")) {
            String url = mode.equals("LOCAL") ? "http://192.168.1.8:3773" : "https://relay.example/mcp";
            Files.writeString(path, """
                    {"transport":"%s","httpUrl":"%s","token":"grant","notificationMode":"CHAT","transparency":0}
                    """.formatted(mode, url));
            var migrated = ClientConfig.load(path);
            assertEquals(0, migrated.transparency());
            assertEquals("grant", migrated.token());
            assertEquals(mode.equals("LOCAL") ? url + "/mcp" : url, migrated.httpUrl());
            migrated.save(path);
            assertFalse(Files.readString(path).contains("\"transport\""));
            assertEquals(migrated, ClientConfig.load(path));
        }
    }
}
