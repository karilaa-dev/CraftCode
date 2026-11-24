package dev.t3mc;

import static dev.t3mc.L10n.tr;

import dev.t3mc.api.T3Api;
import dev.t3mc.auth.T3OAuth;
import dev.t3mc.mcp.McpClient;
import dev.t3mc.ui.Notifications;
import dev.t3mc.ui.model.*;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;

/** Each connected workspace owns its worker and notification monitor. Only the foreground workspace publishes its transcript. */
public final class ClientController implements AutoCloseable {
    private final Path file;
    private final ChatImageStore imageStore;
    @FunctionalInterface
    interface NotificationSink { void present(NotificationMode mode, String title, String body); }
    private final NotificationSink notifications;
    private ClientConfig config;
    private record Target(String profileId, String environmentId) {}
    private final Map<Target,Session> sessions = new LinkedHashMap<>();
    private Session session; // Foreground workspace.

    private volatile long connectionGeneration;
    private volatile WorkspaceSnapshot snapshot;

    public ClientController(Path file) { this(file, Notifications::present); }

    ClientController(Path file, NotificationSink notifications) {
        this.notifications = notifications;
        this.file = file;
        this.imageStore = new ChatImageStore(file.resolveSibling("t3mc-image-cache"));
        String error = "";
        try { config = ClientConfig.load(file); }
        catch (IOException e) { config = ClientConfig.defaults(); error = e.getMessage(); }
        snapshot = new WorkspaceSnapshot(0, new ConnectionStatus(error.isEmpty() ? ConnectionState.DISCONNECTED : ConnectionState.ERROR, error),
                List.of(), null, null, config.view(connections()));
    }

    public WorkspaceSnapshot snapshot() { return snapshot; }

    /** Identifies the connection whose catalog and per-thread settings are currently displayed. */
    public long connectionGeneration() { return connectionGeneration; }

    /** The foreground can change between rendering a button and clicking it (for example when a link expires). */
    public synchronized void onConnection(long generation, Runnable submit) {
        if (generation == connectionGeneration) submit.run();
    }

    public synchronized CompletionStage<Boolean> changeOnConnection(long generation,
            java.util.function.Supplier<CompletionStage<Boolean>> submit) {
        if (generation != connectionGeneration) return CompletableFuture.completedFuture(false);
        return submit.get();
    }

    public synchronized boolean setTheme(ColorTheme theme) {
        if (config.theme() == theme) return true;
        return savePreference(config.withTheme(Objects.requireNonNull(theme)));
    }

    public synchronized boolean setTransparency(int percentage) {
        var next = config.withTransparency(percentage);
        return config.transparency() == next.transparency() || savePreference(next);
    }

    public synchronized List<ModelFavorite> favoriteModels() { return config.favoriteModels(); }

    public synchronized boolean isFavoriteModel(String providerId, String modelId) {
        if (providerId == null || providerId.isBlank() || modelId == null || modelId.isBlank()) return false;
        return config.favoriteModels().contains(new ModelFavorite(providerId, modelId));
    }

    public synchronized boolean toggleFavoriteModel(String providerId, String modelId) {
        ModelFavorite key = new ModelFavorite(providerId, modelId);
        var favorites = new ArrayList<>(config.favoriteModels());
        if (!favorites.remove(key)) favorites.add(key);
        return savePreference(config.withFavorites(favorites));
    }

    private boolean savePreference(ClientConfig next) {
        if (!persistConfig(next)) return false;
        publishActive();
        return true;
    }

