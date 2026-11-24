package dev.t3mc.ui.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class LocalImageTest {
    @TempDir Path dir;

    @Test void detectsAllFourFormatsFromContentAndKeepsOriginalFilename() throws Exception {
        for (var sample : Map.of(
                "image/png", "89504e470d0a1a0a",
                "image/jpeg", "ffd8ffe0",
                "image/webp", "524946460000000057454250",
                "image/gif", "474946383961").entrySet()) {
            byte[] bytes = HexFormat.of().parseHex(sample.getValue());
            Path path = Files.write(dir.resolve("attachment.bin"), bytes);
            LocalImage image = LocalImage.open(path);
            assertEquals(path.toRealPath(), image.path());
            assertEquals("attachment.bin", image.name());
            assertEquals(sample.getKey(), image.mimeType());
            assertEquals(bytes.length, image.sizeBytes());
            assertArrayEquals(bytes, image.readBytes());
        }
    }

    @Test void rejectsEmptyNonimageOversizeAndReplacedFiles() throws Exception {
        Path path = Files.createFile(dir.resolve("x.png"));
        assertThrows(IOException.class, () -> LocalImage.open(dir));
        assertThrows(IOException.class, () -> LocalImage.open(path));
        Files.writeString(path, "not a PNG");
        assertThrows(IOException.class, () -> LocalImage.open(path));
        Files.writeString(path, "GIF87a");
        var chosen = LocalImage.open(path);
        Files.writeString(path, "123456");
        assertThrows(IOException.class, chosen::readBytes);
        Files.writeString(path, "GIF87a-x");
        assertThrows(IOException.class, chosen::readBytes);
        try (var file = new RandomAccessFile(path.toFile(), "rw")) {
            file.setLength(LocalImage.MAX_BYTES + 1L);
        }
        assertThrows(IOException.class, chosen::readBytes);
        assertThrows(IOException.class, () -> LocalImage.open(path));
    }
}
