package dev.t3mc.ui;

import dev.t3mc.L10n;
import dev.lambdaurora.spruceui.Position;
import dev.lambdaurora.spruceui.render.SpruceGuiGraphics;
import dev.lambdaurora.spruceui.screen.SpruceScreen;
import dev.t3mc.ClientController;
import dev.t3mc.ui.model.ConnectionState;
import dev.t3mc.ui.model.LocalImage;
import dev.t3mc.ui.model.ModelSelection;
import dev.t3mc.ui.model.PendingQuestion;
import dev.t3mc.ui.model.ProjectView;
import dev.t3mc.ui.model.ThreadDetail;
import dev.t3mc.ui.model.ThreadStatus;
import dev.t3mc.ui.model.WorkspaceSnapshot;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;

/**
 * T3 Code workspace: project/thread sidebar, transcript, pending questions and composer.
 * Polls {@link ClientController#snapshot()} every tick and re-renders when its version changes.
 */
public class WorkspaceScreen extends SpruceScreen {
    private static final int HEADER = 32;
    private static final int COMPOSER_H = 46;
    private static final int SEND_ROW = 16;
    private static final int GAP = 6;
    private static final int ATTACH_GAP = 3;

    private final ClientController controller;
    private final @Nullable Screen parent;
    private WorkspaceSnapshot snapshot;
    private String serverLabel = "T3 Code";
    private long renderedVersion = Long.MIN_VALUE;
    private String layoutKey = "";

    // State that survives widget rebuilds.
    private record Destination(String profile, String environment) {}
    private final Map<Destination, Map<String, String>> draftsByDestination = new HashMap<>();
    private Destination destination;
    private Map<String, String> composerDrafts;
    private final Map<String, String> answerDrafts = new HashMap<>();
    private final Map<String, Set<String>> multiSelections = new HashMap<>();
    private boolean settledExpanded;
    private int settledLimit = SidebarList.SETTLED_PAGE;
    /** Project picker for "+ New thread" is open in place of the thread list. */
    private boolean pickingProject;
    /** Request ids answered recently, to swallow double clicks while the answer is in flight. */
    private final Map<String, Long> answeredAt = new HashMap<>();
    private double sidebarScroll;
    private double transcriptScroll;
    private boolean transcriptAtBottom = true;
    private @Nullable String displayedThreadId;
    private boolean composerHadFocus = true;
    private boolean answerHadFocus;
    /** In-flight send: composer stays read-only until the server acknowledges it. */
    private @Nullable PendingSend pendingSend;
    private long sendFailedAt;
    /** Catalog cache, per-thread model drafts and the in-flight model change. */
    private final ModelPickerState models;
    /** Set while the model picker is open so focus returns to the composer afterwards. */
    private boolean returningFromPicker;
    private int renderedModelsVersion = -1;

    /** Images staged per thread; kept until the exact images are sent or removed. */
    private final Map<String, List<LocalImage>> imageDrafts = new HashMap<>();
    private final ImagePreviews previews = new ImagePreviews();
    /** Thumbnails of images in the transcript; closed when the screen is removed, recreated on the next init. */
    private @Nullable ChatImagePreviews chatPreviews;
    /** Which transcript turns are expanded; survives widget rebuilds, reset when the thread changes. */
    private final TranscriptView.State transcriptState = new TranscriptView.State();
    /** Non-null while the native file dialog is open; its result only applies to this thread and connection. */
    private @Nullable AttachRequest choosing;
    private boolean imagesSupported;
    /** Connection generation the image drafts belong to; drafts never carry over to another environment. */
    private long imageGeneration;
    /** Threads with dropped/chosen files still being validated; sending waits so a caption can't go alone. */
    private final Map<String, Integer> validating = new HashMap<>();
    /** The strip appeared/disappeared; re-layout on the next tick (never in the middle of an input event). */
    private boolean attachLayoutDirty;
    private String attachNotice = "";
    private boolean attachNoticeError;
    private long attachNoticeAt;
    /** Human-readable reason the last send failed (from T3 Code); shown until the next send or thread switch. */
    private @Nullable String sendError;
    private @Nullable String sendErrorThread;

    private record PendingSend(String threadId, String text, List<LocalImage> images, long generation, Destination destination) {}

    private record AttachRequest(String threadId, long generation) {}

    // Widgets (recreated by init()).
    private int sidebarWidth;
    private SidebarList sidebar;
    private ProjectPicker picker;
    private TranscriptView transcript;
    private @Nullable QuestionPanel questionPanel;
    private @Nullable InputField answerField;
    private @Nullable FlatButton replyButton;
    private ComposerArea composer;
    private FlatButton newThreadButton;
    private FlatButton sendButton;
    private ModelChip modelButton;
    private FlatButton attachButton;
    private @Nullable AttachmentStrip attachmentStrip;
    private FlatButton stopButton;
    private FlatButton connectButton;

    public WorkspaceScreen(ClientController controller) {
        this(controller, null);
    }

    /** Opens the workspace; Escape returns to {@code parent} (for example an in-game menu) when one is given. */
    public WorkspaceScreen(ClientController controller, @Nullable Screen parent) {
        super(Component.literal("T3 Code"));
        this.parent = parent;
        this.controller = Objects.requireNonNull(controller, "controller");
        this.snapshot = controller.snapshot();
        this.models = new ModelPickerState(controller);
        var servers = this.snapshot.settings().connections();
        this.imageGeneration = servers.generation();
        this.destination = new Destination(servers.activeProfileId(), servers.environmentId());
        this.composerDrafts = this.draftsByDestination.computeIfAbsent(this.destination, ignored -> new HashMap<>());
        Theme.sync(this.snapshot.settings());
        Theme.setTransparency(this.snapshot.settings().transparency());
    }

    // ---------------------------------------------------------------- layout

