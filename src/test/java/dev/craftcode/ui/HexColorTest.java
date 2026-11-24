package dev.craftcode.ui;

import dev.craftcode.ui.model.ColorTheme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class HexColorTest {
    @Test void validWhiteAndBlackAreDistinctFromAnIncompleteEntry() {
        assertEquals(0xFFFFFFFF, HexColor.parse("#fff"));
        assertEquals(0xFF000000, HexColor.parse("#000000"));
        assertNotEquals(HexColor.parse("#FFFFFF"), HexColor.parse("#FF"));
        assertEquals("#AABBCC", HexColor.format(HexColor.parse(" abc ")));
    }

    @Test void theEditorRoundTripsEveryPresetWithAllTwelveColorsInOrder() {
        for (var theme : ColorTheme.values()) {
            assertEquals(theme.palette(), HexColor.toPalette(HexColor.toArray(theme.palette())));
        }
    }
}
