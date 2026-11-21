package org.fuin.porelo.forge.github;

import java.time.Instant;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The answer to a conditional read: the value with its new entity tag, or, when GitHub answered
 * 304, no value and the tag that still holds.
 *
 * @param value what GitHub answered, null when nothing changed
 * @param etag the entity tag to send next time, as GitHub sent it, weak prefix included; on a 304
 *     without one, the tag the caller sent
 * @param changed whether GitHub answered with a new value rather than 304
 * @param rateLimit what the answer said of the rate limit, or null when it said nothing
 * @param date the time GitHub answered, from its {@code Date} header, or null when it sent none
 *     that reads; a caller asking what changed since an answer takes this, not its own clock, and
 *     with null has no start for its next look, so it does not narrow it
 */
public record Conditional<T>(
        @Nullable T value,
        @Nullable String etag,
        boolean changed,
        @Nullable RateLimit rateLimit,
        @Nullable Instant date) {
    /** Holds a value exactly when it changed. */
    public Conditional {
        if (changed != (value != null)) {
            throw new IllegalArgumentException(
                    changed ? "A changed answer carries its value" : "A 304 carries no value");
        }
        if (!changed) Objects.requireNonNull(etag, "etag");
    }
}
