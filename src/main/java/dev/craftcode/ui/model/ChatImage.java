package dev.craftcode.ui.model;

import dev.craftcode.L10n;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Objects;
import java.util.zip.ZipFile;

/** Image data available to an authenticated thread. Only the local receipt store builds Cached sources. */
public record ChatImage(String name, String mimeType, long sizeBytes, Source source) {
    /** Only mod-authored errors free of credentials and paths can be shown in a preview. */
    public static final class PreviewError extends IOException {
        public PreviewError(String message) { super(message); }
    }

    public sealed interface Source permits Cached, Embedded, Web, Unavailable, PagedInline {}
    public record Unavailable() implements Source {}
    public record Cached(Path receipt, String entry) implements Source {}
    public record Embedded(String base64) implements Source {
        @Override public String toString() { return "Inline chat image"; }
    }
    public record TextPage(String text, int nextOffset) {}
    @FunctionalInterface public interface PageReader {
        TextPage readImagePage(String threadId, String itemId, int offset) throws IOException;
    }
    /** An inline image cut by MCP's per-item text limit. Reads further pages only when opened. */
    public record PagedInline(String beginning, String threadId, String itemId, int offset, PageReader reader) implements Source {
        @Override public String toString() { return "Paged chat image"; }
    }
    public record Web(URI url) implements Source {
        public Web {
            if (url == null || url.getHost() == null || url.getUserInfo() != null
                    || !("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())))
                throw new IllegalArgumentException(L10n.tr("chat.image.invalid_address", "Invalid chat image address."));
        }
        @Override public String toString() { return url.getHost(); }
    }

    public ChatImage {
        Objects.requireNonNull(source, "source");
        name = Objects.requireNonNullElse(name, "");
        mimeType = Objects.requireNonNullElse(mimeType, "");
    }

    @Override public String name() { return name.isBlank() ? L10n.tr("file.image", "Image") : name; }
    public boolean needsClick() { return source instanceof Web || source instanceof PagedInline; }
    public String host() { return source instanceof Web remote ? remote.url().getHost() : ""; }

    public byte[] readBytes() throws IOException {
        if (source instanceof Unavailable) throw new PreviewError(L10n.tr("chat.image.open_in_t3", "Open this image in T3 Code. The server did not share a preview."));
        byte[] bytes;
        if (source instanceof Cached cached) {
            try (var zip = new ZipFile(cached.receipt().toFile())) {
                var entry = zip.getEntry(cached.entry());
                if (entry == null || entry.getSize() != sizeBytes || sizeBytes < 1 || sizeBytes > LocalImage.MAX_BYTES)
                    throw new PreviewError(L10n.tr("chat.image.cache_missing", "This image is no longer in the local cache."));
                try (InputStream input = zip.getInputStream(entry)) {
                    bytes = input.readNBytes((int) sizeBytes + 1);
                    if (bytes.length != sizeBytes) throw new PreviewError(L10n.tr("chat.image.cache_incomplete", "Incomplete cached image."));
                }
            }
        } else if (source instanceof Embedded || source instanceof PagedInline) {
            String data = source instanceof Embedded embedded ? embedded.base64() : remainingData((PagedInline) source);
            if (data.length() > MAX_BASE64_CHARS) throw new PreviewError(L10n.tr("chat.image.exceeds_limit", "Image exceeds 10 MiB."));
            try { bytes = Base64.getDecoder().decode(data); }
            catch (IllegalArgumentException bad) { throw new PreviewError(L10n.tr("chat.image.inline_invalid", "Invalid inline image.")); }
        } else {
            bytes = ChatImageDownload.read(((Web) source).url());
        }
        if (bytes.length > LocalImage.MAX_BYTES) throw new PreviewError(L10n.tr("chat.image.exceeds_limit", "Image exceeds 10 MiB."));
        String actual = LocalImage.detectType(bytes);
        if (!mimeType.isEmpty() && !actual.equals(mimeType)) throw new PreviewError(L10n.tr("chat.image.type_mismatch", "The image type does not match its data."));
        return bytes;
    }

    private static final int MAX_BASE64_CHARS = 4 * ((LocalImage.MAX_BYTES + 2) / 3);

    private static String remainingData(PagedInline image) throws IOException {
        var data = new StringBuilder(image.beginning());
        int offset = image.offset();
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(40);
        for (int pageNumber = 0; pageNumber < 750 && System.nanoTime() < deadline; pageNumber++) {
            TextPage page = image.reader().readImagePage(image.threadId(), image.itemId(), offset);
            String text = page.text();
            int end = 0;
            while (end < text.length() && text.charAt(end) != ')' && text.charAt(end) != '>') {
                char ch = text.charAt(end++);
                if (!(ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch >= '0' && ch <= '9' || ch == '+' || ch == '/' || ch == '='))
                    throw new PreviewError(L10n.tr("chat.image.inline_invalid", "Invalid inline image."));
            }
            if (data.length() + (long) end > MAX_BASE64_CHARS) throw new PreviewError(L10n.tr("chat.image.exceeds_limit", "Image exceeds 10 MiB."));
            data.append(text, 0, end);
            if (end < text.length()) return data.toString();
            if (text.isEmpty() || page.nextOffset() != offset + text.length()) throw new PreviewError(L10n.tr("chat.image.inline_incomplete", "Incomplete inline image."));
            offset = page.nextOffset();
        }
        throw new PreviewError(L10n.tr("chat.image.load_timeout", "Image loading timed out."));
    }

    @Override public String toString() { return name(); }
}
