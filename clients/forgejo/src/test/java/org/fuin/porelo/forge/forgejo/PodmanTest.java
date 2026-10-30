package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PodmanTest {
    // Prevents runner identity and credentials from persisting in container output logs.
    @ParameterizedTest
    @ValueSource(strings = {"run", "create"})
    void credentialBearingContainersCannotEnablePersistentOutputLogs(String operation) {
        for (String[] command :
                new String[][] {
                    {operation, "fixture-image"},
                    {operation, "--log-driver=json-file", "fixture-image"},
                    {operation, "--log-driver=none", "--log-driver=journald", "fixture-image"}
                }) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as(
                            "runner registration emits its identity on stdout and must never persist it")
                    .isThrownBy(() -> Podman.command(command));
        }
        assertThat(Podman.command(operation, "--log-driver=none", "fixture-image"))
                .as("an explicitly nonlogging container may be launched")
                .containsExactly("podman", operation, "--log-driver=none", "fixture-image");
    }
}
