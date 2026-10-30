package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;

/** Answers from memory or a recorded file without opening a socket. */
final class ReplayHttpClient extends HttpClient {
    private final Queue<Answer> answers = new ArrayDeque<>();
    final List<HttpRequest> requests = new ArrayList<>();
    Redirect redirects = Redirect.NEVER;

    ReplayHttpClient answer(int status, Map<String, List<String>> headers, String body) {
        answers.add(new Answer(status, headers, body));
        return this;
    }

    ReplayHttpClient recording(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/recordings/" + name + ".json")) {
            if (input == null) {
                throw new IOException("Recording is missing: " + name);
            }
            JsonNode data = new ObjectMapper().readTree(input);
            List<JsonNode> exchanges = new ArrayList<>();
            if (data.has("exchanges")) {
                data.path("exchanges").forEach(exchanges::add);
            } else {
                exchanges.add(data);
            }
            for (JsonNode exchange : exchanges) {
                Map<String, List<String>> headers =
                        new ObjectMapper()
                                .convertValue(
                                        exchange.path("headers"),
                                        new com.fasterxml.jackson.core.type.TypeReference<>() {});
                answer(
                        exchange.path("status").intValue(),
                        headers,
                        exchange.path("body").textValue());
            }
            return this;
        }
    }

    /** Delivers text through the supplied BodyHandler, as a real transport does. */
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        requests.add(request);
        Answer answer = answers.remove();
        HttpHeaders headers = HttpHeaders.of(answer.headers(), (key, value) -> true);
        HttpResponse.ResponseInfo info =
                new HttpResponse.ResponseInfo() {
                    public int statusCode() {
                        return answer.status();
                    }

                    public HttpHeaders headers() {
                        return headers;
                    }

                    public Version version() {
                        return Version.HTTP_1_1;
                    }
                };
        var subscriber = handler.apply(info);
        subscriber.onSubscribe(
                new java.util.concurrent.Flow.Subscription() {
                    private boolean sent;

                    public void request(long count) {
                        if (!sent) {
                            sent = true;
                            subscriber.onNext(
                                    List.of(
                                            java.nio.ByteBuffer.wrap(
                                                    answer.body()
                                                            .getBytes(StandardCharsets.UTF_8))));
                            subscriber.onComplete();
                        }
                    }

                    public void cancel() {
                        sent = true;
                    }
                });
        T body = subscriber.getBody().toCompletableFuture().join();
        return new HttpResponse<>() {
            public int statusCode() {
                return answer.status();
            }

            public HttpRequest request() {
                return request;
            }

            public Optional<HttpResponse<T>> previousResponse() {
                return Optional.empty();
            }

            public HttpHeaders headers() {
                return headers;
            }

            public T body() {
                return body;
            }

            public Optional<SSLSession> sslSession() {
                return Optional.empty();
            }

            public URI uri() {
                return request.uri();
            }

            public Version version() {
                return Version.HTTP_1_1;
            }
        };
    }

    /** This fake implements only synchronous calls. */
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    /** This fake implements only synchronous calls. */
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        throw new UnsupportedOperationException();
    }

    /** No cookies are installed. */
    @Override
    public Optional<CookieHandler> cookieHandler() {
        return Optional.empty();
    }

    /** No connection timeout is needed for a fake. */
    @Override
    public Optional<Duration> connectTimeout() {
        return Optional.empty();
    }

    /** Returns the redirect policy under test. */
    @Override
    public Redirect followRedirects() {
        return redirects;
    }

    /** No proxy is installed. */
    @Override
    public Optional<ProxySelector> proxy() {
        return Optional.empty();
    }

    /** SSL is unused by this transport. */
    @Override
    public SSLContext sslContext() {
        throw new UnsupportedOperationException();
    }

    /** Returns empty SSL parameters. */
    @Override
    public SSLParameters sslParameters() {
        return new SSLParameters();
    }

    /** No authenticator is installed. */
    @Override
    public Optional<Authenticator> authenticator() {
        return Optional.empty();
    }

    /** Returns the fake protocol version. */
    @Override
    public Version version() {
        return Version.HTTP_1_1;
    }

    /** No executor is installed. */
    @Override
    public Optional<Executor> executor() {
        return Optional.empty();
    }

    private record Answer(int status, Map<String, List<String>> headers, String body) {}
}
