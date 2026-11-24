package dev.craftcode.ui.model;

import java.util.List;
import java.util.Objects;

public record ModelChoice(String id, String label, List<ModelOption> options) {
    public ModelChoice {
        id = Objects.requireNonNull(id);
        label = label == null || label.isBlank() ? id : label;
        options = List.copyOf(options);
    }
}