    @Override
    protected void init() {
        this.snapshot = this.controller.snapshot();
        this.syncImageGeneration(this.snapshot);
        var snap = this.snapshot;
        var detail = snap.selectedThread();
        this.sidebarWidth = Math.clamp(this.width * 26 / 100, 130, 200);
        int sw = this.sidebarWidth;
        int mainX = sw + 1;
        int mainW = this.width - mainX;
        int pad = 12;

        // Sidebar
        Theme.sync(snap.settings());
        Theme.setTransparency(snap.settings().transparency());
        this.newThreadButton = this.addRenderableWidget(new FlatButton(Position.of(8, 34), sw - 16, 18, L10n.tr("chat.thread.new", "+  New thread"),
                FlatButton.Kind.PRIMARY, b -> this.togglePicker()));
        int listH = this.height - 58 - 30;
        this.sidebar = this.addRenderableWidget(new SidebarList(Position.of(0, 58), sw, listH, new SidebarList.Actions() {
            @Override
            public void selectThread(String threadId) {
                controller.onConnection(snapshot.settings().connections().generation(), () -> controller.selectThread(threadId));
            }

            @Override
            public void toggleSettled() {
                WorkspaceScreen.this.settledExpanded = !WorkspaceScreen.this.settledExpanded;
                if (!WorkspaceScreen.this.settledExpanded) WorkspaceScreen.this.settledLimit = SidebarList.SETTLED_PAGE;
                WorkspaceScreen.this.refreshSidebar();
            }

            @Override
            public void showMoreSettled() {
                WorkspaceScreen.this.settledLimit += SidebarList.SETTLED_PAGE;
                WorkspaceScreen.this.refreshSidebar();
            }
        }));
        this.sidebar.setScroll(this.sidebarScroll);
        this.picker = this.addRenderableWidget(new ProjectPicker(Position.of(0, 58), sw, listH, this::newThread));
        String serversLabel = L10n.tr("chat.workspace.servers", "Servers");
        String settingsLabel = L10n.tr("chat.workspace.settings", "Settings");
        // Split the footer by label width so longer translations stay readable in the narrowest sidebar.
        int serversText = this.font.width(serversLabel) + 8, settingsText = this.font.width(settingsLabel) + 8;
        int footerWidth = Math.clamp((long) (sw - 20) * serversText / (serversText + settingsText), 30, sw - 50);
        this.addRenderableWidget(new FlatButton(Position.of(8, this.height - 24), footerWidth, 18, serversLabel,
                b -> this.minecraft.gui.setScreen(new ConnectionsScreen(this, this.controller))));
        this.addRenderableWidget(new FlatButton(Position.of(12 + footerWidth, this.height - 24), sw - 20 - footerWidth, 18, settingsLabel,
                b -> this.minecraft.gui.setScreen(new SettingsScreen(this, this.controller))));

        // Header buttons (visibility toggled in refresh)
        String stopLabel = L10n.tr("chat.workspace.stop", "Stop");
        int stopW = Math.max(50, this.font.width(stopLabel) + 10);
        this.stopButton = this.addRenderableWidget(new FlatButton(Position.of(this.width - pad - stopW, 8), stopW, 16, stopLabel,
                FlatButton.Kind.DANGER, b -> {
            var current = this.snapshot.selectedThread();
            if (current != null) this.controller.onConnection(snapshot.settings().connections().generation(),
                    () -> controller.interruptThread(current.id()));
        }));
        int connectW = Math.max(64, Math.max(this.font.width(reconnectLabel()), this.font.width(connectLabel())) + 10);
        this.connectButton = this.addRenderableWidget(new FlatButton(Position.of(this.width - pad - connectW, 8), connectW, 16,
                reconnectLabel(),
                b -> {
                    // A saved T3 URL reconnects (reusing its login or signing in again); without one, open Settings.
                    if (!this.controller.snapshot().settings().httpUrl().isBlank()) this.controller.reconnect();
                    else this.minecraft.gui.setScreen(new SettingsScreen(this, this.controller));
                }));

        // Composer
        int sendRowY = this.height - 8 - SEND_ROW;
        String threadId = detail == null ? null : detail.id();
        boolean hasImages = !this.images(threadId).isEmpty();
        int stripY = sendRowY - 4 - AttachmentStrip.HEIGHT;
        int composerY = (hasImages ? stripY - ATTACH_GAP : sendRowY - 4) - COMPOSER_H;
        this.composer = this.addRenderableWidget(new ComposerArea(Position.of(mainX + pad, composerY), mainW - pad * 2, COMPOSER_H,
                L10n.tr("chat.composer.placeholder", "Ask anything…"), this::send));
        this.composer.setText(threadId == null ? "" : this.composerDrafts.getOrDefault(threadId, ""));
        this.composer.setCursorToEnd();
        this.attachmentStrip = null;
        if (hasImages) {
            this.attachmentStrip = this.addRenderableWidget(new AttachmentStrip(Position.of(mainX + pad, stripY), mainW - pad * 2,
                    this.previews, this::removeImage));
        }
        int sendW = Math.max(60, Math.max(this.font.width(sendLabel()), this.font.width(sendingLabel())) + 10);
        this.sendButton = this.addRenderableWidget(new FlatButton(Position.of(this.width - pad - sendW, sendRowY), sendW, SEND_ROW,
                sendLabel(), FlatButton.Kind.PRIMARY, b -> this.send()));
        boolean roomy = mainW - pad * 2 >= 240;
        String attachLabel = roomy ? L10n.tr("chat.composer.attach", "+ Image") : L10n.tr("chat.composer.attach_short", "Image");
        int attachW = Math.max(roomy ? 48 : 36, this.font.width(attachLabel) + 10);
        this.attachButton = this.addRenderableWidget(new FlatButton(Position.of(this.width - pad - sendW - 4 - attachW, sendRowY),
                attachW, SEND_ROW, attachLabel, b -> this.chooseImages()));
        int chipMax = Math.max(40, Math.min(240, (mainW - pad * 2 - 64) * 3 / 5));
        this.modelButton = this.addRenderableWidget(new ModelChip(Position.of(mainX + pad, sendRowY), chipMax, SEND_ROW,
                b -> this.openModelPicker()));

        // Pending question
        int transcriptBottom = composerY - GAP;
        this.questionPanel = null;
        this.answerField = null;
        this.replyButton = null;
        var pending = detail == null ? List.<PendingQuestion>of() : detail.pendingQuestions();
        if (!pending.isEmpty()) {
            var question = pending.getFirst();
            var selection = this.multiSelections.computeIfAbsent(question.requestId(), k -> new LinkedHashSet<>());
            // Leave the transcript at least a few lines; the card scrolls internally when it is taller.
            int maxHeight = Math.max(70, (composerY - HEADER - GAP) * 3 / 5);
            var panel = new QuestionPanel(Position.of(mainX + pad, 0), mainW - pad * 2, maxHeight,
                    question, 0, pending.size(), selection, this::answer);
            int panelY = composerY - GAP - panel.getHeight();
            panel.getPosition().setRelativeY(panelY);
            this.questionPanel = this.addRenderableWidget(panel);
            transcriptBottom = panelY - GAP;

            if (question.allowFreeText()) {
                String replyLabel = L10n.tr("chat.question.reply", "Reply");
                int replyW = Math.max(54, this.font.width(replyLabel) + 10);
                var field = new InputField(Position.of(panel.innerX(), panel.freeTextY()), panel.innerWidth() - replyW - 4,
                        L10n.tr("chat.question.answer", "Answer"), question.multiSelect()
                        ? L10n.tr("chat.question.other_placeholder", "Other (optional)…")
                        : L10n.tr("chat.question.answer_placeholder", "Type an answer…"));
                field.setText(this.answerDrafts.getOrDefault(question.requestId(), ""));
                field.setChangedListener(text -> this.answerDrafts.put(question.requestId(), text));
                field.onEnter(() -> this.submitFreeText(question));
                this.answerField = this.addRenderableWidget(field);
                this.replyButton = this.addRenderableWidget(new FlatButton(Position.of(panel.innerX() + panel.innerWidth() - replyW, panel.freeTextY()),
                        replyW, 18, replyLabel, FlatButton.Kind.PRIMARY, b -> this.submitFreeText(question)));
            }
        }

        // Transcript
        if (this.chatPreviews == null) this.chatPreviews = new ChatImagePreviews();
        this.transcript = this.addRenderableWidget(new TranscriptView(Position.of(mainX, HEADER + 1), mainW,
                Math.max(20, transcriptBottom - HEADER - 1), this.chatPreviews, this.transcriptState));

        boolean sameThread = Objects.equals(threadId, this.displayedThreadId);
        this.displayedThreadId = threadId;
        this.layoutKey = this.layoutKey(snap);
        this.attachLayoutDirty = false;
        this.refresh(snap, !sameThread);
        if (sameThread && !this.transcriptAtBottom) this.transcript.setScroll(this.transcriptScroll);

        boolean fromPicker = this.returningFromPicker && this.minecraft.gui.screen() == this;
        if (fromPicker) this.returningFromPicker = false;
        if (this.answerField != null && this.answerHadFocus) this.setFocused(this.answerField);
        else if (this.composer.isActive() && (this.composerHadFocus || fromPicker)) this.setFocused(this.composer);
    }

