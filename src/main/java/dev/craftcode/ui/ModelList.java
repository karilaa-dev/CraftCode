package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.craftcode.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.ui.model.ModelChoice;
import dev.craftcode.ui.model.ModelProvider;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Grouped, scrollable list of models for the picker: provider headers with count pills, rounded model rows with pixel
 * favorite stars, pixel checkmark on the model in use and accent rail on the player's pending choice.
 */
final class ModelList extends ScrollableWidget {
    static final int HEADER_H = 17;
    static final int ROW_H = 22;
    static final int NOTE_H = 17;
    private static final int STAR_W = 16;

    sealed interface Row permits Header, Item, Note {}

    record Header(String text, String detail) implements Row {}

    /** A model the player can pick. {@code choice} is null for a typed or custom ID with no catalog entry. */
    record Item(ModelProvider provider, String modelId, String label, @Nullable ModelChoice choice, boolean typed,
                boolean showProvider) implements Row {}

    record Note(String text, boolean error) implements Row {}

    interface Marks {
        boolean isFavorite(String providerId, String modelId);

        boolean isCurrent(String providerId, String modelId);

        boolean isDraft(String providerId, String modelId);

        /** Icon that alone identifies the provider in mixed lists, or null to show its name instead. */
        @Nullable ProviderIcon icon(String providerId);
    }

    interface Actions {
        void pick(Item item, boolean activate);

        void toggleFavorite(Item item);
    }

    private final Marks marks;
    private final Actions actions;
    private List<Row> rows = List.of();
    private final List<Integer> rowTops = new ArrayList<>();
    private final List<Integer> selectable = new ArrayList<>();
    private int contentHeight;
    private int highlight = -1; // index into selectable
    private boolean doubleClick;

    ModelList(Position position, int width, int height, Marks marks, Actions actions) {
        super(position, width, height);
        this.marks = marks;
        this.actions = actions;
    }

    void setRows(List<Row> rows) {
        @Nullable Item previous = this.highlighted();
        this.rows = List.copyOf(rows);
        this.rowTops.clear();
        this.selectable.clear();
        int y = 2;
        for (int i = 0; i < this.rows.size(); i++) {
            var row = this.rows.get(i);
            this.rowTops.add(y);
            if (row instanceof Item) this.selectable.add(i);
            y += height(row);
        }
        this.contentHeight = y + 2;
        this.highlight = -1;
        if (previous != null) {
            for (int s = 0; s < this.selectable.size(); s++) {
                var item = (Item) this.rows.get(this.selectable.get(s));
                if (item.provider().id().equals(previous.provider().id()) && item.modelId().equals(previous.modelId())
                        && item.typed() == previous.typed()) {
                    this.highlight = s;
                    break;
                }
            }
        }
        this.setScroll(this.scroll);
    }

    private static int height(Row row) {
        return switch (row) {
            case Header h -> HEADER_H;
            case Item i -> ROW_H;
            case Note n -> NOTE_H;
        };
    }

    @Nullable Item highlighted() {
        if (this.highlight < 0 || this.highlight >= this.selectable.size()) return null;
        return (Item) this.rows.get(this.selectable.get(this.highlight));
    }

    boolean hasItems() {
        return !this.selectable.isEmpty();
    }

    /** Highlights the row for the given model, or the first row when it is not listed. */
    void highlightModel(@Nullable String providerId, @Nullable String modelId) {
        this.highlight = this.selectable.isEmpty() ? -1 : 0;
        if (providerId != null && modelId != null) {
            for (int s = 0; s < this.selectable.size(); s++) {
                var item = (Item) this.rows.get(this.selectable.get(s));
                if (!item.typed() && item.provider().id().equals(providerId) && item.modelId().equals(modelId)) {
                    this.highlight = s;
                    break;
                }
            }
        }
        this.ensureVisible();
    }

    void highlightFirst() {
        this.highlight = this.selectable.isEmpty() ? -1 : 0;
        this.setScroll(0);
    }

