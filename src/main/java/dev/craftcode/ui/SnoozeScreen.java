package dev.craftcode.ui;

import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.craftcode.L10n;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Consumer;

/** Sculpted modal dialog over the live workspace for picking a thread snooze duration. */
final class SnoozeScreen extends SpruceScreen {
    private static final int CARD_W = 180;
    private static final int CARD_H = 136;
    private static final int PAD = 12;

    private final WorkspaceScreen parent;
    private final Consumer<Instant> submit;
    private int cardX, cardY;

    SnoozeScreen(WorkspaceScreen parent, Consumer<Instant> submit) {
        super(Component.literal(L10n.tr("snooze.title", "Snooze thread")));
        this.parent = parent;
        this.submit = submit;
    }

    @Override
    protected void init() {
        this.parent.resizeBehind(this.width, this.height);
        this.cardX = (this.width - CARD_W) / 2;
        this.cardY = (this.height - CARD_H) / 2;
        int bx = this.cardX + PAD;
        int bw = CARD_W - PAD * 2;
        int y = this.cardY + 32;
        choice(bx, y, bw, L10n.tr("snooze.hour", "1 hour"), Duration.ofHours(1));
        choice(bx, y + 24, bw, L10n.tr("snooze.day", "1 day"), Duration.ofDays(1));
        choice(bx, y + 48, bw, L10n.tr("snooze.week", "1 week"), Duration.ofDays(7));
        addRenderableWidget(new FlatButton(Position.of(bx, y + 76), bw, 19,
                L10n.tr("settings.cancel", "Cancel"), FlatButton.Kind.SUBTLE, b -> onClose()));
    }

    private void choice(int x, int y, int w, String label, Duration duration) {
        addRenderableWidget(new FlatButton(Position.of(x, y), w, 20, label, b -> {
            submit.accept(Instant.now().plus(duration));
            onClose();
        }));
    }

    @Override
    public void tick() {
        this.parent.tick();
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.x() < this.cardX || event.x() >= this.cardX + CARD_W
                || event.y() < this.cardY || event.y() >= this.cardY + CARD_H) {
            this.onClose();
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        this.parent.extractBackground(graphics, -1, -1, delta);
        graphics.nextStratum();
        this.parent.extractRenderState(graphics, -1, -1, delta);
        graphics.nextStratum();
        graphics.fill(0, 0, this.width, this.height, Theme.isLight() ? 0x40000000 : 0x70000000);
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        UiShapes.dropShadow(graphics, this.cardX, this.cardY, CARD_W, CARD_H, UiShapes.R_PANEL);
        UiShapes.panel(graphics, this.cardX, this.cardY, CARD_W, CARD_H, UiShapes.R_PANEL,
                Theme.PANEL_SURFACE, Theme.BORDER_STRONG, true);
        UiIcons.clock(graphics, this.cardX + PAD, this.cardY + 12, Theme.ACCENT);
        graphics.text(this.font, this.getTitle().copy().withStyle(s -> s.withBold(true)),
                this.cardX + PAD + 11, this.cardY + 12, Theme.TEXT, false);
        graphics.fill(this.cardX + PAD, this.cardY + 26, this.cardX + CARD_W - PAD, this.cardY + 27, Theme.BORDER_SUBTLE);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return parent.isPauseScreen(); }
}
