package dev.t3mc.api;

import dev.t3mc.L10n;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.t3mc.mcp.McpClient;
import dev.t3mc.ui.model.LinkedEnvironmentView;
import dev.t3mc.ui.model.LocalImage;
import dev.t3mc.ui.model.ChatImage;
import dev.t3mc.ui.model.ChatMessage.RunTiming;
import dev.t3mc.ui.model.ModelProvider;
import dev.t3mc.ui.model.ModelSelection;
import dev.t3mc.ui.model.ThreadOrganization;
import dev.t3mc.ui.model.ThreadStatus;

import java.io.IOException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Typed access to the tools exposed by a T3 Code environment's MCP server. */
public final class T3Api implements ChatImage.PageReader {
    public record Project(String id, String title, String path) {}
    public record ThreadInfo(String id, String projectId, String title, String status, String runId,
                             long updatedAtMillis, boolean settled, String providerId, String modelId,
                             boolean pinned, boolean snoozed, long snoozedUntilMillis) {
        public ThreadInfo(String id, String projectId, String title, String status, String runId,
                          long updatedAtMillis, boolean settled) {
            this(id, projectId, title, status, runId, updatedAtMillis, settled, "", "", false, false, 0);
        }

        public ThreadStatus status(boolean hasPendingInput) {
            return hasPendingInput ? ThreadStatus.WAITING_FOR_INPUT : ThreadStatus.fromRunStatus(status);
        }

        public ThreadOrganization organization(boolean hasPendingInput) {
            if (!settled && snoozed) return ThreadOrganization.SNOOZED;
            if (!settled && pinned) return ThreadOrganization.NORMAL;
            return ThreadOrganization.from(settled, status(hasPendingInput));
        }
    }
    public record Conversation(ThreadInfo thread, List<Message> messages, int pendingRequestCount, Map<String,RunTiming> runTimings) {
        public Conversation(ThreadInfo thread, List<Message> messages, int pendingRequestCount) {
            this(thread, messages, pendingRequestCount, Map.of());
        }
    }
    public static final String ATTACHMENT_PLACEHOLDER = "[Attachment]";
    public enum MessageNotice { NONE, EARLIER_HISTORY, HIDDEN_ATTACHMENT }
    public record Message(String id, String role, String text, long createdAtMillis, boolean streaming, String messageId,
                          String sourceThreadId, String runId, String phase, List<ChatImage> images,
                          MessageNotice notice, boolean truncated) {
        public String displayText(boolean hasImages) {
            String body = switch (notice) {
                case NONE -> text;
                case EARLIER_HISTORY -> L10n.tr("chat.history.recent_only", "Showing recent messages. Earlier history is available in T3 Code.");
                case HIDDEN_ATTACHMENT -> hasImages ? "" : L10n.tr("chat.history.attachment_without_preview",
                        "Attachment shared in T3 Code. This server did not include a preview.");
            };
            return truncated ? body + "\n" + L10n.tr("chat.history.truncated", "[… continued in T3 Code]") : body;
        }
    }
    public record Request(String id, List<Question> questions) {}
    public record Question(String id, String prompt, List<Option> choices, boolean multiSelect,
                           boolean allowCustomAnswer, boolean required, String initialAnswer) {
        public List<String> options() {
            return choices.stream().map(Option::label).toList();
        }
    }
    public record Option(String label, String value, String description) {}

    public record Event(String id, String projectId, String threadId, String type, String requestId, String kind,
                        String runId, String outcome, long occurredAtMillis) {}
    public record EventBatch(List<Event> entries, boolean truncated) {}

    private static final List<String> EVENT_TYPES = List.of("thread.needs_input", "thread.run_settled");
    private final McpClient client;
    private final String environmentId;
    private Map<String, String> eventCursors = Map.of();

    public T3Api(McpClient client) { this(client, ""); }

    public T3Api(McpClient client, String environmentId) {
        this.client = client;
        this.environmentId = environmentId;
    }

    public List<LinkedEnvironmentView> links() throws IOException {
        var reply = call("t3_environment_links", new JsonObject());
        var results = new ArrayList<LinkedEnvironmentView>();
        for (JsonElement entry : array(reply, "links")) {
            JsonObject row = object(entry, "links[]");
            results.add(new LinkedEnvironmentView(required(row, "environmentId"), required(row, "label"),
                    required(row, "status"), required(row, "access"), string(row, "expiresAt")));
        }
        return List.copyOf(results);
    }

