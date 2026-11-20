package org.fuin.porelo.forge.github;

import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * The answer to an aliased GraphQL mutation: one outcome per part, in the order the caller gave
 * them. GitHub may save some parts and refuse others, so a caller checks {@link #complete()} before
 * it reads the batch as done.
 *
 * @param outcomes one per part sent, in the caller's order
 * @param rateLimit what the answer's headers said of the rate limit, or null when they said
 *     nothing; a mutation cannot ask for its cost in points
 */
public record Batch<T>(List<Outcome<T>> outcomes, @Nullable RateLimit rateLimit) {
    /** Keeps the outcomes from changing after the batch is read. */
    public Batch {
        outcomes = List.copyOf(outcomes);
    }

    /** Returns whether GitHub saved every part. */
    public boolean complete() {
        return outcomes.stream().allMatch(Outcome::saved);
    }

    /**
     * One part of a batch: what GitHub saved, or its message, scrubbed of credentials, saying why
     * it refused the part.
     *
     * @param value what GitHub answered for the part, null when it refused it
     * @param error GitHub's message for a refused part, null when it saved it
     */
    public record Outcome<T>(@Nullable T value, @Nullable String error) {
        /** Holds exactly one of a value and an error. */
        public Outcome {
            if ((value == null) == (error == null)) {
                throw new IllegalArgumentException("An outcome is a value or an error");
            }
        }

        /** Returns whether GitHub saved this part. */
        public boolean saved() {
            return value != null;
        }

        /** Makes the outcome of a saved part. */
        public static <T> Outcome<T> of(T value) {
            return new Outcome<>(Objects.requireNonNull(value, "value"), null);
        }

        /** Makes the outcome of a refused part. */
        public static <T> Outcome<T> refused(String error) {
            return new Outcome<>(null, Objects.requireNonNull(error, "error"));
        }
    }
}
