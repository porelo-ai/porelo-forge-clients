package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoClientTest {
    private static final URI BASE = URI.create("https://example.org/api/v1/");
    private static final String SEEDED_KEY =
            "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAdTxLMf+cXzMTRZlQ9nf7cNS1Y8UNRaXmm+cgK7MibB";
    private static final int PLAIN_KEYS_BOUND = 256 * 1024;
    private static final String TOKEN = "synthetic-access-token";

    // Prevents API drift from silently losing a public SSH key's identity.
    @Test
    void theStrictReadingRefusesARenamedKeyFieldByName() throws Exception {
        var json = ForgejoJson.mapper(Strictness.STRICT);
        String body;
        try (var stream = getClass().getResourceAsStream("/recordings/keys.json")) {
            assertThat(stream).as("the public key recording must exist").isNotNull();
            body = json.readTree(stream).path("body").asText();
        }
        assertThat(body).as("the mutation must rename an existing key field").contains("\"key\"");
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), body.replace("\"key\"", "\"renamed_key\""));
        var client = new ForgejoClient(BASE, http, Strictness.STRICT);
        assertThatExceptionOfType(ForgejoException.class)
                .as("strict key reads must identify the renamed key field")
                .isThrownBy(() -> client.keys("synthetic-access-token", "fixture-reviewer", 1, 50))
                .withMessageContaining("renamed_key");
    }

    // Prevents omitted ownership from granting ownership or breaking permission reads.
    @Test
    void missingOrganizationOwnershipReadsAsNull() {
        for (Strictness strictness : Strictness.values()) {
            var http =
                    new ReplayHttpClient()
                            .answer(
                                    200,
                                    Map.of(),
                                    "{\"can_read\":true,\"can_write\":false,\"can_create_repository\":false,\"is_admin\":false}");
            var permissions =
                    new ForgejoClient(BASE, http, strictness)
                            .organizationPermissions(TOKEN, "fixture-reviewer", "review-org");
            assertThat(permissions.canRead())
                    .as("known permissions remain readable in %s", strictness)
                    .isTrue();
            assertThat(permissions.isOwner())
                    .as("omitted ownership remains unknown in %s", strictness)
                    .isNull();
        }
    }

    // Prevents a foreign redirect from restoring credentials by bouncing back to the API.
    @Test
    void authenticationStaysRemovedAfterLeavingTheOrigin() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of("Location", List.of("https://other.example.org/log")),
                                "")
                        .answer(
                                302,
                                Map.of("Location", List.of(BASE.resolve("logs").toString())),
                                "")
                        .answer(200, Map.of(), "done");
        assertThat(new ForgejoClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1))
                .as("a return to the API still delivers the log")
                .isEqualTo("done");
        assertThat(http.requests).as("the complete redirect chain was followed").hasSize(3);
        assertThat(http.requests.getFirst().headers().firstValue("Authorization"))
                .as("the initial API request is authenticated")
                .contains("token " + TOKEN);
        for (var request : http.requests.subList(1, 3)) {
            assertThat(request.headers().firstValue("Authorization"))
                    .as("credentials remain stripped after the first foreign hop")
                    .isEmpty();
        }
    }

    // Prevents cleartext log disclosure after an HTTP base redirects through HTTPS.
    @Test
    void aLogRedirectCannotDowngradeThePreviousHop() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of("Location", List.of("https://other.example.org/log")),
                                "")
                        .answer(302, Map.of("Location", List.of("http://example.org/log")), "")
                        .answer(200, Map.of(), "private log");
        assertThatExceptionOfType(ForgejoException.class)
                .as("every HTTPS hop establishes a transport security floor")
                .isThrownBy(
                        () ->
                                new ForgejoClient(URI.create("http://example.org/api/v1/"), http)
                                        .jobLogs(TOKEN, "owner", "repo", 1));
        assertThat(http.requests).as("the cleartext destination is never contacted").hasSize(2);
    }

    // Prevents dot segments from addressing a parent resource after server URL normalization.
    @ParameterizedTest
    @ValueSource(strings = {".", ".."})
    void dotSegmentsAreRefusedBeforeSending(String segment) {
        var http = new ReplayHttpClient().answer(200, Map.of(), "{}");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a dot segment cannot identify a branch")
                .isThrownBy(
                        () ->
                                new ForgejoClient(BASE, http)
                                        .branch(TOKEN, "owner", "repo", segment));
        assertThat(http.requests).as("invalid paths cause no I/O").isEmpty();
    }

    // Prevents malformed list answers from leaking nulls into callers with a non-null contract.
    @Test
    void nullListElementsAreRefusedInEitherReading() {
        for (Strictness strictness : Strictness.values()) {
            var http = new ReplayHttpClient().answer(200, Map.of(), "[null]");
            assertThatExceptionOfType(ForgejoException.class)
                    .as("null account entries violate the list contract in %s", strictness)
                    .isThrownBy(
                            () ->
                                    new ForgejoClient(BASE, http, strictness)
                                            .organizations(TOKEN, 1, 10));
        }
    }

    // Prevents a null nested in a record's list from reaching a caller the records promise it never
    // does.
    @Test
    void nullElementsNestedInARecordAreRefusedInEitherReading() {
        for (Strictness strictness : Strictness.values()) {
            var http =
                    new ReplayHttpClient()
                            .answer(
                                    200,
                                    Map.of(),
                                    "{\"total_count\": 1, \"workflow_runs\": [null]}");
            assertThatExceptionOfType(ForgejoException.class)
                    .as("a null run inside the run list violates its contract in %s", strictness)
                    .isThrownBy(
                            () ->
                                    new ForgejoClient(BASE, http, strictness)
                                            .runs(TOKEN, "owner", "repo", "abc", 1, 10))
                    .withMessageContaining("non-null");
        }
    }

    // Prevents accidental empty mutation requests when a required body is missing.
    @Test
    void nullRequestBodiesFailBeforeAnyIo() {
        var http = new ReplayHttpClient();
        var client = new ForgejoClient(BASE, http);
        List<Runnable> calls =
                List.of(
                        () -> client.createPull(TOKEN, "owner", "repo", null),
                        () -> client.createReview(TOKEN, "owner", "repo", 1, null),
                        () -> client.dismissReview(TOKEN, "owner", "repo", 1, 1, null),
                        () -> client.createStatus(TOKEN, "owner", "repo", "abc", null),
                        () -> client.createRepository(TOKEN, "owner", null),
                        () -> client.addKey(TOKEN, null),
                        () -> client.createHook(TOKEN, "owner", "repo", null));
        for (Runnable call : calls) {
            assertThatExceptionOfType(NullPointerException.class)
                    .as("a missing mutation body is a caller error")
                    .isThrownBy(call::run);
        }
        assertThat(http.requests).as("body validation happens before transport use").isEmpty();
    }

    // Prevents API drift from silently dropping the identity of a build.
    @Test
    void theStrictReadingRefusesARenamedRunFieldByName() {
        ReplayHttpClient http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"total_count\":1,\"workflow_runs\":[{\"renamed_commit_sha\":\"abc\"}]}");
        var client = new ForgejoClient(BASE, http, Strictness.STRICT);
        assertThatExceptionOfType(ForgejoException.class)
                .as(
                        "API drift must fail by the renamed field rather than silently losing build identity")
                .isThrownBy(() -> client.runs(TOKEN, "owner", "repo", "abc", 1, 10))
                .withMessageContaining("renamed_commit_sha");
    }

    // Prevents compatible API additions from breaking reads or losing timestamps.
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

    // Prevents access tokens from leaking across redirect origins.
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
        assertThat(http.requests.getLast().headers().firstValue("Authorization"))
                .as("the access token belongs only to the API origin")
                .isEmpty();
    }

    // Prevents credentials from escaping in reflected redirect URLs.
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
        assertThatExceptionOfType(ForgejoException.class)
                .as(
                        "stripping headers is insufficient when a redirect reflects the access token in its URL")
                .isThrownBy(() -> new ForgejoClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1))
                .withMessageNotContaining(TOKEN);
        assertThat(http.requests)
                .as("no request may carry the token in a URL to another origin")
                .hasSize(1);
    }

    // Prevents signing keys from leaking through API diagnostics.
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
        assertThatExceptionOfType(ForgejoException.class)
                .as(
                        "API diagnostics must not copy a webhook signing key from the request into an exception")
                .isThrownBy(
                        () ->
                                new ForgejoClient(BASE, http)
                                        .createHook(TOKEN, "owner", "repo", body))
                .withMessageNotContaining(hookSecret)
                .withNoCause();
    }

    // Prevents private log downloads from failing on authenticated same-origin redirects.
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

    // Prevents automatic redirects from bypassing credential isolation.
    @Test
    void anAutomaticRedirectFollowingTransportIsRefused() {
        var http = new ReplayHttpClient();
        http.redirects = HttpClient.Redirect.ALWAYS;
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("automatic redirects bypass the token boundary")
                .isThrownBy(() -> new ForgejoClient(BASE, http))
                .withMessageContaining("Redirect.NEVER");
    }

    // Prevents permission errors from hiding their status or leaking credentials.
    @Test
    void anErrorRetainsItsStatusAndRedactsReflectedCredentials() {
        var http =
                new ReplayHttpClient()
                        .answer(403, Map.of(), "{\"message\":\"refused " + TOKEN + "\"}");
        assertThatExceptionOfType(ForgejoException.class)
                .as("a reflected token must not enter exception messages or causes")
                .isThrownBy(() -> new ForgejoClient(BASE, http).user(TOKEN))
                .withMessage("refused [redacted]")
                .withNoCause()
                .satisfies(
                        ex ->
                                assertThat(ex.status())
                                        .as("callers distinguish permission errors")
                                        .isEqualTo(403));
    }

    // Prevents branch input from changing the addressed resource.
    @Test
    void branchNamesCannotChangeTheRequestOriginOrPathStructure() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "{}");
        new ForgejoClient(BASE, http).branch(TOKEN, "owner", "repo", "../feature/a?x=1");
        assertThat(http.requests.getFirst().uri().getRawPath())
                .as("a branch name is exactly one encoded path segment")
                .isEqualTo("/api/v1/repos/owner/repo/branches/%2E%2E%2Ffeature%2Fa%3Fx%3D1");
    }

    // Prevents a token from reaching the public key page, and a page from being read as JSON.
    @Test
    void thePlainKeyPageIsReadWithoutAToken() throws Exception {
        var http = new ReplayHttpClient().recording("plainKeys");
        assertThat(new ForgejoClient(BASE, http).plainKeys("fixture-reviewer"))
                .as("the recorded page lists exactly the seeded key, without its comment")
                .containsExactly(SEEDED_KEY);
        assertThat(http.requests.getFirst().uri().getRawPath())
                .as("the key page sits at the web root, beside the API")
                .isEqualTo("/fixture-reviewer.keys");
        assertThat(http.requests.getFirst().headers().firstValue("Authorization"))
                .as("the key page is read without a token")
                .isEmpty();
    }

    // Prevents a missing login, or a broken page, from reading as an account without keys.
    @Test
    void aFailedKeyPageIsNeverAnEmptyList() {
        for (int status : new int[] {404, 500}) {
            var http = new ReplayHttpClient().answer(status, Map.of(), "Not found");
            assertThatExceptionOfType(ForgejoException.class)
                    .as("status %s is an error, not an empty key list", status)
                    .isThrownBy(() -> new ForgejoClient(BASE, http).plainKeys("fixture-reviewer"))
                    .satisfies(
                            ex ->
                                    assertThat(ex.status())
                                            .as("the caller tells a missing login by its status")
                                            .isEqualTo(status));
            assertThat(http.delivered)
                    .as("the body of status %s is not read", status)
                    .containsExactly(0);
        }
        var html =
                new ReplayHttpClient()
                        .answer(200, Map.of("content-type", List.of("text/html")), "<html>");
        assertThatExceptionOfType(ForgejoException.class)
                .as("a page that is not plain text is refused")
                .isThrownBy(() -> new ForgejoClient(BASE, html).plainKeys("fixture-reviewer"));
        assertThat(html.delivered)
                .as("a page that is not plain text is not read")
                .containsExactly(0);
    }

    // Prevents an earlier answer's verdict from refusing the page a retrying transport got next.
    @Test
    void aRetriedKeyPageIsJudgedByItsLastAnswer() {
        var http =
                new ReplayHttpClient()
                        .answer(503, Map.of(), "busy")
                        .answer(200, Map.of("content-type", List.of("text/plain")), SEEDED_KEY);
        http.retrying = true;
        assertThat(new ForgejoClient(BASE, http).plainKeys("fixture-reviewer"))
                .as("the retried answer is read")
                .containsExactly(SEEDED_KEY);
    }

    // Prevents a key page from being looked up outside a sub-path deployment, or a login from
    // changing the path.
    @Test
    void theKeyPageSitsBesideTheApiAndTheLoginStaysOneSegment() {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of("content-type", List.of("text/plain")), SEEDED_KEY);
        new ForgejoClient(URI.create("https://example.org/forgejo/api/v1/"), http)
                .plainKeys("a/../b");
        assertThat(http.requests.getFirst().uri().getRawPath())
                .as("the page is beside the API under the same sub-path, the login one segment")
                .isEqualTo("/forgejo/a%2F%2E%2E%2Fb.keys");
        var other = new ReplayHttpClient();
        assertThatExceptionOfType(IllegalStateException.class)
                .as("a base that is not the API's cannot locate the web root")
                .isThrownBy(
                        () ->
                                new ForgejoClient(URI.create("https://example.org/api/"), other)
                                        .plainKeys("fixture-reviewer"));
        assertThat(other.requests).as("nothing is sent from such a base").isEmpty();
    }

    // Prevents an endless or oversized key page from exhausting memory, or another media type
    // from being read as keys.
    @Test
    void theKeyPageIsBoundedAndMustBePlainText() {
        var plain = Map.of("content-type", List.of("text/plain; charset=utf-8"));
        var atBound = new ReplayHttpClient().answer(200, plain, "a".repeat(PLAIN_KEYS_BOUND));
        assertThat(new ForgejoClient(BASE, atBound).plainKeys("fixture-reviewer"))
                .as("a page of exactly the bound is read")
                .containsExactly("a".repeat(PLAIN_KEYS_BOUND));
        var oneOver = new ReplayHttpClient().answer(200, plain, "a".repeat(PLAIN_KEYS_BOUND + 1));
        assertThatExceptionOfType(ForgejoException.class)
                .as("one byte over the bound is refused")
                .isThrownBy(() -> new ForgejoClient(BASE, oneOver).plainKeys("fixture-reviewer"))
                .withMessageContaining("bound");
        var overBound =
                new ReplayHttpClient()
                        .answer(
                                200,
                                plain,
                                "a".repeat(PLAIN_KEYS_BOUND + 3 * ReplayHttpClient.Chunks.SIZE));
        assertThatExceptionOfType(ForgejoException.class)
                .as("a page several buffers over the bound is refused")
                .isThrownBy(() -> new ForgejoClient(BASE, overBound).plainKeys("fixture-reviewer"))
                .withMessageContaining("bound")
                .satisfies(
                        ex ->
                                assertThat(ex.status())
                                        .as("the cancelled reading keeps the page's status")
                                        .isEqualTo(200));
        assertThat(overBound.delivered.getFirst())
                .as("the reading stops at the buffer that crosses the bound")
                .isLessThanOrEqualTo(PLAIN_KEYS_BOUND + ReplayHttpClient.Chunks.SIZE);
        var xml =
                new ReplayHttpClient()
                        .answer(200, Map.of("content-type", List.of("text/plain+xml")), SEEDED_KEY);
        assertThatExceptionOfType(ForgejoException.class)
                .as("a media type that only starts like text/plain is refused")
                .isThrownBy(() -> new ForgejoClient(BASE, xml).plainKeys("fixture-reviewer"))
                .withMessageContaining("plain text")
                .satisfies(
                        ex ->
                                assertThat(ex.status())
                                        .as("the unread page keeps its status")
                                        .isEqualTo(200));
        assertThat(xml.delivered).as("the refused page is not read").containsExactly(0);
    }
}
