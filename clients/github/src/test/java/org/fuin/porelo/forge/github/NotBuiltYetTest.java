package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewOption;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.junit.jupiter.api.Test;

/**
 * The calls whose signatures are published before they are built must fail loud, never answer with
 * nothing; a caller that builds against them meets the gap at once.
 */
class NotBuiltYetTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final String TOKEN = "synthetic-access-token";

    // Prevents a published but unbuilt call from reading as an empty answer.
    @Test
    void everyUnbuiltCallSaysItIsNotBuiltYet() {
        var http = new ChunkedTransport(500, Map.of(), "{}");
        var rest = new GitHubClient(BASE, http);
        var graphQl = new GitHubGraphQl(BASE, http);
        var create = new IssuesCreateRequest("t", null, null, null, null, null, null, null);
        var update =
                new IssuesUpdateRequest(null, null, null, null, null, null, null, null, null, null);
        Instant since = Instant.parse("2026-01-01T00:00:00Z");
        Map<String, ThrowingCallable> calls = new LinkedHashMap<>();
        calls.put(
                "GitHubClient.issues",
                () -> rest.issues(TOKEN, "o", "r", "all", List.of("story"), since, 1, 10));
        calls.put(
                "GitHubClient.issuesIfChanged",
                () -> rest.issuesIfChanged(TOKEN, "o", "r", null, null, null, 1, 10, "\"e\""));
        calls.put("GitHubClient.issue", () -> rest.issue(TOKEN, "o", "r", 1));
        calls.put("GitHubClient.createIssue", () -> rest.createIssue(TOKEN, "o", "r", create));
        calls.put("GitHubClient.updateIssue", () -> rest.updateIssue(TOKEN, "o", "r", 1, update));
        calls.put("GitHubClient.parentIssue", () -> rest.parentIssue(TOKEN, "o", "r", 1));
        calls.put("GitHubClient.subIssues", () -> rest.subIssues(TOKEN, "o", "r", 1, 1, 10));
        calls.put("GitHubClient.addSubIssue", () -> rest.addSubIssue(TOKEN, "o", "r", 1, 2, false));
        calls.put("GitHubClient.removeSubIssue", () -> rest.removeSubIssue(TOKEN, "o", "r", 1, 2));
        calls.put(
                "GitHubClient.reprioritizeSubIssue",
                () -> rest.reprioritizeSubIssue(TOKEN, "o", "r", 1, 2, 3L, null));
        calls.put("GitHubClient.blockedBy", () -> rest.blockedBy(TOKEN, "o", "r", 1, 1, 10));
        calls.put("GitHubClient.addBlockedBy", () -> rest.addBlockedBy(TOKEN, "o", "r", 1, 2));
        calls.put(
                "GitHubClient.removeBlockedBy", () -> rest.removeBlockedBy(TOKEN, "o", "r", 1, 2));
        calls.put("GitHubClient.blocking", () -> rest.blocking(TOKEN, "o", "r", 1, 1, 10));
        calls.put(
                "GitHubClient.projectItemsIfChanged",
                () -> rest.projectItemsIfChanged(TOKEN, "o", 1, null, 10, null));
        calls.put("GitHubGraphQl.projectFields", () -> graphQl.projectFields(TOKEN, "o", 1, null));
        calls.put("GitHubGraphQl.projectItems", () -> graphQl.projectItems(TOKEN, "o", 1, null));
        calls.put(
                "GitHubGraphQl.issueBoard", () -> graphQl.issueBoard(TOKEN, List.of("I_1"), "P_1"));
        calls.put(
                "GitHubGraphQl.addProjectItems",
                () -> graphQl.addProjectItems(TOKEN, "P_1", List.of("I_1")));
        calls.put(
                "GitHubGraphQl.setFieldValues",
                () ->
                        graphQl.setFieldValues(
                                TOKEN, "P_1", List.of(FieldValue.text("PVTI_1", "F_1", "x"))));
        calls.put(
                "GitHubGraphQl.setFieldOptions",
                () ->
                        graphQl.setFieldOptions(
                                TOKEN, "F_1", List.of(new NewOption("Todo", "GRAY", ""))));
        calls.put("GitHubGraphQl.statusChanges", () -> graphQl.statusChanges(TOKEN, "I_1", null));
        calls.put(
                "GitHubGraphQl.createIssues",
                () ->
                        graphQl.createIssues(
                                TOKEN, List.of(new NewIssue("R_1", "t", null, null, List.of()))));

        calls.forEach(
                (name, call) ->
                        assertThatExceptionOfType(UnsupportedOperationException.class)
                                .as("%s fails loud", name)
                                .isThrownBy(call)
                                .withMessage(name + " is not built yet"));
    }
}
