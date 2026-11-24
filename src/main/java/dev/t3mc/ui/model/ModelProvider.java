package dev.t3mc.ui.model;

import java.util.List;
import java.util.Objects;

public record ModelProvider(String id, String label, List<ModelChoice> models, String driverKind) {
    public ModelProvider(String id, String label, List<ModelChoice> models) {
        this(id, label, models, id);
    }

    public ModelProvider {
        id = Objects.requireNonNull(id);
        label = label == null || label.isBlank() ? id : label;
        driverKind = driverKind == null || driverKind.isBlank() ? id : driverKind;
        models = List.copyOf(models);
    }
}
