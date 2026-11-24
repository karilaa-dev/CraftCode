package dev.craftcode.ui;

import dev.craftcode.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.AbstractSpruceWidget;
import dev.craftcode.ui.model.LocalImage;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * One row of rounded chips for the images staged in the composer: thumbnail (PNG/JPEG) or type frame, file name and a
 * pixel "×" that removes it. Scrolls horizontally with the mouse wheel when chips overflow. Hovering shows an elevated
 * preview card drawn via {@link #extractHoverCard}.
 */
final class AttachmentStrip extends AbstractSpruceWidget {
    static final int HEIGHT = 17;
    private static final int THUMB = 11;
    private static final int CLOSE_W = 13;
    private static final int GAP = 4;
    private static final int MAX_NAME_W = 96;

    private record Chip(LocalImage image, String label, int x, int w) {}

    private final ImagePreviews previews;
    private final Consumer<LocalImage> onRemove;
    private final List<Chip> chips = new ArrayList<>();
    private int contentWidth;
    private double scroll;
    private int selected = -1;
    private boolean editable = true;

    AttachmentStrip(Position position, int width, ImagePreviews previews, Consumer<LocalImage> onRemove) {
        super(position);
        this.width = width;
        this.height = HEIGHT;
        this.previews = previews;
        this.onRemove = onRemove;
    }

    void setImages(List<LocalImage> images, boolean editable) {
        this.editable = editable;
        var font = this.client.font;
        this.chips.clear();
        int x = 0;
        for (var image : images) {
            String label = Theme.ellipsize(font, image.name(), MAX_NAME_W);
            int w = 4 + THUMB + 4 + font.width(label) + 4 + CLOSE_W;
            this.chips.add(new Chip(image, label, x, w));
            x += w + GAP;
        }
        this.contentWidth = Math.max(0, x - GAP);
        this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
        if (this.selected >= this.chips.size()) this.selected = this.chips.size() - 1;
    }

    private int maxScroll() {
        return Math.max(0, this.contentWidth - this.width);
    }

    private @Nullable Chip chipAt(double mouseX, double mouseY) {
        if (mouseY < this.getY() || mouseY >= this.getEndY() || mouseX < this.getX() || mouseX >= this.getEndX()) return null;
        double local = mouseX - this.getX() + this.scroll;
        for (var chip : this.chips) if (local >= chip.x() && local < chip.x() + chip.w()) return chip;
        return null;
    }

    private boolean onClose(Chip chip, double mouseX) {
        return mouseX - this.getX() + this.scroll >= chip.x() + chip.w() - CLOSE_W - 1;
    }

    private void remove(Chip chip) {
        if (!this.editable) return;
        this.playDownSound();
        this.onRemove.accept(chip.image());
    }

    @Override
    public boolean requiresCursor() {
        return false;
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        var chip = this.chipAt(event.x(), event.y());
        if (chip == null) return false;
        this.selected = this.chips.indexOf(chip);
        if (this.onClose(chip, event.x())) this.remove(chip);
        return true;
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.maxScroll() == 0) return false;
        double delta = scrollX != 0 ? scrollX : scrollY;
        this.scroll = Math.clamp(this.scroll - delta * 24, 0, this.maxScroll());
        return true;
    }

    @Override
    protected boolean onKeyPress(KeyEvent event) {
        if (this.chips.isEmpty()) return false;
        int key = event.key();
        if (key == InputConstants.KEY_LEFT || key == InputConstants.KEY_RIGHT) {
            int next = this.selected < 0 ? 0 : this.selected + (key == InputConstants.KEY_LEFT ? -1 : 1);
            this.selected = Math.clamp(next, 0, this.chips.size() - 1);
            this.reveal(this.chips.get(this.selected));
            return true;
        }
        if ((key == InputConstants.KEY_DELETE || key == InputConstants.KEY_BACKSPACE) && this.selected >= 0) {
            this.remove(this.chips.get(this.selected));
            return true;
        }
        return false;
    }

    private void reveal(Chip chip) {
        if (chip.x() < this.scroll) this.scroll = chip.x();
        else if (chip.x() + chip.w() > this.scroll + this.width) this.scroll = chip.x() + chip.w() - this.width;
        this.scroll = Math.clamp(this.scroll, 0, this.maxScroll());
    }

    @Override
    protected void extractWidgetRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var font = this.client.font;
        int x0 = this.getX() - (int) this.scroll;
        int y = this.getY();
        var hovered = this.isMouseHovered() ? this.chipAt(mouseX, mouseY) : null;
        boolean clipHorizontally = this.contentWidth > this.width;
        if (clipHorizontally) graphics.enableScissor(this.getX(), 0, this.getEndX(), this.getEndY());
        for (int i = 0; i < this.chips.size(); i++) {
            var chip = this.chips.get(i);
            int cx = x0 + chip.x();
            boolean hot = chip == hovered;
            boolean focused = this.isFocused() && i == this.selected;
            UiShapes.pill(graphics, cx, y, chip.w(), HEIGHT,
                    hot ? Theme.BUTTON_HOVER : Theme.BUTTON,
                    focused ? Theme.ACCENT : hot ? Theme.BORDER_STRONG : Theme.BORDER);

            int tx = cx + 3, ty = y + (HEIGHT - THUMB) / 2;
            var thumb = this.previews.thumb(chip.image());
            if (thumb != null) {
                int dw = Math.max(1, thumb.width() * THUMB / Math.max(thumb.width(), thumb.height()));
                int dh = Math.max(1, thumb.height() * THUMB / Math.max(thumb.width(), thumb.height()));
                graphics.vanilla().blit(RenderPipelines.GUI_TEXTURED, thumb.texture(), tx + (THUMB - dw) / 2, ty + (THUMB - dh) / 2,
                        0f, 0f, dw, dh, thumb.width(), thumb.height(), thumb.width(), thumb.height());
            } else {
                UiShapes.pill(graphics, tx, ty, THUMB, THUMB, Theme.PANEL, Theme.BORDER_SUBTLE);
            }

            int textY = y + (HEIGHT - font.lineHeight) / 2 + 1;
            graphics.text(font, chip.label(), tx + THUMB + 4, textY, this.editable ? Theme.TEXT : Theme.TEXT_DISABLED, false);
            boolean closeHot = hot && this.editable && this.onClose(chip, mouseX);
            int closeX = cx + chip.w() - CLOSE_W;
            if (closeHot) {
                UiShapes.fillRounded(graphics, closeX, y + 2, CLOSE_W - 2, HEIGHT - 4, UiShapes.R_CHIP, Theme.DANGER);
            }
            UiIcons.close(graphics, closeX + (CLOSE_W - 2 - 5) / 2, y + (HEIGHT - 5) / 2,
                    !this.editable ? Theme.TEXT_DISABLED : closeHot ? Theme.TEXT : Theme.MUTED);
            if (closeHot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        if (clipHorizontally) graphics.disableScissor();
        // Overflow indicators
        if (this.scroll > 0) {
            graphics.fill(this.getX(), y, this.getX() + 9, y + HEIGHT, Theme.INPUT);
            UiIcons.chevronLeft(graphics, this.getX() + 2, y + (HEIGHT - 7) / 2, Theme.MUTED);
        }
        if (this.scroll < this.maxScroll()) {
            graphics.fill(this.getEndX() - 9, y, this.getEndX(), y + HEIGHT, Theme.INPUT);
            UiIcons.chevronRight(graphics, this.getEndX() - 6, y + (HEIGHT - 7) / 2, Theme.MUTED);
        }
    }

    /** Larger preview card for the hovered chip, drawn above the strip. Call after the screen's widgets render. */
    void extractHoverCard(SpruceGuiGraphics graphics, int mouseX, int mouseY, int screenWidth) {
        if (!this.isVisible() || !this.isMouseHovered()) return;
        var chip = this.chipAt(mouseX, mouseY);
        if (chip == null) return;
        var font = this.client.font;
        var image = chip.image();
        var thumb = this.previews.thumb(image);
        String name = Theme.ellipsize(font, image.name(), 200);
        String info = ImagePreviews.typeLabel(image) + " · " + ImagePreviews.formatSize(image.sizeBytes())
                + (this.editable ? L10n.tr("chat.attach.remove_hint", " · × removes") : "");
        int pad = 7;
        int imageH = thumb == null ? 0 : thumb.height() + 5;
        int w = Math.max(font.width(name), font.width(info)) + pad * 2;
        if (thumb != null) w = Math.max(w, thumb.width() + pad * 2);
        int h = pad + imageH + font.lineHeight + 2 + font.lineHeight + pad - 1;
        int x = Math.clamp(this.getX() - (int) this.scroll + chip.x(), 4, Math.max(4, screenWidth - w - 4));
        int y = this.getY() - h - 5;
        UiShapes.dropShadow(graphics, x, y, w, h, UiShapes.R_PANEL);
        UiShapes.panel(graphics, x, y, w, h, UiShapes.R_PANEL, Theme.PANEL, Theme.BORDER_STRONG, true);
        int line = y + pad;
        if (thumb != null) {
            graphics.vanilla().blit(RenderPipelines.GUI_TEXTURED, thumb.texture(), x + pad, line, 0f, 0f,
                    thumb.width(), thumb.height(), thumb.width(), thumb.height());
            Theme.outline(graphics, x + pad, line, thumb.width(), thumb.height(), Theme.BORDER_SUBTLE);
            line += imageH;
        }
        graphics.text(font, name, x + pad, line, Theme.TEXT, false);
        graphics.text(font, info, x + pad, line + font.lineHeight + 2, Theme.MUTED, false);
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(L10n.tr("chat.attach.narration", "Attached images: %s",
                String.join(", ", this.chips.stream().map(c -> c.image().name()).toList())));
    }
}
