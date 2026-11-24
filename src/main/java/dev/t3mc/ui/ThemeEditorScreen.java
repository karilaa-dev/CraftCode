package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.t3mc.ClientController;
import dev.t3mc.L10n;
import dev.t3mc.ui.model.ColorTheme;
import dev.t3mc.ui.model.SettingsView;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Advanced theme editor: the twelve palette colors of a {@link ColorTheme.Palette}, edited as a draft with a live local
 * preview. Nothing changes outside this screen until <i>Save as custom</i>, which stores the palette and switches to the
 * Custom theme; <i>Cancel</i> (or Esc) discards the draft.
 *
 * <p>Pick a color with the swatch row (or by clicking that part of the preview), then type {@code #RGB}/{@code #RRGGBB}
 * or adjust the R/G/B sliders. <i>Start from</i> replaces the draft with a preset or the saved custom palette;
 * <i>Reset</i> returns to the colors the editor opened with. The draft survives window resizes.
 */
final class ThemeEditorScreen extends SpruceScreen {
    private record Hit(int x0, int y0, int x1, int y1, int slot) {
        boolean contains(double x, double y) {
            return x >= this.x0 && x < this.x1 && y >= this.y0 && y < this.y1;
        }
    }

    /** A source the draft can start from: a preset, or (when one is stored) the saved custom palette. */
    private record Source(String label, ColorTheme.Palette palette) {}

    private final Screen parent;
    private final ClientController controller;
    private final List<Source> sources = new ArrayList<>();
    private final int initialSourceIndex;
    private final int[] draft;
    private int sourceIndex;
    private int selected;
    private String hexDraft;
    private boolean hexInvalid;
    private String error = "";

    private final List<SwatchButton> swatchButtons = new ArrayList<>();
    private final List<ChannelSlider> sliders = new ArrayList<>();
    private final List<Hit> hits = new ArrayList<>();
    private @Nullable InputField hexField;
    private @Nullable FlatButton saveButton;
    private @Nullable FlatButton sourceButton;
    private boolean syncingHex;
    private int panelX, panelW, panelBottom;
    private int slotY, previewX, previewY, previewW, previewH, noteY, contentX, contentW;

    ThemeEditorScreen(Screen parent, ClientController controller) {
        super(Component.literal(L10n.tr("theme.editor.title", "Customize theme")));
        this.parent = parent;
        this.controller = controller;
        SettingsView settings = controller.snapshot().settings();
        if (settings.hasCustomPalette())
            this.sources.add(new Source(L10n.tr("theme.editor.source.saved", "Saved custom"), settings.customPalette()));
        for (var theme : ColorTheme.values())
            if (theme != ColorTheme.CUSTOM) this.sources.add(new Source(theme.label(), theme.palette()));
        // Open on what is on screen now: the saved custom palette when Custom is active, otherwise the active preset.
        this.sourceIndex = 0;
        for (int i = 0; i < this.sources.size(); i++) {
            boolean match = settings.theme() == ColorTheme.CUSTOM
                    ? i == 0 && settings.hasCustomPalette()
                    : this.sources.get(i).palette().equals(settings.theme().palette()) && !(settings.hasCustomPalette() && i == 0);
            if (match) {
                this.sourceIndex = i;
                break;
            }
        }
        this.initialSourceIndex = this.sourceIndex;
        this.draft = HexColor.toArray(settings.effectivePalette());
        this.hexDraft = HexColor.format(this.draft[0]);
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.swatchButtons.clear();
        this.sliders.clear();
        this.panelW = Math.min(400, this.width - 16);
        this.panelX = (this.width - this.panelW) / 2;
        boolean tight = this.height < 270;
        int gap = tight ? 3 : 6;
        int x = this.panelX + 10;
        int w = this.panelW - 20;
        this.contentX = x;
        this.contentW = w;
        int y = tight ? 30 : 34;

        // Start from: ‹ [source] › [Reset]
        String resetLabel = L10n.tr("theme.editor.reset", "Reset");
        int resetW = Math.min(this.buttonWidth(resetLabel, 44), w / 4);
        int arrowW = 18;
        int midW = w - resetW - 4 - (arrowW + 4) * 2;
        var prev = new FlatButton(Position.of(x, y), arrowW, 18, "‹", b -> this.cycleSource(-1));
        prev.setTooltip(Component.literal(L10n.tr("theme.editor.source.previous", "Previous starting palette")));
        this.addRenderableWidget(prev);
        this.sourceButton = this.addRenderableWidget(new FlatButton(Position.of(x + arrowW + 4, y), midW, 18,
                this.sourceLabel(), b -> this.cycleSource(1)));
        this.sourceButton.setTooltip(Component.literal(L10n.tr("theme.editor.source.tooltip",
                "Replace every color with this palette. Click or use ‹ › to choose another.")));
        var next = new FlatButton(Position.of(x + arrowW + 4 + midW + 4, y), arrowW, 18, "›", b -> this.cycleSource(1));
        next.setTooltip(Component.literal(L10n.tr("theme.editor.source.next", "Next starting palette")));
        this.addRenderableWidget(next);
        var reset = new FlatButton(Position.of(x + w - resetW, y), resetW, 18, resetLabel, b -> this.useSource(this.initialSourceIndex));
        reset.setTooltip(Component.literal(L10n.tr("theme.editor.reset.tooltip", "Back to the colors you opened the editor with")));
        this.addRenderableWidget(reset);
        y += 18 + gap;

        // Swatch row: one button per palette color.
        int cellGap = 2;
        int cellW = Math.max(8, (w - cellGap * (HexColor.SLOTS - 1)) / HexColor.SLOTS);
        int rowW = cellW * HexColor.SLOTS + cellGap * (HexColor.SLOTS - 1);
        int sx = x + (w - rowW) / 2;
        for (int i = 0; i < HexColor.SLOTS; i++) {
            var swatch = this.addRenderableWidget(new SwatchButton(Position.of(sx + i * (cellW + cellGap), y), cellW, 16, i));
            this.swatchButtons.add(swatch);
        }
        y += 16 + gap;

        // Selected color: name on the left, #RRGGBB on the right.
        this.slotY = y;
        int hexW = Math.max(64, this.font.width("#DDDDDD") + 16);
        this.syncingHex = true;
        this.hexField = new InputField(Position.of(x + w - hexW, y), hexW,
                L10n.tr("theme.editor.hex.narration", "Hex color"), "#RRGGBB");
        this.hexField.setTextPredicate(HexColor::allowedDraft);
        this.hexField.setText(this.hexDraft);
        this.syncingHex = false;
        this.hexField.setChangedListener(this::onHexChanged);
        this.hexField.onEnter(this::commitHex);
        this.hexField.setTooltip(Component.literal(L10n.tr("theme.editor.hex.tooltip",
                "Type #RGB or #RRGGBB. Enter confirms; an invalid value goes back to the last good color.")));
        this.addRenderableWidget(this.hexField);
        y += 18 + gap;

        // R/G/B sliders beside the live preview (stacked when the panel is too narrow for both).
        int actionsH = 20;
        int noteH = this.font.lineHeight + 3;
        int bottomLimit = this.height - 10 - actionsH - 4 - noteH;
        boolean sideBySide = w >= 240;
        int slidersW = sideBySide ? Math.max(100, (w - 8) * 2 / 5) : w;
        int sliderH = 18;
        int sliderGap = tight ? 2 : 4;
        String[] names = {L10n.tr("theme.editor.red", "R"), L10n.tr("theme.editor.green", "G"), L10n.tr("theme.editor.blue", "B")};
        String[] tips = {
                L10n.tr("theme.editor.red.tooltip", "Red"), L10n.tr("theme.editor.green.tooltip", "Green"),
                L10n.tr("theme.editor.blue.tooltip", "Blue")};
        String keys = L10n.tr("theme.editor.slider.keys",
                "Click or drag. ←/→ ±1, Shift+←/→ or Page Up/Down ±16, Home/End 0/255.");
        for (int c = 0; c < 3; c++) {
            int channel = c;
            var slider = new ChannelSlider(Position.of(x, y + c * (sliderH + sliderGap)), slidersW, sliderH, channel, names[c],
                    () -> this.draft[this.selected], value -> this.onChannel(channel, value));
            slider.setTooltip(Component.literal(tips[c] + "\n" + keys));
            this.sliders.add(this.addRenderableWidget(slider));
        }
        int slidersBottom = y + 3 * sliderH + 2 * sliderGap;
        if (sideBySide) {
            this.previewX = x + slidersW + 8;
            this.previewY = y;
            this.previewW = w - slidersW - 8;
            this.previewH = Math.clamp(bottomLimit - y, 3 * sliderH + 2 * sliderGap, 130);
            y = Math.max(slidersBottom, this.previewY + this.previewH) + 3;
        } else {
            this.previewX = x;
            this.previewY = slidersBottom + gap;
            this.previewW = w;
            this.previewH = Math.clamp(bottomLimit - this.previewY, 40, 110);
            y = this.previewY + this.previewH + 3;
        }
        this.noteY = y;
        y += noteH;

        // Actions
        String saveLabel = L10n.tr("theme.editor.save", "Save as custom");
        String cancelLabel = L10n.tr("settings.cancel", "Cancel");
        int saveW = this.buttonWidth(saveLabel, 100);
        int cancelW = this.buttonWidth(cancelLabel, 56);
        this.saveButton = this.addRenderableWidget(new FlatButton(Position.of(x + w - saveW, y), saveW, actionsH, saveLabel,
                FlatButton.Kind.PRIMARY, b -> this.save()));
        this.saveButton.setTooltip(Component.literal(L10n.tr("theme.editor.save.tooltip",
                "Store these colors as your Custom theme and switch to it.")));
        this.addRenderableWidget(new FlatButton(Position.of(x + w - saveW - 4 - cancelW, y), cancelW, actionsH, cancelLabel,
                b -> this.onClose()));
        this.panelBottom = Math.min(y + actionsH + 8, this.height);
        this.refreshState();

        if (this.getFocused() == null && this.selected < this.swatchButtons.size())
            this.setFocused(this.swatchButtons.get(this.selected));
    }

    private int buttonWidth(String label, int min) {
        return Math.max(min, this.font.width(label) + 12);
    }

    private String sourceLabel() {
        var source = this.sources.get(this.sourceIndex);
        return L10n.tr("theme.editor.source", "Start from: %s", source.label());
    }

    static String slotName(int slot) {
        return switch (slot) {
            case 0 -> L10n.tr("theme.editor.slot.canvas", "Background");
            case 1 -> L10n.tr("theme.editor.slot.sidebar", "Sidebar");
            case 2 -> L10n.tr("theme.editor.slot.panel", "Panels");
            case 3 -> L10n.tr("theme.editor.slot.input", "Input fields");
            case 4 -> L10n.tr("theme.editor.slot.text", "Text");
            case 5 -> L10n.tr("theme.editor.slot.muted", "Muted text");
            case 6 -> L10n.tr("theme.editor.slot.border", "Borders");
            case 7 -> L10n.tr("theme.editor.slot.accent", "Accent");
            case 8 -> L10n.tr("theme.editor.slot.selected", "Selection");
            case 9 -> L10n.tr("theme.editor.slot.user_bubble", "Your messages");
            case 10 -> L10n.tr("theme.editor.slot.hover", "Hover");
            default -> L10n.tr("theme.editor.slot.primary_text", "Text on accent");
        };
    }

    // ---------------------------------------------------------------- editing

    private void select(int slot) {
        this.selected = slot;
        this.showSelectedHex();
        this.refreshState();
    }

    private void cycleSource(int direction) {
        this.useSource(Math.floorMod(this.sourceIndex + direction, this.sources.size()));
    }

    private void useSource(int index) {
        this.sourceIndex = index;
        System.arraycopy(HexColor.toArray(this.sources.get(this.sourceIndex).palette()), 0, this.draft, 0, HexColor.SLOTS);
        if (this.sourceButton != null) this.sourceButton.setLabel(this.sourceLabel());
        this.showSelectedHex();
        this.refreshState();
    }

    private void onChannel(int channel, int value) {
        this.draft[this.selected] = HexColor.withChannel(this.draft[this.selected], channel, value);
        this.showSelectedHex();
        this.refreshState();
    }

    private void onHexChanged(String text) {
        if (this.syncingHex) return;
        this.hexDraft = text;
        int color = HexColor.parse(text);
        this.hexInvalid = color == HexColor.INVALID;
        if (!this.hexInvalid) this.draft[this.selected] = color;
        this.error = "";
        for (var slider : this.sliders) slider.sync();
        this.refreshState();
    }

    /** Enter in the hex field: normalize a valid value to #RRGGBB, or restore the last valid color. */
    private void commitHex() {
        this.showSelectedHex();
        this.refreshState();
    }

    /** Puts the selected color into the hex field (as #RRGGBB) and the sliders, clearing any typing error. */
    private void showSelectedHex() {
        this.hexDraft = HexColor.format(this.draft[this.selected]);
        this.hexInvalid = false;
        if (this.hexField != null) {
            this.syncingHex = true;
            this.hexField.setText(this.hexDraft);
            this.hexField.setCursorToEnd();
            this.syncingHex = false;
        }
        for (var slider : this.sliders) slider.sync();
    }

    private void refreshState() {
        if (this.saveButton != null) this.saveButton.setActive(!this.hexInvalid);
        for (var swatch : this.swatchButtons) swatch.refresh();
    }

    private void save() {
        if (this.hexInvalid) return;
        if (!this.controller.setCustomTheme(HexColor.toPalette(this.draft))) {
            this.error = L10n.tr("theme.editor.error.save", "Could not save the custom theme. Your colors are still here.");
            return;
        }
        Theme.sync(this.controller.snapshot().settings());
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public boolean isPauseScreen() {
        return this.parent.isPauseScreen();
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        // Esc inside a bad hex value first restores the field, a second Esc cancels the editor.
        if (event.isEscape() && this.hexInvalid && this.hexField != null && this.getFocused() == this.hexField) {
            this.commitHex();
            return true;
        }
        if (event.hasControlDown() && event.key() == InputConstants.KEY_S && !this.hexInvalid) {
            this.save();
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            var hit = this.hitAt(event.x(), event.y());
            if (hit != null) {
                this.select(hit.slot());
                this.setFocused(this.swatchButtons.get(hit.slot()));
                return true;
            }
        }
        return super.mouseClicked(event, doubleClick);
    }

    private @Nullable Hit hitAt(double x, double y) {
        for (int i = this.hits.size() - 1; i >= 0; i--) if (this.hits.get(i).contains(x, y)) return this.hits.get(i);
        return null;
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (Theme.seeThrough()) Theme.seeThroughBackdrop(this.minecraft, graphics, this.width, this.height);
        else super.extractBackground(graphics, mouseX, mouseY, delta);
        graphics.fill(0, 0, this.width, this.height, Theme.BG);
        graphics.fill(this.panelX, 24, this.panelX + this.panelW, this.panelBottom, Theme.PANEL_SURFACE);
        graphics.fill(this.panelX, 24, this.panelX + this.panelW, 25, Theme.BORDER);
        graphics.fill(this.panelX, this.panelBottom - 1, this.panelX + this.panelW, this.panelBottom, Theme.BORDER);
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var heading = this.getTitle().copy().withStyle(s -> s.withBold(true));
        Theme.plate(graphics, this.panelX + 10, 10, this.font.width(heading), this.font.lineHeight, Theme.TEXT_PLATE);
        graphics.text(this.font, heading, this.panelX + 10, 10, Theme.TEXT, false);

        // Selected color: chip + name (+ number) left of the hex field.
        int chipY = this.slotY + 3;
        graphics.fill(this.contentX, chipY, this.contentX + 12, chipY + 12, this.draft[this.selected]);
        Theme.outline(graphics, this.contentX, chipY, 12, 12, Theme.BORDER);
        int hexX = this.hexField == null ? this.contentX + this.contentW : this.hexField.getX();
        String name = L10n.tr("theme.editor.slot.position", "%1$s  %2$s/%3$s", slotName(this.selected),
                this.selected + 1, HexColor.SLOTS);
        graphics.text(this.font, Theme.ellipsize(this.font, name, hexX - this.contentX - 22), this.contentX + 18,
                this.slotY + 5, Theme.TEXT, false);

        this.drawPreview(graphics, mouseX, mouseY);

        String note;
        int noteColor;
        if (this.hexInvalid) {
            note = L10n.tr("theme.editor.error.hex", "Use #RGB or #RRGGBB, like #F80 or #FF8800. Enter restores %s.",
                    HexColor.format(this.draft[this.selected]));
            noteColor = Theme.RED;
        } else if (!this.error.isEmpty()) {
            note = this.error;
            noteColor = Theme.RED;
        } else {
            note = L10n.tr("theme.editor.hint", "Preview only. Click part of the preview to edit its color.");
            noteColor = Theme.FAINT;
        }
        graphics.text(this.font, Theme.ellipsize(this.font, note, this.contentW), this.contentX, this.noteY + 1, noteColor, false);

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /** A miniature workspace painted only from the draft, so the real UI stays unchanged until Save. */
    private void drawPreview(SpruceGuiGraphics graphics, int mouseX, int mouseY) {
        this.hits.clear();
        int[] c = this.draft;
        int x0 = this.previewX, y0 = this.previewY, x1 = x0 + this.previewW, y1 = y0 + this.previewH;
        int line = this.font.lineHeight;
        // canvas + outer border
        graphics.fill(x0, y0, x1, y1, c[0]);
        this.hits.add(new Hit(x0, y0, x1, y1, 0));
        // sidebar
        int sideW = Math.max(36, this.previewW * 3 / 10);
        int sx1 = x0 + sideW;
        graphics.fill(x0, y0, sx1, y1, c[1]);
        graphics.fill(sx1, y0, sx1 + 1, y1, c[6]);
        this.hits.add(new Hit(x0, y0, sx1, y1, 1));
        int rowH = line + 3;
        int ry = y0 + 4;
        String[] rows = {
                L10n.tr("theme.editor.preview.thread", "Thread"),
                L10n.tr("theme.editor.preview.hover", "Hovered"),
                L10n.tr("theme.editor.preview.other", "Older")};
        for (int i = 0; i < rows.length && ry + rowH <= y1 - 2; i++, ry += rowH + 1) {
            int fill = i == 0 ? c[8] : i == 1 ? c[10] : c[1];
            if (i < 2) {
                graphics.fill(x0 + 2, ry, sx1 - 2, ry + rowH, fill);
                this.hits.add(new Hit(x0 + 2, ry, sx1 - 2, ry + rowH, i == 0 ? 8 : 10));
            }
            graphics.text(this.font, Theme.ellipsize(this.font, rows[i], sideW - 10), x0 + 5, ry + 2,
                    i == 0 ? c[4] : c[5], false);
        }

        // main column
        int mx0 = sx1 + 5, mx1 = x1 - 5;
        int mw = mx1 - mx0;
        int my = y0 + 4;
        String hello = L10n.tr("theme.editor.preview.assistant", "Here is the plan.");
        graphics.text(this.font, Theme.ellipsize(this.font, hello, mw), mx0, my, c[4], false);
        this.hits.add(new Hit(mx0, my - 1, mx0 + Math.min(mw, this.font.width(hello)), my + line, 4));
        my += line + 3;

        String you = L10n.tr("theme.editor.preview.user", "Looks good!");
        int bubbleW = Math.min(mw, this.font.width(you) + 10);
        if (my + line + 4 <= y1 - 20) {
            graphics.fill(mx1 - bubbleW, my, mx1, my + line + 4, c[9]);
            graphics.text(this.font, Theme.ellipsize(this.font, you, bubbleW - 8), mx1 - bubbleW + 5, my + 3, c[4], false);
            this.hits.add(new Hit(mx1 - bubbleW, my, mx1, my + line + 4, 9));
            my += line + 7;
        }

        String card = L10n.tr("theme.editor.preview.card", "Panel · muted");
        if (my + line + 6 <= y1 - 20) {
            graphics.fill(mx0, my, mx1, my + line + 6, c[2]);
            Theme.outline(graphics, mx0, my, mw, line + 6, c[6]);
            graphics.text(this.font, Theme.ellipsize(this.font, card, mw - 8), mx0 + 4, my + 4, c[5], false);
            this.hits.add(new Hit(mx0, my, mx1, my + line + 6, 2));
            this.hits.add(new Hit(mx0, my, mx1, my + 1, 6));
        }

        // composer: input + accent button with primary text
        int cy1 = y1 - 4, cy0 = cy1 - (line + 5);
        String send = L10n.tr("theme.editor.preview.send", "Send");
        int sendW = this.font.width(send) + 10;
        int inputX1 = mx1 - sendW - 3;
        graphics.fill(mx0, cy0, inputX1, cy1, c[3]);
        Theme.outline(graphics, mx0, cy0, inputX1 - mx0, cy1 - cy0, c[6]);
        graphics.text(this.font, Theme.ellipsize(this.font, L10n.tr("theme.editor.preview.input", "Ask anything"),
                inputX1 - mx0 - 8), mx0 + 4, cy0 + 3, c[5], false);
        this.hits.add(new Hit(mx0, cy0, inputX1, cy1, 3));
        graphics.fill(mx1 - sendW, cy0, mx1, cy1, c[7]);
        graphics.text(this.font, send, mx1 - sendW + 5, cy0 + 3, c[11], false);
        this.hits.add(new Hit(mx1 - sendW, cy0, mx1, cy1, 7));
        this.hits.add(new Hit(mx1 - sendW + 4, cy0 + 2, mx1 - 4, cy1 - 2, 11));

        Theme.outline(graphics, x0, y0, this.previewW, this.previewH, c[6]);

        // Hover: outline the part under the mouse and name its color.
        var hover = this.hitAt(mouseX, mouseY);
        if (hover != null) {
            Theme.outline(graphics, hover.x0(), hover.y0(), hover.x1() - hover.x0(), hover.y1() - hover.y0(), Theme.ACCENT);
            graphics.vanilla().setTooltipForNextFrame(this.font, this.font.split(Component.literal(
                    slotName(hover.slot()) + "  " + HexColor.format(c[hover.slot()])), 200), mouseX, mouseY);
        }
        var sel = this.selectedHit();
        if (sel != null && sel != hover)
            Theme.outline(graphics, sel.x0(), sel.y0(), sel.x1() - sel.x0(), sel.y1() - sel.y0(), Theme.TEXT_SOFT);
    }

    private @Nullable Hit selectedHit() {
        for (int i = this.hits.size() - 1; i >= 0; i--) if (this.hits.get(i).slot() == this.selected) return this.hits.get(i);
        return null;
    }

    // ---------------------------------------------------------------- widgets

    /** One palette color in the swatch row; selecting it edits that color. */
    private final class SwatchButton extends FlatButton {
        private final int slot;

        SwatchButton(Position position, int width, int height, int slot) {
            super(position, width, height, "", b -> {});
            this.slot = slot;
            this.refresh();
        }

        void refresh() {
            this.setTooltip(Component.literal(slotName(this.slot) + "  " + HexColor.format(ThemeEditorScreen.this.draft[this.slot])));
        }

        @Override
        public void onPress() {
            ThemeEditorScreen.this.select(this.slot);
        }

        @Override
        protected void extractBackground(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
            boolean chosen = ThemeEditorScreen.this.selected == this.slot;
            boolean hot = this.isMouseHovered() || this.isFocused();
            int x = this.getX(), y = this.getY(), w = this.getWidth(), h = this.getHeight();
            graphics.fill(x, y, x + w, y + h, chosen || hot ? Theme.TEXT : Theme.BORDER);
            graphics.fill(x + 1, y + 1, x + w - 1, y + h - 1, chosen ? Theme.PANEL : Theme.BORDER);
            graphics.fill(x + 2, y + 2, x + w - 2, y + h - 2, ThemeEditorScreen.this.draft[this.slot]);
            if (chosen) graphics.fill(x, y + h, x + w, y + h + 1, Theme.ACCENT);
            if (this.isFocused() && !chosen) Theme.outline(graphics, x, y, w, h, Theme.ACCENT);
        }

        @Override
        protected void extractText(SpruceGuiGraphics graphics, int color) {
            // Color only.
        }
    }
}
