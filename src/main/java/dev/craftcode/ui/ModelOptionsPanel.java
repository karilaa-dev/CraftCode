package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.craftcode.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.craftcode.ui.model.ModelOption;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Settings of the chosen model as labeled rows of compact rounded chips: Effort first, then Speed, then every other
 * option the model declares. Explicitly set options show a crisp accent border; a small green/accent dot marks the
 * value currently in use.
 */
final class ModelOptionsPanel extends ScrollableWidget {
    static final int CHIP_H = 16;
    private static final int LABEL_H = 13;
    private static final int GAP = 4;

    private static String resetText() {
        return L10n.tr("model.options.reset", "Reset");
    }

    private static String resetAllText() {
        return L10n.tr("model.options.reset_all", "Reset all to defaults");
    }

    interface Actions {
        void setOption(String optionId, @Nullable Object value);

        void resetOptions();
    }

    private record Chip(ModelOption option, Object value, String text, boolean inUse, int x, int y, int w) {}

    private record Label(ModelOption option, int y) {}

    private record Note(String text, int y) {}

    private final Actions actions;
    private List<ModelOption> sourceOptions = List.of();
    private List<ModelOption> options = List.of();
    private String driverKind = "";
    private Map<String, Object> chosen = Map.of();
    private @Nullable Map<String, Object> current;
    private final List<Chip> chips = new ArrayList<>();
    private final List<Label> labels = new ArrayList<>();
    private final List<Note> notes = new ArrayList<>();
    private boolean header;
    private int contentHeight;
    private boolean enabled = true;

    ModelOptionsPanel(Position position, int width, int height, Actions actions) {
        super(position, width, height);
        this.actions = actions;
    }

    /**
     * @param driverKind the provider's driver, e.g. {@code claudeAgent}
     * @param chosen     explicit option values of the pending choice
     * @param current    the thread's option values when the pending choice is the model it uses now, else null
     */
    void setData(List<ModelOption> options, String driverKind, Map<String, Object> chosen,
                 @Nullable Map<String, Object> current, boolean enabled) {
        this.enabled = enabled;
        if (this.sourceOptions.equals(options) && this.driverKind.equals(driverKind)
                && this.chosen.equals(chosen) && Objects.equals(this.current, current)) return;
        this.sourceOptions = options;
        this.options = ModelFormat.ordered(options);
        this.driverKind = driverKind;
        this.chosen = Map.copyOf(chosen);
        this.current = current == null ? null : Map.copyOf(current);
        this.layout();
        this.setScroll(this.scroll);
    }

    /** Height the options need at {@code width}, for sizing the panel before it exists. */
    static int measure(List<ModelOption> options, String driverKind, int width) {
        var probe = new ModelOptionsPanel(Position.of(0, 0), width, 10, new Actions() {
            @Override public void setOption(String optionId, @Nullable Object value) {}
            @Override public void resetOptions() {}
        });
        probe.options = ModelFormat.ordered(options);
        probe.driverKind = driverKind;
        probe.layout();
        return probe.contentHeight;
    }

    private void layout() {
        Font font = Minecraft.getInstance().font;
        this.chips.clear();
        this.labels.clear();
        this.notes.clear();
        int inner = this.width - 12;
        int y = 2;
        this.header = this.options.size() > 1;
        if (this.header) y += LABEL_H;
        for (var option : this.options) {
            this.labels.add(new Label(option, y));
            y += LABEL_H;
            int x = 0;
            var values = ModelFormat.choices(option, this.driverKind);
            for (Object value : values) {
                boolean inUse = this.current != null && ModelFormat.valueEquals(ModelFormat.effective(option, this.current), value);
                String text = ModelFormat.valueLabel(option, value);
                int dotSpace = inUse ? 6 : 0;
                int w = Math.min(inner, font.width(text) + 12 + dotSpace);
                if (x > 0 && x + w > inner) {
                    x = 0;
                    y += CHIP_H + GAP;
                }
                this.chips.add(new Chip(option, value, text, inUse, x, y, w));
                x += w + GAP;
            }
            if (!values.isEmpty()) y += CHIP_H + GAP;
            String hint = ModelFormat.promptOnlyHint(option, this.driverKind);
            if (!hint.isEmpty()) {
                this.notes.add(new Note(hint, y));
                y += LABEL_H - 1;
            }
            y += 2;
        }
        this.contentHeight = y;
    }

