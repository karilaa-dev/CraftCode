package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.navigation.NavigationEvent;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * One 0–255 RGB channel of the color being edited. Mouse: click, drag or scroll. Keyboard: ←/→ step by 1,
 * Shift+←/→ and Page Up/Down by 16, Home/End jump to 0/255. The track shows the color across the channel's range.
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
        int inner = Math.max(1, this.getWidth() - 4);
        double fraction = (mouseX - this.getX() - 2) / inner;
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
        super.extractBackground(graphics, mouseX, mouseY, delta);
        // Gradient track: the edited color with this channel swept from 0 to 255.
        int x0 = this.getX() + 2, x1 = this.getEndX() - 2;
        int bottom = this.getEndY() - 2;
        int base = this.color.getAsInt();
        int span = Math.max(1, x1 - x0);
        for (int x = x0; x < x1; x += 2) {
            int v = Math.round(255f * (x - x0) / span);
            graphics.fill(x, bottom - 2, Math.min(x + 2, x1), bottom, HexColor.withChannel(base, this.channel, v));
        }
        int knob = x0 + Math.round(span * this.value / 255f);
        knob = Math.clamp(knob, x0 + 1, x1 - 1);
        graphics.fill(knob - 1, bottom - 4, knob + 1, bottom + 1, Theme.TEXT);
    }
}
