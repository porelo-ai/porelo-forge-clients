package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubAnswersTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final String TOKEN = "synthetic-access-token";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SECRET_BODY = "unsafe-answer-content";

    private static GitHubException failure(int status, Map<String, List<String>> headers) {
        var http = new ChunkedTransport(status, headers, "{\"message\":\"refused\"}");
        var thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        GitHubException.class,
                        () -> new GitHubClient(BASE, http).ownKeys(TOKEN, 1, 1));
        assertThat(thrown).as("a status of %s is a failure", status).isNotNull();
        return thrown;
    }

    private static HttpHeaders headers(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (key, values) -> true);
    }

    // Prevents waiting on a refusal forever, or giving up on a limit that only asks for a wait.
    @Test
    void aRateLimitIsToldFromARefusalAndGivesTheDelay() {
        String reset = String.valueOf(NOW.plusSeconds(3600).getEpochSecond());
        var exhausted =
                failure(
                        403,
                        Map.of(
                                "x-ratelimit-remaining",
                                List.of("0"),
                                "x-ratelimit-reset",
                                List.of(reset)));
        assertThat(exhausted.rateLimited()).as("a 403 with nothing remaining is a limit").isTrue();
        assertThat(exhausted.delay(NOW))
                .as("the delay runs up to that reset")
                .isEqualTo(Duration.ofHours(1));
        assertThat(failure(403, Map.of()).rateLimited())
                .as("the same 403 without the headers is a missing permission")
                .isFalse();
        assertThat(failure(403, Map.of()).delay()).as("a refusal gives no delay").isNull();
        assertThat(failure(403, Map.of("x-ratelimit-remaining", List.of("5"))).rateLimited())
                .as("requests remaining make a 403 a refusal")
                .isFalse();
        assertThat(failure(403, Map.of("retry-after", List.of("7"))).delay(NOW))
                .as("a 403 asking for a wait is a limit")
                .isEqualTo(Duration.ofSeconds(7));
        assertThat(failure(429, Map.of()).delay(NOW))
                .as("a 429 saying nothing waits one minute")
                .isEqualTo(Duration.ofMinutes(1));
        assertThat(failure(404, Map.of("x-ratelimit-remaining", List.of("0"))).rateLimited())
                .as("only 403 and 429 can be limits")
                .isFalse();
        var named =
                new ChunkedTransport(
                        403,
                        Map.of(),
                        "{\"message\":\"You have exceeded a secondary Rate Limit\"}");
        assertThatExceptionOfType(GitHubException.class)
                .as("a message naming a rate limit")
                .isThrownBy(() -> new GitHubClient(BASE, named).ownKeys(TOKEN, 1, 1))
                .satisfies(ex -> assertThat(ex.rateLimited()).as("makes it a limit").isTrue());
    }

    // Prevents GraphQL's rate limit, answered with a 200, from reading as an ordinary failure.
    @Test
    void aRateLimitedGraphQlAnswerIsARateLimit() {
        var limited =
                new ChunkedTransport(
                        200,
                        Map.of(),
                        "{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"limited\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("GraphQL's limit")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, limited)
                                        .graphQl(
                                                TOKEN,
                                                java.util.Map.of("query", "q"),
                                                Object.class))
                .satisfies(ex -> assertThat(ex.rateLimited()).as("is a limit").isTrue());
        var other =
                new ChunkedTransport(
                        200, Map.of(), "{\"errors\":[{\"type\":\"OTHER\",\"message\":\"no\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("another GraphQL failure")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, other)
                                        .graphQl(
                                                TOKEN,
                                                java.util.Map.of("query", "q"),
                                                Object.class))
                .satisfies(ex -> assertThat(ex.rateLimited()).as("is not").isFalse());
    }

    // Prevents a caller from retrying too early or waiting without an end.
    @Test
    void theDelayFollowsRetryAfterThenTheResetThenOneMinute() {
        var date =
                DateTimeFormatter.RFC_1123_DATE_TIME.format(
                        NOW.plusSeconds(90).atOffset(ZoneOffset.UTC));
        assertThat(RateLimit.delay(RateLimit.read(headers("retry-after", date), NOW), NOW))
                .as("an HTTP date counts from now")
                .isEqualTo(Duration.ofSeconds(90));
        String reset = String.valueOf(NOW.plusSeconds(30).getEpochSecond());
        assertThat(RateLimit.delay(RateLimit.read(headers("x-ratelimit-reset", reset), NOW), NOW))
                .as("without a wait asked, up to the reset")
                .isEqualTo(Duration.ofSeconds(30));
        assertThat(RateLimit.delay(null, NOW))
                .as("with nothing said, one minute")
                .isEqualTo(Duration.ofMinutes(1));
        String past = String.valueOf(NOW.minusSeconds(30).getEpochSecond());
        assertThat(RateLimit.delay(RateLimit.read(headers("x-ratelimit-reset", past), NOW), NOW))
                .as("a reset already past asks for no wait")
                .isEqualTo(Duration.ZERO);
    }

    // Prevents a forged or garbled header from failing the read of a refusal's status.
    @Test
    void anUnreadableHeaderIsNoValue() {
        assertThat(RateLimit.read(headers("retry-after", "soon"), NOW))
                .as("an unreadable wait is nothing")
                .isNull();
        assertThat(RateLimit.read(headers("x-ratelimit-reset", "999999999999999999"), NOW))
                .as("a reset past every instant is nothing")
                .isNull();
        assertThat(RateLimit.read(headers("x-ratelimit-limit", "-1"), NOW))
                .as("a negative count is nothing")
                .isNull();
    }

    private static Delivery deliveryOf(Exception failure) {
        var http = new ChunkedTransport(failure);
        var thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        GitHubException.class,
                        () -> new GitHubClient(BASE, http).ownKeys(TOKEN, 1, 1));
        assertThat(thrown).as("%s fails the call", failure.getClass()).isNotNull();
        assertThat(thrown.getMessage())
                .as("the message holds nothing of the transport's")
                .doesNotContain("broken-transport-detail");
        assertThat(thrown.status()).as("nothing answered").isZero();
        return thrown.delivery();
    }

    // Prevents a write that may have reached the forge from being sent twice, and one that never
    // left from being given up.
    @Test
    void aFailureNamesWhetherTheRequestMayHaveBeenSent() {
        assertThat(deliveryOf(new ConnectException("broken-transport-detail")))
                .as("a connection never made sent nothing")
                .isEqualTo(Delivery.NOT_SENT);
        assertThat(deliveryOf(new HttpConnectTimeoutException("broken-transport-detail")))
                .as("a connection that timed out sent nothing")
                .isEqualTo(Delivery.NOT_SENT);
        var wrapped = new IOException("broken-transport-detail");
        wrapped.initCause(new ConnectException("broken-transport-detail"));
        assertThat(deliveryOf(wrapped))
                .as("a connection failure the JDK wraps sent nothing")
                .isEqualTo(Delivery.NOT_SENT);
        assertThat(deliveryOf(new HttpTimeoutException("broken-transport-detail")))
                .as("a timeout waiting for the answer may have reached the forge")
                .isEqualTo(Delivery.SENT_UNANSWERED);
        assertThat(deliveryOf(new IOException("broken-transport-detail")))
                .as("a reset may have reached the forge")
                .isEqualTo(Delivery.SENT_UNANSWERED);
        assertThat(deliveryOf(new InterruptedException("broken-transport-detail")))
                .as("an interrupt may have reached the forge")
                .isEqualTo(Delivery.SENT_UNANSWERED);
        assertThat(Thread.interrupted()).as("the interrupt stays set for the caller").isTrue();
    }

    // Prevents an undecodable answer to a write from being taken for one that never arrived.
    @Test
    void anAnswerNamesThatItWasAnswered() {
        var http = new ChunkedTransport(200, Map.of(), "not json");
        assertThatExceptionOfType(GitHubException.class)
                .as("an undecodable answer came back")
                .isThrownBy(() -> new GitHubClient(BASE, http).ownKeys(TOKEN, 1, 1))
                .satisfies(
                        ex ->
                                assertThat(ex.delivery())
                                        .as("it was answered")
                                        .isEqualTo(Delivery.ANSWERED));
        assertThat(failure(403, Map.of()).delivery())
                .as("a refusal was answered")
                .isEqualTo(Delivery.ANSWERED);
    }

    // Prevents a GraphQL write whose answer does not decode from being sent again.
    @Test
    void anUndecodableGraphQlAnswerNamesThatItWasAnswered() {
        var http = new ChunkedTransport(200, Map.of(), "{\"data\":{}}");
        assertThatExceptionOfType(GitHubException.class)
                .as("valid JSON that is not the record asked for")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, http)
                                        .graphQl(TOKEN, Map.of("query", "q"), Integer.class))
                .satisfies(
                        ex ->
                                assertThat(ex.delivery())
                                        .as("it was answered")
                                        .isEqualTo(Delivery.ANSWERED));
    }

    // Prevents a caller from stopping early, or asking for a page that does not exist.
    @Test
    void aPageSaysWhetherAnotherFollows() {
        String one = "[{\"id\":1,\"key\":\"k\",\"created_at\":\"2026-01-01T00:00:00Z\"}]";
        String next = "<https://example.org/user/keys?page=2>; rel=\"next\", <x>; rel=\"last\"";
        var linked = new ChunkedTransport(200, Map.of("link", List.of(next)), one);
        assertThat(new GitHubClient(BASE, linked).keys("octocat", 1, 1).more())
                .as("a next link continues")
                .isTrue();
        String last = "<https://example.org/user/keys?page=1>; rel=\"first\"";
        var ended = new ChunkedTransport(200, Map.of("link", List.of(last)), one);
        assertThat(new GitHubClient(BASE, ended).keys("octocat", 2, 1).more())
                .as("a link without next ends")
                .isFalse();
        var full = new ChunkedTransport(200, Map.of(), one);
        assertThat(new GitHubClient(BASE, full).keys("octocat", 1, 1).more())
                .as("a full page without a next link is the last")
                .isFalse();
    }

    // Prevents a run list's own count from being lost where it has no link.
    @Test
    void anEnvelopeCountsItsOwnEnd() {
        var more = new ChunkedTransport(200, Map.of(), "{\"total_count\":2,\"workflow_runs\":[]}");
        assertThat(new GitHubClient(BASE, more).runs(TOKEN, "o", "r", "abc", 1, 1).more())
                .as("two runs at one per page continue")
                .isTrue();
        var whole = new ChunkedTransport(200, Map.of(), "{\"total_count\":1,\"workflow_runs\":[]}");
        assertThat(new GitHubClient(BASE, whole).runs(TOKEN, "o", "r", "abc", 1, 1).more())
                .as("one run at one per page ends")
                .isFalse();
    }

    // Prevents a hostile or broken forge from exhausting the caller's memory, and its answer from
    // reaching a message.
    @Test
    void anAnswerOneByteOverItsBoundIsRefusedAndNotRead() {
        String over = SECRET_BODY + " ".repeat(BoundedAnswer.ANSWER_BYTES);
        var http = new ChunkedTransport(200, Map.of(), over);
        assertThatExceptionOfType(GitHubException.class)
                .as("one answer past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, http).ownKeys(TOKEN, 1, 1))
                .withMessage("GitHub answer exceeds " + BoundedAnswer.ANSWER_BYTES + " bytes")
                .satisfies(
                        ex -> {
                            assertThat(ex.delivery())
                                    .as("it was answered")
                                    .isEqualTo(Delivery.ANSWERED);
                            assertThat(ex.status()).as("its status stays").isEqualTo(200);
                        });
        assertThat(http.delivered)
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo(BoundedAnswer.ANSWER_BYTES + ChunkedTransport.SIZE);

        String exact = "[" + " ".repeat(BoundedAnswer.ANSWER_BYTES - 2) + "]";
        var within = new ChunkedTransport(200, Map.of(), exact);
        assertThat(new GitHubClient(BASE, within).ownKeys(TOKEN, 1, 1).items())
                .as("an answer exactly at its bound is read")
                .isEmpty();
    }

    // Prevents a long error page from being read like an answer, while its status still decides.
    @Test
    void anErrorAnswerOneByteOverItsBoundKeepsItsStatus() {
        String over = SECRET_BODY + " ".repeat(BoundedAnswer.ERROR_BYTES);
        var http = new ChunkedTransport(429, Map.of("retry-after", List.of("3")), over);
        assertThatExceptionOfType(GitHubException.class)
                .as("one error answer past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, http).ownKeys(TOKEN, 1, 1))
                .withMessage("GitHub answer exceeds " + BoundedAnswer.ERROR_BYTES + " bytes")
                .satisfies(
                        ex -> {
                            assertThat(ex.status()).as("its status stays").isEqualTo(429);
                            assertThat(ex.rateLimited()).as("still a rate limit").isTrue();
                            assertThat(ex.delay(NOW))
                                    .as("still its delay")
                                    .isEqualTo(Duration.ofSeconds(3));
                        });
        assertThat(http.delivered)
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo(BoundedAnswer.ERROR_BYTES + ChunkedTransport.SIZE);

        String exact = "{\"message\":\"refused\"}";
        var within =
                new ChunkedTransport(
                        403,
                        Map.of(),
                        exact + " ".repeat(BoundedAnswer.ERROR_BYTES - exact.length()));
        assertThatExceptionOfType(GitHubException.class)
                .as("an error answer exactly at its bound is read")
                .isThrownBy(() -> new GitHubClient(BASE, within).ownKeys(TOKEN, 1, 1))
                .withMessage("refused");
    }

    // Prevents the reading from going on after its bound, which is what the bound is there to stop.
    @Test
    void theReadingStopsAtTheBufferThatCrossesTheBound() {
        int several = 3 * ChunkedTransport.SIZE;
        var answer =
                new ChunkedTransport(
                        200, Map.of(), " ".repeat(BoundedAnswer.ANSWER_BYTES + several));
        assertThatExceptionOfType(GitHubException.class)
                .as("an answer several buffers past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, answer).ownKeys(TOKEN, 1, 1));
        assertThat(answer.delivered)
                .as("an answer stops at the crossing buffer")
                .isLessThanOrEqualTo(BoundedAnswer.ANSWER_BYTES + ChunkedTransport.SIZE);
        var error =
                new ChunkedTransport(
                        403, Map.of(), " ".repeat(BoundedAnswer.ERROR_BYTES + several));
        assertThatExceptionOfType(GitHubException.class)
                .as("an error answer several buffers past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, error).ownKeys(TOKEN, 1, 1));
        assertThat(error.delivered)
                .as("an error answer stops at the crossing buffer")
                .isLessThanOrEqualTo(BoundedAnswer.ERROR_BYTES + ChunkedTransport.SIZE);
        var log =
                new ChunkedTransport(
                        200, Map.of(), "x".repeat((int) BoundedAnswer.LOG_BYTES + several));
        assertThatExceptionOfType(GitHubException.class)
                .as("a log several buffers past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, log).jobLogs(TOKEN, "o", "r", 1));
        assertThat(log.delivered)
                .as("a log stops at the crossing buffer")
                .isLessThanOrEqualTo((int) BoundedAnswer.LOG_BYTES + ChunkedTransport.SIZE);
    }

    private static JobLog log(String text) {
        var http = new ChunkedTransport(200, Map.of(), text);
        return new GitHubClient(BASE, http).jobLogs(TOKEN, "o", "r", 1);
    }

    // Prevents a build's failure at the end of a long log from being lost, or a whole log from
    // being called cut.
    @Test
    void aLongLogKeepsItsTailAndSaysItWasCut() {
        int tail = BoundedAnswer.LOG_TAIL_BYTES;
        String end = "e".repeat(tail - 8) + "fin-mark";
        var cut = log("dropped" + end);
        assertThat(cut.text().length()).as("exactly its last mebibyte").isEqualTo(tail);
        assertThat(cut.text()).as("the tail itself").isEqualTo(end);
        assertThat(cut.cut()).as("a log one over was cut").isTrue();
        var whole = log(end);
        assertThat(whole.text()).as("a log at the tail's size is whole").isEqualTo(end);
        assertThat(whole.cut()).as("and not cut").isFalse();
        var much = log("x".repeat(5 * tail) + end);
        assertThat(much.text()).as("past the buffer's compaction too").isEqualTo(end);
        assertThat(much.cut()).as("and cut").isTrue();
    }

    // Prevents an endless log from running on past what any build's tail could need.
    @Test
    void aLogOverItsBoundIsRefused() {
        var http =
                new ChunkedTransport(200, Map.of(), "x".repeat((int) BoundedAnswer.LOG_BYTES + 1));
        assertThatExceptionOfType(GitHubException.class)
                .as("a log past its bound")
                .isThrownBy(() -> new GitHubClient(BASE, http).jobLogs(TOKEN, "o", "r", 1))
                .withMessage("GitHub answer exceeds " + BoundedAnswer.LOG_BYTES + " bytes");
        assertThat(http.delivered)
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo((int) BoundedAnswer.LOG_BYTES + ChunkedTransport.SIZE);
    }
}
