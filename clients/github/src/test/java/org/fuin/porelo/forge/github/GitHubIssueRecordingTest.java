package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GitHubIssueRecordingTest {
    private static final ObjectMapper JSON = GitHubJson.mapper(Strictness.STRICT);

    // Prevents API drift, a wrong target or a lost request field in the issue and project calls
    // from passing offline replay.
    @ParameterizedTest(name = "{0} reads its GitHub recording")
    @EnumSource(GitHubIssueOperations.class)
    void everyIssueOperationReadsItsRecordingStrictly(GitHubIssueOperations operation)
            throws Exception {
        JsonNode recording = GitHubRecordingTest.recording(operation.name());
        var context =
                JSON.treeToValue(recording.path("context"), GitHubIssueOperations.Context.class);
        var http = new ReplayHttpClient().recording(operation.name());
        Object result =
                operation.invoke(
                        new GitHubClient(
                                GitHubRecordingTest.BASE,
                                GitHubRecordingTest.BASE,
                                http,
                                Strictness.STRICT),
                        new GitHubGraphQl(GitHubRecordingTest.BASE, http, Strictness.STRICT),
                        GitHubRecordingTest.TOKEN,
                        context,
                        recording.path("request_body"));
        GitHubIssueScenario.requireUsefulResult(operation, result);
        assertThat(http.requests()).as("%s makes one request", operation).hasSize(1);
        var request = http.requests().getFirst();
        assertThat(request.method())
                .as("%s uses its documented verb", operation)
                .isEqualTo(operation.method);
        assertThat(request.uri().getRawPath())
                .as("%s addresses its fixture", operation)
                .isEqualTo(operation.path(context));
        assertThat(request.uri())
                .as("%s sends the captured target with its query", operation)
                .isEqualTo(
                        GitHubRecordingTest.BASE.resolve(recording.path("request_uri").asText()));
        assertThat(request.headers().firstValue("Authorization"))
                .as("%s authenticates", operation)
                .contains("Bearer " + GitHubRecordingTest.TOKEN);
        boolean sendsBody =
                request.bodyPublisher().map(body -> body.contentLength()).orElse(0L) != 0;
        if (!sendsBody) {
            assertThat(operation.method)
                    .as("only a read or a delete by path sends no body")
                    .isIn("GET", "DELETE");
            return;
        }
        JsonNode sent = JSON.readTree(GitHubRecordingTest.requestBody(request));
        if (operation.path(context).equals("/graphql")) {
            assertThat(sent)
                    .as("%s sends the recorded query and variables", operation)
                    .isEqualTo(recording.path("request_body"));
        } else {
            for (var field : recording.path("request_body").properties())
                assertThat(sent.path(field.getKey()))
                        .as("%s retains request field %s", operation, field.getKey())
                        .isEqualTo(field.getValue());
        }
    }

    // Prevents an issue recording from overwriting a pull request recording of the same name.
    @Test
    void noIssueRecordingSharesANameWithAnotherRecording() {
        var names = Arrays.stream(GitHubOperations.values()).map(Enum::name).toList();
        assertThat(Arrays.stream(GitHubIssueOperations.values()).map(Enum::name))
                .as("the two recorders write distinct files")
                .doesNotContainAnyElementsOf(names);
    }

    // Prevents a committed issue recording from carrying a credential.
    @Test
    void everyIssueRecordingIsFreeOfCredentialShapes() throws Exception {
        for (var operation : GitHubIssueOperations.values()) {
            var recording = GitHubRecordingTest.recording(operation.name()).toString();
            assertThat(GitHubRecorder.CREDENTIAL.matcher(recording).find())
                    .as("%s must contain no credential shape", operation)
                    .isFalse();
        }
    }
}
