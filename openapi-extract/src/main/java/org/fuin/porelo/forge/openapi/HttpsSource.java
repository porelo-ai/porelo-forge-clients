package org.fuin.porelo.forge.openapi;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

final class HttpsSource {
    /** GitHub's whole description is about 14 MB; a body far larger is not a description. */
    static final int MAX_BYTES = 64 * 1024 * 1024;

    private HttpsSource() {}

    static URI validate(URI source) {
        if (!"https".equalsIgnoreCase(source.getScheme())
                || source.getHost() == null
                || source.getRawUserInfo() != null
                || source.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "Source must be an HTTPS URL without user information or a fragment");
        }
        return source;
    }

    static byte[] download(URI source) throws IOException, InterruptedException {
        try (HttpClient client = newClient()) {
            return download(source, client);
        }
    }

    static byte[] download(URI source, HttpClient client) throws IOException, InterruptedException {
        URI pinned = validate(source);
        HttpRequest request =
                HttpRequest.newBuilder(pinned).timeout(Duration.ofMinutes(2)).GET().build();
        HttpResponse<InputStream> response =
                client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            return bodyOf(pinned, response.statusCode(), body, MAX_BYTES);
        }
    }

    /**
     * A client that follows redirects, since any server may answer with one, but never from HTTPS
     * to HTTP; it has no authenticator or cookie handler, so no credential reaches any host, and
     * what it reads is used only once its digest matches the pin.
     */
    static HttpClient newClient() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** The body of a successful answer, bounded; any other answer fails, naming the source. */
    static byte[] bodyOf(URI source, int status, InputStream body, int max) throws IOException {
        if (status != 200) {
            throw new IOException("Download of " + source + " returned HTTP " + status);
        }
        return readBounded(body, max);
    }

    /** Reads at most {@code max} bytes, refusing a longer body instead of holding it in memory. */
    static byte[] readBounded(InputStream body, int max) throws IOException {
        byte[] bytes = body.readNBytes(max + 1);
        if (bytes.length > max) {
            throw new IOException("Download is larger than " + max + " bytes");
        }
        return bytes;
    }
}
