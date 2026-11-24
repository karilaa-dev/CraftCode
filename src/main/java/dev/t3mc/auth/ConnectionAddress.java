package dev.t3mc.auth;

import static dev.t3mc.L10n.tr;

import java.io.IOException;
import java.net.URI;
import java.net.InetAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** A credential-free MCP endpoint and, only while connecting, an optional T3 invite code. */
public final class ConnectionAddress {
    private final String endpoint;
    private final String code;

    private ConnectionAddress(String endpoint, String code) {
        this.endpoint = endpoint;
        this.code = code;
    }

    public String endpoint() { return endpoint; }
    public String code() { return code; }

    public String resolveCode(String explicitCode) throws IOException {
        String supplied = explicitCode == null ? "" : explicitCode.strip();
        if (supplied.isEmpty()) return code;
        validateCode(supplied);
        if (!code.isEmpty() && !code.equals(supplied))
            throw new IOException(tr("auth.code_mismatch", "The pairing link and the code field contain different codes. Use one or the other."));
        return supplied;
    }

    @Override public String toString() { return endpoint; }

    static ConnectionAddress parse(String input) throws IOException {
        URI url = addressUri(input);
        // The desktop CLI wraps a web pairing link in t3code://pair?pairingUrl=…
        if (url.getScheme() != null && url.getScheme().toLowerCase(Locale.ROOT).matches("t3code(?:-[a-z]+)?")) {
            if (!"pair".equalsIgnoreCase(url.getRawAuthority()) || !rootPath(url) || url.getRawFragment() != null)
                throw invalidInvite();
            String nested = parameters(url.getRawQuery(), Set.of("pairingUrl")).get("pairingUrl");
            url = addressUri(nested);
            if (!pairPath(url)) throw invalidInvite();
        }
        if (pairPath(url)) return invitation(url);
        return new ConnectionAddress(endpoint(url), "");
    }

    private static ConnectionAddress invitation(URI url) throws IOException {
        // Parse locally. Neither the hosted app nor a redirect receives this invite URL.
        URI outer = origin(url);
        Map<String,String> query = parameters(url.getRawQuery(), Set.of("host", "label", "token"));
        Map<String,String> fragment = parameters(url.getRawFragment(), Set.of("token"));
        if (query.containsKey("token") && fragment.containsKey("token")) throw invalidInvite();
        String code = fragment.getOrDefault("token", query.get("token"));
        validateCode(code);
        if (query.containsKey("host")) {
            if (!hostedApp(outer) || !"https".equals(outer.getScheme()) || outer.getPort() != -1)
                throw new IOException(tr("auth.pairing_source", "Use the direct environment pairing link or one from the T3 hosted app."));
            URI target = addressUri(query.get("host"));
            if (!rootPath(target) || target.getRawQuery() != null || target.getRawFragment() != null) throw invalidInvite();
            return new ConnectionAddress(endpoint(target), code);
        }
        return new ConnectionAddress(endpoint(outer), code);
    }

    private static String endpoint(URI url) throws IOException {
        URI origin = origin(url);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        if (hostedApp(origin) || host.equals("relay.t3.codes") || host.equals("t3.codes"))
            throw new IOException(tr("auth.copy_environment", "Copy the environment’s MCP URL or its pairing link from T3 Code."));
        if (url.getRawQuery() != null || url.getRawFragment() != null)
            throw new IOException(tr("auth.environment_or_invite", "Use a T3 environment URL or a complete pairing link."));
        if (rootPath(url)) return origin.resolve("/mcp").toString();
        String path = url.getRawPath();
        if (path.endsWith("/mcp/")) path = path.substring(0, path.length() - 1);
        if (path.endsWith("/mcp")) return origin + path;
        throw new IOException(tr("auth.environment_address", "Enter a T3 environment URL, its /mcp URL, or a pairing link."));
    }

    private static URI origin(URI url) throws IOException {
        if (url.getHost() == null || url.getRawUserInfo() != null || url.getPort() > 65535 || url.getPort() == 0)
            throw new IOException(tr("auth.no_url_credentials", "Enter a valid T3 Code URL without embedded credentials."));
        if (!"https".equalsIgnoreCase(url.getScheme())
                && !("http".equalsIgnoreCase(url.getScheme()) && localAddress(url.getHost())))
            throw new IOException(tr("auth.https_or_lan", "Use HTTPS or an HTTP URL on your local network."));
        String scheme = url.getScheme().toLowerCase(Locale.ROOT);
        String host = url.getHost().toLowerCase(Locale.ROOT);
        int port = url.getPort();
        boolean defaultPort = port == -1 || port == (scheme.equals("https") ? 443 : 80);
        return URI.create(scheme + "://" + host + (defaultPort ? "" : ":" + port));
    }

    /** Numeric addresses are parsed locally; endpoint validation does not block the render thread on DNS. */
    private static boolean localAddress(String host) {
        String name = host.toLowerCase(Locale.ROOT);
        if (name.equals("localhost") || name.endsWith(".localhost")) return true;
        if (name.endsWith(".local") || name.endsWith(".home.arpa")) return true;
        if (!name.matches("[0-9.]+") && !name.startsWith("[")) return false;
        try {
            InetAddress ip = InetAddress.ofLiteral(name);
            if (ip.isLoopbackAddress()) return true;
            byte[] bytes = ip.getAddress();
            return ip.isSiteLocalAddress() || ip.isLinkLocalAddress()
                    || bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc
                    || bytes.length == 4 && bytes[0] == 100 && (bytes[1] & 0xc0) == 0x40;
        } catch (IllegalArgumentException e) { return false; }
    }

    private static boolean hostedApp(URI origin) {
        String host = origin.getHost();
        return host.equals("app.t3.codes") || host.endsWith(".app.t3.codes");
    }

    private static boolean rootPath(URI url) {
        return "".equals(url.getRawPath()) || "/".equals(url.getRawPath());
    }

    private static boolean pairPath(URI url) {
        return "/pair".equals(url.getRawPath()) || "/pair/".equals(url.getRawPath());
    }

    private static URI addressUri(String input) throws IOException {
        if (input == null || input.isBlank() || input.length() > 16384)
            throw new IOException(tr("auth.url_required", "Enter the URL from T3 Code."));
        try { return URI.create(input.strip()); }
        catch (IllegalArgumentException e) { throw new IOException(tr("auth.url_invalid", "Enter a valid T3 Code URL.")); }
    }

    static void validateCode(String code) throws IOException {
        if (code == null || code.isEmpty() || code.length() > 1024 || code.chars().anyMatch(c -> c < 33 || c > 126))
            throw new IOException(tr("auth.code_required", "Use a complete pairing link or enter a one-time T3 connection code."));
    }

    private static Map<String,String> parameters(String raw, Set<String> allowed) throws IOException {
        Map<String,String> values = new HashMap<>();
        if (raw == null || raw.isEmpty()) return values;
        for (String field : raw.split("&", -1)) {
            String[] entry = field.split("=", 2);
            if (entry.length != 2 || !allowed.contains(entry[0])) throw invalidInvite();
            try {
                String value = URLDecoder.decode(entry[1], StandardCharsets.UTF_8);
                if (values.putIfAbsent(entry[0], value) != null) throw invalidInvite();
            } catch (IllegalArgumentException e) { throw invalidInvite(); }
        }
        return values;
    }

    private static IOException invalidInvite() { return new IOException(tr("auth.invite_incomplete", "Enter a complete T3 Code pairing link.")); }
}
