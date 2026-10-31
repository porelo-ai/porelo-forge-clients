package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

class GitHubKeysTest {
    private static final URI BASE = GitHubRecordingTest.BASE;
    private static final URI WEB = URI.create("https://web.example.org/");
    private static final String TOKEN = GitHubRecordingTest.TOKEN;
    private static final Map<String, List<String>> JSON_TYPE =
            Map.of("content-type", List.of("application/json; charset=utf-8"));
    private static final Map<String, List<String>> TEXT_TYPE =
            Map.of("content-type", List.of("text/plain; charset=utf-8"));

    // Prevents a key read that needs no token from sending one, and a renamed field from passing.
    @Test
    void signingKeysAreReadStrictlyAndWithoutAToken() throws Exception {
        var keys = GitHubClientTest.extractExample("ssh-signing-key-items").deepCopy();
        var renamed = keys.deepCopy();
        var first = (ObjectNode) renamed.path(0);
        first.set("created_at_renamed", first.remove("created_at"));
        var http =
                new ReplayHttpClient()
                        .answer(200, JSON_TYPE, keys.toString())
                        .answer(200, JSON_TYPE, renamed.toString());
        var client = new GitHubClient(BASE, http, Strictness.STRICT);

        assertThat(client.signingKeys("octocat", 1, 50))
                .extracting(key -> key.id())
                .containsExactly(2L, 3L);
        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> client.signingKeys("octocat", 1, 50))
                .withMessageContaining("created_at");

        assertThat(http.requests())
                .allSatisfy(
                        request ->
                                assertThat(request.headers().firstValue("Authorization")).isEmpty())
                .extracting(request -> request.uri().toString())
                .containsOnly(
                        "https://example.org/users/octocat/ssh_signing_keys?page=1&per_page=50");
    }

    @Test
    void authenticationKeysAreReadWithoutAToken() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                JSON_TYPE,
                                GitHubClientTest.extractExample("key-simple-items").toString());

        var keys = new GitHubClient(BASE, http, Strictness.STRICT).keys("octocat", 2, 10);

        assertThat(keys).extracting(key -> key.key()).containsExactly("ssh-rsa AAA...");
        HttpRequest request = http.requests().getFirst();
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
        assertThat(request.uri().toString())
                .isEqualTo("https://example.org/users/octocat/keys?page=2&per_page=10");
    }

    // Prevents the key page from carrying a token, being read off the web base, or a failure from
    // reading as no keys.
    @Test
    void theKeyPageIsReadFromTheWebBaseWithoutAToken() {
        var http =
                new ReplayHttpClient()
                        .answer(200, TEXT_TYPE, "ssh-ed25519 AAAA1\n\nssh-rsa AAAA2\n");

        var lines = new GitHubClient(BASE, WEB, http, Strictness.STRICT).plainKeys("octo.cat");

        assertThat(lines).containsExactly("ssh-ed25519 AAAA1", "ssh-rsa AAAA2");
        HttpRequest request = http.requests().getFirst();
        assertThat(request.uri().toString()).isEqualTo("https://web.example.org/octo%2Ecat.keys");
        assertThat(request.headers().firstValue("Authorization")).isEmpty();
    }

    @Test
    void aMissingLoginIsTold() {
        var http = new ReplayHttpClient().answer(404, TEXT_TYPE, "Not Found");

        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, WEB, http, Strictness.STRICT)
                                        .plainKeys("nobody"))
                .satisfies(failure -> assertThat(failure.status()).isEqualTo(404));
    }

    @Test
    void aKeyPageThatIsNotPlainTextIsRefused() {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of("content-type", List.of("text/html")), "<html>");

        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, WEB, http, Strictness.STRICT)
                                        .plainKeys("octocat"))
                .withMessageContaining("not plain text");
    }

    @Test
    void aKeyPageIsReadUpToItsBound() {
        String atBound = "k".repeat(GitHubKeyPage.MAX_BYTES);
        var http =
                new ReplayHttpClient()
                        .answer(200, TEXT_TYPE, atBound)
                        .answer(200, TEXT_TYPE, atBound + "k");
        var client = new GitHubClient(BASE, WEB, http, Strictness.STRICT);

        assertThat(client.plainKeys("octocat")).containsExactly(atBound);
        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> client.plainKeys("octocat"))
                .withMessageContaining("exceeds its bound")
                .withMessageNotContaining("kkk");
    }

    @Test
    void aWebBaseWithAQueryIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(
                        () ->
                                new GitHubClient(
                                        BASE,
                                        URI.create("https://web.example.org/?x=1"),
                                        new ReplayHttpClient(),
                                        Strictness.STRICT))
                .withMessageContaining("web base");
    }

    @Test
    void anOrganizationMembershipOfAnAccountIsReadByBothNames() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                JSON_TYPE,
                                GitHubRecordingTest.recording("membership").path("body").asText());

        new GitHubClient(BASE, http, Strictness.STRICT).orgMembership(TOKEN, "the org", "a.login");

        assertThat(http.requests().getFirst().uri().getRawPath())
                .isEqualTo("/orgs/the%20org/memberships/a%2Elogin");
        assertThat(http.requests().getFirst().headers().firstValue("Authorization"))
                .hasValue("Bearer " + TOKEN);
    }

    @Test
    void aCommitsCombinedStatusIsReadByItsReference() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                JSON_TYPE,
                                GitHubClientTest.extractExample("combined-commit-status")
                                        .toString());

        var status =
                new GitHubClient(BASE, http, Strictness.STRICT)
                        .status(TOKEN, "owner", "repo", "heads/a b", 1, 100);

        assertThat(status.state()).isEqualTo("success");
        assertThat(status.statuses()).isNotEmpty();
        assertThat(http.requests().getFirst().uri().getRawPath())
                .isEqualTo("/repos/owner/repo/commits/heads/a%20b/status");
        assertThat(http.requests().getFirst().uri().getRawQuery()).isEqualTo("page=1&per_page=100");
    }
}
