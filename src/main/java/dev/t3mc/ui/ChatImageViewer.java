package dev.t3mc.ui;

import dev.t3mc.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.t3mc.ui.model.ChatImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A fitted image in a sculpted darkroom frame with a clear return path to the conversation. */
final class ChatImageViewer extends SpruceScreen {
    private static final int TOP_BAR = 28;

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
        String back = L10n.tr("chat.image.back", "Back");
        int w = Math.max(54, this.font.width(back) + 22);
        addRenderableWidget(new FlatButton(Position.of(width - w - 8, 5), w, 18, back, b -> onClose())
                .withIcon(FlatButton.Icon.BACK));
    }

    @Override public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        graphics.fill(0, 0, width, height, Theme.PANEL);
        graphics.fill(0, 0, width, TOP_BAR, Theme.SURFACE_RAISED);
        graphics.fill(0, TOP_BAR, width, TOP_BAR + 1, Theme.BORDER);
        graphics.text(font, Component.literal(Theme.ellipsize(font, image.name(), Math.max(1, width - 90)))
                .withStyle(s -> s.withBold(true)), 10, (TOP_BAR - font.lineHeight) / 2 + 1, Theme.TEXT, false);
        int stageX = 10, stageY = TOP_BAR + 10;
        int stageW = Math.max(20, width - 20), stageH = Math.max(20, height - TOP_BAR - 20);
        UiShapes.panel(graphics, stageX, stageY, stageW, stageH, UiShapes.R_CONTROL, Theme.CODE_BG, Theme.BORDER_SUBTLE, false);
        preview.draw(graphics, image, stageX + 4, stageY + 4, Math.max(1, stageW - 8), Math.max(1, stageH - 8));
        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return parent != null && parent.isPauseScreen(); }
    @Override public void removed() { preview.close(); super.removed(); }
}
