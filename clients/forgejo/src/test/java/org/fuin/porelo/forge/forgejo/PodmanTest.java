package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PodmanTest {
    @Test
    void credentialBearingContainersCannotEnablePersistentOutputLogs() {
        for (String[] command :
                new String[][] {
                    {"run", "fixture-image"},
                    {"run", "--log-driver=json-file", "fixture-image"},
                    {"run", "--log-driver=none", "--log-driver=journald", "fixture-image"}
                }) {
            assertThatThrownBy(() -> Podman.command(command))
                    .as(
                            "runner registration emits its identity on stdout and must never persist it")
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(Podman.command("run", "--log-driver=none", "fixture-image"))
                .as("an explicitly nonlogging container may be launched")
                .containsExactly("podman", "run", "--log-driver=none", "fixture-image");
    }
}
