package dev.craftcode.api;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.craftcode.ui.model.ChatImage;
import dev.craftcode.ui.model.LocalImage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Reads only the attachment column for user messages already returned by the matching T3 environment. */
final class DesktopAttachments {
    private final Path data;

    private DesktopAttachments(Path data) { this.data = data; }

    static Path dataDirectory() {
        String home = System.getenv("T3CODE_HOME");
        return (home == null || home.isBlank() ? Path.of(System.getProperty("user.home"), ".t3") : Path.of(home))
                .resolve("userdata");
    }

    static boolean available(Path data) {
        return Files.isRegularFile(data.resolve("environment-id")) && Files.isRegularFile(data.resolve("statev2.sqlite"));
    }

    static DesktopAttachments match(Path data, String environmentId) throws IOException {
        return !environmentId.isBlank() && available(data)
                && environmentId.equals(Files.readString(data.resolve("environment-id")).strip()) ? new DesktopAttachments(data) : null;
    }

    Map<String,List<ChatImage>> forMessages(String thread, JsonArray items) {
        var wanted = new JsonArray();
        for (var entry : items) {
            if (!(entry instanceof JsonObject row) || !"user_message".equals(T3Api.string(row, "type"))) continue;
            String id = T3Api.string(row, "messageId");
            if (id.isEmpty()) continue;
            var key = new JsonArray();
            String source = T3Api.string(row, "sourceThreadId");
            key.add(source.isEmpty() ? thread : source);
            key.add(id);
            key.add(T3Api.string(row, "itemId"));
            wanted.add(key);
        }
        if (wanted.isEmpty()) return Map.of();
        var result = new HashMap<String,List<ChatImage>>();
        String url = "jdbc:sqlite:" + data.resolve("statev2.sqlite").toUri().toASCIIString() + "?mode=ro&busy_timeout=100";
        String sql = """
                SELECT json_extract(k.value, '$[2]'), json_extract(m.payload_json, '$.attachments')
                FROM json_each(?) k JOIN orchestration_v2_projection_messages m
                  ON m.message_id = json_extract(k.value, '$[1]') AND m.thread_id = json_extract(k.value, '$[0]')
                WHERE m.role = 'user' AND json_array_length(m.payload_json, '$.attachments') > 0
                """;
        try {
            Class.forName("org.sqlite.JDBC");
            try (var db = DriverManager.getConnection(url); var statement = db.prepareStatement(sql)) {
                statement.setString(1, wanted.toString());
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        var images = new ArrayList<ChatImage>();
                        for (var descriptor : JsonParser.parseString(rows.getString(2)).getAsJsonArray()) {
                            if (descriptor instanceof JsonObject file) images.add(attachment(file));
                        }
                        result.put(rows.getString(1), List.copyOf(images));
                    }
                }
            }
        } catch (SQLException | ClassNotFoundException | RuntimeException | LinkageError ignored) {
            // An absent native library or an older desktop schema must not prevent reading the thread.
        }
        return result;
    }

    private ChatImage attachment(JsonObject file) {
        String id = T3Api.string(file, "id");
        String mime = T3Api.string(file, "mimeType");
        long size = file.has("sizeBytes") ? file.get("sizeBytes").getAsLong() : -1;
        String ext = switch (mime) {
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            case "image/gif" -> ".gif";
            case "image/webp" -> ".webp";
            default -> "";
        };
        ChatImage.Source source = new ChatImage.Unavailable();
        if (!ext.isEmpty() && size > 0 && size <= LocalImage.MAX_BYTES && id.matches("[A-Za-z0-9_-]{1,220}")) {
            Path path = data.resolve("attachments").resolve(id + ext);
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) source = new ChatImage.Desktop(path);
        }
        return new ChatImage(T3Api.string(file, "name"), mime, size, source);
    }
}
