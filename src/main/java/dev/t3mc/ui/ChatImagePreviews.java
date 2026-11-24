package dev.t3mc.ui;

import dev.t3mc.L10n;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.t3mc.ui.model.ChatImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;
import java.util.*;
import java.util.concurrent.*;

/** Each screen owns its textures. Remote Markdown references remain dormant until the player opens them. */
final class ChatImagePreviews implements AutoCloseable {
    private final Map<ChatImage, Slot> entries = new HashMap<>();
    private final boolean viewer;
    private final int edge;
    ChatImagePreviews() { this(false); }
    ChatImagePreviews(boolean viewer) { this.viewer = viewer; this.edge = viewer ? 1600 : 128; }

    private static final class Slot {
        CompletableFuture<ChatImageDecoder.Pixels> decoding;
        Identifier texture;
        int width, height;
        String error;
    }

    String draw(SpruceGuiGraphics graphics, ChatImage image, int x, int y, int w, int h) {
        if (w <= 0 || h <= 0) return "";
        if (image.source() instanceof ChatImage.Unavailable)
            return placeholder(graphics, x, y, w, h, L10n.tr("chat.image.preview_unavailable", "Preview unavailable"),
                    L10n.tr("chat.image.open_in_t3_short", "Open this image in T3 Code."));
        if (image.needsClick() && !viewer)
            return placeholder(graphics, x, y, w, h, L10n.tr("chat.image.open", "Open image"), image.host());
        Slot slot = entries.computeIfAbsent(image, key -> {
            var s = new Slot();
            s.decoding = CompletableFuture.supplyAsync(() -> {
                try { return ChatImageDecoder.decode(key.readBytes(), edge); }
                catch (Exception error) { throw new CompletionException(error); }
            }, ChatImageDecoder.WORKER);
            return s;
        });
        if (slot.decoding != null && slot.decoding.isDone()) {
            try {
                var pixels = slot.decoding.join();
                slot.width = pixels.width();
                slot.height = pixels.height();
                slot.texture = pixels.upload();
            } catch (CompletionException failure) {
                slot.error = L10n.tr("chat.image.open_failed", "Could not open image");
                // Codec failures may contain a file path or a signed URL.
                if (failure.getCause() instanceof ChatImage.PreviewError error) slot.error = error.getMessage();
            } finally { slot.decoding = null; }
        }
        if (slot.texture == null) return placeholder(graphics, x, y, w, h,
                slot.error == null ? L10n.tr("chat.image.loading", "Loading image…") : slot.error, image.host());
        double scale = Math.min((double) w / slot.width, (double) h / slot.height);
        int dw = Math.max(1, (int) Math.round(slot.width * scale)), dh = Math.max(1, (int) Math.round(slot.height * scale));
        graphics.vanilla().blit(RenderPipelines.GUI_TEXTURED, slot.texture, x + (w - dw) / 2, y + (h - dh) / 2,
                0, 0, dw, dh, slot.width, slot.height, slot.width, slot.height);
        return "";
    }

    private String placeholder(SpruceGuiGraphics graphics, int x, int y, int w, int h, String text, String detail) {
        var font = Minecraft.getInstance().font;
        graphics.fill(x, y, x + w, y + h, Theme.PANEL);
        boolean twoLines = h >= 2 * font.lineHeight + 4 && !detail.isEmpty();
        int ty = y + (h - font.lineHeight * (twoLines ? 2 : 1)) / 2;
        String line = Theme.ellipsize(font, text, Math.max(0, w - 4));
        graphics.text(font, line, x + (w - font.width(line)) / 2, ty, Theme.TEXT, false);
        if (twoLines) {
            String secondary = Theme.ellipsize(font, detail, Math.max(0, w - 4));
            graphics.text(font, secondary, x + (w - font.width(secondary)) / 2, ty + font.lineHeight + 2, Theme.MUTED, false);
        }
        return text;
    }

    void retain(Collection<ChatImage> live) {
        Set<ChatImage> keep = new HashSet<>(live);
        entries.entrySet().removeIf(entry -> {
            if (keep.contains(entry.getKey())) return false;
            Slot slot = entry.getValue();
            if (slot.decoding != null) slot.decoding.cancel(false);
            if (slot.texture != null) Minecraft.getInstance().getTextureManager().release(slot.texture);
            return true;
        });
    }
    @Override public void close() { retain(List.of()); }
}
