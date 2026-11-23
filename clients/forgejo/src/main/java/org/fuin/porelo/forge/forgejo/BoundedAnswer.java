package org.fuin.porelo.forge.forgejo;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.jspecify.annotations.Nullable;

/**
 * Reads one request's answer up to its bound, a successful log to its end keeping its tail. Past
 * the bound the reading stops and {@link #exceeded} says by how much the answer was too long, since
 * a transport may hide a stopped body behind its own failure.
 */
final class BoundedAnswer implements HttpResponse.BodyHandler<String> {
    static final int ANSWER_BYTES = 16 * 1024 * 1024;
    static final int ERROR_BYTES = 64 * 1024;
    static final int LOG_TAIL_BYTES = 1024 * 1024;

    private final boolean log;
    private final Duration rateLimitDelay;
    private volatile @Nullable BoundedBytes bytes;
    private volatile @Nullable ForgejoException exceeded;

    private BoundedAnswer(boolean log, Duration rateLimitDelay) {
        this.log = log;
        this.rateLimitDelay = rateLimitDelay;
    }

    /** Reads an ordinary answer. */
    static BoundedAnswer json(Duration rateLimitDelay) {
        return new BoundedAnswer(false, rateLimitDelay);
    }

    /** Reads a job log, of which a successful answer keeps its tail. */
    static BoundedAnswer log(Duration rateLimitDelay) {
        return new BoundedAnswer(true, rateLimitDelay);
    }

    /** Returns the failure of an answer past its bound, or null when it was within. */
    @Nullable ForgejoException exceeded() {
        return exceeded;
    }

    /** Returns whether a log was longer than what was kept. */
    boolean cut() {
        BoundedBytes current = bytes;
        return current != null && current.cut();
    }

    @Override
    public HttpResponse.BodySubscriber<String> apply(HttpResponse.ResponseInfo info) {
        // A transport that retries may call again; only this answer's verdict counts.
        exceeded = null;
        boolean success = info.statusCode() >= 200 && info.statusCode() < 300;
        int bound = success ? ANSWER_BYTES : ERROR_BYTES;
        Runnable overflow =
                () ->
                        exceeded =
                                new ForgejoException(
                                        info.statusCode(),
                                        "Forgejo answer exceeds " + bound + " bytes",
                                        Delivery.ANSWERED,
                                        ForgejoClient.rateLimit(info.headers()),
                                        rateLimitDelay);
        BoundedBytes current =
                success && log
                        ? BoundedBytes.tail(LOG_TAIL_BYTES)
                        : BoundedBytes.whole(bound, overflow);
        bytes = current;
        return HttpResponse.BodySubscribers.mapping(
                current, read -> new String(read, StandardCharsets.UTF_8));
    }
}
