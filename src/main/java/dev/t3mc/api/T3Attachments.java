package dev.t3mc.api;

import dev.t3mc.L10n;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.t3mc.mcp.McpClient;
import dev.t3mc.ui.model.LocalImage;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** T3 stages each image before claiming all staged uploads in one message. */
final class T3Attachments {
    private T3Attachments() {}

    static String send(T3Api api, String threadId, String caption, List<LocalImage> images,
                       java.util.function.Consumer<byte[]> capture) throws IOException {
        if (images.isEmpty() || images.size() > LocalImage.MAX_IMAGES)
            throw new IOException(L10n.tr("api.image.selection_count", "Choose between one and %s images.", LocalImage.MAX_IMAGES));
        if (caption != null && caption.length() > 120_000)
            throw new IOException(L10n.tr("api.image.caption_limit", "T3 accepts up to 120,000 characters with an image."));

        List<String> pending = new ArrayList<>();
        JsonArray attachments = new JsonArray();
        try {
            for (LocalImage image : images) {
                byte[] bytes = image.readBytes();
                JsonObject attachment = new JsonObject();
                attachment.addProperty("type", "image");
                attachment.addProperty("name", image.name());
                attachment.addProperty("mimeType", image.mimeType());
                attachment.addProperty("sizeBytes", bytes.length);
                JsonObject args = new JsonObject();
                args.add("upload", attachment);
                JsonObject upload = api.call("t3_attachment_prepare_upload", args);
                String id = T3Api.required(upload, "attachmentId");
                pending.add(id);
                api.upload(upload, image.mimeType(), bytes);
                capture.accept(bytes);
                attachment.addProperty("id", id);
                attachments.add(attachment);
            }
        } catch (IOException | RuntimeException failure) {
            discard(api, pending);
            throw failure;
        }

        JsonObject args = new JsonObject();
        args.addProperty("threadId", threadId);
        if (caption != null && !caption.isBlank()) args.addProperty("message", caption);
        args.add("attachments", attachments);
        try {
            JsonObject receipt = api.call("t3_thread_send_attachments", args);
            return T3Api.required(receipt, "messageId");
        } catch (McpClient.ToolRejected rejection) {
            discard(api, pending);
            throw rejection;
        } catch (IOException uncertain) {
            // This tool has no clientRequestId. A lost acknowledgement can follow a successful send.
            throw new IOException(L10n.tr("api.image.unconfirmed", "Image delivery unconfirmed. Check the thread before sending again."), uncertain);
        }
    }

    private static void discard(T3Api api, List<String> ids) {
        for (String id : ids) {
            JsonObject args = new JsonObject();
            args.addProperty("attachmentId", id);
            try {
                api.call("t3_attachment_discard", args);
            } catch (IOException ignored) {
                // T3 automatically sweeps unclaimed uploads. Preserve the original failure.
            }
        }
    }
}