    /** Things that need widgets to be recreated when they change. */
    private String layoutKey(WorkspaceSnapshot snap) {
        var detail = snap.selectedThread();
        if (detail == null) return "none";
        var pending = detail.pendingQuestions();
        return detail.id() + "|" + (pending.isEmpty() ? "" : pending.getFirst().requestId() + "#" + pending.size()
                + "#" + pending.getFirst().hashCode()) + "|" + (this.images(detail.id()).isEmpty() ? "" : "img");
    }

    private void saveWidgetState() {
        if (this.composer == null) return;
        if (this.displayedThreadId != null) {
            String text = this.composer.getText();
            if (text.isEmpty()) this.composerDrafts.remove(this.displayedThreadId);
            else this.composerDrafts.put(this.displayedThreadId, text);
        }
        this.composerHadFocus = this.getFocused() == this.composer;
        this.answerHadFocus = this.answerField != null && this.getFocused() == this.answerField;
        this.sidebarScroll = this.sidebar.scroll();
        this.transcriptScroll = this.transcript.scroll();
        this.transcriptAtBottom = this.transcript.atBottom();
    }

    @Override
    public void resize(int width, int height) {
        this.saveWidgetState();
        super.resize(width, height);
    }

    // ---------------------------------------------------------------- state sync

    @Override
    public void tick() {
        super.tick();
        var next = this.controller.snapshot();
        this.syncImageGeneration(next);
        Theme.setTransparency(next.settings().transparency()); // colors are read per frame; no rebuild needed
        if (Theme.sync(next.settings())) {
            this.saveWidgetState();
            this.snapshot = next;
            this.rebuildWidgets();
            return;
        }
        if (this.attachLayoutDirty) {
            this.saveWidgetState();
            this.snapshot = next;
            this.rebuildWidgets();
            return;
        }
        if (next.version() == this.renderedVersion) {
            // The catalog can resolve without a snapshot change; relabel the model chip when it does.
            if (this.composer != null && (this.models.version() != this.renderedModelsVersion
                    || this.models.generation() != this.controller.connectionGeneration())) {
                this.refresh(next, false);
            }
            return;
        }
        if (!this.layoutKey(next).equals(this.layoutKey)) {
            this.saveWidgetState();
            this.snapshot = next;
            this.rebuildWidgets();
        } else {
            this.snapshot = next;
            this.refresh(next, false);
        }
    }

    /** Pushes snapshot data into the existing widgets. */
    private void refresh(WorkspaceSnapshot snap, boolean forceBottom) {
        this.serverLabel = ConnectionsScreen.activeName(snap.settings().connections());
        this.renderedVersion = snap.version();
        boolean connected = snap.connection().state() == ConnectionState.CONNECTED;
        var detail = snap.selectedThread();

        boolean writable = connected && !snap.settings().connections().readOnly();
        if (!writable || snap.projects().isEmpty()) this.pickingProject = false;
        this.refreshSidebar();
        this.newThreadButton.setActive(writable && !snap.projects().isEmpty());

        if (!connected) {
            String message = snap.connection().message();
            switch (snap.connection().state()) {
                case CONNECTING -> this.transcript.setEmpty(L10n.tr("chat.empty.connecting", "Connecting to T3 Code…"), message);
                case ERROR -> this.transcript.setEmpty(L10n.tr("chat.empty.connect_failed", "Could not connect to T3 Code"), message,
                        L10n.tr("chat.empty.connect_failed_hint", "Check Settings, then press Reconnect."));
                default -> this.transcript.setEmpty(L10n.tr("chat.empty.not_connected", "Not connected"),
                        L10n.tr("chat.empty.not_connected_hint", "Open Settings to connect to T3 Code."));
            }
        } else if (detail == null) {
            String select = L10n.tr("chat.empty.select_thread", "Select a thread");
            if (writable) this.transcript.setEmpty(select, L10n.tr("chat.empty.select_thread_hint", "or start one in any project with “+ New thread”."));
            else this.transcript.setEmpty(select, L10n.tr("chat.empty.read_only", "This environment has read-only access."));
        } else {
            this.transcript.setConversation(detail.id(), detail.messages(), detail.status() == ThreadStatus.RUNNING, forceBottom);
        }

        boolean canType = writable && detail != null;
        if (this.questionPanel != null) this.questionPanel.setReadOnly(!writable);
        if (this.answerField != null) this.answerField.setActive(writable);
        if (this.replyButton != null) this.replyButton.setActive(writable);
        boolean sending = this.pendingSend != null && detail != null && this.pendingSend.threadId().equals(detail.id());
        boolean configuring = detail != null && this.models.applying(detail.id());
        this.composer.setActive(canType && !sending);
        boolean attaching = detail != null && this.attaching(detail.id());
        this.sendButton.setActive(canType && !sending && !configuring && !attaching);
        this.sendButton.setLabel(sending ? sendingLabel() : sendLabel());
        this.imagesSupported = connected && this.controller.supportsImageAttachments();
        this.refreshAttachments(canType && !sending, detail != null && !sending);
        this.refreshModelChip(snap, canType && !sending);
        boolean running = detail != null && detail.status() == ThreadStatus.RUNNING;
        this.stopButton.setVisible(writable && running);
        var state = snap.connection().state();
        this.connectButton.setVisible(state == ConnectionState.ERROR || state == ConnectionState.DISCONNECTED);
        this.connectButton.setLabel(state == ConnectionState.ERROR ? reconnectLabel() : connectLabel());

        // Forget drafts/selections for questions that are gone.
        var live = new HashSet<String>();
        if (detail != null) for (var q : detail.pendingQuestions()) live.add(q.requestId());
        if (detail != null) {
            this.answerDrafts.keySet().retainAll(live);
            this.multiSelections.keySet().retainAll(live);
            this.answeredAt.keySet().retainAll(live);
        }
        var threads = new HashSet<String>();
        for (var project : snap.projects()) for (var thread : project.threads()) threads.add(thread.id());
        if (detail != null) threads.add(detail.id());
        if (connected) this.models.retainThreads(threads);
    }