    private boolean anyOverride() {
        for (var option : this.options) if (ModelFormat.overridden(option, this.chosen)) return true;
        return false;
    }

    @Override
    protected int contentHeight() {
        return this.contentHeight;
    }

    @Override
    protected boolean usesScissor() { return false; }

    private boolean visibleRow(int offset, int height) {
        int y = offset - (int) Math.round(this.scroll);
        return y >= 0 && y + height <= this.height;
    }

    private int right() {
        return this.getEndX() - 8;
    }

    private @Nullable Chip chipAt(double mouseX, double contentY) {
        int left = this.getX() + 4;
        for (var chip : this.chips) {
            if (mouseX >= left + chip.x() && mouseX < left + chip.x() + chip.w()
                    && contentY >= chip.y() && contentY < chip.y() + CHIP_H
                    && this.visibleRow(chip.y(), CHIP_H)) return chip;
        }
        return null;
    }

    /** The overridden option whose "Reset" link is under the mouse. */
    private @Nullable ModelOption resetAt(double mouseX, double contentY) {
        int w = this.client.font.width(resetText());
        if (mouseX < this.right() - w - 4 || mouseX >= this.right() + 2) return null;
        for (var label : this.labels) {
            if (contentY >= label.y() && contentY < label.y() + LABEL_H - 1
                    && this.visibleRow(label.y(), LABEL_H)
                    && ModelFormat.overridden(label.option(), this.chosen)) return label.option();
        }
        return null;
    }

    private boolean onResetAll(double mouseX, double contentY) {
        int w = this.client.font.width(resetAllText());
        return this.header && this.visibleRow(2, LABEL_H) && this.anyOverride() && contentY >= 2 && contentY < 2 + LABEL_H - 1
                && mouseX >= this.right() - w - 4 && mouseX < this.right() + 2;
    }

    @Override
    protected boolean onContentClick(double mouseX, double contentY, int button) {
        if (button != InputConstants.MOUSE_BUTTON_LEFT || !this.enabled) return false;
        if (this.onResetAll(mouseX, contentY)) {
            this.playDownSound();
            this.actions.resetOptions();
            return true;
        }
        var reset = this.resetAt(mouseX, contentY);
        if (reset != null) {
            this.playDownSound();
            this.actions.setOption(reset.id(), null);
            return true;
        }
        var chip = this.chipAt(mouseX, contentY);
        if (chip == null) return false;
        this.playDownSound();
        String id = chip.option().id();
        Object explicit = this.chosen.get(id);
        if (explicit != null && ModelFormat.valueEquals(explicit, chip.value())) this.actions.setOption(id, null);
        else this.actions.setOption(id, chip.value());
        return true;
    }

