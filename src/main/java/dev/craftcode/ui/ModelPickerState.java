package dev.craftcode.ui;

import dev.craftcode.L10n;
import dev.craftcode.ClientController;
import dev.craftcode.ui.model.ModelChoice;
import dev.craftcode.ui.model.ModelProvider;
import dev.craftcode.ui.model.ModelSelection;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/**
 * Model picker state that outlives the picker overlay: the last loaded catalog, per-thread drafts
 * (kept after a rejected change so the player can edit and retry) and the in-flight model change.
 * Owned by {@link WorkspaceScreen}. All controller calls happen from explicit player actions or
 * the picker's tick, never while rendering; results are applied on the client thread.
 */
final class ModelPickerState {
    private final ClientController controller;

    // Catalog (search = "")
    private List<ModelProvider> catalog = List.of();
    private boolean catalogLoaded;
    private boolean loading;
    private String loadError = "";
    private int loadSeq;

    // Server-side search for models the full catalog omits
    private boolean searching;
    private String searchError = "";
    private int searchSeq;
    /** Models found by server searches, kept for labels, favorites and the list after the query is cleared. */
    private final Map<String, ModelProvider> discovered = new LinkedHashMap<>();
    private final Set<String> describedIds = new HashSet<>();
    private @Nullable List<ModelProvider> knownCache;
    private int knownVersion = -1;
    private long generation = Long.MIN_VALUE;

    private final Map<String, ModelSelection> drafts = new HashMap<>();
    private final Map<String, String> failures = new HashMap<>();
    private final Map<String, Long> failedAt = new HashMap<>();
    private @Nullable String applyingThreadId;
    private @Nullable ModelSelection applyingSelection;
    /** Bumped on every catalog, search or apply change so views know to rebuild. */
    private int version;

    ModelPickerState(ClientController controller) {
        this.controller = controller;
    }

    // ---------------------------------------------------------------- catalog

    int version() {
        return this.version;
    }

    List<ModelProvider> catalog() {
        return this.catalog;
    }

    boolean catalogLoaded() {
        return this.catalogLoaded;
    }

    boolean loading() {
        return this.loading;
    }

    String loadError() {
        return this.loadError;
    }

    boolean searching() {
        return this.searching;
    }

    String searchError() {
        return this.searchError;
    }

    /** Fetches the full catalog. Keeps showing the previous one while it loads. */
    void reload() {
        int seq = ++this.loadSeq;
        this.loading = true;
        this.loadError = "";
        this.version++;
        call(() -> this.controller.listModels(""), (providers, error) -> {
            if (seq != this.loadSeq) return;
            this.loading = false;
            this.version++;
            if (error != null) {
                this.loadError = message(error, L10n.tr("model.error.load", "Could not load models."));
            } else {
                this.catalog = providers == null ? List.of() : List.copyOf(providers);
                this.catalogLoaded = true;
                this.describedIds.clear();
            }
        });
    }

    /** Resolve options for a thread or favorite whose model is outside the server's initial shortlist. */
    void describe(@Nullable ModelSelection selection) {
        if (this.loading || !this.catalogLoaded || !ModelFormat.isSet(selection)) return;
        String model = selection.modelId();
        ModelProvider provider = ModelFormat.provider(this.known(), selection.providerId());
        if (provider == null || provider.models().stream().anyMatch(choice -> choice.id().equals(model))
                || !this.describedIds.add(model)) return;
        int seq = this.loadSeq;
        call(() -> this.controller.listModels(model), (providers, error) -> {
            if (seq != this.loadSeq || error != null) return;
            for (var hit : providers) this.discovered.merge(hit.id(), hit, ModelPickerState::union);
            this.version++;
        });
    }

    /** Asks the server for {@code query}; new models are kept in {@link #known()}. Blank clears. */
    void search(String query) {
        String q = query.strip();
        int seq = ++this.searchSeq;
        this.searchError = "";
        this.version++;
        if (q.isEmpty()) {
            this.searching = false;
            return;
        }
        this.searching = true;
        call(() -> this.controller.listModels(q), (providers, error) -> {
            if (seq != this.searchSeq) return;
            this.searching = false;
            this.version++;
            if (error != null) {
                this.searchError = message(error, L10n.tr("model.error.search", "Search failed."));
            } else {
                for (var hit : providers) this.discovered.merge(hit.id(), hit, ModelPickerState::union);
            }
        });
    }

    /**
     * Everything known for this connection: the catalog plus models earlier searches found that it omits.
     * Providers only seen in search results are listed only once the catalog has them too.
     */
    List<ModelProvider> known() {
        if (this.discovered.isEmpty()) return this.catalog;
        if (this.knownCache != null && this.knownVersion == this.version) return this.knownCache;
        var byId = new LinkedHashMap<String, ModelProvider>();
        for (var p : this.catalog) byId.put(p.id(), p);
        for (var hit : this.discovered.values()) {
            var base = byId.get(hit.id());
            if (base != null) byId.put(base.id(), union(base, hit));
        }
        this.knownCache = List.copyOf(byId.values());
        this.knownVersion = this.version;
        return this.knownCache;
    }

