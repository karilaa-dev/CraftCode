package dev.craftcode.ui;

import static dev.craftcode.L10n.tr;

import dev.craftcode.ui.model.LocalImage;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/** Asynchronous thumbnails for local images staged in the composer. */
final class ImagePreviews {
    private static final int MAX_EDGE = 64;
    record Thumb(Identifier texture, int width, int height) {}

    private final Map<LocalImage, CompletableFuture<ChatImageDecoder.Pixels>> decoded = new IdentityHashMap<>();
    private final Map<LocalImage, Thumb> textures = new IdentityHashMap<>();
    /** Render thread only. Returns null until the thumbnail is ready (or if there is none). */
    @Nullable Thumb thumb(LocalImage image) {
        var ready = this.textures.get(image);
        if (ready != null) return ready;
        var future = this.decoded.computeIfAbsent(image,
                k -> CompletableFuture.supplyAsync(() -> decode(k), ChatImageDecoder.WORKER));
        if (!future.isDone() || future.isCompletedExceptionally()) return null;
        ChatImageDecoder.Pixels pixels = future.getNow(null);
        if (pixels == null) return null;
        var thumb = new Thumb(pixels.upload(), pixels.width(), pixels.height());
        this.textures.put(image, thumb);
        return thumb;
    }

    /** Forgets previews of images that are no longer staged anywhere. */
    void retain(Collection<LocalImage> live) {
        Set<LocalImage> keep = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        keep.addAll(live);
        var pending = this.decoded.entrySet().iterator();
        while (pending.hasNext()) {
            var entry = pending.next();
            if (keep.contains(entry.getKey())) continue;
            entry.getValue().cancel(false); // a queued decode for a removed image never runs
            pending.remove();
        }
        var it = this.textures.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (keep.contains(entry.getKey())) continue;
            Minecraft.getInstance().getTextureManager().release(entry.getValue().texture());
            it.remove();
        }
    }

    /** Frees GPU textures (they are recreated from the cached thumbnail on demand). Render thread only. */
    void releaseTextures() {
        for (var thumb : this.textures.values()) Minecraft.getInstance().getTextureManager().release(thumb.texture());
        this.textures.clear();
    }

    private static @Nullable ChatImageDecoder.Pixels decode(LocalImage image) {
        try { return ChatImageDecoder.decode(image.readBytes(), MAX_EDGE); }
        catch (Exception e) { return null; }
    }

    static String formatSize(long bytes) {
        if (bytes < 1024) return tr("file.bytes", "%s B", bytes);
        if (bytes < 1024 * 1024) return tr("file.kib", "%s KB", Math.max(1, Math.round(bytes / 1024.0)));
        return tr("file.mib", "%s MB", String.format(java.util.Locale.ROOT, "%.1f", bytes / (1024.0 * 1024.0)));
    }

    static String typeLabel(LocalImage image) {
        return switch (image.mimeType()) {
            case "image/png" -> "PNG";
            case "image/jpeg" -> "JPEG";
            case "image/gif" -> "GIF";
            case "image/webp" -> "WebP";
            default -> tr("file.image", "Image");
        };
    }
}
