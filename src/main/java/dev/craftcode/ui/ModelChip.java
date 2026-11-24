package dev.craftcode.ui;

import dev.craftcode.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

/**
 * Composer control showing the thread's provider (as its icon when recognized), model and effort.
 * Right-aligned beside the primary action as its label changes width.
 */
final class ModelChip extends FlatButton {
    private final int rightEdge;
    private final int maxWidth;
    private String detail = "";
    private String spoken = "";
    private @Nullable ProviderIcon icon;
    private boolean warn;

    ModelChip(int rightEdge, int y, int maxWidth, int height, PressAction action) {
        super(Position.of(rightEdge - Math.min(maxWidth, 68), y), Math.min(maxWidth, 68), height, L10n.tr("model.chip", "Model"), Kind.SUBTLE, action);
        this.withRadius(UiShapes.R_CONTROL);
        this.maxWidth = maxWidth;
        this.rightEdge = rightEdge;
    }

    /**
     * @param icon   provider icon drawn before the label, or null for none
     * @param label  e.g. "Opus 4.5 · High" beside the Claude icon, or "Claude · Opus 4.5 · High" without it
     * @param spoken full text for narration, naming the provider even when only its icon shows
     * @param detail full description for the tooltip
     */
    void set(@Nullable ProviderIcon icon, String label, String spoken, String detail, boolean warn) {
        this.setLabel(label);
        this.icon = icon;
        this.spoken = spoken;
        this.detail = detail;
        this.warn = warn;
        var font = this.client.font;
        int content = font.width(label) + 12 + this.iconSpace();
        this.width = Math.max(Math.min(44, this.maxWidth), Math.min(this.maxWidth, content));
        this.getPosition().setRelativeX(this.rightEdge - this.width);
    }

    private int iconSpace() {
        return this.icon == null ? 0 : ProviderIcon.SIZE + 4;
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        if (this.isActive() && (this.isFocused() || this.isMouseHovered())) {
            super.extractBackground(graphics, mouseX, mouseY, delta);
        }
    }

    @Override
    protected void extractText(SpruceGuiGraphics graphics, int color) {
        var font = this.client.font;
        int pressOffset = this.isPressedState() ? 1 : 0;
        int text = !this.isActive() ? Theme.TEXT_DISABLED : this.warn ? Theme.RED : Theme.BUTTON_TEXT;
        int y = this.getY() + (this.getHeight() - font.lineHeight) / 2 + 1 + pressOffset;
        int x = this.getX() + 6;
        if (this.icon != null) {
            this.icon.draw(graphics, x, this.getY() + (this.getHeight() - ProviderIcon.SIZE) / 2 + pressOffset, !this.isActive());
            x += this.iconSpace();
        }
        String label = Theme.ellipsize(font, this.getMessage().getString(),
                Math.max(0, this.getWidth() - 12 - this.iconSpace()));
        graphics.text(font, label, x, y, text, false);
        if (this.isMouseHovered() && !this.detail.isEmpty()) {
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(this.detail),
                    Math.min(240, this.client.getWindow().getGuiScaledWidth() - 24)),
                    (width, height, anchorX, y0, w, h) -> new org.joml.Vector2i(
                            Math.clamp(anchorX, 10, Math.max(10, width - w - 10)),
                            Math.clamp(y0 - h - 10, 10, Math.max(10, height - h - 10))),
                    this.getX(), this.getY(), false);
        }
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(L10n.tr("model.chip.narration", "Model: %s",
                this.spoken.isEmpty() ? this.getMessage().getString() : this.spoken));
    }
}
