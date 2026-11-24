package dev.t3mc.mcp;

import static dev.t3mc.L10n.tr;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.t3mc.auth.ConnectionAddress;
import dev.t3mc.auth.T3OAuth;
import dev.t3mc.ui.model.LocalImage;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/** Blocking MCP operations. Call from a worker thread. Closing is safe from another thread. */
public final class McpClient implements Closeable {
    private static final String PROTOCOL = "2025-11-25";
    private static final String MOD_VERSION = "0.8.4";
    private final HttpTransport wire;
    private final boolean interactive;
    private volatile String pendingPairingCode;
    private final Consumer<String> onAuthorized;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(
            Thread.ofVirtual().name("t3mc-mcp").factory());
    private volatile boolean closed;
    private long sequence;
    private volatile Set<String> routedTools = Set.of();
    public Set<String> routedTools() { return routedTools; }
    public URI endpoint() { return wire.endpoint(); }

    private McpClient(HttpTransport wire, Consumer<String> onAuthorized, String pairingCode, boolean interactive) {
        this.wire = wire;
        this.interactive = interactive;
        this.onAuthorized = java.util.Objects.requireNonNull(onAuthorized);
        this.pendingPairingCode = pairingCode;
    }

    /** A supplied invite or code starts a new grant. Otherwise a saved grant can fall back to browser sign-in. */
    public static McpClient connect(String url, String savedGrant, String optionalCode, Consumer<String> onAuthorized) throws IOException {
        ConnectionAddress address = T3OAuth.connectionAddress(url);
        String code = address.resolveCode(optionalCode);
        return new McpClient(new HttpTransport(address, code.isEmpty() ? savedGrant : ""), onAuthorized,
                code.isEmpty() ? null : code, true);
    }

    /** A linked workspace uses its primary profile's credentials. It cannot begin a second sign-in. */
    public static McpClient forLinkedEnvironment(String endpoint, String grant) throws IOException {
        ConnectionAddress address = T3OAuth.connectionAddress(endpoint);
        return new McpClient(new HttpTransport(address, grant), unused -> {}, null, false);
    }

    public static McpClient http(String url, String bearerToken) throws IOException {
        return connect(url, bearerToken, "", token -> {});
    }

