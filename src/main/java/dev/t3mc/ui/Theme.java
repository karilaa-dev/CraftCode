package dev.t3mc.ui;

import static dev.t3mc.L10n.tr;

import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ColorTheme;
import dev.t3mc.ui.model.ConnectionState;
import dev.t3mc.ui.model.SettingsView;
import dev.t3mc.ui.model.ThreadStatus;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import org.jetbrains.annotations.Nullable;

/** Shared, theme-switchable palette (ARGB) and small drawing helpers for the T3 screens. */
final class Theme {
    private Theme() {}

    // Live palette. Mutable so every widget reads the active theme; switch with apply()/sync().
    // BG and SIDEBAR are the broad backdrop; their alpha follows the transparency setting exactly (100% = clear).
    // TEXT_PLATE / SIDEBAR_PLATE back loose text on them so it stays legible; PANEL_SURFACE is for popups/panels,
    // which stay mostly solid. PANEL remains opaque for controls and cards.
    static int BG, SIDEBAR, TEXT_PLATE, SIDEBAR_PLATE, PANEL_SURFACE, BORDER, PANEL, INPUT, HOVER, SELECTED, SELECTED_HOVER, TEXT, TEXT_SOFT, MUTED, FAINT;
    static int ACCENT, ACCENT_DIM, ACCENT_HOVER, ON_ACCENT, ASSISTANT, USER_BG, USER_TEXT, USER_LABEL, SYSTEM_BG, CODE;
    /** Transcript extras: link ink, fenced-code backing (opaque), thin rules and the work-log guide line. */
    static int LINK, CODE_BG, RULE;
    /**
     * Group surfaces behind readable content (a transcript turn, a sidebar section). Same alpha math as the plates, but
     * drawn once per group as one continuous sheet instead of per line, so there are no bands or stacked layers.
     */
    static int SHEET, SIDEBAR_SHEET;
    static int GREEN, AMBER, AMBER_BG, AMBER_BORDER, RED, DANGER, DANGER_HOVER;
    static int BUTTON, BUTTON_HOVER, BUTTON_DISABLED, BUTTON_TEXT, TEXT_DISABLED, SCROLLBAR, SCROLLBAR_HOT;
    private static int[] BADGES = new int[0];
    private static boolean light;
    private static @Nullable ColorTheme current;
    private static ColorTheme.Palette currentPalette;
    private static int transparency = SettingsView.DEFAULT_TRANSPARENCY;
    private static int canvasRgb, sidebarRgb, panelRgb;

    /** Transparency steps offered by the settings slider. */
    static final int TRANSPARENCY_STEP = 5; // 5% steps: 0, 5, …, 35 (the default), …, 100

    static {
        apply(ColorTheme.DEFAULT, ColorTheme.DEFAULT.palette());
    }

    /** A custom palette can change while the selected theme stays Custom. */
    static boolean sync(SettingsView settings) {
        var palette = settings.effectivePalette();
        if (settings.theme() == current && palette.equals(currentPalette)) return false;
        apply(settings.theme(), palette);
        return true;
    }

    static ColorTheme current() {
        return current == null ? ColorTheme.DEFAULT : current;
    }

    static boolean isLight() {
        return light;
    }

