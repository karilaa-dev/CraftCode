package dev.t3mc.auth;

import static dev.t3mc.L10n.tr;

import com.google.gson.*;
import com.sun.net.httpserver.HttpServer;
import com.mojang.blaze3d.Blaze3D;

import java.io.IOException;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/** T3's dynamic client registration and OAuth authorization-code flow with PKCE. */
public final class T3OAuth {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final Map<String,String> APP_CODE_ACCESS = Map.of("READ", "read-only", "SUPERVISED", "approval-required",
            "EDITS", "auto-accept-edits", "AUTO", "auto", "FULL", "full-access");

    public static ConnectionAddress connectionAddress(String address) throws IOException {
        return ConnectionAddress.parse(address);
    }

    public static String connectionEndpoint(String address) throws IOException {
        return connectionAddress(address).endpoint();
    }

    public static String authorize(URI endpoint) throws IOException {
        return authorize(endpoint, Blaze3D::openUri);
    }

    static String authorize(URI endpoint, Consumer<URI> openBrowser) throws IOException {
        return signIn(URI.create(connectionEndpoint(endpoint.toString())), openBrowser, null);
    }

    public static String pair(URI endpoint, String code) throws IOException {
        ConnectionAddress address = connectionAddress(endpoint.toString());
        String supplied = address.resolveCode(code);
        ConnectionAddress.validateCode(supplied);
        return signIn(URI.create(address.endpoint()), null, supplied);
    }

