package dev.craftcode.ui.model;

import dev.craftcode.L10n;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;

/** A file chosen in this Minecraft instance. Only the basename is sent to T3 Code. */
public final class LocalImage {
    public static final int MAX_IMAGES = 8;
    public static final int MAX_BYTES = 10 * 1024 * 1024;
    private static final byte[] PNG = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private final Path path;
    private final String name;
    private final String mimeType;
    private final long sizeBytes;

    private LocalImage(Path path, String name, String mimeType, long sizeBytes) {
        this.path = path;
        this.name = name;
        this.mimeType = mimeType;
        this.sizeBytes = sizeBytes;
    }

    public static LocalImage open(Path path) throws IOException {
        if (path == null || path.getFileName() == null) throw new IOException(L10n.tr("chat.image.choose_file", "Choose an image file."));
        Path resolved = path.toRealPath();
        long size = imageSize(resolved);
        String name = path.getFileName().toString();
        if (name.isBlank() || name.length() > 255) throw new IOException(L10n.tr("chat.image.filename_length", "Choose a shorter image filename."));
        try (InputStream input = Files.newInputStream(resolved, LinkOption.NOFOLLOW_LINKS)) {
            return new LocalImage(resolved, name, detectType(input.readNBytes(12)), size);
        }
    }

    public Path path() { return path; }
    public String name() { return name; }
    public String mimeType() { return mimeType; }
    public long sizeBytes() { return sizeBytes; }

    public byte[] readBytes() throws IOException {
        if (imageSize(path) != sizeBytes) throw changed();
        try (InputStream input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            // Do not rely on the file's stat size as an allocation or upload bound.
            byte[] data = input.readNBytes((int) sizeBytes + 1);
            if (data.length != sizeBytes || !mimeType.equals(detectType(data))) throw changed();
            return data;
        }
    }

    private IOException changed() { return new IOException(L10n.tr("chat.image.file_changed", "%s changed. Remove it and attach it again.", name)); }

    private static long imageSize(Path path) throws IOException {
        BasicFileAttributes file = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!file.isRegularFile() || file.size() == 0) throw new IOException(L10n.tr("chat.image.empty_file", "Choose a nonempty image file."));
        if (file.size() > MAX_BYTES) throw new IOException(L10n.tr("chat.image.file_size", "Images can be at most 10 MiB each."));
        return file.size();
    }

    static String detectType(byte[] data) throws IOException {
        if (data.length >= PNG.length && Arrays.equals(data, 0, PNG.length, PNG, 0, PNG.length)) return "image/png";
        if (data.length >= 3 && data[0] == (byte) 255 && data[1] == (byte) 216 && data[2] == (byte) 255) return "image/jpeg";
        if (matches(data, 0, "GIF87a") || matches(data, 0, "GIF89a")) return "image/gif";
        if (matches(data, 0, "RIFF") && matches(data, 8, "WEBP")) return "image/webp";
        throw new IOException(L10n.tr("chat.image.file_format", "Choose a PNG, JPEG, GIF, or WebP image."));
    }

    private static boolean matches(byte[] data, int offset, String ascii) {
        if (data.length < offset + ascii.length()) return false;
        for (int i = 0; i < ascii.length(); i++) if (data[offset + i] != ascii.charAt(i)) return false;
        return true;
    }
}
