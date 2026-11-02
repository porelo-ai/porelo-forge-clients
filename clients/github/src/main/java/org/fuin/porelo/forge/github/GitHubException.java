package org.fuin.porelo.forge.github;

import java.time.Duration;
import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * A sanitized API, transport or payload failure, without request headers or raw response bodies.
 */
public final class GitHubException extends RuntimeException {
    private final int status;
    private final Delivery delivery;
    private final @Nullable RateLimit rateLimit;
    private final boolean rateLimited;

    GitHubException(int status, String message) {
        this(status, message, status == 0 ? Delivery.NOT_SENT : Delivery.ANSWERED, null, false);
    }

    GitHubException(
            int status,
            String message,
            Delivery delivery,
            @Nullable RateLimit rateLimit,
            boolean rateLimited) {
        super(message);
        this.status = status;
        this.delivery = delivery;
        this.rateLimit = rateLimit;
        this.rateLimited = rateLimited;
    }

    /** Returns the HTTP status, or zero for a transport, validation or payload failure. */
    public int status() {
        return status;
    }

    /** Returns whether the failed request may have reached GitHub. */
    public Delivery delivery() {
        return delivery;
    }

    /** Returns what the answer says of a rate limit, or null when it says nothing. */
    public @Nullable RateLimit rateLimit() {
        return rateLimit;
    }

    /** Returns whether this is a rate limit rather than a refusal. */
    public boolean rateLimited() {
        return rateLimited;
    }

    /** Returns how long to wait before trying again, or null when this is no rate limit. */
    public @Nullable Duration delay() {
        return delay(Instant.now());
    }

    @Nullable Duration delay(Instant now) {
        return rateLimited ? RateLimit.delay(rateLimit, now) : null;
    }
}
