package org.fuin.porelo.forge.github.replaytest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.GitHubClient;
import org.fuin.porelo.forge.github.Strictness;
import org.fuin.porelo.forge.github.model.PullRequestReviewComment;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

/**
 * Replays as another repository's test does: through public API only, from outside the client's
 * package.
 */
class ReplayFromOutsideTest {
    private static final URI API = URI.create("https://api.github.com");

    @Test
    void aRecordedAnswerReplaysThroughTheClient() throws Exception {
        var http = new ReplayHttpClient().recording("reviewComments");

        List<PullRequestReviewComment> comments =
                new GitHubClient(API, http, Strictness.STRICT)
                        .reviewComments("token", "fixture-org", "fixture-repo", 6, 1, 50);

        assertThat(comments).extracting(PullRequestReviewComment::line).containsExactly(1, 1);
        assertThat(http.requests())
                .singleElement()
                .satisfies(
                        request ->
                                assertThat(request.uri().getPath())
                                        .isEqualTo(
                                                "/repos/fixture-org/fixture-repo/pulls/6/comments"));
    }

    @Test
    void aChangedCopyOfARecordedAnswerReplaysWithTheChange() throws Exception {
        ReplayHttpClient.Answer recorded = ReplayHttpClient.recorded("reviewComments").getFirst();
        var http =
                new ReplayHttpClient()
                        .answer(
                                new ReplayHttpClient.Answer(
                                        recorded.status(),
                                        recorded.headers(),
                                        recorded.body()
                                                .replaceFirst("\"line\":1,", "\"line\":7,")));

        List<PullRequestReviewComment> comments =
                new GitHubClient(API, http, Strictness.STRICT)
                        .reviewComments("token", "fixture-org", "fixture-repo", 6, 1, 50);

        assertThat(comments).extracting(PullRequestReviewComment::line).containsExactly(7, 1);
    }

    @Test
    void aMissingRecordingFailsByItsName() {
        assertThatThrownBy(() -> ReplayHttpClient.recorded("noSuchCall"))
                .isInstanceOf(IOException.class)
                .hasMessage("Recording is missing: noSuchCall");
    }

    @Test
    void aNameThatLeavesTheRecordingsIsRefused() {
        assertThatThrownBy(() -> ReplayHttpClient.recorded("../replay/ReplayHttpClient"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Not a recording name");
    }

    @Test
    void anAnswerKeepsItsHeadersWhenTheGivenOnesChange() {
        var values = new ArrayList<>(List.of("application/json"));
        var headers = new HashMap<String, List<String>>(Map.of("content-type", values));

        var answer = new ReplayHttpClient.Answer(200, headers, "[]");
        values.add("text/plain");
        headers.put("x-other", List.of("1"));

        assertThat(answer.headers())
                .containsExactly(Map.entry("content-type", List.of("application/json")));
    }

    @Test
    void theRequestsReturnedAreACopy() throws Exception {
        var http = new ReplayHttpClient().recording("reviewComments");
        List<HttpRequest> before = http.requests();

        new GitHubClient(API, http, Strictness.STRICT)
                .reviewComments("token", "fixture-org", "fixture-repo", 6, 1, 50);

        assertThat(before).isEmpty();
        assertThat(http.requests()).hasSize(1);
    }

    @Test
    void aBodyHandlerFailureIsThrownAsTheJdkThrowsIt() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "x").answer(200, Map.of(), "y");
        HttpRequest request = HttpRequest.newBuilder(API).build();

        assertThatThrownBy(
                        () ->
                                http.send(
                                        request,
                                        info ->
                                                HttpResponse.BodySubscribers.mapping(
                                                        HttpResponse.BodySubscribers.ofString(
                                                                StandardCharsets.UTF_8),
                                                        text -> {
                                                            throw new IllegalStateException(text);
                                                        })))
                .isExactlyInstanceOf(IllegalStateException.class)
                .hasMessage("x");
        assertThatThrownBy(() -> http.send(request, info -> failing(new IOException("too big"))))
                .isExactlyInstanceOf(IOException.class)
                .hasMessage("too big");
    }

    @Test
    void aSubscriberThatCancelsIsNotCompleted() throws Exception {
        var http = new ReplayHttpClient().answer(200, Map.of(), "x");
        var events = new ArrayList<String>();

        http.send(
                HttpRequest.newBuilder(API).build(),
                info ->
                        new Recorder(events, 1) {
                            @Override
                            public void onNext(List<ByteBuffer> item) {
                                events.add("next");
                                subscription().cancel();
                            }
                        });

        assertThat(events).containsExactly("next");
    }

    @Test
    void aDemandOfNothingIsAnError() throws Exception {
        var http = new ReplayHttpClient().answer(200, Map.of(), "x");
        var events = new ArrayList<String>();

        http.send(HttpRequest.newBuilder(API).build(), info -> new Recorder(events, 0));

        assertThat(events).containsExactly("error IllegalArgumentException");
    }

    @Test
    void aDemandOfNothingWhileReadingIsAnError() throws Exception {
        var http = new ReplayHttpClient().answer(200, Map.of(), "x");
        var events = new ArrayList<String>();

        http.send(
                HttpRequest.newBuilder(API).build(),
                info ->
                        new Recorder(events, 1) {
                            @Override
                            public void onNext(List<ByteBuffer> item) {
                                events.add("next");
                                subscription().request(0);
                            }
                        });

        assertThat(events).containsExactly("next", "error IllegalArgumentException");
    }

    @Test
    void headersThatCannotBeSentAreRefusedWhenTheAnswerIsMade() {
        assertThatThrownBy(
                        () ->
                                new ReplayHttpClient.Answer(
                                        200,
                                        Map.of(
                                                "content-type", List.of("application/json"),
                                                "Content-Type", List.of("text/plain")),
                                        "[]"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sendingNothingLeavesTheRequestsReadable() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "x");

        assertThatThrownBy(() -> http.send(null, HttpResponse.BodyHandlers.ofString()))
                .isInstanceOf(NullPointerException.class);

        assertThat(http.requests()).isEmpty();
    }

    private static HttpResponse.BodySubscriber<String> failing(IOException failure) {
        return new Recorder(new ArrayList<>(), 1) {
            @Override
            public CompletionStage<String> getBody() {
                return CompletableFuture.failedFuture(failure);
            }
        };
    }

    /** Asks for its demand, notes what a transport signals, and completes with nothing. */
    private static class Recorder implements HttpResponse.BodySubscriber<String> {
        private final List<String> events;
        private final long demand;
        private final AtomicReference<Flow.Subscription> subscription = new AtomicReference<>();

        Recorder(List<String> events, long demand) {
            this.events = events;
            this.demand = demand;
        }

        Flow.Subscription subscription() {
            return subscription.get();
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription.set(subscription);
            subscription.request(demand);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            events.add("next");
        }

        @Override
        public void onError(Throwable throwable) {
            events.add("error " + throwable.getClass().getSimpleName());
        }

        @Override
        public void onComplete() {
            events.add("complete");
        }

        @Override
        public CompletionStage<String> getBody() {
            return CompletableFuture.completedFuture("");
        }
    }
}
