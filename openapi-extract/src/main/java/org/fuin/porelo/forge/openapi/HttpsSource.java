package org.fuin.porelo.forge.openapi;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;

final class HttpsSource {
    private static final Set<Integer> REDIRECTS = Set.of(301, 302, 303, 307, 308);
    private static final int MAX_REDIRECTS = 5;

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
        URI current = validate(source);
        // A dedicated client has no authenticator or cookie handler to supply credentials.
        try (HttpClient client =
                HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(30))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build()) {
            for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
                HttpRequest request =
                        HttpRequest.newBuilder(current)
                                .timeout(Duration.ofMinutes(2))
                                .GET()
                                .build();
                HttpResponse<byte[]> response =
                        client.send(request, HttpResponse.BodyHandlers.ofByteArray());
                if (response.statusCode() == 200) {
                    return response.body();
                }
                if (!REDIRECTS.contains(response.statusCode())) {
                    throw new IOException("Download returned HTTP " + response.statusCode());
                }
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(
                                        () -> new IOException("Redirect has no Location header"));
                current = validate(current.resolve(location));
            }
        }
        throw new IOException("Download exceeded " + MAX_REDIRECTS + " redirects");
    }
}
