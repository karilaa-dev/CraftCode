package dev.craftcode.mcp;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AttachmentUploadTest {
    @Test void postsExactBytesWithoutMcpCredentialsAndDoesNotFollowRedirects() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        byte[] raw = {1, 2, 3};
        var body = new AtomicReference<byte[]>();
        var mime = new AtomicReference<String>();
        var headers = new AtomicReference<com.sun.net.httpserver.Headers>();
        var method = new AtomicReference<String>();
        var followed = new AtomicBoolean();
        server.createContext("/upload", exchange -> {
            try (exchange) {
                body.set(exchange.getRequestBody().readAllBytes());
                mime.set(exchange.getRequestHeaders().getFirst("Content-Type"));
                headers.set(exchange.getRequestHeaders());
                method.set(exchange.getRequestMethod());
                exchange.sendResponseHeaders(204, -1);
            }
        });
        server.createContext("/move", exchange -> {
            try (exchange) {
                exchange.getResponseHeaders().set("Location", base + "/forbidden");
                exchange.sendResponseHeaders(307, -1);
            }
        });
        server.createContext("/forbidden", exchange -> { try (exchange) { followed.set(true); exchange.sendResponseHeaders(204, -1); } });
        server.start();
        try (McpClient client = McpClient.http(base + "/mcp", "secret-token")) {
            client.upload("", "/upload", "image/png", raw);
            assertArrayEquals(raw, body.get());
            assertEquals("image/png", mime.get());
            assertEquals("POST", method.get());
            assertNull(headers.get().getFirst("Authorization"));
            assertNull(headers.get().getFirst("Cookie"));
            assertNull(headers.get().getFirst("Mcp-Session-Id"));
            assertThrows(IOException.class, () -> client.upload(base.replace("127.0.0.1","localhost") + "/forbidden", "", "image/png", raw));
            var redirect = assertThrows(IOException.class, () -> client.upload(base + "/move?signed-token", "", "image/png", raw));
            assertTrue(redirect.getMessage().contains("307"));
            assertFalse(redirect.getMessage().contains("signed-token"));
            assertFalse(followed.get());
        } finally { server.stop(0); }
    }

    @Test void validatesAbsoluteAndRelativeTargetsWithoutExposingTheSignedUrl() throws Exception {
        URI mcp = URI.create("https://t3.example/mcp");
        assertEquals(URI.create("https://t3.example:443/a"), AttachmentUpload.destination(mcp, "https://t3.example:443/a", null));
        assertEquals(URI.create("https://t3.example/a"), AttachmentUpload.destination(mcp, "http://localhost:3773/a", "/a"));
        for (String absolute : new String[] {"http://t3.example/a", "https://t3.example:4000/a", "https://user@t3.example/a", "file:///tmp/a", "invalid signed-token"}) {
            var failure = assertThrows(IOException.class, () -> AttachmentUpload.destination(mcp, absolute, null));
            assertFalse(failure.toString().contains("signed-token"));
        }
        assertThrows(IOException.class, () -> AttachmentUpload.destination(mcp, null, "//foreign.example/upload"));
    }
}
