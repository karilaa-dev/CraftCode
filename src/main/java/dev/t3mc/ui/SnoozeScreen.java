package dev.t3mc.ui;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.t3mc.L10n;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

final class SnoozeScreen extends SpruceScreen {
    private final WorkspaceScreen parent;
    private final Consumer<Instant> submit;

    SnoozeScreen(WorkspaceScreen parent, Consumer<Instant> submit) {
        super(Component.literal(L10n.tr("snooze.title", "Snooze thread")));
        this.parent = parent;
        this.submit = submit;
    }

    @Override
    protected void init() {
        int top = height / 2 - 36;
        choice(top, L10n.tr("snooze.hour", "1 hour"), Duration.ofHours(1));
        choice(top + 22, L10n.tr("snooze.day", "1 day"), Duration.ofDays(1));
        choice(top + 44, L10n.tr("snooze.week", "1 week"), Duration.ofDays(7));
        addRenderableWidget(new FlatButton(Position.of(width / 2 - 70, top + 70), 140, 18,
                L10n.tr("settings.cancel", "Cancel"), b -> onClose()));
    }

    private void choice(int y, String label, Duration duration) {
        addRenderableWidget(new FlatButton(Position.of(width / 2 - 70, y), 140, 18, label, b -> {
            submit.accept(Instant.now().plus(duration));
            onClose();
        }));
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        int top = height / 2 - 58;
        graphics.fill(width / 2 - 78, top, width / 2 + 78, top + 118, Theme.SIDEBAR);
        graphics.text(font, getTitle(), width / 2 - 70, top + 7, Theme.TEXT, false);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return parent.isPauseScreen(); }
}
