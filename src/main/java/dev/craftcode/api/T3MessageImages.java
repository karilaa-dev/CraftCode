package dev.craftcode.api;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.craftcode.ui.model.ChatImage;
import dev.craftcode.ui.model.LocalImage;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads inline Markdown and image descriptors. Filesystem paths in a transcript never become local file sources. */
final class T3MessageImages {
    private static final int MAX_REFERENCES = 24;
    private static final Pattern MARKDOWN = Pattern.compile("!\\[([^\\]\\n]{0,512})\\]\\([ \\t]*");
    private static final Pattern INLINE = Pattern.compile("^data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/]+=*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PARTIAL = Pattern.compile("!\\[([^\\]\\n]{0,512})\\]\\(<?data:(image/(?:png|jpeg|gif|webp));base64,([A-Za-z0-9+/]*=*)$", Pattern.CASE_INSENSITIVE);
    record Content(String text, List<ChatImage> images) {}

    static Content extract(String text, JsonObject item, URI endpoint) {
        return extract(text, item, endpoint, null, "");
    }

    static Content extract(String text, JsonObject item, URI endpoint, ChatImage.PageReader reader, String threadId) {
        var images = new ArrayList<ChatImage>();
        if (item.get("attachments") instanceof com.google.gson.JsonArray descriptors) {
            for (JsonElement element : descriptors) {
                if (!(element instanceof JsonObject image)) continue;
                if (images.size() >= MAX_REFERENCES) break;
                var destination = T3Api.string(image, "dataUrl");
                if (destination.isEmpty()) destination = T3Api.string(image, "url");
                if (destination.isEmpty()) destination = T3Api.string(image, "downloadUrl");
                long size;
                try { size = Long.parseLong(T3Api.string(image, "sizeBytes")); }
                catch (NumberFormatException ignored) { size = -1; }
                String name = T3Api.string(image, "name");
                String mime = T3Api.string(image, "mimeType");
                images.add("image".equals(T3Api.string(image, "type")) ? image(name, mime, size, destination, endpoint)
                        : new ChatImage(name, mime, size, new ChatImage.Unavailable()));
            }
        }
        if (!text.contains("![")) return new Content(text, List.copyOf(images));
        var body = new StringBuilder();
        String fence = "";
        int inlineTicks = 0;
        String[] lines = text.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index > 0) body.append('\n');
            String leading = line.stripLeading();
            if (leading.startsWith("```") || leading.startsWith("~~~")) {
                String marker = leading.substring(0, 3);
                if (fence.isEmpty()) fence = marker;
                else if (fence.equals(marker)) fence = "";
                inlineTicks = 0;
                body.append(line);
                continue;
            }
            if (!fence.isEmpty()) { body.append(line); continue; }
            Matcher matches = MARKDOWN.matcher(line);
            int tail = 0, scanned = 0;
            while (matches.find(scanned) && images.size() < MAX_REFERENCES) {
                inlineTicks = ticks(line, scanned, matches.start(), inlineTicks);
                scanned = matches.end();
                if (inlineTicks != 0 || matches.start() > 0 && line.charAt(matches.start() - 1) == '\\') continue;
                Destination destination = destination(line, scanned);
                if (destination == null) continue;
                images.add(image(matches.group(1), "", -1, destination.value(), endpoint));
                body.append(line, tail, matches.start());
                tail = scanned = destination.end();
            }
            if (index == lines.length - 1 && images.size() < MAX_REFERENCES
                    && T3Api.flag(item, "textTruncated", false)) {
                Matcher partial = PARTIAL.matcher(line);
                if (partial.find(tail) && ticks(line, scanned, partial.start(), inlineTicks) == 0) {
                    ChatImage.Source source = new ChatImage.Unavailable();
                    String itemId = T3Api.string(item, "itemId");
                    if (reader != null && !itemId.isBlank() && T3Api.present(item, "nextTextOffset")) {
                        int offset;
                        try { offset = Integer.parseInt(T3Api.string(item, "nextTextOffset")); }
                        catch (NumberFormatException malformed) { offset = 0; }
                        if (offset > 0) source = new ChatImage.PagedInline(partial.group(3), threadId, itemId, offset, reader);
                    }
                    images.add(new ChatImage(partial.group(1), partial.group(2).toLowerCase(Locale.ROOT), -1, source));
                    body.append(line, tail, partial.start());
                    tail = line.length();
                }
            }
            inlineTicks = ticks(line, scanned, line.length(), inlineTicks);
            body.append(line, tail, line.length());
        }
        return new Content(body.toString().stripTrailing(), List.copyOf(images));
    }

    private record Destination(String value, int end) {}

    private static Destination destination(String line, int start) {
        if (start >= line.length()) return null;
        int end = start, position;
        if (line.charAt(start) == '<') {
            start++;
            end = line.indexOf('>', start);
            if (end < 0) return null;
            position = end + 1;
        } else {
            int depth = 0;
            while (end < line.length()) {
                char ch = line.charAt(end);
                if (Character.isWhitespace(ch) || ch == ')' && depth == 0) break;
                if (ch == '(') depth++;
                if (ch == ')') depth--;
                end++;
            }
            if (depth != 0) return null;
            position = end;
        }
        if (end == start) return null;
        while (position < line.length() && Character.isWhitespace(line.charAt(position))) position++;
        if (position < line.length() && (line.charAt(position) == '"' || line.charAt(position) == '\'')) {
            int close = line.indexOf(line.charAt(position), position + 1);
            if (close < 0) return null;
            position = close + 1;
            while (position < line.length() && Character.isWhitespace(line.charAt(position))) position++;
        }
        return position < line.length() && line.charAt(position) == ')'
                ? new Destination(line.substring(start, end), position + 1) : null;
    }

    private static int ticks(String line, int start, int end, int open) {
        for (int i = start; i < end; i++) {
            if (line.charAt(i) != '`' || i > 0 && line.charAt(i - 1) == '\\') continue;
            int n = 1;
            while (i + n < end && line.charAt(i + n) == '`') n++;
            if (open == n) open = 0;
            else if (open == 0) open = n;
            i += n - 1;
        }
        return open;
    }

    private static ChatImage image(String name, String mime, long size, String destination, URI endpoint) {
        Matcher embedded = INLINE.matcher(destination);
        if (embedded.matches()) {
            String data = embedded.group(2);
            int padding = data.endsWith("==") ? 2 : data.endsWith("=") ? 1 : 0;
            long bytes = data.length() * 3L / 4 - padding;
            if (bytes > 0 && bytes <= LocalImage.MAX_BYTES)
                return new ChatImage(name, embedded.group(1).toLowerCase(Locale.ROOT), bytes, new ChatImage.Embedded(data));
        } else if (!destination.isBlank()) {
            try {
                URI url = URI.create(destination);
                if (endpoint != null && signedAssetPath(url)) url = endpoint.resolve(url);
                var source = new ChatImage.Web(url);
                String path = url.getPath();
                if (name.isBlank() && path != null && !path.endsWith("/")) name = path.substring(path.lastIndexOf('/') + 1);
                return new ChatImage(name, mime, size, source);
            } catch (IllegalArgumentException ignored) { /* A workspace path stays in its environment. */ }
        }
        return new ChatImage(name, mime, size, new ChatImage.Unavailable());
    }

    private static boolean signedAssetPath(URI url) {
        if (url.isAbsolute() || url.getRawAuthority() != null || !url.normalize().equals(url)) return false;
        // The T3 client receives /api/assets/{signedToken}/{encodedFilename}. Permit encoded spaces etc.
        // while verifying the decoded structure, so escaped path segments never change the destination.
        String[] path = url.getPath().split("/", -1);
        return path.length == 5 && path[0].isEmpty() && path[1].equals("api") && path[2].equals("assets")
                && path[3].matches("[A-Za-z0-9._~-]+") && !path[3].equals(".") && !path[3].equals("..")
                && !path[4].isBlank() && !path[4].equals(".") && !path[4].equals("..") && !path[4].contains("\\");
    }
}
