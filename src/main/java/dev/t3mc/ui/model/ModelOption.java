package dev.t3mc.ui.model;

import java.util.List;
import java.util.Objects;

public record ModelOption(String id, List<Object> values, Object defaultValue) {
    public ModelOption {
        id = Objects.requireNonNull(id);
        values = List.copyOf(values);
    }
}
