package dev.craftcode.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.craftcode.mcp.McpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@Timeout(15)
class T3OAuthTest {
    @Test void normalizesConnectionUrlsBeforeTheyCanBeSaved() throws Exception {
        for (String host : List.of("localhost", "127.0.0.1", "[::1]", "192.168.4.12", "[fd00::1]", "host.local")) {
            String base = "http://" + host + ":3773";
            assertEquals(base + "/mcp", T3OAuth.connectionEndpoint(base + "/"));
            assertEquals(base + "/mcp", T3OAuth.connectionEndpoint(base + "/mcp/"));
        }
        assertEquals("https://relay.example/clients/id/mcp", T3OAuth.connectionEndpoint("https://relay.example/clients/id/mcp/"));
        for (String url : List.of("https://environment.example", "HTTPS://ENVIRONMENT.EXAMPLE:443/", "https://ENVIRONMENT.example:443/mcp/"))
            assertEquals("https://environment.example/mcp", T3OAuth.connectionEndpoint(url));
        assertEquals("http://localhost/mcp", T3OAuth.connectionEndpoint("HTTP://LOCALHOST:80/mcp"));
        for (String central : List.of("https://app.t3.codes", "https://nightly.app.t3.codes/mcp", "https://relay.t3.codes"))
            assertThrows(IOException.class, () -> T3OAuth.connectionEndpoint(central));
        assertThrows(IOException.class, () -> T3OAuth.connectionEndpoint("http://8.8.8.8"));
        for (String address : List.of("https://private-code@example.org/mcp", "https://example.org/mcp?private-code",
                "https://example.org/pair#private-code", "invalid private-code")) {
            assertFalse(assertThrows(IOException.class, () -> T3OAuth.connectionEndpoint(address)).getMessage().contains("private-code"));
        }
    }

    @Test void extractsTheEnvironmentAndSecretFromOfficialInvitations() throws Exception {
        String direct = "https://work.example/pair#token=one-time-code";
        String hosted = "https://app.t3.codes/pair?host=" + encoded("https://work.example") + "&label=My+environment#token=one-time-code";
        String app = "t3code://pair?pairingUrl=" + encoded(direct);
        String nightly = "t3code-nightly://pair?pairingUrl=" + encoded(hosted);
        for (String link : List.of(direct, hosted, app, nightly, "https://work.example/pair?token=one-time-code")) {
            var address = T3OAuth.connectionAddress(link);
            assertEquals("https://work.example/mcp", address.endpoint());
            assertEquals("one-time-code", address.code());
            assertEquals("one-time-code", address.resolveCode(""));
            assertFalse(address.toString().contains("one-time-code"));
            assertThrows(IOException.class, () -> address.resolveCode("different"));
        }
        for (String invalid : List.of("https://app.t3.codes/pair?host=" + encoded("file:///tmp") + "#token=one-time-code",
                "https://work.example/pair?token=other#token=one-time-code", "https://work.example/pair#token=x&token=one-time-code",
                "https://app.t3.codes/pair?host=https://user:one-time-code@example.org#token=x",
                "https://other.example/pair?host=https://work.example#token=one-time-code",
                "https://app.t3.codes:444/pair?host=https://work.example#token=one-time-code",
                "https://work.example/mcp?token=one-time-code", "t3code://pair?pairingUrl=" + encoded("https://work.example/mcp?token=one-time-code"))) {
            assertFalse(assertThrows(IOException.class, () -> T3OAuth.connectionAddress(invalid)).toString().contains("one-time-code"));
        }
    }

    @Test void pairsExplicitAndPlainCodesAtTheirAllowedAccess() throws Exception {
        try (Fixture server = new Fixture()) {
            assertEquals("issued-grant", T3OAuth.pair(server.endpoint, "read-222233334444"));
            assertEquals(List.of("read-only"), server.access);
            assertEquals("READ-222233334444", server.lastCode);
            server.access.clear();
            server.restrictPlain = true;
            assertEquals("issued-grant", T3OAuth.pair(server.endpoint, "plain"));
            assertEquals(List.of("approval-required", "read-only"), server.access);
            assertEquals("plain", server.lastCode);
            assertEquals(2, server.tokenCalls);
        }
    }

    @Test void rejectsInvalidCodesAndUntrustedAuthorizationResponsesBeforeExchange() throws Exception {
        try (Fixture server = new Fixture()) {
            server.invalid = true;
            IOException error = assertThrows(IOException.class, () -> T3OAuth.pair(server.endpoint, "invalid-secret-code"));
            assertFalse(error.toString().contains("invalid-secret-code"));
            assertEquals(List.of("approval-required"), server.access);
            server.invalid = false;
            server.redirectOverride = "http://localhost/callback";
            assertThrows(IOException.class, () -> T3OAuth.pair(server.endpoint, "plain"));
            server.redirectOverride = null;
            server.badState = true;
            assertThrows(IOException.class, () -> T3OAuth.pair(server.endpoint, "plain"));
            assertEquals(0, server.tokenCalls);
        }
    }

