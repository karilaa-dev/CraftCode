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
import java.util.List;
import java.util.Set;

/**
 * Sculpted amber-accented card showing a pending agent question with rounded interactive option chips.
 * Single-select answers on click; multi-select toggles chips (with pixel checkmarks) and submits together.
 */
final class QuestionPanel extends AbstractSpruceWidget {
    interface Actions {
        void answer(PendingQuestion question, List<String> answers);
    }

    static final int FREE_TEXT_ROW = 24;
    private static final int PAD = 9;
    private static final int CHIP_H = 18;

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
        int inner = width - PAD * 2 - 5;
        this.promptLines = font.split(MessageFormatter.format(Theme.orDefault(question.prompt(), L10n.tr("chat.question.default_prompt", "The agent needs your input.")), Theme.TEXT), inner);

        int y = PAD + font.lineHeight + 5 + this.promptLines.size() * (font.lineHeight + 2) + 5;
        int x = 0;
        List<String> labels = new ArrayList<>(question.options());
        if (question.multiSelect() && !labels.isEmpty()) labels.add(""); // submit chip
        boolean any = false;
        for (int i = 0; i < labels.size(); i++) {
            String label = labels.get(i);
            boolean submit = question.multiSelect() && i == labels.size() - 1;
            int checkSpace = question.multiSelect() && !submit ? 11 : 0;
            String shown = Theme.ellipsize(font, chipText(label, submit), Math.max(16, inner - 14 - checkSpace));
            int w = font.width(shown) + 14 + checkSpace;
            w = Math.min(w, inner);
            if (x > 0 && x + w > inner) {
                x = 0;
                y += CHIP_H + 5;
            }
            this.chips.add(new Chip(label, x, y, w, submit));
            x += w + 5;
            any = true;
        }
        if (any) y += CHIP_H + 5;
        this.contentHeight = y;
        this.footer = (question.allowFreeText() ? FREE_TEXT_ROW : 0) + PAD - 4;
        this.height = Math.max(Math.min(this.contentHeight + this.footer, maxHeight), this.footer + 44);
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

    private String chipText(String label, boolean submit) {
        if (submit) return this.selection.isEmpty() ? L10n.tr("chat.question.submit", "Submit")
                : L10n.tr("chat.question.submit_count", "Submit (%s)", this.selection.size());
        return label;
    }

    /** Screen Y for the free-text row, inside the card. */
    int freeTextY() {
        return this.getY() + this.viewport();
    }

    int innerX() {
        return this.getX() + PAD + 5;
    }

    int innerWidth() {
        return this.width - PAD * 2 - 5;
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
        UiShapes.panel(graphics, x, y, this.width, this.height, UiShapes.R_PANEL, Theme.AMBER_BG, Theme.AMBER_BORDER, true);
        UiShapes.accentRail(graphics, x + 2, y + 4, this.height - 8, Theme.AMBER);

        int textX = this.innerX();
        int top = y - (int) this.scroll;
        int lineY = top + PAD;
        graphics.enableScissor(x + 1, y + 1, this.getEndX() - 1, y + this.viewport());
        UiIcons.diamond(graphics, textX, lineY + 1, Theme.AMBER);
        graphics.text(font, Component.literal(this.header).withStyle(s -> s.withBold(true)), textX + 9, lineY, Theme.AMBER, false);
        lineY += font.lineHeight + 5;
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
            int border;
            if (chip.submit()) {
                bg = disabled ? Theme.BUTTON_DISABLED : hot ? Theme.ACCENT_HOVER : Theme.ACCENT_DIM;
                border = disabled ? Theme.BORDER_SUBTLE : hot ? Theme.ACCENT : Theme.mix(Theme.ACCENT_DIM, Theme.isLight() ? 0xFF000000 : 0xFFFFFFFF, 0.16f);
            } else if (selected) {
                bg = hot ? Theme.SELECTED_HOVER : Theme.SELECTED;
                border = Theme.ACCENT;
            } else {
                bg = hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
                border = hot ? Theme.BORDER_STRONG : Theme.BORDER;
            }
            UiShapes.panel(graphics, cx, cy, chip.w(), CHIP_H, UiShapes.R_CONTROL, bg, border, !disabled);
            int textColor = disabled ? Theme.TEXT_DISABLED : chip.submit() ? Theme.ON_ACCENT : Theme.TEXT;
            int checkSpace = selected ? 10 : 0;
            String text = Theme.ellipsize(font, this.chipText(chip.label(), chip.submit()), chip.w() - 12 - checkSpace);
            int totalW = checkSpace + font.width(text);
            int tx = cx + (chip.w() - totalW) / 2;
            int ty = cy + (CHIP_H - font.lineHeight) / 2 + 1;
            if (selected) {
                UiIcons.check(graphics, tx, cy + (CHIP_H - 6) / 2, Theme.ACCENT);
                tx += checkSpace;
            }
            graphics.text(font, text, tx, ty, textColor, false);
            if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        graphics.disableScissor();

        int max = this.maxScroll();
        if (max > 0) {
            int view = this.viewport();
            int thumb = Math.max(12, view * view / Math.max(1, this.contentHeight));
            int thumbY = y + 2 + (int) ((view - 4 - thumb) * (this.scroll / max));
            UiShapes.fillRounded(graphics, this.getEndX() - 6, thumbY, 3, thumb, UiShapes.R_CHIP, Theme.SCROLLBAR_HOT);
            if (this.scroll < max) {
                String more = stripTrailingCaret(L10n.tr("chat.question.scroll_more", "scroll for more ▾"));
                int contentW = font.width(more) + 4 + 5;
                int mx = this.getEndX() - 12 - contentW;
                int my = y + view - font.lineHeight - 3;
                UiShapes.pill(graphics, mx - 4, my - 2, contentW + 8, font.lineHeight + 4, Theme.AMBER_BG, Theme.AMBER_BORDER);
                graphics.text(font, more, mx, my, Theme.AMBER, false);
                UiIcons.caretDown(graphics, mx + font.width(more) + 4, my + 2, Theme.AMBER);
            }
        }
    }

    static String stripTrailingCaret(String text) {
        if (text == null) return "";
        String s = text.stripTrailing();
        if (s.endsWith("▾")) return s.substring(0, s.length() - 1).stripTrailing();
        return text;
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(this.header + ": " + this.question.prompt());
    }
}
