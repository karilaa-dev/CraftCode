package dev.craftcode.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/** Icon-only composer controls retain the localized button label for narration and tooltips. */
final class ComposerAction extends FlatButton {
    private static final Identifier CLIP = Identifier.fromNamespaceAndPath("craftcode", "composer/paperclip");
    private Icon symbol;

    ComposerAction(Position position, int size, String label, Icon symbol, PressAction press) {
        super(position, size, size, label, press);
        this.symbol = symbol;
    }

    @Override
    void setIcon(Icon symbol) {
        this.symbol = symbol;
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics g, int mx, int my, float delta) {
        boolean hot = isActive() && (isMouseHovered() || isFocused());
        if (hot) g.requestCursor(CursorTypes.POINTING_HAND);
        if (symbol == Icon.IMAGE && !hot) return;
        int face = switch (symbol) {
            case IMAGE -> Theme.BUTTON_HOVER;
            case STOP -> 0xFFE84048;
            default -> Theme.ACCENT_DIM;
        };
        if (!isActive()) face = Theme.BUTTON_DISABLED;
        else if (hot) face = Theme.mix(face, Theme.TEXT, isPressedState() ? 0.23f : 0.12f);
        int size = getWidth(), inset = isFocused() && isActive() ? 1 : 0;
        if (inset > 0) UiShapes.roundSurface(g, getX(), getY(), size, size, size / 2, Theme.TEXT);
        UiShapes.roundSurface(g, getX() + inset, getY() + inset, size - 2 * inset, size - 2 * inset, size / 2, face);
    }

    @Override
    protected void extractText(SpruceGuiGraphics g, int ignored) {
        int cx = getX() + getWidth() / 2;
        int cy = getY() + getHeight() / 2 + (isPressedState() ? 1 : 0);
        int ink = isActive() ? Theme.ON_ACCENT : Theme.TEXT_DISABLED;
        switch (symbol) {
            case IMAGE -> g.vanilla().blitSprite(RenderPipelines.GUI_TEXTURED, CLIP, cx - 4, cy - 4, 8, 8,
                    isActive() ? Theme.TEXT_SOFT : Theme.composerSecondaryText());
            case STOP -> UiIcons.stop(g, cx - 3, cy - 3, 0xFFFFFFFF);
            case NONE -> UiIcons.sendingDots(g, cx - 4, cy - 1, ink);
            default -> UiIcons.send(g, cx - 4, cy - 4, ink);
        }
    }
}
