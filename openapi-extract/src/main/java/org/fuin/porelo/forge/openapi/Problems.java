package org.fuin.porelo.forge.openapi;

import java.io.IOException;
import java.util.List;

final class Problems extends IOException {
    private final List<String> problems;

    Problems(List<String> problems) {
        super(String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    List<String> problems() {
        return problems;
    }
}
