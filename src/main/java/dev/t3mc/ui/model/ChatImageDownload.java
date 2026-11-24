package dev.t3mc.ui.model;

import dev.t3mc.L10n;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;

/** User-initiated read of an image in message text. This client has no authenticated T3 session or cookie store. */
final class ChatImageDownload {
    private static final HttpClient HTTP = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(8)).build();

    static byte[] read(URI uri) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        URI current = uri;
        for (int hops = 0; hops <= 5; hops++) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new ChatImage.PreviewError(L10n.tr("chat.image.host_timeout", "Image host timed out."));
            var req = HttpRequest.newBuilder(current).timeout(Duration.ofNanos(remaining))
                    .header("Accept", "image/png,image/jpeg,image/gif,image/webp").GET().build();
            var future = HTTP.sendAsync(req, info -> {
                if (info.statusCode() != 200) {
                    // Read only the redirect/error headers. A host may send a large response body here.
                    var headersOnly = new BoundedBody();
                    headersOnly.result.complete(new byte[0]);
                    return headersOnly;
                }
                if (info.headers().firstValueAsLong("Content-Length").orElse(0) > LocalImage.MAX_BYTES)
                    return rejected();
                return new BoundedBody();
            });
            HttpResponse<byte[]> response;
            try { response = future.get(remaining, TimeUnit.NANOSECONDS); }
            catch (InterruptedException e) {
                future.cancel(true);
                Thread.currentThread().interrupt();
                throw new ChatImage.PreviewError(L10n.tr("chat.image.interrupted", "Image loading interrupted."));
            } catch (TimeoutException e) {
                future.cancel(true);
                throw new ChatImage.PreviewError(L10n.tr("chat.image.host_timeout", "Image host timed out."));
            } catch (ExecutionException e) {
                if (e.getCause() instanceof SizeLimit) throw new ChatImage.PreviewError(L10n.tr("chat.image.exceeds_limit", "Image exceeds 10 MiB."));
                throw new ChatImage.PreviewError(L10n.tr("chat.image.load_failed", "Image could not be loaded."));
            }
            if (response.statusCode() == 200) return response.body();
            if (!isRedirect(response.statusCode())) throw new ChatImage.PreviewError(L10n.tr("chat.image.http_error", "Image host returned HTTP %s.", response.statusCode()));
            var location = response.headers().firstValue("Location").orElseThrow(() -> new ChatImage.PreviewError(L10n.tr("chat.image.redirect_missing", "Missing image redirect.")));
            current = redirect(current, location);
        }
        throw new ChatImage.PreviewError(L10n.tr("chat.image.redirect_limit", "Too many image redirects."));
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    static URI redirect(URI current, String location) throws IOException {
        try {
            var next = new ChatImage.Web(current.resolve(location)).url();
            if ("https".equalsIgnoreCase(current.getScheme()) && !"https".equalsIgnoreCase(next.getScheme()))
                throw new ChatImage.PreviewError(L10n.tr("chat.image.redirect_insecure", "Image host redirected to an insecure address."));
            return next;
        } catch (IllegalArgumentException invalid) { throw new ChatImage.PreviewError(L10n.tr("chat.image.redirect_invalid", "Invalid image redirect.")); }
    }

    private static final class SizeLimit extends IOException {}

    private static HttpResponse.BodySubscriber<byte[]> rejected() {
        var body = new BoundedBody();
        body.result.completeExceptionally(new SizeLimit());
        return body;
    }

    private static final class BoundedBody implements HttpResponse.BodySubscriber<byte[]> {
        final CompletableFuture<byte[]> result = new CompletableFuture<>();
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            if (result.isDone()) subscription.cancel();
            else subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> batch) {
            for (ByteBuffer buffer : batch) {
                if (buffer.remaining() > LocalImage.MAX_BYTES - bytes.size()) {
                    result.completeExceptionally(new SizeLimit());
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[buffer.remaining()];
                buffer.get(chunk);
                bytes.writeBytes(chunk);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
