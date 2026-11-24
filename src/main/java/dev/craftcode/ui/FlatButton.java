package dev.craftcode.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.SpruceButtonWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Tactile, pixel-rounded button in the active T3 theme with hover elevation, pressed feedback,
 * keyboard focus ring and optional crisp pixel iconography.
 */
class FlatButton extends SpruceButtonWidget {
    enum Kind { NORMAL, PRIMARY, DANGER, SELECTED, SUBTLE }

    enum Icon {
        NONE(0, 0),
        PLUS(7, 7),
        MINUS(7, 7),
        FILTER(7, 7),
        FILTER_ACTIVE(7, 7),
        SETTINGS(7, 7),
        SEND(8, 8),
        STOP(6, 6),
        IMAGE(8, 7),
        REFRESH(7, 7),
        BACK(6, 7),
        CHEVRON_LEFT(4, 7),
        CHEVRON_RIGHT(4, 7);

        final int width;
        final int height;

        Icon(int width, int height) {
            this.width = width;
            this.height = height;
        }
    }

    private Kind kind;
    private Icon icon = Icon.NONE;
    private int radius = UiShapes.R_CONTROL;
    private @Nullable String compactLabel;
    private boolean pressed;

    FlatButton(Position position, int width, int height, String label, Kind kind, PressAction action) {
        super(position, width, height, Component.literal(label), action);
        this.kind = kind;
    }

    FlatButton(Position position, int width, int height, String label, PressAction action) {
        this(position, width, height, label, Kind.NORMAL, action);
    }

    void setKind(Kind kind) {
        this.kind = kind;
    }

    FlatButton withIcon(Icon icon) {
        this.icon = icon;
        return this;
    }

    void setIcon(Icon icon) {
        this.icon = icon;
    }

    FlatButton withRadius(int radius) {
        this.radius = radius;
        return this;
    }

    void setLabel(String label) {
        this.setLabel(label, null);
    }

    void setLabel(String label, @Nullable String compactLabel) {
        this.setMessage(Component.literal(label));
        this.compactLabel = compactLabel;
    }

    boolean isPressedState() {
        return this.pressed && this.isActive() && this.isMouseHovered();
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        if (this.isActive() && this.isValidClickButton(event.button()) && this.isMouseHovered()) {
            this.pressed = true;
        }
        return super.onMouseClick(event, doubleClick);
    }

    @Override
    public boolean onMouseRelease(MouseButtonEvent event) {
        this.pressed = false;
        return super.onMouseRelease(event);
    }

    @Override
    protected void onRelease(double mouseX, double mouseY) {
        this.pressed = false;
        super.onRelease(mouseX, mouseY);
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean active = this.isActive();
        boolean hovered = active && this.isMouseHovered();
        boolean hot = active && (hovered || this.isFocused());
        boolean down = this.isPressedState();
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);

        int fill;
        int border;
        boolean sheen;
        if (!active) {
            fill = this.kind == Kind.SUBTLE ? 0 : Theme.BUTTON_DISABLED;
            border = Theme.BORDER_SUBTLE;
            sheen = false;
        } else {
            fill = switch (this.kind) {
                case PRIMARY -> down ? Theme.ACCENT_PRESSED : hot ? Theme.ACCENT_HOVER : Theme.ACCENT_DIM;
                case DANGER -> down ? Theme.DANGER_PRESSED : hot ? Theme.DANGER_HOVER : Theme.DANGER;
                case SELECTED -> down ? Theme.SELECTED_PRESSED : hot ? Theme.SELECTED_HOVER : Theme.SELECTED;
                case SUBTLE -> down ? Theme.BUTTON_PRESSED : hot ? Theme.BUTTON_HOVER : 0;
                case NORMAL -> down ? Theme.BUTTON_PRESSED : hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
            };
            border = switch (this.kind) {
                case PRIMARY -> hot ? Theme.ACCENT : Theme.mix(Theme.ACCENT_DIM, Theme.isLight() ? 0xFF000000 : 0xFFFFFFFF, 0.16f);
                case DANGER -> hot ? Theme.RED : Theme.mix(Theme.DANGER, Theme.RED, 0.35f);
                case SELECTED -> hot ? Theme.ACCENT : Theme.mix(Theme.BORDER, Theme.ACCENT, 0.55f);
                case SUBTLE -> hot ? Theme.BORDER_STRONG : Theme.BORDER_SUBTLE;
                case NORMAL -> hot ? Theme.BORDER_STRONG : Theme.BORDER;
            };
            sheen = !down && this.kind != Kind.SUBTLE;
        }

