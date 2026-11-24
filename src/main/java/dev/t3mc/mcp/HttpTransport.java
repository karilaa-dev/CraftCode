package dev.t3mc.mcp;

import static dev.t3mc.L10n.tr;

import com.google.gson.JsonObject;
import dev.t3mc.auth.ConnectionAddress;
import static dev.t3mc.mcp.RpcMessage.MAX_BYTES;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;

final class HttpTransport implements AutoCloseable {
    static final String EVENT_PROTOCOL = "2026-07-28";
    private final URI endpoint;
    private final HttpClient client;
    private volatile String bearer;
    private volatile String version;
    private volatile String session;
    private volatile boolean closed;

    HttpTransport(ConnectionAddress address, String bearerToken) throws IOException {
        endpoint = URI.create(address.endpoint());
        setBearer(bearerToken);
        // Redirecting an authenticated POST could disclose a bearer token or session ID.
        client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    URI endpoint() { return endpoint; }

    void setBearer(String token) throws IOException {
        String trimmed = token == null ? "" : token.strip();
        if (trimmed.codePoints().anyMatch(c -> c < 33 || c > 126)) throw new IOException(tr("auth.token_invalid", "Invalid MCP bearer token."));
        bearer = trimmed;
    }

    private boolean hasBearer() { return !bearer.isEmpty(); }

    void setVersion(String version) { this.version = version; }

    String redact(String text) {
        String token = bearer;
        String sessionId = session;
        if (token != null && !token.isEmpty()) text = text.replace(token, "[redacted]");
        if (sessionId != null && !sessionId.isEmpty()) text = text.replace(sessionId, "[redacted]");
        return text;
    }

    private HttpRequest.Builder headers(JsonObject message) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofSeconds(40))
                .header("Accept", "application/json, text/event-stream");
        if (hasBearer()) builder.header("Authorization", "Bearer " + bearer);
        String method = message == null ? "" : McpClient.string(message, "method");
        if (method.equals("events/poll") || method.equals("server/discover")) {
            builder.header("MCP-Method", method).header("MCP-Protocol-Version", EVENT_PROTOCOL);
            String name = McpClient.string(message.getAsJsonObject("params"), "name");
            if (!name.isEmpty()) builder.header("MCP-Name", name);
        } else {
            if (session != null) builder.header("Mcp-Session-Id", session);
            if (version != null) builder.header("MCP-Protocol-Version", version);
        }
        return builder;
    }

    private HttpResponse<InputStream> post(JsonObject obj) throws IOException {
        if (closed) throw new IOException(tr("net.closed", "MCP connection is closed."));
        HttpRequest request = headers(obj).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(obj.toString())).build();
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tr("net.interrupted", "MCP request interrupted."), e);
        }
    }

    JsonObject exchange(JsonObject message) throws IOException {
        HttpResponse<InputStream> response = post(message);
        try (BufferedInputStream body = new BufferedInputStream(response.body())) {
            int status = response.statusCode();
            if (status == 401) throw new Unauthorized();
            if (status == 403) throw new IOException(tr("auth.access_denied", "Access denied by T3 Code. Check your MCP permissions."));
            if (status < 200 || status >= 300) throw new IOException(tr("net.http", "MCP server returned HTTP %s.", status));
            if ("initialize".equals(McpClient.string(message, "method"))) {
                String returnedSession = response.headers().firstValue("Mcp-Session-Id").orElse(null);
                if (returnedSession != null && (returnedSession.length() > 8192 ||
                        returnedSession.chars().anyMatch(c -> c < 33 || c > 126))) {
                    throw new IOException(tr("net.session_header", "Invalid MCP session header."));
                }
                session = returnedSession;
            }
            if (!message.has("id")) return null;
            String mime = response.headers().firstValue("Content-Type").orElse("")
                    .split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            if (mime.equals("text/event-stream")) return readEvents(body, message);
            if (!mime.equals("application/json")) throw new IOException(tr("net.not_mcp", "The URL did not return an MCP response."));
            byte[] data = body.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) throw new IOException(tr("net.response_size", "MCP response exceeds the size limit."));
            JsonObject result = RpcMessage.parse(new String(data, StandardCharsets.UTF_8));
            if (!RpcMessage.matches(result, message)) throw new IOException(tr("net.response_id", "Unexpected MCP response ID."));
            return result;
        }
    }

    private JsonObject readEvents(BufferedInputStream body, JsonObject request) throws IOException {
        StringBuilder event = new StringBuilder();
        String line;
        while ((line = RpcMessage.line(body)) != null) {
            if (line.startsWith("data:") || line.equals("data")) {
                int start = line.startsWith("data: ") ? 6 : Math.min(5, line.length());
                if ((long) event.length() + line.length() > MAX_BYTES) throw new IOException(tr("net.event_size", "MCP event exceeds the size limit."));
                event.append(line, start, line.length()).append('\n');
            } else if (line.isEmpty() && !event.isEmpty()) {
                String data = event.toString().strip();
                event.setLength(0);
                if (data.isEmpty()) continue;
                JsonObject received = RpcMessage.parse(data);
                if (RpcMessage.matches(received, request)) return received;
                JsonObject reply = RpcMessage.serverReply(received);
                if (reply != null) sendReply(reply);
            }
        }
        throw new IOException(tr("net.stream_closed", "The MCP event stream closed before the server replied."));
    }

    private void sendReply(JsonObject reply) throws IOException {
        HttpResponse<InputStream> response = post(reply);
        try (InputStream ignored = response.body()) {
            if (response.statusCode() / 100 != 2) throw new IOException(tr("net.protocol_reply", "MCP server rejected a protocol response."));
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        if (session == null) {
            client.shutdownNow();
            return;
        }
        try {
            HttpRequest request = headers(null).timeout(Duration.ofSeconds(2)).DELETE().build();
            client.sendAsync(request, HttpResponse.BodyHandlers.discarding())
                    .orTimeout(2, java.util.concurrent.TimeUnit.SECONDS)
                    .whenComplete((result, error) -> client.shutdownNow());
        } catch (RuntimeException error) {
            client.shutdownNow();
        }
    }

    static final class Unauthorized extends IOException {
        Unauthorized() {
            super(tr("auth.reconnect", "T3 Code requires sign-in. Reconnect from connection settings."));
        }
    }
}
