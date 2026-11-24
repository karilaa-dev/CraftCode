package dev.t3mc.ui;

import dev.t3mc.L10n;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.AbstractSpruceWidget;
import dev.t3mc.ui.model.PendingQuestion;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Card showing a pending question with its options as chips. Single-select answers on click;
 * multi-select toggles chips and submits them together. The free-text row (if any) is a
 * separate text field the screen places at {@link #freeTextY()}, pinned to the bottom of the card.
 * When the content is taller than the card, the prompt and chips scroll (mouse wheel / scrollbar).
 */
final class QuestionPanel extends AbstractSpruceWidget {
    interface Actions {
        void answer(PendingQuestion question, List<String> answers);
    }

    static final int FREE_TEXT_ROW = 22;
    private static final int PAD = 8;
    private static final int CHIP_H = 16;

    private record Chip(String label, int x, int y, int w, boolean submit) {}

    private final PendingQuestion question;
    private final String header;
    private final Set<String> selection;
    private final Actions actions;
    private final List<FormattedCharSequence> promptLines;
    private final List<Chip> chips = new ArrayList<>();
    private final int contentHeight;
    private final int footer;
    private double scroll;
    private boolean readOnly;

    void setReadOnly(boolean readOnly) { this.readOnly = readOnly; }

    /**
     * @param selection live, mutable set of chosen options for a multi-select question; kept by the
     *                  screen so choices survive re-layouts
     */
    QuestionPanel(Position position, int width, int maxHeight, PendingQuestion question, int index, int total,
                  Set<String> selection, Actions actions) {
        super(position);
        this.question = question;
        this.selection = selection;
        this.actions = actions;
        this.width = width;
        this.header = (question.multiSelect() ? L10n.tr("chat.question.multi", "Choose one or more")
                : L10n.tr("chat.question.single", "Question"))
                + (total > 1 ? "  ·  " + L10n.tr("chat.question.position", "%1$s of %2$s", index + 1, total) : "");

        Font font = Minecraft.getInstance().font;
        int inner = width - PAD * 2 - 4;
        this.promptLines = font.split(MessageFormatter.format(Theme.orDefault(question.prompt(), L10n.tr("chat.question.default_prompt", "The agent needs your input.")), Theme.TEXT), inner);

        int y = PAD + font.lineHeight + 4 + this.promptLines.size() * (font.lineHeight + 2) + 4;
        int x = 0;
        List<String> labels = new ArrayList<>(question.options());
        if (question.multiSelect() && !labels.isEmpty()) labels.add(""); // the submit chip; its text comes from chipText
        boolean any = false;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            boolean submit = question.multiSelect() && i == labels.size() - 1;
            String shown = Theme.ellipsize(font, chipText(label, submit, false), inner - 12);
            int w = font.width(shown) + 12 + (question.multiSelect() && !submit ? font.width("✓ ") : 0);
            w = Math.min(w, inner);
            if (x > 0 && x + w > inner) {
                x = 0;
                y += CHIP_H + 4;
            }
            this.chips.add(new Chip(label, x, y, w, submit));
            x += w + 4;
            any = true;
        }
        if (any) y += CHIP_H + 4;
        this.contentHeight = y;
        this.footer = (question.allowFreeText() ? FREE_TEXT_ROW : 0) + PAD - 4;
        this.height = Math.max(Math.min(this.contentHeight + this.footer, maxHeight), this.footer + 40);
    }

    /** Height of the scrolling region (prompt + chips). */
    private int viewport() {
        return this.height - this.footer;
    }

    private int maxScroll() {
        return Math.max(0, this.contentHeight - this.viewport());
    }

    boolean scrolls() {
        return this.maxScroll() > 0;
    }

    private String chipText(String label, boolean submit, boolean selected) {
        if (submit) return this.selection.isEmpty() ? L10n.tr("chat.question.submit", "Submit")
                : L10n.tr("chat.question.submit_count", "Submit (%s)", this.selection.size());
        if (this.question.multiSelect()) return (selected ? "✓ " : "") + label;
        return label;
    }

    /** Screen Y for the free-text row, inside the card. */
    int freeTextY() {
        return this.getY() + this.viewport();
    }

    int innerX() {
        return this.getX() + PAD + 4;
    }

    int innerWidth() {
        return this.width - PAD * 2 - 4;
    }

    private @Nullable Chip chipAt(double mouseX, double mouseY) {
        if (mouseY < this.getY() || mouseY >= this.getY() + this.viewport()) return null;
        for (var chip : this.chips) {
            int cx = this.innerX() + chip.x();
            int cy = this.getY() + chip.y() - (int) this.scroll;
            if (mouseX >= cx && mouseX < cx + chip.w() && mouseY >= cy && mouseY < cy + CHIP_H) return chip;
        }
        return null;
    }

    @Override
    public boolean requiresCursor() {
        return true;
    }

    @Override
    protected boolean onMouseScroll(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (!this.scrolls()) return false;
        this.scroll = Math.clamp(this.scroll - scrollY * 18, 0, this.maxScroll());
        return true;
    }

    @Override
    protected boolean onMouseDrag(MouseButtonEvent event, double deltaX, double deltaY) {
        if (!this.scrolls() || event.x() < this.getEndX() - 8) return false;
        double progress = (event.y() - this.getY()) / Math.max(1, this.viewport());
        this.scroll = Math.clamp(progress * this.maxScroll(), 0, this.maxScroll());
        return true;
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.scrolls() && event.x() >= this.getEndX() - 8 && event.y() < this.getY() + this.viewport()) {
            return this.onMouseDrag(event, 0, 0);
        }
        if (readOnly || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        var chip = this.chipAt(event.x(), event.y());
        if (chip == null) return false;
        if (chip.submit()) {
            if (this.selection.isEmpty()) return true;
            this.playDownSound();
            this.actions.answer(this.question, this.question.options().stream().filter(this.selection::contains).toList());
        } else if (this.question.multiSelect()) {
            this.playDownSound();
            if (!this.selection.remove(chip.label())) this.selection.add(chip.label());
        } else {
            this.playDownSound();
            this.actions.answer(this.question, List.of(chip.label()));
        }
        return true;
    }

    @Override
    protected void extractWidgetRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var font = this.client.font;
        int x = this.getX();
        int y = this.getY();
        graphics.fill(x, y, this.getEndX(), this.getEndY(), Theme.AMBER_BG);
        graphics.fill(x, y, x + 2, this.getEndY(), Theme.AMBER);
        Theme.outline(graphics, x, y, this.width, this.height, Theme.AMBER_BORDER);

        int textX = this.innerX();
        int top = y - (int) this.scroll;
        int lineY = top + PAD;
        graphics.enableScissor(x, y, this.getEndX(), y + this.viewport());
        graphics.text(font, this.header, textX, lineY, Theme.AMBER, false);
        lineY += font.lineHeight + 4;
        for (var line : this.promptLines) {
            graphics.text(font, line, textX, lineY, Theme.TEXT, false);
            lineY += font.lineHeight + 2;
        }

        var hoveredChip = this.isMouseHovered() ? this.chipAt(mouseX, mouseY) : null;
        for (var chip : this.chips) {
            int cx = textX + chip.x();
            int cy = top + chip.y();
            boolean selected = !chip.submit() && this.selection.contains(chip.label());
            boolean disabled = readOnly || chip.submit() && this.selection.isEmpty();
            boolean hot = chip == hoveredChip && !disabled;
            int bg;
            if (chip.submit()) bg = disabled ? Theme.BUTTON_DISABLED : hot ? Theme.ACCENT_HOVER : Theme.ACCENT_DIM;
            else if (selected) bg = hot ? Theme.SELECTED_HOVER : Theme.SELECTED;
            else bg = hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
            graphics.fill(cx, cy, cx + chip.w(), cy + CHIP_H, bg);
            if (selected) Theme.outline(graphics, cx, cy, chip.w(), CHIP_H, Theme.ACCENT);
            String text = Theme.ellipsize(font, this.chipText(chip.label(), chip.submit(), selected), chip.w() - 12);
            graphics.text(font, text, cx + (chip.w() - font.width(text)) / 2, cy + (CHIP_H - font.lineHeight) / 2 + 1,
                    disabled ? Theme.TEXT_DISABLED : chip.submit() ? Theme.ON_ACCENT : Theme.TEXT, false);
            if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        graphics.disableScissor();

        int max = this.maxScroll();
        if (max > 0) {
            int view = this.viewport();
            int thumb = Math.max(12, view * view / Math.max(1, this.contentHeight));
            int thumbY = y + (int) ((view - thumb) * (this.scroll / max));
            graphics.fill(this.getEndX() - 5, thumbY + 1, this.getEndX() - 2, thumbY + thumb - 1, Theme.SCROLLBAR_HOT);
            if (this.scroll < max) {
                String more = L10n.tr("chat.question.scroll_more", "scroll for more ▾");
                int mx = this.getEndX() - 10 - font.width(more);
                int my = y + view - font.lineHeight - 2;
                graphics.fill(mx - 3, my - 2, this.getEndX() - 7, my + font.lineHeight + 1, Theme.AMBER_BG);
                graphics.text(font, more, mx, my, Theme.AMBER, false);
            }
        }
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(this.header + ": " + this.question.prompt());
    }
}
