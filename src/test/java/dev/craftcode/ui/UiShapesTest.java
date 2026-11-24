package dev.craftcode.ui;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class UiShapesTest {
    @Test void cornerInsetsMatchPixelCurvesAndClampForSmallBoxes() {
        assertArrayEquals(new int[0], UiShapes.cornerInsets(UiShapes.R_NONE, 40, 20));
        assertArrayEquals(new int[] {1}, UiShapes.cornerInsets(UiShapes.R_CHIP, 40, 20));
        assertArrayEquals(new int[] {2, 1}, UiShapes.cornerInsets(UiShapes.R_CONTROL, 40, 20));
        assertArrayEquals(new int[] {3, 1, 1}, UiShapes.cornerInsets(UiShapes.R_PANEL, 40, 20));

        // Tiny dimensions step down gracefully without overlapping or inverting corners
        assertArrayEquals(new int[0], UiShapes.cornerInsets(UiShapes.R_PANEL, 3, 20));
        assertArrayEquals(new int[] {1}, UiShapes.cornerInsets(UiShapes.R_PANEL, 5, 20));
        assertArrayEquals(new int[] {2, 1}, UiShapes.cornerInsets(UiShapes.R_PANEL, 8, 8));
        for (int r = 0; r <= 5; r++) {
            for (int w = 0; w <= 16; w++) {
                for (int h = 0; h <= 16; h++) {
                    int[] insets = UiShapes.cornerInsets(r, w, h);
                    assertTrue(insets.length * 2 <= h, "rows must fit height w=" + w + " h=" + h);
                    for (int i = 0; i < insets.length; i++) {
                        assertTrue(insets[i] * 2 < w, "inset must stay strictly inside width w=" + w);
                        if (i > 0) assertTrue(insets[i] <= insets[i - 1], "insets must be monotonic");
                    }
                }
            }
        }
    }

    @Test void stripLeadingPlusPreservesLocalizedLabelsWithoutDuplicatingGlyph() {
        assertEquals("New thread", WorkspaceScreen.stripLeadingPlus("+  New thread"));
        assertEquals("Новый чат", WorkspaceScreen.stripLeadingPlus("+  Новый чат"));
        assertEquals("Add server", WorkspaceScreen.stripLeadingPlus("+ Add server"));
        assertEquals("Cancel", WorkspaceScreen.stripLeadingPlus("Cancel"));
    }

    @Test void stripTrailingCaretPreservesLocalizedScrollMoreLabels() {
        assertEquals("scroll for more", QuestionPanel.stripTrailingCaret("scroll for more ▾"));
        assertEquals("прокрутите дальше", QuestionPanel.stripTrailingCaret("прокрутите дальше ▾"));
        assertEquals("plain label", QuestionPanel.stripTrailingCaret("plain label"));
    }

    @Test void builtInConnectionMarksAreRecognizedWhileCustomTextFallsBack() {
        for (String mark : List.of("★", "◆", "●", "▲", "■", "♥", "☀")) {
            assertTrue(UiIcons.isConnectionMark(mark), "expected built-in mark: " + mark);
        }
        assertFalse(UiIcons.isConnectionMark(""));
        assertFalse(UiIcons.isConnectionMark(null));
        assertFalse(UiIcons.isConnectionMark("⚡"));
        assertFalse(UiIcons.isConnectionMark("A"));
    }

    @Test void bundledGuiIconsMatchNativePixelDimensions() throws IOException {
        assertPngSize("/assets/craftcode/textures/gui/sprites/composer/paperclip.png", 8, 8);
        assertPngSize("/assets/craftcode/textures/gui/sprites/thread/snooze.png", 7, 7);
        assertPngSize("/assets/craftcode/textures/gui/sprites/thread/settle.png", 7, 7);
        for (String provider : List.of("codex", "claude", "opencode", "antigravity", "cursor")) {
            assertPngSize("/assets/t3mc/textures/gui/providers/" + provider + ".png",
                    ProviderIcon.SIZE, ProviderIcon.SIZE);
        }
    }

    private static void assertPngSize(String resource, int expectedW, int expectedH) throws IOException {
        try (var stream = UiShapesTest.class.getResourceAsStream(resource)) {
            assertNotNull(stream, "missing icon asset " + resource);
            var image = ImageIO.read(stream);
            assertNotNull(image, "unreadable icon asset " + resource);
            assertEquals(expectedW, image.getWidth(), resource + " width");
            assertEquals(expectedH, image.getHeight(), resource + " height");
            boolean hasVisiblePixel = false;
            for (int y = 0; y < image.getHeight() && !hasVisiblePixel; y++) {
                for (int x = 0; x < image.getWidth(); x++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        hasVisiblePixel = true;
                        break;
                    }
                }
            }
            assertTrue(hasVisiblePixel, resource + " must contain visible pixels");
        }
    }
}
