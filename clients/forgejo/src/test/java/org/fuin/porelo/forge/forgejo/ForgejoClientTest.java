package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoClientTest {
    private static final URI BASE = URI.create("https://example.org/api/v1/");
    private static final String TOKEN = "synthetic-access-token";

    @Test
    void theStrictReadingRefusesARenamedRunFieldByName() {
        ReplayHttpClient http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"total_count\":1,\"workflow_runs\":[{\"renamed_commit_sha\":\"abc\"}]}");
        var client = new ForgejoClient(BASE, http, Strictness.STRICT);
        assertThatThrownBy(() -> client.runs(TOKEN, "owner", "repo", "abc", 1, 10))
                .as(
                        "API drift must fail by the renamed field rather than silently losing build identity")
                .isInstanceOf(ForgejoException.class)
                .hasMessageContaining("renamed_commit_sha");
    }

    @Test
    void theDefaultReadingToleratesAdditionalFieldsAndReadsDates() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"id\":7,\"new_field\":true,\"created\":\"2026-01-01T00:00:00Z\"}");
        var user = new ForgejoClient(BASE, http).user(TOKEN);
        assertThat(user.id())
                .as("known identity survives additions within an API version")
                .isEqualTo(7L);
        assertThat(user.created())
                .as("Forgejo timestamps use the registered time module")
                .isNotNull();
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://other.example.org/log",
                "https://example.org:8443/log",
                "http://example.org:8080/log"
            })
    void aLogRedirectNeverSendsTheTokenToAnotherOrigin(String location) {
        var http =
                new ReplayHttpClient()
                        .answer(302, Map.of("Location", List.of(location)), "")
                        .answer(200, Map.of(), "finished");
        var client = new ForgejoClient(URI.create("http://example.org/api/v1/"), http);
        assertThat(client.jobLogs(TOKEN, "owner", "repo", 1))
                .as("redirected log content is returned")
                .isEqualTo("finished");
        assertThat(http.requests.getFirst().headers().firstValue("Authorization"))
                .as("the origin request needs authentication")
                .contains("token " + TOKEN);
        {
            assertThat(http.requests.getLast().headers().firstValue("Authorization"))
                    .as("the access token belongs only to the API origin")
                    .isEmpty();
        }
    }

    @Test
    void aRedirectCannotPutTheAccessTokenIntoAForeignUrl() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of(
                                        "location",
                                        List.of("https://other.example.org/log?access=" + TOKEN)),
                                "")
                        .answer(200, Map.of(), "done");
        assertThatThrownBy(() -> new ForgejoClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1))
                .as(
                        "stripping headers is insufficient when a redirect reflects the access token in its URL")
                .isInstanceOf(ForgejoException.class)
                .hasMessageNotContaining(TOKEN);
        assertThat(http.requests)
                .as("no request may carry the token in a URL to another origin")
                .hasSize(1);
    }

    @Test
    void aReflectedWebhookSecretIsRemovedFromAnApiError() throws Exception {
        String hookSecret = "synthetic-hook-key";
        var body =
                ForgejoJson.mapper(Strictness.STRICT)
                        .readValue(
                                "{\"type\":\"forgejo\",\"config\":{\"secret\":\""
                                        + hookSecret
                                        + "\",\"url\":\"https://example.org/hook\"}}",
                                org.fuin.porelo.forge.forgejo.model.CreateHookOption.class);
        var http =
                new ReplayHttpClient()
                        .answer(
                                400,
                                Map.of(),
                                "{\"message\":\"Invalid secret " + hookSecret + "\"}");
        assertThatThrownBy(
                        () ->
                                new ForgejoClient(BASE, http)
                                        .createHook(TOKEN, "owner", "repo", body))
                .as(
                        "API diagnostics must not copy a webhook signing key from the request into an exception")
                .hasMessageNotContaining(hookSecret)
                .hasNoCause();
    }

    @Test
    void aSameOriginLogRedirectKeepsAuthentication() {
        var http =
                new ReplayHttpClient()
                        .answer(307, Map.of("location", List.of("/download/log")), "")
                        .answer(200, Map.of(), "done");
        new ForgejoClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1);
        assertThat(http.requests.getLast().headers().firstValue("Authorization"))
                .as("private log storage on the same origin still needs authentication")
                .contains("token " + TOKEN);
    }

    @Test
    void anAutomaticRedirectFollowingTransportIsRefused() {
        var http = new ReplayHttpClient();
        http.redirects = HttpClient.Redirect.ALWAYS;
        assertThatThrownBy(() -> new ForgejoClient(BASE, http))
                .as("automatic redirects bypass the token boundary")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Redirect.NEVER");
    }

    @Test
    void anErrorRetainsItsStatusAndRedactsReflectedCredentials() {
        var http =
                new ReplayHttpClient()
                        .answer(403, Map.of(), "{\"message\":\"refused " + TOKEN + "\"}");
        try {
            new ForgejoClient(BASE, http).user(TOKEN);
            org.junit.jupiter.api.Assertions.fail("expected a refusal");
        } catch (ForgejoException ex) {
            assertThat(ex.status()).as("callers distinguish permission errors").isEqualTo(403);
            assertThat(ex)
                    .as("a reflected token must not enter exception messages or causes")
                    .hasMessage("refused [redacted]")
                    .hasNoCause();
        }
    }

    @Test
    void branchNamesCannotChangeTheRequestOriginOrPathStructure() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "{}");
        new ForgejoClient(BASE, http).branch(TOKEN, "owner", "repo", "../feature/a?x=1");
        assertThat(http.requests.getFirst().uri().getRawPath())
                .as("a branch name is exactly one encoded path segment")
                .isEqualTo("/api/v1/repos/owner/repo/branches/%2E%2E%2Ffeature%2Fa%3Fx%3D1");
    }
}
