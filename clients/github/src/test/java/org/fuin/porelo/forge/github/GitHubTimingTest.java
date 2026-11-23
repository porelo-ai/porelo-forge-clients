package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubTimingTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final URI WEB = URI.create("https://example.org/web/");
    private static final String TOKEN = "synthetic-access-token";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(150);
    private static final Duration PAUSE = Duration.ofMinutes(4);

    private static GitHubClient client(HttpClient http) {
        return new GitHubClient(BASE, WEB, http, Strictness.LENIENT, TIMEOUT, PAUSE);
    }

    private static GitHubGraphQl graphQl(HttpClient http) {
        return new GitHubGraphQl(BASE, http, Strictness.LENIENT, TIMEOUT, PAUSE);
    }

    // Prevents a slow forge from failing every call while the caller has allowed it more time.
    @Test
    void theCallersRequestTimeoutReachesEveryRequest() {
        var rest = new ChunkedTransport(200, Map.of(), "[]");
        client(rest).ownKeys(TOKEN, 1, 1);
        assertThat(rest.sent.get(0).timeout()).as("a REST call's timeout").contains(TIMEOUT);

        var keys = new ChunkedTransport(200, Map.of("Content-Type", List.of("text/plain")), "k\n");
        client(keys).plainKeys("octocat");
        assertThat(keys.sent.get(0).timeout()).as("the key page's timeout").contains(TIMEOUT);

        var query =
                new ChunkedTransport(
                        200, Map.of(), "{\"data\":{\"a0\":{\"item\":{\"id\":\"I\"}}}}");
        graphQl(query).addProjectItems(TOKEN, "P_1", List.of("I_1"));
        assertThat(query.sent.get(0).timeout()).as("a GraphQL call's timeout").contains(TIMEOUT);

        var plain = new ChunkedTransport(200, Map.of(), "[]");
        new GitHubClient(BASE, plain).ownKeys(TOKEN, 1, 1);
        assertThat(plain.sent.get(0).timeout())
                .as("the default timeout")
                .contains(GitHubClient.DEFAULT_REQUEST_TIMEOUT);
    }

    // Prevents a caller from retrying sooner than it chose when GitHub names no wait.
    @Test
    void theCallersPauseIsTheWaitWhenTheAnswerNamesNone() {
        var refused = new ChunkedTransport(429, Map.of(), "{\"message\":\"slow down\"}");
        var exception =
                catchThrowableOfType(
                        GitHubException.class, () -> client(refused).ownKeys(TOKEN, 1, 1));
        assertThat(exception.delay(NOW)).as("a REST rate limit's wait").isEqualTo(PAUSE);

        var whole =
                new ChunkedTransport(
                        200,
                        Map.of(),
                        "{\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"limit\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("a GraphQL call refused whole")
                .isThrownBy(() -> graphQl(whole).addProjectItems(TOKEN, "P_1", List.of("I_1")))
                .satisfies(ex -> assertThat(ex.delay(NOW)).as("its wait").isEqualTo(PAUSE));

        var part =
                new ChunkedTransport(
                        200,
                        Map.of(),
                        "{\"data\":{\"a0\":null},\"errors\":[{\"type\":\"RATE_LIMITED\","
                                + "\"path\":[\"a0\"],\"message\":\"limit\"}]}");
        var batch = graphQl(part).addProjectItems(TOKEN, "P_1", List.of("I_1"));
        assertThat(batch.delay(NOW)).as("a batch's wait").isEqualTo(PAUSE);

        var plain = new ChunkedTransport(429, Map.of(), "{\"message\":\"slow down\"}");
        var byDefault =
                catchThrowableOfType(
                        GitHubException.class,
                        () -> new GitHubClient(BASE, plain).ownKeys(TOKEN, 1, 1));
        assertThat(byDefault.delay(NOW))
                .as("the default wait")
                .isEqualTo(GitHubClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    // Prevents a zero or negative limit from failing every call at once or never.
    @Test
    void aTimeoutOrPauseThatIsNotPositiveIsRefused() {
        var http = new ChunkedTransport(200, Map.of(), "[]");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a zero timeout")
                .isThrownBy(
                        () ->
                                new GitHubClient(
                                        BASE, WEB, http, Strictness.LENIENT, Duration.ZERO, PAUSE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a negative pause")
                .isThrownBy(
                        () ->
                                new GitHubClient(
                                        BASE,
                                        WEB,
                                        http,
                                        Strictness.LENIENT,
                                        TIMEOUT,
                                        Duration.ofSeconds(-1)));
    }
}
