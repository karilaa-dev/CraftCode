package dev.t3mc.ui.model;

import java.util.Map;
import java.util.Objects;

/** Model options retain their MCP primitive types, including booleans. */
public record ModelSelection(String providerId, String modelId, Map<String, Object> options) {
    public ModelSelection {
        providerId = Objects.requireNonNullElse(providerId, "").strip();
        modelId = Objects.requireNonNullElse(modelId, "").strip();
        options = options == null ? Map.of() : Map.copyOf(options);
        for (var value : options.values()) {
            if (!(value instanceof String || value instanceof Boolean))
                throw new IllegalArgumentException("Model option values must be text or booleans.");
        }
    }

    public static ModelSelection empty() { return new ModelSelection("", "", Map.of()); }
}
