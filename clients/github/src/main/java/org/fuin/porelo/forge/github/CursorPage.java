package org.fuin.porelo.forge.github;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list GitHub pages by cursor.
 *
 * @param items what this page holds
 * @param after the cursor of the next page, read from GitHub's {@code Link} header, or null when
 *     this page is the last
 */
public record CursorPage<T>(List<T> items, @Nullable String after) {
    /** Keeps the items from changing after the page is read. */
    public CursorPage {
        items = List.copyOf(items);
    }
}