    void moveHighlight(int delta) {
        if (this.selectable.isEmpty()) return;
        if (this.highlight < 0) this.highlight = delta > 0 ? 0 : this.selectable.size() - 1;
        else this.highlight = Math.clamp(this.highlight + delta, 0, this.selectable.size() - 1);
        this.ensureVisible();
    }

    void page(int direction) {
        this.moveHighlight(direction * Math.max(1, this.height / ROW_H - 1));
    }

    private void ensureVisible() {
        if (this.highlight < 0) return;
        int index = this.selectable.get(this.highlight);
        int top = this.rowTops.get(index);
        if (index > 0 && this.rows.get(index - 1) instanceof Header) top = this.rowTops.get(index - 1);
        int bottom = this.rowTops.get(index) + ROW_H;
        if (top < this.scroll) this.setScroll(top);
        else if (bottom > this.scroll + this.height) this.setScroll(bottom - this.height);
    }

    @Override
    protected int contentHeight() {
        return this.contentHeight;
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        this.doubleClick = doubleClick;
        return super.onMouseClick(event, doubleClick);
    }

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT) return false;
        for (int s = 0; s < this.selectable.size(); s++) {
            int index = this.selectable.get(s);
            int top = this.rowTops.get(index);
            if (contentY < top || contentY >= top + ROW_H) continue;
            var item = (Item) this.rows.get(index);
            this.highlight = s;
            this.playDownSound();
            if (!item.typed() && mouseX < this.getX() + 4 + STAR_W + 4) this.actions.toggleFavorite(item);
            else this.actions.pick(item, this.doubleClick);
            return true;
        }
        return false;
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        int left = this.getX() + 4;
        int right = this.getEndX() - 8;
        boolean inside = this.isMouseHovered();
        var highlighted = this.highlighted();

        for (int i = 0; i < this.rows.size(); i++) {
            var row = this.rows.get(i);
            int y = top + this.rowTops.get(i);
            int h = height(row);
            if (y + h < this.getY() || y > this.getEndY()) continue;
            switch (row) {
                case Header header -> {
                    int ty = y + h - font.lineHeight - 2;
                    String detail = header.detail();
                    int dw = detail.isEmpty() ? 0 : font.width(detail) + 6;
                    String text = header.text();
                    int tx = left + 4;
                    if (text.startsWith("★")) {
                        UiIcons.star(graphics, tx, ty, Theme.AMBER, true);
                        tx += 10;
                        text = text.substring(1).stripLeading();
                    }
                    graphics.text(font, Theme.ellipsize(font, text, right - tx - 4 - (dw == 0 ? 0 : dw + 6)),
                            tx, ty, Theme.MUTED, false);
                    if (dw > 0) {
                        UiShapes.pill(graphics, right - dw - 1, ty - 1, dw, font.lineHeight + 2, Theme.BUTTON, 0);
                        graphics.text(font, detail, right - dw + 2, ty, Theme.FAINT, false);
                    }
                }
                case Note note -> graphics.text(font, Theme.ellipsize(font, note.text(), right - left - 8), left + 4,
                        y + (h - font.lineHeight) / 2 + 1, note.error() ? Theme.RED : Theme.FAINT, false);
                case Item item -> this.drawItem(graphics, item, item == highlighted, left, right, y, inside, mouseX, mouseY);
            }
        }
    }

    private void drawItem(SpruceGuiGraphics graphics, Item item, boolean keyboard, int left, int right, int y,
                          boolean inside, int mouseX, int mouseY) {
        var font = this.client.font;
        String pid = item.provider().id();
        boolean hovered = inside && mouseY >= y && mouseY < y + ROW_H && mouseX >= left && mouseX < right;
        boolean draft = !item.typed() && this.marks.isDraft(pid, item.modelId());
        boolean current = !item.typed() && this.marks.isCurrent(pid, item.modelId());
        int rowH = ROW_H - 1;
        int bg = draft ? (hovered ? Theme.SELECTED_HOVER : Theme.SELECTED) : hovered || keyboard ? Theme.HOVER : 0;
        if (bg != 0) UiShapes.fillRounded(graphics, left, y, right - left, rowH, UiShapes.R_CONTROL, bg);
        if (draft) UiShapes.accentRail(graphics, left, y + 3, rowH - 6, Theme.ACCENT);
        if (keyboard) {
            UiShapes.outlineRounded(graphics, left, y, right - left, rowH, UiShapes.R_CONTROL,
                    draft ? Theme.ACCENT : Theme.BORDER_STRONG);
        }
        if (hovered) graphics.requestCursor(CursorTypes.POINTING_HAND);
        int textY = y + (rowH - font.lineHeight) / 2 + 1;

        int x = left + 5;
        if (item.typed()) {
            UiIcons.plus(graphics, x + 3, y + (rowH - 7) / 2, Theme.ACCENT);
        } else {
            boolean favorite = this.marks.isFavorite(pid, item.modelId());
            boolean starHot = hovered && mouseX < left + 4 + STAR_W + 4;
            int starColor = favorite ? Theme.AMBER : starHot ? Theme.TEXT_SOFT : Theme.FAINT;
            if (starHot) {
                UiShapes.pill(graphics, x, y + 3, STAR_W - 2, rowH - 6, Theme.BUTTON_HOVER, Theme.BORDER_SUBTLE);
            }
            UiIcons.star(graphics, x + (STAR_W - 2 - 7) / 2, y + (rowH - 7) / 2, starColor, favorite);
        }
        x += STAR_W + 3;

        // Right side: crisp pixel check for the model in use, then provider (icon or name) or ID.
        int rx = right - 6;
        if (current) {
            UiIcons.check(graphics, rx - 7, y + (rowH - 6) / 2, Theme.GREEN);
            rx -= 13;
        }
        @Nullable ProviderIcon icon = item.showProvider() && !item.typed() ? this.marks.icon(pid) : null;
        if (icon != null && rx - x <= ProviderIcon.SIZE + 30) icon = null;
        if (icon != null) {
            int ix = rx - ProviderIcon.SIZE;
            int iy = y + (rowH - ProviderIcon.SIZE) / 2;
            icon.draw(graphics, ix, iy, false);
            if (hovered && mouseX >= ix && mouseX < rx && mouseY >= iy && mouseY < iy + ProviderIcon.SIZE) {
                graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(item.provider().label()), 200),
                        mouseX, mouseY);
            }
            rx = ix - 6;
        }
        String side = item.typed() ? item.provider().label()
                : item.showProvider() ? (icon != null ? "" : item.provider().label())
                : item.label().equals(item.modelId()) ? "" : item.modelId();
        int labelColor = draft || hovered || keyboard ? Theme.TEXT : Theme.TEXT_SOFT;
        String label = item.typed() ? L10n.tr("model.list.use_id", "Use model ID “%s”", item.modelId()) : item.label();
        int labelW = font.width(label);
        int room = rx - x;
        if (!side.isEmpty() && room - Math.min(labelW, room * 2 / 3) > 30) {
            int sideRoom = room - Math.min(labelW, room * 2 / 3) - 8;
            String shown = Theme.ellipsize(font, side, sideRoom);
            graphics.text(font, shown, rx - font.width(shown), textY, Theme.FAINT, false);
            room -= font.width(shown) + 8;
        }
        graphics.text(font, Theme.ellipsize(font, label, room), x, textY, item.typed() ? Theme.ACCENT : labelColor, false);
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        var item = this.highlighted();
        return Component.literal(item == null ? L10n.tr("model.list.narration", "Models")
                : L10n.tr("model.list.narration.item", "Model %1$s, %2$s", item.label(), item.provider().label()));
    }
}
