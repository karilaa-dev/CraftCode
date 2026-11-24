package dev.craftcode;

import dev.craftcode.ui.model.LocalImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import java.util.Base64;
import static org.junit.jupiter.api.Assertions.*;

class ChatImageStoreTest {
    @TempDir Path directory;

    @Test void receiptSurvivesRestartAndOriginalDeletionWithoutCrossingRoutesOrThreads() throws Exception {
        byte[] bytes = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jfH0AAAAASUVORK5CYII=");
        var file = Files.write(directory.resolve("Player screenshot.png"), bytes);
        var cache = directory.resolve("cache");
        new ChatImageStore(cache).put("https://a.example/mcp", "peer", "../out", "m", List.of(LocalImage.open(file)), List.of(bytes));
        Files.delete(file);
        var restarted = new ChatImageStore(cache);
        var image = restarted.get("https://a.example/mcp", "peer", "../out", "m").getFirst();
        assertEquals("Player screenshot.png", image.name());
        assertArrayEquals(bytes, image.readBytes());
        assertTrue(restarted.get("https://b.example/mcp", "peer", "../out", "m").isEmpty());
        assertTrue(restarted.get("https://a.example/mcp", "", "../out", "m").isEmpty());
        assertTrue(restarted.get("https://a.example/mcp", "peer", "elsewhere", "m").isEmpty());
    }
}