    public List<Project> projects() throws IOException {
        Map<String, Project> projects = new LinkedHashMap<>();
        for (JsonObject row : list("t3_project_list", new JsonObject(), "projects")) {
            if (present(row, "deletedAt") || present(row, "archivedAt")) continue;
            Project project = new Project(required(row, "id"), string(row, "title"), string(row, "workspaceRoot"));
            projects.put(project.id(), project);
        }
        return List.copyOf(projects.values());
    }

    /** A null project ID lists the workspace's top-level threads across projects. */
    public List<ThreadInfo> threads(String projectId) throws IOException {
        JsonObject args = new JsonObject();
        if (projectId == null) args.addProperty("allProjects", true);
        else args.addProperty("projectId", projectId);
        args.addProperty("includeSubagents", false);
        Map<String, ThreadInfo> threads = new LinkedHashMap<>();
        for (JsonObject row : list("t3_thread_list", args, "threads")) {
            ThreadInfo thread = threadInfo(row);
            threads.put(thread.id(), thread);
        }
        return List.copyOf(threads.values());
    }

    private List<JsonObject> list(String tool, JsonObject args, String key) throws IOException {
        List<JsonObject> rows = new ArrayList<>();
        args.addProperty("limit", 100);
        int cursor = 0;
        while (true) {
            args.addProperty("cursor", cursor);
            JsonObject page = call(tool, args);
            for (JsonElement entry : array(page, key)) rows.add(object(entry, key));
            if (!present(page, "nextCursor")) return rows;
            int next = integer(page, "nextCursor");
            if (next <= cursor) throw new IOException(L10n.tr("api.pagination.repeat", "T3 returned a repeated page cursor for %s", tool));
            cursor = next;
        }
    }

    public Conversation read(String threadId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("view", "messages");
        // Unscoped MCP clients otherwise receive the oldest messages on long threads.
        args.addProperty("beforePosition", Integer.MAX_VALUE);
        args.addProperty("limit", 60);
        args.addProperty("maxCharsPerItem", 20000);
        args.addProperty("runLimit", 50); // Maximum summaries for runs represented in this page of 60 items.
        JsonObject result = call("t3_thread_read", args);
        JsonObject thread = object(result.get("thread"), "thread");
        List<Message> messages = new ArrayList<>();
        if (present(result, "earlierPosition")) {
            messages.add(new Message("t3mc-history", "system", "", 0, false, "", threadId, "", "", List.of(),
                    MessageNotice.EARLIER_HISTORY, false));
        }
        for (JsonElement entry : array(result, "items")) {
            JsonObject item = object(entry, "items");
            String text = string(item, "text");
            if (text.isEmpty()) text = string(item, "title");
            // T3's messages view omits attachment descriptors. An image-only user message
            // still has a messageId, which the composer can match against its send receipt.
            boolean missingAttachment = text.isEmpty() && "user_message".equals(string(item, "type"));
            if (missingAttachment) text = ATTACHMENT_PLACEHOLDER;
            var content = T3MessageImages.extract(text, item, environmentId.isEmpty() ? client.endpoint() : null, this, threadId);
            text = content.text();
            if (text.isEmpty() && content.images().isEmpty()) continue;
            // MCP-sent user messages can have createdBy="agent". Type identifies the chat role.
            String role = switch (string(item, "type")) {
                case "user_message" -> "user";
                case "assistant_message" -> "assistant";
                default -> "system";
            };
            messages.add(new Message(required(item, "itemId"), role, text, timestamp(item, "updatedAt"),
                    "running".equals(string(item, "status")), string(item, "messageId"),
                    present(item, "sourceThreadId") ? string(item, "sourceThreadId") : threadId,
                    string(item, "runId"), string(item, "phase"), content.images(),
                    missingAttachment ? MessageNotice.HIDDEN_ATTACHMENT : MessageNotice.NONE, flag(item, "textTruncated", false)));
        }
        return new Conversation(threadInfo(thread), List.copyOf(messages), integer(thread, "pendingRequestCount"), runTimings(result));
    }

