package org.fuin.porelo.forge.forgejo;

import java.util.List;

/**
 * One page of a list.
 *
 * @param items what this page holds
 * @param more whether Forgejo says a further page follows
 */
public record Page<T>(List<T> items, boolean more) {
    /** Keeps the items from changing after the page is read. */
    public Page {
        items = List.copyOf(items);
    }
}