    private static String sendLabel() { return L10n.tr("chat.composer.send", "Send"); }

    private static String sendingLabel() { return L10n.tr("chat.composer.sending", "Sending…"); }

    private static String reconnectLabel() { return L10n.tr("chat.workspace.reconnect", "Reconnect"); }

    private static String connectLabel() { return L10n.tr("chat.workspace.connect", "Connect"); }

    /** Label for the composer's model control. Reads cached data only; the catalog loads once per connection. */
    private void refreshModelChip(WorkspaceSnapshot snap, boolean enabled) {
        var detail = snap.selectedThread();
        boolean connected = snap.connection().state() == ConnectionState.CONNECTED;
        // Never offer a previous environment's catalog or drafts after reconnecting.
        this.models.syncGeneration(snap.settings().connections().generation());
        if (connected && !this.models.catalogLoaded() && !this.models.loading() && this.models.loadError().isEmpty()) {
            this.models.reload(); // for provider/model names on the chip
        }
        var catalog = this.models.known();
        String label;
        String tooltip;
        String spoken = "";
        @Nullable ProviderIcon icon = null;
        boolean warn = false;
        if (detail == null) {
            label = L10n.tr("chat.model.chip", "Model");
            tooltip = L10n.tr("chat.model.select_thread", "Select a thread to choose its model.");
        } else if (this.models.applying(detail.id()) && this.models.applyingSelection() != null) {
            var pending = this.models.applyingSelection();
            label = L10n.tr("chat.model.switching", "Switching to %s…",
                    ModelFormat.modelLabel(catalog, pending.providerId(), pending.modelId()));
            tooltip = L10n.tr("chat.model.switching_tooltip", "Waiting for T3 Code to accept the model change.");
        } else if (ModelFormat.isSet(detail.modelSelection())) {
            ModelSelection sel = detail.modelSelection();
            var model = ModelFormat.model(catalog, sel.providerId(), sel.modelId());
            String provider = ModelFormat.providerLabel(catalog, sel.providerId());
            String name = ModelFormat.modelLabel(catalog, sel.providerId(), sel.modelId());
            this.models.describe(sel); // option metadata when the shortlist omitted this model
            String effort = ModelFormat.chipSummary(model, sel.options());
            spoken = provider + " · " + name + (effort.isEmpty() ? "" : " · " + effort);
            // A recognized provider shows as its icon; its name stays when another instance shares the icon.
            icon = ProviderIcon.resolve(ModelFormat.provider(catalog, sel.providerId()), sel.providerId());
            label = icon != null && ModelFormat.distinctIcon(catalog, sel.providerId()) == icon
                    ? name + (effort.isEmpty() ? "" : " · " + effort) : spoken;
            String opts = ModelFormat.optionsDetail(model, sel.options());
            tooltip = provider + " · " + name + (opts.isEmpty() ? "" : "\n" + opts) + "\n" + L10n.tr("chat.model.change_hint", "Click or Ctrl+M to change.");
        } else {
            label = L10n.tr("chat.model.default", "Default model");
            tooltip = L10n.tr("chat.model.default_tooltip",
                    "This thread uses the project's default model until you choose one. Click or Ctrl+M to choose.");
        }
        if (detail != null && !this.models.failure(detail.id()).isEmpty() && !this.models.applying(detail.id())) {
            warn = this.models.recentlyFailed(detail.id());
            tooltip += "\n" + L10n.tr("chat.model.last_failed", "Last change failed: %s", this.models.failure(detail.id()));
        }
        this.modelButton.set(icon, label, spoken, tooltip, warn);
        this.modelButton.setActive(enabled && !this.models.applying(detail == null ? null : detail.id()));
        this.renderedModelsVersion = this.models.version();
    }

    private void openModelPicker() {
        var detail = this.snapshot.selectedThread();
        if (detail == null || !this.modelButton.isActive()
                || this.snapshot.connection().state() != ConnectionState.CONNECTED) return;
        this.returningFromPicker = true;
        this.minecraft.gui.setScreen(new ModelPickerScreen(this, this.controller, this.models, detail.id()));
    }

    int[] modelAnchor() {
        return new int[] {this.modelButton.getX(), this.composer.getY()};
    }

    boolean isSending(String threadId) {
        return this.pendingSend != null && this.pendingSend.threadId().equals(threadId);
    }

    /** Keeps this screen laid out for the picker drawn over it. */
    void resizeBehind(int width, int height) {
        if (this.width != width || this.height != height) this.resize(width, height);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (event.hasControlDown() && event.key() == com.mojang.blaze3d.platform.InputConstants.KEY_M && !event.hasShiftDown()) {
            this.openModelPicker();
            return true;
        }
        return super.keyPressed(event);
    }