    static Map<String,RunTiming> runTimings(JsonObject result) {
        if (!(result.get("recentRuns") instanceof JsonArray runs)) return Map.of();
        var timings = new LinkedHashMap<String,RunTiming>();
        for (var item : runs) {
            if (!(item instanceof JsonObject row)) continue;
            String id = string(row, "runId");
            if (id.isBlank()) continue;
            long start = timestamp(row, "startedAt");
            if (start == 0) start = timestamp(row, "requestedAt");
            long end = timestamp(row, "completedAt");
            if ((start > 0 || end > 0) && (end == 0 || end >= start))
                timings.put(id, new RunTiming(start, end));
        }
        return Map.copyOf(timings);
    }

    @Override public ChatImage.TextPage readImagePage(String threadId, String itemId, int offset) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("itemId", itemId);
        args.addProperty("textOffset", offset);
        args.addProperty("view", "messages");
        args.addProperty("limit", 1);
        args.addProperty("runLimit", 1);
        args.addProperty("maxCharsPerItem", 20_000);
        var items = array(call("t3_thread_read", args), "items");
        if (items.size() != 1) throw malformed("items");
        var item = object(items.get(0), "items[]");
        if (!itemId.equals(string(item, "itemId"))) throw malformed("items[].itemId");
        return new ChatImage.TextPage(string(item, "text"), present(item, "nextTextOffset") ? integer(item, "nextTextOffset") : -1);
    }

    public List<Request> questions(String threadId) throws IOException {
        List<Request> requests = new ArrayList<>();
        for (String id : questionIds(threadId)) {
            try {
                requests.add(question(threadId, id));
            } catch (IOException failure) {
                // A player can answer in T3 Code between our list and read calls.
                if (questionIds(threadId).contains(id)) throw failure;
            }
        }
        return List.copyOf(requests);
    }

    public List<String> questionIds(String threadId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("action", "list");
        List<String> ids = new ArrayList<>();
        for (JsonElement id : array(call("t3_pending_request", args), "requestIds")) {
            if (!id.isJsonPrimitive() || !id.getAsJsonPrimitive().isString()) throw malformed("requestIds");
            ids.add(id.getAsString());
        }
        return List.copyOf(ids);
    }

    /** The list omits pending approvals, which also move a thread out of the desktop Working section. */
    public boolean hasPendingRequest(String threadId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("view", "messages");
        args.addProperty("beforePosition", Integer.MAX_VALUE);
        args.addProperty("limit", 1);
        args.addProperty("maxCharsPerItem", 64);
        args.addProperty("runLimit", 1);
        return integer(object(call("t3_thread_read", args).get("thread"), "thread"), "pendingRequestCount") > 0;
    }

    public Request question(String threadId, String requestId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("action", "read");
        args.addProperty("requestId", requestId);
        JsonObject request = call("t3_pending_request", args);
        List<Question> questions = new ArrayList<>();
        for (JsonElement entry : array(request, "questions")) questions.add(parseQuestion(object(entry, "questions")));
        return new Request(required(request, "requestId"), List.copyOf(questions));
    }

    /** Outside-client HTTP connections on newer T3 servers support SEP-3415. Older servers use state polling. */
    public boolean enableEvents() {
        // Events have no linked environment selector. Monitor linked threads through their routed reads.
        if (!environmentId.isEmpty()) return false;
        eventCursors = Map.of();
        try {
            JsonObject discovery = object(client.eventRequest("server/discover", new JsonObject()), "server/discover");
            JsonObject capabilities = object(discovery.get("capabilities"), "capabilities");
            JsonObject extensions = object(capabilities.get("extensions"), "extensions");
            if (!extensions.has("io.modelcontextprotocol/events")) return false;
            Map<String, String> initial = new LinkedHashMap<>();
            for (String type : EVENT_TYPES) initial.put(type, required(eventPage(type, null), "cursor"));
            eventCursors = Map.copyOf(initial);
            return true;
        } catch (IOException unsupported) {
            return false;
        }
    }

    /** Reads occurrences since the previous successful poll, in chronological order across both feeds. */
    public EventBatch events() throws IOException {
        if (eventCursors.isEmpty()) throw new IOException(L10n.tr("api.events.unavailable", "The T3 event extension is unavailable."));
        List<Event> events = new ArrayList<>();
        Map<String, String> next = new LinkedHashMap<>(eventCursors);
        boolean truncated = false;
        for (String type : EVENT_TYPES) {
            String cursor = next.get(type);
            JsonObject page = eventPage(type, cursor);
            // T3 keeps at most 1,000 events and this page requests all of them. If the server
            // imposes a lower cap, the next poll continues from this cursor.
            next.put(type, required(page, "cursor"));
            truncated |= flag(page, "truncated", false);
            for (JsonElement item : array(page, "events")) {
                JsonObject event = object(object(item, "occurrence").get("data"), "data");
                if (!type.equals(required(event, "type"))) throw malformed("type");
                events.add(new Event(required(event, "id"), required(event, "projectId"), required(event, "threadId"),
                        type, string(event, "requestId"), string(event, "kind"), string(event, "runId"),
                        string(event, "outcome"), timestamp(event, "occurredAt")));
            }
        }
        // Move both cursors together so a failed second feed can be retried without losing the first.
        eventCursors = Map.copyOf(next);
        events.sort(Comparator.comparingLong(Event::occurredAtMillis));
        return new EventBatch(List.copyOf(events), truncated);
    }

    private JsonObject eventPage(String type, String cursor) throws IOException {
        JsonObject args = new JsonObject();
        args.addProperty("name", type);
        args.addProperty("cursor", cursor); // A JSON null starts the cursor at the current head.
        args.addProperty("maxEvents", 1000);
        return object(client.eventRequest("events/poll", args), "events/poll");
    }

    private static Question parseQuestion(JsonObject question) throws IOException {
        List<Option> choices = new ArrayList<>();
        for (JsonElement entry : array(question, "options")) {
            JsonObject option = object(entry, "options");
            String label = required(option, "label");
            String value = present(option, "value") ? string(option, "value") : label;
            choices.add(new Option(label, value, string(option, "description")));
        }
        return new Question(required(question, "id"), required(question, "question"), List.copyOf(choices),
                flag(question, "multiSelect", false), flag(question, "allowCustomAnswer", true),
                flag(question, "required", true), string(question, "initialAnswer"));
    }

    /** Values are plain text for single answers and arrays of selected option values for multi-select. */
    public void answer(String threadId, String requestId, Map<String, ?> answers) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("action", "respond");
        args.addProperty("requestId", requestId);
        JsonObject values = new JsonObject();
        for (var entry : answers.entrySet()) {
            if (entry.getValue() instanceof String text) {
                values.addProperty(entry.getKey(), text);
            } else if (entry.getValue() instanceof List<?> selected) {
                JsonArray choices = new JsonArray();
                for (Object value : selected) {
                    if (!(value instanceof String text)) throw new IOException(L10n.tr("api.answer.selection_text", "A selected answer must be text."));
                    choices.add(text);
                }
                values.add(entry.getKey(), choices);
            } else throw new IOException(L10n.tr("api.answer.invalid_type", "A question answer must be text or a list of selected options."));
        }
        args.add("answers", values);
        call("t3_pending_request", args);
    }

    public List<ModelProvider> models(String search) throws IOException {
        JsonObject args = new JsonObject();
        String query = search == null ? "" : search.strip();
        if (query.length() > 200) throw new IOException(L10n.tr("api.model.search_length", "Model searches can contain up to 200 characters."));
        if (!query.isEmpty()) args.addProperty("search", query);
        return T3Models.providers(call("orchestrator_capabilities", args));
    }

    public ModelSelection selection(String threadId) throws IOException {
        JsonObject reply = call("t3_thread_configure", threadArgs(threadId));
        return T3Models.selection(object(reply.get("modelSelection"), "modelSelection"));
    }

    public void configure(String threadId, ModelSelection selection) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.add("modelSelection", T3Models.encode(selection));
        call("t3_thread_configure", args);
    }

    public String launch(String projectId, String title, String message, String clientRequestId, ModelSelection selection) throws IOException {
        JsonObject args = new JsonObject();
        if (projectId.isEmpty()) args.addProperty("scratch", true);
        else args.addProperty("projectId", projectId);
        args.addProperty("title", title);
        if (!selection.modelId().isBlank()) args.add("modelSelection", T3Models.encode(selection));
        if (message != null && !message.isBlank()) args.addProperty("message", message);
        args.addProperty("clientRequestId", clientRequestId);
        return required(call("t3_thread_launch", args), "threadId");
    }

    public void send(String threadId, String message, String clientRequestId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("message", message);
        args.addProperty("mode", "auto");
        args.addProperty("clientRequestId", clientRequestId);
        call("t3_thread_send", args);
    }

    /** Returns the authoritative chat message ID for later transcript reconciliation. */
    public static final Set<String> ROUTED_IMAGE_TOOLS = Set.of("t3_attachment_prepare_upload", "t3_attachment_discard", "t3_thread_send_attachments");

    public String sendImages(String threadId, String caption, List<LocalImage> images) throws IOException {
        return sendImages(threadId, caption, images, bytes -> {});
    }

    public String sendImages(String threadId, String caption, List<LocalImage> images,
                             java.util.function.Consumer<byte[]> capture) throws IOException {
        if (!environmentId.isEmpty() && !client.routedTools().containsAll(ROUTED_IMAGE_TOOLS))
            throw new IOException(L10n.tr("api.image.direct_upload", "Connect directly to this environment to upload images."));
        return T3Attachments.send(this, threadId, caption, images, capture);
    }

    public void setSnooze(String threadId, Instant until) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("action", until == null ? "unsnooze" : "snooze");
        if (until != null) args.addProperty("snoozedUntil", until.toString());
        call("t3_thread_organize", args);
    }

    public void interrupt(String threadId, String clientRequestId) throws IOException {
        JsonObject args = threadArgs(threadId);
        args.addProperty("clientRequestId", clientRequestId);
        call("t3_thread_interrupt", args);
    }

    void upload(JsonObject staged, String mimeType, byte[] bytes) throws IOException {
        String absolute = string(staged, "uploadUrl");
        // A peer's relative path belongs to the peer. Only an explicit same-origin upload address
        // from the routed prepare operation can direct these bytes through the current MCP host.
        if (!environmentId.isEmpty() && absolute.isEmpty())
            throw new IOException(L10n.tr("api.image.no_routed_url", "The linked environment did not provide a routed upload URL. Connect directly to upload images."));
        client.upload(absolute, environmentId.isEmpty() ? string(staged, "relativeUrl") : "", mimeType, bytes);
    }

    JsonObject call(String name, JsonObject args) throws IOException {
        if (!environmentId.isEmpty()) {
            if (!client.routedTools().contains(name)) throw new IOException(L10n.tr("api.route.unsupported", "This T3 server cannot route %s to a linked environment.", name));
            args.addProperty("environmentId", environmentId);
        }
        return object(client.call(name, args), name);
    }

    private static ThreadInfo threadInfo(JsonObject row) throws IOException {
        return new ThreadInfo(required(row, "threadId"), required(row, "projectId"), string(row, "title"),
                required(row, "status"), string(row, "latestRunId"), timestamp(row, "updatedAt"), flag(row, "settled", false),
                string(row, "providerInstanceId"), string(row, "model"), flag(row, "pinned", false), flag(row, "snoozed", false),
                timestamp(row, "snoozedUntil"));
    }

    private static JsonObject threadArgs(String id) {
        JsonObject args = new JsonObject();
        args.addProperty("threadId", id);
        return args;
    }

    static boolean present(JsonObject row, String key) {
        return row.has(key) && !row.get(key).isJsonNull();
    }

    static String string(JsonObject row, String key) {
        return present(row, key) && row.get(key).isJsonPrimitive() ? row.get(key).getAsString() : "";
    }

    static String required(JsonObject row, String key) throws IOException {
        String value = string(row, key);
        if (value.isEmpty()) throw malformed(key);
        return value;
    }

    private static int integer(JsonObject row, String key) throws IOException {
        try {
            return Integer.parseInt(string(row, key));
        } catch (NumberFormatException ignored) {
            throw malformed(key);
        }
    }

    private static long timestamp(JsonObject row, String key) {
        try {
            return Instant.parse(string(row, key)).toEpochMilli();
        } catch (DateTimeParseException | ArithmeticException ignored) {
            return 0;
        }
    }

    static boolean flag(JsonObject row, String key, boolean fallback) {
        return present(row, key) ? Boolean.parseBoolean(string(row, key)) : fallback;
    }

    static JsonObject object(JsonElement value, String key) throws IOException {
        if (value == null || !value.isJsonObject()) throw malformed(key);
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonObject row, String key) throws IOException {
        if (!row.has(key) || !row.get(key).isJsonArray()) throw malformed(key);
        return row.getAsJsonArray(key);
    }

    static IOException malformed(String key) {
        return new IOException(L10n.tr("api.response.invalid", "T3 returned an unexpected value for %s. Check the MCP server version.", key));
    }
}
