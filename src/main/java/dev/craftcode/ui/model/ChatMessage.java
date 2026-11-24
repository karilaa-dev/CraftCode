package dev.craftcode.ui.model;

import java.util.Objects;
import java.util.List;

public record ChatMessage(String id, MessageRole role, String text, long createdAtMillis, boolean streaming,
                          String runId, String phase, List<ChatImage> images, RunTiming runTiming, int position) {
    public record RunTiming(long startedAtMillis, long completedAtMillis) {}

    public ChatMessage(String id, MessageRole role, String text, long createdAtMillis, boolean streaming,
                       String runId, String phase, List<ChatImage> images, RunTiming runTiming) {
        this(id, role, text, createdAtMillis, streaming, runId, phase, images, runTiming, -1);
    }

    public ChatMessage(String id, MessageRole role, String text, long createdAtMillis, boolean streaming,
                       String runId, String phase, List<ChatImage> images) {
        this(id, role, text, createdAtMillis, streaming, runId, phase, images, null);
    }

    public ChatMessage(String id, MessageRole role, String text, long createdAtMillis, boolean streaming) {
        this(id, role, text, createdAtMillis, streaming, "", "", List.of());
    }

    public ChatMessage {
        id = Objects.requireNonNull(id, "id");
        role = Objects.requireNonNullElse(role, MessageRole.ASSISTANT);
        text = Objects.requireNonNullElse(text, "");
        runId = Objects.requireNonNullElse(runId, "");
        phase = Objects.requireNonNullElse(phase, "");
        images = images == null ? List.of() : List.copyOf(images);
    }
}