    private void refreshSidebar() {
        var snap = this.snapshot;
        String empty = switch (snap.connection().state()) {
            case CONNECTED -> snap.projects().isEmpty() ? L10n.tr("chat.sidebar.no_projects", "No projects yet. Add one in T3 Code.")
                    : L10n.tr("chat.sidebar.no_threads", "No threads yet. Start one with “+ New thread”.");
            case CONNECTING -> L10n.tr("chat.sidebar.loading", "Loading threads…");
            default -> L10n.tr("chat.sidebar.disconnected", "Connect to see your threads.");
        };
        this.sidebar.setData(snap.projects(), snap.selectedThreadId(), this.settledExpanded, this.settledLimit, empty);
        var suggested = this.defaultProject(null);
        this.picker.setProjects(snap.projects(), suggested == null ? null : suggested.id());
        this.sidebar.setVisible(!this.pickingProject);
        this.picker.setVisible(this.pickingProject);
        this.newThreadButton.setKind(this.pickingProject ? FlatButton.Kind.NORMAL : FlatButton.Kind.PRIMARY);
        this.newThreadButton.setLabel(this.pickingProject ? L10n.tr("chat.picker.cancel", "Cancel") : L10n.tr("chat.thread.new", "+  New thread"));
    }

    private void togglePicker() {
        this.pickingProject = !this.pickingProject && this.snapshot.connection().state() == ConnectionState.CONNECTED;
        if (this.pickingProject) this.picker.setScroll(0);
        this.refreshSidebar();
    }

    // ---------------------------------------------------------------- actions

    private @Nullable ProjectView defaultProject(@Nullable String preferred) {
        var snap = this.snapshot;
        if (preferred != null) {
            var project = snap.project(preferred);
            if (project != null) return project;
        }
        var detail = snap.selectedThread();
        if (detail != null) {
            var project = snap.project(detail.projectId());
            if (project != null) return project;
        }
        return snap.projects().isEmpty() ? null : snap.projects().getFirst();
    }

    /** Creates a thread in the explicitly picked project. */
    private void newThread(String projectId) {
        this.pickingProject = false;
        this.refreshSidebar();
        if (this.snapshot.connection().state() != ConnectionState.CONNECTED) return;
        var project = this.snapshot.project(projectId);
        if (project == null) return;
        this.sidebar.setScroll(0);
        this.composerHadFocus = true;
        this.controller.onConnection(this.snapshot.settings().connections().generation(), () -> this.controller.createThread(project.id()));
    }

    @Override
    public void onClose() {
        if (this.pickingProject) { // Esc closes the project picker first
            this.pickingProject = false;
            this.refreshSidebar();
            return;
        }
        if (this.parent != null) {
            this.minecraft.gui.setScreen(this.parent);
            return;
        }
        super.onClose();
    }

    private void send() {
        ThreadDetail detail = this.snapshot.selectedThread();
        if (detail == null || this.pendingSend != null || this.snapshot.connection().state() != ConnectionState.CONNECTED
                || this.snapshot.settings().connections().readOnly()) return;
        if (this.models.applying(detail.id())) return; // the model change must land before the next turn
        if (this.attaching(detail.id())) { // the images must be staged before Enter can send the caption
            this.notice(L10n.tr("chat.attach.wait_attaching", "Wait for the images to finish attaching."), true);
            return;
        }
        String raw = this.composer.getText();
        String text = raw.strip();
        List<LocalImage> images = List.copyOf(this.images(detail.id()));
        if (text.isEmpty() && images.isEmpty()) return;

        long generation = this.snapshot.settings().connections().generation();
        if (generation != this.imageGeneration) return;
        var pending = new PendingSend(detail.id(), raw, images, generation, this.destination);
        this.pendingSend = pending;
        this.sendFailedAt = 0;
        this.sendError = null;
        this.sendErrorThread = null;
        this.composerHadFocus = true;
        this.refresh(this.snapshot, false);
        this.transcript.scrollToBottom();

        CompletionStage<Boolean> result;
        try {
            result = this.controller.changeOnConnection(pending.generation(), () -> this.controller.sendMessage(detail.id(), text, images));
        } catch (RuntimeException e) {
            result = CompletableFuture.failedFuture(e);
        }
        // Never retried automatically: a failure only restores the composer so the player can decide.
        result.whenComplete((ok, error) -> this.minecraft.execute(
                () -> this.onSendFinished(pending, error == null && Boolean.TRUE.equals(ok), error)));
    }

    private void onSendFinished(PendingSend pending, boolean ok, @Nullable Throwable error) {
        if (this.pendingSend != pending || pending.generation() != this.controller.connectionGeneration()) {
            if (ok) {
                var originDrafts = this.draftsByDestination.get(pending.destination());
                if (originDrafts != null) originDrafts.remove(pending.threadId(), pending.text());
                if (this.pendingSend == pending && this.destination.equals(pending.destination())
                        && pending.threadId().equals(this.displayedThreadId) && this.composer != null
                        && pending.text().equals(this.composer.getText())) this.composer.setText("");
            }
            return;
        }
        this.pendingSend = null;
        boolean showing = pending.threadId().equals(this.displayedThreadId) && this.composer != null;
        if (ok) {
            // Only clear text that is still exactly what was sent; keep anything newer.
            if (showing && this.composer.getText().equals(pending.text())) {
                this.composer.setText("");
                this.composer.setCursorToStart();
            }
            this.composerDrafts.remove(pending.threadId(), pending.text());
            // Remove exactly the image instances that were sent; anything staged meanwhile stays.
            var staged = this.imageDrafts.get(pending.threadId());
            if (staged != null && !pending.images().isEmpty()) {
                staged.removeIf(image -> pending.images().stream().anyMatch(sent -> sent == image));
                this.imagesChanged(pending.threadId());
            }
        } else if (pending.generation() == this.imageGeneration) {
            this.sendFailedAt = Util.getMillis();
            this.sendError = errorMessage(error, pending.images().isEmpty() ? null
                    : L10n.tr("chat.send.images_uncertain", "The images may not have been delivered. Check the thread in T3 Code before sending again."));
            this.sendErrorThread = pending.threadId();
            if (!showing) this.composerDrafts.putIfAbsent(pending.threadId(), pending.text());
        }
        if (this.composer != null) {
            this.refresh(this.snapshot, false);
            if (showing && this.minecraft.gui.screen() == this) this.setFocused(this.composer);
        }
    }

