package dev.t3mc.ui;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.SpruceButtonWidget;
import net.minecraft.network.chat.Component;

/** A flat, borderless button in the T3 palette. */
class FlatButton extends SpruceButtonWidget {
    enum Kind { NORMAL, PRIMARY, DANGER, SELECTED }

    private Kind kind;

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

    void setLabel(String label) {
        this.setMessage(Component.literal(label));
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        boolean hot = this.isActive() && (this.isMouseHovered() || this.isFocused());
        int color;
        if (!this.isActive()) {
            color = Theme.BUTTON_DISABLED;
        } else {
            color = switch (this.kind) {
                case PRIMARY -> hot ? Theme.ACCENT_HOVER : Theme.ACCENT_DIM;
                case DANGER -> hot ? Theme.DANGER_HOVER : Theme.DANGER;
                case SELECTED -> hot ? Theme.SELECTED_HOVER : Theme.SELECTED;
                case NORMAL -> hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
            };
        }
        graphics.fill(this.getX(), this.getY(), this.getEndX(), this.getEndY(), color);
        if (this.kind == Kind.SELECTED) {
            graphics.fill(this.getX(), this.getEndY() - 1, this.getEndX(), this.getEndY(), Theme.ACCENT);
        }
        if (this.isFocused()) {
            Theme.outline(graphics, this.getX(), this.getY(), this.getWidth(), this.getHeight(),
                    this.kind == Kind.PRIMARY ? Theme.ON_ACCENT : Theme.ACCENT);
        }
    }

    /** Draws the label in the theme's text color instead of SpruceUI's fixed white. */
    @Override
    protected void extractText(SpruceGuiGraphics graphics, int color) {
        int themed;
        if (!this.isActive()) themed = Theme.TEXT_DISABLED;
        else themed = switch (this.kind) {
            case PRIMARY -> Theme.ON_ACCENT;
            case DANGER -> Theme.isLight() ? Theme.RED : Theme.TEXT;
            default -> Theme.BUTTON_TEXT;
        };
        var font = this.client.font;
        String label = Theme.ellipsize(font, this.getMessage().getString(), this.getWidth() - 4);
        graphics.text(font, label, this.getX() + (this.getWidth() - font.width(label)) / 2,
                this.getY() + (this.getHeight() - font.lineHeight) / 2 + 1, themed, false);
    }
}
