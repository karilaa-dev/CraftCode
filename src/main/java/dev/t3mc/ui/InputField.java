package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.Background;
import dev.lambdaurora.spruceui.border.Border;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.widget.SpruceWidget;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/**
 * Single-line text field with pixel-rounded frame, recessed inner shadow, hover/focus borders,
 * optional search adornment (magnifying glass + clear button), Enter action and secret masking.
 */
final class InputField extends SpruceTextFieldWidget {
    private static final int CLEAR_ZONE = 15;

    private @Nullable Runnable onEnter;
    private @Nullable Runnable afterPaste;
    private boolean masked;
    private boolean searchIcon;

    InputField(Position position, int width, String title, String placeholder) {
        this(position, width, 19, title, placeholder);
    }

    InputField(Position position, int width, int height, String title, String placeholder) {
        super(position, width, height, Component.literal(title), MessageFormatter.colored(
                Theme.ellipsize(Minecraft.getInstance().font, placeholder, Math.max(12, width - 18)), Theme.FAINT));
        this.setBackground(new RoundedInputBackground());
        this.setBorder(new RoundedInputBorder());
        this.setEditableColor(Theme.TEXT);
        this.setUneditableColor(Theme.TEXT_DISABLED);
    }

    InputField onEnter(Runnable onEnter) {
        this.onEnter = onEnter;
        return this;
    }

    void afterPaste(Runnable action) {
        this.afterPaste = action;
    }

    /** Adds a subtle search icon on the right (and a clickable × clear control while text is present). */
    InputField searchable() {
        this.searchIcon = true;
        return this;
    }

    /** Draws the value as bullets so secrets are not shown on screen. */
    InputField masked() {
        this.masked = true;
        this.setRenderTextProvider((text, offset) -> FormattedCharSequence.forward("•".repeat(text.length()), Style.EMPTY));
        return this;
    }

    @Override
    public boolean capturesInput() {
        return this.isEditorActive();
    }

    @Override
    public void setFocused(boolean focused) {
        boolean wasEditing = this.isEditorActive();
        super.setFocused(focused);
        if (wasEditing != this.isEditorActive()) this.client.onTextInputFocusChange(this, this.isEditorActive());
    }

    @Override
    public void setActive(boolean active) {
        boolean wasEditing = this.isEditorActive();
        super.setActive(active);
        if (wasEditing != this.isEditorActive()) this.client.onTextInputFocusChange(this, this.isEditorActive());
    }

    private boolean onClearButton(double mouseX, double mouseY) {
        return this.searchIcon && this.isActive() && !this.getText().isEmpty()
                && mouseX >= this.getEndX() - CLEAR_ZONE && mouseX < this.getEndX()
                && mouseY >= this.getY() && mouseY < this.getEndY();
    }

    @Override
    protected boolean onMouseClick(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.onClearButton(event.x(), event.y())) {
            this.setText("");
            this.setFocused(true);
            return true;
        }
        return super.onMouseClick(event, doubleClick);
    }

    @Override
    protected void extractWidgetRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        super.extractWidgetRenderState(graphics, mouseX, mouseY, delta);
        if (!this.searchIcon) return;
        int x = this.getEndX() - 12;
        int y = this.getY() + (this.getHeight() - 7) / 2;
        if (this.getText().isEmpty()) {
            UiIcons.search(graphics, x, y, this.isFocused() ? Theme.MUTED : Theme.FAINT);
        } else {
            boolean hot = this.onClearButton(mouseX, mouseY);
            if (hot) {
                UiShapes.fillRounded(graphics, this.getEndX() - CLEAR_ZONE + 1, this.getY() + 3,
                        CLEAR_ZONE - 4, this.getHeight() - 6, UiShapes.R_CHIP, Theme.BUTTON_HOVER);
                graphics.requestCursor(CursorTypes.POINTING_HAND);
            }
            UiIcons.close(graphics, x + 1, y + 1, hot ? Theme.TEXT : Theme.MUTED);
        }
    }

    @Override
    protected boolean onKeyPress(KeyEvent event) {
        if (this.masked && (event.isCopy() || event.isCut())) return true; // never copy secrets out
        boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
        if (enter && this.onEnter != null && this.isEditorActive()) {
            this.onEnter.run();
            return true;
        }
        boolean handled = super.onKeyPress(event);
        if (handled && event.isPaste() && this.afterPaste != null) this.afterPaste.run();
        return handled;
    }

    private static final class RoundedInputBackground implements Background {
        @Override
        public void extractRenderState(SpruceGuiGraphics graphics, SpruceWidget widget, int vOffset, int mouseX, int mouseY, float delta) {
            int x = widget.getX();
            int y = widget.getY();
            int w = widget.getWidth();
            int h = widget.getHeight();
            int fill = widget.isActive() ? Theme.INPUT : Theme.BUTTON_DISABLED;
            UiShapes.fillRounded(graphics, x, y, w, h, UiShapes.R_CONTROL, fill);
            if (w > 6 && h > 4) {
                graphics.fill(x + 2, y + 1, x + w - 2, y + 2, Theme.SHADOW_EDGE);
            }
        }
    }

    private static final class RoundedInputBorder implements Border {
        @Override
        public void extractRenderState(SpruceGuiGraphics graphics, SpruceWidget widget, int mouseX, int mouseY, float delta) {
            int color = !widget.isActive() ? Theme.BORDER_SUBTLE
                    : widget.isFocused() ? Theme.ACCENT
                    : widget.isMouseHovered() ? Theme.BORDER_STRONG
                    : Theme.BORDER;
            UiShapes.outlineRounded(graphics, widget.getX(), widget.getY(), widget.getWidth(), widget.getHeight(),
                    UiShapes.R_CONTROL, color);
        }

        @Override
        public int getThickness() {
            return 1;
        }
    }
}
