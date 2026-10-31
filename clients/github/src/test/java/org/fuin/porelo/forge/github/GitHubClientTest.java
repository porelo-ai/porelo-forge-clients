package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.model.PullsCreateReplyForReviewCommentRequest;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

class GitHubClientTest {
    private static final URI BASE = GitHubRecordingTest.BASE;
    private static final String TOKEN = GitHubRecordingTest.TOKEN;

    // Prevents labeled pull requests from failing when GitHub sends null archive data or
    // descriptions.
    @ParameterizedTest
    @EnumSource(Strictness.class)
    void labeledPullsAcceptNullableDescriptionsAndArchiveFields(Strictness strictness)
            throws Exception {
        var mapper = GitHubJson.mapper(strictness);
        var pull =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("pull").path("body").asText());
        // The recorded pull has no label; GitHub's own example in the extract gives one.
        var label =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        extractExample("pull-request").path("labels").path(0).deepCopy();
        pull.putArray("labels").add(label);
        label.putNull("description");
        label.putNull("archived_by");
        label.putNull("archived_at");
        var pulls = mapper.readTree(GitHubRecordingTest.recording("pulls").path("body").asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) pulls.path(0))
                .set("labels", pull.path("labels"));
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), pull.toString())
                        .answer(200, Map.of(), pulls.toString());
        var client = new GitHubClient(BASE, http, strictness);

        var single = client.pull(TOKEN, "owner", "repo", 1).labels().getFirst();
        var listed =
                client.pulls(TOKEN, "owner", "repo", "open", 1, 50).getFirst().labels().getFirst();

        assertThat(mapper.<com.fasterxml.jackson.databind.JsonNode>valueToTree(single))
                .as(
                        "a labeled pull preserves its identity with nullable metadata in %s",
                        strictness)
                .isEqualTo(mapper.valueToTree(listed));
        assertThat(single.description()).as("an absent description is nullable").isNull();
        assertThat(single.archivedBy()).as("an unarchived label has no archiver").isNull();
        assertThat(single.archivedAt()).as("an unarchived label has no archive time").isNull();

        label.put("archived_at", "2020-01-20T17:40:00Z");
        var archivedHttp = new ReplayHttpClient().answer(200, Map.of(), pull.toString());
        var archived =
                new GitHubClient(BASE, archivedHttp, strictness)
                        .pull(TOKEN, "owner", "repo", 1)
                        .labels()
                        .getFirst();
        assertThat(archived.archivedAt())
                .as("an archive time is exposed as a date-time")
                .isEqualTo(java.time.OffsetDateTime.parse("2020-01-20T17:40:00Z"));
    }

    // Prevents a null in an open map, which its schema does not allow, from reaching a caller the
    // records promise a map without nulls.
    @ParameterizedTest
    @EnumSource(Strictness.class)
    void aNullInAnOpenMapIsRefused(Strictness strictness) throws Exception {
        var mapper = GitHubJson.mapper(strictness);
        var check =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("createCheckRun")
                                        .path("body")
                                        .asText());
        ((com.fasterxml.jackson.databind.node.ObjectNode) check.path("app").path("permissions"))
                .putNull("not_described");
        var request =
                mapper.treeToValue(
                        GitHubRecordingTest.recording("createCheckRun").path("request_body"),
                        org.fuin.porelo.forge.github.model.ChecksCreateRequest.class);
        var http = new ReplayHttpClient().answer(201, Map.of(), check.toString());

        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, http, strictness)
                                        .createCheckRun(TOKEN, "owner", "repo", request));
    }

    // Prevents an account view the description does not map, an empty one included, from being
    // read as one it does.
    @ParameterizedTest
    @ValueSource(strings = {"", "internal"})
    void anAccountViewTheDescriptionDoesNotMapIsRefused(String view) throws Exception {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var user =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("user").path("body").asText());
        user.put("user_view_type", view);
        var http = new ReplayHttpClient().answer(200, Map.of(), user.toString());

        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> new GitHubClient(BASE, http, Strictness.STRICT).user(TOKEN));
    }

    // Prevents an account without a view from failing to read, or being read as the wrong one.
    @Test
    void anAccountWithoutAViewIsReadByItsFields() throws Exception {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var user =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("user").path("body").asText());
        user.remove("user_view_type");
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), user.toString())
                        .answer(
                                200,
                                Map.of(),
                                user.put("user_view_type", (String) null).toString());
        var client = new GitHubClient(BASE, http, Strictness.STRICT);

        assertThat(client.user(TOKEN))
                .as("an absent view reads the public account")
                .isInstanceOf(org.fuin.porelo.forge.github.model.PublicUser.class);
        assertThat(client.user(TOKEN))
                .as("a null view reads the public account")
                .isInstanceOf(org.fuin.porelo.forge.github.model.PublicUser.class);

        // The recorded account is public; GitHub's own example gives a private one.
        var own =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        extractExample(
                                        "private-user-response-with-public-and-private-profile-information")
                                .deepCopy();
        own.remove("user_view_type");
        String absentView = own.toString();
        String nullView = own.put("user_view_type", (String) null).toString();
        // The fields decide in either strictness; a lenient reading must not settle on the public
        // account and drop the private profile.
        for (Strictness strictness : Strictness.values()) {
            var ownHttp =
                    new ReplayHttpClient()
                            .answer(200, Map.of(), absentView)
                            .answer(200, Map.of(), nullView);
            var ownClient = new GitHubClient(BASE, ownHttp, strictness);
            assertThat(ownClient.user(TOKEN))
                    .as(
                            "an absent view with private fields reads the private account, "
                                    + strictness)
                    .isInstanceOf(org.fuin.porelo.forge.github.model.PrivateUser.class);
            assertThat(ownClient.user(TOKEN))
                    .as("a null view with private fields reads the private account, " + strictness)
                    .isInstanceOf(org.fuin.porelo.forge.github.model.PrivateUser.class);
        }
    }

    // Prevents a check run GitHub marked stale, or that failed to start, from reading as one with
    // no conclusion yet: the check run schema lacks both, which GitHub's request bodies list.
    @ParameterizedTest
    @ValueSource(strings = {"stale", "startup_failure"})
    void checkRunConclusionsOnlyGitHubSetsAreRead(String conclusion) throws Exception {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var check =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("updateCheckRun")
                                        .path("body")
                                        .asText());
        check.put("conclusion", conclusion);
        var http = new ReplayHttpClient().answer(200, Map.of(), check.toString());
        var request =
                mapper.treeToValue(
                        GitHubRecordingTest.recording("updateCheckRun").path("request_body"),
                        org.fuin.porelo.forge.github.model.ChecksUpdateRequest.class);

        var read =
                new GitHubClient(BASE, http, Strictness.STRICT)
                        .updateCheckRun(TOKEN, "owner", "repo", 1, request)
                        .conclusion();

        assertThat(read == null ? null : read.getValue())
                .as("the conclusion is read, not dropped to null")
                .isEqualTo(conclusion);
    }

    // Prevents a real GitHub identifier above the 32-bit range from failing to read: comment,
    // repository and workflow identifiers have long passed it.
    @ParameterizedTest
    @EnumSource(Strictness.class)
    void identifiersAboveTheThirtyTwoBitRangeRead(Strictness strictness) throws Exception {
        long large = 3_000_000_123L;
        var mapper = GitHubJson.mapper(strictness);
        var reply =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        mapper.readTree(
                                GitHubRecordingTest.recording("replyReviewComment")
                                        .path("body")
                                        .asText());
        reply.put("in_reply_to_id", large);
        var runs = mapper.readTree(GitHubRecordingTest.recording("runs").path("body").asText());
        var run =
                (com.fasterxml.jackson.databind.node.ObjectNode) runs.path("workflow_runs").path(0);
        run.put("workflow_id", large);
        run.put("head_repository_id", large);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), reply.toString())
                        .answer(200, Map.of(), runs.toString());
        var client = new GitHubClient(BASE, http, strictness);

        var read =
                client.replyReviewComment(
                        TOKEN,
                        "owner",
                        "repo",
                        1,
                        1,
                        new PullsCreateReplyForReviewCommentRequest("reply"));
        var listed = client.runs(TOKEN, "owner", "repo", "abc", 1, 50).workflowRuns().getFirst();

        assertThat(read.inReplyToId())
                .as("a reply names a comment above the 32-bit range in %s", strictness)
                .isEqualTo(large);
        assertThat(listed.workflowId())
                .as("a run names a workflow above the 32-bit range in %s", strictness)
                .isEqualTo(large);

        var request =
                mapper.readValue(
                        "{\"body\": \"reply\", \"commit_id\": \"abc\", \"path\": \"a.txt\","
                                + " \"in_reply_to\": "
                                + large
                                + "}",
                        org.fuin.porelo.forge.github.model.PullsCreateReviewCommentRequest.class);
        assertThat((Object) request.inReplyTo())
                .as("a new comment answers a comment above the 32-bit range in %s", strictness)
                .isEqualTo(large);
    }

    // Prevents API drift from silently losing build identity.
    @Test
    void renamedFieldsFailByNameStrictlyAndPassLeniently() throws Exception {
        String body =
                GitHubRecordingTest.recording("runs")
                        .path("body")
                        .asText()
                        .replace("\"run_attempt\"", "\"renamed_run_attempt\"");
        var strict = new ReplayHttpClient().answer(200, Map.of(), body);
        assertThatExceptionOfType(GitHubException.class)
                .as("strict reading identifies the changed API field")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, strict, Strictness.STRICT)
                                        .runs(TOKEN, "owner", "repo", "abc", 1, 50))
                .withMessageContaining("renamed_run_attempt");
        var lenient = new ReplayHttpClient().answer(200, Map.of(), body);
        assertThat(
                        new GitHubClient(BASE, lenient)
                                .runs(TOKEN, "owner", "repo", "abc", 1, 50)
                                .workflowRuns())
                .as("compatible additions still expose the recorded runs")
                .isNotEmpty();
    }

    // Prevents token disclosure across any component of a redirect origin.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "https://other.example.org/log",
                "https://example.org:8443/log",
                "https://example.org/log"
            })
    void foreignRedirectsStripAuthenticationPermanently(String location) {
        var http =
                new ReplayHttpClient()
                        .answer(302, Map.of("location", List.of(location)), "")
                        .answer(302, Map.of("location", List.of("http://example.org/return")), "")
                        .answer(200, Map.of(), "fixture-complete");
        // An HTTP base permits testing scheme changes without an HTTPS downgrade.
        var client = new GitHubClient(URI.create("http://example.org/"), http);
        // The HTTPS hop forbids the subsequent downgrade, even back to the API origin.
        assertThatExceptionOfType(GitHubException.class)
                .as("a secure log hop cannot downgrade")
                .isThrownBy(() -> client.jobLogs(TOKEN, "owner", "repo", 1));
        assertThat(http.requests()).as("the cleartext return is not contacted").hasSize(2);
        assertThat(http.requests().getFirst().headers().firstValue("Authorization"))
                .as("the API request carries the token")
                .contains("Bearer " + TOKEN);
        assertThat(http.requests().getLast().headers().firstValue("Authorization"))
                .as("the foreign origin never receives the token")
                .isEmpty();
    }

    // Prevents cleartext log disclosure after an HTTPS API request.
    @Test
    void aLogCannotDowngradeToHttp() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of("location", List.of("http://logs.example.org/log")),
                                "");
        assertThatExceptionOfType(GitHubException.class)
                .as("HTTPS must not downgrade")
                .isThrownBy(() -> new GitHubClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1));
        assertThat(http.requests()).as("the insecure host is never contacted").hasSize(1);
    }

    // Prevents a redirect chain from restoring credentials after leaving the API.
    @Test
    void returningToTheOriginDoesNotRestoreAuthentication() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of("location", List.of("https://logs.example.org/log")),
                                "")
                        .answer(302, Map.of("location", List.of("https://example.org/return")), "")
                        .answer(200, Map.of(), "fixture-complete");
        assertThat(new GitHubClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1))
                .as("redirect chain returns its log")
                .isEqualTo("fixture-complete");
        for (var request : http.requests().subList(1, 3))
            assertThat(request.headers().firstValue("Authorization"))
                    .as("authentication stays removed after a foreign hop")
                    .isEmpty();
    }

    // Prevents same-origin private log downloads from losing required authentication.
    @Test
    void sameOriginRedirectsRetainAuthentication() {
        var http =
                new ReplayHttpClient()
                        .answer(307, Map.of("location", List.of("/download/log")), "")
                        .answer(200, Map.of(), "fixture-complete");
        new GitHubClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1);
        assertThat(http.requests().getLast().headers().firstValue("Authorization"))
                .as("same-origin log storage requires its token")
                .contains("Bearer " + TOKEN);
    }

    // Prevents implicit redirects from bypassing origin checks.
    @ParameterizedTest
    @ValueSource(strings = {"ALWAYS", "NORMAL"})
    void automaticRedirectTransportsAreRefused(String policy) {
        var http = new ReplayHttpClient();
        http.redirects(HttpClient.Redirect.valueOf(policy));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("REST refuses automatic redirects")
                .isThrownBy(() -> new GitHubClient(BASE, http))
                .withMessageContaining("Redirect.NEVER");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("GraphQL refuses automatic redirects")
                .isThrownBy(() -> new GitHubGraphQl(BASE, http))
                .withMessageContaining("Redirect.NEVER");
    }

    // Prevents credentials from escaping in redirect URLs rather than headers.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "?access=synthetic-access-token",
                "?access=%73ynthetic-access-token",
                "/synthetic-access-token"
            })
    void reflectedTokensInForeignRedirectUrlsAreRefused(String suffix) {
        var http =
                new ReplayHttpClient()
                        .answer(
                                302,
                                Map.of(
                                        "location",
                                        List.of("https://logs.example.org/log" + suffix)),
                                "");
        assertThatExceptionOfType(GitHubException.class)
                .as("a redirect URL cannot exfiltrate the token")
                .isThrownBy(() -> new GitHubClient(BASE, http).jobLogs(TOKEN, "owner", "repo", 1))
                .withMessageNotContaining(TOKEN)
                .withNoCause();
        assertThat(http.requests())
                .as("the credential-bearing destination is never contacted")
                .hasSize(1);
    }

    // Prevents dot segments from addressing a different resource after normalization.
    @ParameterizedTest
    @ValueSource(strings = {".", ".."})
    void dotSegmentsAreRefusedBeforeIo(String value) {
        var http = new ReplayHttpClient();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a dot is not a ref name")
                .isThrownBy(() -> new GitHubClient(BASE, http).ref(TOKEN, "owner", "repo", value));
        assertThat(http.requests()).as("invalid input causes no I/O").isEmpty();
    }

    // Prevents a multi-segment reference from losing its slashes, which GitHub's routing needs.
    @Test
    void refSegmentsKeepTheirSlashesAndEachIsEncoded() throws Exception {
        var http = new ReplayHttpClient().recording("ref").recording("checkRuns");
        var client = new GitHubClient(BASE, http);
        client.ref(TOKEN, "owner", "repo", "heads/feature/a b");
        client.checkRuns(TOKEN, "owner", "repo", "feature/v1.0", 1, 50);
        assertThat(http.requests().getFirst().uri().getRawPath())
                .as("the slashes between segments stay, the segments are encoded")
                .isEqualTo("/repos/owner/repo/git/ref/heads/feature/a%20b");
        assertThat(http.requests().getLast().uri().getRawPath())
                .as("check runs take the same multi-segment reference")
                .isEqualTo("/repos/owner/repo/commits/feature/v1%2E0/check-runs");
    }

    // Prevents branch input from injecting paths, queries, dot segments or empty segments.
    @ParameterizedTest
    @ValueSource(
            strings = {
                "../feature/a?x=1",
                "heads/../main",
                "heads//main",
                "/heads/main",
                "heads/main/",
                "heads/./main"
            })
    void refsWithEmptyOrDotSegmentsAreRefusedBeforeIo(String ref) {
        var http = new ReplayHttpClient();
        var client = new GitHubClient(BASE, http);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("%s is not a ref", ref)
                .isThrownBy(() -> client.ref(TOKEN, "owner", "repo", ref));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("%s is not a commit reference", ref)
                .isThrownBy(() -> client.checkRuns(TOKEN, "owner", "repo", ref, 1, 50));
        assertThat(http.requests()).as("invalid input causes no I/O").isEmpty();
    }

    // Prevents a query-string injection through a segment from reaching the wire.
    @Test
    void aQuestionMarkInsideASegmentIsEncoded() throws Exception {
        var http = new ReplayHttpClient().recording("ref");
        new GitHubClient(BASE, http).ref(TOKEN, "owner", "repo", "heads/a?x=1");
        assertThat(http.requests().getFirst().uri().getRawPath())
                .as("the query character cannot end the path")
                .isEqualTo("/repos/owner/repo/git/ref/heads/a%3Fx%3D1");
        assertThat(http.requests().getFirst().uri().getRawQuery()).as("no query is added").isNull();
    }

    // Prevents a payload mismatch from being reported without the field that differs.
    @Test
    void aMissingRequiredFieldIsNamedByItsPathWithoutValues() {
        String secret = "value-that-must-not-leak";
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"total_count\":1,\"workflow_runs\":[{\"name\":\""
                                        + secret
                                        + "\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("the error names where the payload differs")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, http)
                                        .runs(TOKEN, "owner", "repo", "abc", 1, 50))
                .withMessageContaining("workflow_runs[0].id")
                .withMessageNotContaining(secret)
                .withNoCause();
    }

    // Prevents null or wrongly typed nested fields from losing their path or leaking input values.
    @ParameterizedTest
    @ValueSource(strings = {"missing", "null", "wrong-type"})
    void nestedMappingFailuresNameOnlyTheFieldPath(String failure) throws Exception {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var body = mapper.readTree(GitHubRecordingTest.recording("ref").path("body").asText());
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) body.path("object");
        switch (failure) {
            case "missing" -> object.remove("sha");
            case "null" -> object.putNull("sha");
            case "wrong-type" -> object.putArray("sha").add("value-that-must-not-leak");
            default -> throw new IllegalArgumentException("Unknown failure");
        }
        var http = new ReplayHttpClient().answer(200, Map.of(), body.toString());

        assertThatExceptionOfType(GitHubException.class)
                .as("the nested %s failure identifies its field", failure)
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, http)
                                        .ref(TOKEN, "owner", "repo", "heads/main"))
                .withMessageContaining("object.sha")
                .withMessageNotContaining("value-that-must-not-leak")
                .withNoCause();
    }

    // Prevents mapping paths from leaking credentials embedded in property names or values.
    @ParameterizedTest
    @ValueSource(strings = {"null", "\"value-that-must-not-leak\""})
    void mappingPathsScrubCredentialsAndKeepIndexes(String value) {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var type =
                mapper.constructType(
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, List<Integer>>>() {}.getType());

        assertThatExceptionOfType(GitHubException.class)
                .as("mapping paths expose only redacted names and indexes")
                .isThrownBy(
                        () ->
                                GitHubJson.read(
                                        mapper, "{\"" + TOKEN + "\":[" + value + "]}", type, TOKEN))
                .withMessageContaining("[redacted][0]")
                .withMessageNotContaining(TOKEN)
                .withMessageNotContaining("value-that-must-not-leak")
                .withNoCause();
    }

    // Prevents a GraphQL constructor failure from hiding which selected field was absent.
    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"data\":null}"})
    void absentGraphQlDataIsNamedWithoutExposingThePayload(String body) {
        var http = new ReplayHttpClient().answer(200, Map.of(), body);

        assertThatExceptionOfType(GitHubException.class)
                .as("the required GraphQL data field is named")
                .isThrownBy(
                        () ->
                                new GitHubGraphQl(BASE, http, Strictness.STRICT)
                                        .reviewThreads(TOKEN, "owner", "repo", 1, null))
                .withMessageEndingWith(": data")
                .withNoCause();
    }

    // Prevents malformed payloads from leaking null collection entries into callers.
    @Test
    void collectionNullsAreRefusedAtEveryDepth() {
        var types =
                List.of(
                        new com.fasterxml.jackson.core.type.TypeReference<List<String>>() {},
                        new com.fasterxml.jackson.core.type.TypeReference<
                                Map<String, List<Map<String, List<String>>>>>() {});
        for (Strictness strictness : Strictness.values()) {
            var mapper = GitHubJson.mapper(strictness);
            for (int i = 0; i < types.size(); i++) {
                String body = i == 0 ? "[null]" : "{\"outer\":[{\"inner\":[null]}]}";
                var type = mapper.constructType(types.get(i).getType());
                assertThatExceptionOfType(GitHubException.class)
                        .as("nested collection nulls fail in %s", strictness)
                        .isThrownBy(() -> GitHubJson.read(mapper, body, type, TOKEN))
                        .withMessageContaining("non-null");
            }
            assertThatExceptionOfType(GitHubException.class)
                    .as("map values cannot be null in %s", strictness)
                    .isThrownBy(
                            () ->
                                    GitHubJson.read(
                                            mapper,
                                            "{\"outer\":null}",
                                            mapper.getTypeFactory()
                                                    .constructMapType(
                                                            Map.class, String.class, String.class),
                                            TOKEN));
            var http =
                    new ReplayHttpClient()
                            .answer(200, Map.of(), "{\"total_count\":1,\"workflow_runs\":[null]}");
            assertThatExceptionOfType(GitHubException.class)
                    .as("record collection nulls fail in %s", strictness)
                    .isThrownBy(
                            () ->
                                    new GitHubClient(BASE, http, strictness)
                                            .runs(TOKEN, "owner", "repo", "abc", 1, 50));
        }
    }

    // Prevents untyped collection values from bypassing the non-null container contract.
    @Test
    void nullsInsideUntypedNestedContainersAreRefused() {
        for (Strictness strictness : Strictness.values()) {
            var mapper = GitHubJson.mapper(strictness);
            for (String body : List.of("[{\"nested\":[null]}]", "[{\"nested\":null}]")) {
                assertThatExceptionOfType(GitHubException.class)
                        .as("untyped nested collection nulls fail in %s", strictness)
                        .isThrownBy(
                                () ->
                                        GitHubJson.read(
                                                mapper,
                                                body,
                                                mapper.getTypeFactory()
                                                        .constructCollectionType(
                                                                List.class, Object.class),
                                                TOKEN));
            }
        }
    }

    // Prevents check timestamps from being sent as numbers that GitHub rejects.
    @Test
    void checkTimestampsAreSentAsIso8601Strings() throws Exception {
        var mapper = GitHubJson.mapper(Strictness.STRICT);
        var body =
                mapper.readValue(
                        "{\"name\":\"fixture\",\"head_sha\":\"abc\",\"started_at\":\"2026-01-01T00:00:00Z\"}",
                        org.fuin.porelo.forge.github.model.ChecksCreateRequest.class);
        var http = new ReplayHttpClient().recording("createCheckRun").recording("updateCheckRun");
        var client = new GitHubClient(BASE, http);
        client.createCheckRun(TOKEN, "owner", "repo", body);
        var update =
                mapper.readValue(
                        "{\"status\":\"completed\",\"conclusion\":\"success\","
                                + "\"completed_at\":\"2026-01-01T00:00:00Z\"}",
                        org.fuin.porelo.forge.github.model.ChecksUpdateRequest.class);
        client.updateCheckRun(TOKEN, "owner", "repo", 1, update);
        assertThat(
                        mapper.readTree(GitHubRecordingTest.requestBody(http.requests().getLast()))
                                .path("completed_at")
                                .asText())
                .as("updated check timestamps match the API date-time format")
                .isEqualTo("2026-01-01T00:00:00Z");
        assertThat(
                        mapper.readTree(GitHubRecordingTest.requestBody(http.requests().getFirst()))
                                .path("started_at")
                                .asText())
                .as("outgoing check timestamps match the API date-time format")
                .isEqualTo("2026-01-01T00:00:00Z");
    }

    // Prevents missing mutation bodies from becoming unintended empty writes.
    @Test
    void nullBodiesFailBeforeIo() {
        var http = new ReplayHttpClient();
        var client = new GitHubClient(BASE, http);
        List<Runnable> calls =
                List.of(
                        () -> client.createPull(TOKEN, "owner", "repo", null),
                        () -> client.createReview(TOKEN, "owner", "repo", 1, null),
                        () -> client.createReviewComment(TOKEN, "owner", "repo", 1, null),
                        () -> client.replyReviewComment(TOKEN, "owner", "repo", 1, 1, null),
                        () -> client.createCheckRun(TOKEN, "owner", "repo", null),
                        () -> client.updateCheckRun(TOKEN, "owner", "repo", 1, null),
                        () -> client.updateReviewComment(TOKEN, "owner", "repo", 1, null),
                        () -> client.createIssueComment(TOKEN, "owner", "repo", 1, null),
                        () -> client.updateIssueComment(TOKEN, "owner", "repo", 1, null),
                        () -> client.addKey(TOKEN, null),
                        () -> client.addSigningKey(TOKEN, null),
                        () -> client.createRepository(TOKEN, "org", null));
        for (var call : calls)
            assertThatExceptionOfType(NullPointerException.class)
                    .as("every write requires its body")
                    .isThrownBy(call::run);
        assertThat(http.requests()).as("body validation precedes all transport use").isEmpty();
    }

    // Prevents a missing token from sending an anonymous request where one is due.
    @Test
    void nullTokensFailBeforeIo() {
        var http = new ReplayHttpClient();
        var client = new GitHubClient(BASE, http);
        List<Runnable> calls =
                List.of(
                        () -> client.repository(null, "owner", "repo"),
                        () -> client.ownKeys(null, 1, 50),
                        () -> client.deleteKey(null, 1),
                        () -> client.deleteSigningKey(null, 1),
                        () -> client.jobLogs(null, "owner", "repo", 1),
                        () -> client.graphQl(null, Map.of("query", "{}"), Object.class));
        for (var call : calls)
            assertThatExceptionOfType(NullPointerException.class)
                    .as("every call but the key reads requires its token")
                    .isThrownBy(call::run);
        assertThat(http.requests()).as("token validation precedes all transport use").isEmpty();
    }

    // Prevents general comments read without a time from sending an empty filter, which would
    // differ from reading them all.
    @Test
    void generalCommentsWithoutATimeSendOnlyThePage() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "[]");

        new GitHubClient(BASE, http).issueComments(TOKEN, "owner", "repo", 1, null, 2, 30);

        assertThat(http.requests().getFirst().uri().getRawQuery())
                .as("only the page is sent without a time")
                .isEqualTo("page=2&per_page=30");
    }

    // Prevents reflected credentials from leaking in API error diagnostics.
    @Test
    void errorStatusIsPreservedAndCredentialsAreScrubbed() {
        var http =
                new ReplayHttpClient()
                        .answer(403, Map.of(), "{\"message\":\"refused " + TOKEN + "\"}");
        assertThatExceptionOfType(GitHubException.class)
                .as("errors preserve useful status without credentials")
                .isThrownBy(() -> new GitHubClient(BASE, http).user(TOKEN))
                .withMessage("refused [redacted]")
                .withNoCause()
                .satisfies(
                        ex ->
                                assertThat(ex.status())
                                        .as("permission failure retains HTTP status")
                                        .isEqualTo(403));
    }

    // Prevents credential-bearing unknown field names from escaping strict reader diagnostics.
    @Test
    void unknownFieldDiagnosticsScrubCredentials() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"" + TOKEN + "\":true,\"total_count\":0,\"workflow_runs\":[]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("a server may reflect credentials in JSON keys")
                .isThrownBy(
                        () ->
                                new GitHubClient(BASE, http, Strictness.STRICT)
                                        .runs(TOKEN, "owner", "repo", "abc", 1, 50))
                .withMessageContaining("[redacted]")
                .withMessageNotContaining(TOKEN)
                .withNoCause();
    }

    // Prevents GraphQL partial failures from being mistaken for successful reads or writes.
    @Test
    void graphQlErrorsNameTheFirstMessageAndScrubTheToken() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"data\":null,\"errors\":[{\"message\":\"refused "
                                        + TOKEN
                                        + "\"},{\"message\":\"second\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("GraphQL errors override HTTP success")
                .isThrownBy(
                        () ->
                                new GitHubGraphQl(BASE, http, Strictness.STRICT)
                                        .reviewThreads(TOKEN, "owner", "repo", 1, null))
                .withMessage("refused [redacted]")
                .withNoCause()
                .satisfies(
                        ex ->
                                assertThat(ex.status())
                                        .as("HTTP 200 still carries the GraphQL error")
                                        .isEqualTo(200));
    }

    // Prevents a comparison from failing when a commit's author or committer is no GitHub account,
    // which GitHub sends as null or as an empty object.
    @Test
    void aCommitWithoutAnAccountIsCompared() throws Exception {
        var comparison =
                (com.fasterxml.jackson.databind.node.ObjectNode)
                        extractExample("commit-comparison").deepCopy();
        var base = (com.fasterxml.jackson.databind.node.ObjectNode) comparison.path("base_commit");
        base.putNull("author");
        base.putObject("committer");
        var http = new ReplayHttpClient().answer(200, Map.of(), comparison.toString());

        var read =
                new GitHubClient(BASE, http, Strictness.STRICT)
                        .compare(TOKEN, "owner", "repo", "main", "abc");

        assertThat(read.baseCommit().author()).as("no account wrote the base").isNull();
        assertThat(read.baseCommit().committer()).as("an empty account reads as none").isNull();
        assertThat(read.mergeBaseCommit().author())
                .as("an account is read as one")
                .isInstanceOf(SimpleUser.class);
        assertThat(read.mergeBaseCommit().sha())
                .as("the merge base is read")
                .isEqualTo(comparison.path("merge_base_commit").path("sha").asText());
    }

    // Prevents a thread ID GitHub does not know, which it answers with a null node and a NOT_FOUND
    // error for it, from failing as a broken answer instead of reading as no thread.
    @Test
    void anUnknownThreadIdReadsNoThread() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                """
                                {"data": {"node": null}, "errors": [{"type": "NOT_FOUND",
                                 "path": ["node"], "message": "Could not resolve to a node"}]}
                                """);

        var thread = new GitHubGraphQl(BASE, http, Strictness.STRICT).reviewThread(TOKEN, "PRRT_x");

        assertThat(thread.data().node()).as("no thread has the id").isNull();
    }

    // Prevents the ID of something that is not a thread, which GitHub answers with an empty node,
    // from failing as a broken answer instead of reading as no thread.
    @Test
    void anIdOfAnotherKindReadsNoThread() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "{\"data\": {\"node\": {}}}");

        var thread = new GitHubGraphQl(BASE, http, Strictness.STRICT).reviewThread(TOKEN, "PRRC_x");

        assertThat(thread.data().node()).as("no thread has the id").isNull();
    }

    // Prevents a NOT_FOUND error from being dropped when GitHub did send a node beside it.
    @Test
    void aNotFoundBesideANodeStillFails() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                """
                                {"data": {"node": {}}, "errors": [{"type": "NOT_FOUND",
                                 "path": ["node"], "message": "Could not resolve"}]}
                                """);

        assertThatExceptionOfType(GitHubException.class)
                .as("an error beside a node is not an unknown id")
                .isThrownBy(
                        () ->
                                new GitHubGraphQl(BASE, http, Strictness.STRICT)
                                        .reviewThread(TOKEN, "PRRT_x"));
    }

    // Prevents any other GraphQL error on a thread read from passing as no thread.
    @Test
    void anotherErrorOnAThreadReadStillFails() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                """
                                {"data": {"node": null}, "errors": [{"type": "FORBIDDEN",
                                 "path": ["node"], "message": "Resource not accessible"}]}
                                """);

        assertThatExceptionOfType(GitHubException.class)
                .as("a refusal is not an unknown id")
                .isThrownBy(
                        () ->
                                new GitHubGraphQl(BASE, http, Strictness.STRICT)
                                        .reviewThread(TOKEN, "PRRT_x"))
                .withMessageContaining("Resource not accessible");
    }

    // Prevents an author that is neither a user nor a bot, such as a mannequin, from failing the
    // reading or from being given an identity GitHub did not send.
    @Test
    void anAuthorOfAnotherKindHasNoStableId() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                """
                                {"data": {"node": {"id": "PRRT_x", "isResolved": false,
                                 "isOutdated": false, "path": "a.txt", "line": 1, "diffSide": "RIGHT",
                                 "startDiffSide": null, "resolvedBy": null,
                                 "comments": {"nodes": [{"id": "PRRC_x", "databaseId": 7, "body": "b",
                                  "createdAt": "2026-10-31T10:00:00Z", "author": {"login": "someone"}}],
                                  "pageInfo": {"hasNextPage": false, "endCursor": null}}}}}
                                """);

        var thread = new GitHubGraphQl(BASE, http, Strictness.STRICT).reviewThread(TOKEN, "PRRT_x");

        var author = thread.data().node().comments().nodes().getFirst().author();
        assertThat(author.login()).as("the author is named").isEqualTo("someone");
        assertThat(author.databaseId()).as("no stable id is made up").isNull();
        assertThat(author.id()).as("no node id is made up").isNull();
    }

    // Prevents an empty error container from turning a successful GraphQL answer into a failure.
    @ParameterizedTest
    @ValueSource(strings = {"null", "[]"})
    void emptyOrNullGraphQlErrorsAreNotFailures(String errors) throws Exception {
        String data = GitHubRecordingTest.recording("reviewThreads").path("body").asText();
        String body = data.substring(0, data.length() - 1) + ",\"errors\":" + errors + "}";
        var http = new ReplayHttpClient().answer(200, Map.of(), body);
        var response =
                new GitHubGraphQl(BASE, http, Strictness.STRICT)
                        .reviewThreads(TOKEN, "owner", "repo", 1, null);
        assertThat(
                        response.data()
                                .repository()
                                .pullRequest()
                                .reviewThreads()
                                .nodes()
                                .getFirst()
                                .id())
                .as("errors %s still read the data", errors)
                .isEqualTo(
                        GitHubJson.mapper(Strictness.STRICT)
                                .readTree(data)
                                .at("/data/repository/pullRequest/reviewThreads/nodes/0/id")
                                .asText());
    }

    static com.fasterxml.jackson.databind.JsonNode extractExample(String name) throws Exception {
        var example =
                GitHubJson.mapper(Strictness.STRICT)
                        .readTree(java.nio.file.Path.of("src/main/openapi/github.json").toFile())
                        .path("components")
                        .path("examples")
                        .path(name)
                        .path("value");
        if (example.isMissingNode())
            throw new IllegalStateException("The extract has no example " + name);
        return example;
    }
}