    private boolean persistConfig(ClientConfig next) {
        try {
            next.save(file);
            config = next;
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    public synchronized ConnectionsView connections() {
        var profiles = config.profiles().stream().map(p -> {
            Session root = root(p.endpoint());
            return new ConnectionProfileView(p.endpoint(), p.name(), p.endpoint(), !p.token().isBlank(),
                    root == null ? ConnectionState.DISCONNECTED : root.status.state(),
                    p.includeLinked(), root != null && root.capabilities.contains("t3_environment_links"),
                    root != null && p.includeLinked() ? root.links : List.of(), root == null ? "" : root.linkError,
                    root != null && p.includeLinked() && root.discoveryQueued, p.linksExpanded());
        }).toList();
        return new ConnectionsView(profiles, config.activeProfileId(), session == null ? "" : session.environmentId, connectionGeneration);
    }

    private Session root(String profileId) { return sessions.get(new Target(profileId, "")); }

    public void applySettings(SettingsUpdate update) { saveProfile("", update); }

    /** Add or update this endpoint without disconnecting other environments. */
    public synchronized boolean saveProfile(String name, SettingsUpdate update) {
        try {
            var address = T3OAuth.connectionAddress(update.httpUrl());
            String connectionCode = address.resolveCode(update.connectionCode());
            ClientConfig next = config.withProfile(name, address.endpoint(), update.notificationMode());
            next.save(file);
            boolean sameProfile = config.activeProfileId().equals(address.endpoint());
            config = next;
            Session primary = root(address.endpoint());
            if (primary == null || primary.status.state() == ConnectionState.ERROR || !connectionCode.isEmpty())
                startRoot(connectionCode);
            else activate(sameProfile && session != null ? session : primary);
            return true;
        } catch (IOException e) {
            publish(new ConnectionStatus(snapshot.connection().state(), message(e)), snapshot.projects(), snapshot.selectedThread());
            return false;
        }
    }

    public synchronized boolean selectProfile(String id) {
        if (config.profile(id) == null || !persistConfig(config.select(id))) return false;
        Session selected = root(id);
        if (selected == null || selected.status.state() == ConnectionState.ERROR) startRoot("");
        else activate(selected);
        return true;
    }

    public synchronized boolean removeProfile(String id) {
        if (config.profile(id) == null) return false;
        boolean active = config.activeProfileId().equals(id);
        if (!persistConfig(config.remove(id))) return false;
        stopProfile(id);
        if (active) activate(root(config.activeProfileId()));
        else publishActive();
        return true;
    }

    /** Folding the tree only changes presentation. Opening an opted-out tree also starts discovery. */
    public synchronized boolean setLinksExpanded(String profileId, boolean expanded) {
        var profile = config.profile(profileId);
        if (profile == null) return false;
        if (profile.linksExpanded() == expanded) return true;
        if (!persistConfig(config.withLinksExpanded(profileId, expanded))) return false;
        if (expanded && !profile.includeLinked()) refreshLinkedEnvironments(profileId);
        publishActive();
        return true;
    }

    public synchronized boolean setIncludeLinked(boolean include) {
        return setIncludeLinked(config.activeProfileId(), include);
    }

    public synchronized boolean setIncludeLinked(String profileId, boolean include) {
        var profile = config.profile(profileId);
        if (profile == null) return false;
        if (include == profile.includeLinked()) return true;
        if (!persistConfig(config.withLinked(profileId, include))) return false;
        Session primary = root(profileId);
        if (primary != null && include) primary.queueDiscovery();
        if (primary != null && !include) {
            primary.links = List.of();
            primary.linkError = "";
            for (Session peer : List.copyOf(sessions.values()))
                if (peer.profileId.equals(primary.profileId) && !peer.environmentId.isEmpty()) stop(peer);
            if (session != null && session.profileId.equals(profileId) && !session.environmentId.isEmpty()) activate(primary);
        }
        publishActive();
        return true;
    }

    public synchronized void refreshLinkedEnvironments() {
        refreshLinkedEnvironments(config.activeProfileId());
    }

    public synchronized void refreshLinkedEnvironments(String profileId) {
        Session primary = root(profileId);
        if (primary != null && config.includeLinked(profileId)) primary.queueDiscovery();
    }

    public synchronized boolean selectEnvironment(String id) {
        return selectEnvironment(config.activeProfileId(), id);
    }

    public synchronized boolean selectEnvironment(String profileId, String id) {
        Session primary = root(profileId);
        if (primary == null || id == null) return false;
        if (id.isEmpty()) return selectProfile(profileId);
        if (!config.includeLinked(profileId) || primary.status.state() != ConnectionState.CONNECTED) return false;
        var link = primary.links.stream().filter(l -> l.id().equals(id) && l.reachable()).findFirst().orElse(null);
        if (link == null) return false;
        if (!profileId.equals(config.activeProfileId()) && !persistConfig(config.select(profileId))) return false;
        var key = new Target(primary.profileId, id);
        Session target = sessions.get(key);
        if (target == null || target.status.state() == ConnectionState.ERROR) {
            if (target != null) stop(target);
            target = new Session(config, id, link.label());
            sessions.put(key, target);
            activate(target);
            Session started = target;
            target.worker.execute(() -> started.connect(""));
        } else activate(target);
        return true;
    }

    public synchronized void reconnect() {
        if (session != null && !session.environmentId.isEmpty()) {
            String id = session.environmentId;
            Session primary = root(session.profileId);
            if (primary != null && primary.status.state() == ConnectionState.CONNECTED) {
                if (primary.links.stream().anyMatch(l -> l.id().equals(id) && l.reachable())) {
                    stop(session);
                    selectEnvironment(id);
                } else primary.queueDiscovery();
                return;
            }
        }
        if (!config.httpUrl().isBlank()) startRoot("");
    }

    private void startRoot(String code) {
        stopProfile(config.activeProfileId());
        Session next = new Session(config, "", config.profile(config.activeProfileId()).name());
        sessions.put(next.key, next);
        activate(next);
        next.worker.execute(() -> next.connect(code));
    }

    private void activate(Session next) {
        if (next != session) connectionGeneration++;
        session = next;
        publishActive();
    }

    private void publishActive() {
        publish(session == null ? ConnectionStatus.disconnected() : session.status,
                session == null ? List.of() : session.visibleSidebar, session == null ? null : session.visibleDetail);
    }

    public synchronized void disconnect() {
        stopProfile(config.activeProfileId());
        activate(null);
    }

    private void stopProfile(String id) {
        for (Session target : List.copyOf(sessions.values())) if (target.profileId.equals(id)) stop(target);
    }

    private void stop(Session old) {
        sessions.remove(old.key, old);
        old.worker.shutdownNow();
        if (old.sending != null && old.sendingImages)
            old.sending.completeExceptionally(new IOException(tr("net.closed_images", "Connection closed. Check the thread before sending the images again.")));
        else if (old.sending != null) old.sending.complete(false);
        if (old.configuring != null) old.configuring.complete(false);
        for (var query : old.catalogRequests) query.completeExceptionally(new IOException(tr("net.disconnected", "Disconnected from T3 Code.")));
        old.catalogRequests.clear();
        Thread.startVirtualThread(old::closeWire);
    }

    @Override public synchronized void close() {
        for (Session open : List.copyOf(sessions.values())) stop(open);
        activate(null);
    }
    public void selectThread(String threadId) { navigate(s -> s.select(threadId)); }
    public synchronized void createThread(String projectId) { if (!readOnlyEnvironment()) navigate(s -> s.create(projectId)); }

    private synchronized void navigate(Action action) {
        Session target = session;
        if (target == null || snapshot.connection().state() != ConnectionState.CONNECTED) return;
        target.pendingNavigation++;
        target.worker.execute(() -> {
            try { action.run(target); }
            catch (Exception e) { target.problem(e, false); }
            finally {
                synchronized (ClientController.this) { target.pendingNavigation--; }
            }
        });
    }

    /** Search on the server so providers can return models omitted from the initial short catalog. */
    public synchronized CompletionStage<List<ModelProvider>> listModels(String search) {
        Session target = session;
        if (target == null || snapshot.connection().state() != ConnectionState.CONNECTED)
            return CompletableFuture.failedFuture(new IOException(tr("net.models_connect", "Connect to T3 Code to browse models.")));
        if (!target.capabilities.contains("orchestrator_capabilities"))
            return CompletableFuture.failedFuture(new IOException(tr("net.no_catalog", "This T3 server does not expose a model catalog.")));
        var result = new CompletableFuture<List<ModelProvider>>();
        target.catalogRequests.add(result);
        target.worker.execute(() -> {
            try { result.complete(target.api.models(Objects.requireNonNullElse(search, "").strip())); }
            catch (Exception e) { result.completeExceptionally(e); }
            finally { synchronized (ClientController.this) { target.catalogRequests.remove(result); } }
        });
        return result;
    }

    /** Apply to the explicit selected thread. The next send may proceed once T3 accepts the change. */
    public synchronized CompletionStage<Boolean> configureModel(String threadId, ModelSelection selection) {
        Session target = session;
        if (!canEditThread(target, threadId) || selection == null
                || selection.providerId().isBlank() || selection.modelId().isBlank()) return CompletableFuture.completedFuture(false);
        var result = new CompletableFuture<Boolean>();
        target.configuring = result;
        target.worker.execute(() -> {
            try {
                if (!Objects.equals(target.selected, threadId) || target.detail == null) {
                    result.complete(false);
                    return;
                }
                if (!target.capabilities.contains("t3_thread_configure"))
                    throw new IOException(tr("net.no_model_settings", "This T3 server does not expose model configuration."));
                target.api.configure(threadId, selection);
                target.detail = target.detail.withModelSelection(selection);
                target.show();
                result.complete(true);
            } catch (Exception e) {
                target.problem(e, false);
                result.completeExceptionally(e);
            }
        });
        return result;
    }

    public synchronized boolean readOnlyEnvironment() {
        if (session == null || session.environmentId.isEmpty()) return false;
        Session primary = root(session.profileId);
        return primary == null || primary.links.stream().noneMatch(link -> link.id().equals(session.environmentId)
                && link.reachable() && !link.readOnly());
    }

    private boolean canEditThread(Session target, String threadId) {
        return !readOnlyEnvironment() && target != null && snapshot.connection().state() == ConnectionState.CONNECTED
                && threadId != null && threadId.equals(snapshot.selectedThreadId()) && target.pendingNavigation == 0
                && (target.configuring == null || target.configuring.isDone())
                && (target.sending == null || target.sending.isDone());
    }

    public synchronized boolean supportsImageAttachments() {
        return session != null && !readOnlyEnvironment() && snapshot.connection().state() == ConnectionState.CONNECTED
                && (session.environmentId.isEmpty() || session.capabilities.containsAll(T3Api.ROUTED_IMAGE_TOOLS))
                && session.capabilities.contains("t3_attachment_prepare_upload")
                && session.capabilities.contains("t3_thread_send_attachments");
    }

    public CompletionStage<Boolean> sendMessage(String threadId, String text) {
        return sendMessage(threadId, text, List.of());
    }

    public synchronized CompletionStage<Boolean> sendMessage(String threadId, String text, List<LocalImage> images) {
        Session target = session;
        if (!canEditThread(target, threadId) || (text.isBlank() && images.isEmpty()))
            return CompletableFuture.completedFuture(false);
        if (!images.isEmpty() && !supportsImageAttachments())
            return CompletableFuture.failedFuture(new IOException(tr("net.no_attachments", "This T3 connection does not offer image attachments.")));
        if (images.size() > LocalImage.MAX_IMAGES)
            return CompletableFuture.failedFuture(new IOException(tr("net.max_images", "You can attach up to %s images per message.", LocalImage.MAX_IMAGES)));
        var attachments = List.copyOf(images);
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        target.sending = result;
        target.sendingImages = !attachments.isEmpty();
        target.worker.execute(() -> {
            try {
                if (!Objects.equals(target.selected, threadId)) {
                    result.complete(false);
                    return;
                }
                target.send(threadId, text, attachments);
                result.complete(true);
                target.refresh();
            } catch (Exception e) {
                if (attachments.isEmpty()) result.complete(false);
                else result.completeExceptionally(e);
                target.problem(e, false);
            }
        });
        return result;
    }
    public void interruptThread(String threadId) {
        submit(s -> { s.api.interrupt(threadId, UUID.randomUUID().toString()); s.refresh(); });
    }
    public void answerQuestion(String threadId, String requestId, String answer) {
        answerQuestion(threadId, requestId, List.of(answer));
    }
    public void answerQuestion(String threadId, String requestId, List<String> answers) {
        submit(s -> s.answer(threadId, requestId, List.copyOf(answers)));
    }

    private interface Action { void run(Session s) throws IOException; }
    private synchronized void submit(Action action) {
        Session target = session;
        if (target == null || readOnlyEnvironment() || snapshot.connection().state() != ConnectionState.CONNECTED) return;
        target.worker.execute(() -> {
            try { action.run(target); }
            catch (Exception e) { target.problem(e, false); }
        });
    }

    private void publish(ConnectionStatus connection, List<ProjectView> projects, ThreadDetail detail) {
        String id = detail == null ? null : detail.id();
        var next = new WorkspaceSnapshot(snapshot.version(), connection, projects, id, detail, config.view(connections()));
        if (!snapshot.equals(next)) snapshot = new WorkspaceSnapshot(next.version() + 1, connection, projects, id, detail, next.settings());
    }

    private final class Session {
        final ClientConfig settings;
        final String profileId, environmentId, label;
        final Target key;
        ConnectionStatus status = new ConnectionStatus(ConnectionState.CONNECTING, tr("net.connecting", "Connecting to T3 Code…"));
        List<ProjectView> visibleSidebar = List.of();
        ThreadDetail visibleDetail;
        List<LinkedEnvironmentView> links = List.of();
        String linkError = "";
        long nextDiscovery;
        boolean discoveryQueued;
        final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(Thread.ofVirtual().name("t3mc-session").factory());
        volatile McpClient wire;
        T3Api api;
        volatile Set<String> capabilities = Set.of();
        List<T3Api.Project> projects = List.of();
        List<ProjectView> sidebar = List.of();
        CompletableFuture<Boolean> sending;
        boolean sendingImages;
        CompletableFuture<Boolean> configuring;
        final Set<CompletableFuture<List<ModelProvider>>> catalogRequests = new HashSet<>();
        int pendingNavigation;
        String retryThread, retryText, retryKey;
        ThreadMonitor monitor;
        T3Api.ThreadInfo lastDetail;
        final Map<String,Map<String,Object>> partialAnswers = new HashMap<>();
        List<T3Api.Request> requests = List.of();
        String selected;
        ThreadDetail detail;
        long projectRefresh;
        String pollError = "";

        Session(ClientConfig settings, String environmentId, String label) {
            this.settings = settings;
            this.profileId = settings.httpUrl();
            this.environmentId = environmentId;
            this.label = label;
            this.key = new Target(profileId, environmentId);
        }

        boolean registered() { return sessions.get(key) == this; }

        void queueDiscovery() {
            if (api == null || discoveryQueued || !capabilities.contains("t3_environment_links") || worker.isShutdown()) return;
            discoveryQueued = true;
            publishActive();
            worker.execute(() -> {
                try { discover(); }
                finally {
                    synchronized (ClientController.this) {
                        discoveryQueued = false;
                        if (registered()) publishActive();
                    }
                }
            });
        }

        void discover() {
            synchronized (ClientController.this) {
                if (!registered() || !config.includeLinked(profileId) || !capabilities.contains("t3_environment_links")) return;
            }
            List<LinkedEnvironmentView> found = List.of();
            String error = "";
            try { found = api.links(); }
            catch (Exception e) { error = message(e); }
            synchronized (ClientController.this) {
                if (!registered() || !config.includeLinked(profileId)) return;
                linkError = error;
                if (error.isEmpty()) {
                    links = found;
                    Set<String> reachable = new HashSet<>();
                    for (var link : links) if (link.reachable()) reachable.add(link.id());
                    for (Session peer : List.copyOf(sessions.values())) {
                        if (!profileId.equals(peer.profileId) || peer.environmentId.isEmpty() || reachable.contains(peer.environmentId)) continue;
                        stop(peer);
                        if (session == peer) {
                            linkError = tr("net.peer_unreachable", "%s is no longer reachable. Switched to the primary environment.", peer.label);
                            activate(this);
                        }
                    }
                }
                publishActive();
            }
        }

        void connect(String connectionCode) {
            try {
                if (settings.httpUrl().isBlank()) throw new IOException(tr("net.enter_url", "Enter your T3 Code URL in Settings."));
                wire = environmentId.isEmpty()
                        ? McpClient.connect(profileId, settings.token(), connectionCode, this::rememberGrant)
                        : McpClient.forLinkedEnvironment(profileId, settings.token());
                if (worker.isShutdown()) { closeWire(); return; }
                capabilities = wire.initialize();
                if (!environmentId.isEmpty()) {
                    capabilities = wire.routedTools();
                    if (!capabilities.containsAll(Set.of("t3_project_list", "t3_thread_list", "t3_thread_read")))
                        throw new IOException(tr("net.workspace_routing", "This T3 server cannot route the workspace tools to a linked environment."));
                }
                api = new T3Api(wire, environmentId);
                monitor = new ThreadMonitor(api, this::notice, capabilities.contains("t3_pending_request"), api.enableEvents());
                refresh();
                worker.scheduleWithFixedDelay(() -> {
                    try { refresh(); }
                    catch (Exception e) { problem(e, true); }
                }, 3, 3, TimeUnit.SECONDS);
            } catch (Exception e) {
                closeWire();
                synchronized (ClientController.this) {
                    if (registered()) {
                        status = new ConnectionStatus(ConnectionState.ERROR, message(e));
                        worker.shutdown();
                        publishActive();
                    }
                }
            }
        }

        void rememberGrant(String token) {
            synchronized (ClientController.this) {
                if (!registered()) return;
                config = config.withGrant(profileId, token);
                try { config.save(file); publishActive(); }
                catch (IOException e) { notice("T3 Code", tr("notice.grant_unsaved", "Sign-in succeeded for this session. Could not save the token.")); }
            }
        }

        void closeWire() { McpClient current = wire; if (current != null) current.close(); }

        void problem(Exception e, boolean polling) {
            String reason = message(e);
            synchronized (ClientController.this) {
                if (!registered()) return;
                status = new ConnectionStatus(polling ? ConnectionState.ERROR : ConnectionState.CONNECTED, reason);
                visibleSidebar = sidebar;
                visibleDetail = detail;
                publishActive();
                if (!polling || !reason.equals(pollError)) notice(tr("notice.t3_error", "T3 Code error"), reason);
            }
            if (polling) pollError = reason;
        }

        void notice(String title, String body) {
            synchronized (ClientController.this) {
                if (!registered()) return;
                if (sessions.size() > 1) {
                    String server = config.profile(profileId).name();
                    String source = environmentId.isEmpty() ? server : server + " / " + label;
                    title = source + " · " + title;
                }
                notifications.present(config.notificationMode(), title, body);
            }
        }

        void show() {
            synchronized (ClientController.this) {
                if (!registered()) return;
                status = new ConnectionStatus(ConnectionState.CONNECTED, "");
                visibleSidebar = sidebar;
                visibleDetail = detail;
                publishActive();
            }
        }

        void refresh() throws IOException {
            if (environmentId.isEmpty() && (nextDiscovery == 0 || System.nanoTime() - nextDiscovery > 0)) {
                nextDiscovery = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
                discover();
            }
            if (System.nanoTime() - projectRefresh >= 60_000_000_000L || projects.isEmpty() || projectRefresh == 0) {
                projects = api.projects();
                projectRefresh = System.nanoTime();
            }
            var threads = api.threads(null);
            Map<String,List<ThreadSummary>> grouped = new HashMap<>();
            monitor.update(threads);
            T3Api.ThreadInfo selectedInfo = null;
            for (var thread : threads) {
                if (thread.id().equals(selected)) selectedInfo = thread;
                boolean hasQuestion = monitor.hasQuestions(thread.id());
                boolean hasPendingInput = monitor.hasPendingInput(thread.id());
                grouped.computeIfAbsent(thread.projectId(), k -> new ArrayList<>()).add(new ThreadSummary(
                        thread.id(), thread.projectId(), thread.title(), thread.status(hasPendingInput), hasQuestion,
                        thread.updatedAtMillis(), thread.organization(hasPendingInput)));
            }
            sidebar = projects.stream().map(p -> new ProjectView(p.id(), p.title(), p.path(), grouped.getOrDefault(p.id(), List.of()))).toList();
            if (selected != null && selectedInfo == null) {
                selected = null;
                detail = null;
                requests = List.of();
            } else if (selected != null && (!Objects.equals(lastDetail, selectedInfo) || detail == null
                    || detail.status() == ThreadStatus.RUNNING || detail.status() == ThreadStatus.WAITING_FOR_INPUT
                    || monitor.hasQuestions(selected) || !requests.isEmpty())) {
                loadDetail();
                lastDetail = selectedInfo;
            }
            pollError = "";
            show();
        }

        void select(String id) throws IOException {
            if (Objects.equals(selected, id)) return;
            var conversation = api.read(id);
            updateDetail(conversation);
            selected = id;
            lastDetail = conversation.thread();
            show();
        }

        void create(String projectId) throws IOException {
            // T3 accepts an empty thread, inheriting its project's provider and permissions.
            String id = api.launch(projectId, tr("net.new_thread", "New thread"), "", UUID.randomUUID().toString());
            selected = id;
            refresh();
        }

        void send(String id, String text, List<LocalImage> images) throws IOException {
            if (images.isEmpty()) {
                if (!id.equals(retryThread) || !text.equals(retryText)) {
                    retryThread = id;
                    retryText = text;
                    retryKey = UUID.randomUUID().toString();
                }
                api.send(id, text, retryKey);
            } else {
                var payloads = new ArrayList<byte[]>(images.size());
                String messageId = api.sendImages(id, text, images, payloads::add);
                try { imageStore.put(profileId, environmentId, id, messageId, images, payloads); }
                catch (IOException e) { notice(tr("notice.images_sent", "Images sent"), tr("notice.preview_unsaved", "Could not save the local preview.")); }
            }
            retryText = retryThread = retryKey = null;
            lastDetail = null;
        }

        void loadDetail() throws IOException { updateDetail(api.read(selected)); }

        void updateDetail(T3Api.Conversation conversation) throws IOException {
            var info = conversation.thread();
            requests = conversation.pendingRequestCount() > 0 && capabilities.contains("t3_pending_request")
                    ? api.questions(info.id()) : List.of();
            List<PendingQuestion> questions = new ArrayList<>();
            for (var request : requests) {
                var values = partialAnswers.getOrDefault(request.id(), Map.of());
                for (var q : request.questions()) {
                    if (values.containsKey(q.id())) continue;
                    questions.add(pending(request, q));
                    break;
                }
            }
            var messages = new ArrayList<ChatMessage>();
            for (var m : conversation.messages()) {
                MessageRole role = switch (m.role()) {
                    case "user" -> MessageRole.USER;
                    case "assistant" -> MessageRole.ASSISTANT;
                    default -> MessageRole.SYSTEM;
                };
                var images = new ArrayList<ChatImage>();
                if (role == MessageRole.USER) images.addAll(imageStore.get(profileId, environmentId, m.sourceThreadId(), m.messageId()));
                for (var image : m.images()) {
                    if (image.source() instanceof ChatImage.Unavailable && images.stream().anyMatch(c -> c.name().equals(image.name()))) continue;
                    images.add(image);
                }
                String text = m.displayText(!images.isEmpty());
                messages.add(new ChatMessage(m.id(), role, text, m.createdAtMillis(), m.streaming(), m.runId(), m.phase(), images,
                        conversation.runTimings().get(m.runId())));
            }
            if (conversation.pendingRequestCount() > 0 && questions.isEmpty())
                messages.add(new ChatMessage("pending", MessageRole.SYSTEM, tr("net.pending_request", "Open this thread in T3 Code to review its pending request."), 0, false));
            boolean hasPendingInput = conversation.pendingRequestCount() > 0;
            detail = new ThreadDetail(info.id(), info.projectId(), info.title(), info.status(hasPendingInput),
                    messages, questions, modelFor(info), info.organization(hasPendingInput));
        }

        ModelSelection modelFor(T3Api.ThreadInfo info) {
            ModelSelection listed = new ModelSelection(info.providerId(), info.modelId(), Map.of());
            boolean same = detail != null && detail.id().equals(info.id())
                    && detail.modelSelection().providerId().equals(listed.providerId())
                    && detail.modelSelection().modelId().equals(listed.modelId());
            if (same && (Objects.equals(lastDetail, info) || info.status(false) == ThreadStatus.RUNNING))
                return detail.modelSelection();
            if (capabilities.contains("t3_thread_configure")) {
                try { return api.selection(info.id()); }
                catch (IOException ignored) { /* Read-only grants and older servers can still list provider and model. */ }
            }
            return same ? detail.modelSelection() : listed;
        }

        PendingQuestion pending(T3Api.Request request, T3Api.Question question) {
            return new PendingQuestion(key(request, question), question.prompt(), question.options(), question.allowCustomAnswer() || question.options().isEmpty(), question.multiSelect());
        }

        String key(T3Api.Request request, T3Api.Question question) { return request.id() + "/" + question.id(); }

        void answer(String threadId, String key, List<String> answers) throws IOException {
            if (!threadId.equals(selected)) return;
            for (var request : requests) for (var q : request.questions()) {
                if (!key(request, q).equals(key)) continue;
                var values = partialAnswers.computeIfAbsent(request.id(), unused -> new LinkedHashMap<>());
                if (values.containsKey(q.id()) || answers.isEmpty()) return;
                List<String> mapped = answers.stream().map(a -> q.choices().stream().filter(o -> o.label().equals(a))
                        .map(T3Api.Option::value).findFirst().orElse(a)).toList();
                var updated = new LinkedHashMap<>(values);
                updated.put(q.id(), q.multiSelect() ? mapped : mapped.getFirst());
                if (updated.size() == request.questions().size()) {
                    api.answer(threadId, request.id(), updated);
                    partialAnswers.remove(request.id());
                } else partialAnswers.put(request.id(), updated);
                lastDetail = null;
                refresh();
                return;
            }
        }
    }

    private static String message(Exception e) {
        return e.getMessage() == null || e.getMessage().isBlank() ? tr("net.unreachable", "Could not reach the T3 Code server.") : e.getMessage();
    }

}