    private void answer(PendingQuestion question, List<String> answers) {
        var detail = this.snapshot.selectedThread();
        if (detail == null || answers.isEmpty() || this.snapshot.settings().connections().readOnly()) return;
        long now = Util.getMillis();
        Long previous = this.answeredAt.get(question.requestId());
        if (previous != null && now - previous < 4000) return;
        this.answeredAt.put(question.requestId(), now);
        this.controller.onConnection(this.snapshot.settings().connections().generation(),
                () -> this.controller.answerQuestion(detail.id(), question.requestId(), List.copyOf(answers)));
        this.answerDrafts.remove(question.requestId());
        this.multiSelections.remove(question.requestId());
        this.composerHadFocus = true;
        this.answerHadFocus = false;
    }

    private void submitFreeText(PendingQuestion question) {
        if (this.answerField == null) return;
        String text = this.answerField.getText().strip();
        List<String> answers = new ArrayList<>();
        if (question.multiSelect()) {
            var selected = this.multiSelections.getOrDefault(question.requestId(), Set.of());
            question.options().stream().filter(selected::contains).forEach(answers::add);
        }
        if (!text.isEmpty()) answers.add(text);
        if (answers.isEmpty()) return;
        this.answer(question, answers);
    }

    @Override
    public void removed() {
        this.saveWidgetState();
        this.previews.releaseTextures();
        if (this.chatPreviews != null) {
            this.chatPreviews.close();
            this.chatPreviews = null;
        }
        super.removed();
    }

    // ---------------------------------------------------------------- image attachments

    private List<LocalImage> images(@Nullable String threadId) {
        if (threadId == null) return List.of();
        return this.imageDrafts.getOrDefault(threadId, List.of());
    }

    /** Why new images can't be staged right now for the displayed thread, or null if they can. */
    private @Nullable String attachBlocker() {
        var detail = this.snapshot.selectedThread();
        if (this.snapshot.connection().state() != ConnectionState.CONNECTED) return L10n.tr("chat.attach.blocked_disconnected", "Connect to T3 Code to attach images.");
        if (detail == null) return L10n.tr("chat.attach.blocked_no_thread", "Select a thread to attach images.");
        if (this.snapshot.settings().connections().readOnly()) return L10n.tr("chat.attach.blocked_read_only", "This linked environment is read-only.");
        if (!this.imagesSupported) return L10n.tr("chat.attach.blocked_unsupported", "This T3 Code connection doesn't support image attachments.");
        if (this.isSending(detail.id())) return L10n.tr("chat.attach.blocked_sending", "Wait for the message to send.");
        if (this.images(detail.id()).size() >= LocalImage.MAX_IMAGES)
            return L10n.tr("chat.attach.blocked_limit", "At most %s images per message.", LocalImage.MAX_IMAGES);
        return null;
    }

    /** @param canAttach composer is usable; @param canRemove staged images may be removed (anything but mid-send) */
    private void refreshAttachments(boolean canAttach, boolean canRemove) {
        var detail = this.snapshot.selectedThread();
        this.attachButton.setActive(canAttach && this.choosing == null && this.attachBlocker() == null);
        if (this.attachmentStrip != null) this.attachmentStrip.setImages(this.images(detail == null ? null : detail.id()), canRemove);
    }

    private void chooseImages() {
        var detail = this.snapshot.selectedThread();
        if (this.choosing != null || detail == null) return;
        String blocked = this.attachBlocker();
        if (blocked != null) {
            this.notice(blocked, true);
            return;
        }
        var request = new AttachRequest(detail.id(), this.imageGeneration);
        this.choosing = request;
        this.refresh(this.snapshot, false);
        NativeFilePicker.openImages(result -> {
            if (this.choosing == request) this.choosing = null;
            if (result.error() != null) this.notice(L10n.tr("chat.file.dialog_error", "File dialog: %s", result.error()), true);
            else if (!result.paths().isEmpty()) this.stageImages(request, result.paths());
            if (this.composer != null) this.refresh(this.snapshot, false);
        });
    }

    /** Files dragged onto the window (Minecraft calls this on the render thread). */
    @Override
    public void onFilesDrop(List<Path> paths) {
        var detail = this.snapshot.selectedThread();
        if (paths.isEmpty() || detail == null) {
            if (detail == null) this.notice(L10n.tr("chat.attach.drop_no_thread", "Select a thread before dropping images."), true);
            return;
        }
        String blocked = this.attachBlocker();
        if (blocked != null) {
            this.notice(blocked, true);
            return;
        }
        this.stageImages(new AttachRequest(detail.id(), this.imageGeneration), paths);
    }

    /** Validates files off the render thread, then stages them if the same thread/connection is still showing. */
    private void stageImages(AttachRequest request, List<Path> paths) {
        this.syncImageGeneration(this.controller.snapshot());
        if (request.generation() != this.imageGeneration) {
            this.notice(L10n.tr("chat.attach.connection_changed", "Images not attached: the connection changed."), true);
            return;
        }
        List<Path> files = List.copyOf(paths.subList(0, Math.min(paths.size(), 32)));
        this.validating.merge(request.threadId(), 1, Integer::sum);
        if (this.composer != null) this.refresh(this.snapshot, false);
        CompletableFuture.supplyAsync(() -> {
            List<Object> opened = new ArrayList<>(); // LocalImage or String error
            for (var path : files) {
                try {
                    opened.add(LocalImage.open(path));
                } catch (Exception e) {
                    String name = path.getFileName() == null ? path.toString() : path.getFileName().toString();
                    opened.add(name + ": " + Theme.orDefault(e.getMessage(), L10n.tr("chat.attach.unreadable", "could not be read.")));
                }
            }
            return opened;
        }, Util.ioPool()).whenCompleteAsync((opened, failure) -> {
            if (request.generation() != this.imageGeneration) return; // another environment: drafts were reset
            this.validating.computeIfPresent(request.threadId(), (k, n) -> n > 1 ? n - 1 : null);
            if (this.composer != null) this.refresh(this.snapshot, false);
            if (failure != null) {
                this.notice(L10n.tr("chat.attach.failed", "Could not attach: %s",
                        Theme.orDefault(failure.getMessage(), L10n.tr("chat.attach.unknown_error", "unknown error"))), true);
                return;
            }
            var detail = this.snapshot.selectedThread();
            if (detail == null || !detail.id().equals(request.threadId())) {
                this.notice(L10n.tr("chat.attach.thread_changed", "Images not attached: the thread or connection changed."), true);
                return;
            }
            if (this.isSending(request.threadId())) {
                this.notice(L10n.tr("chat.attach.wait_sending", "Images not attached: wait for the message to send."), true);
                return;
            }
            var staged = this.imageDrafts.computeIfAbsent(request.threadId(), k -> new ArrayList<>());
            int added = 0;
            String firstError = null;
            int errors = 0;
            int overLimit = 0;
            for (var item : opened) {
                if (item instanceof LocalImage image) {
                    if (staged.stream().anyMatch(s -> s.path().equals(image.path()))) continue; // already attached
                    if (staged.size() >= LocalImage.MAX_IMAGES) {
                        overLimit++;
                        continue;
                    }
                    staged.add(image);
                    added++;
                } else {
                    errors++;
                    if (firstError == null) firstError = (String) item;
                }
            }
            this.imagesChanged(request.threadId());
            if (firstError != null) {
                this.notice(firstError + (errors > 1 ? L10n.tr("chat.attach.more_errors", " (+%s more not attached)", errors - 1) : ""), true);
            } else if (overLimit > 0) {
                this.notice(L10n.tr("chat.attach.over_limit", "%1$s not attached: at most %2$s images per message.", overLimit, LocalImage.MAX_IMAGES), true);
            } else if (added > 0) {
                this.notice(added == 1 ? L10n.tr("chat.attach.added_one", "Attached %s.", staged.getLast().name())
                        : L10n.tr("chat.attach.added_many", "Attached %s images.", added), false);
            }
            if (this.composer != null) this.refresh(this.snapshot, false);
        }, this.minecraft);
    }

