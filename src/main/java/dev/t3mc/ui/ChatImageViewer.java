package dev.t3mc.ui;

import dev.t3mc.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.t3mc.ui.model.ChatImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A fitted image with a return path to the exact conversation the player was reading. */
final class ChatImageViewer extends SpruceScreen {
    private final ChatImage image;
    private final Screen parent;
    private final ChatImagePreviews preview = new ChatImagePreviews(true);

    private ChatImageViewer(Screen parent, ChatImage image) {
        super(Component.literal(image.name()));
        this.parent = parent;
        this.image = image;
    }

    static void open(Screen parent, ChatImage image) {
        Minecraft.getInstance().gui.setScreen(new ChatImageViewer(parent, image));
    }

    @Override protected void init() {
        addRenderableWidget(new FlatButton(Position.of(width - 54, 5), 48, 16, L10n.tr("chat.image.back", "Back"), b -> onClose()));
    }

    @Override public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, Theme.PANEL);
        graphics.fill(0, 24, width, 25, Theme.BORDER);
        graphics.text(font, Theme.ellipsize(font, image.name(), Math.max(1, width - 74)), 9, 10, Theme.TEXT, false);
        preview.draw(graphics, image, 6, 29, width - 12, height - 35);
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return parent != null && parent.isPauseScreen(); }
    @Override public void removed() { preview.close(); super.removed(); }
}