    @Override
    protected void drawContent(SpruceGuiGraphics graphics, int top, int mouseX, int mouseY) {
        var font = this.client.font;
        int left = this.getX() + 4;
        int right = this.right();
        double contentY = mouseY - top;
        boolean hover = this.enabled && this.isMouseHovered();

        if (this.header && this.visibleRow(2, LABEL_H)) {
            int y = top + 2;
            graphics.text(font, L10n.tr("model.options.title", "Settings"), left, y + 1, Theme.MUTED, false);
            boolean any = this.anyOverride();
            String text = any ? resetAllText() : L10n.tr("model.options.provider_defaults", "Provider defaults");
            boolean hot = any && hover && this.onResetAll(mouseX, contentY);
            if (hot) {
                int tw = font.width(text);
                UiShapes.pill(graphics, right - tw - 3, y, tw + 6, font.lineHeight + 2, Theme.BUTTON_HOVER, 0);
            }
            graphics.text(font, text, right - font.width(text), y + 1,
                    !any ? Theme.FAINT : hot ? Theme.TEXT : Theme.ACCENT, false);
            if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }

        var hotReset = hover ? this.resetAt(mouseX, contentY) : null;
        for (var label : this.labels) {
            var option = label.option();
            int y = top + label.y();
            if (!this.visibleRow(label.y(), LABEL_H)) continue;
            var kind = ModelFormat.kind(option);
            boolean overridden = ModelFormat.overridden(option, this.chosen);
            int limit = right;
            if (overridden) {
                boolean hot = option == hotReset;
                String reset = resetText();
                int rw = font.width(reset);
                if (hot) UiShapes.pill(graphics, right - rw - 3, y, rw + 6, font.lineHeight + 2, Theme.BUTTON_HOVER, 0);
                graphics.text(font, reset, right - rw, y + 1, !this.enabled ? Theme.TEXT_DISABLED : hot ? Theme.TEXT : Theme.ACCENT, false);
                if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
                limit = right - rw - 10;
            }
            String name = Theme.ellipsize(font, ModelFormat.optionName(option), limit - left);
            graphics.text(font, name, left, y + 1, kind == ModelFormat.Kind.OTHER ? Theme.MUTED : Theme.TEXT, false);
            int hx = left + font.width(name) + 8;
            if (hx < limit - 12) {
                String hint = Theme.ellipsize(font, ModelFormat.stateHint(option, this.chosen), limit - hx);
                int color = ModelFormat.unsupported(option, this.chosen) ? Theme.AMBER : Theme.FAINT;
                graphics.text(font, hint, hx, y + 1, color, false);
            }
        }
        for (var note : this.notes) {
            if (!this.visibleRow(note.y(), font.lineHeight)) continue;
            graphics.text(font, Theme.ellipsize(font, note.text(), right - left), left, top + note.y(), Theme.FAINT, false);
        }

        var hovered = hover ? this.chipAt(mouseX, contentY) : null;
        for (var chip : this.chips) {
            int cx = left + chip.x();
            int cy = top + chip.y();
            if (!this.visibleRow(chip.y(), CHIP_H)) continue;
            var option = chip.option();
            boolean selected = ModelFormat.valueEquals(ModelFormat.effective(option, this.chosen), chip.value());
            boolean explicit = selected && ModelFormat.overridden(option, this.chosen);
            boolean hot = chip == hovered;
            int bg = !this.enabled ? Theme.BUTTON_DISABLED
                    : selected ? (hot ? Theme.SELECTED_HOVER : Theme.SELECTED)
                    : hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
            int border = !this.enabled ? Theme.BORDER_SUBTLE
                    : selected ? (explicit ? Theme.ACCENT : Theme.BORDER_STRONG)
                    : hot ? Theme.BORDER_STRONG : Theme.BORDER_SUBTLE;
            UiShapes.pill(graphics, cx, cy, chip.w(), CHIP_H, bg, border);
            int dotSpace = chip.inUse() ? 6 : 0;
            String text = Theme.ellipsize(font, chip.text(), chip.w() - 10 - dotSpace);
            int totalW = dotSpace + font.width(text);
            int tx = cx + (chip.w() - totalW) / 2;
            if (chip.inUse()) {
                int dy = cy + (CHIP_H - 3) / 2;
                graphics.fill(tx, dy, tx + 3, dy + 3, !this.enabled ? Theme.TEXT_DISABLED : Theme.GREEN);
                tx += dotSpace;
            }
            graphics.text(font, text, tx, cy + (CHIP_H - font.lineHeight) / 2 + 1,
                    !this.enabled ? Theme.TEXT_DISABLED : selected ? Theme.TEXT : Theme.TEXT_SOFT, false);
            if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
    }

    @Override
    protected @Nullable Component getNarrationMessage() {
        return Component.literal(L10n.tr("model.options.narration", "Model settings"));
    }
}
