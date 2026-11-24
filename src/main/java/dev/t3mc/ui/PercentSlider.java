package dev.t3mc.ui;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.navigation.NavigationEvent;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.L10n;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntPredicate;

/**
 * A compact 0–100% slider in fixed steps. Mouse: click or drag along the track. Keyboard: when focused, ←/→ change it
 * by one step and Enter/Space steps up (wrapping to 0 after 100); Tab moves on as usual.
 *
 * <p>{@code onChange} receives each new value and returns false to reject it (for example when it could not be saved);
 * the slider then keeps its previous value.
 */
final class PercentSlider extends FlatButton {
    private final int step;
    private final IntPredicate onChange;
    private int value;

    PercentSlider(Position position, int width, int height, int value, int step, IntPredicate onChange) {
        super(position, width, height, "", b -> {});
        this.step = step;
        this.onChange = onChange;
        this.value = Math.clamp(value, 0, 100); // shown as saved; any change snaps to a step
        this.setLabel(label(this.value));
    }

    int value() {
        return this.value;
    }

    static String label(int value) {
        return value == 0 ? L10n.tr("slider.solid", "%s · solid", "0%") : value + "%";
    }

    static int snap(int value, int step) {
        return Math.clamp(Math.round(value / (float) step) * step, 0, 100);
    }

    /** Sets the value as if the user chose it (it goes through {@code onChange}). */
    void setValue(int next) {
        this.set(next);
    }

    private void set(int next) {
        next = snap(next, this.step);
        if (next == this.value) return;
        if (!this.onChange.test(next)) return;
        this.value = next;
        this.setLabel(label(next));
    }

    private void setFromMouse(double mouseX) {
        int inner = Math.max(1, this.getWidth() - 4);
        double fraction = (mouseX - this.getX() - 2) / inner;
        this.set((int) Math.round(Math.clamp(fraction, 0, 1) * 100));
    }

    @Override
    public void onPress() {
        this.set(this.value >= 100 ? 0 : this.value + this.step);
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
    public boolean onNavigation(NavigationEvent event) {
        if (this.isFocused() && !event.tab()
                && (event.direction() == ScreenDirection.LEFT || event.direction() == ScreenDirection.RIGHT)) {
            this.set(this.value + (event.direction() == ScreenDirection.RIGHT ? this.step : -this.step));
            return true;
        }
        return super.onNavigation(event);
    }

    @Override
    protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.extractBackground(graphics, mouseX, mouseY, delta);
        // Track fill and step ticks inside the button body.
        int x0 = this.getX() + 2, x1 = this.getEndX() - 2;
        int bottom = this.getEndY() - 2;
        int fill = x0 + Math.round((x1 - x0) * this.value / 100f);
        graphics.fill(x0, bottom - 2, x1, bottom, Theme.BUTTON_DISABLED);
        if (fill > x0) graphics.fill(x0, bottom - 2, fill, bottom, Theme.ACCENT);
        int knob = Math.clamp(fill, x0 + 1, x1 - 1);
        graphics.fill(knob - 1, bottom - 3, knob + 1, bottom, Theme.TEXT);
    }
}