    /** Derives every UI color from the theme's base palette so all screens and controls follow it. */
    private static void apply(ColorTheme theme, ColorTheme.Palette p) {
        current = theme;
        currentPalette = p;
        int canvas = opaque(p.canvas());
        int panel = opaque(p.panel());
        int text = opaque(p.text());
        light = luminance(canvas) > 0.5;
        int ink = light ? 0xFF000000 : 0xFFFFFFFF;

        canvasRgb = canvas & 0xFFFFFF;
        sidebarRgb = p.sidebar() & 0xFFFFFF;
        panelRgb = panel & 0xFFFFFF;
        surfaces();
        BORDER = opaque(p.border());
        PANEL = panel;
        INPUT = opaque(p.input());
        HOVER = opaque(p.hover());
        SELECTED = opaque(p.selected());
        SELECTED_HOVER = mix(SELECTED, text, 0.10f);
        TEXT = text;
        TEXT_SOFT = mix(text, canvas, 0.14f);
        MUTED = opaque(p.muted());
        FAINT = readable(mix(MUTED, canvas, 0.25f), MUTED, opaque(p.sidebar()), 3.0);

        ACCENT = towardContrast(opaque(p.accent()), text, canvas, 3.0);
        ACCENT_DIM = opaque(p.accent());
        ACCENT_HOVER = mix(ACCENT_DIM, ink, 0.14f);
        ON_ACCENT = readable(opaque(p.primaryText()), contrast(0xFFFFFFFF, ACCENT_DIM) >= contrast(0xFF111111, ACCENT_DIM)
                ? 0xFFFFFFFF : 0xFF111111, ACCENT_DIM, 4.0);
        ASSISTANT = ACCENT;
        USER_BG = opaque(p.userBubble());
        int bubbleInk = contrast(text, USER_BG) >= contrast(opaque(p.primaryText()), USER_BG) ? text : opaque(p.primaryText());
        USER_TEXT = readable(bubbleInk, luminance(USER_BG) > 0.4 ? 0xFF111111 : 0xFFFFFFFF, USER_BG, 4.5);
        USER_LABEL = readable(ACCENT, mix(USER_TEXT, USER_BG, 0.3f), USER_BG, 3.0);
        SYSTEM_BG = mix(canvas, panel, 0.6f);
        int linkInk = light ? 0xFF1758A1 : 0xFF8DCDFF;
        LINK = towardContrast(towardContrast(linkInk, text, canvas, 4.5), USER_TEXT, USER_BG, 4.5);
        CODE_BG = mix(canvas, panel, light ? 0.55f : 0.75f);
        RULE = mix(canvas, opaque(p.border()), 0.85f);

        GREEN = light ? 0xFF16803C : 0xFF6FD08C;
        AMBER = light ? 0xFFA85F00 : 0xFFF2C14E;
        RED = light ? 0xFFC62828 : 0xFFF08A80;
        CODE = light ? 0xFF1F7A3A : 0xFFB9D99A;
        AMBER_BG = mix(panel, AMBER, 0.12f);
        AMBER_BORDER = mix(panel, AMBER, 0.35f);
        DANGER = mix(panel, RED, light ? 0.18f : 0.24f);
        DANGER_HOVER = mix(panel, RED, light ? 0.28f : 0.36f);

        BUTTON = mix(panel, text, light ? 0.07f : 0.08f);
        BUTTON_HOVER = mix(panel, text, light ? 0.14f : 0.16f);
        BUTTON_DISABLED = mix(canvas, panel, 0.5f);
        BUTTON_TEXT = text;
        TEXT_DISABLED = mix(MUTED, canvas, 0.45f);
        SCROLLBAR = 0x50000000 | (text & 0xFFFFFF);
        SCROLLBAR_HOT = 0xC0000000 | (text & 0xFFFFFF);
        BADGES = light
                ? new int[] {0xFF2F5BD3, 0xFF16803C, 0xFFA85F00, 0xFF8E3FB8, 0xFFC0392B, 0xFF0F7C8C}
                : new int[] {0xFF5BC27A, 0xFFE6B450, 0xFFEF8A6B, 0xFFB48CE0, 0xFF6FB6E8, 0xFFE07BA0};
    }

    static int transparency() {
        return transparency;
    }

    /** True when the live game (or the title panorama) should show through the workspace surfaces. */
    static boolean seeThrough() {
        return transparency > 0;
    }

    /** Sets the backdrop transparency (0 = solid … 100 = clear backdrop). Returns true when it changed. */
    static boolean setTransparency(int percent) {
        int next = Math.clamp(percent, 0, 100);
        if (next == transparency) return false;
        transparency = next;
        surfaces();
        return true;
    }

    /** Combined backdrop + plate coverage kept behind loose text, so it stays readable over any world. */
    private static final float TEXT_COVERAGE = 0.82f;

    /**
     * The broad backdrop fades exactly with the setting: 0% is the theme's normal (opaque) canvas and sidebar, 100% is
     * fully clear. Text plates top the coverage behind loose text back up to {@link #TEXT_COVERAGE}; popups and panels
     * only fade to a mostly solid floor.
     */
    private static void surfaces() {
        float clear = 1 - transparency / 100f;
        int backdropAlpha = Math.round(255 * clear);
        BG = alpha(canvasRgb, backdropAlpha);
        SIDEBAR = alpha(sidebarRgb, backdropAlpha);
        TEXT_PLATE = alpha(canvasRgb, plateAlpha(backdropAlpha));
        SIDEBAR_PLATE = alpha(sidebarRgb, plateAlpha(backdropAlpha));
        SHEET = TEXT_PLATE;
        SIDEBAR_SHEET = SIDEBAR_PLATE;
        PANEL_SURFACE = alpha(panelRgb, Math.round(0xFF - (0xFF - 0xE0) * (transparency / 100f)));
    }

