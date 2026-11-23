package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequestIssueFieldValuesInner;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

/**
 * An issue's calls, against GitHub's own example answers from the extract: the path, method and
 * body each sends, and that the answer is read.
 */
class GitHubIssuesTest {
    private static final URI BASE = URI.create("https://api.github.com/");
    private static final String TOKEN = "synthetic-access-token";
    private static final ObjectMapper JSON = new ObjectMapper();

    private static String example(String name) throws Exception {
        JsonNode extract = JSON.readTree(Path.of("src/main/openapi/github.json").toFile());
        return extract.path("components").path("examples").path(name).path("value").toString();
    }

    private static ReplayHttpClient answering(int status, String body) {
        return new ReplayHttpClient().answer(status, Map.of(), body);
    }

    private static GitHubClient client(ReplayHttpClient http) {
        return new GitHubClient(BASE, http, Strictness.STRICT);
    }

    private static HttpRequest sent(ReplayHttpClient http) {
        assertThat(http.requests()).as("one request sent").hasSize(1);
        return http.requests().get(0);
    }

    private static JsonNode body(HttpRequest request) throws Exception {
        return JSON.readTree(GitHubRecordingTest.requestBody(request));
    }

    // Prevents an issue that carries a priority from failing every read of it: GitHub gives a
    // select field's value as its option's id, a number, as measured.
    @Test
    void anIssuesSelectFieldValueIsRead() throws Exception {
        var issue =
                (com.fasterxml.jackson.databind.node.ObjectNode) JSON.readTree(example("issue"));
        issue.set(
                "issue_field_values",
                JSON.readTree(
                        "[{\"issue_field_id\":49223313,\"node_id\":\"IFSSV_kgDOASnK2g\","
                                + "\"data_type\":\"single_select\",\"issue_field_name\":\"Priority\","
                                + "\"value\":86177766,\"single_select_option\":{\"id\":86177766,"
                                + "\"name\":\"Low\",\"color\":\"green\"}}]"));
        var read = client(answering(200, issue.toString())).issue(TOKEN, "o", "r", 1347);
        var value = read.issueFieldValues().get(0);
        assertThat(value.issueFieldName()).as("the field's name").isEqualTo("Priority");
        assertThat(value.singleSelectOption().name()).as("the option's name").isEqualTo("Low");
        assertThat(value.value()).as("the value, the option's id").hasToString("86177766");
    }

