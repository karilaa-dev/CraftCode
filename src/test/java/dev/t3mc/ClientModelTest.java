package dev.t3mc;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.t3mc.ui.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class ClientModelTest {
    @TempDir Path dir;

    @Test void changesTheSelectedThreadBeforeSendAndPreservesItsConfirmedOptions() throws Exception {
        var fixture = new ModelServer();
        try (fixture; var client = new ClientController(dir.resolve("test.json"))) {
            client.applySettings(new SettingsUpdate(fixture.url(), "", NotificationMode.OFF));
            await(() -> client.snapshot().connection().state() == ConnectionState.CONNECTED);
            client.createThread("project");
            await(() -> "one".equals(client.snapshot().selectedThreadId()));
            client.listModels("").toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals("inherited", client.snapshot().selectedThread().modelSelection().modelId());

            var choice = new ModelSelection("second-provider", "typed-model", Map.of("fast", true));
            var configure = client.configureModel("one", choice);
            assertTrue(fixture.changing.await(3, TimeUnit.SECONDS));
            assertFalse(client.sendMessage("one", "hello").toCompletableFuture().join());
            fixture.allowChange.countDown();
            assertTrue(configure.toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(choice, client.snapshot().selectedThread().modelSelection());

            var sent = client.sendMessage("one", "hello");
            assertTrue(fixture.sending.await(3, TimeUnit.SECONDS));
            assertFalse(client.sendMessage("one", "hello").toCompletableFuture().join());
            fixture.allowSend.countDown();
            assertTrue(sent.toCompletableFuture().get(3, TimeUnit.SECONDS));
            client.listModels("").toCompletableFuture().get(3, TimeUnit.SECONDS);
            assertEquals(1, fixture.sendCount.get());
            assertEquals(choice, client.snapshot().selectedThread().modelSelection());

            var bad = new ModelSelection("second-provider", "unavailable", Map.of());
            var rejection = assertThrows(ExecutionException.class,
                    () -> client.configureModel("one", bad).toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertTrue(rejection.getCause().getMessage().contains("Model unavailable"));
            assertEquals(choice, client.snapshot().selectedThread().modelSelection());

            client.selectThread("two");
            assertFalse(client.configureModel("one", bad).toCompletableFuture().join());
            assertFalse(client.sendMessage("one", "stale").toCompletableFuture().join());
            await(() -> "two".equals(client.snapshot().selectedThreadId()));
            assertEquals("inherited", client.snapshot().selectedThread().modelSelection().modelId());
            assertEquals(1, fixture.sendCount.get());
        }
    }

    private static void await(BooleanSupplier predicate) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!predicate.getAsBoolean() && System.nanoTime() < end) Thread.sleep(10);
        assertTrue(predicate.getAsBoolean());
    }

    private static final class ModelServer implements AutoCloseable {
        final HttpServer server;
        final CountDownLatch changing = new CountDownLatch(1), allowChange = new CountDownLatch(1);
        final CountDownLatch sending = new CountDownLatch(1), allowSend = new CountDownLatch(1);
        final AtomicInteger sendCount = new AtomicInteger();
        final JsonObject inherited = obj("{\"instanceId\":\"first\",\"model\":\"inherited\",\"options\":[]}");
        JsonObject selection = inherited;
        boolean created;

        ModelServer() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.createContext("/", this::handle);
            server.start();
        }

        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }

        void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                if (!exchange.getRequestMethod().equals("POST")) { exchange.sendResponseHeaders(204, -1); return; }
                JsonObject in = obj(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                String method = in.get("method").getAsString();
                if (!in.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                JsonObject out = new JsonObject();
                out.addProperty("jsonrpc", "2.0");
                out.add("id", in.get("id"));
                try {
                    JsonObject result = switch (method) {
                        case "initialize" -> obj("{\"protocolVersion\":\"2025-11-25\"}");
                        case "tools/list" -> obj("{\"tools\":[{\"name\":\"t3_project_list\"},{\"name\":\"t3_thread_list\"},{\"name\":\"t3_thread_read\"},{\"name\":\"t3_thread_configure\"},{\"name\":\"orchestrator_capabilities\"}]}");
                        case "tools/call" -> {
                            JsonObject params = in.getAsJsonObject("params");
                            JsonObject wrapped = new JsonObject();
                            wrapped.add("structuredContent", tool(params.get("name").getAsString(), params.getAsJsonObject("arguments")));
                            yield wrapped;
                        }
                        default -> throw new IOException("Unsupported method");
                    };
                    out.add("result", result);
                } catch (Exception e) {
                    JsonObject error = new JsonObject();
                    error.addProperty("code", -32000);
                    error.addProperty("message", e.getMessage());
                    out.add("error", error);
                }
                byte[] body = out.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        JsonObject tool(String name, JsonObject args) throws Exception {
            String id = args.has("threadId") ? args.get("threadId").getAsString() : "";
            return switch (name) {
                case "t3_project_list" -> obj("{\"projects\":[{\"id\":\"project\",\"title\":\"Test\"}]}");
                case "t3_thread_list" -> {
                    var result = obj("{\"threads\":[]}");
                    if (created) result.getAsJsonArray("threads").add(thread("one"));
                    result.getAsJsonArray("threads").add(thread("two"));
                    yield result;
                }
                case "t3_thread_launch" -> {
                    if (args.has("modelSelection") || args.has("message")) throw new IOException("Expected idle inherited thread");
                    created = true;
                    yield obj("{\"threadId\":\"one\"}");
                }
                case "t3_thread_read" -> {
                    var result = obj("{\"items\":[]}");
                    result.add("thread", thread(id));
                    yield result;
                }
                case "orchestrator_capabilities" -> obj("{\"providers\":[]}");
                case "t3_thread_configure" -> {
                    if (args.has("modelSelection")) {
                        var next = args.getAsJsonObject("modelSelection");
                        if (!id.equals("one") || next.get("model").getAsString().equals("unavailable"))
                            throw new IOException("Model unavailable");
                        changing.countDown();
                        if (!allowChange.await(3, TimeUnit.SECONDS)) throw new IOException("Timed out");
                        selection = next;
                        yield obj("{\"sequence\":1}");
                    }
                    var result = new JsonObject();
                    result.add("modelSelection", id.equals("one") ? selection : inherited);
                    yield result;
                }
                case "t3_thread_send" -> {
                    if (!id.equals("one") || !"typed-model".equals(selection.get("model").getAsString()))
                        throw new IOException("Sent before applying selection");
                    sending.countDown();
                    if (!allowSend.await(3, TimeUnit.SECONDS)) throw new IOException("Timed out");
                    sendCount.incrementAndGet();
                    yield new JsonObject();
                }
                default -> throw new IOException("Unsupported tool");
            };
        }

        JsonObject thread(String id) {
            var thread = obj("{\"projectId\":\"project\",\"title\":\"Test\",\"status\":\"idle\",\"pendingRequestCount\":0}");
            var model = id.equals("one") ? selection : inherited;
            thread.addProperty("threadId", id);
            thread.add("providerInstanceId", model.get("instanceId"));
            thread.add("model", model.get("model"));
            return thread;
        }

        @Override public void close() { allowChange.countDown(); allowSend.countDown(); server.stop(0); }
        static JsonObject obj(String raw) { return JsonParser.parseString(raw).getAsJsonObject(); }
    }
}
