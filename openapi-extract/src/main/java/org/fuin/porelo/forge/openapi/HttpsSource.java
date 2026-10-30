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
        URI pinned = validate(source);
        // A dedicated client has no authenticator or cookie handler to supply credentials, and
        // follows no redirect:
        // a pinned source is read from its own address, so nothing is requested from a host the pin
        // does not name.
        try (HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            HttpRequest request =
                    HttpRequest.newBuilder(pinned).timeout(Duration.ofMinutes(2)).GET().build();
            HttpResponse<InputStream> response =
                    client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) {
                    throw new IOException(
                            "Download returned HTTP "
                                    + response.statusCode()
                                    + "; a redirect is not followed");
                }
                return readBounded(body, MAX_BYTES);
            }
        }
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
