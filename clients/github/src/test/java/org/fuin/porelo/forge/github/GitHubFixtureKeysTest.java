package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.http.HttpRequest;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

/** The repository and keys a recording makes: refused when left over, removed when its own. */
class GitHubFixtureKeysTest {
    private static final String PREFIX = "forge-client-unit";
    private static final String SHA = "a".repeat(40);
    private static final String OWN_REPOSITORY = "/repos/" + GitHubFixture.OWNER + "/" + PREFIX;

    // Prevents a run from adopting, and then deleting, a repository a run before it left.
    @Test
    void aLeftOverRepositoryIsRefusedBeforeAnyWrite() {
        var http =
                new ReplayHttpClient()
                        .otherwise(
                                request ->
                                        request.uri().getRawPath().equals(OWN_REPOSITORY)
                                                ? answer(200, "{\"name\":\"" + PREFIX + "\"}")
                                                : empty(request));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                            }
                        })
                .withMessageContaining("Refusing leftovers");
        assertThat(http.requests()).allMatch(request -> request.method().equals("GET"));
    }

    @Test
    void aLeftOverKeyIsRefusedBeforeAnyWrite() {
        var http =
                new ReplayHttpClient()
                        .otherwise(
                                request ->
                                        request.uri().getRawPath().equals("/user/ssh_signing_keys")
                                                ? answer(
                                                        200,
                                                        "[{\"id\":7,\"title\":\"" + PREFIX + "\"}]")
                                                : empty(request));

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                            }
                        })
                .withMessageContaining("Refusing leftovers");
        assertThat(http.requests()).allMatch(request -> request.method().equals("GET"));
    }

    // Prevents a failed run from leaving its repository or keys, or deleting anyone else's.
    @Test
    void aFailedRunRemovesItsOwnRepositoryAndKeysOnly() throws Exception {
        var remote = new Remote();
        var http = new ReplayHttpClient().otherwise(remote::answer);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                                fixture.requesting(
                                        GitHubFixture.Requested.REPOSITORY,
                                        () -> remote.repository = true);
                                fixture.requesting(
                                        GitHubFixture.Requested.KEYS, () -> remote.keys.add(5L));
                                fixture.requesting(
                                        GitHubFixture.Requested.SIGNING_KEYS,
                                        () -> remote.signingKeys.add(9L));
                                throw new IllegalStateException("scenario failed");
                            }
                        })
                .withMessage("scenario failed");

        assertThat(deletes(http))
                .contains(OWN_REPOSITORY, "/user/keys/5", "/user/ssh_signing_keys/9")
                .doesNotContain("/user/keys/6", "/user/ssh_signing_keys/10");
        assertThat(remote.repository).isFalse();
    }

    // Prevents a refused create from adopting, and then deleting, what another run made.
    @Test
    void aRefusedCreateLeavesTheRepositoryToWhoeverMadeIt() throws Exception {
        var remote = new Remote();
        var http = new ReplayHttpClient().otherwise(remote::answer);

        try (var fixture = fixture(http)) {
            fixture.prepare();
            assertThatExceptionOfType(GitHubException.class)
                    .isThrownBy(
                            () ->
                                    fixture.requesting(
                                            GitHubFixture.Requested.REPOSITORY,
                                            () -> {
                                                remote.repository = true;
                                                throw new GitHubException(422, "name exists");
                                            }));
        }

        assertThat(deletes(http)).doesNotContain(OWN_REPOSITORY);
        assertThat(remote.repository).isTrue();
    }

    // Prevents asking for one kind of key from deleting the run's keys of the other kind.
    @Test
    void eachKindOfKeyIsRemovedOnlyWhenAskedFor() throws Exception {
        var remote = new Remote();
        var http = new ReplayHttpClient().otherwise(remote::answer);

        try (var fixture = fixture(http)) {
            fixture.prepare();
            fixture.requesting(GitHubFixture.Requested.KEYS, () -> remote.keys.add(5L));
            remote.signingKeys.add(9L);
        }

        assertThat(deletes(http))
                .contains("/user/keys/5")
                .doesNotContain("/user/ssh_signing_keys/9");
    }

    // Prevents a create before the fixture is prepared from escaping the cleanup.
    @Test
    void aRequestBeforeThePreparationIsRefused() {
        var http = new ReplayHttpClient();
        var fixture = fixture(http);

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> fixture.requesting(GitHubFixture.Requested.KEYS, () -> 1))
                .withMessageContaining("not prepared");
        assertThat(http.requests()).isEmpty();
    }

    // Prevents the cleanup from looking for what this run never asked GitHub to make.
    @Test
    void aRunThatMadeNoRepositoryOrKeyLooksForNone() throws Exception {
        var remote = new Remote();
        var http = new ReplayHttpClient().otherwise(remote::answer);

        try (var fixture = fixture(http)) {
            fixture.prepare();
            int prepared = http.requests().size();
            fixture.close();

            assertThat(http.requests().subList(prepared, http.requests().size()))
                    .extracting(request -> request.uri().getRawPath())
                    .doesNotContain(OWN_REPOSITORY, "/user/keys", "/user/ssh_signing_keys");
        }
    }

    // Prevents a keys token of a person's own account from having its keys added and deleted.
    @Test
    void aKeysTokenOfAnotherAccountThanTheReviewerIsRefused() {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "{\"login\":\"someone\"}")
                        .answer(200, Map.of(), "{\"login\":\"reviewer-login\"}");

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(() -> fixture(http).keyAccount())
                .withMessageContaining("must belong to the reviewer");
        assertThat(http.requests())
                .extracting(request -> request.headers().firstValue("Authorization").orElseThrow())
                .containsExactly("Bearer keys", "Bearer reviewer");
    }

    @Test
    void aNewPublicKeyIsAnOpenSshEd25519KeyWithoutAComment() throws Exception {
        String first = GitHubFixture.newPublicKey();
        String[] parts = first.split(" ");

        assertThat(parts).hasSize(2);
        assertThat(parts[0]).isEqualTo("ssh-ed25519");
        ByteBuffer blob = ByteBuffer.wrap(Base64.getDecoder().decode(parts[1]));
        byte[] type = new byte[blob.getInt()];
        blob.get(type);
        assertThat(new String(type, StandardCharsets.US_ASCII)).isEqualTo("ssh-ed25519");
        assertThat(blob.getInt()).isEqualTo(32);
        assertThat(blob.remaining()).isEqualTo(32);
        assertThat(GitHubFixture.newPublicKey())
                .as("each run makes a key of its own")
                .isNotEqualTo(first);
    }

    private static List<String> deletes(ReplayHttpClient http) {
        return http.requests().stream()
                .filter(request -> request.method().equals("DELETE"))
                .map(request -> request.uri().getRawPath())
                .toList();
    }

    private static ReplayHttpClient.Answer answer(int status, String body) {
        return new ReplayHttpClient.Answer(status, Map.of(), body);
    }

    /** What an empty test repository answers to the fixture's discovery and setup. */
    private static ReplayHttpClient.Answer empty(HttpRequest request) {
        String path = request.uri().getRawPath();
        if (path.equals(OWN_REPOSITORY)) return answer(404, "{\"message\":\"Not Found\"}");
        if (path.endsWith("/actions/runs")) return answer(200, "{\"workflow_runs\":[]}");
        if (path.endsWith("/git/ref/heads/main"))
            return answer(200, "{\"object\":{\"sha\":\"" + SHA + "\"}}");
        if (path.endsWith("/check-runs")) return answer(200, "{\"check_runs\":[]}");
        if (request.method().equals("PUT"))
            return answer(201, "{\"commit\":{\"sha\":\"" + SHA + "\"}}");
        if (request.method().equals("POST")) return answer(201, "{}");
        if (request.method().equals("DELETE")) return answer(204, "");
        return answer(200, "[]");
    }

    /** A test organization holding this run's repository and keys, and another key of each kind. */
    private static final class Remote {
        volatile boolean repository;
        final List<Long> keys = new ArrayList<>();
        final List<Long> signingKeys = new ArrayList<>();

        synchronized ReplayHttpClient.Answer answer(HttpRequest request) {
            String path = request.uri().getRawPath();
            String method = request.method();
            if (path.equals(OWN_REPOSITORY)) {
                if (method.equals("DELETE")) {
                    repository = false;
                    return GitHubFixtureKeysTest.answer(204, "");
                }
                return repository
                        ? GitHubFixtureKeysTest.answer(200, "{\"name\":\"" + PREFIX + "\"}")
                        : GitHubFixtureKeysTest.answer(404, "{}");
            }
            if (path.startsWith("/user/keys")) return keys(request, keys, 6L);
            if (path.startsWith("/user/ssh_signing_keys")) return keys(request, signingKeys, 10L);
            return empty(request);
        }

        private static ReplayHttpClient.Answer keys(
                HttpRequest request, List<Long> own, long other) {
            String path = request.uri().getRawPath();
            if (request.method().equals("DELETE")) {
                own.remove(Long.valueOf(path.substring(path.lastIndexOf('/') + 1)));
                return GitHubFixtureKeysTest.answer(204, "");
            }
            StringBuilder body = new StringBuilder("[{\"id\":" + other + ",\"title\":\"kept\"}");
            for (long id : own)
                body.append(",{\"id\":").append(id).append(",\"title\":\"" + PREFIX + "\"}");
            return GitHubFixtureKeysTest.answer(200, body.append("]").toString());
        }
    }

    private static GitHubFixture fixture(ReplayHttpClient http) {
        return new GitHubFixture(
                http,
                "user",
                "reviewer",
                "app",
                "keys",
                List.of("user", "reviewer", "app", "keys"),
                "unit");
    }
}
