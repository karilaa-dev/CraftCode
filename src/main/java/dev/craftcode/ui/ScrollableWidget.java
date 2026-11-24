package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.AbstractSpruceWidget;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.util.Mth;

/** Base for custom-drawn vertical lists: wheel scrolling, a draggable rounded pill scrollbar and clipping. */
abstract class ScrollableWidget extends AbstractSpruceWidget {
    private static final int BAR_REST = 3;
    private static final int BAR_HOT = 4;

    protected double scroll;
    private boolean draggingBar;

    ScrollableWidget(Position position, int width, int height) {
        super(position);
        this.width = width;
        this.height = height;
    }

    /** Total height of the content. */
    protected abstract int contentHeight();

    protected int maxScroll() {
        return Math.max(0, this.contentHeight() - this.height);
    }

    double scroll() {
        return this.scroll;
    }

    void setScroll(double value) {
        this.scroll = Mth.clamp(value, 0, this.maxScroll());
    }

    boolean atBottom() {
        return this.maxScroll() - this.scroll <= 4;
    }

    void scrollToBottom() {
        this.scroll = this.maxScroll();
    }

    @Override
    public boolean requiresCursor() {
        return true;
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        double before = this.scroll;
        this.setScroll(this.scroll - scrollY * 18);
        return before != this.scroll || this.maxScroll() > 0;
    }

    private boolean onBar(double mouseX) {
        return this.maxScroll() > 0 && mouseX >= this.getEndX() - BAR_HOT - 4;
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.onBar(event.x())) {
            this.draggingBar = true;
            this.dragTo(event.y());
            return true;
        }
        return this.onContentClick(event.x(), event.y() - this.getY() + Math.round(this.scroll), event.button());
    }

    @Override
    protected boolean onMouseDrag(MouseButtonEvent event, double deltaX, double deltaY) {
        if (!this.draggingBar) return false;
        this.dragTo(event.y());
        return true;
    }

    @Override
    protected boolean onMouseRelease(MouseButtonEvent event) {
        boolean was = this.draggingBar;
        this.draggingBar = false;
        return was;
    }

    private void dragTo(double mouseY) {
        double progress = (mouseY - this.getY()) / Math.max(1, this.height);
        this.setScroll(progress * this.maxScroll());
    }

    /** Click on the content; {@code contentY} is relative to the top of the content. */
    protected abstract boolean onContentClick(double mouseX, double contentY, int button);

    /** Draws the content; {@code top} is the screen Y of content offset 0. */
    protected abstract void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY);

    protected boolean usesScissor() {
        return true;
    }

    @Override
    protected void extractWidgetRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        this.setScroll(this.scroll);
        boolean clip = this.usesScissor();
        if (clip) graphics.enableScissor(this.getX(), this.getY(), this.getEndX(), this.getEndY());
        this.drawContent(graphics, this.getY() - (int) Math.round(this.scroll), mouseX, mouseY);
        if (clip) graphics.disableScissor();

        int max = this.maxScroll();
        if (max > 0) {
            int trackInset = 2;
            int track = Math.max(1, this.height - trackInset * 2);
            int thumb = Math.max(16, track * this.height / Math.max(1, this.contentHeight()));
            int thumbY = this.getY() + trackInset + (int) ((track - thumb) * (this.scroll / max));
            boolean hot = this.draggingBar || (this.isMouseHovered() && this.onBar(mouseX));
            int barW = hot ? BAR_HOT : BAR_REST;
            int x = this.getEndX() - barW - 2;
            if (hot) {
                UiShapes.fillRounded(graphics, x, this.getY() + trackInset, barW, track, UiShapes.R_CHIP,
                        0x20000000 | (Theme.BORDER & 0xFFFFFF));
            }
            UiShapes.fillRounded(graphics, x, thumbY, barW, thumb, UiShapes.R_CHIP,
                    hot ? Theme.SCROLLBAR_HOT : Theme.SCROLLBAR);
            if (hot) graphics.requestCursor(CursorTypes.RESIZE_NS);
        }
    }
}
