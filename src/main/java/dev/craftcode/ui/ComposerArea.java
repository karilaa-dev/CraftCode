package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.EmptyBackground;
import dev.lambdaurora.spruceui.border.EmptyBorder;
import dev.lambdaurora.spruceui.widget.text.SpruceTextAreaWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Multi-line composer: Enter submits, Shift+Enter inserts a newline. */
final class ComposerArea extends SpruceTextAreaWidget {
    private final Runnable onSubmit;

    ComposerArea(Position position, int width, int height, String placeholder, Runnable onSubmit) {
        super(position, width, height, Component.translatable("craftcode.composer.narration"),
                MessageFormatter.colored(placeholder, Theme.composerSecondaryText()));
        this.onSubmit = onSubmit;
        this.setBackground(EmptyBackground.EMPTY_BACKGROUND);
        this.setBorder(EmptyBorder.EMPTY_BORDER);
        this.setEditableColor(Theme.TEXT);
        this.setUneditableColor(Theme.TEXT_DISABLED);
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
        boolean enter = event.key() == InputConstants.KEY_RETURN || event.key() == InputConstants.KEY_NUMPADENTER;
        if (enter && !event.hasShiftDown() && this.isEditorActive()) {
            this.onSubmit.run();
            return true;
        }
        return super.onKeyPress(event);
    }
}
