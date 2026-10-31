package org.fuin.porelo.forge.github.replay;

import com.fasterxml.jackson.core.type.TypeReference;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.regex.Pattern;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import org.jspecify.annotations.Nullable;

/**
 * Answers from memory or a recorded file without opening a socket.
 *
 * <p>Answers are queued and given in order, one per request; after them the {@link #otherwise}
 * responder answers, if one is set. A recording is read from {@value #RECORDINGS}{@code
 * <name>.json}, as this repository's recorder wrote it. Only synchronous {@code send} is offered.
 */
public final class ReplayHttpClient extends HttpClient {
    /** The resource path of the recordings, each {@code <name>.json}. */
    public static final String RECORDINGS = "/org/fuin/porelo/forge/github/recordings/";

    private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9-]*");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final Queue<Answer> answers = new ArrayDeque<>();
    private final List<HttpRequest> requests = new ArrayList<>();
    private @Nullable Function<HttpRequest, Answer> otherwise;
    private volatile Redirect redirects = Redirect.NEVER;

    /** Queues an answer. */
    public synchronized ReplayHttpClient answer(Answer answer) {
        answers.add(Objects.requireNonNull(answer, "answer"));
        return this;
    }

    /** Queues an answer of a status, headers and body. */
    public ReplayHttpClient answer(int status, Map<String, List<String>> headers, String body) {
        return answer(new Answer(status, headers, body));
    }

    /** Answers every request after the queued answers, from the request itself. */
    public synchronized ReplayHttpClient otherwise(Function<HttpRequest, Answer> responder) {
        otherwise = Objects.requireNonNull(responder, "responder");
        return this;
    }

    /** Queues a recording's answers, in the order they were recorded. */
    public ReplayHttpClient recording(String name) throws IOException {
        List<Answer> recorded = recorded(name);
        synchronized (this) {
            answers.addAll(recorded);
        }
        return this;
    }

    /** Sets the redirect policy this client reports; it follows none itself. */
    public ReplayHttpClient redirects(Redirect policy) {
        redirects = Objects.requireNonNull(policy, "policy");
        return this;
    }

    /** Returns the requests sent so far, in order. */
    public synchronized List<HttpRequest> requests() {
        return List.copyOf(requests);
    }

    /**
     * Reads a recording's answers, in the order they were recorded, for a caller to queue as they
     * are or changed.
     *
     * @param name the recording's name, such as {@code reviewComments}
     * @throws IOException if there is no such recording or it cannot be read
     */
    public static List<Answer> recorded(String name) throws IOException {
        if (!NAME.matcher(name).matches()) {
            throw new IllegalArgumentException("Not a recording name: " + name);
        }
        try (var input = ReplayHttpClient.class.getResourceAsStream(RECORDINGS + name + ".json")) {
            if (input == null) {
                throw new IOException("Recording is missing: " + name);
            }
            JsonNode data = JSON.readTree(input);
            List<JsonNode> exchanges = new ArrayList<>();
            if (data.has("exchanges")) {
                data.path("exchanges").forEach(exchanges::add);
            } else {
                exchanges.add(data);
            }
            List<Answer> recorded = new ArrayList<>();
            for (JsonNode exchange : exchanges) {
                Map<String, List<String>> headers =
                        JSON.convertValue(exchange.path("headers"), new TypeReference<>() {});
                String body = exchange.path("body").textValue();
                if (body == null) {
                    throw new IOException("Recording has an exchange without a body: " + name);
                }
                recorded.add(new Answer(exchange.path("status").intValue(), headers, body));
            }
            return List.copyOf(recorded);
        }
    }

    private Answer next(HttpRequest request) {
        @Nullable Function<HttpRequest, Answer> responder;
        synchronized (this) {
            requests.add(request);
            Answer queued = answers.poll();
            if (queued != null) return queued;
            responder = otherwise;
        }
        if (responder == null) throw new NoSuchElementException("No answer left");
        return responder.apply(request);
    }

    /** Delivers text through the supplied BodyHandler, as a real transport does. */
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(handler, "handler");
        Answer answer = next(request);
        HttpHeaders headers = answer.httpHeaders();
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
                    private boolean terminated;

                    public void request(long count) {
                        if (terminated) return;
                        if (count <= 0) {
                            terminated = true;
                            subscriber.onError(
                                    new IllegalArgumentException(
                                            "Demand must be positive: " + count));
                            return;
                        }
                        if (sent) return;
                        sent = true;
                        subscriber.onNext(
                                List.of(
                                        java.nio.ByteBuffer.wrap(
                                                answer.body().getBytes(StandardCharsets.UTF_8))));
                        if (!terminated) {
                            terminated = true;
                            subscriber.onComplete();
                        }
                    }

                    public void cancel() {
                        terminated = true;
                    }
                });
        T body = bodyOf(subscriber);
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

    /** Waits for the body, and throws its failure as the JDK's {@code send} does: unwrapped. */
    private static <T> T bodyOf(HttpResponse.BodySubscriber<T> subscriber) throws IOException {
        try {
            return subscriber.getBody().toCompletableFuture().join();
        } catch (java.util.concurrent.CompletionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof IOException io) throw io;
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new IOException(cause);
        }
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

    /**
     * A status, headers and body to answer with.
     *
     * @param status the HTTP status
     * @param headers the headers, each name with its values; copied
     * @param body the body as text, sent as UTF-8
     */
    public record Answer(int status, Map<String, List<String>> headers, String body) {
        /**
         * Copies the headers, so a later change to the given map does not reach the answer, and
         * refuses names an {@link HttpHeaders} cannot hold, such as one given twice in different
         * case.
         */
        public Answer {
            headers = copy(headers);
            HttpHeaders.of(headers, (name, value) -> true);
            Objects.requireNonNull(body, "body");
        }

        HttpHeaders httpHeaders() {
            return HttpHeaders.of(headers, (name, value) -> true);
        }

        private static Map<String, List<String>> copy(Map<String, List<String>> headers) {
            Map<String, List<String>> copy = new LinkedHashMap<>();
            headers.forEach((name, values) -> copy.put(name, List.copyOf(values)));
            return Collections.unmodifiableMap(copy);
        }
    }
}
