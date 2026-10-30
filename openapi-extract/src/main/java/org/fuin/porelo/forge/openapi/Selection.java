package org.fuin.porelo.forge.openapi;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

record Selection(Set<String> operations, Set<String> webhooks) {
    static final Set<String> METHODS =
            Set.of("get", "put", "post", "delete", "options", "head", "patch", "trace");

    Selection {
        operations = Collections.unmodifiableSet(new LinkedHashSet<>(operations));
        webhooks = Collections.unmodifiableSet(new LinkedHashSet<>(webhooks));
        if (operations.isEmpty()) {
            throw new IllegalArgumentException("At least one --operation is required");
        }
        for (String operation : operations) {
            int separator = operation.indexOf(' ');
            if (separator < 1
                    || !METHODS.contains(operation.substring(0, separator))
                    || !operation.substring(separator + 1).startsWith("/")
                    || operation.chars().anyMatch(Character::isISOControl)
                    || operation.substring(separator + 1).contains(" ")) {
                throw new IllegalArgumentException(
                        "Operation must be '<lowercase method> <path>': " + operation);
            }
        }
        if (webhooks.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("Webhook names must not be blank");
        }
    }
}
