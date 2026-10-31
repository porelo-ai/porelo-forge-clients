package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Map;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

class GitHubLiveCheckTest {
    // Prevents the scheduled live check from passing while the comments query goes unchecked, as it
    // would whenever no thread has a second page of comments.
    @Test
    void readingThreadsAlsoReadsTheFirstThreadsCommentsThroughTheCommentsQuery() throws Exception {
        var http = new ReplayHttpClient().recording("reviewThreads").recording("threadComments");

        String resolved =
                GitHubLiveCheck.readThreads(
                        new GitHubGraphQl(GitHubRecordingTest.BASE, http, Strictness.STRICT),
                        GitHubRecordingTest.TOKEN,
                        1);

        // The recording reads the threads before one is resolved.
        assertThat(resolved).as("the recorded thread is not resolved").isNull();
        assertThat(http.requests()).as("threads and then comments are queried").hasSize(2);
        assertThat(
                        GitHubJson.mapper(Strictness.STRICT)
                                .readTree(
                                        GitHubRecordingTest.requestBody(http.requests().getLast()))
                                .path("query")
                                .asText())
                .as("the second query is the comments query")
                .isEqualTo(GitHubGraphQl.THREAD_COMMENTS);
    }

    // Prevents a failed check from printing a job log, which can carry what the job printed, into
    // the CI log.
    @Test
    void aFailedResultCheckDoesNotPrintTheResult() {
        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(
                        () ->
                                GitHubScenario.requireUsefulResult(
                                        GitHubOperations.jobLogs, "printed-by-the-job"))
                .withMessageNotContaining("printed-by-the-job");
    }

    // Prevents the live check from printing review comments into the CI log when the comments
    // query disagrees with the threads query.
    @Test
    void disagreeingCommentsAreReportedWithoutTheirText() throws Exception {
        var json = GitHubJson.mapper(Strictness.STRICT);
        var comments =
                json.readTree(
                        GitHubRecordingTest.recording("threadComments").path("body").asText());
        ((ObjectNode) comments.at("/data/node/comments/nodes/0"))
                .put("body", "written-by-a-reviewer");
        // Both sides carry a text of their own, so neither may reach the message.
        var threads =
                json.readTree(GitHubRecordingTest.recording("reviewThreads").path("body").asText());
        ((ObjectNode)
                        threads.at(
                                "/data/repository/pullRequest/reviewThreads/nodes/0/comments/nodes/0"))
                .put("body", "written-by-another-reviewer");
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), threads.toString())
                        .answer(200, Map.of(), comments.toString());

        assertThatExceptionOfType(AssertionError.class)
                .isThrownBy(
                        () ->
                                GitHubLiveCheck.readThreads(
                                        new GitHubGraphQl(
                                                GitHubRecordingTest.BASE, http, Strictness.STRICT),
                                        GitHubRecordingTest.TOKEN,
                                        1))
                .withMessageNotContaining("written-by-a-reviewer")
                .withMessageNotContaining("written-by-another-reviewer");
    }

    // Prevents the live check from missing a resolved thread on the retained pull.
    @Test
    void aResolvedThreadIsSeen() throws Exception {
        var json = GitHubJson.mapper(Strictness.STRICT);
        var threads =
                json.readTree(GitHubRecordingTest.recording("reviewThreads").path("body").asText());
        ((ObjectNode) threads.at("/data/repository/pullRequest/reviewThreads/nodes/0"))
                .put("isResolved", true);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), threads.toString())
                        .recording("threadComments");

        String resolved =
                GitHubLiveCheck.readThreads(
                        new GitHubGraphQl(GitHubRecordingTest.BASE, http, Strictness.STRICT),
                        GitHubRecordingTest.TOKEN,
                        1);

        assertThat(resolved)
                .as("a resolved thread is seen by its node id")
                .isEqualTo(
                        threads.at("/data/repository/pullRequest/reviewThreads/nodes/0/id")
                                .asText());
    }
}
