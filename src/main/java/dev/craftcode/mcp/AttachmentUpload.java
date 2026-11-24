package dev.craftcode.mcp;

import static dev.craftcode.L10n.tr;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** T3 returns a signed HTTP URL for this one upload. It authorizes the POST by itself. */
final class AttachmentUpload {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(10)).build();

    static URI destination(URI origin, String absolute, String relative) throws IOException {
        // A proxied server may advertise its internal hostname. Its signed route also works on the MCP origin.
        boolean useRelative = relative != null && !relative.isBlank();
        boolean useAbsolute = !useRelative && absolute != null && !absolute.isBlank();
        URI target;
        try {
            if (useAbsolute) {
                target = URI.create(absolute);
            } else {
                URI route = URI.create(relative);
                if (route.isAbsolute() || route.getRawAuthority() != null || !route.getPath().startsWith("/"))
                    throw new IllegalArgumentException();
                target = origin.resolve(route);
            }
            if (target.getHost() == null || target.getUserInfo() != null || target.getFragment() != null
                    || !("https".equalsIgnoreCase(target.getScheme()) || "http".equalsIgnoreCase(target.getScheme())))
                throw new IllegalArgumentException();
            if (!(origin.getScheme().equalsIgnoreCase(target.getScheme())
                    && origin.getHost().equalsIgnoreCase(target.getHost()) && port(origin) == port(target)))
                throw new IOException(tr("net.upload_origin", "The image upload URL belongs to a different server."));
            return target;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException(tr("net.upload_url", "T3 Code returned an invalid image upload URL."));
        }
    }

    private static int port(URI uri) {
        return uri.getPort() >= 0 ? uri.getPort() : "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    static void post(URI url, String mimeType, byte[] data) throws IOException {
        HttpResponse<InputStream> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(url).timeout(Duration.ofSeconds(40))
                    .header("Content-Type", mimeType).POST(HttpRequest.BodyPublishers.ofByteArray(data)).build();
            response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(tr("net.upload_cancelled", "Image upload cancelled."));
        } catch (IOException | IllegalArgumentException e) {
            // HTTP exceptions and response bodies can contain the signed URL.
            throw new IOException(tr("net.upload_failed", "Could not upload the image to T3 Code."));
        }
        try (InputStream ignored = response.body()) {
            if (response.statusCode() / 100 != 2) throw new IOException(tr("net.upload_http", "Image upload returned HTTP %s.", response.statusCode()));
        }
    }

    private AttachmentUpload() {}
}