    /** Plate alpha that, drawn over a backdrop of {@code backdropAlpha}, reaches {@link #TEXT_COVERAGE} in total. */
    static int plateAlpha(int backdropAlpha) {
        float below = backdropAlpha / 255f;
        if (below >= TEXT_COVERAGE) return 0;
        return Math.round(255 * (1 - (1 - TEXT_COVERAGE) / (1 - below)));
    }

    /**
     * Backs a run of loose text with {@code plate} (TEXT_PLATE or SIDEBAR_PLATE) when the backdrop is see-through.
     * Draw it right before the text; it is a no-op while the backdrop already provides enough contrast.
     */
    static void plate(SpruceGuiGraphics graphics, int x, int y, int width, int lineHeight, int plate) {
        if (width > 0) fillPlate(graphics, x - 2, y - 1, x + width + 2, y + lineHeight, plate);
    }

    /** Fills a whole row/area with {@code plate}; a no-op while the backdrop is opaque enough on its own. */
    static void fillPlate(SpruceGuiGraphics graphics, int x0, int y0, int x1, int y1, int plate) {
        if ((plate >>> 24) != 0) graphics.fill(x0, y0, x1, y1, plate);
    }

    /**
     * Fills one coherent group surface (rect with 1px softened corners) in three non-overlapping strips, so a translucent
     * color is never layered over itself. A no-op for fully transparent colors (the backdrop already gives contrast).
     */
    static void sheet(SpruceGuiGraphics graphics, int x0, int y0, int x1, int y1, int color) {
        if ((color >>> 24) == 0 || x1 - x0 < 3 || y1 - y0 < 3) {
            if ((color >>> 24) != 0 && x1 > x0 && y1 > y0) graphics.fill(x0, y0, x1, y1, color);
            return;
        }
        graphics.fill(x0 + 1, y0, x1 - 1, y0 + 1, color);
        graphics.fill(x0, y0 + 1, x1, y1 - 1, color);
        graphics.fill(x0 + 1, y1 - 1, x1 - 1, y1, color);
    }

    private static int alpha(int rgb, int a) {
        return (Math.clamp(a, 0, 255) << 24) | (rgb & 0xFFFFFF);
    }

    /**
     * Backdrop for a see-through screen: no vanilla blur or menu-background texture (both hide the world), only the
     * title panorama when no world is loaded. Subtitles are still flushed like {@code Screen.extractBackground} does.
     */
    static void seeThroughBackdrop(Minecraft minecraft, GuiGraphicsExtractor graphics, int width, int height) {
        if (minecraft.level == null) minecraft.gameRenderer.panorama().extractRenderState(graphics, width, height);
        minecraft.gui.hud.extractDeferredSubtitles();
    }

    private static int opaque(int color) {
        return 0xFF000000 | color;
    }

    private static double channel(int c) {
        double v = c / 255.0;
        return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    }

    static double luminance(int color) {
        return 0.2126 * channel((color >> 16) & 0xFF) + 0.7152 * channel((color >> 8) & 0xFF) + 0.0722 * channel(color & 0xFF);
    }

    static double contrast(int a, int b) {
        double la = luminance(a), lb = luminance(b);
        return (Math.max(la, lb) + 0.05) / (Math.min(la, lb) + 0.05);
    }

    /** Blends {@code color} toward {@code target} until it reaches {@code min} contrast against {@code bg}. */
    private static int towardContrast(int color, int target, int bg, double min) {
        for (float amount = 0; amount <= 1.0f; amount += 0.1f) {
            int candidate = mix(color, target, amount);
            if (contrast(candidate, bg) >= min) return candidate;
        }
        return target;
    }

    /** Returns {@code color}, or {@code fallback} when it does not reach {@code min} contrast against {@code bg}. */
    private static int readable(int color, int fallback, int bg, double min) {
        return contrast(color, bg) >= min ? color : fallback;
    }

