package dev.craftcode.ui;

import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.navigation.NavigationEvent;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.L10n;
import net.minecraft.client.gui.navigation.ScreenDirection;
import net.minecraft.client.input.MouseButtonEvent;

import java.util.function.IntPredicate;

/**
 * Precision 0–100% slider in fixed steps with recessed rounded track, filled accent bar and pill thumb.
 */
final class PercentSlider extends FlatButton {
    private final int step;
    private final IntPredicate onChange;
    private int value;

    PercentSlider(Position position, int width, int height, int value, int step, IntPredicate onChange) {
        super(position, width, height, "", b -> {});
        this.step = step;
        this.onChange = onChange;
        this.value = Math.clamp(value, 0, 100);
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
        int inner = Math.max(1, this.getWidth() - 12);
        double fraction = (mouseX - this.getX() - 6) / inner;
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
        int x = this.getX();
        int y = this.getY();
        int w = this.getWidth();
        int h = this.getHeight();
        boolean hot = this.isActive() && (this.isMouseHovered() || this.isFocused());
        if (this.isMouseHovered() && this.isActive()) graphics.requestCursor(CursorTypes.RESIZE_EW);

        UiShapes.fillRounded(graphics, x, y, w, h, UiShapes.R_CONTROL, hot ? Theme.BUTTON_HOVER : Theme.INPUT);
        UiShapes.outlineRounded(graphics, x, y, w, h, UiShapes.R_CONTROL,
                this.isFocused() ? Theme.ACCENT : hot ? Theme.BORDER_STRONG : Theme.BORDER);

        // Recessed track at lower half of the control
        int tx0 = x + 6;
        int tx1 = x + w - 6;
        int trackW = Math.max(1, tx1 - tx0);
        int trackY = y + h - 5;
        UiShapes.fillRounded(graphics, tx0, trackY, trackW, 3, UiShapes.R_CHIP, Theme.BUTTON_DISABLED);
        int fillW = Math.round(trackW * this.value / 100f);
        if (fillW > 0) {
            UiShapes.fillRounded(graphics, tx0, trackY, Math.max(3, fillW), 3, UiShapes.R_CHIP, Theme.ACCENT);
        }
        // Rounded thumb pill
        int knobX = Math.clamp(tx0 + fillW - 2, tx0, tx1 - 4);
        UiShapes.fillRounded(graphics, knobX, trackY - 2, 4, 7, UiShapes.R_CHIP, Theme.TEXT);
        UiShapes.outlineRounded(graphics, knobX, trackY - 2, 4, 7, UiShapes.R_CHIP,
                hot ? Theme.ACCENT : Theme.BORDER);
    }

    @Override
    protected void extractText(SpruceGuiGraphics graphics, int color) {
        var font = this.client.font;
        String label = Theme.ellipsize(font, this.getMessage().getString(), this.getWidth() - 10);
        graphics.text(font, label, this.getX() + (this.getWidth() - font.width(label)) / 2,
                this.getY() + 2, Theme.TEXT, false);
    }
}
