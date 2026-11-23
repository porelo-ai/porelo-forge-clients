package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewOption;
import org.fuin.porelo.forge.github.model.ChecksCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateLabelRequest;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;

/**
 * The issue and project calls recorded with the organization member's token alone, in the order the
 * recorder makes them; live capture and strict offline replay share it.
 */
enum GitHubIssueOperations {
    createIssue("POST", "/repos/{owner}/{repo}/issues"),
    issue("GET", "/repos/{owner}/{repo}/issues/{number}"),
    updateIssue("PATCH", "/repos/{owner}/{repo}/issues/{number}"),
    issues("GET", "/repos/{owner}/{repo}/issues"),
    issuesIfChanged("GET", "/repos/{owner}/{repo}/issues"),
    issuesNotModified("GET", "/repos/{owner}/{repo}/issues"),
    addSubIssue("POST", "/repos/{owner}/{repo}/issues/{number}/sub_issues"),
    // A sub-issue in another repository of the same owner, so the first can be moved after it.
    addCrossSubIssue("POST", "/repos/{owner}/{repo}/issues/{number}/sub_issues"),
    subIssues("GET", "/repos/{owner}/{repo}/issues/{number}/sub_issues"),
    parentIssue("GET", "/repos/{owner}/{repo}/issues/{other}/parent"),
    parentIssueNone("GET", "/repos/{owner}/{repo}/issues/{number}/parent"),
    reprioritizeSubIssue("PATCH", "/repos/{owner}/{repo}/issues/{number}/sub_issues/priority"),
    removeSubIssue("DELETE", "/repos/{owner}/{repo}/issues/{number}/sub_issue"),
    addBlockedBy("POST", "/repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by"),
    addCrossBlockedBy("POST", "/repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by"),
    blockedBy("GET", "/repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by"),
    blocking("GET", "/repos/{owner}/{repo}/issues/{other}/dependencies/blocking"),
    removeBlockedBy(
            "DELETE", "/repos/{owner}/{repo}/issues/{number}/dependencies/blocked_by/{subIssueId}"),
    refusedPermissions("POST", "/repos/{owner}/{repo}/check-runs"),
    projectFields("POST", "/graphql"),
    setFieldOptions("POST", "/graphql"),
    addProjectItems("POST", "/graphql"),
    setFieldValues("POST", "/graphql"),
    setFieldValuesPartly("POST", "/graphql"),
    projectItems("POST", "/graphql"),
    projectItemsIfChanged("GET", "/orgs/{owner}/projectsV2/{projectNumber}/items"),
    projectItemsNotModified("GET", "/orgs/{owner}/projectsV2/{projectNumber}/items"),
    issueBoard("POST", "/graphql"),
    statusChanges("POST", "/graphql"),
    issueLabels("POST", "/graphql"),
    issueSubIssues("POST", "/graphql"),
    issueBlockedBy("POST", "/graphql"),
    itemValues("POST", "/graphql"),
    createIssues("POST", "/graphql"),
    createLabel("POST", "/repos/{owner}/{repo}/labels"),
    labels("GET", "/repos/{owner}/{repo}/labels"),
    deleteLabel("DELETE", "/repos/{owner}/{repo}/labels/{label}"),
    issueFields("GET", "/orgs/{owner}/issue-fields");

    /** The options the recorder sets on its single-select field, in this order. */
    static final List<NewOption> OPTIONS =
            List.of(
                    new NewOption("Story", "BLUE", "A story"),
                    new NewOption("Bug", "RED", "A bug"),
                    new NewOption("Task", "GRAY", ""));

    /** An item id GitHub does not know, for the part of a batch it refuses. */
    static final String UNKNOWN_ITEM = "PVTI_lADOAAAAAM4AAAAAzgAAAAA";

    final String method;
    private final String template;
    private static final ObjectMapper JSON = GitHubJson.mapper(Strictness.STRICT);