    private void removeImage(LocalImage image) {
        var detail = this.snapshot.selectedThread();
        if (detail == null || this.isSending(detail.id())) return;
        var staged = this.imageDrafts.get(detail.id());
        if (staged == null || !staged.removeIf(s -> s == image)) return;
        this.imagesChanged(detail.id());
        if (this.composer != null) this.refresh(this.snapshot, false);
    }

    /** Drops empty drafts, frees unused previews, and schedules a re-layout when the strip appears/disappears. */
    private void imagesChanged(String threadId) {
        var staged = this.imageDrafts.get(threadId);
        if (staged != null && staged.isEmpty()) this.imageDrafts.remove(threadId);
        List<LocalImage> live = new ArrayList<>();
        this.imageDrafts.values().forEach(live::addAll);
        this.previews.retain(live);
        if (threadId.equals(this.displayedThreadId) && (this.attachmentStrip != null) != this.imageDrafts.containsKey(threadId)) {
            this.attachLayoutDirty = true;
        }
    }

    private boolean attaching(String threadId) {
        return this.validating.containsKey(threadId)
                || (this.choosing != null && this.choosing.threadId().equals(threadId));
    }

    /** Drops every image draft, pending validation and preview when the controller moved to a new connection. */
    private void syncImageGeneration(WorkspaceSnapshot snapshot) {
        var servers = snapshot.settings().connections();
        long generation = servers.generation();
        if (generation == this.imageGeneration) return;
        var nextDestination = new Destination(servers.activeProfileId(), servers.environmentId());
        if (!nextDestination.equals(this.destination)) {
            this.saveWidgetState();
            this.destination = nextDestination;
            this.draftsByDestination.keySet().removeIf(key -> servers.profiles().stream().noneMatch(p -> p.id().equals(key.profile())));
            this.composerDrafts = this.draftsByDestination.computeIfAbsent(nextDestination, ignored -> new HashMap<>());
            this.displayedThreadId = null;
            this.answerDrafts.clear();
            this.answeredAt.clear();
            this.multiSelections.clear();
            this.sidebarScroll = 0;
            this.transcriptAtBottom = true;
            this.transcriptState.reset();
            if (this.chatPreviews != null) this.chatPreviews.retain(List.of());
            this.pickingProject = false;
            this.settledExpanded = false;
            this.settledLimit = SidebarList.SETTLED_PAGE;
            this.attachLayoutDirty = true;
        }
        this.pendingSend = null;
        this.imageGeneration = generation;
        this.imageDrafts.clear();
        this.validating.clear();
        this.previews.retain(List.of());
        this.sendError = null;
        this.sendErrorThread = null;
        if (this.attachmentStrip != null) this.attachLayoutDirty = true;
    }

    private void notice(String message, boolean error) {
        this.attachNotice = message;
        this.attachNoticeError = error;
        this.attachNoticeAt = Util.getMillis();
    }

    private static String errorMessage(@Nullable Throwable error, @Nullable String fallback) {
        Throwable cause = error;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        String message = cause == null ? null : cause.getMessage();
        if (message != null && !message.isBlank()) return message.strip();
        return fallback;
    }

    @Override
    public boolean isPauseScreen() {
        return this.parent != null && this.parent.isPauseScreen();
    }

