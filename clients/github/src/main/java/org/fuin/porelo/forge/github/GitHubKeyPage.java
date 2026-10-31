package org.fuin.porelo.forge.github;

import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import org.jspecify.annotations.Nullable;

/**
 * Reads an account's key page {@code <login>.keys}: a refused answer is not read at all, and a
 * plain text one only up to one byte past its bound. Its verdict says why it stopped.
 */
final class GitHubKeyPage implements HttpResponse.BodyHandler<byte[]> {
    static final int MAX_BYTES = 256 * 1024;

    volatile @Nullable GitHubException verdict;

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            verdict = new GitHubException(info.statusCode(), "GitHub key page request failed");
            return new BoundedBytes(0, this);
        }
        if (!isPlainText(info.headers().firstValue("Content-Type").orElse(""))) {
            verdict = new GitHubException(200, "GitHub key page is not plain text");
            return new BoundedBytes(0, this);
        }
        // A transport that retries may call again; only this answer's verdict counts.
        verdict = null;
        return new BoundedBytes(MAX_BYTES, this);
    }

    private static boolean isPlainText(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .strip()
                .equalsIgnoreCase("text/plain");
    }

    /**
     * Collects at most its bound, one buffer at a time. One byte more, or any byte when the page
     * already has a verdict, stops the reading; a bound of zero for a refused page stops it at
     * once.
     */
    private static final class BoundedBytes implements HttpResponse.BodySubscriber<byte[]> {
        private final int bound;
        private final GitHubKeyPage page;
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private volatile Flow.@Nullable Subscription subscription;

        BoundedBytes(int bound, GitHubKeyPage page) {
            this.bound = bound;
            this.page = page;
        }

        @Override
        public CompletionStage<byte[]> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            Objects.requireNonNull(subscription);
            if (this.subscription != null || result.isDone()) {
                subscription.cancel();
                return;
            }
            this.subscription = subscription;
            if (page.verdict != null) {
                stop(subscription);
            } else {
                subscription.request(1);
            }
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            Objects.requireNonNull(items);
            Flow.Subscription current = subscription;
            if (result.isDone() || current == null) {
                return;
            }
            for (ByteBuffer item : items) {
                int take = Math.min(item.remaining(), bound + 1 - bytes.size());
                byte[] chunk = new byte[take];
                item.get(chunk);
                bytes.writeBytes(chunk);
                if (bytes.size() > bound) {
                    page.verdict = new GitHubException(200, "GitHub key page exceeds its bound");
                    stop(current);
                    return;
                }
            }
            current.request(1);
        }

        /** Completes before cancelling, since a transport may fail the exchange on the cancel. */
        private void stop(Flow.Subscription current) {
            result.complete(new byte[0]);
            current.cancel();
        }

        @Override
        public void onError(Throwable error) {
            result.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            result.complete(bytes.toByteArray());
        }
    }
}
