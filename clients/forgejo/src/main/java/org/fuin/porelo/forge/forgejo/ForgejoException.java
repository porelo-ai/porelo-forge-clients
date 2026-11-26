package org.fuin.porelo.forge.forgejo;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A sanitized API, transport or payload failure, without request headers or raw response bodies.
 */
public final class ForgejoException extends RuntimeException {
    private static final int TOO_MANY_REQUESTS = 429;
    private final int status;
    private final Delivery delivery;
    private final @Nullable RateLimit rateLimit;
    private final @Nullable Duration rateLimitDelay;
    private final @Nullable String forgeMessage;
    private final List<String> forgeDetails;

    ForgejoException(int status, String message) {
        this(status, message, status == 0 ? Delivery.NOT_SENT : Delivery.ANSWERED, null);
    }

    ForgejoException(int status, String message, Delivery delivery, @Nullable RateLimit rateLimit) {
        this(status, message, delivery, rateLimit, null);
    }

    ForgejoException(
            int status,
            String message,
            Delivery delivery,
            @Nullable RateLimit rateLimit,
            @Nullable Duration rateLimitDelay) {
        this(status, message, delivery, rateLimit, rateLimitDelay, null, List.of());
    }

    ForgejoException(
            int status,
            String message,
            Delivery delivery,
            @Nullable RateLimit rateLimit,
            @Nullable Duration rateLimitDelay,
            @Nullable String forgeMessage,
            List<String> forgeDetails) {
        super(message);
        if (status == TOO_MANY_REQUESTS && rateLimitDelay == null) {
            throw new IllegalArgumentException(
                    "A rate limit needs the wait to use when its answer names none");
        }
        this.rateLimitDelay = rateLimitDelay;
        this.status = status;
        this.delivery = delivery;
        this.rateLimit = rateLimit;
        this.forgeMessage = forgeMessage;
        this.forgeDetails = List.copyOf(forgeDetails);
    }

    /**
     * Returns the id Forgejo gave the request, which is always null: Forgejo sends no request id
     * header on its answers.
     */
    public @Nullable String requestId() {
        return null;
    }

    /**
     * Returns Forgejo's own message word for word, scrubbed of the request's credentials, or null
     * when Forgejo's answer gave none; {@link #getMessage()} may then be the client's own words.
     */
    public @Nullable String forgeMessage() {
        return forgeMessage;
    }

    /**
     * Returns each entry of Forgejo's {@code errors} list in its order, scrubbed of the request's
     * credentials, or an empty list when it gave none.
     */
    public List<String> forgeDetails() {
        return forgeDetails;
    }

    /** Returns the HTTP status, or zero for a transport, validation, payload or webhook failure. */
    public int status() {
        return status;
    }

    /** Returns whether the failed request may have reached Forgejo. */
    public Delivery delivery() {
        return delivery;
    }

    /** Returns what the answer says of a rate limit, or null when it says nothing. */
    public @Nullable RateLimit rateLimit() {
        return rateLimit;
    }

    /** Returns whether this is a rate limit rather than a refusal: only a 429 is one on Forgejo. */
    public boolean rateLimited() {
        return status == TOO_MANY_REQUESTS;
    }

    /** Returns how long to wait before trying again, or null when this is no rate limit. */
    public @Nullable Duration delay() {
        return delay(Instant.now());
    }

    @Nullable Duration delay(Instant now) {
        return rateLimited() && rateLimitDelay != null
                ? RateLimit.delay(rateLimit, now, rateLimitDelay)
                : null;
    }
}
