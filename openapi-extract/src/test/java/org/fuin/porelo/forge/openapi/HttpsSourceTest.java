package org.fuin.porelo.forge.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import org.junit.jupiter.api.Test;

class HttpsSourceTest {
    private static final URI SOURCE = URI.create("https://example.org/description.json");

    // Prevents a source that streams without end from exhausting the build's memory before its
    // digest is checked.
    @Test
    void aBodyLongerThanTheBoundIsRefused() {
        assertThatExceptionOfType(IOException.class)
                .as("eleven bytes exceed a bound of ten")
                .isThrownBy(
                        () -> HttpsSource.readBounded(new ByteArrayInputStream(new byte[11]), 10))
                .withMessageContaining("larger than 10 bytes");
    }

    // Prevents the bound from cutting off or altering a description that fits it exactly.
    @Test
    void aBodyAtTheBoundIsReadWhole() throws IOException {
        byte[] body = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10};

        assertThat(HttpsSource.readBounded(new ByteArrayInputStream(body), 10))
                .as("ten bytes fit a bound of ten and are read as sent")
                .isEqualTo(body);
    }

    // Prevents a forge that answers its pinned address with a redirect from failing the fetch,
    // and a redirect from sending the request on in the clear.
    @Test
    void theClientFollowsRedirectsButNeverToHttp() {
        try (HttpClient client = HttpsSource.newClient()) {
            assertThat(client.followRedirects())
                    .as("NORMAL follows redirects except from HTTPS to HTTP")
                    .isEqualTo(HttpClient.Redirect.NORMAL);
        }
    }

    // Prevents a credential from reaching the source or a host it redirects to.
    @Test
    void theClientHasNoCredentialsToSend() {
        try (HttpClient client = HttpsSource.newClient()) {
            assertThat(client.authenticator()).as("no authenticator").isEmpty();
            assertThat(client.cookieHandler()).as("no cookie handler").isEmpty();
        }
    }

    // Prevents an error page from being taken for the description, and its failure from hiding
    // what was asked for.
    @Test
    void anAnswerOtherThanOkFailsNamingTheSourceAndStatus() {
        assertThatExceptionOfType(IOException.class)
                .as("a 404 answer is not a description")
                .isThrownBy(
                        () ->
                                HttpsSource.bodyOf(
                                        SOURCE, 404, new ByteArrayInputStream(new byte[] {1})))
                .withMessageContaining(SOURCE.toString())
                .withMessageContaining("HTTP 404");
    }

    // Prevents a successful answer from bypassing the bound.
    @Test
    void anOkAnswerIsReadBounded() throws IOException {
        byte[] body = {'{', '}'};

        assertThat(HttpsSource.bodyOf(SOURCE, 200, new ByteArrayInputStream(body)))
                .as("a 200 answer is read as sent")
                .isEqualTo(body);
    }
}
