package dev.craftcode.api;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.craftcode.mcp.McpClient;
import dev.craftcode.ui.model.ChatMessage;
import dev.craftcode.ui.model.MessageRole;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;

class T3ActivityTest {
    @Test
    void loadsEveryPageOfTheSelectedTurnAndContinuesTruncatedNarrative() throws Exception {
        var cursors = new CopyOnWriteArrayList<Integer>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                var args = request.getAsJsonObject("params").getAsJsonObject("arguments");
                String page;
                if (!args.get("threadId").getAsString().equals("thread") || !args.get("view").getAsString().equals("activity")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                if (args.has("itemId") && args.get("itemId").getAsString().equals("think") && args.get("textOffset").getAsInt() == 5) {
                    page = """
                            {"items":[{"itemId":"think","text":"half"}]}
                            """;
                } else {
                    int before = args.get("beforePosition").getAsInt();
                    cursors.add(before);
                    page = switch (before) {
                        case 12 -> """
                                {"earlierPosition":8,"items":[
                                  {"position":8,"itemId":"cmd","runId":"r","type":"command_execution","text":"$ ls"},
                                  {"position":9,"itemId":"reply","runId":"r","type":"assistant_message","text":"Done"},
                                  {"position":10,"itemId":"check","runId":"r","type":"checkpoint","text":"[]"},
                                  {"position":11,"itemId":"web","runId":"r","type":"web_search","text":""}]}
                                """;
                        case 8 -> """
                                {"earlierPosition":6,"items":[
                                  {"position":6,"itemId":"think","runId":"r","type":"reasoning","text":"Last ","nextTextOffset":5},
                                  {"position":7,"itemId":"update","runId":"r","type":"assistant_message","text":"Checked files"}]}
                                """;
                        case 6 -> """
                                {"earlierPosition":2,"items":[
                                  {"position":2,"itemId":"old","runId":"old-run","type":"reasoning","text":"Older task"},
                                  {"position":3,"itemId":"question","runId":"r","type":"user_message","text":"Build"},
                                  {"position":4,"itemId":"early","runId":"r","type":"reasoning","text":"First step"}]}
                                """;
                        default -> "{\"items\":[]}";
                    };
                }
                var result = new JsonObject();
                result.add("structuredContent", JsonParser.parseString(page));
                var response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                response.add("id", request.get("id"));
                response.add("result", result);
                byte[] bytes = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var client = McpClient.http("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "")) {
            var rows = new T3Api(client).readWork("thread", "r", -1, 12);
            assertEquals(List.of(12, 8, 6), cursors);
            assertEquals(List.of("First step", "Last half", "Checked files", "$ ls", "Done", ""),
                    rows.stream().map(ChatMessage::text).toList());
            assertEquals(MessageRole.TOOL, rows.get(3).role());
            assertEquals("web_search", rows.getLast().phase());
        } finally {
            server.stop(0);
        }
    }
}
