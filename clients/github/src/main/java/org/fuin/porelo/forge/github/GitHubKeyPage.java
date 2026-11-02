package org.fuin.porelo.forge.github;

import java.net.http.HttpResponse;
import org.jspecify.annotations.Nullable;

/**
 * Reads an account's key page {@code <login>.keys}: a refused answer is not read at all, and a
 * plain text one only up to one byte past its bound. Its verdict says why it stopped.
 */
final class GitHubKeyPage implements HttpResponse.BodyHandler<byte[]> {
    static final int MAX_BYTES = 256 * 1024;

    volatile @Nullable GitHubException verdict;

    @Override
    public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
        if (info.statusCode() != 200) {
            verdict =
                    GitHubClient.refusal(
                            info.statusCode(), "GitHub key page request failed", info.headers());
            return BoundedBytes.refusing();
        }
        if (!isPlainText(info.headers().firstValue("Content-Type").orElse(""))) {
            verdict = new GitHubException(200, "GitHub key page is not plain text");
            return BoundedBytes.refusing();
        }
        // A transport that retries may call again; only this answer's verdict counts.
        verdict = null;
        return BoundedBytes.whole(
                MAX_BYTES,
                () -> verdict = new GitHubException(200, "GitHub key page exceeds its bound"));
    }

    private static boolean isPlainText(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .strip()
                .equalsIgnoreCase("text/plain");
    }
}
