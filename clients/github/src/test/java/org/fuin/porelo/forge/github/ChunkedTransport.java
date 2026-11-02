package org.fuin.porelo.forge.github;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/** Fails a request, or hands an answer over in small buffers, counting what it handed over. */
final class ChunkedTransport extends HttpClient {
    static final int SIZE = 8 * 1024;
    private final int status;
    private final Map<String, List<String>> headers;
    private final byte[] body;
    private final @Nullable Exception failure;
    int delivered;

    ChunkedTransport(int status, Map<String, List<String>> headers, String body) {
        this.status = status;
        this.headers = headers;
        this.body = body.getBytes(StandardCharsets.UTF_8);
        failure = null;
    }

    ChunkedTransport(Exception failure) {
        status = 0;
        headers = Map.of();
        body = new byte[0];
        this.failure = failure;
    }

    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        if (failure instanceof IOException broken) {
            throw broken;
        }
        if (failure instanceof InterruptedException interrupted) {
            throw interrupted;
        }
        HttpHeaders answerHeaders = HttpHeaders.of(headers, (key, values) -> true);
        var subscriber =
                handler.apply(
                        new HttpResponse.ResponseInfo() {
                            @Override
                            public int statusCode() {
                                return status;
                            }

                            @Override
                            public HttpHeaders headers() {
                                return answerHeaders;
                            }

                            @Override
                            public Version version() {
                                return Version.HTTP_1_1;
                            }
                        });
        boolean[] cancelled = {false};
        long[] demand = {0};
        subscriber.onSubscribe(
                new Flow.Subscription() {
                    @Override
                    public void request(long count) {
                        demand[0] += count;
                    }

                    @Override
                    public void cancel() {
                        cancelled[0] = true;
                    }
                });
        while (!cancelled[0] && delivered < body.length && demand[0] > 0) {
            demand[0]--;
            int take = Math.min(SIZE, body.length - delivered);
            subscriber.onNext(List.of(ByteBuffer.wrap(body, delivered, take)));
            delivered += take;
        }
        CompletableFuture<T> future = subscriber.getBody().toCompletableFuture();
        if (cancelled[0]) {
            // Over HTTP/1.1 send waits for the body after a cancel, so a subscriber that has not
            // completed its body first would hang there.
            if (!future.isDone() || future.isCompletedExceptionally()) {
                throw new AssertionError("The subscriber cancelled without completing its body");
            }
            // Over HTTP/2, which api.github.com speaks, a cancelled body fails the whole exchange.
            throw new IOException("Stream cancelled");
        }
        if (delivered < body.length) {
            // The connection stays taken for as long as nobody cancels.
            throw new AssertionError("The subscriber stopped reading without cancelling");
        }
        subscriber.onComplete();
        T read = future.join();
        return new Answer<>(request, status, answerHeaders, read);
    }

    private record Answer<T>(HttpRequest request, int statusCode, HttpHeaders headers, T body)
            implements HttpResponse<T> {
        @Override
        public Optional<HttpResponse<T>> previousResponse() {
            return Optional.empty();
        }

        @Override
        public Optional<SSLSession> sslSession() {
            return Optional.empty();
        }

        @Override
        public java.net.URI uri() {
            return request.uri();
        }

        @Override
        public Version version() {
            return Version.HTTP_1_1;
        }
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    @Override
    public Redirect followRedirects() {
        return Redirect.NEVER;
    }

    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters sslParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }
}
