package dev.t3mc.ui;

import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Pixel-exact rounded-rectangle, hairline-border, pill, rail and shadow primitives.
 *
 * <p>Every fill is decomposed into non-overlapping horizontal strips so translucent surfaces (such as the
 * see-through workspace backdrop and transcript sheets) never double-blend at corners or edges.
 */
final class UiShapes {
    private UiShapes() {}

    /** Larger pixel arcs for the composer and its circular primary action. */
    static void roundSurface(SpruceGuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        int r = Math.min(radius, Math.min(w, h) / 2);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = (int) Math.round(r - Math.sqrt(r * r - dy * dy));
            g.fill(x + inset, y + row, x + w - inset, y + row + 1, color);
            g.fill(x + inset, y + h - row - 1, x + w - inset, y + h - row, color);
        }
        if (h > r * 2) g.fill(x, y + r, x + w, y + h - r, color);
    }

    static final int R_NONE = 0;
    static final int R_CHIP = 1;
    static final int R_CONTROL = 2;
    static final int R_PANEL = 3;

    /**
     * Horizontal inset (in GUI pixels from both left and right edges) for each corner scanline from the outer edge
     * inward. Clamped cleanly for small boxes so corners never invert or overlap.
     */
    static int[] cornerInsets(int radius, int width, int height) {
        int r = Math.clamp(radius, 0, R_PANEL);
        if (r == 0 || width < 4 || height < 4) return new int[0];
        if (r >= 3 && (width < 10 || height < 10)) r = 2;
        if (r >= 2 && (width < 6 || height < 6)) r = 1;
        return switch (r) {
            case 1 -> new int[] {1};
            case 2 -> new int[] {2, 1};
            default -> new int[] {3, 1, 1};
        };
    }

    /** Fills a rounded rectangle using non-overlapping horizontal strips (safe for translucent colors). */
    static void fillRounded(SpruceGuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        int[] insets = cornerInsets(radius, w, h);
        if (insets.length == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int n = insets.length;
        for (int i = 0; i < n; i++) {
            int in = insets[i];
            g.fill(x + in, y + i, x + w - in, y + i + 1, color);
        }
        if (h > n * 2) {
            g.fill(x, y + n, x + w, y + h - n, color);
        }
        for (int i = 0; i < n; i++) {
            int in = insets[i];
            int rowY = y + h - 1 - i;
            if (rowY >= y + n) {
                g.fill(x + in, rowY, x + w - in, rowY + 1, color);
            }
        }
    }

    /** {@link GuiGraphicsExtractor} overload for {@code Screen.extractBackground}. */
    static void fillRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        int[] insets = cornerInsets(radius, w, h);
        if (insets.length == 0) {
            g.fill(x, y, x + w, y + h, color);
            return;
        }
        int n = insets.length;
        for (int i = 0; i < n; i++) {
            int in = insets[i];
            g.fill(x + in, y + i, x + w - in, y + i + 1, color);
        }
        if (h > n * 2) {
            g.fill(x, y + n, x + w, y + h - n, color);
        }
        for (int i = 0; i < n; i++) {
            int in = insets[i];
            int rowY = y + h - 1 - i;
            if (rowY >= y + n) {
                g.fill(x + in, rowY, x + w - in, rowY + 1, color);
            }
        }
    }

    /**
     * Draws a crisp 1px border matching {@link #fillRounded}'s contour with zero overlapping corner pixels.
     */
    static void outlineRounded(SpruceGuiGraphics g, int x, int y, int w, int h, int radius, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        int[] insets = cornerInsets(radius, w, h);
        if (insets.length == 0) {
            Theme.outline(g, x, y, w, h, color);
            return;
        }
        int n = insets.length;
        // Top and bottom straight spans
        int topIn = insets[0];
        g.fill(x + topIn, y, x + w - topIn, y + 1, color);
        g.fill(x + topIn, y + h - 1, x + w - topIn, y + h, color);
        // Corner diagonal steps (top and bottom)
        for (int i = 1; i < n; i++) {
            int in = insets[i];
            int prev = insets[i - 1];
            int span = Math.max(1, prev - in);
            g.fill(x + in, y + i, x + in + span, y + i + 1, color);
            g.fill(x + w - in - span, y + i, x + w - in, y + i + 1, color);
            int by = y + h - 1 - i;
            g.fill(x + in, by, x + in + span, by + 1, color);
            g.fill(x + w - in - span, by, x + w - in, by + 1, color);
        }
        // Left and right vertical spans
        if (h > n * 2) {
            g.fill(x, y + n, x + 1, y + h - n, color);
            g.fill(x + w - 1, y + n, x + w, y + h - n, color);
        }
    }

    /** {@link GuiGraphicsExtractor} overload for {@code Screen.extractBackground}. */
    static void outlineRounded(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int color) {
        if ((color >>> 24) == 0 || w <= 0 || h <= 0) return;
        int[] insets = cornerInsets(radius, w, h);
        if (insets.length == 0) {
            g.fill(x, y, x + w, y + 1, color);
            g.fill(x, y + h - 1, x + w, y + h, color);
            g.fill(x, y + 1, x + 1, y + h - 1, color);
            g.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
            return;
        }
        int n = insets.length;
        int topIn = insets[0];
        g.fill(x + topIn, y, x + w - topIn, y + 1, color);
        g.fill(x + topIn, y + h - 1, x + w - topIn, y + h, color);
        for (int i = 1; i < n; i++) {
            int in = insets[i];
            int prev = insets[i - 1];
            int span = Math.max(1, prev - in);
            g.fill(x + in, y + i, x + in + span, y + i + 1, color);
            g.fill(x + w - in - span, y + i, x + w - in, y + i + 1, color);
            int by = y + h - 1 - i;
            g.fill(x + in, by, x + in + span, by + 1, color);
            g.fill(x + w - in - span, by, x + w - in, by + 1, color);
        }
        if (h > n * 2) {
            g.fill(x, y + n, x + 1, y + h - n, color);
            g.fill(x + w - 1, y + n, x + w, y + h - n, color);
        }
    }

    /**
     * Draws a sculpted rounded surface: fill + optional 1px inner top sheen + 1px continuous border.
     */
    static void panel(SpruceGuiGraphics g, int x, int y, int w, int h, int radius, int fill, int border, boolean sheen) {
        fillRounded(g, x, y, w, h, radius, fill);
        if (sheen && w > 6 && h > 4) {
            int inset = Math.max(2, radius);
            g.fill(x + inset, y + 1, x + w - inset, y + 2, Theme.HIGHLIGHT_TOP);
        }
        if ((border >>> 24) != 0) {
            outlineRounded(g, x, y, w, h, radius, border);
        }
    }

    /** {@link GuiGraphicsExtractor} overload for screen backgrounds. */
    static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius, int fill, int border, boolean sheen) {
        fillRounded(g, x, y, w, h, radius, fill);
        if (sheen && w > 6 && h > 4) {
            int inset = Math.max(2, radius);
            g.fill(x + inset, y + 1, x + w - inset, y + 2, Theme.HIGHLIGHT_TOP);
        }
        if ((border >>> 24) != 0) {
            outlineRounded(g, x, y, w, h, radius, border);
        }
    }

    /** Draws a soft two-step drop shadow under a floating popover or modal card. */
    static void dropShadow(SpruceGuiGraphics g, int x, int y, int w, int h, int radius) {
        int outer = Theme.isLight() ? 0x18000000 : 0x30000000;
        int inner = Theme.isLight() ? 0x24000000 : 0x48000000;
        fillRounded(g, x + 2, y + 3, w, h, radius, outer);
        fillRounded(g, x + 1, y + 2, w, h, radius, inner);
    }

    /** {@link GuiGraphicsExtractor} overload for modal screen backgrounds. */
    static void dropShadow(GuiGraphicsExtractor g, int x, int y, int w, int h, int radius) {
        int outer = Theme.isLight() ? 0x18000000 : 0x30000000;
        int inner = Theme.isLight() ? 0x24000000 : 0x48000000;
        fillRounded(g, x + 2, y + 3, w, h, radius, outer);
        fillRounded(g, x + 1, y + 2, w, h, radius, inner);
    }

    /** Draws a 2px-wide vertical accent rail with softened caps. */
    static void accentRail(SpruceGuiGraphics g, int x, int y, int h, int color) {
        if (h <= 0) return;
        if (h >= 6) {
            g.fill(x, y + 1, x + 2, y + h - 1, color);
        } else {
            g.fill(x, y, x + 2, y + h, color);
        }
    }

    /** Draws a compact pill badge (1px softened corners) with fill and optional hairline border. */
    static void pill(SpruceGuiGraphics g, int x, int y, int w, int h, int fill, int border) {
        fillRounded(g, x, y, w, h, R_CHIP, fill);
        if ((border >>> 24) != 0) {
            outlineRounded(g, x, y, w, h, R_CHIP, border);
        }
    }
}
