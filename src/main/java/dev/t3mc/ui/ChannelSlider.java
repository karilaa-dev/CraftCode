package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.navigation.NavigationEvent;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * One 0–255 RGB channel slider with a rounded shell, full-spectrum gradient track and tactile pill thumb.
 */
final class ChannelSlider extends FlatButton {
    private static final int COARSE = 16;

    private final int channel;
    private final String name;
    private final IntSupplier color;
    private final IntConsumer onChange;
    private int value;

    /** {@code channel} is 0 (red), 1 (green) or 2 (blue); {@code color} is the full color being edited. */
    ChannelSlider(Position position, int width, int height, int channel, String name, IntSupplier color, IntConsumer onChange) {
        super(position, width, height, "", b -> {});
        this.channel = channel;
        this.name = name;
        this.color = color;
        this.onChange = onChange;
        this.value = HexColor.channel(color.getAsInt(), channel);
        this.refreshLabel();
    }

    /** Follows an outside change (hex field, another swatch) without reporting it back. */
    void sync() {
        this.value = HexColor.channel(this.color.getAsInt(), this.channel);
        this.refreshLabel();
    }

    private void refreshLabel() {
        this.setLabel(this.name + "  " + this.value);
    }

    private void set(int next) {
        next = Math.clamp(next, 0, 255);
        if (next == this.value) return;
        this.value = next;
        this.refreshLabel();
        this.onChange.accept(next);
    }

    private void setFromMouse(double mouseX) {
        int inner = Math.max(1, this.getWidth() - 12);
        double fraction = (mouseX - this.getX() - 6) / inner;
        this.set((int) Math.round(Math.clamp(fraction, 0, 1) * 255));
    }

    @Override
    public void onPress() {
        // Enter/Space: nothing to toggle; arrows adjust.
    }

    @Override
    public void onClick(double mouseX, double mouseY) {
        this.setFromMouse(mouseX);
    }

    @Override
    protected boolean onMouseDrag(MouseButtonEvent event, double deltaX, double deltaY) {
        this.setFromMouse(event.x());
        return true;
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (scrollY == 0) return false;
        this.set(this.value + (scrollY > 0 ? 1 : -1));
        return true;
    }

    @Override
    protected boolean onKeyPress(KeyEvent event) {
        switch (event.key()) {
            case InputConstants.KEY_PAGEUP -> this.set(this.value + COARSE);
            case InputConstants.KEY_PAGEDOWN -> this.set(this.value - COARSE);
            case InputConstants.KEY_HOME -> this.set(0);
            case InputConstants.KEY_END -> this.set(255);
            case InputConstants.KEY_RIGHT, InputConstants.KEY_LEFT -> {
                if (!event.hasShiftDown()) return super.onKeyPress(event);
                this.set(this.value + (event.key() == InputConstants.KEY_RIGHT ? COARSE : -COARSE));
            }
            default -> {
                return super.onKeyPress(event);
            }
        }
        return true;
    }

    @Override
    public boolean onNavigation(NavigationEvent event) {
        if (this.isFocused() && !event.tab()
                && (event.direction() == ScreenDirection.LEFT || event.direction() == ScreenDirection.RIGHT)) {
            this.set(this.value + (event.direction() == ScreenDirection.RIGHT ? 1 : -1));
            return true;
        }
        return super.onNavigation(event);
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hot = this.isActive() && (this.isMouseHovered() || this.isFocused());
        if (this.isMouseHovered() && this.isActive()) graphics.requestCursor(CursorTypes.RESIZE_EW);

        UiShapes.fillRounded(graphics, x, y, w, h, UiShapes.R_CONTROL, hot ? Theme.BUTTON_HOVER : Theme.INPUT);
        UiShapes.outlineRounded(graphics, x, y, w, h, UiShapes.R_CONTROL,
                this.isFocused() ? Theme.ACCENT : hot ? Theme.BORDER_STRONG : Theme.BORDER);

        // Gradient spectrum bar
        int x0 = x + 6;
        int x1 = x + w - 6;
        int trackY = y + h - 5;
        int base = this.color.getAsInt();
        int span = Math.max(1, x1 - x0);
        for (int px = x0; px < x1; px += 2) {
            int v = Math.round(255f * (px - x0) / span);
            graphics.fill(px, trackY, Math.min(px + 2, x1), trackY + 3, HexColor.withChannel(base, this.channel, v));
        }
        int knobX = Math.clamp(x0 + Math.round(span * this.value / 255f) - 2, x0, x1 - 4);
        UiShapes.fillRounded(graphics, knobX, trackY - 2, 4, 7, UiShapes.R_CHIP, Theme.TEXT);
        UiShapes.outlineRounded(graphics, knobX, trackY - 2, 4, 7, UiShapes.R_CHIP,
                hot ? Theme.ACCENT : Theme.BORDER);
    }

    @Override
    protected void extractText(SpruceGuiGraphics graphics, int color) {
        var font = this.client.font;
        String val = Integer.toString(this.value);
        graphics.text(font, this.name, this.getX() + 6, this.getY() + 2, Theme.MUTED, false);
        graphics.text(font, val, this.getEndX() - 6 - font.width(val), this.getY() + 2, Theme.TEXT, false);
    }
}