    GitHubIssueOperations(String method, String template) {
        this.method = method;
        this.template = template;
    }

    /** Calls the client; a refusal the recording holds on purpose is returned, not thrown. */
    Object invoke(
            GitHubClient client, GitHubGraphQl graphql, String token, Context c, JsonNode body)
            throws Exception {
        try {
            return call(client, graphql, token, c, body);
        } catch (GitHubException refused) {
            if (this == parentIssueNone || this == refusedPermissions) return refused;
            throw refused;
        }
    }

    private Object call(
            GitHubClient client, GitHubGraphQl graphql, String token, Context c, JsonNode body)
            throws Exception {
        Instant since = Instant.parse(c.since());
        return switch (this) {
            case createIssue ->
                    client.createIssue(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, IssuesCreateRequest.class));
            case issue -> client.issue(token, c.owner(), c.repo(), c.number());
            case updateIssue ->
                    client.updateIssue(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            JSON.treeToValue(body, IssuesUpdateRequest.class));
            case issues -> client.issues(token, c.owner(), c.repo(), "open", null, since, 1, 50);
            case issuesIfChanged ->
                    client.issuesIfChanged(
                            token, c.owner(), c.repo(), "open", null, since, 1, 50, null);
            case issuesNotModified ->
                    client.issuesIfChanged(
                            token, c.owner(), c.repo(), "open", null, since, 1, 50, c.etag());
            case addSubIssue ->
                    client.addSubIssue(
                            token, c.owner(), c.repo(), c.number(), c.subIssueId(), false);
            case addCrossSubIssue ->
                    client.addSubIssue(
                            token, c.owner(), c.repo(), c.number(), c.thirdIssueId(), false);
            case subIssues -> client.subIssues(token, c.owner(), c.repo(), c.number(), 1, 50);
            case parentIssue -> client.parentIssue(token, c.owner(), c.repo(), c.other());
            case parentIssueNone -> client.parentIssue(token, c.owner(), c.repo(), c.number());
            case reprioritizeSubIssue ->
                    client.reprioritizeSubIssue(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            c.subIssueId(),
                            c.thirdIssueId(),
                            null);
            case removeSubIssue ->
                    client.removeSubIssue(token, c.owner(), c.repo(), c.number(), c.thirdIssueId());
            case addBlockedBy ->
                    client.addBlockedBy(token, c.owner(), c.repo(), c.number(), c.subIssueId());
            case addCrossBlockedBy ->
                    client.addBlockedBy(token, c.owner(), c.repo(), c.number(), c.thirdIssueId());
            case blockedBy -> client.blockedBy(token, c.owner(), c.repo(), c.number(), 1, 50);
            case blocking -> client.blocking(token, c.owner(), c.repo(), c.other(), 1, 50);
            case removeBlockedBy ->
                    client.removeBlockedBy(token, c.owner(), c.repo(), c.number(), c.subIssueId());
            case refusedPermissions ->
                    client.createCheckRun(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, ChecksCreateRequest.class));
            case projectFields -> graphql.projectFields(token, c.owner(), c.projectNumber(), null);
            case setFieldOptions -> graphql.setFieldOptions(token, c.fieldId(), OPTIONS);
            case addProjectItems -> graphql.addProjectItems(token, c.projectId(), c.issueIds());
            case setFieldValues -> graphql.setFieldValues(token, c.projectId(), values(c));
            case setFieldValuesPartly ->
                    graphql.setFieldValues(
                            token,
                            c.projectId(),
                            List.of(
                                    FieldValue.text(c.itemIds().get(0), c.textFieldId(), "partly"),
                                    FieldValue.text(UNKNOWN_ITEM, c.textFieldId(), "refused"),
                                    FieldValue.number(c.itemIds().get(1), c.numberFieldId(), 7)));
            case projectItems -> graphql.projectItems(token, c.owner(), c.projectNumber(), null);
            case projectItemsIfChanged ->
                    client.projectItemsIfChanged(
                            token,
                            c.owner(),
                            c.projectNumber(),
                            null,
                            50,
                            c.fieldDatabaseIds(),
                            null);
            case projectItemsNotModified ->
                    client.projectItemsIfChanged(
                            token,
                            c.owner(),
                            c.projectNumber(),
                            null,
                            50,
                            c.fieldDatabaseIds(),
                            c.itemsEtag());
            case issueBoard ->
                    graphql.issueBoard(
                            token,
                            List.of(c.issueIds().get(0), c.issueIds().get(1), c.unknownIssueId()),
                            c.projectId());
            case statusChanges -> graphql.statusChanges(token, c.issueIds().get(0), null);
            case issueLabels -> graphql.issueLabels(token, c.issueIds().get(0), null);
            case issueSubIssues -> graphql.issueSubIssues(token, c.issueIds().get(0), null);
            case issueBlockedBy -> graphql.issueBlockedBy(token, c.issueIds().get(0), null);
            case itemValues -> graphql.itemValues(token, c.itemIds().get(0), null);
            case createIssues ->
                    graphql.createIssues(
                            token,
                            List.of(
                                    new NewIssue(
                                            c.repositoryId(),
                                            c.prefix() + "-batch-a",
                                            "First of a batch",
                                            null,
                                            List.of()),
                                    new NewIssue(
                                            c.repositoryId(),
                                            c.prefix() + "-batch-b",
                                            null,
                                            c.issueIds().get(0),
                                            List.of())));
            case createLabel ->
                    client.createLabel(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, IssuesCreateLabelRequest.class));
            case labels -> client.labels(token, c.owner(), c.repo(), 1, 100);
            case issueFields -> client.issueFields(token, c.owner());
            case deleteLabel -> {
                client.deleteLabel(token, c.owner(), c.repo(), c.label());
                yield c.label();
            }
        };
    }

    /**
     * One value of each kind, and a clear: an option and a text on the first item, a number on the
     * second.
     */
    static List<FieldValue> values(Context c) {
        return List.of(
                FieldValue.option(c.itemIds().get(0), c.statusFieldId(), c.statusOptionId()),
                FieldValue.text(c.itemIds().get(0), c.textFieldId(), "OLD-1"),
                FieldValue.number(c.itemIds().get(1), c.numberFieldId(), 3),
                FieldValue.clear(c.itemIds().get(1), c.textFieldId()));
    }

    String path(Context c) {
        return template.replace("{owner}", c.owner())
                .replace("{repo}", c.repo())
                .replace("{number}", Long.toString(c.number()))
                .replace("{other}", Long.toString(c.other()))
                .replace("{subIssueId}", Long.toString(c.subIssueId()))
                .replace("{projectNumber}", Integer.toString(c.projectNumber()))
                .replace("{label}", segment(c.label()));
    }

    /** A label's name as one path segment, as GitHub's documentation asks it encoded. */
    private static String segment(String name) {
        return java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace(".", "%2E");
    }

    /**
     * What the recorder made and the replay addresses: the repository's two issues by number and
     * id, the other repository's issue by id, the project, its fields and items.
     */
    record Context(
            String owner,
            String repo,
            String prefix,
            long number,
            long other,
            long subIssueId,
            long thirdIssueId,
            String since,
            String etag,
            String itemsEtag,
            int projectNumber,
            String projectId,
            String repositoryId,
            List<String> issueIds,
            String unknownIssueId,
            List<String> itemIds,
            String fieldId,
            String statusFieldId,
            String statusOptionId,
            String textFieldId,
            String numberFieldId,
            List<Long> fieldDatabaseIds,
            String label) {
        /** Keeps the lists from changing. */
        Context {
            issueIds = List.copyOf(issueIds);
            itemIds = List.copyOf(itemIds);
            fieldDatabaseIds = List.copyOf(fieldDatabaseIds);
        }
    }
}
