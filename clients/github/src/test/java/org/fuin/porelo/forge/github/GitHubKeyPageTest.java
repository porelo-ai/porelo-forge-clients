package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
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
import org.junit.jupiter.api.Test;

/** The key page as the JDK's transport delivers it: in several buffers, and failing on a stop. */
class GitHubKeyPageTest {
    private static final URI BASE = GitHubRecordingTest.BASE;

    // Prevents a page of more than one buffer from stalling after the first.
    @Test
    void aPageInSeveralBuffersIsReadWhole() throws Exception {
        var subscription = new CountingSubscription();
        var subscriber = new GitHubKeyPage().apply(info(200, "text/plain"));

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(bytes("ssh-ed25519 A\n")));
        subscriber.onNext(List.of(bytes("ssh-ed25519 B\n")));
        subscriber.onComplete();

        assertThat(
                        new String(
                                subscriber.getBody().toCompletableFuture().get(),
                                StandardCharsets.UTF_8))
                .isEqualTo("ssh-ed25519 A\nssh-ed25519 B\n");
        assertThat(subscription.requested).as("one buffer asked for at a time").isEqualTo(3);
        assertThat(subscription.cancelled).isFalse();
    }

    // Prevents a page from passing its bound in buffers that each stay below it.
    @Test
    void theBoundCountsEveryBuffer() throws Exception {
        var page = new GitHubKeyPage();
        var subscription = new CountingSubscription();
        var subscriber = page.apply(info(200, "text/plain"));
        int half = GitHubKeyPage.MAX_BYTES / 2 + 1;

        subscriber.onSubscribe(subscription);
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[half])));
        subscriber.onNext(List.of(ByteBuffer.wrap(new byte[half])));

        assertThat(page.verdict).isNotNull();
        assertThat(page.verdict.getMessage()).contains("exceeds its bound");
        assertThat(subscription.cancelled).isTrue();
        assertThat(subscriber.getBody().toCompletableFuture().get()).isEmpty();
    }

    // Prevents a refusal from reading as a failed transport when the transport fails on the stop.
    @Test
    void aTransportThatFailsOnTheStopKeepsTheRefusal() {
        var client = new GitHubClient(BASE, BASE, new FailingOnStop(404), Strictness.STRICT);

        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> client.plainKeys("octocat"))
                .satisfies(ex -> assertThat(ex.status()).isEqualTo(404));
    }

    private static ByteBuffer bytes(String text) {
        return ByteBuffer.wrap(text.getBytes(StandardCharsets.UTF_8));
    }

    private static HttpResponse.ResponseInfo info(int status, String type) {
        return new HttpResponse.ResponseInfo() {
            @Override
            public int statusCode() {
                return status;
            }

            @Override
            public HttpHeaders headers() {
                return HttpHeaders.of(Map.of("content-type", List.of(type)), (name, value) -> true);
            }

            @Override
            public HttpClient.Version version() {
                return HttpClient.Version.HTTP_1_1;
            }
        };
    }

    private static final class CountingSubscription implements Flow.Subscription {
        long requested;
        boolean cancelled;

        @Override
        public void request(long count) {
            requested += count;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    /** Answers with a status, then fails the exchange once the body subscriber stops it. */
    private static final class FailingOnStop extends HttpClient {
        private final int status;

        FailingOnStop(int status) {
            this.status = status;
        }

        @Override
        public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler)
                throws IOException {
            var subscription = new CountingSubscription();
            handler.apply(info(status, "text/plain")).onSubscribe(subscription);
            // No catch of the client's turns this into the refusal it would otherwise read.
            if (!subscription.cancelled) throw new AssertionError("the page did not stop");
            throw new IOException("stream cancelled");
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
}
