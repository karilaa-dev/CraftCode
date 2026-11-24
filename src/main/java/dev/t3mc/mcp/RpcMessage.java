package dev.t3mc.mcp;

import static dev.t3mc.L10n.tr;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

final class RpcMessage {
    private RpcMessage() {}
    static final int MAX_BYTES = 32 * 1024 * 1024;

    static JsonObject parse(String payload) throws IOException {
        try {
            JsonObject message = McpClient.object(JsonParser.parseString(payload), tr("net.message_kind", "message"));
            if (!"2.0".equals(McpClient.string(message, "jsonrpc"))) throw new IOException(tr("net.rpc_version", "Invalid JSON-RPC version."));
            return message;
        } catch (RuntimeException e) {
            throw new IOException(tr("net.invalid_json", "The MCP server sent invalid JSON."), e);
        }
    }

    static boolean matches(JsonObject response, JsonObject request) {
        return !response.has("method") && request.has("id") && request.get("id").equals(response.get("id"));
    }

    /** The client only advertises base protocol support. Answer pings and decline unknown requests. */
    static JsonObject serverReply(JsonObject message) {
        JsonElement id = message.get("id");
        if (id == null || !message.has("method")) return null;
        JsonObject reply = new JsonObject();
        reply.addProperty("jsonrpc", "2.0");
        reply.add("id", id);
        if ("ping".equals(McpClient.string(message, "method"))) {
            reply.add("result", new JsonObject());
        } else {
            JsonObject error = new JsonObject();
            error.addProperty("code", -32601);
            error.addProperty("message", "Method not supported by this client");
            reply.add("error", error);
        }
        return reply;
    }

    /** Bounded UTF-8 framing for SSE. */
    static String line(BufferedInputStream input) throws IOException {
        ByteArrayOutputStream data = new ByteArrayOutputStream(256);
        int c;
        while ((c = input.read()) != -1 && c != '\n' && c != '\r') {
            if (data.size() >= MAX_BYTES) throw new IOException(tr("net.response_size", "MCP response exceeds the size limit."));
            data.write(c);
        }
        if (c == '\r') {
            input.mark(1);
            if (input.read() != '\n') input.reset();
        }
        if (c == -1 && data.size() == 0) return null;
        return data.toString(StandardCharsets.UTF_8);
    }
}