    // ---------------------------------------------------------------- rendering

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float delta) {
        if (Theme.seeThrough()) Theme.seeThroughBackdrop(this.minecraft, graphics, this.width, this.height);
        else super.extractBackground(graphics, mouseX, mouseY, delta);
        // Sidebar and main area tint side by side (never stacked) so each one's translucency matches the setting.
        graphics.fill(0, 0, this.sidebarWidth, this.height, Theme.SIDEBAR);
        graphics.fill(this.sidebarWidth + 1, 0, this.width, this.height, Theme.BG);
        graphics.fill(this.sidebarWidth, 0, this.sidebarWidth + 1, this.height, Theme.BORDER);
        graphics.fill(this.sidebarWidth + 1, HEADER, this.width, HEADER + 1, Theme.BORDER);
        graphics.fill(0, this.height - 30, this.sidebarWidth, this.height - 29, Theme.BORDER);
    }

    @Override
    public void extractRenderState(SpruceGuiGraphics graphics, int mouseX, int mouseY, float delta) {
        var font = this.font;
        var snap = this.snapshot;
        int sw = this.sidebarWidth;

        // Sidebar header: brand + connection state
        var brand = Component.literal(Theme.ellipsize(font, this.serverLabel, sw - 18)).withStyle(s -> s.withBold(true));
        var connection = snap.connection();
        boolean busy = connection.state() == ConnectionState.CONNECTING;
        String status = Theme.connectionLabel(connection.state());
        if (connection.state() == ConnectionState.CONNECTED && snap.settings().connections().readOnly()) status = L10n.tr("chat.workspace.connected_read_only", "Connected · read-only");
        if (!connection.message().isBlank() && connection.state() != ConnectionState.CONNECTING) {
            status = connection.state() == ConnectionState.CONNECTED ? connection.message() : status;
        }
        int statusColor = connection.state() == ConnectionState.CONNECTED && !connection.message().isBlank() ? Theme.AMBER : Theme.MUTED;
        String statusText = Theme.ellipsize(font, status, sw - 28);
        Theme.fillPlate(graphics, 0, 0, sw, HEADER, Theme.SIDEBAR_SHEET);
        Theme.fillPlate(graphics, sw + 1, 0, this.width, HEADER, Theme.SHEET);
        graphics.text(font, brand, 10, 9, Theme.TEXT, false);
        Theme.dot(graphics, 10, 22, Theme.connectionColor(connection.state()), busy);
        graphics.text(font, statusText, 18, 20, statusColor, false);
        if (mouseX < sw && mouseY >= 18 && mouseY < 30 && !connection.message().isBlank()) {
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(connection.message()), 220), mouseX, mouseY);
        }

        // Main header: thread title, status and project
        int mainX = sw + 1 + 12;
        int rightLimit = this.width - 12 - (this.stopButton.isVisible() ? this.stopButton.getWidth() + 6 : 0)
                - (this.connectButton.isVisible() ? this.connectButton.getWidth() + 6 : 0);
        var detail = snap.selectedThread();
        if (detail != null) {
            String title = Theme.ellipsize(font, Theme.orDefault(detail.title(), L10n.tr("chat.thread.untitled", "New thread")), rightLimit - mainX);
            var titleText = Component.literal(title).withStyle(s -> s.withBold(true));
            var project = snap.project(detail.projectId());
            String sub = Theme.statusLabel(detail.status()) + (project == null ? "" : "  ·  " + Theme.orDefault(project.name(), project.path()));
            sub = Theme.ellipsize(font, sub, rightLimit - mainX - 8);
            graphics.text(font, titleText, mainX, 7, Theme.TEXT, false);
            Theme.dot(graphics, mainX, 20, Theme.statusColor(detail.status()), detail.status() == ThreadStatus.RUNNING);
            graphics.text(font, sub, mainX + 8, 19, Theme.MUTED, false);
        } else {
            var none = Component.literal(L10n.tr("chat.workspace.no_thread", "No thread selected")).withStyle(s -> s.withBold(true));
            graphics.text(font, none, mainX, 12, Theme.MUTED, false);
        }

        // Composer hint row
        if (this.composer != null) {
            String hint = "";
            String hintDetail = null;
            int hintColor = Theme.FAINT;
            if (this.pendingSend != null && detail != null && this.pendingSend.threadId().equals(detail.id())) {
                hint = sendingLabel();
                hintColor = Theme.MUTED;
            } else if (detail != null && this.models.applying(detail.id())) {
                hint = L10n.tr("chat.hint.applying_model", "Applying model…");
                hintColor = Theme.MUTED;
            } else if (detail != null && this.models.recentlyFailed(detail.id())) {
                hint = L10n.tr("chat.hint.model_failed", "Model not changed. Your choice is kept — open the picker to retry.");
                hintColor = Theme.RED;
            } else if (detail != null && this.sendError != null && detail.id().equals(this.sendErrorThread)
                    && Util.getMillis() - this.sendFailedAt < 20000) {
                hint = L10n.tr("chat.hint.not_sent", "Not sent: %s", this.sendError);
                hintColor = Theme.RED;
                hintDetail = this.sendError + "\n" + L10n.tr("chat.hint.not_sent_detail",
                        "Your message and images are kept. Nothing is retried automatically — press Enter to send again.");
            } else if (this.sendFailedAt != 0 && Util.getMillis() - this.sendFailedAt < 6000) {
                hint = L10n.tr("chat.hint.draft_kept", "Message not sent. Your draft is kept — press Enter to retry.");
                hintColor = Theme.RED;
            } else if (!this.attachNotice.isEmpty() && Util.getMillis() - this.attachNoticeAt < 6000) {
                hint = this.attachNotice;
                hintColor = this.attachNoticeError ? Theme.RED : Theme.MUTED;
                hintDetail = this.attachNotice;
            } else if (this.choosing != null) {
                hint = L10n.tr("chat.hint.choosing", "Choose images in the file dialog…");
                hintColor = Theme.MUTED;
            } else if (detail != null && this.validating.containsKey(detail.id())) {
                hint = L10n.tr("chat.hint.attaching", "Attaching images…");
                hintColor = Theme.MUTED;
            } else if (snap.settings().connections().readOnly()) {
                hint = L10n.tr("chat.hint.read_only", "Read-only environment");
            } else if (!this.composer.isActive()) {
                hint = snap.connection().state() == ConnectionState.CONNECTED ? L10n.tr("chat.hint.select_thread", "Select a thread to start typing")
                        : L10n.tr("chat.empty.not_connected", "Not connected");
            }
            int hintY = this.height - 8 - SEND_ROW + (SEND_ROW - font.lineHeight) / 2 + 1;
            int hintX = this.modelButton.getEndX() + 8;
            int hintEnd = this.attachButton.getX() - 6;
            String hintText = Theme.ellipsize(font, hint, hintEnd - hintX);
            if (hintDetail == null && !hintText.equals(hint)) hintDetail = hint; // truncated (narrow window or long translation)
            Theme.fillPlate(graphics, this.composer.getX(), this.composer.getEndY(), this.composer.getEndX(),
                    this.height - 8, Theme.SHEET);
            graphics.text(font, hintText, hintX, hintY, hintColor, false);
            if (hintDetail != null && mouseX >= hintX && mouseX < hintEnd && mouseY >= hintY - 4 && mouseY < hintY + font.lineHeight + 4) {
                graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(hintDetail), 240), mouseX, mouseY);
            }
        }

        super.extractRenderState(graphics, mouseX, mouseY, delta);

        if (this.attachmentStrip != null) this.attachmentStrip.extractHoverCard(graphics, mouseX, mouseY, this.width);
        if (this.attachButton != null && this.attachButton.isMouseHovered()) {
            String blocked = this.choosing != null ? L10n.tr("chat.file.dialog_open", "The file dialog is open.") : this.attachBlocker();
            String tip = blocked != null ? blocked
                    : L10n.tr("chat.attach.tooltip", "Attach PNG, JPEG, GIF or WebP images (up to %s, 10 MiB each).", LocalImage.MAX_IMAGES)
                    + "\n" + L10n.tr("chat.attach.tooltip_drop", "You can also drop image files onto this window.");
            graphics.vanilla().setTooltipForNextFrame(font, font.split(Component.literal(tip), 220), mouseX, mouseY);
        }
    }
}
