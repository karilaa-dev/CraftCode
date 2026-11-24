package dev.t3mc.ui.model;

/** A starred catalog entry, keyed by the provider instance as model IDs may overlap. */
public record ModelFavorite(String providerId, String modelId) {
    public ModelFavorite {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("A favorite needs both a provider and a model.");
        }
    }
}
