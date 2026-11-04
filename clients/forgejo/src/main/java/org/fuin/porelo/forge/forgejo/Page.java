package org.fuin.porelo.forge.forgejo;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * One page of a list.
 *
 * @param items what this page holds
 * @param more whether Forgejo says a further page follows
 * @param total how many the whole list holds, when Forgejo counts it
 */
public record Page<T>(List<T> items, boolean more, @Nullable Long total) {
    /** Keeps the items from changing after the page is read. */
    public Page {
        items = List.copyOf(items);
    }

    /** A page of a list whose length Forgejo does not say. */
    public Page(List<T> items, boolean more) {
        this(items, more, null);
    }
}
