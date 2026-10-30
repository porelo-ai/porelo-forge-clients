package org.fuin.porelo.forge.openapi;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.InputStream;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/**
 * Answers every request with one status and body, remembering what was asked and whether it was
 * closed.
 */
final class StubHttpClient extends HttpClient {
    private final int status;
    private final Supplier<InputStream> body;
    final List<URI> requested = new ArrayList<>();
    boolean bodyClosed;

    StubHttpClient(int status, byte[] body) {
        this(status, () -> new ByteArrayInputStream(body.clone()));
    }

    /** Answers with {@code length} zero bytes, generated as they are read rather than held. */
    StubHttpClient(int status, long length) {
        this(
                status,
                () ->
                        new InputStream() {
                            private long left = length;

                            @Override
                            public int read() {
                                if (left == 0) {
                                    return -1;
                                }
                                left--;
                                return 0;
                            }

                            @Override
                            public int read(byte[] buffer, int offset, int count) {
                                if (left == 0) {
                                    return -1;
                                }
                                int read = (int) Math.min(count, left);
                                java.util.Arrays.fill(buffer, offset, offset + read, (byte) 0);
                                left -= read;
                                return read;
                            }
                        });
    }

    private StubHttpClient(int status, Supplier<InputStream> body) {
        this.status = status;
        this.body = body;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        requested.add(request.uri());
        InputStream stream =
                new FilterInputStream(body.get()) {
                    @Override
                    public void close() {
                        bodyClosed = true;
                    }
                };
        // HttpsSource reads with BodyHandlers.ofInputStream, so the body is the stream itself.
        T typed = (T) stream;
        return new HttpResponse<>() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpRequest request() {
                return request;
            }

            @Override
            public Optional<HttpResponse<T>> previousResponse() {
                return Optional.empty();
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of(), (name, value) -> true);
            }

            @Override
            public T body() {
                return typed;
            }

            @Override
            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            @Override
            public URI uri() {
                return request.uri();
            }

            @Override
            public Version version() {
                return Version.HTTP_1_1;
            }
        };
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
            HttpResponse.@Nullable PushPromiseHandler<T> pushPromiseHandler) {
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
        return Redirect.NORMAL;
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
        return new SSLParameters();
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
