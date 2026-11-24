package dev.craftcode.ui;

import dev.craftcode.L10n;
import dev.craftcode.ui.model.ChatImage;

import com.twelvemonkeys.imageio.plugins.webp.WebPImageReaderSpi;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Dimensions are checked before allocating pixels. Animated GIF and WebP use their first frame. */
final class ChatImageDecoder {
    static final ExecutorService WORKER = Executors.newSingleThreadExecutor(Thread.ofPlatform()
            .daemon(true).name("CraftCode image decoder").factory());
    private static int nextTexture;

    record Pixels(int width, int height, int originalWidth, int originalHeight, int[] argb) {
        /** Must run on Minecraft's render thread. */
        Identifier upload() {
            NativeImage image = new NativeImage(width, height, false);
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int pixel = argb[y * width + x];
                    int abgr = pixel & 0xff00ff00 | (pixel >>> 16 & 255) | (pixel & 255) << 16;
                    image.setPixelABGR(x, y, abgr);
                }
            }
            var texture = Identifier.fromNamespaceAndPath("craftcode", "image/" + nextTexture++);
            Minecraft.getInstance().getTextureManager().register(texture, new DynamicTexture(() -> "CraftCode image", image));
            return texture;
        }
    }
    static final int MAX_SIDE = 8192;
    static final long MAX_PIXELS = 16L * 1024 * 1024;

    static Pixels decode(byte[] data, int edge) throws IOException {
        try (var input = new MemoryCacheImageInputStream(new ByteArrayInputStream(data))) {
            ImageReader reader;
            // A game or worker's context class loader can miss nested ImageIO providers. Select our bundled
            // WebP SPI directly rather than depending on JVM-global ImageIO service registration.
            if (data.length >= 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                    && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
                reader = new WebPImageReaderSpi().createReaderInstance();
            } else {
                var readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw new ChatImage.PreviewError(L10n.tr("chat.image.unsupported_format", "Unsupported image format."));
                reader = readers.next();
            }
            try {
                reader.setInput(input, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w < 1 || h < 1 || w > MAX_SIDE || h > MAX_SIDE || (long) w * h > MAX_PIXELS)
                    throw new ChatImage.PreviewError(L10n.tr("chat.image.too_large_preview", "Image is too large to preview."));
                int step = Math.max(1, Math.ceilDiv(Math.max(w, h), edge));
                var params = reader.getDefaultReadParam();
                params.setSourceSubsampling(step, step, 0, 0);
                var frame = reader.read(0, params);
                int width = frame.getWidth(), height = frame.getHeight();
                // Some plugins may ignore subsampling. Bound the uploaded texture independently.
                var output = frame;
                if (width > edge || height > edge) {
                    float scale = edge / (float) Math.max(width, height);
                    output = new java.awt.image.BufferedImage(Math.max(1, Math.round(width * scale)),
                            Math.max(1, Math.round(height * scale)), java.awt.image.BufferedImage.TYPE_INT_ARGB);
                    var graphics = output.createGraphics();
                    try { graphics.drawImage(frame, 0, 0, output.getWidth(), output.getHeight(), null); }
                    finally { graphics.dispose(); }
                }
                var pixels = output.getRGB(0, 0, output.getWidth(), output.getHeight(), null, 0, output.getWidth());
                return new Pixels(output.getWidth(), output.getHeight(), w, h, pixels);
            } finally { reader.dispose(); }
        }
    }
}
