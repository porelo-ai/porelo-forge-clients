package org.fuin.porelo.forge.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import org.junit.jupiter.api.Test;

class HttpsSourceTest {
    // Prevents a source that streams without end from exhausting the build's memory before its
    // digest is checked.
    @Test
    void aBodyLongerThanTheBoundIsRefused() {
        assertThatThrownBy(
                        () -> HttpsSource.readBounded(new ByteArrayInputStream(new byte[11]), 10))
                .as("eleven bytes exceed a bound of ten")
                .isInstanceOf(IOException.class)
                .hasMessageContaining("larger than 10 bytes");
    }

    // Prevents the bound from cutting off a description that fits it exactly.
    @Test
    void aBodyAtTheBoundIsReadWhole() throws IOException {
        assertThat(HttpsSource.readBounded(new ByteArrayInputStream(new byte[10]), 10))
                .as("ten bytes fit a bound of ten")
                .hasSize(10);
    }
}
