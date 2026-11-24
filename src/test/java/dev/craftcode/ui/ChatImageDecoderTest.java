package dev.craftcode.ui;

import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class ChatImageDecoderTest {
    @Test void readsWebpWithoutAContextImageIoPluginAndAllThreeJvmFormats() throws Exception {
        byte[] webp = Base64.getDecoder().decode("UklGRiAAAABXRUJQVlA4TBQAAAAvA8AAAA8wZ1M0ifMfeBiBiP5HAA==");
        var pixels = ChatImageDecoder.decode(webp, 64);
        assertEquals(4, pixels.width());
        assertEquals(0xff456789, pixels.argb()[1]);
        var source = new BufferedImage(8, 4, BufferedImage.TYPE_INT_RGB);
        for (String format : new String[]{"jpeg", "png", "gif"}) {
            var bytes = new ByteArrayOutputStream();
            assertTrue(ImageIO.write(source, format, bytes));
            assertTrue(ChatImageDecoder.decode(bytes.toByteArray(), 3).width() <= 3);
        }
    }
}
