package dev.craftcode.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftcode.ui.model.ChatImage;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class T3MessageImagesTest {
    private static final String PNG = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfH0AAAAASUVORK5CYII=";
    private static final URI ORIGIN = URI.create("http://localhost:1234/mcp");

    @Test void separatesInlineImagesAndPreservesMarkdownExamples() throws Exception {
        var example = "`![example](https://other.host/ignored.png)`\n```md\n![also ignored](https://other.host/x)\n```";
        var incoming = T3MessageImages.extract("Hello world\n![Green](data:image/png;base64," + PNG + ")\n" + example,
                new JsonObject(), ORIGIN);
        assertEquals("Hello world\n\n" + example, incoming.text());
        assertEquals(1, incoming.images().size());
        assertArrayEquals(Base64.getDecoder().decode(PNG), incoming.images().getFirst().readBytes());
        assertFalse(incoming.images().getFirst().needsClick());
    }

    @Test void onlySignedAssetPathsResolveAgainstTheDirectEnvironment() {
        var text = "![server](/api/assets/signed/my%20tile.png) ![remote](https://example.org/tile.png) ![local](file:///tmp/tile.png)";
        var direct = T3MessageImages.extract(text, new JsonObject(), ORIGIN).images();
        var peer = T3MessageImages.extract(text, new JsonObject(), null).images();
        assertEquals("localhost", direct.getFirst().host());
        assertTrue(direct.get(1).needsClick());
        assertInstanceOf(ChatImage.Unavailable.class, direct.get(2).source());
        assertInstanceOf(ChatImage.Unavailable.class, peer.getFirst().source());
        assertEquals("example.org", peer.get(1).host());
    }

    @Test void keepsAnExplicitFallbackForSourceLessServerAttachments() {
        var item = JsonParser.parseString("""
                {"attachments":[{"type":"image","name":"Screenshot.png","mimeType":"image/png","sizeBytes":350}]}
                """).getAsJsonObject();
        var image = T3MessageImages.extract("", item, ORIGIN).images().getFirst();
        assertEquals("Screenshot.png", image.name());
        assertInstanceOf(ChatImage.Unavailable.class, image.source());
    }

    @Test void opensAnInlineImageAcrossMcpTextPagesOnlyOnRequest() throws Exception {
        String beginning = "Here is a tile.\n![Tile](data:image/png;base64," + PNG.substring(0, 40);
        var item = new JsonObject();
        item.addProperty("textTruncated", true);
        item.addProperty("itemId", "item");
        item.addProperty("nextTextOffset", beginning.length());
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var content = T3MessageImages.extract(beginning, item, ORIGIN, (thread, id, offset) -> {
            assertEquals("thread", thread);
            assertEquals("item", id);
            calls.incrementAndGet();
            if (offset == beginning.length()) return new ChatImage.TextPage(PNG.substring(40, 60), offset + 20);
            return new ChatImage.TextPage(PNG.substring(60) + ") More text after the image.", -1);
        }, "thread");
        assertEquals("Here is a tile.", content.text());
        var image = content.images().getFirst();
        assertTrue(image.needsClick());
        assertEquals(0, calls.get());
        assertArrayEquals(Base64.getDecoder().decode(PNG), image.readBytes());
        assertEquals(2, calls.get());
    }
}
