package dev.craftcode.ui;

import dev.craftcode.ui.model.ColorTheme;

import java.util.Locale;

/** Parses and formats the {@code #RGB} / {@code #RRGGBB} colors typed in the theme editor. */
final class HexColor {
    private HexColor() {}

    // Transparent black is distinct from every valid, opaque ARGB color.
    static final int INVALID = 0;

    /** Number of editable colors in a {@link ColorTheme.Palette}, in record order. */
    static final int SLOTS = 12;

    /**
     * Parses {@code #RGB} or {@code #RRGGBB} (the {@code #} and surrounding blanks are optional, any letter case) into
     * an opaque ARGB color, or returns {@link #INVALID} when {@code text} is not one of those forms.
     */
    static int parse(String text) {
        if (text == null) return INVALID;
        String hex = text.strip();
        if (hex.startsWith("#")) hex = hex.substring(1);
        if (hex.length() != 3 && hex.length() != 6) return INVALID;
        for (int i = 0; i < hex.length(); i++) if (Character.digit(hex.charAt(i), 16) < 0) return INVALID;
        if (hex.length() == 3) {
            var wide = new StringBuilder(6);
            for (int i = 0; i < 3; i++) wide.append(hex.charAt(i)).append(hex.charAt(i));
            hex = wide.toString();
        }
        return 0xFF000000 | Integer.parseInt(hex, 16);
    }

    /** Formats the RGB part of {@code color} as {@code #RRGGBB} (upper case). */
    static String format(int color) {
        return String.format(Locale.ROOT, "#%06X", color & 0xFFFFFF);
    }

    /** Characters a hex field may hold while the player types or pastes; anything else is rejected outright. */
    static boolean allowedDraft(String text) {
        if (text.length() > 9) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '#' && c != ' ' && Character.digit(c, 16) < 0) return false;
        }
        return true;
    }

    static int channel(int color, int channel) {
        return (color >> (16 - channel * 8)) & 0xFF;
    }

    static int withChannel(int color, int channel, int value) {
        int shift = 16 - channel * 8;
        return 0xFF000000 | (color & ~(0xFF << shift) & 0xFFFFFF) | ((Math.clamp(value, 0, 255)) << shift);
    }

    static int[] toArray(ColorTheme.Palette p) {
        return new int[] {p.canvas(), p.sidebar(), p.panel(), p.input(), p.text(), p.muted(),
                p.border(), p.accent(), p.selected(), p.userBubble(), p.hover(), p.primaryText()};
    }

    static ColorTheme.Palette toPalette(int[] c) {
        return new ColorTheme.Palette(c[0], c[1], c[2], c[3], c[4], c[5], c[6], c[7], c[8], c[9], c[10], c[11]);
    }
}
