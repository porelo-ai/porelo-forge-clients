package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.jspecify.annotations.Nullable;

/**
 * The kept issue and project, which stay in the test organization between runs: {@code keep} makes
 * them unless they are there, {@code check} reads them strictly with the organization member's
 * token. The kept issue has one sub-issue, which also blocks it, and both are on the kept project
 * with a value in each of its fields.
 */
public final class GitHubIssueLiveCheck {
    static final String ISSUE = "forge-client-kept-issue";
    static final String SUB_ISSUE = "forge-client-kept-sub-issue";
    static final String PROJECT = "forge-client-kept-board";

    private final String token;
    private final GitHubClient client;
    private final GitHubGraphQl graphql;
    private final ObjectMapper json = GitHubJson.mapper(Strictness.STRICT);

    private GitHubIssueLiveCheck(HttpClient http, String token) {
        this.token = token;
        client = new GitHubClient(GitHubFixture.BASE, GitHubFixture.WEB, http, Strictness.STRICT);
        graphql = new GitHubGraphQl(GitHubFixture.BASE, http, Strictness.STRICT);
    }

    /** Runs {@code keep} or {@code check}; the token comes only from the environment. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !List.of("keep", "check").contains(args[0]))
            throw new IllegalArgumentException("Expected keep or check");
        String token = GitHubFixture.required(System.getenv(), "LIVE_GITHUB_REVIEWER_TOKEN");
        try (var http =
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()) {
            var live = new GitHubIssueLiveCheck(http, token);
            if (args[0].equals("keep")) live.keep();
            else live.check();
        }
    }

    private void keep() throws Exception {
        Issue issue = find(ISSUE);
        Issue sub = find(SUB_ISSUE);
        if (issue == null) issue = create(ISSUE);
        if (sub == null) sub = create(SUB_ISSUE);
        long number = issue.number();
        long subId = sub.id();
        if (client.subIssues(token, GitHubFixture.OWNER, GitHubFixture.REPO, number, 1, 100)
                .items()
                .isEmpty())
            client.addSubIssue(
                    token, GitHubFixture.OWNER, GitHubFixture.REPO, number, subId, false);
        if (client.blockedBy(token, GitHubFixture.OWNER, GitHubFixture.REPO, number, 1, 100)
                .items()
                .isEmpty())
            client.addBlockedBy(token, GitHubFixture.OWNER, GitHubFixture.REPO, number, subId);
        var project = project();
        if (project == null) project = makeProject();
        int projectNumber = project.path("number").asInt();
        String projectId = project.path("id").asText();
        var fields = graphql.projectFields(token, GitHubFixture.OWNER, projectNumber, null);
        var board = graphql.issueBoard(token, List.of(issue.nodeId(), sub.nodeId()), projectId);
        if (board.issues().stream().anyMatch(on -> on == null || on.itemId() == null)) {
            var items =
                    graphql.addProjectItems(
                            token, projectId, List.of(issue.nodeId(), sub.nodeId()));
            if (!items.complete())
                throw new IllegalStateException("The kept issues were not added");
            List<String> ids = items.outcomes().stream().map(o -> (String) o.value()).toList();
            String kind = field(fields, "Kind");
            String status = field(fields, "Status");
            var values =
                    graphql.setFieldValues(
                            token,
                            projectId,
                            List.of(
                                    FieldValue.option(ids.get(0), kind, option(fields, "Kind")),
                                    FieldValue.option(ids.get(0), status, option(fields, "Status")),
                                    FieldValue.text(ids.get(0), field(fields, "Key"), "KEPT-1"),
                                    FieldValue.number(ids.get(1), field(fields, "Rank"), 1)));
            if (!values.complete()) throw new IllegalStateException("The kept values were not set");
        }
        System.out.println("kept issue " + number + " and project " + projectNumber);
    }

    private void check() throws Exception {
        Issue issue = required(find(ISSUE), "the kept issue");
        Issue sub = required(find(SUB_ISSUE), "the kept sub-issue");
        long number = issue.number();
        read("issue", () -> client.issue(token, GitHubFixture.OWNER, GitHubFixture.REPO, number));
        var subs =
                read(
                        "subIssues",
                        () ->
                                client.subIssues(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        number,
                                        1,
                                        100));
        assertThat(subs.items())
                .extracting(Issue::id)
                .as("the kept sub-issue is listed")
                .contains(sub.id());
        var parent =
                read(
                        "parentIssue",
                        () ->
                                client.parentIssue(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        sub.number()));
        assertThat(parent.id()).as("the sub-issue names its parent").isEqualTo(issue.id());
        var blockers =
                read(
                        "blockedBy",
                        () ->
                                client.blockedBy(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        number,
                                        1,
                                        100));
        assertThat(blockers.items())
                .extracting(Issue::id)
                .as("the sub-issue blocks it")
                .contains(sub.id());
        var blocked =
                read(
                        "blocking",
                        () ->
                                client.blocking(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        sub.number(),
                                        1,
                                        100));
        assertThat(blocked.items())
                .extracting(Issue::id)
                .as("it is blocked by the sub-issue")
                .contains(issue.id());
        var first =
                read(
                        "issuesIfChanged",
                        () ->
                                client.issuesIfChanged(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        "open",
                                        null,
                                        null,
                                        1,
                                        100,
                                        null));
        assertThat(first.changed()).as("a first read answers").isTrue();
        var again =
                read(
                        "issuesNotModified",
                        () ->
                                client.issuesIfChanged(
                                        token,
                                        GitHubFixture.OWNER,
                                        GitHubFixture.REPO,
                                        "open",
                                        null,
                                        null,
                                        1,
                                        100,
                                        first.etag()));
        assertThat(again.etag()).as("a read again carries a tag").isNotBlank();

        var project = required(project(), "the kept project");
        int projectNumber = project.path("number").asInt();
        String projectId = project.path("id").asText();
        var fields =
                read(
                        "projectFields",
                        () ->
                                graphql.projectFields(
                                        token, GitHubFixture.OWNER, projectNumber, null));
        for (String name : List.of("Kind", "Key", "Rank", "Status")) field(fields, name);
        var items =
                read(
                        "projectItems",
                        () ->
                                graphql.projectItems(
                                        token, GitHubFixture.OWNER, projectNumber, null));
        assertThat(items.items())
                .as("both kept issues are on the board")
                .hasSizeGreaterThanOrEqualTo(2);
        var board =
                read(
                        "issueBoard",
                        () ->
                                graphql.issueBoard(
                                        token, List.of(issue.nodeId(), sub.nodeId()), projectId));
        var kept = required(board.issues().getFirst(), "the kept issue on the board");
        assertThat(kept.values())
                .as("the kept issue has its values")
                .hasSizeGreaterThanOrEqualTo(3);
        read("statusChanges", () -> graphql.statusChanges(token, issue.nodeId(), null));
        var rest =
                read(
                        "projectItemsIfChanged",
                        () ->
                                client.projectItemsIfChanged(
                                        token, GitHubFixture.OWNER, projectNumber, null, 50, null));
        assertThat(rest.value()).as("the board is read over REST").isNotNull();
        read(
                "projectItemsNotModified",
                () ->
                        client.projectItemsIfChanged(
                                token, GitHubFixture.OWNER, projectNumber, null, 50, rest.etag()));
        System.out.println("live check of the kept issue and project passed");
    }

    private @Nullable Issue find(String title) {
        for (int page = 1; page <= 100; page++) {
            var issues =
                    client.issues(
                            token,
                            GitHubFixture.OWNER,
                            GitHubFixture.REPO,
                            "open",
                            null,
                            null,
                            page,
                            100);
            for (var issue : issues.items()) if (issue.title().equals(title)) return issue;
            if (!issues.more()) return null;
        }
        throw new IllegalStateException("Issue discovery exceeded its pagination bound");
    }

    private Issue create(String title) throws Exception {
        return client.createIssue(
                token,
                GitHubFixture.OWNER,
                GitHubFixture.REPO,
                json.treeToValue(
                        json.valueToTree(Map.of("title", title, "body", "Kept for the live check")),
                        IssuesCreateRequest.class));
    }

    private @Nullable JsonNode project() {
        JsonNode nodes =
                data(
                                "query($o: String!, $q: String!) { organization(login: $o) { projectsV2(first: 20, query: $q) { nodes { id number title closed } } } }",
                                Map.of("o", GitHubFixture.OWNER, "q", PROJECT))
                        .path("organization")
                        .path("projectsV2")
                        .path("nodes");
        for (JsonNode node : nodes)
            if (node.path("title").asText().equals(PROJECT) && !node.path("closed").asBoolean())
                return node;
        return null;
    }

    private JsonNode makeProject() {
        String org =
                data(
                                "query($o: String!) { organization(login: $o) { id } }",
                                Map.of("o", GitHubFixture.OWNER))
                        .path("organization")
                        .path("id")
                        .asText();
        JsonNode project =
                data(
                                "mutation($i: CreateProjectV2Input!) { createProjectV2(input: $i) { projectV2 { id number } } }",
                                Map.of("i", Map.of("ownerId", org, "title", PROJECT)))
                        .path("createProjectV2")
                        .path("projectV2");
        String id = project.path("id").asText();
        String field =
                "mutation($i: CreateProjectV2FieldInput!) { createProjectV2Field(input: $i) { projectV2Field { ... on ProjectV2FieldCommon { id } } } }";
        data(
                field,
                Map.of(
                        "i",
                        Map.of(
                                "projectId",
                                id,
                                "dataType",
                                "SINGLE_SELECT",
                                "name",
                                "Kind",
                                "singleSelectOptions",
                                List.of(
                                        Map.of(
                                                "name",
                                                "Story",
                                                "color",
                                                "BLUE",
                                                "description",
                                                "")))));
        data(field, Map.of("i", Map.of("projectId", id, "dataType", "TEXT", "name", "Key")));
        data(field, Map.of("i", Map.of("projectId", id, "dataType", "NUMBER", "name", "Rank")));
        return project;
    }

    private JsonNode data(String query, Map<String, Object> variables) {
        var answer = client.graphQlAnswer(token, Map.of("query", query, "variables", variables));
        answer.failOnErrors();
        return answer.envelope().path("data");
    }

    private static String field(GitHubGraphQl.ProjectFields fields, String name) {
        return fields.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow(
                        () -> new IllegalStateException("The kept project lacks the field " + name))
                .id();
    }

    private static String option(GitHubGraphQl.ProjectFields fields, String name) {
        return fields.fields().stream()
                .filter(field -> field.name().equals(name))
                .findFirst()
                .orElseThrow()
                .options()
                .getFirst()
                .id();
    }

    private static <T> T required(@Nullable T value, String what) {
        if (value == null) throw new IllegalStateException("Missing " + what);
        return value;
    }

    private static <T> T read(String operation, Callable<T> call) throws Exception {
        try {
            return call.call();
        } catch (GitHubException ex) {
            throw new GitHubException(ex.status(), operation + ": " + ex.getMessage());
        }
    }
}
