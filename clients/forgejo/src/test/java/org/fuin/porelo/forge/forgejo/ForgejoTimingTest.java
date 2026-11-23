package org.fuin.porelo.forge.forgejo;

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

class ForgejoTimingTest {
    private static final URI BASE = URI.create("https://example.org/api/v1/");
    private static final String TOKEN = "synthetic-access-token";
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TIMEOUT = Duration.ofSeconds(150);
    private static final Duration PAUSE = Duration.ofMinutes(4);

    private static ForgejoClient client(HttpClient http) {
        return new ForgejoClient(BASE, http, Strictness.LENIENT, TIMEOUT, PAUSE);
    }

    // Prevents a slow forge from failing every call while the caller has allowed it more time.
    @Test
    void theCallersRequestTimeoutReachesEveryRequest() {
        var api = new ReplayHttpClient().answer(200, Map.of(), "[]");
        client(api).ownKeys(TOKEN, 1, 1);
        assertThat(api.requests.get(0).timeout()).as("an API call's timeout").contains(TIMEOUT);

        var keys =
                new ReplayHttpClient()
                        .answer(200, Map.of("Content-Type", List.of("text/plain")), "k\n");
        client(keys).plainKeys("octocat");
        assertThat(keys.requests.get(0).timeout()).as("the key page's timeout").contains(TIMEOUT);

        var plain = new ReplayHttpClient().answer(200, Map.of(), "[]");
        new ForgejoClient(BASE, plain).ownKeys(TOKEN, 1, 1);
        assertThat(plain.requests.get(0).timeout())
                .as("the default timeout")
                .contains(ForgejoClient.DEFAULT_REQUEST_TIMEOUT);
    }

    // Prevents a caller from retrying sooner than it chose when Forgejo names no wait.
    @Test
    void theCallersPauseIsTheWaitWhenTheAnswerNamesNone() {
        var api = new ReplayHttpClient().answer(429, Map.of(), "{\"message\":\"slow down\"}");
        var refused =
                catchThrowableOfType(
                        ForgejoException.class, () -> client(api).ownKeys(TOKEN, 1, 1));
        assertThat(refused.delay(NOW)).as("an API call's wait").isEqualTo(PAUSE);

        var keys = new ReplayHttpClient().answer(429, Map.of(), "");
        var page =
                catchThrowableOfType(
                        ForgejoException.class, () -> client(keys).plainKeys("octocat"));
        assertThat(page.delay(NOW)).as("the key page's wait").isEqualTo(PAUSE);

        var plain = new ReplayHttpClient().answer(429, Map.of(), "{\"message\":\"slow down\"}");
        var byDefault =
                catchThrowableOfType(
                        ForgejoException.class,
                        () -> new ForgejoClient(BASE, plain).ownKeys(TOKEN, 1, 1));
        assertThat(byDefault.delay(NOW))
                .as("the default wait")
                .isEqualTo(ForgejoClient.DEFAULT_RATE_LIMIT_DELAY);
    }

    // Prevents a zero or negative limit from failing every call at once or never.
    @Test
    void aTimeoutOrPauseThatIsNotPositiveIsRefused() {
        var http = new ReplayHttpClient();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a zero timeout")
                .isThrownBy(
                        () ->
                                new ForgejoClient(
                                        BASE, http, Strictness.LENIENT, Duration.ZERO, PAUSE));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a negative pause")
                .isThrownBy(
                        () ->
                                new ForgejoClient(
                                        BASE,
                                        http,
                                        Strictness.LENIENT,
                                        TIMEOUT,
                                        Duration.ofSeconds(-1)));
    }
}
