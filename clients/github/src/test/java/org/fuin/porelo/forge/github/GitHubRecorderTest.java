package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GitHubRecorderTest {
    // Prevents forged, incorrectly scoped or overlong App credentials from authorizing check
    // writes.
    @Test
    void appJwtIsVerifiableRs256WithABoundedLifetime() throws Exception {
        var generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        var pair = generator.generateKeyPair();
        String pem =
                "-----BEGIN PRIVATE KEY-----\n"
                        + Base64.getEncoder().encodeToString(pair.getPrivate().getEncoded())
                        + "\n-----END PRIVATE KEY-----";
        Instant now = Instant.ofEpochSecond(1000000);
        String jwt = GitHubAppToken.jwt("123", pem, now);
        String[] parts = jwt.split("\\.");
        assertThat(parts).as("JWT has header, claims and signature").hasSize(3);
        var json = new ObjectMapper();
        var header = json.readTree(Base64.getUrlDecoder().decode(parts[0]));
        var claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.path("alg").asText())
                .as("App JWT pins the algorithm GitHub verifies")
                .isEqualTo("RS256"); // not-a-citation
        assertThat(claims.path("iss").asText())
                .as("the configured App is the issuer")
                .isEqualTo("123");
        assertThat(claims.path("iat").asLong())
                .as("clock skew is bounded to sixty seconds")
                .isEqualTo(now.minusSeconds(60).getEpochSecond());
        assertThat(claims.path("exp").asLong())
                .as("JWT lifetime stays within ten minutes from issuance")
                .isEqualTo(now.plusSeconds(540).getEpochSecond());
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(pair.getPublic());
        signature.update(
                (parts[0] + "." + parts[1]).getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        assertThat(signature.verify(Base64.getUrlDecoder().decode(parts[2])))
                .as("GitHub can verify the signature using the App public key")
                .isTrue();
        // A PKCS#1 key is the octet string inside this PKCS#8 structure.
        byte[] encoded = pair.getPrivate().getEncoded();
        int index = 0;
        if (encoded[index++] != 48) throw new AssertionError("Expected a PKCS#8 sequence");
        index = skipLength(encoded, index);
        for (int component = 0; component < 2; component++) {
            index++;
            int[] length = length(encoded, index);
            index = length[0] + length[1];
        }
        if (encoded[index++] != 4) throw new AssertionError("Expected the PKCS#1 octet string");
        int[] length = length(encoded, index);
        String rsa =
                "-----BEGIN RSA PRIVATE KEY-----\n"
                        + Base64.getEncoder()
                                .encodeToString(
                                        java.util.Arrays.copyOfRange(
                                                encoded, length[0], length[0] + length[1]))
                        + "\n-----END RSA PRIVATE KEY-----";
        assertThat(GitHubAppToken.jwt("123", rsa, now))
                .as("GitHub's PKCS#1 PEM signs the same JWT with the same key")
                .isEqualTo(jwt);
    }

    private static int skipLength(byte[] bytes, int index) {
        return length(bytes, index)[0];
    }

    private static int[] length(byte[] bytes, int index) {
        int size = bytes[index++] & 255;
        if (size < 128) return new int[] {index, size};
        int count = size & 127;
        size = 0;
        for (int i = 0; i < count; i++) size = (size << 8) | (bytes[index++] & 255);
        return new int[] {index, size};
    }

    // Prevents malformed configuration from exposing key material or starting a live recording.
    @Test
    void invalidEnvironmentAndSigningInputFailWithoutSecrets() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("credentials come only from explicit environment entries")
                .isThrownBy(() -> GitHubFixture.required(Map.of(), "LIVE_GITHUB_USER_TOKEN"))
                .withMessageContaining("LIVE_GITHUB_USER_TOKEN");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("bad signing keys have sanitized diagnostics")
                .isThrownBy(() -> GitHubAppToken.jwt("123", "synthetic-private-key", Instant.EPOCH))
                .withMessageNotContaining("synthetic-private-key")
                .withNoCause();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("run IDs cannot inject remote paths")
                .isThrownBy(
                        () ->
                                new GitHubFixture(
                                        new ReplayHttpClient(),
                                        "user",
                                        "reviewer",
                                        "app",
                                        List.of("user"),
                                        "../other"));
    }

    // Prevents a repeated run from adopting and deleting existing remote resources.
    @Test
    void matchingLeftoversAreRefusedBeforeAnyWrite() {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "[{\"ref\":\"refs/heads/forge-client-unit\"}]");
        assertThatExceptionOfType(IllegalStateException.class)
                .as("an existing prefix must not be adopted")
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                            }
                        })
                .withMessageContaining("Refusing leftovers");
        assertThat(http.requests).as("leftover discovery stops before any mutation").hasSize(1);
        assertThat(http.requests)
                .as("the recorder must perform no write against someone else's resources")
                .allMatch(request -> request.method().equals("GET"));
    }

    // Prevents a competing recorder from deleting the branch whose creation it lost.
    @Test
    void aRejectedBranchCreationDoesNotAdoptTheWinnersResources() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "[]")
                        .answer(200, Map.of(), "[]")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "{\"object\":{\"sha\":\"" + "a".repeat(40) + "\"}}")
                        .answer(422, Map.of(), "{}")
                        .answer(200, Map.of(), "[]")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "[{\"ref\":\"refs/heads/forge-client-unit\"}]")
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), "[]");
        assertThatExceptionOfType(GitHubException.class)
                .as("a competing prefix creation is refused")
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                            }
                        });
        assertThat(http.requests.stream().filter(request -> request.method().equals("DELETE")))
                .as("a rejected create owns no remote resources")
                .isEmpty();
    }

    // Prevents failed scenarios from leaking a branch or deleting unrelated resources.
    @Test
    void failedScenariosCleanOnlyTheirOwnedBranch() throws Exception {
        String sha = "a".repeat(40);
        var http =
                preparedTransport(sha)
                        .answer(200, Map.of(), "[]")
                        .answer(200, Map.of(), "{\"check_runs\":[]}")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(
                                200,
                                Map.of(),
                                "[{\"ref\":\"refs/heads/forge-client-unit\"},"
                                        + "{\"ref\":\"refs/heads/forge-client-unit-other\"}]")
                        .answer(204, Map.of(), "")
                        .answer(
                                200,
                                Map.of(),
                                "[{\"ref\":\"refs/heads/forge-client-unit-other\"}]");
        assertThatExceptionOfType(IllegalStateException.class)
                .as("the original scenario failure survives successful cleanup")
                .isThrownBy(
                        () -> {
                            try (var fixture = fixture(http)) {
                                fixture.prepare();
                                throw new IllegalStateException("scenario failed");
                            }
                        })
                .withMessage("scenario failed");
        assertThat(
                        http.requests.stream()
                                .filter(request -> request.method().equals("DELETE"))
                                .map(request -> request.uri().getRawPath()))
                .as("cleanup deletes exactly the branch this run owns")
                .containsExactly("/" + GitHubFixture.ROOT + "/git/refs/heads/forge-client-unit");
    }

    // Prevents a failed cleanup from being reported as a successful recording.
    @Test
    void cleanupFailuresNameWhatRemainsAndStillTryOtherResources() throws Exception {
        var http =
                preparedTransport("a".repeat(40))
                        .answer(500, Map.of(), "{}")
                        .answer(200, Map.of(), "{\"check_runs\":[]}")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(200, Map.of(), "[{\"ref\":\"refs/heads/forge-client-unit\"}]")
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), "[]");
        var fixture = fixture(http);
        fixture.prepare();
        assertThatExceptionOfType(IllegalStateException.class)
                .as("cleanup is a recording success condition")
                .isThrownBy(fixture::close)
                .withMessageContaining("pull requests")
                .withMessageContaining("forge-client-unit");
        assertThat(
                        http.requests.stream()
                                .filter(request -> request.method().equals("DELETE"))
                                .map(HttpRequest::uri))
                .as("later cleanup still removes the branch")
                .hasSize(1);
    }

    // Prevents a review comment the user may not delete from leaving the fixture pull open.
    @Test
    void aRefusedCommentDeletionStillClosesThePull() throws Exception {
        String pulls = "/" + GitHubFixture.ROOT + "/pulls";
        var http =
                preparedTransport("a".repeat(40))
                        .otherwise(
                                request -> {
                                    String path = request.uri().getRawPath();
                                    String body =
                                            switch (request.method() + " " + path) {
                                                case String call when call.equals("GET " + pulls) ->
                                                        """
                                                        [{"number": 7, "head": {"ref": "forge-client-unit"}}]""";
                                                case String call
                                                        when call.equals(
                                                                "GET " + pulls + "/7/comments") ->
                                                        "[{\"id\": 11}, {\"id\": 12}]";
                                                case String call
                                                        when call.equals(
                                                                "DELETE "
                                                                        + pulls
                                                                        + "/comments/11") ->
                                                        null;
                                                case String call when call.endsWith(pulls + "/7") ->
                                                        "{\"state\": \"closed\"}";
                                                case String call
                                                        when call.contains("/check-runs") ->
                                                        "{\"check_runs\": []}";
                                                case String call
                                                        when call.contains("/actions/runs") ->
                                                        "{\"workflow_runs\": []}";
                                                default -> "[]";
                                            };
                                    return body == null
                                            ? new ReplayHttpClient.Answer(
                                                    403, Map.of(), "{\"message\": \"Forbidden\"}")
                                            : new ReplayHttpClient.Answer(200, Map.of(), body);
                                });
        var fixture = fixture(http);
        fixture.prepare();

        assertThatExceptionOfType(IllegalStateException.class)
                .as("the refused deletion is named")
                .isThrownBy(fixture::close)
                .withMessageContaining("review comments")
                .withMessageNotContaining("pull requests");
        assertThat(
                        http.requests.stream()
                                .filter(request -> request.method().equals("PATCH"))
                                .map(request -> request.uri().getRawPath()))
                .as("the pull is closed all the same")
                .containsExactly(pulls + "/7");
        assertThat(
                        http.requests.stream()
                                .filter(request -> request.method().equals("DELETE"))
                                .map(request -> request.uri().getRawPath()))
                .as("a refused comment does not keep the next one")
                .contains(pulls + "/comments/12");
    }

    // Prevents cleanup from rewriting a check run's finished conclusion, or missing the run's own
    // check among more than one page.
    @Test
    void cleanupCompletesOnlyTheRunsOwnOpenCheckRuns() throws Exception {
        var http =
                preparedTransport("a".repeat(40))
                        .answer(200, Map.of(), "[]")
                        .answer(
                                200,
                                Map.of(),
                                """
                                {"total_count": 2, "check_runs": [
                                  {"id": 5, "name": "forge-client-unit", "status": "completed"},
                                  {"id": 6, "name": "forge-client-unit", "status": "queued"}]}""")
                        .answer(200, Map.of(), "{\"status\": \"completed\"}")
                        .otherwise(
                                request ->
                                        new ReplayHttpClient.Answer(
                                                200,
                                                Map.of(),
                                                request.uri().getRawPath().endsWith("/actions/runs")
                                                        ? "{\"workflow_runs\": []}"
                                                        : "[]"));
        var fixture = fixture(http);
        fixture.prepare();

        fixture.close();

        assertThat(
                        http.requests.stream()
                                .filter(request -> request.method().equals("PATCH"))
                                .map(request -> request.uri().getRawPath()))
                .as("only the open check run is completed")
                .containsExactly("/" + GitHubFixture.ROOT + "/check-runs/6");
        assertThat(
                        http.requests.stream()
                                .map(request -> request.uri().getRawQuery())
                                .filter(query -> query != null && query.contains("check_name="))
                                .count())
                .as("GitHub is asked for the run's own check by name")
                .isEqualTo(1);
    }

    // Prevents check runs past the first page from being left open unnoticed.
    @Test
    void moreCheckRunsThanOnePageFailTheCleanup() throws Exception {
        var http =
                preparedTransport("a".repeat(40))
                        .answer(200, Map.of(), "[]")
                        .answer(200, Map.of(), "{\"total_count\": 101, \"check_runs\": []}")
                        .otherwise(
                                request ->
                                        new ReplayHttpClient.Answer(
                                                200,
                                                Map.of(),
                                                request.uri().getRawPath().endsWith("/actions/runs")
                                                        ? "{\"workflow_runs\": []}"
                                                        : "[]"));
        var fixture = fixture(http);
        fixture.prepare();

        assertThatExceptionOfType(IllegalStateException.class)
                .isThrownBy(fixture::close)
                .withMessageContaining("check runs");
    }

    // Prevents raw credentials, signed addresses and real accounts from entering fixture files.
    @Test
    void recordingsAreScrubbedBeforeWritingAndCredentialShapesAreRefused(@TempDir Path temporary)
            throws Exception {
        var json = new ObjectMapper();
        var scrubber =
                new RecordingScrubber(
                        List.of("synthetic-private-token"), Map.of("actual-user", "fixture-user"));
        var input =
                json.readTree(
                        "{\"body\":\"{\\\"user\\\":{\\\"login\\\":\\\"actual-user\\\"},"
                                + "\\\"url\\\":\\\"https://storage.invalid/log?signature=hidden\\\","
                                + "\\\"message\\\":\\\"synthetic-private-token\\\"}\"}");
        Path recording = temporary.resolve("fixture.json");
        GitHubRecorder.write(recording, scrubber.scrub(input));
        String output = Files.readString(recording);
        assertThat(output)
                .as("only scrubbed data reaches disk")
                .contains("fixture-user", "example.org", "[redacted]")
                .doesNotContain(
                        "actual-user",
                        "storage.invalid",
                        "signature",
                        "hidden",
                        "synthetic-private-token");
        for (String shaped :
                List.of(
                        "ghp_" + "a".repeat(36),
                        "github_pat_" + "b".repeat(40),
                        "eyJabc.def.ghi",
                        "-----BEGIN PRIVATE KEY-----")) {
            Path denied = temporary.resolve("denied.json");
            assertThatExceptionOfType(IllegalStateException.class)
                    .as("credential-shaped output must not be written")
                    .isThrownBy(
                            () ->
                                    GitHubRecorder.write(
                                            denied, json.getNodeFactory().textNode(shaped)));
            assertThat(denied).as("rejected credential output creates no file").doesNotExist();
        }
    }

    // Prevents the recorder from crashing on the URI templates every GitHub account object carries,
    // and a log redirect that left the API origin from staying on it when replayed.
    @Test
    void templatesSurviveAndEachForeignHostGetsItsOwnFictionalHost() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var input =
                new ObjectMapper()
                        .readTree(
                                "{\"following_url\":\"https://api.github.com/"
                                        + "users/actual-user/following{/other_user}\","
                                        + "\"repo\":\"https://api.github.com/repos{/owner}{/repo}\","
                                        + "\"log\":\"https://logs.invalid/a?sig=hidden\","
                                        + "\"again\":\"http://logs.invalid:8443/b\","
                                        + "\"api_port\":\"https://api.github.com:8443/log\","
                                        + "\"ipv6\":\"https://[2001:db8::1]/d\","
                                        + "\"other_ipv6\":\"https://[2001:db8::2]/e\","
                                        + "\"other\":\"https://store.invalid/c#frag\"}");

        var scrubbed = scrubber.scrub(input);

        assertThat(scrubbed.path("following_url").asText())
                .as("the template part is kept and the account scrubbed")
                .isEqualTo("https://example.org/users/fixture-user/following{/other_user}");
        assertThat(scrubbed.path("repo").asText())
                .as("several templates in a row are kept")
                .isEqualTo("https://example.org/repos{/owner}{/repo}");
        String log = scrubbed.path("log").asText();
        assertThat(log)
                .as("a foreign host leaves the API host's fictional one")
                .doesNotContain("://example.org");
        assertThat(log).as("a query never reaches the recording").doesNotContain("hidden");
        assertThat(scrubbed.path("again").asText())
                .as("the same foreign host maps to the same fictional host")
                .isEqualTo("http://" + java.net.URI.create(log).getHost() + ":8443/b");
        assertThat(scrubbed.path("api_port").asText())
                .as("a different port on the API host still leaves the replay origin")
                .isEqualTo("https://example.org:8443/log");
        assertThat(java.net.URI.create(scrubbed.path("other").asText()).getHost())
                .as("another foreign host maps to another fictional host")
                .isNotEqualTo(java.net.URI.create(log).getHost())
                .endsWith(".example.org");
        assertThat(scrubbed.path("ipv6").asText())
                .as("different IPv6 hosts remain different fictional origins")
                .isNotEqualTo(scrubbed.path("other_ipv6").asText().replace("/e", "/d"));
    }

    // Prevents a signed log address from reaching a recording because its scheme is upper case or
    // its redirect is relative.
    @Test
    void signedAddressesLoseTheirQueriesWhateverTheirForm() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"log": "HTTPS://Logs.Invalid/a?sig=hidden",
                                 "headers": {"location": ["/download/logs?sig=hidden#frag"]}}
                                """);

        var scrubbed = scrubber.scrub(input);

        assertThat(scrubbed.path("log").asText())
                .as("an upper-case scheme is scrubbed like a lower-case one")
                .doesNotContain("Logs.Invalid", "hidden")
                .endsWith(".example.org/a");
        assertThat(scrubbed.path("headers").path("location").path(0).asText())
                .as("a relative redirect keeps its path and loses its query")
                .isEqualTo("/download/logs");
    }

    // Prevents a test account's real identity - its numeric id, node id, profile and the App it
    // runs - from entering a committed recording, in a field or in a URL.
    @Test
    void accountAndAppIdentitiesAreScrubbedEverywhere() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var json = new ObjectMapper();
        String body =
                """
                {"user": {"login": "actual-user", "id": 1856319,
                          "node_id": "MDQ6VXNlcjE4NTYzMTk=",
                          "avatar_url": "https://avatars.invalid/u/1856319?v=4",
                          "location": "Real Town", "bio": "Owner of Real Firm",
                          "company": "Real Firm", "blog": "https://real.invalid",
                          "twitter_username": "realhandle"},
                 "app": {"id": 7654321, "client_id": "Iv23liRealClient", "slug": "real-app",
                         "node_id": "A_realAppNode", "name": "Real App",
                         "html_url": "https://web.invalid/apps/real-app"},
                 "note": "by real-app for 1856319"}
                """;
        // GitHub's answer is kept as text inside the recording, as the recorder keeps it.
        var input = json.createObjectNode().put("body", body);

        String output = scrubber.scrub(input).toString();

        assertThat(
                        scrubber.scrub(
                                        json.readTree(
                                                """
                                                {"app": {"id": 1, "client_id": "c", "slug": "same-app",
                                                         "name": "same-app",
                                                         "html_url": "https://web.invalid/apps/same-app"}}
                                                """))
                                .path("app")
                                .path("html_url")
                                .asText())
                .as("an App named like its slug keeps a valid URL")
                .doesNotContain(" ", "same-app");
        assertThat(output)
                .as("no account or App identity survives, in a field or in text")
                .doesNotContain(
                        "1856319",
                        "MDQ6VXNlcjE4NTYzMTk",
                        "Real Town",
                        "Real Firm",
                        "real.invalid",
                        "realhandle",
                        "7654321",
                        "Iv23liRealClient",
                        "real-app",
                        "A_realAppNode",
                        "Real App");
    }

    // Prevents an App's name, replaced inside an address, from splitting the address so its tail
    // and its signed query stay in the recording.
    @Test
    void anAppNameInsideAnAddressKeepsTheAddressWhole() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"app": {"id": 7, "client_id": "c", "slug": "acme-ci", "name": "acme"},
                                 "log": "https://storage.invalid/acme/logs?sig=secret"}
                                """);

        String log = scrubber.scrub(input).path("log").asText();

        assertThat(log)
                .as("the address stays one address, without the name or the query")
                .doesNotContain(" ", "acme", "secret")
                .endsWith("/logs");
    }

    // Prevents an identity first seen in a later answer from staying in the text of an earlier one.
    @Test
    void identitiesAreLearnedFromEveryCaptureBeforeAnyIsScrubbed(@TempDir Path directory)
            throws Exception {
        var json = new ObjectMapper();
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var earlier =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree("{\"body\": \"made by real-app\"}");
        var later =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        json.readTree(
                                """
                                {"app": {"id": 7, "client_id": "c", "slug": "real-app", "name": "Real"}}
                                """);

        GitHubRecorder.writeAll(
                directory,
                List.of(
                        new GitHubScenario.Capture("createPull", earlier),
                        new GitHubScenario.Capture("createCheckRun", later)),
                scrubber);

        assertThat(Files.readString(directory.resolve("createPull.json")))
                .as("the earlier answer loses the App's slug learned from the later one")
                .doesNotContain("real-app");
    }

    // Prevents an account's or App's number from being replaced inside a commit hash, which would
    // break the recording.
    @Test
    void anIdentityNumberIsReplacedOnlyAsAWholeWord() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"user": {"login": "actual-user", "id": 4433221},
                                 "sha": "c7094bf4433221a0d152eb58a449fee5471ba2c0",
                                 "avatar": "https://avatars.invalid/u/4433221"}
                                """);

        var scrubbed = scrubber.scrub(input);

        assertThat(scrubbed.path("sha").asText())
                .as("a hash containing the number is kept")
                .isEqualTo("c7094bf4433221a0d152eb58a449fee5471ba2c0");
        assertThat(scrubbed.path("avatar").asText())
                .as("the number as a word is replaced")
                .doesNotContain("4433221");
    }

    // Prevents a GraphQL account, whose number is its databaseId and whose id is its node id, from
    // keeping either in the recording when no REST answer named the account first.
    @Test
    void aGraphQlAccountLosesItsNumberAndNodeId() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"data": {"node": {"resolvedBy":
                                  {"login": "actual-user", "id": "U_kgDOActual", "databaseId": 4433221},
                                 "comments": {"nodes": [{"id": "PRRC_kwDOComment", "databaseId": 98765,
                                  "author": {"login": "actual-user", "id": "U_kgDOActual",
                                   "databaseId": 4433221}}]}}}}
                                """);

        String scrubbed = scrubber.scrub(input).toString();

        assertThat(scrubbed.contains("4433221")).as("the account's number is replaced").isFalse();
        assertThat(scrubbed.contains("U_kgDOActual"))
                .as("the account's node id is replaced")
                .isFalse();
        assertThat(scrubbed.contains("98765")).as("a comment's own number is kept").isTrue();
    }

    // Prevents an account the recording was not told about from passing under a fixture name while
    // its real id and URLs stay in the file.
    @Test
    void anAccountTheRecordingDoesNotKnowIsRefused() {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        assertThatExceptionOfType(IllegalStateException.class)
                .as("an unknown login stops the recording")
                .isThrownBy(
                        () ->
                                scrubber.scrub(
                                        new ObjectMapper()
                                                .readTree(
                                                        """
                                                        {"user": {"login": "someone-else", "id": 7}}
                                                        """)));
    }

    // Prevents a comparison from failing to record, or keeping a real account, when a commit was
    // written or committed by an account no test token belongs to, such as GitHub's own committer.
    @Test
    void aCommitsUnknownAccountGetsAFixtureName() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"commits": [{"sha": "a1", "commit": {"message": "m"},
                                  "author": {"login": "actual-user", "id": 11},
                                  "committer": {"login": "real-committer", "id": 4455667,
                                   "node_id": "MDQ6VXNlcjRealCommitter",
                                   "html_url": "https://github.com/real-committer"}},
                                 {"sha": "b2", "commit": {"message": "m"},
                                  "author": {"login": "real-author", "id": 7788990}}],
                                 "message": "by real-committer"}
                                """);

        String scrubbed = scrubber.scrub(input).toString();

        assertThat(scrubbed)
                .as("neither login, number nor node id of an unknown commit account survives")
                .doesNotContain(
                        "real-committer", "4455667", "RealCommitter", "real-author", "7788990")
                .contains("\"login\":\"fixture-committer-1\"", "\"login\":\"fixture-committer-2\"");
    }

    // Prevents a comment author the recording was not told about from passing as a commit's
    // account, since GraphQL names a comment's author under the same key as a commit's.
    @Test
    void anUnknownCommentAuthorIsStillRefused() {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        assertThatExceptionOfType(IllegalStateException.class)
                .as("an unknown author of a comment stops the recording")
                .isThrownBy(
                        () ->
                                scrubber.scrub(
                                        new ObjectMapper()
                                                .readTree(
                                                        """
                                                        {"data": {"node": {"comments": {"nodes": [
                                                          {"body": "b", "author": {"login": "real-mannequin"}}]}}}}
                                                        """)));
    }

    // Prevents a commit's author from keeping a real name in a recording through the commit's
    // message, such as a sign-off line, while GitHub's own committer name stays in other text.
    @Test
    void aCommitAuthorsNameIsScrubbedFromItsMessage() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"sha": "a1", "commit": {
                                  "author": {"name": "Real Person", "email": "real@person.invalid"},
                                  "committer": {"name": "GitHub", "email": "noreply@github.com"},
                                  "message": "Init\\n\\nSigned-off-by: Real Person <real@person.invalid>"},
                                 "name": "GitHub Actions"}
                                """);

        String output = scrubber.scrub(input).toString();

        assertThat(output)
                .as("the author's name and address leave the message too")
                .doesNotContain("Real Person", "person.invalid")
                .contains("Signed-off-by: Fixture-User <fixture@example.org>", "GitHub Actions");
    }

    // Prevents an account learned from a commit from passing elsewhere, where only accounts the
    // recording was told about may appear, whichever is read first.
    @Test
    void aCommitAccountIsStillRefusedElsewhere() {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        assertThatExceptionOfType(IllegalStateException.class)
                .as("a commit's account is no account the recording was told about")
                .isThrownBy(
                        () ->
                                scrubber.scrub(
                                        new ObjectMapper()
                                                .readTree(
                                                        """
                                                        {"commits": [{"sha": "a1", "commit": {"message": "m"},
                                                          "author": {"login": "real-mannequin", "id": 1}}],
                                                         "data": {"node": {"comments": {"nodes": [
                                                          {"body": "b", "author": {"login": "real-mannequin"}}]}}}}
                                                        """)));
    }

    // Prevents a commit's tagger, which a REST commit does not link, from passing as a commit
    // account.
    @Test
    void anUnknownTaggerIsRefused() {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        assertThatExceptionOfType(IllegalStateException.class)
                .as("only a commit's author and committer may be unknown")
                .isThrownBy(
                        () ->
                                scrubber.scrub(
                                        new ObjectMapper()
                                                .readTree(
                                                        """
                                                        {"sha": "a1", "commit": {"message": "m"},
                                                         "tagger": {"login": "stranger", "id": 1}}
                                                        """)));
    }

    // Prevents a git author named like a known login from replacing that login in addresses with a
    // name, which would put a space into them and stop their scrubbing early.
    @Test
    void aGitNameLikeALoginKeepsTheLoginsFixture() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of("actual-user", "fixture-user"));
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"sha": "a1",
                                 "url": "https://api.github.com/repos/actual-user/repo/commits/a1",
                                 "commit": {"author": {"name": "actual-user", "email": "a@b.invalid"},
                                  "message": "by Real Name"},
                                 "author": {"login": "actual-user", "id": 11}}
                                """);

        String output = scrubber.scrub(input).toString();

        assertThat(output)
                .as("the login keeps its fixture name in addresses")
                .contains("https://example.org/repos/fixture-user/repo/commits/a1")
                .doesNotContain("actual-user", "Fixture User/");
    }

    // Prevents a git author named like a commit account's login, read before that account in
    // GitHub's order, from replacing the login with a name instead of the account's fixture.
    @Test
    void aGitNameLikeACommitAccountKeepsItsFixture() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"sha": "a1", "url": "https://api.github.com/users/ext-user",
                                 "commit": {"author": {"name": "ext-user", "email": "a@b.invalid"},
                                  "message": "by ext-user"},
                                 "author": {"login": "ext-user", "id": 11}}
                                """);

        String output = scrubber.scrub(input).toString();

        assertThat(output)
                .as("the login keeps its account's fixture everywhere")
                .contains("https://example.org/users/fixture-committer-1", "by fixture-committer-1")
                .doesNotContain("ext-user", "Fixture-User");
    }

    // Prevents a signed commit's raw text, which names its author as git does, from keeping a real
    // name in a recording.
    @Test
    void aCommitsSignedTextIsDropped() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                """
                                {"commit": {"verification": {"verified": true, "reason": "valid",
                                  "signature": "-----BEGIN PGP SIGNATURE-----\\nabc\\n",
                                  "payload": "tree 4b41\\nauthor Real Person <real@person.invalid> 1 +0100\\n",
                                  "verified_at": "2026-10-31T05:09:41Z"}}}
                                """);

        JsonNode verification = scrubber.scrub(input).path("commit").path("verification");

        assertThat(verification.path("payload").isNull()).as("the raw commit is dropped").isTrue();
        assertThat(verification.path("signature").isNull())
                .as("the signature over it is dropped")
                .isTrue();
        assertThat(verification.path("verified").asBoolean())
                .as("what GitHub found is kept")
                .isTrue();
    }

    // Prevents a test user's real display name from entering a recording through a commit author.
    @Test
    void gitCommitAuthorAndCommitterNamesAreScrubbed() throws Exception {
        var scrubber = new RecordingScrubber(List.of(), Map.of());
        var input =
                new ObjectMapper()
                        .readTree(
                                "{\"head_commit\":{\"author\":{\"name\":\"Real Person\","
                                        + "\"email\":\"real@person.invalid\"},"
                                        + "\"committer\":{\"name\":\"Real Person\",\"email\":\"real@person.invalid\"},"
                                        + "\"message\":\"keep\"}}");

        String output = scrubber.scrub(input).toString();

        assertThat(output)
                .as("neither name nor email of a commit identity survives")
                .doesNotContain("Real Person", "person.invalid")
                .contains("Fixture User", "keep");
    }

    // Prevents opaque repository clone credentials from reaching disk even when their format is
    // unknown.
    @Test
    void repositoryCloneCredentialsAreScrubbedBeforeWriting(@TempDir Path directory)
            throws Exception {
        var json = new ObjectMapper();
        String credential = "synthetic-opaque-clone-credential";
        var recording =
                json.createObjectNode()
                        .put(
                                "body",
                                "{\"head\":{\"repo\":{\"temp_clone_token\":\""
                                        + credential
                                        + "\"}},"
                                        + "\"base\":{\"repo\":{\"temp_clone_token\":\""
                                        + credential
                                        + "\"}}}");

        GitHubRecorder.writeAll(
                directory,
                List.of(new GitHubScenario.Capture("clone", recording)),
                new RecordingScrubber(List.of(), Map.of()));

        String written = Files.readString(directory.resolve("clone.json"));
        assertThat(written)
                .as("an opaque clone credential never reaches disk")
                .doesNotContain(credential);
        var body = json.readTree(json.readTree(written).path("body").asText());
        assertThat(
                        List.of(
                                body.at("/head/repo/temp_clone_token").asText(),
                                body.at("/base/repo/temp_clone_token").asText()))
                .as("both repository credentials are redacted")
                .containsExactly("[redacted]", "[redacted]");
    }

    // Prevents a refusal on a later capture from leaving earlier recordings overwritten.
    @Test
    void nothingIsWrittenWhenAnyCaptureIsRefused(@TempDir Path temporary) throws Exception {
        var json = new ObjectMapper();
        Path existing = temporary.resolve("first.json");
        Files.writeString(existing, "old\n");
        var captures =
                List.of(
                        new GitHubScenario.Capture("first", json.createObjectNode().put("a", "b")),
                        new GitHubScenario.Capture(
                                "second",
                                json.createObjectNode().put("a", "-----BEGIN PRIVATE KEY-----x")));
        // The key marker is not scrubbed away, so the second capture is refused.
        var scrubber = new RecordingScrubber(List.of(), Map.of());

        assertThatExceptionOfType(IllegalStateException.class)
                .as("a credential-shaped capture is refused")
                .isThrownBy(() -> GitHubRecorder.writeAll(temporary, captures, scrubber));

        assertThat(Files.readString(existing))
                .as("the earlier recording is untouched")
                .isEqualTo("old\n");
        try (var files = Files.list(temporary)) {
            assertThat(files.toList()).as("no other file appears").containsExactly(existing);
        }
    }

    // Prevents the recorder from creating directories behind a symbolic link before it refuses
    // them.
    @Test
    void aLinkedRecordingsPathIsRefusedBeforeAnythingIsCreated(@TempDir Path directory)
            throws Exception {
        Path temporary = directory.toRealPath();
        Path target = Files.createDirectory(temporary.resolve("target"));
        Path link = Files.createSymbolicLink(temporary.resolve("link"), target);

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a path through a link is refused")
                .isThrownBy(() -> GitHubRecorder.checkedDirectory(link.resolve("new/recordings")));

        try (var files = Files.list(target)) {
            assertThat(files.toList()).as("nothing was created behind the link").isEmpty();
        }
        Path real =
                GitHubRecorder.checkedDirectory(temporary.toRealPath().resolve("fresh/recordings"));
        assertThat(real).as("a real path is created").isDirectory();
    }

    // Prevents a committed recording from carrying a credential or signed download query.
    @Test
    void allOperationRecordingsAreFreeOfCredentialShapes() throws Exception {
        for (var operation : GitHubOperations.values()) {
            var recording = GitHubRecordingTest.recording(operation.name());
            assertThat(GitHubRecorder.CREDENTIAL.matcher(recording.toString()).find())
                    .as("%s must contain no credential shape", operation)
                    .isFalse();
            assertThat(recording.toString())
                    .as("%s contains no signing-key material", operation)
                    .doesNotContain("PRIVATE KEY");
            if (recording.has("exchanges"))
                for (var exchange : recording.path("exchanges")) {
                    for (var location : exchange.path("headers").path("location"))
                        assertThat(java.net.URI.create(location.asText()).getQuery())
                                .as("recorded signed URLs lose their queries")
                                .isNull();
                }
        }
    }

    private static GitHubFixture fixture(ReplayHttpClient http) {
        return new GitHubFixture(
                http, "user", "reviewer", "app", List.of("user", "reviewer", "app"), "unit");
    }

    private static ReplayHttpClient preparedTransport(String sha) {
        return new ReplayHttpClient()
                .answer(200, Map.of(), "[]")
                .answer(200, Map.of(), "[]")
                .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                .answer(200, Map.of(), "{\"object\":{\"sha\":\"" + sha + "\"}}")
                .answer(201, Map.of(), "{\"ref\":\"refs/heads/forge-client-unit\"}")
                .answer(201, Map.of(), "{\"commit\":{\"sha\":\"" + sha + "\"}}");
    }
}
