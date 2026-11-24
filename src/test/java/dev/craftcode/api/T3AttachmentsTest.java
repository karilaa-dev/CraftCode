package dev.craftcode.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.craftcode.mcp.McpClient;
import dev.craftcode.ui.model.LocalImage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class T3AttachmentsTest {
    @TempDir Path dir;

    @Test void distinguishesUserTextFromLocalizedHistoryAndAttachmentNotices() throws Exception {
        try (var fixture = new Fixture("read", new byte[0])) {
            var messages = fixture.api.read("t").messages();
            assertEquals(T3Api.MessageNotice.EARLIER_HISTORY, messages.get(0).notice());
            assertEquals("[Attachment]", messages.get(1).displayText(false));
            assertEquals("[Attachment]", messages.get(1).displayText(true));
            assertEquals(T3Api.MessageNotice.HIDDEN_ATTACHMENT, messages.get(2).notice());
            assertEquals("", messages.get(2).displayText(true));
            assertFalse(messages.get(2).displayText(false).isBlank());
            assertEquals("Текст агента", messages.get(3).text());
            assertTrue(messages.get(3).displayText(false).startsWith("Текст агента\n"));
        }
    }

    @Test void sendsAnImageOnlyMessageAndReturnsItsReceipt() throws Exception {
        var image = sample();
        try (var fixture = new Fixture("accept", image.readBytes())) {
            assertEquals("message-1", fixture.api.sendImages("t", "", List.of(image)));
            assertEquals(List.of("prepare", "upload", "send"), fixture.calls);
            assertFalse(fixture.sendArgs.has("message"));
            var attachment = fixture.sendArgs.getAsJsonArray("attachments").get(0).getAsJsonObject();
            assertEquals("pending_1", attachment.get("id").getAsString());
            assertEquals(image.sizeBytes(), attachment.get("sizeBytes").getAsLong());
        }
    }

    @Test void discardsAllStagedImagesIfAnUploadFails() throws Exception {
        var image = sample();
        try (var fixture = new Fixture("fail-second-upload", image.readBytes())) {
            assertThrows(IOException.class, () -> fixture.api.sendImages("t", "Two images", List.of(image, image)));
            assertEquals(List.of("prepare", "upload", "prepare", "upload", "discard:pending_1", "discard:pending_2"), fixture.calls);
        }
    }

    @Test void discardsARejectedSendButLeavesAnUnacknowledgedSendAlone() throws Exception {
        var image = sample();
        try (var fixture = new Fixture("reject", image.readBytes())) {
            assertThrows(McpClient.ToolRejected.class, () -> fixture.api.sendImages("t", "Caption", List.of(image)));
            assertEquals("Caption", fixture.sendArgs.get("message").getAsString());
            assertEquals(List.of("prepare", "upload", "send", "discard:pending_1"), fixture.calls);
        }
        try (var fixture = new Fixture("lost", image.readBytes())) {
            var error = assertThrows(IOException.class, () -> fixture.api.sendImages("t", "", List.of(image)));
            assertTrue(error.getMessage().contains("unconfirmed"));
            assertEquals(List.of("prepare", "upload", "send"), fixture.calls);
        }
    }

    @Test void linkedUploadsRequireAllThreeRoutedToolsAndAnExplicitSameOriginUploadAddress() throws Exception {
        var image = sample();
        for (String scenario : List.of("remote-success", "remote-relative", "remote-foreign", "remote-unscoped")) {
            try (var fixture = new Fixture(scenario, image.readBytes())) {
                fixture.client.initialize();
                var peer = new T3Api(fixture.client, "peer");
                if (scenario.equals("remote-success")) {
                    assertEquals("message-1", peer.sendImages("t", "", List.of(image)));
                    assertEquals(List.of("prepare", "upload", "send"), fixture.calls);
                    assertEquals(List.of("peer", "peer"), fixture.targets);
                } else {
                    assertThrows(IOException.class, () -> peer.sendImages("t", "", List.of(image)));
                    if (scenario.equals("remote-unscoped")) assertTrue(fixture.calls.isEmpty());
                    else {
                        assertEquals(List.of("prepare", "discard:pending_1"), fixture.calls);
                        assertEquals(List.of("peer", "peer"), fixture.targets);
                    }
                }
            }
        }
    }

    private LocalImage sample() throws IOException {
        Path png = dir.resolve("test.png");
        ImageIO.write(new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", png.toFile());
        return LocalImage.open(png);
    }

    private static final class Fixture implements AutoCloseable {
        final List<String> calls = new ArrayList<>();
        final List<String> targets = new ArrayList<>();
        final HttpServer server;
        final McpClient client;
        final T3Api api;
        int uploads;
        JsonObject sendArgs;

        Fixture(String scenario, byte[] png) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String origin = "http://127.0.0.1:" + server.getAddress().getPort();
            server.createContext("/api/attachments/upload/", exchange -> {
                try (exchange) {
                    calls.add("upload");
                    boolean ok = exchange.getRequestMethod().equals("POST")
                            && Arrays.equals(png, exchange.getRequestBody().readAllBytes())
                            && exchange.getRequestHeaders().getFirst("Authorization") == null
                            && !(scenario.equals("fail-second-upload") && uploads == 2);
                    exchange.sendResponseHeaders(ok ? 204 : 400, -1);
                }
            });
            server.createContext("/mcp", exchange -> {
                try (exchange) {
                    JsonObject req = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
                    String method = req.get("method").getAsString();
                    if (!method.equals("tools/call")) {
                        if (!req.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                        JsonObject rpc = new JsonObject();
                        rpc.addProperty("jsonrpc", "2.0");
                        rpc.add("id", req.get("id"));
                        JsonObject result = new JsonObject();
                        if (method.equals("initialize")) result.addProperty("protocolVersion", "2025-11-25");
                        else {
                            var list = new com.google.gson.JsonArray();
                            for (String name : List.of("t3_project_list", "t3_thread_list", "t3_thread_read",
                                    "t3_attachment_prepare_upload", "t3_attachment_discard", "t3_thread_send_attachments")) {
                                JsonObject tool = new JsonObject();
                                tool.addProperty("name", name);
                                if (!name.equals("t3_attachment_prepare_upload") || !scenario.equals("remote-unscoped"))
                                    tool.add("inputSchema", JsonParser.parseString("{\"properties\":{\"environmentId\":{\"type\":\"string\"}}}"));
                                list.add(tool);
                            }
                            result.add("tools", list);
                        }
                        rpc.add("result", result);
                        json(exchange, rpc);
                        return;
                    }
                    JsonObject params = req.getAsJsonObject("params");
                    JsonObject args = params.getAsJsonObject("arguments");
                    targets.add(args.has("environmentId") ? args.get("environmentId").getAsString() : "");
                    JsonObject result = new JsonObject();
                    JsonObject data = new JsonObject();
                    switch (params.get("name").getAsString()) {
                        case "t3_thread_read" -> data = JsonParser.parseString("""
                            {"thread":{"threadId":"t","projectId":"p","status":"idle","pendingRequestCount":0},
                             "earlierPosition":2,
                             "items":[{"itemId":"1","type":"user_message","text":"[Attachment]"},
                                      {"itemId":"2","type":"user_message","text":""},
                                      {"itemId":"3","type":"assistant_message","text":"Текст агента","textTruncated":true}]}
                            """).getAsJsonObject();
                        case "t3_attachment_prepare_upload" -> {
                            calls.add("prepare");
                            uploads++;
                            data.addProperty("attachmentId", "pending_" + uploads);
                            data.addProperty("relativeUrl", "/api/attachments/upload/" + uploads);
                            if (scenario.equals("remote-success")) data.addProperty("uploadUrl", origin + "/api/attachments/upload/" + uploads);
                            if (scenario.equals("remote-foreign")) data.addProperty("uploadUrl", "https://other.example/upload");
                        }
                        case "t3_thread_send_attachments" -> {
                            calls.add("send");
                            sendArgs = args;
                            if (scenario.equals("lost")) {
                                exchange.sendResponseHeaders(503, -1);
                                return;
                            }
                            if (scenario.equals("reject")) result.addProperty("isError", true);
                            else data.addProperty("messageId", "message-1");
                        }
                        case "t3_attachment_discard" -> calls.add("discard:" + args.get("attachmentId").getAsString());
                        default -> throw new AssertionError(params.get("name"));
                    }
                    result.add("structuredContent", data);
                    JsonObject rpc = new JsonObject();
                    rpc.addProperty("jsonrpc", "2.0");
                    rpc.add("id", req.get("id"));
                    rpc.add("result", result);
                    json(exchange, rpc);
                }
            });
            server.start();
            client = McpClient.http(origin + "/mcp", "test-token");
            api = new T3Api(client);
        }

        private static void json(HttpExchange exchange, JsonObject json) throws IOException {
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
        }

        @Override public void close() {
            client.close();
            server.stop(0);
        }
    }
}