    public synchronized void upload(String uploadUrl, String relativeUrl, String mimeType, byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0 || bytes.length > LocalImage.MAX_BYTES)
            throw new IOException(tr("net.upload_size", "Choose a nonempty image of at most 10 MiB."));
        if (mimeType == null || !Set.of("image/png", "image/jpeg", "image/webp", "image/gif").contains(mimeType))
            throw new IOException(tr("net.upload_type", "Unsupported T3 image type."));
        URI endpoint = wire.endpoint();
        URI destination = AttachmentUpload.destination(endpoint, uploadUrl, relativeUrl);
        run(() -> {
            AttachmentUpload.post(destination, mimeType, bytes);
            return null;
        }, 45);
    }

    public synchronized Set<String> initialize() throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("protocolVersion", PROTOCOL);
        params.add("capabilities", new JsonObject());
        JsonObject client = new JsonObject();
        client.addProperty("name", "t3mc");
        client.addProperty("title", "T3 Code for Minecraft");
        client.addProperty("version", MOD_VERSION);
        params.add("clientInfo", client);
        JsonElement handshake;
        if (closed) throw new IOException(tr("net.closed", "MCP connection is closed."));
        String freshToken = null;
        String pairingCode = pendingPairingCode;
        pendingPairingCode = null;
        if (pairingCode != null) {
            freshToken = T3OAuth.pair(wire.endpoint(), pairingCode);
            wire.setBearer(freshToken);
        }
        try {
            handshake = request("initialize", params, 40);
        } catch (HttpTransport.Unauthorized e) {
            if (closed || !interactive) throw e;
            if (pairingCode != null) throw new IOException(tr("auth.pairing_rejected", "The environment rejected the new pairing login. Generate a fresh connection code."));
            freshToken = T3OAuth.authorize(wire.endpoint());
            wire.setBearer(freshToken);
            handshake = request("initialize", params, 40);
        }
        JsonObject hello = object(handshake, "initialize");
        String version = string(hello, "protocolVersion");
        if (!Set.of(PROTOCOL, "2025-06-18", "2025-03-26", "2024-11-05").contains(version)) {
            throw new IOException(tr("net.protocol_version", "The MCP server requested an unsupported protocol version."));
        }
        wire.setVersion(version);
        exchange(envelope("notifications/initialized", null), 40);
        Set<String> names = new LinkedHashSet<>();
        Set<String> routable = new HashSet<>();
        Set<String> seenCursors = new HashSet<>();
        JsonObject page = new JsonObject();
        while (true) {
            JsonObject listing = object(request("tools/list", page, 40), "tools/list");
            if (!(listing.get("tools") instanceof JsonArray tools)) throw new IOException(tr("net.tools_missing", "Missing MCP tool list."));
            for (JsonElement tool : tools) {
                JsonObject description = object(tool, tr("net.tool_descriptor", "tool description"));
                String name = string(description, "name");
                if (description.get("inputSchema") instanceof JsonObject schema
                        && schema.get("properties") instanceof JsonObject fields && fields.has("environmentId"))
                    routable.add(name);
                if (name.isEmpty()) throw new IOException(tr("net.tool_name", "Invalid MCP tool name."));
                names.add(name);
            }
            String cursor = string(listing, "nextCursor");
            if (cursor.isEmpty()) {
                if (!names.containsAll(Set.of("t3_project_list", "t3_thread_list", "t3_thread_read")))
                    throw new IOException(tr("net.workspace_tools", "This MCP server does not expose the T3 Code workspace tools."));
                routedTools = Set.copyOf(routable);
                if (freshToken != null && !closed) onAuthorized.accept(freshToken);
                return Set.copyOf(names);
            }
            if (!seenCursors.add(cursor) || seenCursors.size() > 1000) throw new IOException(tr("net.tool_pagination", "Invalid MCP tool pagination."));
            page.addProperty("cursor", cursor);
        }
    }

    /** Stateless MCP Events calls coexist with the negotiated tool session on recent T3 servers. */
    public synchronized JsonElement eventRequest(String method, JsonObject params) throws IOException {
        if (!method.equals("server/discover") && !method.equals("events/poll")) throw new IOException(tr("net.event_method", "Unsupported event method."));
        JsonObject eventParams = params == null ? new JsonObject() : params.deepCopy();
        JsonObject meta = new JsonObject();
        meta.addProperty("io.modelcontextprotocol/protocolVersion", HttpTransport.EVENT_PROTOCOL);
        JsonObject extensions = new JsonObject();
        extensions.add("io.modelcontextprotocol/events", new JsonObject());
        JsonObject capabilities = new JsonObject();
        capabilities.add("extensions", extensions);
        meta.add("io.modelcontextprotocol/clientCapabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", "t3mc");
        info.addProperty("version", MOD_VERSION);
        meta.add("io.modelcontextprotocol/clientInfo", info);
        eventParams.add("_meta", meta);
        return request(method, eventParams, 40);
    }

    public synchronized JsonElement call(String tool, JsonObject args) throws IOException {
        JsonObject params = new JsonObject();
        params.addProperty("name", tool);
        params.add("arguments", args == null ? new JsonObject() : args);
        JsonObject result = object(request("tools/call", params, 40), "tools/call");
        JsonElement structured = result.get("structuredContent");
        boolean failed = result.get("isError") instanceof JsonPrimitive flag && flag.isBoolean() && flag.getAsBoolean();
        if (!failed && structured != null && !structured.isJsonNull()) return structured;

        StringBuilder text = new StringBuilder();
        if (result.get("content") instanceof JsonArray blocks) {
            for (JsonElement entry : blocks) {
                if (entry instanceof JsonObject block && "text".equals(string(block, "type"))) {
                    if (!text.isEmpty()) text.append('\n');
                    text.append(string(block, "text"));
                }
            }
        }
        if (failed) throw new ToolRejected(failureMessage(tr("net.request_rejected", "T3 Code rejected the request"), text.toString()));
        if (text.isEmpty()) return JsonNull.INSTANCE;
        try {
            return JsonParser.parseString(text.toString());
        } catch (RuntimeException invalidJson) {
            return new JsonPrimitive(text.toString());
        }
    }

    private JsonElement request(String method, JsonObject params, int timeoutSeconds) throws IOException {
        JsonObject message = envelope(method, params);
        message.addProperty("id", ++sequence);
        JsonObject response = exchange(message, timeoutSeconds);
        if (response == null) throw new IOException(tr("net.no_response", "The MCP server did not return a response."));
        if (response.get("error") instanceof JsonObject error) {
            throw new IOException(failureMessage(tr("net.method_failed", "MCP %s failed", method), string(error, "message")));
        }
        if (!response.has("result")) throw new IOException(tr("net.invalid_result", "Invalid MCP response to %s.", method));
        return response.get("result");
    }

    /** A definite tool rejection. A disconnected or lost response has an unknown outcome. */
    public static final class ToolRejected extends IOException {
        private ToolRejected(String message) { super(message); }
    }

    private String failureMessage(String prefix, String reason) {
        String detail = wire.redact(reason).strip().replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", "");
        if (detail.length() > 320) detail = detail.substring(0, 320) + "…";
        return detail.isEmpty() ? prefix + "." : prefix + ": " + detail;
    }

    private JsonObject exchange(JsonObject message, int timeoutSeconds) throws IOException {
        return run(() -> wire.exchange(message), timeoutSeconds);
    }

    private <T> T run(Callable<T> operation, int timeoutSeconds) throws IOException {
        if (closed) throw new IOException(tr("net.closed", "MCP connection is closed."));
        Future<T> work;
        try {
            work = worker.submit(operation);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            throw new IOException(tr("net.closed", "MCP connection is closed."), e);
        }
        try {
            return work.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            work.cancel(true);
            close();
            throw new IOException(tr("net.timeout", "MCP request timed out. Check the thread before resending."), e);
        } catch (InterruptedException e) {
            work.cancel(true);
            close();
            Thread.currentThread().interrupt();
            throw new IOException(tr("net.interrupted", "MCP request interrupted."), e);
        } catch (CancellationException e) {
            throw new IOException(tr("net.cancelled", "MCP request cancelled."), e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof IOException io) throw io;
            throw new IOException(tr("net.invalid_response", "Invalid response from MCP server."), e.getCause());
        }
    }

    private static JsonObject envelope(String method, JsonObject params) {
        JsonObject envelope = new JsonObject();
        envelope.addProperty("jsonrpc", "2.0");
        envelope.addProperty("method", method);
        if (params != null) envelope.add("params", params);
        return envelope;
    }

    static JsonObject object(JsonElement value, String context) throws IOException {
        if (value instanceof JsonObject object) return object;
        throw new IOException(tr("net.invalid_object", "Invalid MCP %s.", context));
    }

    static String string(JsonObject obj, String key) {
        return obj.get(key) instanceof JsonPrimitive value ? value.getAsString() : "";
    }

    @Override
    public void close() {
        closed = true;
        pendingPairingCode = null;
        worker.shutdownNow();
        wire.close();
    }
}
