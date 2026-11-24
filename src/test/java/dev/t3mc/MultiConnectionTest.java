package dev.t3mc;

import com.google.gson.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.t3mc.ui.model.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(30)
class MultiConnectionTest {
    @TempDir Path dir;

    @Test void namespacesDuplicateIdsKeepsRootsPollingAndRetiresExpiredLinks() throws Exception {
        try (var a = new Server("A"); var b = new Server("B"); var c = new ClientController(dir.resolve("t3mc.json"))) {
            assertTrue(c.saveProfile("Studio", settings(a)));
            await(() -> connected(c, "A") && !c.connections().links().isEmpty());
            c.selectThread("same");
            await(() -> c.snapshot().selectedThread() != null);
            assertTrue(c.selectEnvironment("peer"));
            await(() -> connected(c, "A-peer"));
            c.selectThread("same");
            await(() -> c.snapshot().selectedThread() != null);
            assertTrue(c.sendMessage("same", "peer message").toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(List.of("peer"), a.sendDestinations);
            long generation = c.connectionGeneration();
            c.reconnect();
            await(() -> c.connectionGeneration() > generation && connected(c, "A-peer"));

            assertTrue(c.saveProfile("Other", settings(b)));
            await(() -> connected(c, "B"));
            assertEquals(2, c.connections().profiles().stream().filter(p -> p.state() == ConnectionState.CONNECTED).count());
            int background = a.rootPolls.get();
            await(() -> a.rootPolls.get() > background);
            assertTrue(c.setIncludeLinked(false));
            assertTrue(c.selectProfile(a.url()));
            assertTrue(c.connections().includeLinked());
            assertTrue(connected(c, "A"));
            assertEquals("same", c.snapshot().selectedThreadId());

            assertTrue(c.selectEnvironment("peer"));
            await(() -> connected(c, "A-peer"));
            a.access = "read-only";
            c.refreshLinkedEnvironments();
            await(c::readOnlyEnvironment);
            c.selectThread("same");
            await(() -> c.snapshot().selectedThread() != null);
            assertFalse(c.sendMessage("same", "forbidden").toCompletableFuture().join());
            assertEquals(List.of("peer"), a.sendDestinations);
            long peerGeneration = c.snapshot().settings().connections().generation();
            a.linkStatus = "expired";
            c.refreshLinkedEnvironments();
            await(() -> c.connections().environmentId().isEmpty() && connected(c, "A"));
            assertFalse(c.selectEnvironment("peer"));
            assertTrue(c.connections().linkError().contains("no longer reachable"));
            assertEquals("same", c.snapshot().selectedThreadId());
            assertFalse(c.changeOnConnection(peerGeneration, () -> c.sendMessage("same", "stale peer draft"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            var staleCreateSubmitted = new AtomicBoolean();
            c.onConnection(peerGeneration, () -> {
                staleCreateSubmitted.set(true);
                c.createThread("p");
            });
            assertFalse(staleCreateSubmitted.get());
            assertEquals(List.of("peer"), a.sendDestinations);
            assertTrue(a.createDestinations.isEmpty());
            assertTrue(c.removeProfile(a.url()));
            assertTrue(connected(c, "B"));
            assertEquals(1, c.connections().profiles().size());
            assertFalse(c.connections().includeLinked());
        }
    }

    @Test void restoresCombinedSidebarAndRoutesDuplicateIdsThroughTheSelectedRow() throws Exception {
        Path file = dir.resolve("combined.json");
        try (var a = new Server("A"); var b = new Server("B")) {
            ClientConfig.defaults().withProfile("Studio", a.url(), NotificationMode.OFF)
                    .withProfile("Lab", b.url(), NotificationMode.OFF).save(file);
            try (var c = new ClientController(file)) {
                c.restoreWorkspaces();
                await(() -> c.connections().online() == 4);
                assertEquals(4, c.connections().total());
                var peer = c.snapshot().environments().stream()
                        .filter(e -> e.profileId().equals(a.url()) && e.environmentId().equals("peer")).findFirst().orElseThrow();
                assertEquals("same", peer.projects().getFirst().threads().getFirst().id());
                c.openThread(peer, "same");
                await(() -> connected(c, "A-peer") && "same".equals(c.snapshot().selectedThreadId()));
                assertTrue(c.setDisplay(a.url(), "peer", new EnvironmentDisplay(true, "★")));
                assertEquals(3, c.snapshot().environments().size());
                assertNull(c.snapshot().selectedThreadId());
                assertEquals(4, c.connections().online());
                assertEquals(new EnvironmentDisplay(true, "★"), ClientConfig.load(file).profile(a.url()).display("peer"));
                assertTrue(c.setDisplay(a.url(), "peer", new EnvironmentDisplay(false, "★")));
                assertEquals("same", c.snapshot().selectedThreadId());
                b.linkStatus = "unreachable";
                c.refreshLinkedEnvironments(b.url());
                await(() -> c.connections().online() == 3);
                assertEquals(4, c.connections().total());
            }
        }
    }

    @Test void browsingAnotherProfilesLinksPreservesTheActiveChatAndRoutesOnlyExplicitSwitches() throws Exception {
        try (var a = new Server("A"); var b = new Server("B"); var c = new ClientController(dir.resolve("t3mc.json"))) {
            assertTrue(c.saveProfile("Studio", settings(a)));
            await(() -> connected(c, "A") && !profile(c, a).links().isEmpty());
            assertTrue(c.saveProfile("Lab", settings(b)));
            await(() -> connected(c, "B") && !profile(c, b).links().isEmpty());

            assertTrue(c.selectEnvironment(a.url(), "peer"));
            await(() -> connected(c, "A-peer"));
            c.selectThread("same");
            await(() -> "same".equals(c.snapshot().selectedThreadId()));
            long aGeneration = c.connectionGeneration();
            assertTrue(profile(c, b).linksAvailable());
            assertTrue(c.setIncludeLinked(b.url(), false));
            assertFalse(profile(c, b).includeLinked());
            assertTrue(profile(c, b).links().isEmpty());
            assertTrue(profile(c, a).includeLinked());
            assertEquals("peer", profile(c, a).links().getFirst().id());

            b.failDiscovery = true;
            assertTrue(c.setIncludeLinked(b.url(), true));
            await(() -> profile(c, b).linkError().contains("Discovery failed on B"));
            assertTrue(profile(c, a).linkError().isEmpty());
            b.failDiscovery = false;
            c.refreshLinkedEnvironments(b.url());
            await(() -> profile(c, b).linkError().isEmpty() && !profile(c, b).links().isEmpty());
            assertEquals(a.url(), c.connections().activeProfileId());
            assertTrue(connected(c, "A-peer"));
            assertEquals("same", c.snapshot().selectedThreadId());
            assertEquals(aGeneration, c.connectionGeneration());
            assertTrue(c.changeOnConnection(aGeneration, () -> c.sendMessage("same", "Studio draft"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(List.of("Studio draft"), a.sentMessages);
            assertTrue(b.sentMessages.isEmpty());

            assertTrue(c.selectEnvironment(b.url(), "peer"));
            await(() -> connected(c, "B-peer"));
            assertNull(c.snapshot().selectedThreadId());
            c.selectThread("same");
            await(() -> "same".equals(c.snapshot().selectedThreadId()));
            long bGeneration = c.connectionGeneration();
            assertNotEquals(aGeneration, bGeneration);
            assertFalse(c.changeOnConnection(aGeneration, () -> c.sendMessage("same", "Stale Studio draft"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));

            a.linkStatus = "unreachable";
            c.refreshLinkedEnvironments(a.url());
            await(() -> profile(c, a).links().stream().anyMatch(link -> link.id().equals("peer") && !link.reachable()));
            assertFalse(c.selectEnvironment(a.url(), "peer"));
            assertFalse(c.selectEnvironment(a.url(), "missing-peer"));
            assertFalse(c.selectEnvironment("https://unknown.example/mcp", "peer"));
            assertEquals(b.url(), c.connections().activeProfileId());
            assertEquals("peer", c.connections().environmentId());
            assertTrue(connected(c, "B-peer"));
            assertEquals("same", c.snapshot().selectedThreadId());
            assertEquals(bGeneration, c.connectionGeneration());
            assertTrue(c.changeOnConnection(bGeneration, () -> c.sendMessage("same", "Lab draft"))
                    .toCompletableFuture().get(3, TimeUnit.SECONDS));
            assertEquals(List.of("Studio draft"), a.sentMessages);
            assertEquals(List.of("Lab draft"), b.sentMessages);
            assertEquals(List.of("peer"), a.sendDestinations);
            assertEquals(List.of("peer"), b.sendDestinations);
        }
    }

    private static ConnectionProfileView profile(ClientController c, Server server) {
        return c.snapshot().settings().connections().profiles().stream()
                .filter(p -> p.id().equals(server.url())).findFirst().orElseThrow();
    }

    @Test void foldingASelectedPeerKeepsItsPermissionsSessionAndCompletionNotifications() throws Exception {
        var file = dir.resolve("folded.json");
        record Notice(NotificationMode mode, String title, String body) {}
        List<Notice> notices = new CopyOnWriteArrayList<>();
        try (var server = new Server("A")) {
            server.access = "read-only";
            try (var c = new ClientController(file, (mode, title, body) -> notices.add(new Notice(mode, title, body)))) {
                assertTrue(c.saveProfile("Studio", new SettingsUpdate(server.url(), "", NotificationMode.POPUP)));
                await(() -> connected(c, "A") && !profile(c, server).links().isEmpty());
                assertTrue(c.selectEnvironment(server.url(), "peer"));
                await(() -> connected(c, "A-peer"));
                c.selectThread("same");
                await(() -> "same".equals(c.snapshot().selectedThreadId()));
                long generation = c.connectionGeneration();

                assertTrue(profile(c, server).linksExpanded());
                assertTrue(c.readOnlyEnvironment());
                assertTrue(c.setLinksExpanded(server.url(), false));
                assertFalse(profile(c, server).linksExpanded());
                assertTrue(profile(c, server).includeLinked());
                assertTrue(profile(c, server).links().getFirst().readOnly());
                assertEquals("peer", c.connections().environmentId());
                assertEquals("same", c.snapshot().selectedThreadId());
                assertEquals(generation, c.connectionGeneration());
                assertTrue(c.readOnlyEnvironment());

                server.peerStatus = "completed";
                await(() -> notices.contains(new Notice(NotificationMode.POPUP, "Studio / Peer · Agent finished", "A-peer")));
                assertTrue(connected(c, "A-peer"));
                assertEquals(generation, c.connectionGeneration());
            }
            try (var restarted = new ClientController(file)) {
                assertFalse(profile(restarted, server).linksExpanded());
                assertTrue(profile(restarted, server).includeLinked());
            }
        }
    }

    @Test void olderProfilesInheritDiscoveryAsFoldStateAndExpandingAnOptOutStartsDiscovery() throws Exception {
        Path file = dir.resolve("older-profiles.json");
        try (var disabled = new Server("Disabled"); var enabled = new Server("Enabled")) {
            Files.writeString(file, """
                    {"profiles":[{"endpoint":"%s","name":"Enabled","token":"","includeLinked":true},
                                 {"endpoint":"%s","name":"Disabled","token":"","includeLinked":false}],
                     "activeProfileId":"%s","notificationMode":"OFF"}
                    """.formatted(enabled.url(), disabled.url(), disabled.url()));
            try (var c = new ClientController(file)) {
                assertTrue(profile(c, enabled).linksExpanded());
                assertFalse(profile(c, disabled).linksExpanded());
                assertTrue(c.selectProfile(disabled.url()));
                await(() -> connected(c, "Disabled"));
                assertTrue(profile(c, disabled).links().isEmpty());
                assertTrue(c.setLinksExpanded(disabled.url(), true));
                await(() -> !profile(c, disabled).links().isEmpty());
                assertTrue(profile(c, disabled).includeLinked());
                assertTrue(profile(c, disabled).linksExpanded());
                assertTrue(c.setLinksExpanded(enabled.url(), false));
            }
            var persisted = ClientConfig.load(file);
            assertTrue(persisted.profile(disabled.url()).includeLinked());
            assertTrue(persisted.profile(disabled.url()).linksExpanded());
            assertTrue(persisted.profile(enabled.url()).includeLinked());
            assertFalse(persisted.profile(enabled.url()).linksExpanded());
        }
    }

    @Test void migratesAnExistingSignInAndStoresEachGrantAndLinkPreferenceWithItsProfile() throws Exception {
        var file = dir.resolve("settings.json");
        Files.writeString(file, """
                {"httpUrl":"https://one.example/mcp","token":"first-grant","notificationMode":"OFF","transparency":45}
                """);
        var config = ClientConfig.load(file).withLinked(false)
                .withProfile("Second", "https://two.example/mcp", NotificationMode.CHAT)
                .withGrant("second-grant").withGrant("https://one.example/mcp", "renewed-first");
        config.save(file);
        var restored = ClientConfig.load(file);
        assertEquals(2, restored.profiles().size());
        assertEquals("https://two.example/mcp", restored.activeProfileId());
        assertEquals("second-grant", restored.token());
        assertTrue(restored.includeLinked());
        restored = restored.select("https://one.example/mcp");
        assertEquals("renewed-first", restored.token());
        assertFalse(restored.includeLinked());
        assertEquals(45, restored.transparency());
        try (var c = new ClientController(file)) {
            assertFalse(c.connections().toString().contains("second-grant"));
            assertFalse(c.connections().toString().contains("renewed-first"));
        }
    }

    @Test void missingPeerRoutingFailsWithoutIssuingLocalToolCalls() throws Exception {
        try (var server = new Server("Primary"); var c = new ClientController(dir.resolve("test.json"))) {
            server.advertiseRouting = false;
            assertTrue(c.saveProfile("Profile", settings(server)));
            await(() -> connected(c, "Primary") && !c.connections().links().isEmpty());
            int reads = server.rootPolls.get();
            assertTrue(c.selectEnvironment("peer"));
            await(() -> c.snapshot().connection().state() == ConnectionState.ERROR);
            assertTrue(c.snapshot().connection().message().contains("cannot route"));
            assertEquals(reads, server.rootPolls.get());
            assertTrue(server.sendDestinations.isEmpty());
        }
    }

    private static SettingsUpdate settings(Server s) { return new SettingsUpdate(s.url(), "", NotificationMode.OFF); }
    private static boolean connected(ClientController c, String project) {
        var snapshot = c.snapshot();
        return snapshot.connection().state() == ConnectionState.CONNECTED && !snapshot.projects().isEmpty()
                && snapshot.projects().getFirst().name().equals(project);
    }
    private static void await(BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        while (!condition.getAsBoolean() && System.nanoTime() < end) Thread.sleep(15);
        assertTrue(condition.getAsBoolean());
    }
    private static JsonObject json(String s) { return JsonParser.parseString(s).getAsJsonObject(); }

    private static final class Server implements AutoCloseable {
        final HttpServer server;
        final String name;
        final AtomicInteger rootPolls = new AtomicInteger();
        final List<String> sendDestinations = new CopyOnWriteArrayList<>(), createDestinations = new CopyOnWriteArrayList<>();
        volatile String access = "approval-required", linkStatus = "reachable", peerStatus = "idle";
        volatile boolean advertiseRouting = true, failDiscovery;
        final List<String> sentMessages = new CopyOnWriteArrayList<>();

        Server(String name) throws IOException {
            this.name = name;
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4);
            server.createContext("/mcp", this::rpc);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp"; }
        @Override public void close() { server.stop(0); }

        private void rpc(HttpExchange exchange) throws IOException {
            try (exchange) {
                JsonObject message = json(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                if (!message.has("id")) { exchange.sendResponseHeaders(202, -1); return; }
                String method = message.get("method").getAsString();
                JsonObject response = json("{\"jsonrpc\":\"2.0\"}");
                response.add("id", message.get("id"));
                switch (method) {
                    case "initialize" -> response.add("result", json("{\"protocolVersion\":\"2025-11-25\"}"));
                    case "tools/list" -> {
                        var result = new JsonObject();
                        var tools = new JsonArray();
                        for (String tool : List.of("t3_project_list", "t3_thread_list", "t3_thread_read", "t3_thread_send", "t3_environment_links")) {
                            var schema = new JsonObject();
                            schema.addProperty("name", tool);
                            if (advertiseRouting && !tool.equals("t3_environment_links"))
                                schema.add("inputSchema", json("{\"type\":\"object\",\"properties\":{\"environmentId\":{\"type\":\"string\"}}}"));
                            tools.add(schema);
                        }
                        result.add("tools", tools);
                        response.add("result", result);
                    }
                    case "tools/call" -> {
                        var params = message.getAsJsonObject("params");
                        var args = params.getAsJsonObject("arguments");
                        var result = new JsonObject();
                        String tool = params.get("name").getAsString();
                        if (tool.equals("t3_environment_links") && failDiscovery) {
                            result.addProperty("isError", true);
                            result.add("content", JsonParser.parseString("[{\"type\":\"text\",\"text\":\"Discovery failed on " + name + "\"}]"));
                        } else result.add("structuredContent", tool(tool, args));
                        response.add("result", result);
                    }
                    default -> response.add("error", json("{\"code\":-32601,\"message\":\"unsupported\"}"));
                }
                byte[] body = response.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        }

        private JsonObject tool(String tool, JsonObject args) {
            String target = args.has("environmentId") ? args.get("environmentId").getAsString() : "";
            String label = target.isEmpty() ? name : name + "-" + target;
            String thread = "{\"threadId\":\"same\",\"projectId\":\"p\",\"title\":\"%s\",\"status\":\"%s\",\"pendingRequestCount\":0}".formatted(label, target.isEmpty() ? "idle" : peerStatus);
            return switch (tool) {
                case "t3_project_list" -> json("{\"projects\":[{\"id\":\"p\",\"title\":\"%s\"}]}".formatted(label));
                case "t3_thread_list" -> {
                    if (target.isEmpty()) rootPolls.incrementAndGet();
                    yield json("{\"threads\":[" + thread + "]}");
                }
                case "t3_thread_read" -> json("{\"thread\":" + thread + ",\"items\":[]}");
                case "t3_thread_launch" -> { createDestinations.add(target); yield json("{\"threadId\":\"same\"}"); }
                case "t3_thread_send" -> {
                    sendDestinations.add(target);
                    sentMessages.add(args.get("message").getAsString());
                    yield new JsonObject();
                }
                case "t3_environment_links" -> json("""
                        {"environmentId":"primary","links":[{"environmentId":"peer","label":"Peer","status":"%s","access":"%s","expiresAt":"2099-01-01T00:00:00Z","lastError":null}]}
                        """.formatted(linkStatus, access));
                default -> throw new AssertionError(tool);
            };
        }
    }
}
