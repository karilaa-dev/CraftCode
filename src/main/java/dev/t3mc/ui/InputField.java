package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.SimpleColorBackground;
import dev.lambdaurora.spruceui.border.SimpleBorder;
import dev.lambdaurora.spruceui.widget.text.SpruceTextFieldWidget;
import net.minecraft.client.Minecraft;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.util.FormattedCharSequence;
import org.jetbrains.annotations.Nullable;

/** Single-line text field in the T3 palette with optional Enter action and secret masking. */
final class InputField extends SpruceTextFieldWidget {
    private @Nullable Runnable onEnter;
    private @Nullable Runnable afterPaste;
    private boolean masked;

    InputField(Position position, int width, String title, String placeholder) {
        super(position, width, 18, Component.literal(title), MessageFormatter.colored(
                Theme.ellipsize(Minecraft.getInstance().font, placeholder, width - 12), Theme.FAINT));
        this.setBackground(new SimpleColorBackground(Theme.INPUT));
        this.setBorder(new SimpleBorder(1, Theme.BORDER, Theme.ACCENT));
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
}
