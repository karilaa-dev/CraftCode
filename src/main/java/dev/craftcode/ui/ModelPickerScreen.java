package dev.craftcode.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.cursor.CursorTypes;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.craftcode.ClientController;
import dev.craftcode.L10n;
import dev.craftcode.ui.model.ConnectionState;
import dev.craftcode.ui.model.ModelChoice;
import dev.craftcode.ui.model.ModelFavorite;
import dev.craftcode.ui.model.ModelOption;
import dev.craftcode.ui.model.ModelProvider;
import dev.craftcode.ui.model.ModelSelection;
import dev.craftcode.ui.model.ThreadDetail;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Model picker popover, opened from the composer's model chip. Drawn over the workspace (which keeps
 * updating behind it) and modal: Escape clears the filter, then closes; clicking outside closes.
 * <p>
 * The player builds a pending choice (provider, model, settings) and confirms it with Apply, Enter on
 * a row, or a double click. Only that confirmation calls {@link ClientController#configureModel}, for
 * the thread the picker was opened on. Until a thread is configured, the T3 project's defaults apply.
 */
final class ModelPickerScreen extends SpruceScreen {
    static final String TAB_ALL = "\u0000all";
    static final String TAB_FAVORITES = "\u0000fav";
    private static final int PAD = 10;
    private static final int TAB_H = 18;
    private static final int FOOTER_H = 18;
    private static final long SEARCH_DEBOUNCE_MS = 350;

    /** {@code label} is the visible text (empty when the icon alone identifies the provider); {@code name} is spoken and hovered. */
    private record Tab(String key, String label, String name, @Nullable ProviderIcon icon, int x, int w, boolean dim) {}

    private final WorkspaceScreen parent;
    private final ClientController controller;
    private final ModelPickerState state;
    private final String threadId;
    private final long generation;

    // Survives rebuildWidgets()
    private String query = "";
    private String tab = TAB_ALL;
    private ModelSelection draft;
    private String status = "";
    private boolean statusError;
    private long queryChangedAt;
    private String searchedQuery = "";
    private Set<String> favorites = Set.of();
    private List<ModelFavorite> favoriteList = List.of();
    private Object rowsKey = new Object();
    private boolean firstInit = true;
    /** Whether the last layout was for a model with settings (the panel may still be omitted when tiny). */
    private boolean laidOutWithOptions;
    private @Nullable Object themeAtInit;

    // Layout
    private int px, py, pw, ph;
    private int titleY, summaryY, tabsY, footerY, optionsTop;
    private boolean twoLineHeader;
    private final List<Tab> tabs = new ArrayList<>();
    private int tabScroll;
    private int tabsWidth;

    private InputField search;
    private ModelList list;
    private @Nullable ModelOptionsPanel optionsPanel;
    private FlatButton applyButton;
    private FlatButton retryButton;
    /** Footer button widths, sized to their (translated) labels. */
    private int applyW = 64, cancelW = 54, retryW = 48;

    ModelPickerScreen(WorkspaceScreen parent, ClientController controller, ModelPickerState state, String threadId) {
        super(Component.literal(L10n.tr("model.title", "Choose model")));
        this.parent = parent;
        this.controller = controller;
        this.state = state;
        this.threadId = threadId;
        this.generation = state.generation();
        var current = this.currentSelection();
        var kept = state.draft(threadId);
        this.draft = kept != null ? kept : current != null ? current : ModelSelection.empty();
        this.reloadFavorites();
        state.reload();
    }

    // ---------------------------------------------------------------- data

    private @Nullable ThreadDetail thread() {
        var detail = this.controller.snapshot().selectedThread();
        return detail != null && detail.id().equals(this.threadId) ? detail : null;
    }

    private @Nullable ModelSelection currentSelection() {
        var detail = this.thread();
        if (detail == null) return null;
        var selection = detail.modelSelection();
        return ModelFormat.isSet(selection) ? selection : null;
    }

    private boolean connected() {
        return this.generation == this.controller.connectionGeneration()
                && this.controller.snapshot().connection().state() == ConnectionState.CONNECTED && this.thread() != null;
    }

    private boolean busy() {
        return this.state.applying();
    }

    private static String key(String providerId, String modelId) {
        return providerId + "\u0000" + modelId;
    }

    private void reloadFavorites() {
        this.favoriteList = this.controller.favoriteModels();
        var keys = new HashSet<String>();
        for (var f : this.favoriteList) keys.add(key(f.providerId(), f.modelId()));
        this.favorites = keys;
    }

    private List<ModelProvider> catalog() {
        return this.state.known();
    }

    /** The full catalog's provider (not search hits), used to tell providers that list no models. */
    private boolean listsNoModels(ModelProvider provider) {
        var base = ModelFormat.provider(this.state.catalog(), provider.id());
        return (base == null ? provider : base).models().isEmpty();
    }

    private @Nullable ModelChoice draftChoice() {
        return ModelFormat.model(this.catalog(), this.draft.providerId(), this.draft.modelId());
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.parent.resizeBehind(this.width, this.height);
        this.themeAtInit = Theme.current();
        var font = this.font;
        int[] anchor = this.parent.modelAnchor();
        this.pw = Math.min(460, this.width - 16);
        int above = anchor[1] - 4;
        int wanted = Math.min(340, this.height - 16);
        if (above - 8 >= Math.min(wanted, 190)) {
            this.ph = Math.min(wanted, above - 8);
            this.py = above - this.ph;
        } else { // not enough room above the composer: center on screen
            this.ph = wanted;
            this.py = (this.height - this.ph) / 2;
        }
        this.px = Math.clamp(anchor[0], 8, Math.max(8, this.width - 8 - this.pw));
        int innerX = this.px + PAD;
        int innerW = this.pw - PAD * 2;

        this.twoLineHeader = this.ph >= 210;
        int y = this.py + PAD;
        this.titleY = y;
        if (this.twoLineHeader) y += font.lineHeight + 3;
        this.summaryY = y;
        y += font.lineHeight + 5;

        this.search = new InputField(Position.of(innerX, y), innerW, 19, L10n.tr("model.search", "Search models"),
                L10n.tr("model.search.placeholder", "Search models or type a model ID…")).searchable();
        this.search.setTextPredicate(t -> t.length() <= 200);
        this.search.setText(this.query);
        this.search.setCursorToEnd();
        this.search.setChangedListener(this::onQueryChanged);
        this.addRenderableWidget(this.search);
        y += 19 + 5;

        this.tabsY = y;
        y += TAB_H + 5;

        this.footerY = this.py + this.ph - PAD - FOOTER_H;
        int bottom = this.footerY - 6;

        // Fit two typical option rows on a compact screen while keeping at least two model rows.
        var choice = this.draftChoice();
        List<ModelOption> options = choice == null ? List.of() : choice.options();
        int room = bottom - y;
        int optionsH = 0;
        this.laidOutWithOptions = !options.isEmpty();
        if (!options.isEmpty() && room >= ModelList.ROW_H * 2 + ModelOptionsPanel.CHIP_H + 22) {
            int optionBudget = Math.max(78, room / 2);
            optionsH = Math.min(ModelOptionsPanel.measure(options, this.draftDriver(), innerW), optionBudget);
            optionsH = Math.min(optionsH, room - ModelList.ROW_H * 2 - 6);
        }
        int listH = Math.max(ModelList.ROW_H * 2, room - (optionsH > 0 ? optionsH + 6 : 0));
        this.list = this.addRenderableWidget(new ModelList(Position.of(innerX - 4, y), innerW + 8, listH, new ModelList.Marks() {
            @Override
            public boolean isFavorite(String providerId, String modelId) {
                return ModelPickerScreen.this.favorites.contains(key(providerId, modelId));
            }

            @Override
            public boolean isCurrent(String providerId, String modelId) {
                return ModelFormat.sameModel(ModelPickerScreen.this.currentSelection(), providerId, modelId);
            }

            @Override
            public boolean isDraft(String providerId, String modelId) {
                return ModelFormat.sameModel(ModelPickerScreen.this.draft, providerId, modelId);
            }

            @Override
            public @Nullable ProviderIcon icon(String providerId) {
                return ModelFormat.distinctIcon(ModelPickerScreen.this.catalog(), providerId);
            }
        }, new ModelList.Actions() {
            @Override
            public void pick(ModelList.Item item, boolean activate) {
                ModelPickerScreen.this.pick(item, activate);
            }

            @Override
            public void toggleFavorite(ModelList.Item item) {
                ModelPickerScreen.this.toggleFavorite(item);
            }
        }));
        this.optionsPanel = null;
        this.optionsTop = 0;
        if (optionsH > 0) {
            this.optionsTop = y + listH + 6;
            this.optionsPanel = this.addRenderableWidget(new ModelOptionsPanel(Position.of(innerX - 4, this.optionsTop), innerW + 8,
                    Math.max(10, bottom - this.optionsTop), new ModelOptionsPanel.Actions() {
                        @Override
                        public void setOption(String optionId, @Nullable Object value) {
                            ModelPickerScreen.this.setOption(optionId, value);
                        }

                        @Override
                        public void resetOptions() {
                            ModelPickerScreen.this.resetOptions();
                        }
                    }));
        }

        String applyLabel = applyText(), cancelLabel = L10n.tr("model.cancel", "Cancel"), retryLabel = L10n.tr("model.retry", "Retry");
        int applyW = this.applyW = Math.max(66, Math.max(font.width(applyLabel), font.width(applyingText())) + 16);
        int cancelW = this.cancelW = Math.max(56, font.width(cancelLabel) + 14);
        int retryW = this.retryW = Math.max(56, font.width(retryLabel) + 22);
        this.applyButton = this.addRenderableWidget(new FlatButton(Position.of(innerX + innerW - applyW, this.footerY), applyW, FOOTER_H,
                applyLabel, FlatButton.Kind.PRIMARY, b -> this.apply()));
        this.addRenderableWidget(new FlatButton(Position.of(innerX + innerW - applyW - 5 - cancelW, this.footerY), cancelW, FOOTER_H,
                cancelLabel, b -> this.onClose()));
        this.retryButton = this.addRenderableWidget(new FlatButton(Position.of(innerX + innerW - applyW - 10 - cancelW - retryW, this.footerY),
                retryW, FOOTER_H, retryLabel, b -> this.state.reload()).withIcon(FlatButton.Icon.REFRESH));

        this.rowsKey = new Object();
        this.refreshRows();
        if (this.firstInit) {
            this.firstInit = false;
            this.list.highlightModel(this.draft.providerId(), this.draft.modelId());
        } else if (this.list.highlighted() == null) {
            this.list.highlightModel(this.draft.providerId(), this.draft.modelId());
        }
        this.refreshControls();
        this.setFocused(this.search);
    }

    private void layoutTabs() {
        var font = this.font;
        this.tabs.clear();
        int x = 0;
        var catalog = this.catalog();
        String all = L10n.tr("model.tab.all", "All");
        String favCount = Integer.toString(this.favoriteList.size());
        x = this.addTab(TAB_ALL, all, all, null, x, false);
        x = this.addTab(TAB_FAVORITES, favCount, "★ " + favCount, null, x, false);
        for (var p : catalog) {
            // Recognized brands show as icons; a name stays beside the icon when another instance shares it.
            var icon = ProviderIcon.resolve(p, p.id());
            boolean iconOnly = icon != null && ModelFormat.distinctIcon(catalog, p.id()) == icon;
            x = this.addTab(p.id(), iconOnly ? "" : Theme.ellipsize(font, p.label(), 110), p.label(), icon, x,
                    this.listsNoModels(p));
        }
        this.tabsWidth = x - 3;
        // A provider tab that disappeared falls back to All.
        if (!this.tab.equals(TAB_ALL) && !this.tab.equals(TAB_FAVORITES)
                && this.tabs.stream().noneMatch(t -> t.key().equals(this.tab)) && this.state.catalogLoaded()) {
            this.tab = TAB_ALL;
        }
        this.clampTabScroll(true);
    }

    private int addTab(String key, String label, String name, @Nullable ProviderIcon icon, int x, boolean dim) {
        boolean favTab = key.equals(TAB_FAVORITES);
        int iconW = favTab ? 7 : icon == null ? 0 : ProviderIcon.SIZE;
        int content = iconW + (iconW > 0 && !label.isEmpty() ? 4 : 0) + this.font.width(label);
        int w = content + 14;
        this.tabs.add(new Tab(key, label, name, icon, x, w, dim));
        return x + w + 3;
    }

    private void clampTabScroll(boolean revealActive) {
        int view = this.pw - PAD * 2;
        if (revealActive) {
            for (var t : this.tabs) {
                if (!t.key().equals(this.tab)) continue;
                if (t.x() < this.tabScroll) this.tabScroll = t.x();
                else if (t.x() + t.w() > this.tabScroll + view) this.tabScroll = t.x() + t.w() - view;
            }
        }
        this.tabScroll = Math.clamp(this.tabScroll, 0, Math.max(0, this.tabsWidth - view));
    }

    // ---------------------------------------------------------------- rows

    private void refreshRows() {
        var key = List.of(this.state.version(), this.query, this.tab, this.draft, this.favorites,
                Objects.requireNonNullElse(this.currentSelection(), ModelSelection.empty()));
        if (key.equals(this.rowsKey)) return;
        this.rowsKey = key;
        this.layoutTabs();
        this.list.setRows(this.buildRows());
    }

    private ModelList.Item item(ModelProvider provider, ModelChoice choice, boolean showProvider) {
        String label = ModelFormat.modelLabel(provider.driverKind(), choice.id(), choice.label());
        return new ModelList.Item(provider, choice.id(), label, choice, false, showProvider);
    }

    private List<ModelList.Row> buildRows() {
        var rows = new ArrayList<ModelList.Row>();
        var catalog = this.catalog();
        String q = this.query.strip();
        boolean typedOk = ModelFormat.isTypedModelId(q);

        if (!this.state.catalogLoaded()) {
            if (!this.state.loadError().isEmpty()) {
                rows.add(new ModelList.Note(L10n.tr("model.note.load_failed", "Could not load models: %s", this.state.loadError()), true));
                rows.add(new ModelList.Note(L10n.tr("model.note.retry", "Press Retry or Ctrl+R to try again."), false));
            } else {
                rows.add(new ModelList.Note(L10n.tr("model.note.loading", "Loading models…"), false));
            }
            return rows;
        }

        // Models the thread uses or the player chose that the catalog does not list.
        Map<String, List<ModelChoice>> extras = new LinkedHashMap<>();
        for (var sel : new ModelSelection[] {this.currentSelection(), this.draft}) {
            if (!ModelFormat.isSet(sel) || ModelFormat.provider(catalog, sel.providerId()) == null) continue;
            if (ModelFormat.model(catalog, sel.providerId(), sel.modelId()) != null) continue;
            var l = extras.computeIfAbsent(sel.providerId(), k -> new ArrayList<>());
            if (l.stream().noneMatch(c -> c.id().equals(sel.modelId()))) l.add(new ModelChoice(sel.modelId(), sel.modelId(), List.of()));
        }

        switch (this.tab) {
            case TAB_FAVORITES -> {
                for (var row : this.favoriteRows(catalog, q)) rows.add(row);
                if (rows.isEmpty()) {
                    rows.add(new ModelList.Note(q.isEmpty() ? L10n.tr("model.note.no_favorites", "No favorites yet. Click ☆ next to a model (or Ctrl+D).")
                            : L10n.tr("model.note.no_favorites_match", "No favorites match “%s”.", q), false));
                }
            }
            case TAB_ALL -> {
                if (q.isEmpty()) {
                    var favs = this.favoriteRows(catalog, q);
                    if (!favs.isEmpty()) {
                        rows.add(new ModelList.Header(L10n.tr("model.header.favorites", "★ Favorites"), ""));
                        rows.addAll(favs);
                    }
                }
                String typedProvider = ModelFormat.provider(catalog, this.draft.providerId()) != null ? this.draft.providerId() : "";
                for (var provider : catalog) {
                    var models = this.matching(provider, extras, q);
                    boolean empty = this.listsNoModels(provider);
                    boolean exact = models.stream().anyMatch(m -> m.id().equals(q));
                    boolean offerTyped = typedOk && !exact && (empty || provider.id().equals(typedProvider));
                    if (!q.isEmpty() && models.isEmpty() && !offerTyped) continue;
                    rows.add(new ModelList.Header(provider.label(), empty ? L10n.tr("model.header.no_models", "no models listed") : q.isEmpty() ? Integer.toString(models.size()) : ""));
                    for (var m : models) rows.add(this.item(provider, m, false));
                    if (offerTyped) rows.add(new ModelList.Item(provider, q, q, null, true, false));
                    else if (empty && models.isEmpty()) rows.add(new ModelList.Note(L10n.tr("model.note.type_id_for", "Type a model ID above to use %s.", provider.label()), false));
                }
                if (catalog.isEmpty()) rows.add(new ModelList.Note(L10n.tr("model.note.no_providers", "T3 Code reported no model providers."), false));
                else if (rows.isEmpty()) rows.add(new ModelList.Note(L10n.tr("model.note.no_match",
                        "No models match “%s”. Pick a provider tab to use it as a model ID.", q), false));
            }
            default -> {
                var provider = ModelFormat.provider(catalog, this.tab);
                if (provider == null) {
                    rows.add(new ModelList.Note(L10n.tr("model.note.provider_gone", "Provider is no longer available."), true));
                    break;
                }
                var models = this.matching(provider, extras, q);
                for (var m : models) rows.add(this.item(provider, m, false));
                boolean exact = models.stream().anyMatch(m -> m.id().equals(q));
                if (typedOk && !exact) rows.add(new ModelList.Item(provider, q, q, null, true, false));
                if (models.isEmpty() && q.isEmpty()) {
                    rows.add(new ModelList.Note(this.listsNoModels(provider)
                            ? L10n.tr("model.note.provider_empty", "%s lists no models. Type a model ID above.", provider.label())
                            : L10n.tr("model.note.no_models", "No models."), false));
                } else if (models.isEmpty() && !typedOk) {
                    rows.add(new ModelList.Note(L10n.tr("model.note.no_provider_match", "No %1$s models match “%2$s”.", provider.label(), q), false));
                }
            }
        }

        if (this.state.searching()) rows.add(new ModelList.Note(L10n.tr("model.note.searching", "Searching T3 Code…"), false));
        if (!this.state.searchError().isEmpty()) rows.add(new ModelList.Note(L10n.tr("model.note.search_failed", "Search failed: %s", this.state.searchError()), true));
        if (!this.state.loadError().isEmpty()) rows.add(new ModelList.Note(L10n.tr("model.note.refresh_failed", "Refresh failed: %s", this.state.loadError()), true));
        return rows;
    }

    private List<ModelChoice> matching(ModelProvider provider, Map<String, List<ModelChoice>> extras, String q) {
        var out = new ArrayList<ModelChoice>();
        for (var m : provider.models()) if (matches(q, provider, m)) out.add(m);
        for (var m : extras.getOrDefault(provider.id(), List.of())) {
            if (matches(q, provider, m)) out.add(m);
        }
        return out;
    }

    private static boolean matches(String query, ModelProvider provider, ModelChoice choice) {
        return ModelFormat.matches(query, provider.label(),
                ModelFormat.modelLabel(provider.driverKind(), choice.id(), choice.label()), choice.id());
    }

    private List<ModelList.Row> favoriteRows(List<ModelProvider> catalog, String q) {
        var rows = new ArrayList<ModelList.Row>();
        for (var fav : this.favoriteList) {
            var provider = ModelFormat.provider(catalog, fav.providerId());
            if (provider == null) continue; // provider not enabled right now
            var choice = ModelFormat.model(catalog, fav.providerId(), fav.modelId());
            String label = ModelFormat.modelLabel(provider.driverKind(), fav.modelId(), choice == null ? fav.modelId() : choice.label());
            if (!ModelFormat.matches(q, provider.label(), label, fav.modelId())) continue;
            rows.add(new ModelList.Item(provider, fav.modelId(), label, choice, false, true));
        }
        return rows;
    }

    // ---------------------------------------------------------------- actions

    private void onQueryChanged(String text) {
        if (text.equals(this.query)) return;
        this.query = text;
        this.queryChangedAt = Util.getMillis();
        this.refreshRows();
        this.list.highlightFirst();
    }

    private void pick(ModelList.Item item, boolean activate) {
        if (this.busy()) return;
        String pid = item.provider().id();
        String mid = item.modelId();
        var current = this.currentSelection();
        Map<String, Object> options;
        if (ModelFormat.sameModel(this.draft, pid, mid)) {
            options = this.draft.options();
        } else if (ModelFormat.sameModel(current, pid, mid)) {
            options = current.options();
        } else {
            // Carry over settings the new model also supports (e.g. keep High effort).
            options = ModelFormat.carryOver(this.draftChoice(), this.draft.options(), item.choice(),
                    ModelFormat.driverKind(this.catalog(), pid));
        }
        boolean modelChanged = !ModelFormat.sameModel(this.draft, pid, mid);
        this.draft = new ModelSelection(pid, mid, options);
        this.status = "";
        if (item.typed()) {
            // The typed ID is now the chosen model; clear the filter so it shows in its provider group.
            this.query = "";
            this.search.setText("");
            this.tab = pid;
        }
        if (activate) {
            this.apply();
        } else if (modelChanged) {
            this.rebuildKeepingScroll();
        } else {
            this.refreshControls();
        }
    }

    private void rebuildKeepingScroll() {
        double scroll = this.list.scroll();
        var highlighted = this.list.highlighted();
        this.rebuildWidgets();
        this.list.setScroll(scroll);
        if (highlighted != null && !highlighted.typed()) this.list.highlightModel(highlighted.provider().id(), highlighted.modelId());
        else this.list.highlightModel(this.draft.providerId(), this.draft.modelId());
    }

    private void setOption(String optionId, @Nullable Object value) {
        if (this.busy()) return;
        var options = new HashMap<>(this.draft.options());
        if (value == null) options.remove(optionId);
        else options.put(optionId, value);
        this.draft = new ModelSelection(this.draft.providerId(), this.draft.modelId(), options);
        this.status = "";
        this.refreshControls();
    }

    /** Drops every override so the provider's defaults apply. */
    private void resetOptions() {
        if (this.busy() || this.draft.options().isEmpty()) return;
        this.draft = new ModelSelection(this.draft.providerId(), this.draft.modelId(), Map.of());
        this.status = "";
        this.refreshControls();
    }

    private String draftDriver() {
        return ModelFormat.driverKind(this.catalog(), this.draft.providerId());
    }

    private void toggleFavorite(ModelList.Item item) {
        if (item.typed()) return;
        boolean ok;
        try {
            ok = this.controller.toggleFavoriteModel(item.provider().id(), item.modelId());
        } catch (RuntimeException e) {
            ok = false;
        }
        if (!ok) this.setStatus(L10n.tr("model.status.favorites_failed", "Could not update favorites."), true);
        this.reloadFavorites();
        this.refreshRows();
    }

    private void selectTab(String key) {
        if (key.equals(this.tab)) return;
        this.tab = key;
        for (var t : this.tabs) {
            if (t.key().equals(key) && t.icon() != null) this.minecraft.getNarrator().saySystemNow(Component.literal(t.name()));
        }
        this.clampTabScroll(true);
        this.refreshRows();
        this.list.highlightModel(this.draft.providerId(), this.draft.modelId());
    }

    private void cycleTab(int delta) {
        if (this.tabs.isEmpty()) return;
        int index = 0;
        for (int i = 0; i < this.tabs.size(); i++) if (this.tabs.get(i).key().equals(this.tab)) index = i;
        this.selectTab(this.tabs.get(Math.floorMod(index + delta, this.tabs.size())).key());
    }

    private void apply() {
        if (this.busy()) return;
        if (!this.connected()) {
            this.setStatus(L10n.tr("model.status.not_connected_thread", "Not connected to this thread."), true);
            return;
        }
        if (!ModelFormat.isSet(this.draft)) {
            this.setStatus(L10n.tr("model.status.choose_first", "Choose a model first."), true);
            return;
        }
        if (this.parent.isSending(this.threadId)) {
            this.setStatus(L10n.tr("model.status.wait_sending", "Wait for your message to finish sending."), true);
            return;
        }
        if (this.draft.equals(this.currentSelection())) {
            this.state.setDraft(this.threadId, null);
            this.state.clearFailure(this.threadId);
            this.close();
            return;
        }
        boolean started = this.state.apply(this.threadId, this.draft, ok -> {
            if (this.minecraft.gui.screen() != this) return;
            if (ok) {
                this.close();
            } else {
                var kept = this.state.draft(this.threadId);
                if (kept != null) this.draft = kept;
                this.setStatus(this.state.failure(this.threadId), true);
                this.rebuildKeepingScroll();
            }
        });
        if (started) this.setStatus(applyingText(), false);
        this.refreshControls();
    }

    private static String applyText() {
        return L10n.tr("model.apply", "Apply");
    }

    private static String applyingText() {
        return L10n.tr("model.applying", "Applying…");
    }

    private void setStatus(String text, boolean error) {
        this.status = text;
        this.statusError = error;
    }

    private void refreshControls() {
        boolean busy = this.busy();
        boolean mine = this.state.applying(this.threadId);
        this.applyButton.setActive(!busy && this.connected() && ModelFormat.isSet(this.draft));
        this.applyButton.setLabel(mine ? applyingText() : applyText());
        this.retryButton.setVisible(!this.state.loadError().isEmpty() && !this.state.loading());
        this.search.setActive(true);
        if (this.optionsPanel != null) {
            var choice = this.draftChoice();
            var current = this.currentSelection();
            boolean same = current != null && ModelFormat.sameModel(current, this.draft.providerId(), this.draft.modelId());
            this.optionsPanel.setData(choice == null ? List.of() : choice.options(), this.draftDriver(), this.draft.options(),
                    same ? current.options() : null, !busy);
        }
    }

    /** Leaves the picker. A rejected choice stays as the thread's draft; otherwise the pending choice is dropped. */
    private void close() {
        if (!this.state.applying(this.threadId) && this.state.failure(this.threadId).isEmpty()) {
            this.state.setDraft(this.threadId, null);
        } else if (!this.state.applying(this.threadId)) {
            this.state.setDraft(this.threadId, this.draft);
        }
        this.minecraft.gui.setScreen(this.parent);
    }

    @Override
    public void onClose() {
        this.close();
    }

    @Override
    public boolean isPauseScreen() {
        return this.parent.isPauseScreen();
    }

    // ---------------------------------------------------------------- tick & input

    @Override
    public void tick() {
        this.parent.tick();
        if (this.minecraft.gui.screen() != this) return;
        if (this.generation != this.controller.connectionGeneration()) {
            this.close(); // reconnected: this catalog and choice belong to the previous connection
            return;
        }
        if (this.themeAtInit != Theme.current()) {
            this.rebuildKeepingScroll();
            return;
        }
        if (this.thread() == null && this.controller.snapshot().connection().state() == ConnectionState.CONNECTED
                && !this.state.applying(this.threadId)) {
            // The thread went away (deleted or deselected): nothing to configure.
            this.close();
            return;
        }
        String q = this.query.strip();
        if (!q.equals(this.searchedQuery) && Util.getMillis() - this.queryChangedAt >= SEARCH_DEBOUNCE_MS) {
            this.searchedQuery = q;
            this.state.search(q);
        }
        this.state.describe(this.draft); // option metadata for a model the shortlist omitted
        var choice = this.draftChoice();
        boolean wantsOptions = choice != null && !choice.options().isEmpty();
        if (wantsOptions != this.laidOutWithOptions && this.state.catalogLoaded()) {
            this.rebuildKeepingScroll();
            return;
        }
        if (this.status.equals(applyingText()) && !this.busy()) this.status = "";
        this.refreshRows();
        this.refreshControls();
    }

    @Override
    public void resize(int width, int height) {
        double scroll = this.list == null ? 0 : this.list.scroll();
        super.resize(width, height);
        this.list.setScroll(scroll);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        int key = event.key();
        boolean ctrl = event.hasControlDown();
        if (event.isEscape()) {
            if (!this.query.isEmpty()) {
                this.search.setText("");
                return true;
            }
            this.onClose();
            return true;
        }
        if (key == InputConstants.KEY_UP) {
            this.list.moveHighlight(-1);
            return true;
        }
        if (key == InputConstants.KEY_DOWN) {
            this.list.moveHighlight(1);
            return true;
        }
        if (key == InputConstants.KEY_PAGEUP || key == InputConstants.KEY_PAGEDOWN) {
            this.list.page(key == InputConstants.KEY_PAGEUP ? -1 : 1);
            return true;
        }
        if (key == InputConstants.KEY_TAB) {
            this.cycleTab(event.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (key == InputConstants.KEY_RETURN || key == InputConstants.KEY_NUMPADENTER) {
            var item = this.list.highlighted();
            if (ctrl || item == null) this.apply();
            else this.pick(item, true);
            return true;
        }
        if (ctrl && key == InputConstants.KEY_D) {
            var item = this.list.highlighted();
            if (item != null) this.toggleFavorite(item);
            return true;
        }
        if (ctrl && key == InputConstants.KEY_R) {
            this.state.reload();
            return true;
        }
        if (this.getFocused() != this.search) this.setFocused(this.search);
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (this.getFocused() != this.search) this.setFocused(this.search);
        return super.charTyped(event);
    }

    private boolean insidePanel(double x, double y) {
        return x >= this.px && x < this.px + this.pw && y >= this.py && y < this.py + this.ph;
    }

    private boolean onTabs(double x, double y) {
        return y >= this.tabsY && y < this.tabsY + TAB_H && x >= this.px + PAD && x < this.px + this.pw - PAD;
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (!this.insidePanel(event.x(), event.y())) {
            this.onClose();
            return true;
        }
        if (event.button() == InputConstants.MOUSE_BUTTON_LEFT && this.onTabs(event.x(), event.y())) {
            int local = (int) event.x() - (this.px + PAD) + this.tabScroll;
            for (var t : this.tabs) {
                if (local >= t.x() && local < t.x() + t.w()) {
                    this.selectTab(t.key());
                    return true;
                }
            }
            return true;
        }
        super.mouseClicked(event, doubleClick);
        if (this.minecraft.gui.screen() == this && this.search != null) this.setFocused(this.search);
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.onTabs(mouseX, mouseY)) {
            this.tabScroll -= (int) ((scrollX != 0 ? -scrollX : scrollY) * 24);
            this.clampTabScroll(false);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        // The workspace stays visible (and live) behind the popover; it draws the only backdrop (blur, or the see-through
        // world/panorama when transparency is on) this frame.
        this.parent.extractBackground(graphics, -1, -1, delta);
        graphics.nextStratum();
        this.parent.extractRenderState(graphics, -1, -1, delta);
        graphics.nextStratum();
        graphics.fill(0, 0, this.width, this.height, Theme.isLight() ? 0x40000000 : 0x70000000);
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var font = this.font;
        int x = this.px, y = this.py, w = this.pw, h = this.ph;
        UiShapes.dropShadow(graphics, x, y, w, h, UiShapes.R_PANEL);
        UiShapes.panel(graphics, x, y, w, h, UiShapes.R_PANEL, Theme.PANEL_SURFACE, Theme.BORDER_STRONG, true);
        int innerX = x + PAD;
        int innerW = w - PAD * 2;
        var catalog = this.catalog();

        // Header: what the thread uses now.
        var current = this.currentSelection();
        String inUse;
        @Nullable ProviderIcon inUseIcon = null;
        String inUseFull = "";
        if (current == null) {
            inUse = L10n.tr("model.header.project_default", "Project default (applies until you choose a model)");
        } else {
            var model = ModelFormat.model(catalog, current.providerId(), current.modelId());
            String name = ModelFormat.modelLabel(catalog, current.providerId(), current.modelId());
            String opts = ModelFormat.optionsSummary(model, current.options());
            String tail = opts.isEmpty() ? "" : "  ·  " + opts;
            inUseFull = ModelFormat.providerLabel(catalog, current.providerId()) + " · " + name + tail;
            inUseIcon = ModelFormat.distinctIcon(catalog, current.providerId());
            inUse = inUseIcon == null ? inUseFull : name + tail;
        }
        if (this.twoLineHeader) {
            graphics.text(font, Component.literal(L10n.tr("model.header.title", "Model")).withStyle(s -> s.withBold(true)), innerX, this.titleY, Theme.TEXT, false);
            String keys = L10n.tr("model.header.keys", "↑↓ move · Enter use · Tab provider · Ctrl+D ★");
            int kw = font.width(keys);
            if (kw < innerW - 60) graphics.text(font, keys, innerX + innerW - kw, this.titleY, Theme.FAINT, false);
            this.drawInUse(graphics, L10n.tr("model.header.in_use", "In use: "), inUseIcon, inUse, inUseFull, innerX, this.summaryY, innerW, mouseX, mouseY);
        } else {
            this.drawInUse(graphics, L10n.tr("model.header.now", "Now: "), inUseIcon, inUse, inUseFull, innerX, this.summaryY, innerW, mouseX, mouseY);
        }

        // Provider tabs
        graphics.enableScissor(innerX, this.tabsY, innerX + innerW, this.tabsY + TAB_H);
        boolean tabsHot = this.onTabs(mouseX, mouseY);
        @Nullable Tab hoveredTab = null;
        for (var t : this.tabs) {
            int tx = innerX + t.x() - this.tabScroll;
            if (tx + t.w() < innerX || tx > innerX + innerW) continue;
            boolean active = t.key().equals(this.tab);
            boolean hot = tabsHot && mouseX >= tx && mouseX < tx + t.w();
            int bg = active ? Theme.SELECTED : hot ? Theme.BUTTON_HOVER : Theme.BUTTON;
            int border = active ? Theme.mix(Theme.BORDER, Theme.ACCENT, 0.55f) : hot ? Theme.BORDER_STRONG : Theme.BORDER_SUBTLE;
            UiShapes.panel(graphics, tx, this.tabsY, t.w(), TAB_H, UiShapes.R_CONTROL, bg, border, active || hot);
            if (active && t.w() > 8) {
                graphics.fill(tx + 3, this.tabsY + TAB_H - 2, tx + t.w() - 3, this.tabsY + TAB_H - 1, Theme.ACCENT);
            }
            boolean hasDraft = t.key().equals(this.draft.providerId());
            boolean favTab = t.key().equals(TAB_FAVORITES);
            int color = active ? Theme.TEXT : t.dim() ? Theme.FAINT : Theme.TEXT_SOFT;
            int cx = tx + 7;
            if (favTab) {
                UiIcons.star(graphics, cx, this.tabsY + (TAB_H - 7) / 2, Theme.AMBER, active || !this.favoriteList.isEmpty());
                cx += 11;
            } else if (t.icon() != null) {
                t.icon().draw(graphics, cx, this.tabsY + (TAB_H - ProviderIcon.SIZE) / 2, t.dim() && !active);
                cx += ProviderIcon.SIZE + 4;
            }
            if (!t.label().isEmpty()) {
                graphics.text(font, t.label(), cx, this.tabsY + (TAB_H - font.lineHeight) / 2 + 1, color, false);
            }
            if (hot) hoveredTab = t;
            if (hasDraft && !active) {
                graphics.fill(tx + t.w() / 2 - 2, this.tabsY + TAB_H - 2, tx + t.w() / 2 + 2, this.tabsY + TAB_H - 1, Theme.ACCENT);
            }
            if (hot) graphics.requestCursor(CursorTypes.POINTING_HAND);
        }
        graphics.disableScissor();
        // Name a tab on hover when its icon or a shortened label stands in for it.
        if (hoveredTab != null && !hoveredTab.label().equals(hoveredTab.name())) {
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(hoveredTab.name()), 200), mouseX, mouseY);
        }
        int view = innerW;
        if (this.tabScroll > 0) graphics.fill(innerX, this.tabsY + 2, innerX + 2, this.tabsY + TAB_H - 2, Theme.ACCENT);
        if (this.tabScroll < this.tabsWidth - view) graphics.fill(innerX + innerW - 2, this.tabsY + 2, innerX + innerW, this.tabsY + TAB_H - 2, Theme.ACCENT);

        // Divider above the settings of the chosen model and footer
        if (this.optionsPanel != null) graphics.fill(innerX, this.optionsTop - 3, innerX + innerW, this.optionsTop - 2, Theme.BORDER_SUBTLE);
        graphics.fill(x + 2, this.footerY - 6, x + w - 2, this.footerY - 5, Theme.BORDER_SUBTLE);

        // Footer status: the pending choice, progress or an error.
        String text;
        int color;
        int buttons = this.applyW + 5 + this.cancelW + (this.retryButton.isVisible() ? this.retryW + 5 : 0) + 8;
        if (!this.status.isEmpty()) {
            text = this.status;
            color = this.statusError ? Theme.RED : Theme.MUTED;
        } else if (!this.connected()) {
            text = L10n.tr("model.status.not_connected", "Not connected");
            color = Theme.RED;
        } else if (this.state.applying() && !this.state.applying(this.threadId)) {
            text = L10n.tr("model.status.other_change", "Another model change is in progress…");
            color = Theme.MUTED;
        } else if (!ModelFormat.isSet(this.draft)) {
            text = L10n.tr("model.status.pick", "Pick a model");
            color = Theme.FAINT;
        } else if (this.draft.equals(current)) {
            text = L10n.tr("model.status.no_changes", "No changes");
            color = Theme.FAINT;
        } else {
            var choice = this.draftChoice();
            String effort = ModelFormat.chipSummary(choice, this.draft.options());
            text = "→ " + ModelFormat.modelLabel(catalog, this.draft.providerId(), this.draft.modelId())
                    + (effort.isEmpty() ? "" : " · " + effort);
            if (choice == null) text = L10n.tr("model.status.custom_id", "%s (custom ID)", text);
            color = Theme.ACCENT;
        }
        int ty = this.footerY + (FOOTER_H - font.lineHeight) / 2 + 1;
        String shown = Theme.ellipsize(font, text, innerW - buttons);
        graphics.text(font, shown, innerX, ty, color, false);
        if (!shown.equals(text) && mouseY >= this.footerY && mouseY < this.footerY + FOOTER_H
                && mouseX >= innerX && mouseX < innerX + font.width(shown)) {
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(text), 240), mouseX, mouseY);
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);
    }

    /** {@code full} names the provider in words and is shown on hover when the icon or a shortened line hides it. */
    private void drawInUse(SpruceGuiGraphics graphics, String prefix, @Nullable ProviderIcon icon, String text, String full,
                           int x, int y, int width, int mouseX, int mouseY) {
        var font = this.font;
        int pw = font.width(prefix);
        graphics.text(font, prefix, x, y, Theme.FAINT, false);
        if (icon != null) {
            // Text sits one pixel below its line top; centre the icon on the glyphs.
            icon.draw(graphics, x + pw, y + (font.lineHeight - 1 - ProviderIcon.SIZE) / 2, false);
            pw += ProviderIcon.SIZE + 4;
        }
        String shown = Theme.ellipsize(font, text, width - pw);
        graphics.text(font, shown, x + pw, y, Theme.TEXT_SOFT, false);
        String hover = full.isEmpty() ? text : full;
        if (!shown.equals(hover) && mouseY >= y - 2 && mouseY < y + font.lineHeight + 2 && mouseX >= x
                && mouseX < x + Math.min(width, pw + font.width(shown))) {
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(hover), 260), mouseX, mouseY);
        }
    }
}