    @Test void browserUsesItsLoopbackCallbackAndChecksState() throws Exception {
        try (Fixture server = new Fixture(); HttpClient browser = HttpClient.newHttpClient()) {
            String token = T3OAuth.authorize(server.endpoint, url -> {
                try {
                    assertEquals(server.origin.resolve("/oauth/mcp/authorize"), URI.create(url.toString().split("\\?")[0]));
                    Map<String,String> p = form(url.getRawQuery());
                    server.challenge = p.get("code_challenge");
                    String callback = p.get("redirect_uri") + "?code=auth-code&iss=" + encoded(server.origin.toString());
                    assertEquals(400, get(browser, callback + "&state=wrong"));
                    assertEquals(200, get(browser, callback + "&state=" + p.get("state")));
                } catch (Exception e) { throw new AssertionError(e); }
            });
            assertEquals("issued-grant", token);
            assertTrue(server.access.isEmpty());
            assertEquals(1, server.tokenCalls);
        }
    }

    @Test void freshCodeAndExpiredBearerTakeDifferentSignInPaths() throws Exception {
        try (Fixture server = new Fixture(); McpClient mcp = McpClient.connect(server.origin + "/pair#token=plain",
                "old-grant", "", ignored -> fail("Unusable grant must not be saved"))) {
            IOException error = assertThrows(IOException.class, mcp::initialize);
            assertTrue(error.getMessage().contains("fresh connection code"));
            assertEquals(List.of("approval-required"), server.access);
            assertEquals(1, server.registrations);
        }
        try (Fixture server = new Fixture(); McpClient mcp = McpClient.connect(server.origin.toString(),
                "expired", "", ignored -> fail("Unusable grant must not be saved"))) {
            // Stop the browser sign-in at registration, before an external browser is opened.
            server.denyRegistration = true;
            assertThrows(IOException.class, mcp::initialize);
            assertEquals(1, server.registrations);
            assertTrue(server.access.isEmpty());
        }
    }

    private static int get(HttpClient browser, String address) throws IOException, InterruptedException {
        return browser.send(HttpRequest.newBuilder(URI.create(address)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final URI origin;
        final URI endpoint;
        final List<String> access = new ArrayList<>();
        String lastCode, redirectOverride, challenge;
        boolean restrictPlain, invalid, badState, denyRegistration;
        int registrations, tokenCalls;

        Fixture() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            origin = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
            endpoint = origin.resolve("/mcp");
            server.createContext("/", this::handle);
            server.start();
        }

        private void handle(HttpExchange exchange) throws IOException {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                var result = new JsonObject();
                int status = 200;
                switch (exchange.getRequestURI().toString()) {
                    case "/.well-known/oauth-authorization-server" -> {
                        result.addProperty("issuer", origin.toString());
                        result.addProperty("registration_endpoint", origin + "/oauth/mcp/register");
                        result.addProperty("authorization_endpoint", origin + "/oauth/mcp/authorize");
                        result.addProperty("token_endpoint", origin + "/oauth/mcp/token");
                    }
                    case "/oauth/mcp/register" -> {
                        registrations++;
                        if (denyRegistration) status = 403;
                        else result.addProperty("client_id", "client");
                    }
                    case "/oauth/mcp/decision" -> {
                        var json = JsonParser.parseString(body).getAsJsonObject();
                        var authorization = json.getAsJsonObject("authorization");
                        var decision = json.getAsJsonObject("decision");
                        String selected = decision.get("access").getAsString();
                        access.add(selected);
                        lastCode = decision.get("code").getAsString();
                        challenge = authorization.get("code_challenge").getAsString();
                        if (invalid || restrictPlain && !selected.equals("read-only")) {
                            status = 400;
                            result.addProperty("error", invalid ? "Invalid pairing code" : "That pairing code cannot grant this access. It has not been used");
                        } else {
                            String redirect = redirectOverride != null ? redirectOverride : authorization.get("redirect_uri").getAsString();
                            String state = badState ? "wrong" : authorization.get("state").getAsString();
                            result.addProperty("redirectTo", redirect + "?code=auth-code&state=" + state + "&iss=" + encoded(origin.toString()));
                        }
                    }
                    case "/oauth/mcp/token" -> {
                        tokenCalls++;
                        Map<String,String> params = form(body);
                        if (!"auth-code".equals(params.get("code")) || !challenge.equals(challenge(params.get("code_verifier")))) status = 400;
                        else result.addProperty("access_token", "issued-grant");
                    }
                    case "/mcp" -> status = 401;
                    default -> status = 404;
                }
                byte[] bytes = result.toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        }

        @Override public void close() { server.stop(0); }
    }

    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private static Map<String,String> form(String query) {
        var result = new HashMap<String,String>();
        for (String part : query.split("&")) {
            String[] entry = part.split("=", 2);
            result.put(entry[0], URLDecoder.decode(entry[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
