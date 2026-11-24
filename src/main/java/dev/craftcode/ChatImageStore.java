package dev.craftcode;

import static dev.craftcode.L10n.tr;

import dev.craftcode.ui.model.ChatImage;
import dev.craftcode.ui.model.LocalImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;
import java.util.zip.*;

/** Local copies of the exact bytes uploaded in confirmed messages, scoped to the route and source thread. */
final class ChatImageStore {
    private static final long MAX_DISK_BYTES = 192L * 1024 * 1024;
    private static final int MAX_RECEIPTS = 256;
    private final Path directory;

    ChatImageStore(Path directory) { this.directory = directory; }

    private Path receipt(String endpoint, String environment, String thread, String message) {
        try {
            var hash = MessageDigest.getInstance("SHA-256");
            for (String part : List.of(endpoint, environment, thread, message)) {
                byte[] value = part.getBytes(StandardCharsets.UTF_8);
                hash.update(java.nio.ByteBuffer.allocate(4).putInt(value.length).array());
                hash.update(value);
            }
            return directory.resolve(HexFormat.of().formatHex(hash.digest()) + ".zip");
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    synchronized void put(String endpoint, String environment, String thread, String message, List<LocalImage> images,
                          List<byte[]> payloads) throws IOException {
        if (message.isBlank() || images.size() != payloads.size()) throw new IOException(tr("cache.missing_receipt", "Missing image receipt."));
        Files.createDirectories(directory);
        Path target = receipt(endpoint, environment, thread, message);
        Path temporary = Files.createTempFile(directory, ".upload-", ".tmp");
        try {
            try (var archive = new ZipOutputStream(Files.newOutputStream(temporary))) {
                for (int i = 0; i < images.size(); i++) {
                    LocalImage image = images.get(i);
                    byte[] bytes = payloads.get(i);
                    String suffix = image.mimeType().substring("image/".length());
                    var entry = new ZipEntry(i + "." + suffix);
                    entry.setComment(image.name());
                    entry.setMethod(ZipEntry.STORED);
                    var crc = new CRC32();
                    crc.update(bytes);
                    entry.setSize(bytes.length);
                    entry.setCompressedSize(bytes.length);
                    entry.setCrc(crc.getValue());
                    archive.putNextEntry(entry);
                    archive.write(bytes);
                    archive.closeEntry();
                }
            }
            try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            prune(target);
        } finally { Files.deleteIfExists(temporary); }
    }

    List<ChatImage> get(String endpoint, String environment, String thread, String message) {
        if (message.isBlank()) return List.of();
        Path target = receipt(endpoint, environment, thread, message);
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) return List.of();
        var images = new ArrayList<ChatImage>();
        try (var archive = new ZipFile(target.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements() && images.size() < LocalImage.MAX_IMAGES) {
                var entry = entries.nextElement();
                String key = entry.getName();
                if (!key.matches("[0-7]\\.(png|jpeg|gif|webp)") || entry.getSize() < 1 || entry.getSize() > LocalImage.MAX_BYTES) continue;
                images.add(new ChatImage(entry.getComment(), "image/" + key.substring(2), entry.getSize(), new ChatImage.Cached(target, key)));
            }
        } catch (IOException | IllegalArgumentException corrupt) { return List.of(); }
        return List.copyOf(images);
    }

    private void prune(Path latest) throws IOException {
        try (var files = Files.list(directory)) {
            var receipts = files.filter(p -> p.getFileName().toString().matches("[0-9a-f]{64}\\.zip") && !p.equals(latest))
                    .sorted(Comparator.comparingLong((Path p) -> p.toFile().lastModified()).reversed()).toList();
            long retained = Files.size(latest);
            int count = 1;
            for (Path path : receipts) {
                long size = path.toFile().length();
                if (count >= MAX_RECEIPTS || retained + size > MAX_DISK_BYTES) Files.deleteIfExists(path);
                else { retained += size; count++; }
            }
        }
    }
}
