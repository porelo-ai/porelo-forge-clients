package org.fuin.porelo.forge.github;

import java.net.http.HttpHeaders;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import org.jspecify.annotations.Nullable;

/**
 * What an answer says of a rate limit. A value the answer does not carry, or carries in a form that
 * cannot be read, is null.
 *
 * @param limit the requests allowed in the window
 * @param remaining the requests left in the window
 * @param reset when the window starts anew
 * @param retryAfter how long the answer asks to wait
 */
public record RateLimit(
        @Nullable Long limit,
        @Nullable Long remaining,
        @Nullable Instant reset,
        @Nullable Duration retryAfter) {

    private static final Duration WITHOUT_A_WORD = Duration.ofMinutes(1);

    /** Reads an answer's headers, or null when it has none of them. */
    static @Nullable RateLimit read(HttpHeaders headers, Instant now) {
        RateLimit found =
                new RateLimit(
                        count(headers, "x-ratelimit-limit"),
                        count(headers, "x-ratelimit-remaining"),
                        reset(headers),
                        retryAfter(headers, now));
        return found.equals(new RateLimit(null, null, null, null)) ? null : found;
    }

    /** How long to wait: what the answer asks, else until the reset, else one minute. */
    static Duration delay(@Nullable RateLimit limit, Instant now) {
        if (limit != null && limit.retryAfter() != null) {
            return limit.retryAfter();
        }
        if (limit != null && limit.reset() != null) {
            Duration until = Duration.between(now, limit.reset());
            return until.isNegative() ? Duration.ZERO : until;
        }
        return WITHOUT_A_WORD;
    }

    private static @Nullable Long count(HttpHeaders headers, String name) {
        String value = headers.firstValue(name).orElse("").strip();
        if (!value.matches("[0-9]{1,18}")) {
            return null;
        }
        return Long.valueOf(value);
    }

    private static @Nullable Instant reset(HttpHeaders headers) {
        Long seconds = count(headers, "x-ratelimit-reset");
        if (seconds == null) {
            return null;
        }
        try {
            return Instant.ofEpochSecond(seconds);
        } catch (DateTimeException ex) {
            return null;
        }
    }

    private static @Nullable Duration retryAfter(HttpHeaders headers, Instant now) {
        Long seconds = count(headers, "retry-after");
        if (seconds != null) {
            return Duration.ofSeconds(seconds);
        }
        String value = headers.firstValue("retry-after").orElse("").strip();
        if (value.isEmpty()) {
            return null;
        }
        try {
            Instant at =
                    ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
            Duration until = Duration.between(now, at);
            return until.isNegative() ? Duration.ZERO : until;
        } catch (DateTimeParseException ex) {
            return null;
        }
    }
}