    private static ModelProvider union(ModelProvider base, ModelProvider extra) {
        var models = new ArrayList<>(base.models());
        boolean added = false;
        for (ModelChoice m : extra.models()) {
            if (models.stream().noneMatch(e -> e.id().equals(m.id()))) {
                models.add(m);
                added = true;
            }
        }
        return added ? new ModelProvider(base.id(), base.label(), models, base.driverKind()) : base;
    }

    /**
     * Forgets everything tied to the current connection (catalog, search hits, drafts) so a previous
     * environment's models are never offered after a reconnect. In-flight loads are ignored on arrival.
     */
    void syncGeneration(long generation) {
        if (generation == this.generation) return;
        this.generation = generation;
        this.loadSeq++;
        this.searchSeq++;
        this.catalog = List.of();
        this.catalogLoaded = false;
        this.loading = false;
        this.loadError = "";
        this.searching = false;
        this.searchError = "";
        this.discovered.clear();
        this.describedIds.clear();
        this.drafts.clear();
        this.failures.clear();
        this.failedAt.clear();
        this.applyingThreadId = null;
        this.applyingSelection = null;
        this.knownCache = null;
        this.version++;
    }

    long generation() {
        return this.generation;
    }

    // ---------------------------------------------------------------- drafts & applying

    @Nullable ModelSelection draft(String threadId) {
        return this.drafts.get(threadId);
    }

    void setDraft(String threadId, @Nullable ModelSelection draft) {
        if (draft == null) this.drafts.remove(threadId);
        else this.drafts.put(threadId, draft);
    }

    /** Last rejection message for the thread, or empty. */
    String failure(String threadId) {
        return this.failures.getOrDefault(threadId, "");
    }

    /** True for a few seconds after a rejected change, for the composer hint. */
    boolean recentlyFailed(String threadId) {
        Long at = this.failedAt.get(threadId);
        return at != null && Util.getMillis() - at < 6000;
    }

    void clearFailure(String threadId) {
        this.failures.remove(threadId);
        this.failedAt.remove(threadId);
    }

    boolean applying() {
        return this.applyingThreadId != null;
    }

    boolean applying(@Nullable String threadId) {
        return threadId != null && threadId.equals(this.applyingThreadId);
    }

    @Nullable ModelSelection applyingSelection() {
        return this.applyingSelection;
    }

    /** Drops state for threads that no longer exist. */
    void retainThreads(java.util.Set<String> threadIds) {
        this.drafts.keySet().retainAll(threadIds);
        this.failures.keySet().retainAll(threadIds);
        this.failedAt.keySet().retainAll(threadIds);
    }

    /**
     * Sends the player's choice for exactly {@code threadId}. On rejection the selection stays as the
     * thread's draft. {@code done} runs on the client thread with whether T3 Code accepted it.
     * Returns false without calling the controller while another change is in flight.
     */
    boolean apply(String threadId, ModelSelection selection, Consumer<Boolean> done) {
        if (this.applyingThreadId != null || !ModelFormat.isSet(selection)) return false;
        this.applyingThreadId = threadId;
        this.applyingSelection = selection;
        this.drafts.put(threadId, selection);
        this.clearFailure(threadId);
        this.version++;
        long requestedGeneration = this.generation;
        call(() -> this.controller.changeOnConnection(requestedGeneration, () -> this.controller.configureModel(threadId, selection)), (ok, error) -> {
            if (requestedGeneration != this.controller.connectionGeneration()) return;
            this.applyingThreadId = null;
            this.applyingSelection = null;
            this.version++;
            boolean accepted = error == null && Boolean.TRUE.equals(ok);
            if (accepted) {
                this.drafts.remove(threadId, selection);
            } else {
                this.drafts.putIfAbsent(threadId, selection);
                this.failures.put(threadId, error != null ? message(error, L10n.tr("model.error.change", "Model change failed."))
                        : L10n.tr("model.error.rejected", "T3 Code did not accept this model change."));
                this.failedAt.put(threadId, Util.getMillis());
            }
            done.accept(accepted);
        });
        return true;
    }

    // ---------------------------------------------------------------- helpers

    private interface Call<T> {
        CompletionStage<T> start();
    }

    private interface Done<T> {
        void accept(@Nullable T value, @Nullable Throwable error);
    }

    private static <T> void call(Call<T> call, Done<T> done) {
        CompletionStage<T> stage;
        try {
            stage = call.start();
            if (stage == null) stage = CompletableFuture.failedFuture(new IllegalStateException(L10n.tr("model.error.not_connected", "Not connected.")));
        } catch (RuntimeException e) {
            stage = CompletableFuture.failedFuture(e);
        }
        stage.whenComplete((value, error) -> Minecraft.getInstance().execute(() -> done.accept(value, error)));
    }

    private static String message(Throwable error, String fallback) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof java.util.concurrent.ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String text = cause.getMessage();
        if (text == null || text.isBlank()) return fallback;
        text = text.strip().replace('\n', ' ');
        return text.length() > 160 ? text.substring(0, 159) + "…" : text;
    }
}
