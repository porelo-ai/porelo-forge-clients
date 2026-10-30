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
        StubHttpClient client = new StubHttpClient(404, new byte[] {1});

        assertThatExceptionOfType(IOException.class)
                .as("a 404 answer is not a description")
                .isThrownBy(() -> HttpsSource.download(SOURCE, client))
                .withMessageContaining(SOURCE.toString())
                .withMessageContaining("HTTP 404");
        assertThat(client.bodyClosed).as("the refused answer's body is closed").isTrue();
    }

    // Prevents the description from being read from anywhere but the pinned source.
    @Test
    void anOkAnswerIsReadFromTheSourceAsSent() throws Exception {
        byte[] body = {'{', '}'};
        StubHttpClient client = new StubHttpClient(200, body);

        assertThat(HttpsSource.download(SOURCE, client)).as("read as sent").isEqualTo(body);
        assertThat(client.requested).as("only the source is asked").containsExactly(SOURCE);
        assertThat(client.bodyClosed).as("the body is closed").isTrue();
    }

    // Prevents a source that is not HTTPS from being asked at all.
    @Test
    void aSourceThatIsNotHttpsIsRefusedBeforeAnyRequest() {
        StubHttpClient client = new StubHttpClient(200, new byte[] {1});

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a plain HTTP source is refused")
                .isThrownBy(
                        () ->
                                HttpsSource.download(
                                        URI.create("http://example.org/description.json"), client));
        assertThat(client.requested).as("nothing was requested").isEmpty();
    }

    // Prevents a successful answer that streams without end from bypassing the bound.
    @Test
    void anOkAnswerLongerThanTheBoundIsRefused() {
        assertThatExceptionOfType(IOException.class)
                .as("a 200 answer of eleven bytes exceeds a bound of ten")
                .isThrownBy(
                        () ->
                                HttpsSource.bodyOf(
                                        SOURCE, 200, new ByteArrayInputStream(new byte[11]), 10))
                .withMessageContaining("larger than 10 bytes");
    }

    // Prevents the download from reading a body of any length before its digest is checked.
    @Test
    void aDownloadLongerThanSixtyFourMegabytesIsRefused() {
        StubHttpClient client = new StubHttpClient(200, HttpsSource.MAX_BYTES + 1L);

        assertThatExceptionOfType(IOException.class)
                .as("the download is bounded by MAX_BYTES")
                .isThrownBy(() -> HttpsSource.download(SOURCE, client))
                .withMessageContaining("larger than " + HttpsSource.MAX_BYTES + " bytes");
    }
}
