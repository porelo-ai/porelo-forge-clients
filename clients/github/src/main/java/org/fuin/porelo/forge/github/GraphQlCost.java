package org.fuin.porelo.forge.github;

import java.time.Instant;
import java.util.Objects;

/**
 * What a GraphQL query cost of the hour's points, as its {@code rateLimit} field says.
 *
 * @param cost the points this query cost
 * @param remaining the points left in the window
 * @param resetAt when the window starts anew
 */
public record GraphQlCost(int cost, int remaining, Instant resetAt) {
    /** Requires the reset time. */
    public GraphQlCost {
        Objects.requireNonNull(resetAt, "resetAt");
    }
}
