package dev.t3mc.ui.model;

import static dev.t3mc.L10n.tr;

/** T3 Code workspace colors converted from its built-in OKLCH presets to opaque sRGB. */
public enum ColorTheme {
    T3_CODE_DARK("T3 Code Dark", new Palette(
            0xFF0A0A0A, 0xFF000000, 0xFF111111, 0xFF1E1E1E, 0xFFF5F5F5, 0xFF818181,
            0xFF191919, 0xFF346BF1, 0xFF111111, 0xFF141414, 0xFF131313, 0xFFFFFFFF)),
    EMBER("Ember", new Palette(
            0xFF291E1A, 0xFF39281F, 0xFF433835, 0xFF3D332F, 0xFFFFFAFF, 0xFFA59996,
            0xFF664C3F, 0xFFF09A64, 0xFF5D3F2D, 0xFF704B34, 0xFF4F3528, 0xFF241523)),
    GROVE("Grove", new Palette(
            0xFF1B2821, 0xFF21362B, 0xFF36413C, 0xFF28342E, 0xFFFFFAFF, 0xFF9DA5A2,
            0xFF415F4F, 0xFF69D69A, 0xFF2F5641, 0xFF37664D, 0xFF2A4938, 0xFF241523)),
    OCEAN("Ocean", new Palette(
            0xFF17212B, 0xFF1E2D3B, 0xFF333B45, 0xFF252E38, 0xFFFFFAFF, 0xFF969CA6,
            0xFF405567, 0xFF70B9EE, 0xFF2F495F, 0xFF375871, 0xFF283E50, 0xFF241523)),
    IRIS("Iris", new Palette(
            0xFF1D1929, 0xFF272139, 0xFF383443, 0xFF2A2736, 0xFFFFFAFF, 0xFF9690A1,
            0xFF4D4366, 0xFF9D7DF2, 0xFF3F345E, 0xFF4B3D72, 0xFF352C4F, 0xFF241523)),
    T3_CHAT("T3 Chat", new Palette(
            0xFF1F1A24, 0xFF171018, 0xFF2C2631, 0xFF1F1A24, 0xFFF9F8FB, 0xFFE7D0DD,
            0xFF27242C, 0xFFA3004C, 0xFF261922, 0xFF2B2431, 0xFF261922, 0xFFFBD0E8)),
    LIGHT("Ember Light", new Palette(
            0xFFF9F7F5, 0xFFF3EAE5, 0xFFF1EFEE, 0xFFF3F1F0, 0xFF241523, 0xFF74686F,
            0xFFDDD2CE, 0xFFAE552A, 0xFFE5CCC0, 0xFFEBDAD1, 0xFFEAD8CF, 0xFFFFFAFF)),
    DARK("Classic Dark", new Palette(
            0xFF0D0E11, 0xFF131418, 0xFF181A1F, 0xFF121317, 0xFFE6E7EB, 0xFF8B8F99,
            0xFF262830, 0xFF7C9CFF, 0xFF2A3040, 0xFF1C2433, 0xFF22252C, 0xFF101421));

    public static final ColorTheme DEFAULT = T3_CODE_DARK;

    public record Palette(int canvas, int sidebar, int panel, int input, int text, int muted,
                          int border, int accent, int selected, int userBubble, int hover, int primaryText) {}

    private final String label;
    private final String translationKey;
    private final Palette palette;

    ColorTheme(String label, Palette palette) {
        this.label = label;
        this.translationKey = "theme." + name().toLowerCase(java.util.Locale.ROOT);
        this.palette = palette;
    }

    public String label() { return tr(translationKey, label); }
    public Palette palette() { return palette; }
}
