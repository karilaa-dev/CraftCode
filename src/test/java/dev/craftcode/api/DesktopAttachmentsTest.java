package dev.craftcode.api;

import com.google.gson.JsonParser;
import dev.craftcode.ui.model.ChatImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class DesktopAttachmentsTest {
    @TempDir Path data;

    @Test void captionedMessageKeepsBothSameNamedAttachmentsWhenOnlyOneFileIsAvailable() throws Exception {
        Files.writeString(data.resolve("environment-id"), "mine\n");
        Files.createDirectory(data.resolve("attachments"));
        byte[] png = {(byte)137, 80, 78, 71, 13, 10, 26, 10};
        Files.write(data.resolve("attachments/first.png"), png);
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("statev2.sqlite")); var s = db.createStatement()) {
            s.execute("CREATE TABLE orchestration_v2_projection_messages(message_id TEXT, thread_id TEXT, role TEXT, payload_json TEXT)");
            s.execute("""
                INSERT INTO orchestration_v2_projection_messages VALUES('m','parent','user',
                    '{"text":"Two views","attachments":[{"type":"image","id":"first","name":"image.png","mimeType":"image/png","sizeBytes":8},
                    {"type":"image","id":"second","name":"image.png","mimeType":"image/png","sizeBytes":8}]}')
                """);
        }
        assertNull(DesktopAttachments.match(data, "other-server"));
        var store = DesktopAttachments.match(data, "mine");
        var items = JsonParser.parseString("""
                [{"type":"user_message","itemId":"right","messageId":"m","sourceThreadId":"parent","text":"Two views"},
                 {"type":"user_message","itemId":"wrong","messageId":"m","sourceThreadId":"elsewhere"}]
                """).getAsJsonArray();
        var found = store.forMessages("child", items);
        assertFalse(found.containsKey("wrong"));
        var images = found.get("right");
        assertEquals(2, images.size());
        assertInstanceOf(ChatImage.Desktop.class, images.getFirst().source());
        assertArrayEquals(png, images.getFirst().readBytes());
        assertInstanceOf(ChatImage.Unavailable.class, images.getLast().source());
        assertEquals(images, ChatImage.merge(List.of(images.getFirst()), images));
    }
}
