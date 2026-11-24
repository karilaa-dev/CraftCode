package dev.t3mc.ui;

import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ConnectionState;
import dev.t3mc.ui.model.ThreadStatus;
import net.minecraft.client.gui.Font;

/**
 * Crisp integer-pixel iconography drawn natively via {@link SpruceGuiGraphics#fill}.
 *
 * <p>Replaces inconsistent Unicode font glyphs ({@code ▾ ▸ ★ ☆ ✓ × ↶ ‹ › ◆ ● ▲ ■ ♥ ☀}) with pixel-aligned marks
 * that stay razor-sharp at every Minecraft GUI scale and automatically inherit any built-in or custom theme color.
 */
final class UiIcons {
    private UiIcons() {}

    /** 4×7 right-pointing chevron. */
    static void chevronRight(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
        g.fill(x + 2, y + 4, x + 3, y + 5, color);
        g.fill(x + 1, y + 5, x + 2, y + 6, color);
        g.fill(x, y + 6, x + 1, y + 7, color);
    }

    /** 4×7 left-pointing chevron. */
    static void chevronLeft(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 3, y, x + 4, y + 1, color);
        g.fill(x + 2, y + 1, x + 3, y + 2, color);
        g.fill(x + 1, y + 2, x + 2, y + 3, color);
        g.fill(x, y + 3, x + 1, y + 4, color);
        g.fill(x + 1, y + 4, x + 2, y + 5, color);
        g.fill(x + 2, y + 5, x + 3, y + 6, color);
        g.fill(x + 3, y + 6, x + 4, y + 7, color);
    }

    /** 7×4 downward chevron. */
    static void chevronDown(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
        g.fill(x + 6, y, x + 7, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 5, y + 1, x + 6, y + 2, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
        g.fill(x + 4, y + 2, x + 5, y + 3, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
    }

    /** 5×3 compact downward chevron (for tight chips like ModelChip). */
    static void caretDown(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 5, y + 1, color);
        g.fill(x + 1, y + 1, x + 4, y + 2, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
    }

    /** 3×5 compact right caret (for collapsed lists). */
    static void caretRight(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 5, color);
        g.fill(x + 1, y + 1, x + 2, y + 4, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
    }

    /** 7×7 plus sign. */
    static void plus(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 3, y, x + 4, y + 7, color);
        g.fill(x, y + 3, x + 7, y + 4, color);
    }

    /** 7×7 minus sign (matches {@link #plus} width). */
    static void minus(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y + 3, x + 7, y + 4, color);
    }

    /** 5×5 crisp close cross. */
    static void close(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 1, y + 1, color);
        g.fill(x + 4, y, x + 5, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 3, y + 1, x + 4, y + 2, color);
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
        g.fill(x, y + 4, x + 1, y + 5, color);
        g.fill(x + 4, y + 4, x + 5, y + 5, color);
    }

    /** 7×6 crisp checkmark. */
    static void check(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 6, y, x + 7, y + 1, color);
        g.fill(x + 5, y + 1, x + 6, y + 2, color);
        g.fill(x + 4, y + 2, x + 5, y + 3, color);
        g.fill(x, y + 3, x + 1, y + 4, color);
        g.fill(x + 3, y + 3, x + 4, y + 4, color);
        g.fill(x + 1, y + 4, x + 3, y + 5, color);
        g.fill(x + 2, y + 5, x + 3, y + 6, color);
    }

    /** 7×7 pixel star, either filled or outlined. */
    static void star(SpruceGuiGraphics g, int x, int y, int color, boolean filled) {
        if (filled) {
            g.fill(x + 3, y, x + 4, y + 5, color);
            g.fill(x + 2, y + 1, x + 5, y + 5, color);
            g.fill(x, y + 2, x + 7, y + 3, color);
            g.fill(x + 1, y + 3, x + 6, y + 4, color);
            g.fill(x + 1, y + 5, x + 3, y + 6, color);
            g.fill(x + 4, y + 5, x + 6, y + 6, color);
            g.fill(x + 1, y + 6, x + 2, y + 7, color);
            g.fill(x + 5, y + 6, x + 6, y + 7, color);
        } else {
            g.fill(x + 3, y, x + 4, y + 1, color);
            g.fill(x + 2, y + 1, x + 3, y + 2, color);
            g.fill(x + 4, y + 1, x + 5, y + 2, color);
            g.fill(x, y + 2, x + 2, y + 3, color);
            g.fill(x + 5, y + 2, x + 7, y + 3, color);
            g.fill(x + 1, y + 3, x + 2, y + 4, color);
            g.fill(x + 5, y + 3, x + 6, y + 4, color);
            g.fill(x + 2, y + 4, x + 3, y + 5, color);
            g.fill(x + 4, y + 4, x + 5, y + 5, color);
            g.fill(x + 1, y + 5, x + 2, y + 7, color);
            g.fill(x + 3, y + 5, x + 4, y + 6, color);
            g.fill(x + 5, y + 5, x + 6, y + 7, color);
        }
    }

    /** 7×7 magnifying glass for search fields. */
    static void search(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 1, y, x + 4, y + 1, color);
        g.fill(x, y + 1, x + 1, y + 4, color);
        g.fill(x + 4, y + 1, x + 5, y + 4, color);
        g.fill(x + 1, y + 4, x + 5, y + 5, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        g.fill(x + 6, y + 6, x + 7, y + 7, color);
    }

    /** 7×7 symmetric filter funnel. */
    static void filter(SpruceGuiGraphics g, int x, int y, int color, boolean filled) {
        g.fill(x, y, x + 7, y + 1, color);
        if (filled) {
            g.fill(x + 1, y + 1, x + 6, y + 2, color);
            g.fill(x + 2, y + 2, x + 5, y + 3, color);
        } else {
            g.fill(x + 1, y + 1, x + 2, y + 2, color);
            g.fill(x + 5, y + 1, x + 6, y + 2, color);
            g.fill(x + 2, y + 2, x + 3, y + 3, color);
            g.fill(x + 4, y + 2, x + 5, y + 3, color);
        }
        g.fill(x + 3, y + 3, x + 4, y + 7, color);
    }

    /** 7×7 pixel gear for Settings. */
    static void settings(SpruceGuiGraphics g, int x, int y, int color) {
        // Top and bottom teeth
        g.fill(x + 2, y, x + 3, y + 1, color);
        g.fill(x + 4, y, x + 5, y + 1, color);
        g.fill(x + 2, y + 6, x + 3, y + 7, color);
        g.fill(x + 4, y + 6, x + 5, y + 7, color);
        // Outer ring (hollow 3×3 core)
        g.fill(x + 1, y + 1, x + 6, y + 2, color);
        g.fill(x + 1, y + 5, x + 6, y + 6, color);
        g.fill(x + 1, y + 2, x + 2, y + 5, color);
        g.fill(x + 5, y + 2, x + 6, y + 5, color);
        // Left and right teeth
        g.fill(x, y + 2, x + 1, y + 3, color);
        g.fill(x + 6, y + 2, x + 7, y + 3, color);
        g.fill(x, y + 4, x + 1, y + 5, color);
        g.fill(x + 6, y + 4, x + 7, y + 5, color);
    }

    /** 8×8 bold upward arrow for the circular Send button. */
    static void send(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 3, y, x + 5, y + 8, color);
        g.fill(x + 2, y + 1, x + 6, y + 2, color);
        g.fill(x + 1, y + 2, x + 7, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 6, y + 3, x + 7, y + 4, color);
    }

    /** 6×6 crisp solid square for the circular Stop button. */
    static void stop(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 6, y + 6, color);
    }

    /** 8×2 animated three-dot wave for an in-flight Send request. */
    static void sendingDots(SpruceGuiGraphics g, int x, int y, int color) {
        int dim = (0x70 << 24) | (color & 0xFFFFFF);
        int phase = (int) ((System.currentTimeMillis() / 220L) % 3L);
        for (int i = 0; i < 3; i++) {
            g.fill(x + i * 3, y, x + i * 3 + 2, y + 2, i == phase ? color : dim);
        }
    }

    /** 8×7 crisp photo/image icon. */
    static void image(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y, x + 8, y + 1, color);
        g.fill(x, y + 6, x + 8, y + 7, color);
        g.fill(x, y + 1, x + 1, y + 6, color);
        g.fill(x + 7, y + 1, x + 8, y + 6, color);
        // Sun dot
        g.fill(x + 2, y + 2, x + 3, y + 3, color);
        // Mountain silhouette
        g.fill(x + 2, y + 5, x + 6, y + 6, color);
        g.fill(x + 3, y + 4, x + 6, y + 5, color);
        g.fill(x + 4, y + 3, x + 5, y + 4, color);
    }

    /** 7×7 curved return arrow (for Unsettle). */
    static void undo(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 3, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x, y + 2, x + 5, y + 3, color);
        g.fill(x + 1, y + 3, x + 2, y + 4, color);
        g.fill(x + 2, y + 4, x + 3, y + 5, color);
        g.fill(x + 5, y + 3, x + 6, y + 4, color);
        g.fill(x + 6, y + 4, x + 7, y + 5, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        g.fill(x + 2, y + 6, x + 5, y + 7, color);
    }

    /** 7×7 circular refresh icon with crisp clockwise arrowhead. */
    static void refresh(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 5, y + 1, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x, y + 2, x + 1, y + 5, color);
        g.fill(x + 1, y + 5, x + 2, y + 6, color);
        g.fill(x + 2, y + 6, x + 5, y + 7, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        g.fill(x + 6, y + 4, x + 7, y + 5, color);
        // Clockwise arrowhead at top-right
        g.fill(x + 6, y, x + 7, y + 3, color);
        g.fill(x + 5, y + 1, x + 6, y + 3, color);
        g.fill(x + 4, y + 2, x + 5, y + 3, color);
    }

    /** 7×7 crisp clock icon (matches {@code sprites/thread/snooze.png}). */
    static void clock(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 5, y + 1, color);
        g.fill(x + 2, y + 6, x + 5, y + 7, color);
        g.fill(x, y + 2, x + 1, y + 5, color);
        g.fill(x + 6, y + 2, x + 7, y + 5, color);
        g.fill(x + 1, y + 1, x + 2, y + 2, color);
        g.fill(x + 5, y + 1, x + 6, y + 2, color);
        g.fill(x + 1, y + 5, x + 2, y + 6, color);
        g.fill(x + 5, y + 5, x + 6, y + 6, color);
        // Hands (12:15)
        g.fill(x + 3, y + 2, x + 4, y + 4, color);
        g.fill(x + 4, y + 3, x + 5, y + 4, color);
    }

    /** 6×7 left arrow for Back button. */
    static void back(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x, y + 3, x + 6, y + 4, color);
        g.fill(x + 1, y + 2, x + 2, y + 3, color);
        g.fill(x + 2, y + 1, x + 3, y + 2, color);
        g.fill(x + 1, y + 4, x + 2, y + 5, color);
        g.fill(x + 2, y + 5, x + 3, y + 6, color);
    }

    /**
     * Expressive 5×5 thread-status emblem:
     * <ul>
     *   <li>{@code RUNNING}: 4-phase pixel orbital indicator</li>
     *   <li>{@code WAITING_FOR_INPUT}: pulsing amber diamond</li>
     *   <li>{@code COMPLETED}: crisp rounded green dot</li>
     *   <li>{@code ERROR}: crisp red diamond</li>
     *   <li>{@code IDLE}: hollow ring</li>
     * </ul>
     */
    static void threadStatus(SpruceGuiGraphics g, int x, int y, ThreadStatus status) {
        switch (status) {
            case RUNNING -> spinner(g, x, y, Theme.ACCENT);
            case WAITING_FOR_INPUT -> diamond(g, x, y, pulse(Theme.AMBER));
            case COMPLETED -> {
                int c = Theme.GREEN;
                g.fill(x + 1, y, x + 4, y + 1, c);
                g.fill(x, y + 1, x + 5, y + 4, c);
                g.fill(x + 1, y + 4, x + 4, y + 5, c);
            }
            case ERROR -> diamond(g, x, y, Theme.RED);
            case IDLE -> {
                int c = Theme.FAINT;
                g.fill(x + 1, y, x + 4, y + 1, c);
                g.fill(x + 1, y + 4, x + 4, y + 5, c);
                g.fill(x, y + 1, x + 1, y + 4, c);
                g.fill(x + 4, y + 1, x + 5, y + 4, c);
            }
        }
    }

    /** 5×5 animated 4-phase orbital spinner for active agent work or connecting state. */
    static void spinner(SpruceGuiGraphics g, int x, int y, int color) {
        int dim = (0x58 << 24) | (color & 0xFFFFFF);
        // Base soft ring
        g.fill(x + 1, y, x + 4, y + 1, dim);
        g.fill(x + 1, y + 4, x + 4, y + 5, dim);
        g.fill(x, y + 1, x + 1, y + 4, dim);
        g.fill(x + 4, y + 1, x + 5, y + 4, dim);
        // Bright orbiting arc (4 phases)
        int phase = (int) ((System.currentTimeMillis() / 160L) & 3L);
        switch (phase) {
            case 0 -> {
                g.fill(x + 1, y, x + 4, y + 1, color);
                g.fill(x + 4, y + 1, x + 5, y + 2, color);
            }
            case 1 -> {
                g.fill(x + 4, y + 1, x + 5, y + 4, color);
                g.fill(x + 3, y + 4, x + 4, y + 5, color);
            }
            case 2 -> {
                g.fill(x + 1, y + 4, x + 4, y + 5, color);
                g.fill(x, y + 3, x + 1, y + 4, color);
            }
            default -> {
                g.fill(x, y + 1, x + 1, y + 4, color);
                g.fill(x + 1, y, x + 2, y + 1, color);
            }
        }
    }

    /** 5×5 diamond mark. */
    static void diamond(SpruceGuiGraphics g, int x, int y, int color) {
        g.fill(x + 2, y, x + 3, y + 5, color);
        g.fill(x + 1, y + 1, x + 4, y + 4, color);
        g.fill(x, y + 2, x + 5, y + 3, color);
    }

    /** 5×5 connection status mark. */
    static void connectionMark(SpruceGuiGraphics g, int x, int y, ConnectionState state, int color) {
        if (state == ConnectionState.CONNECTING) {
            spinner(g, x, y, color);
        } else if (state == ConnectionState.DISCONNECTED) {
            g.fill(x + 1, y, x + 4, y + 1, color);
            g.fill(x + 1, y + 4, x + 4, y + 5, color);
            g.fill(x, y + 1, x + 1, y + 4, color);
            g.fill(x + 4, y + 1, x + 5, y + 4, color);
        } else if (state == ConnectionState.ERROR) {
            diamond(g, x, y, color);
        } else {
            g.fill(x + 1, y, x + 4, y + 1, color);
            g.fill(x, y + 1, x + 5, y + 4, color);
            g.fill(x + 1, y + 4, x + 4, y + 5, color);
        }
    }

    /** Width of a built-in pixel connection mark cell. */
    static final int MARK_SIZE = 7;

    /** True when {@code icon} is one of the built-in user-set connection marks rendered as crisp pixel art. */
    static boolean isConnectionMark(String icon) {
        if (icon == null || icon.isEmpty()) return false;
        return switch (icon) {
            case "★", "◆", "●", "▲", "■", "♥", "☀" -> true;
            default -> false;
        };
    }

    /** Draws a user-set connection mark (crisp 7×7 pixel art for built-in marks, font fallback for custom text) and returns its width. */
    static int drawConnectionGlyph(SpruceGuiGraphics g, Font font, String icon, int x, int y, int color) {
        if (icon == null || icon.isEmpty()) return 0;
        if (isConnectionMark(icon)) {
            drawConnectionMark(g, icon, x, y, color);
            return MARK_SIZE;
        }
        g.text(font, icon, x, y, color, false);
        return font.width(icon);
    }

    /** Renders one of the 7 built-in user-set connection marks inside a harmonized 7×7 optical cell. */
    static void drawConnectionMark(SpruceGuiGraphics g, String icon, int x, int y, int color) {
        switch (icon) {
            case "★" -> star(g, x, y, color, true);
            case "◆" -> diamond(g, x + 1, y + 1, color);
            case "●" -> {
                g.fill(x + 2, y + 1, x + 5, y + 2, color);
                g.fill(x + 1, y + 2, x + 6, y + 5, color);
                g.fill(x + 2, y + 5, x + 5, y + 6, color);
            }
            case "▲" -> {
                g.fill(x + 3, y + 1, x + 4, y + 2, color);
                g.fill(x + 2, y + 2, x + 5, y + 3, color);
                g.fill(x + 1, y + 3, x + 6, y + 4, color);
                g.fill(x, y + 4, x + 7, y + 5, color);
            }
            case "■" -> g.fill(x + 1, y + 1, x + 6, y + 6, color);
            case "♥" -> {
                g.fill(x + 1, y + 1, x + 3, y + 2, color);
                g.fill(x + 4, y + 1, x + 6, y + 2, color);
                g.fill(x, y + 2, x + 7, y + 4, color);
                g.fill(x + 1, y + 4, x + 6, y + 5, color);
                g.fill(x + 2, y + 5, x + 5, y + 6, color);
                g.fill(x + 3, y + 6, x + 4, y + 7, color);
            }
            case "☀" -> {
                g.fill(x + 3, y, x + 4, y + 1, color);
                g.fill(x + 3, y + 6, x + 4, y + 7, color);
                g.fill(x, y + 3, x + 1, y + 4, color);
                g.fill(x + 6, y + 3, x + 7, y + 4, color);
                g.fill(x + 1, y + 1, x + 2, y + 2, color);
                g.fill(x + 5, y + 1, x + 6, y + 2, color);
                g.fill(x + 1, y + 5, x + 2, y + 6, color);
                g.fill(x + 5, y + 5, x + 6, y + 6, color);
                g.fill(x + 2, y + 2, x + 5, y + 5, color);
            }
            default -> { }
        }
    }

    private static int pulse(int color) {
        double t = (System.currentTimeMillis() % 1400) / 1400.0;
        int alpha = (int) (155 + 100 * Math.sin(t * Math.PI * 2));
        return (Math.clamp(alpha, 90, 255) << 24) | (color & 0xFFFFFF);
    }
}