    // Prevents a priority from never reaching GitHub: a select value is sent as the option's name
    // the caller gave, which GitHub accepts, as measured.
    @Test
    void anIssueFieldValueIsSentAsTheCallerGaveIt() throws Exception {
        var http = answering(200, example("issue"));
        var request =
                new IssuesUpdateRequest(
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                new IssuesUpdateRequestIssueFieldValuesInner(
                                        49223313L, "High", null, null, null)),
                        null);
        client(http).updateIssue(TOKEN, "o", "r", 1347, request);
        JsonNode sent = body(sent(http)).path("issue_field_values").path(0);
        assertThat(sent.path("field_id").asLong()).as("the field's id").isEqualTo(49223313L);
        assertThat(sent.path("value").asText()).as("the option's name").isEqualTo("High");
    }

    // Prevents a caller from acting on an issue it misread.
    @Test
    void anIssueIsReadByItsNumber() throws Exception {
        var http = answering(200, example("issue"));
        var issue = client(http).issue(TOKEN, "o", "r", 1347);
        assertThat(sent(http).uri().getPath())
                .as("the issue's path")
                .isEqualTo("/repos/o/r/issues/1347");
        assertThat(sent(http).method()).as("a read").isEqualTo("GET");
        assertThat(issue.number()).as("its number").isEqualTo(1347);
        assertThat(issue.labels()).as("its labels read as objects").isNotEmpty();
        assertThat(issue.labels().get(0).name()).as("a label's name").isEqualTo("bug");
    }

    // Prevents an issue from being created with other labels or text than the caller gave.
    @Test
    void anIssueIsCreatedWithItsLabels() throws Exception {
        var http = answering(201, example("issue"));
        var request =
                new IssuesCreateRequest(
                        "A title", "A body", null, List.of("story"), null, null, null, null);
        client(http).createIssue(TOKEN, "o", "r", request);
        assertThat(sent(http).method()).as("a create").isEqualTo("POST");
        assertThat(sent(http).uri().getPath())
                .as("the issues' path")
                .isEqualTo("/repos/o/r/issues");
        JsonNode sent = body(sent(http));
        assertThat(sent.path("title").asText()).as("the title sent").isEqualTo("A title");
        assertThat(sent.path("labels").toString())
                .as("labels sent as names")
                .isEqualTo("[\"story\"]");
    }

    // Prevents an issue from being closed with another reason than the caller gave.
    @Test
    void anIssueIsClosedAsNotPlanned() throws Exception {
        var http = answering(200, example("issue"));
        var request =
                new IssuesUpdateRequest(
                        null,
                        null,
                        IssuesUpdateRequest.StateEnum.CLOSED,
                        IssuesUpdateRequest.StateReasonEnum.NOT_PLANNED,
                        null,
                        null,
                        null,
                        null,
                        null,
                        null);
        client(http).updateIssue(TOKEN, "o", "r", 1347, request);
        assertThat(sent(http).method()).as("a change").isEqualTo("PATCH");
        JsonNode sent = body(sent(http));
        assertThat(sent.path("state").asText()).as("the state sent").isEqualTo("closed");
        assertThat(sent.path("state_reason").asText())
                .as("the reason sent")
                .isEqualTo("not_planned");
        assertThat(sent.has("title")).as("a field left null is not sent").isFalse();
    }

    // Prevents an update of an issue's text from removing its labels, assignees and field values.
    @Test
    void anUpdateOfTheBodyKeepsTheIssuesLabelsAndAssignees() throws Exception {
        var http = answering(200, example("issue"));
        var request =
                new IssuesUpdateRequest(
                        null, "A new body", null, null, null, null, null, null, null, null);
        client(http).updateIssue(TOKEN, "o", "r", 1347, request);
        JsonNode sent = body(sent(http));
        assertThat(sent.path("body").asText()).as("the body sent").isEqualTo("A new body");
        assertThat(sent.has("labels")).as("labels left null are not sent").isFalse();
        assertThat(sent.has("assignees")).as("assignees left null are not sent").isFalse();
        assertThat(sent.has("issue_field_values"))
                .as("field values left null are not sent")
                .isFalse();
    }

    // Prevents a caller from being unable to remove every label of an issue.
    @Test
    void anUpdateWithNoLabelsClearsThem() throws Exception {
        var http = answering(200, example("issue"));
        var request =
                new IssuesUpdateRequest(
                        null, null, null, null, null, null, List.of(), null, null, null);
        client(http).updateIssue(TOKEN, "o", "r", 1347, request);
        assertThat(body(sent(http)).path("labels").toString())
                .as("an empty list asked is sent, so GitHub clears the labels")
                .isEqualTo("[]");
    }

    // Prevents a sub-issue or dependency from being linked by number, which names another issue.
    @Test
    void subIssuesAndDependenciesAreNamedByTheirId() throws Exception {
        long id = 5_000_000_000L;
        var http = answering(201, example("issue"));
        client(http).addSubIssue(TOKEN, "o", "r", 1, id, true);
        assertThat(sent(http).uri().getPath()).isEqualTo("/repos/o/r/issues/1/sub_issues");
        assertThat(body(sent(http)).path("sub_issue_id").asLong())
                .as("the id past 32 bits")
                .isEqualTo(id);
        assertThat(body(sent(http)).path("replace_parent").asBoolean())
                .as("replace asked")
                .isTrue();

        http = answering(201, example("issue"));
        client(http).addBlockedBy(TOKEN, "o", "r", 1, id);
        assertThat(sent(http).uri().getPath())
                .isEqualTo("/repos/o/r/issues/1/dependencies/blocked_by");
        assertThat(body(sent(http)).path("issue_id").asLong()).as("the blocking id").isEqualTo(id);

        http = answering(200, example("issue"));
        client(http).removeBlockedBy(TOKEN, "o", "r", 1, id);
        assertThat(sent(http).method()).isEqualTo("DELETE");
        assertThat(sent(http).uri().getPath())
                .as("the blocking id in the path")
                .isEqualTo("/repos/o/r/issues/1/dependencies/blocked_by/" + id);
    }

    // Prevents a sub-issue removal from reaching GitHub without naming the sub-issue.
    @Test
    void removingASubIssueSendsItsIdInTheBody() throws Exception {
        var http = answering(200, example("issue"));
        client(http).removeSubIssue(TOKEN, "o", "r", 1, 42);
        assertThat(sent(http).method()).as("a delete").isEqualTo("DELETE");
        assertThat(sent(http).uri().getPath()).isEqualTo("/repos/o/r/issues/1/sub_issue");
        assertThat(body(sent(http)).path("sub_issue_id").asLong())
                .as("the id in the body")
                .isEqualTo(42);
    }

    // Prevents a sub-issue from moving to another place than the caller named.
    @Test
    void aSubIssueMovesAfterAnother() throws Exception {
        var http = answering(200, example("issue"));
        client(http).reprioritizeSubIssue(TOKEN, "o", "r", 1, 42, 41L, null);
        assertThat(sent(http).method()).isEqualTo("PATCH");
        assertThat(sent(http).uri().getPath()).isEqualTo("/repos/o/r/issues/1/sub_issues/priority");
        assertThat(body(sent(http)).path("after_id").asLong()).as("after").isEqualTo(41);
        assertThat(body(sent(http)).has("before_id")).as("no before").isFalse();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("both or neither of after and before")
                .isThrownBy(
                        () ->
                                client(answering(200, "{}"))
                                        .reprioritizeSubIssue(TOKEN, "o", "r", 1, 42, 41L, 40L));
    }

    // Prevents a page of linked issues from being read from another list than asked.
    @Test
    void linkedIssuesAreReadPageByPage() throws Exception {
        String page = "[" + example("issue") + "]";
        for (var call : List.of("sub_issues", "dependencies/blocked_by", "dependencies/blocking")) {
            var http =
                    new ReplayHttpClient()
                            .answer(
                                    200,
                                    Map.of(
                                            "link",
                                            List.of(
                                                    "<https://api.github.com/x?page=3>; rel=\"next\"")),
                                    page);
            var read =
                    switch (call) {
                        case "sub_issues" -> client(http).subIssues(TOKEN, "o", "r", 1, 2, 30);
                        case "dependencies/blocked_by" ->
                                client(http).blockedBy(TOKEN, "o", "r", 1, 2, 30);
                        default -> client(http).blocking(TOKEN, "o", "r", 1, 2, 30);
                    };
            assertThat(sent(http).uri().toString())
                    .as("%s is asked by page", call)
                    .isEqualTo(
                            "https://api.github.com/repos/o/r/issues/1/"
                                    + call
                                    + "?page=2&per_page=30");
            assertThat(read.items()).as("%s read", call).hasSize(1);
            assertThat(read.more()).as("%s knows a page follows", call).isTrue();
        }
        var http = answering(200, example("issue"));
        client(http).parentIssue(TOKEN, "o", "r", 1);
        assertThat(sent(http).uri().getPath())
                .as("the parent")
                .isEqualTo("/repos/o/r/issues/1/parent");
    }

    // Prevents a poll from reading another list than the issues changed since its last look.
    @Test
    void aRepositorysIssuesAreListedNewestChangeFirst() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), JSON.readTree(example("issue-items")).toString());
        var page =
                client(http)
                        .issues(
                                TOKEN,
                                "o",
                                "r",
                                "all",
                                List.of("story"),
                                java.time.Instant.parse("2026-01-01T00:00:00Z"),
                                1,
                                100);
        assertThat(sent(http).uri().getRawQuery())
                .as("state, labels, since, newest change first, paged")
                .isEqualTo(
                        "state=all&labels=story&since=2026-01-01T00%3A00%3A00Z&sort=updated&direction=desc&page=1&per_page=100");
        assertThat(page.items()).as("GitHub's example list read").isNotEmpty();
        assertThat(page.more()).as("no next link, no more").isFalse();
    }

    // Prevents a number or id that cannot exist from being sent as part of a path.
    @Test
    void anIdThatIsNotPositiveIsRefusedBeforeSending() {
        var http = answering(200, "{}");
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> client(http).addSubIssue(TOKEN, "o", "r", 1, 0, false));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .isThrownBy(() -> client(http).issue(TOKEN, "o", "r", -1));
        assertThat(http.requests()).as("nothing sent").isEmpty();
    }
}