    /** Stable accent color for a project badge. */
    static int badgeColor(String key) {
        return BADGES[Math.floorMod(key == null ? 0 : key.hashCode(), BADGES.length)];
    }

    /** Mixes {@code color} over {@code base} at {@code amount} (0..1); result is opaque. */
    static int mix(int base, int color, float amount) {
        int r = (int) (((base >> 16) & 0xFF) * (1 - amount) + ((color >> 16) & 0xFF) * amount);
        int g = (int) (((base >> 8) & 0xFF) * (1 - amount) + ((color >> 8) & 0xFF) * amount);
        int b = (int) ((base & 0xFF) * (1 - amount) + (color & 0xFF) * amount);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** Up to two letters naming a project, e.g. "minecraft-mcp" → "MM", "books" → "BS", "t3-mc" → "T3". */
    static String initials(String name) {
        String clean = name == null ? "" : name.strip();
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (String part : clean.split("[^\\p{L}\\p{N}]+")) if (!part.isEmpty()) parts.add(part);
        if (parts.isEmpty()) return "?";
        String first = parts.getFirst();
        String out;
        if (first.length() == 2 && parts.size() > 1) out = first;
        else if (parts.size() > 1) out = first.substring(0, 1) + parts.get(1).charAt(0);
        else if (first.length() > 1) out = first.substring(0, 1) + first.charAt(first.length() - 1);
        else out = first;
        return out.toUpperCase(java.util.Locale.ROOT);
    }

    static int statusColor(ThreadStatus status) {
        return switch (status) {
            case RUNNING -> ACCENT;
            case WAITING_FOR_INPUT -> AMBER;
            case COMPLETED -> GREEN;
            case ERROR -> RED;
            case IDLE -> FAINT;
        };
    }

    static String statusLabel(ThreadStatus status) {
        return switch (status) {
            case RUNNING -> tr("status.working", "Working");
            case WAITING_FOR_INPUT -> tr("status.needs_input", "Needs input");
            case COMPLETED -> tr("status.done", "Done");
            case ERROR -> tr("status.error", "Error");
            case IDLE -> tr("status.idle", "Idle");
        };
    }

    static int connectionColor(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> GREEN;
            case CONNECTING -> AMBER;
            case ERROR -> RED;
            case DISCONNECTED -> FAINT;
        };
    }

    static String connectionLabel(ConnectionState state) {
        return switch (state) {
            case CONNECTED -> tr("connection.connected", "Connected");
            case CONNECTING -> tr("connection.connecting", "Connecting…");
            case ERROR -> tr("connection.error", "Connection error");
            case DISCONNECTED -> tr("connection.disconnected", "Disconnected");
        };
    }

    /** Small filled dot; pulses when {@code pulse} is set. */
    static void dot(SpruceGuiGraphics graphics, int x, int y, int color, boolean pulse) {
        int c = color;
        if (pulse) {
            double t = (System.currentTimeMillis() % 1200) / 1200.0;
            int alpha = (int) (140 + 115 * Math.sin(t * Math.PI * 2));
            c = (Math.max(60, Math.min(255, alpha)) << 24) | (color & 0xFFFFFF);
        }
        graphics.fill(x, y + 1, x + 4, y + 3, c);
        graphics.fill(x + 1, y, x + 3, y + 4, c);
    }

    static void outline(SpruceGuiGraphics graphics, int x, int y, int w, int h, int color) {
        graphics.fill(x, y, x + w, y + 1, color);
        graphics.fill(x, y + h - 1, x + w, y + h, color);
        graphics.fill(x, y + 1, x + 1, y + h - 1, color);
        graphics.fill(x + w - 1, y + 1, x + w, y + h - 1, color);
    }

    static String ellipsize(Font font, String text, int width) {
        String single = text.replace('\n', ' ');
        if (width <= 0) return "";
        if (font.width(single) <= width) return single;
        return font.plainSubstrByWidth(single, Math.max(0, width - font.width("…"))) + "…";
    }

    static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static String relativeTime(long millis) {
        if (millis <= 0) return "";
        long seconds = Math.max(0, (System.currentTimeMillis() - millis) / 1000);
        if (seconds < 60) return tr("time.now", "now");
        if (seconds < 3600) return tr("time.minutes", "%sm", seconds / 60);
        if (seconds < 86400) return tr("time.hours", "%sh", seconds / 3600);
        return tr("time.days", "%sd", seconds / 86400);
    }
}