        if (fill != 0) {
            UiShapes.fillRounded(graphics, x, y, w, h, this.radius, fill);
        }
        if (sheen && w > 6 && h > 5) {
            int inset = Math.max(2, this.radius);
            graphics.fill(x + inset, y + 1, x + w - inset, y + 2, Theme.HIGHLIGHT_TOP);
            graphics.fill(x + inset, y + h - 2, x + w - inset, y + h - 1, Theme.SHADOW_EDGE);
        }
        if (this.kind == Kind.SELECTED && active && w > 6) {
            int inset = Math.max(2, this.radius + 1);
            graphics.fill(x + inset, y + h - 2, x + w - inset, y + h - 1, Theme.ACCENT);
        }
        if (this.isFocused() && active) {
            int focusColor = this.kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.ACCENT;
            UiShapes.outlineRounded(graphics, x, y, w, h, this.radius, focusColor);
        } else if (border != 0) {
            UiShapes.outlineRounded(graphics, x, y, w, h, this.radius, border);
        }
    }

    /** Draws the optional pixel icon and themed label, depressing 1px while pressed. */
    @Override
    protected void extractText(SpruceGuiGraphics graphics, int color) {
        int themed;
        if (!this.isActive()) {
            themed = Theme.TEXT_DISABLED;
        } else {
            themed = switch (this.kind) {
                case PRIMARY -> Theme.ON_ACCENT;
                case DANGER -> Theme.isLight() ? Theme.RED : Theme.TEXT;
                case SUBTLE -> this.isMouseHovered() || this.isFocused() ? Theme.TEXT : Theme.TEXT_SOFT;
                default -> Theme.BUTTON_TEXT;
            };
        }
        var font = this.client.font;
        int pressOffset = this.isPressedState() ? 1 : 0;
        String fullLabel = this.getMessage().getString();
        boolean hasIcon = this.icon != Icon.NONE;

        // Icon-only button (e.g. ‹, ›, −, +)
        if (hasIcon && fullLabel.isBlank()) {
            int ix = this.getX() + (this.getWidth() - this.icon.width) / 2;
            int iy = this.getY() + (this.getHeight() - this.icon.height) / 2 + pressOffset;
            this.drawIcon(graphics, ix, iy, themed);
            return;
        }

        int pad = 6;
        int iconGap = hasIcon ? 4 : 0;
        int iconSpan = hasIcon ? this.icon.width + iconGap : 0;
        int availableWidth = Math.max(0, this.getWidth() - pad * 2 - iconSpan);
        String chosen = this.compactLabel != null && font.width(fullLabel) > availableWidth ? this.compactLabel : fullLabel;
        if (hasIcon && availableWidth < 10) {
            // Very narrow button: show centered icon cleanly instead of a crushed ellipsis
            int ix = this.getX() + (this.getWidth() - this.icon.width) / 2;
            int iy = this.getY() + (this.getHeight() - this.icon.height) / 2 + pressOffset;
            this.drawIcon(graphics, ix, iy, themed);
            return;
        }
        String label = Theme.ellipsize(font, chosen, Math.max(0, this.getWidth() - 6 - iconSpan));
        int totalW = iconSpan + font.width(label);
        int startX = this.getX() + (this.getWidth() - totalW) / 2;
        int textY = this.getY() + (this.getHeight() - font.lineHeight) / 2 + 1 + pressOffset;
        if (hasIcon) {
            int iconColor = this.kind == Kind.SELECTED && this.icon == Icon.FILTER_ACTIVE && this.isActive()
                    ? Theme.ACCENT : themed;
            int iy = this.getY() + (this.getHeight() - this.icon.height) / 2 + pressOffset;
            this.drawIcon(graphics, startX, iy, iconColor);
            startX += iconSpan;
        }
        graphics.text(font, label, startX, textY, themed, false);
    }

    private void drawIcon(SpruceGuiGraphics g, int x, int y, int color) {
        switch (this.icon) {
            case NONE -> { }
            case PLUS -> UiIcons.plus(g, x, y, color);
            case MINUS -> UiIcons.minus(g, x, y, color);
            case FILTER -> UiIcons.filter(g, x, y, color, false);
            case FILTER_ACTIVE -> UiIcons.filter(g, x, y, color, true);
            case SETTINGS -> UiIcons.settings(g, x, y, color);
            case SEND -> UiIcons.send(g, x, y, color);
            case STOP -> UiIcons.stop(g, x, y, color);
            case IMAGE -> UiIcons.image(g, x, y, color);
            case REFRESH -> UiIcons.refresh(g, x, y, color);
            case BACK -> UiIcons.back(g, x, y, color);
            case CHEVRON_LEFT -> UiIcons.chevronLeft(g, x, y, color);
            case CHEVRON_RIGHT -> UiIcons.chevronRight(g, x, y, color);
        }
    }
}