    private static String signIn(URI endpoint, Consumer<URI> openBrowser, String pairingCode) throws IOException {
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            URI origin = endpoint.resolve("/");
            JsonObject metadata = request(http, origin.resolve(".well-known/oauth-authorization-server"), null, null);
            URI issuer = URI.create(metadata.get("issuer").getAsString());
            if (!sameOrigin(origin, issuer)) throw new IOException(tr("auth.unexpected_issuer", "Unexpected T3 authorization server."));
            URI register = checkedEndpoint(origin, metadata, "registration_endpoint");
            URI authorize = checkedEndpoint(origin, metadata, "authorization_endpoint");
            URI token = checkedEndpoint(origin, metadata, "token_endpoint");
            String state = random(), verifier = random();
            String challenge = BASE64.encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            // Pairing receives the redirect URI directly from the decision endpoint. Only the
            // external browser flow needs an actual HTTP listener.
            HttpServer callback = pairingCode == null ? HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 4) : null;
            try {
                String redirect = callback == null ? "http://127.0.0.1/callback"
                        : "http://127.0.0.1:" + callback.getAddress().getPort() + "/callback";
                JsonObject registration = new JsonObject();
                registration.addProperty("client_name", "Minecraft T3 Code");
                registration.addProperty("token_endpoint_auth_method", "none");
                JsonArray redirects = new JsonArray();
                redirects.add(redirect);
                registration.add("redirect_uris", redirects);
                String id = request(http, register, "application/json", registration.toString()).get("client_id").getAsString();
                Map<String,String> params = Map.of("response_type", "code", "client_id", id,
                        "redirect_uri", redirect, "state", state, "code_challenge", challenge, "code_challenge_method", "S256",
                        "resource", endpoint.toString(), "scope", "orchestration:read orchestration:operate");
                String authCode;
                if (pairingCode != null) {
                    authCode = pairDecision(http, origin, issuer, pairingCode, params);
                } else {
                    var result = receiveCallback(callback, state, issuer);
                    openBrowser.accept(URI.create(authorize + "?" + form(params)));
                    authCode = result.get(5, TimeUnit.MINUTES);
                }
                JsonObject response = request(http, token, "application/x-www-form-urlencoded", form(Map.of(
                        "grant_type", "authorization_code", "client_id", id, "code", authCode, "redirect_uri", redirect,
                        "code_verifier", verifier, "resource", endpoint.toString())));
                String bearer = response.get("access_token").getAsString();
                if (bearer.isBlank()) throw new IOException(tr("auth.empty_token", "T3 Code returned an empty access token."));
                return bearer;
            } finally { if (callback != null) callback.stop(0); }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tr("auth.cancelled", "Sign-in cancelled."));
        } catch (TimeoutException e) {
            throw new IOException(tr("auth.timeout", "T3 sign-in timed out. Connect again."));
        } catch (ExecutionException e) {
            throw new IOException(tr("auth.denied", "T3 Code sign-in was declined."));
        } catch (RuntimeException | GeneralSecurityException e) {
            // URI/JSON exceptions can include the authorization code or token.
            throw new IOException(tr("auth.failed", "Could not complete T3 Code sign-in."));
        }
    }

    private static CompletableFuture<String> receiveCallback(HttpServer callback, String state, URI issuer) {
        var result = new CompletableFuture<String>();
        callback.createContext("/callback", exchange -> {
            int status = 400;
            String message = tr("auth.callback_invalid", "Invalid authorization response.");
            try {
                var params = query(exchange.getRequestURI().getRawQuery());
                if ("GET".equals(exchange.getRequestMethod()) && "/callback".equals(exchange.getRequestURI().getPath())
                        && state.equals(params.get("state")) && (!params.containsKey("iss") || issuer.toString().equals(params.get("iss")))) {
                    if (params.containsKey("error")) {
                        result.completeExceptionally(new IOException(tr("auth.denied", "T3 Code sign-in was declined.")));
                        message = tr("auth.callback_denied", "Sign-in was declined. Return to Minecraft.");
                    } else if (!params.getOrDefault("code", "").isBlank()) {
                        result.complete(params.get("code"));
                        status = 200;
                        message = tr("auth.callback_received", "Authorization received. Return to Minecraft.");
                    }
                }
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        callback.start();
        return result;
    }

    private static String pairDecision(HttpClient http, URI origin, URI issuer, String code, Map<String,String> params)
            throws IOException, InterruptedException {
        String upper = code.toUpperCase(Locale.ROOT);
        String assigned = null;
        if (upper.matches("(READ|SUPERVISED|EDITS|AUTO|FULL)-[23456789ABCDEFGHJKLMNPQRSTUVWXYZ]{12}")) {
            assigned = APP_CODE_ACCESS.get(upper.substring(0, upper.indexOf('-')));
            code = upper;
        }
        var authorization = new JsonObject();
        params.forEach(authorization::addProperty);
        var decision = new JsonObject();
        decision.addProperty("_tag", "pairing-code");
        decision.addProperty("code", code);
        decision.addProperty("access", assigned == null ? "approval-required" : assigned);
        var body = new JsonObject();
        body.add("authorization", authorization);
        body.add("decision", decision);
        URI decisionUrl = origin.resolve("oauth/mcp/decision");
        JsonObject response;
        try {
            response = request(http, decisionUrl, "application/json", body.toString());
        } catch (InsufficientCodeScope e) {
            if (assigned != null) throw e;
            // The T3 grant store explicitly preserves the plain code on insufficient scope.
            decision.addProperty("access", "read-only");
            response = request(http, decisionUrl, "application/json", body.toString());
        }
        URI redirect = URI.create(response.get("redirectTo").getAsString());
        URI expected = URI.create(params.get("redirect_uri"));
        if (!sameOrigin(expected, redirect) || !expected.getRawPath().equals(redirect.getRawPath()) || redirect.getRawFragment() != null)
            throw new IOException(tr("auth.unexpected_pairing", "T3 Code returned an unexpected pairing response."));
        var result = query(redirect.getRawQuery());
        if (!params.get("state").equals(result.get("state")) || !issuer.toString().equals(result.get("iss"))
                || result.containsKey("error") || result.getOrDefault("code", "").isBlank())
            throw new IOException(tr("auth.code_unapproved", "T3 Code did not approve the connection code."));
        return result.get("code");
    }

    private static final class InsufficientCodeScope extends IOException {
        InsufficientCodeScope() { super(tr("auth.code_scope", "This connection code cannot grant the requested access.")); }
    }

    private static JsonObject request(HttpClient http, URI url, String type, String body) throws IOException, InterruptedException {
        HttpRequest.Builder req = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(20)).header("Accept", "application/json");
        if (body != null) req.header("Content-Type", type).POST(HttpRequest.BodyPublishers.ofString(body));
        HttpResponse<String> response = http.send(req.build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            if (url.getPath().equals("/oauth/mcp/decision") && response.statusCode() == 400) {
                if (response.body().contains("That pairing code cannot grant this access. It has not been used"))
                    throw new InsufficientCodeScope();
                throw new IOException(tr("auth.code_rejected", "T3 Code rejected the connection code. Check the code or generate a new one."));
            }
            throw new IOException(tr("auth.http", "T3 sign-in returned HTTP %s.", response.statusCode()));
        }
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static URI checkedEndpoint(URI origin, JsonObject metadata, String key) throws IOException {
        URI value = URI.create(metadata.get(key).getAsString());
        if (!sameOrigin(origin, value) || value.getFragment() != null || value.getQuery() != null)
            throw new IOException(tr("auth.unexpected_endpoint", "Unexpected T3 sign-in endpoint."));
        return value;
    }

    private static boolean sameOrigin(URI first, URI second) {
        return second.getScheme() != null && first.getScheme().equalsIgnoreCase(second.getScheme()) && second.getHost() != null
                && first.getHost().equalsIgnoreCase(second.getHost()) && port(first) == port(second) && second.getUserInfo() == null;
    }

    private static int port(URI uri) {
        return uri.getPort() != -1 ? uri.getPort() : uri.getScheme().equalsIgnoreCase("https") ? 443 : 80;
    }

    private static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64.encodeToString(bytes);
    }

    private static String form(Map<String,String> params) {
        return params.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue())).collect(Collectors.joining("&"));
    }

    private static String encode(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }

    private static Map<String,String> query(String query) {
        Map<String,String> values = new HashMap<>();
        if (query != null) for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2) values.put(parts[0], URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private T3OAuth() {}
}
