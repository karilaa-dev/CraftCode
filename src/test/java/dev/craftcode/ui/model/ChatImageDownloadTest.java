package dev.craftcode.ui.model;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.net.*;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatImageDownloadTest {
    @Test void loadsOnClickAndFollowsOnlyBoundedAnonymousWebRedirects() throws Exception {
        var hits = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        byte[] png = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfH0AAAAASUVORK5CYII=");
        server.createContext("/image", ex -> {
            try (ex) {
                hits.incrementAndGet();
                assertNull(ex.getRequestHeaders().getFirst("Authorization"));
                assertNull(ex.getRequestHeaders().getFirst("Cookie"));
                ex.sendResponseHeaders(200, png.length);
                ex.getResponseBody().write(png);
            }
        });
        server.createContext("/redirect", ex -> {
            try (ex) {
                ex.getResponseHeaders().add("Location", "/image");
                ex.sendResponseHeaders(302, -1);
            }
        });
        var redirects = new AtomicInteger();
        server.createContext("/loop", ex -> {
            try (ex) {
                redirects.incrementAndGet();
                ex.getResponseHeaders().add("Location", "/loop");
                ex.sendResponseHeaders(307, -1);
            }
        });
        server.start();
        try {
            URI origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            var image = new ChatImage("tile", "", -1, new ChatImage.Web(origin.resolve("/image")));
            assertTrue(image.needsClick());
            assertEquals(0, hits.get());
            assertArrayEquals(png, image.readBytes());
            assertArrayEquals(png, new ChatImage("redirect", "", -1,
                    new ChatImage.Web(origin.resolve("/redirect"))).readBytes());
            assertEquals(2, hits.get());
            assertThrows(IOException.class, () -> new ChatImage("loop", "", -1,
                    new ChatImage.Web(origin.resolve("/loop"))).readBytes());
            assertEquals(6, redirects.get());
            URI secure = URI.create("https://images.example/initial");
            assertEquals("cdn.example", ChatImageDownload.redirect(secure, "//cdn.example/file.png").getHost());
            assertThrows(IOException.class, () -> ChatImageDownload.redirect(secure, "http://cdn.example/file.png"));
            assertThrows(IOException.class, () -> ChatImageDownload.redirect(secure, "https://user@cdn.example/file.png"));
            assertThrows(IOException.class, () -> ChatImageDownload.redirect(secure, "file:///some/path"));
        } finally { server.stop(0); }
    }
}
