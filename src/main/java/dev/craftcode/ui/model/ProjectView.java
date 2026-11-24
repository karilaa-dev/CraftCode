package dev.craftcode.ui.model;

import java.util.List;
import java.util.Objects;

/** A project with its threads, newest first. */
public record ProjectView(String id, String name, String path, List<ThreadSummary> threads) {
    public ProjectView {
        id = Objects.requireNonNull(id, "id");
        name = Objects.requireNonNullElse(name, "");
        path = Objects.requireNonNullElse(path, "");
        threads = threads == null ? List.of() : List.copyOf(threads);
    }
}
