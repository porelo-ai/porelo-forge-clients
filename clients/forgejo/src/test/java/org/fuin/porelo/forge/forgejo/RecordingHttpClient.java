package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;

/** Observes only responses to the client calls; output remains in memory until scrubbed. */
final class RecordingHttpClient extends HttpClient {
    private final HttpClient delegate;
    private final ObjectMapper json = new ObjectMapper();
    private final List<ObjectNode> exchanges = new ArrayList<>();

    RecordingHttpClient(HttpClient delegate) {
        this.delegate = delegate;
    }

    List<ObjectNode> take() {
        List<ObjectNode> result = List.copyOf(exchanges);
        exchanges.clear();
        return result;
    }

    /** Captures text responses from a live transport without persisting headers or tokens. */
    @Override
    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
            throws IOException, InterruptedException {
        HttpResponse<String> response =
                delegate.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        String body = response.body();
        ObjectNode recorded = json.createObjectNode();
        // Redirect queries may contain storage signatures that are unknown to the fixture.
        if (exchanges.isEmpty()) {
            recorded.put(
                    "request_uri",
                    request.uri().getRawPath()
                            + (request.uri().getRawQuery() == null
                                    ? ""
                                    : "?" + request.uri().getRawQuery()));
        }
        recorded.put("status", response.statusCode());
        Map<String, List<String>> headers = new java.util.LinkedHashMap<>();
        for (String name : List.of("content-type", "location")) {
            List<String> values = response.headers().allValues(name);
            if (!values.isEmpty()) {
                headers.put(name, values);
            }
        }
        recorded.set("headers", json.valueToTree(headers));
        recorded.put("body", body);
        exchanges.add(recorded);
        return ReplayHttpClient.deliver(
                request, response.statusCode(), response.headers(), body, handler);
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Optional<CookieHandler> cookieHandler() {
        return delegate.cookieHandler();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Optional<Duration> connectTimeout() {
        return delegate.connectTimeout();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Redirect followRedirects() {
        return delegate.followRedirects();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Optional<ProxySelector> proxy() {
        return delegate.proxy();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public SSLContext sslContext() {
        return delegate.sslContext();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public SSLParameters sslParameters() {
        return delegate.sslParameters();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Optional<Authenticator> authenticator() {
        return delegate.authenticator();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Version version() {
        return delegate.version();
    }

    /** Uses the caller’s transport configuration. */
    @Override
    public Optional<Executor> executor() {
        return delegate.executor();
    }

    /** Live scenarios use synchronous calls only. */
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        throw new UnsupportedOperationException();
    }

    /** Live scenarios use synchronous calls only. */
    @Override
    public <T> CompletableFuture<HttpResponse<T>> sendAsync(
            HttpRequest request,
            HttpResponse.BodyHandler<T> handler,
            HttpResponse.PushPromiseHandler<T> push) {
        throw new UnsupportedOperationException();
    }
}
