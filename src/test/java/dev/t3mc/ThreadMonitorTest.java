package dev.t3mc;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import dev.t3mc.api.T3Api;
import dev.t3mc.mcp.McpClient;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ThreadMonitorTest {
    @Test void observesSuccessiveQuestionsWhileRunningAndDistinguishesSubsequentRuns() throws Exception {
        var requestIds = new AtomicReference<>(List.of("before-connection"));
        var reviewCount = new AtomicInteger();
        var readCalls = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            try (exchange) {
                var request = JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8)).getAsJsonObject();
                String requestedTool = request.getAsJsonObject("params").get("name").getAsString();
                var pending = new JsonObject();
                if (requestedTool.equals("t3_thread_read")) {
                    readCalls.incrementAndGet();
                    var thread = new JsonObject();
                    thread.addProperty("pendingRequestCount", reviewCount.get() + requestIds.get().size());
                    pending.add("thread", thread);
                } else if (requestedTool.equals("t3_pending_request")) {
                    var ids = new JsonArray();
                    requestIds.get().forEach(ids::add);
                    pending.add("requestIds", ids);
                } else {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                var result = new JsonObject();
                result.add("structuredContent", pending);
                var response = new JsonObject();
                response.addProperty("jsonrpc", "2.0");
                response.add("id", request.get("id"));
                response.add("result", result);
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
        try (var wire = McpClient.http("http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "")) {
            List<String> notices = new ArrayList<>();
            var monitor = new ThreadMonitor(new T3Api(wire), (title, body) -> notices.add(title), true, false);
            var active = thread("running", "one");
            monitor.update(active);
            assertTrue(monitor.hasQuestions("thread"));
            assertTrue(notices.isEmpty());

            requestIds.set(List.of("question-two"));
            monitor.update(active);
            monitor.update(active);
            requestIds.set(List.of("question-three"));
            monitor.update(active);
            requestIds.set(List.of());
            monitor.update(thread("completed", "one"));
            monitor.update(thread("completed", "one"));
            monitor.update(thread("completed", "two"));
            monitor.update(thread("failed", "three"));
            monitor.update(thread("failed", "three"));
            assertFalse(monitor.hasQuestions("thread"));
            assertEquals(List.of("Agent has a question", "Agent has a question", "Agent finished",
                    "Agent finished", "Agent error"), notices);

            var review = thread("running", "approval-run");
            readCalls.set(0);
            monitor.update(review);
            assertFalse(monitor.hasPendingInput("thread"));
            reviewCount.set(1);
            monitor.update(review);
            assertFalse(monitor.hasQuestions("thread"));
            assertTrue(monitor.hasPendingInput("thread"));
            reviewCount.set(0);
            monitor.update(review);
            assertFalse(monitor.hasPendingInput("thread"));
            assertEquals(3, readCalls.get());
        } finally { server.stop(0); }
    }

    private static List<T3Api.ThreadInfo> thread(String status, String run) {
        return List.of(new T3Api.ThreadInfo("thread", "project", "Fix the stairs", status, run, 123, false));
    }
}
