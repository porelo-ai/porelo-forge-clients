package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

class GitHubMergeCommitTest {
    private static final URI BASE = URI.create("https://api.github.com/");
    private static final String TOKEN = "synthetic-access-token";
    private static final String OID = "0123456789abcdef0123456789abcdef01234567";

    private static ReplayHttpClient answering(String pull) {
        return new ReplayHttpClient()
                .answer(
                        200,
                        Map.of(),
                        "{\"data\":{\"repository\":{\"pullRequest\":" + pull + "}}}");
    }

    private static GitHubGraphQl graphQl(ReplayHttpClient http) {
        return new GitHubGraphQl(BASE, http, Strictness.STRICT);
    }

    // Prevents the pull request's identity from becoming part of the GraphQL document.
    @Test
    void theMergedPullRequestsCommitIsReadThroughVariables() throws Exception {
        var http = answering("{\"merged\":true,\"mergeCommit\":{\"oid\":\"" + OID + "\"}}");

        assertThat(graphQl(http).mergeCommit(TOKEN, "o", "r", 7))
                .as("a merged pull request names the commit its merge made")
                .isEqualTo(OID);
        var sent =
                new ObjectMapper()
                        .readTree(GitHubRecordingTest.requestBody(http.requests().getFirst()));
        assertThat(sent.path("query").asText())
                .as("the fixed query is sent")
                .isEqualTo(GitHubGraphQl.MERGE_COMMIT);
        assertThat(sent.path("variables").toString())
                .as("owner, repository and number travel as variables")
                .isEqualTo("{\"owner\":\"o\",\"repo\":\"r\",\"number\":7}");
    }

    // Prevents a repository on SHA-256 object names from failing a merged pull request.
    @Test
    void aSha256MergeCommitIsRead() {
        String oid = "ab".repeat(32);
        var http = answering("{\"merged\":true,\"mergeCommit\":{\"oid\":\"" + oid + "\"}}");

        assertThat(graphQl(http).mergeCommit(TOKEN, "o", "r", 7))
                .as("a 64-digit commit name is a commit")
                .isEqualTo(oid);
    }

    // Prevents an open pull request from being taken for one merged into nothing.
    @Test
    void anOpenPullRequestHasNoMergeCommit() {
        var http = answering("{\"merged\":false,\"mergeCommit\":null}");

        assertThat(graphQl(http).mergeCommit(TOKEN, "o", "r", 7))
                .as("an unmerged pull request has no merge commit")
                .isNull();
    }

    // Prevents an answer GitHub never gives from passing as a commit or as none.
    @Test
    void anAnswerThatContradictsItselfIsRefused() {
        for (String pull :
                new String[] {
                    "{\"merged\":true,\"mergeCommit\":null}",
                    "{\"merged\":true,\"mergeCommit\":{\"oid\":\"not-a-commit\"}}",
                    "{\"merged\":false,\"mergeCommit\":{\"oid\":\"" + OID + "\"}}",
                    "{\"mergeCommit\":{\"oid\":\"" + OID + "\"}}",
                    "{\"merged\":\"true\",\"mergeCommit\":{\"oid\":\"" + OID + "\"}}",
                    "{\"merged\":true}",
                    "{\"merged\":true,\"mergeCommit\":{\"oid\":\"\"}}",
                    "null"
                }) {
            assertThatExceptionOfType(GitHubException.class)
                    .as("%s is unreadable", pull)
                    .isThrownBy(() -> graphQl(answering(pull)).mergeCommit(TOKEN, "o", "r", 7))
                    .withMessageContaining("could not be read");
        }
    }

    // Prevents an answer without its repository from reading as an unmerged pull request.
    @Test
    void anAnswerWithoutItsRepositoryIsRefused() {
        var http = new ReplayHttpClient().answer(200, Map.of(), "{\"data\":{\"repository\":null}}");

        assertThatExceptionOfType(GitHubException.class)
                .as("no repository is no answer")
                .isThrownBy(() -> graphQl(http).mergeCommit(TOKEN, "o", "r", 7))
                .withMessageContaining("could not be read");
    }

    // Prevents a missing pull request from reading as an unmerged one.
    @Test
    void aMissingPullRequestFailsAsGitHubAnswers() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of(),
                                "{\"data\":{\"repository\":{\"pullRequest\":null}},\"errors\":[{\"type\":\"NOT_FOUND\",\"message\":\"Could not resolve to a PullRequest with the number of 7.\"}]}");

        assertThatExceptionOfType(GitHubException.class)
                .as("GitHub's error is the failure")
                .isThrownBy(() -> graphQl(http).mergeCommit(TOKEN, "o", "r", 7))
                .withMessageContaining("Could not resolve");
    }

    // Prevents a call that could not name a pull request from reaching GitHub.
    @Test
    void anUnnamedPullRequestIsRefusedBeforeAnyCall() {
        var http = new ReplayHttpClient();

        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a number below one names no pull request")
                .isThrownBy(() -> graphQl(http).mergeCommit(TOKEN, "o", "r", 0));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a blank owner names no repository")
                .isThrownBy(() -> graphQl(http).mergeCommit(TOKEN, " ", "r", 7));
        assertThat(http.requests()).as("nothing was sent").isEmpty();
    }
}
