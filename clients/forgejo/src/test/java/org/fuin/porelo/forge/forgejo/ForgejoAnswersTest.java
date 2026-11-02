package org.fuin.porelo.forge.forgejo;

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

class ForgejoAnswersTest {
    private static final URI BASE = URI.create("https://example.org/api/v1/");
    private static final String TOKEN = "synthetic-access-token";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final String SECRET_BODY = "unsafe-answer-content";

    private static ForgejoException failure(int status, Map<String, List<String>> headers) {
        var http = new ReplayHttpClient().answer(status, headers, "{\"message\":\"refused\"}");
        var thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        ForgejoException.class, () -> new ForgejoClient(BASE, http).user(TOKEN));
        assertThat(thrown).as("a status of %s is a failure", status).isNotNull();
        return thrown;
    }

    private static HttpHeaders headers(String name, String value) {
        return HttpHeaders.of(Map.of(name, List.of(value)), (key, values) -> true);
    }

    // Prevents waiting on a refusal forever, or giving up on a limit that only asks for a wait.
    @Test
    void a429IsARateLimitAndARefusalIsNot() {
        var limited = failure(429, Map.of("retry-after", List.of("7")));
        assertThat(limited.rateLimited()).as("429 is Forgejo's rate limit").isTrue();
        assertThat(limited.delay(NOW)).as("the delay it asks").isEqualTo(Duration.ofSeconds(7));
        var refused =
                failure(
                        403,
                        Map.of("x-ratelimit-remaining", List.of("0"), "retry-after", List.of("7")));
        assertThat(refused.rateLimited())
                .as("Forgejo 17 refuses with a 403 only for a missing permission")
                .isFalse();
        assertThat(refused.delay(NOW)).as("a refusal gives no delay").isNull();
        assertThat(refused.rateLimit())
                .as("what a proxy said stays readable")
                .isNotNull()
                .extracting(RateLimit::remaining)
                .isEqualTo(0L);
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
        var http = new ReplayHttpClient();
        http.failure = failure;
        var thrown =
                org.assertj.core.api.Assertions.catchThrowableOfType(
                        ForgejoException.class, () -> new ForgejoClient(BASE, http).user(TOKEN));
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
        var http = new ReplayHttpClient().answer(200, Map.of(), "not json");
        assertThatExceptionOfType(ForgejoException.class)
                .as("an undecodable answer came back")
                .isThrownBy(() -> new ForgejoClient(BASE, http).user(TOKEN))
                .satisfies(
                        ex ->
                                assertThat(ex.delivery())
                                        .as("it was answered")
                                        .isEqualTo(Delivery.ANSWERED));
        assertThat(failure(403, Map.of()).delivery())
                .as("a refusal was answered")
                .isEqualTo(Delivery.ANSWERED);
    }

    // Prevents a caller from stopping early, or asking for a page that does not exist.
    @Test
    void aPageSaysWhetherAnotherFollows() {
        String one = "[{\"id\":1}]";
        String next = "<https://example.org/api/v1/user/keys?page=2>; rel=\"next\"";
        String last = "<https://example.org/api/v1/user/keys?page=1>; rel=\"first\"";
        record Case(String why, Map<String, List<String>> headers, boolean more) {}
        for (Case c :
                List.of(
                        new Case("a next link", Map.of("link", List.of(next)), true),
                        new Case("a link without next", Map.of("link", List.of(last)), false),
                        new Case("more counted", Map.of("x-total-count", List.of("2")), true),
                        new Case("all counted", Map.of("x-total-count", List.of("1")), false),
                        new Case("a full page and nothing else", Map.of(), false))) {
            var http = new ReplayHttpClient().answer(200, c.headers(), one);
            var page = new ForgejoClient(BASE, http).ownKeys(TOKEN, 1, 1);
            assertThat(page.more()).as(c.why()).isEqualTo(c.more());
            assertThat(page.items()).as("the page keeps its item").hasSize(1);
        }
    }

    // Prevents a run list's own count from being lost where it has no headers.
    @Test
    void aRunListCountsItsOwnEnd() {
        var more =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "{\"total_count\":2,\"workflow_runs\":[]}");
        assertThat(new ForgejoClient(BASE, more).runs(TOKEN, "o", "r", "abc", 1, 1).more())
                .as("two runs at one per page continue")
                .isTrue();
        var whole =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "{\"total_count\":1,\"workflow_runs\":[]}");
        assertThat(new ForgejoClient(BASE, whole).runs(TOKEN, "o", "r", "abc", 1, 1).more())
                .as("one run at one per page ends")
                .isFalse();
    }

    // Prevents a hostile or broken forge from exhausting the caller's memory, and its answer from
    // reaching a message.
    @Test
    void anAnswerOneByteOverItsBoundIsRefusedAndNotRead() {
        String over = SECRET_BODY + " ".repeat(BoundedAnswer.ANSWER_BYTES);
        var http = new ReplayHttpClient().answer(200, Map.of(), over);
        assertThatExceptionOfType(ForgejoException.class)
                .as("one answer past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, http).user(TOKEN))
                .withMessage("Forgejo answer exceeds " + BoundedAnswer.ANSWER_BYTES + " bytes")
                .satisfies(
                        ex -> {
                            assertThat(ex.delivery())
                                    .as("it was answered")
                                    .isEqualTo(Delivery.ANSWERED);
                            assertThat(ex.status()).as("its status stays").isEqualTo(200);
                        });
        assertThat(http.delivered.getFirst())
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo(BoundedAnswer.ANSWER_BYTES + ReplayHttpClient.Chunks.SIZE);

        String exact = "[" + " ".repeat(BoundedAnswer.ANSWER_BYTES - 2) + "]";
        var within = new ReplayHttpClient().answer(200, Map.of(), exact);
        assertThat(new ForgejoClient(BASE, within).ownKeys(TOKEN, 1, 1).items())
                .as("an answer exactly at its bound is read")
                .isEmpty();
    }

    // Prevents a long error page from being read like an answer, while its status still decides.
    @Test
    void anErrorAnswerOneByteOverItsBoundKeepsItsStatus() {
        String over = SECRET_BODY + " ".repeat(BoundedAnswer.ERROR_BYTES);
        var http = new ReplayHttpClient().answer(429, Map.of("retry-after", List.of("3")), over);
        assertThatExceptionOfType(ForgejoException.class)
                .as("one error answer past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, http).user(TOKEN))
                .withMessage("Forgejo answer exceeds " + BoundedAnswer.ERROR_BYTES + " bytes")
                .satisfies(
                        ex -> {
                            assertThat(ex.status()).as("its status stays").isEqualTo(429);
                            assertThat(ex.rateLimited()).as("still a rate limit").isTrue();
                            assertThat(ex.delay(NOW))
                                    .as("still its delay")
                                    .isEqualTo(Duration.ofSeconds(3));
                        });
        assertThat(http.delivered.getFirst())
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo(BoundedAnswer.ERROR_BYTES + ReplayHttpClient.Chunks.SIZE);

        String exact = "{\"message\":\"refused\"}";
        var within =
                new ReplayHttpClient()
                        .answer(
                                403,
                                Map.of(),
                                exact + " ".repeat(BoundedAnswer.ERROR_BYTES - exact.length()));
        assertThatExceptionOfType(ForgejoException.class)
                .as("an error answer exactly at its bound is read")
                .isThrownBy(() -> new ForgejoClient(BASE, within).user(TOKEN))
                .withMessage("refused");
    }

    // Prevents the reading from going on after its bound, which is what the bound is there to stop.
    @Test
    void theReadingStopsAtTheBufferThatCrossesTheBound() {
        int several = 3 * ReplayHttpClient.Chunks.SIZE;
        var answer =
                new ReplayHttpClient()
                        .answer(200, Map.of(), " ".repeat(BoundedAnswer.ANSWER_BYTES + several));
        assertThatExceptionOfType(ForgejoException.class)
                .as("an answer several buffers past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, answer).user(TOKEN));
        assertThat(answer.delivered.getFirst())
                .as("an answer stops at the crossing buffer")
                .isLessThanOrEqualTo(BoundedAnswer.ANSWER_BYTES + ReplayHttpClient.Chunks.SIZE);
        var error =
                new ReplayHttpClient()
                        .answer(403, Map.of(), " ".repeat(BoundedAnswer.ERROR_BYTES + several));
        assertThatExceptionOfType(ForgejoException.class)
                .as("an error answer several buffers past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, error).user(TOKEN));
        assertThat(error.delivered.getFirst())
                .as("an error answer stops at the crossing buffer")
                .isLessThanOrEqualTo(BoundedAnswer.ERROR_BYTES + ReplayHttpClient.Chunks.SIZE);
        var log =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "x".repeat((int) BoundedAnswer.LOG_BYTES + several));
        assertThatExceptionOfType(ForgejoException.class)
                .as("a log several buffers past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, log).jobLogs(TOKEN, "o", "r", 1));
        assertThat(log.delivered.getFirst())
                .as("a log stops at the crossing buffer")
                .isLessThanOrEqualTo((int) BoundedAnswer.LOG_BYTES + ReplayHttpClient.Chunks.SIZE);
    }

    private static JobLog log(String text) {
        var http = new ReplayHttpClient().answer(200, Map.of(), text);
        return new ForgejoClient(BASE, http).jobLogs(TOKEN, "o", "r", 1);
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
                new ReplayHttpClient()
                        .answer(200, Map.of(), "x".repeat((int) BoundedAnswer.LOG_BYTES + 1));
        assertThatExceptionOfType(ForgejoException.class)
                .as("a log past its bound")
                .isThrownBy(() -> new ForgejoClient(BASE, http).jobLogs(TOKEN, "o", "r", 1))
                .withMessage("Forgejo answer exceeds " + BoundedAnswer.LOG_BYTES + " bytes");
        assertThat(http.delivered.getFirst())
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo((int) BoundedAnswer.LOG_BYTES + ReplayHttpClient.Chunks.SIZE);
    }
}
