package dev.t3mc.ui;

import com.mojang.blaze3d.platform.InputConstants;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.background.SimpleColorBackground;
import dev.lambdaurora.spruceui.border.SimpleBorder;
import dev.lambdaurora.spruceui.widget.text.SpruceTextAreaWidget;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

/** Multi-line composer: Enter submits, Shift+Enter inserts a newline. */
final class ComposerArea extends SpruceTextAreaWidget {
    private final Runnable onSubmit;

    ComposerArea(Position position, int width, int height, String placeholder, Runnable onSubmit) {
        super(position, width, height, Component.translatable("t3mc.composer.narration"),
                MessageFormatter.colored(placeholder, Theme.FAINT));
        this.onSubmit = onSubmit;
        this.setBackground(new SimpleColorBackground(Theme.INPUT));
        this.setBorder(new SimpleBorder(0, Theme.INPUT, Theme.INPUT));
        this.setEditableColor(Theme.TEXT);
        this.setUneditableColor(Theme.TEXT_DISABLED);
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
