package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;
import org.fuin.porelo.forge.github.model.*;

/** Validates that fixtures exercise useful API data rather than empty envelopes. */
final class GitHubScenario {
    private final GitHubFixture fixture;
    private final RecordingHttpClient transport;
    private final GitHubClient client;
    private final GitHubGraphQl graphql;
    private final com.fasterxml.jackson.databind.ObjectMapper json =
            GitHubJson.mapper(Strictness.STRICT);
    private GitHubOperations.Context context;

    GitHubScenario(GitHubFixture fixture, java.net.http.HttpClient http) {
        this.fixture = fixture;
        transport = new RecordingHttpClient(http);
        client = new GitHubClient(GitHubFixture.BASE, transport);
        graphql = new GitHubGraphQl(GitHubFixture.BASE, transport);
        context =
                new GitHubOperations.Context(
                        GitHubFixture.OWNER,
                        GitHubFixture.REPO,
                        fixture.sha(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        "pending",
                        "heads/" + fixture.prefix,
                        "pending");
    }

    java.util.List<Capture> run() throws Exception {
        var user = fixture.api("GET", "user", null).path("login").asText();
        if (!user.matches("[A-Za-z0-9-]{1,39}"))
            throw new IllegalStateException("Test user has no login");
        context =
                new GitHubOperations.Context(
                        context.owner(),
                        context.repo(),
                        context.sha(),
                        0,
                        0,
                        0,
                        0,
                        0,
                        user,
                        context.ref(),
                        "pending");
        java.util.List<Capture> captures = new java.util.ArrayList<>();
        for (var operation : GitHubOperations.values()) {
            if (operation == GitHubOperations.runs) {
                long run = fixture.dispatch(fixture.prefix);
                var jobs =
                        fixture.api(
                                        "GET",
                                        GitHubFixture.ROOT + "/actions/runs/" + run + "/jobs",
                                        null)
                                .path("jobs");
                long job = jobs.path(0).path("id").asLong();
                if (job <= 0) throw new IllegalStateException("Dispatched workflow has no job");
                update(
                        context.number(),
                        context.comment(),
                        context.check(),
                        run,
                        job,
                        context.thread());
            }
            var body = body(operation);
            String token =
                    switch (operation) {
                        case createReview, createReviewComment -> fixture.reviewerToken();
                        case createCheckRun, updateCheckRun -> fixture.appToken();
                        default -> fixture.userToken();
                    };
            Object result =
                    fixture.guarded(() -> operation.invoke(client, graphql, token, context, body));
            requireUsefulResult(operation, result);
            switch (result) {
                case PullRequest pull when operation == GitHubOperations.createPull ->
                        update(
                                pull.number(),
                                context.comment(),
                                context.check(),
                                context.run(),
                                context.job(),
                                context.thread());
                case PullRequestReviewComment comment
                        when operation == GitHubOperations.createReviewComment ->
                        update(
                                context.number(),
                                comment.id(),
                                context.check(),
                                context.run(),
                                context.job(),
                                context.thread());
                case CheckRun check when operation == GitHubOperations.createCheckRun ->
                        update(
                                context.number(),
                                context.comment(),
                                check.id(),
                                context.run(),
                                context.job(),
                                context.thread());
                case GitHubGraphQl.ThreadsResponse threads ->
                        update(
                                context.number(),
                                context.comment(),
                                context.check(),
                                context.run(),
                                context.job(),
                                threads.data()
                                        .repository()
                                        .pullRequest()
                                        .reviewThreads()
                                        .nodes()
                                        .getFirst()
                                        .id());
                default -> {}
            }
            var exchanges = transport.take();
            if (exchanges.isEmpty())
                throw new IllegalStateException("Operation exchanged no response: " + operation);
            var recording = exchanges.getLast().deepCopy();
            recording.set("request_uri", exchanges.getFirst().path("request_uri"));
            recording.set("exchanges", json.valueToTree(exchanges));
            recording.set("context", json.valueToTree(context));
            // Captured inputs and replay inputs share exactly the same schema.
            if (operation.path(context).equals("/graphql")) {
                var request =
                        json.readTree(GitHubRecordingTest.requestBody(transport.lastRequest()));
                recording.set("request_body", request);
            } else recording.set("request_body", body);
            captures.add(new Capture(operation.name(), recording));
        }
        return java.util.List.copyOf(captures);
    }

    private void update(long number, long comment, long check, long run, long job, String thread) {
        context =
                new GitHubOperations.Context(
                        context.owner(),
                        context.repo(),
                        context.sha(),
                        number,
                        comment,
                        check,
                        run,
                        job,
                        context.username(),
                        context.ref(),
                        thread);
    }

    private com.fasterxml.jackson.databind.JsonNode body(GitHubOperations operation) {
        return json.valueToTree(
                switch (operation) {
                    case createPull ->
                            java.util.Map.of(
                                    "head",
                                    fixture.prefix,
                                    "base",
                                    "main",
                                    "title",
                                    fixture.prefix);
                    case createReview ->
                            java.util.Map.of(
                                    "event",
                                    "APPROVE",
                                    "body",
                                    "Approved fixture",
                                    "commit_id",
                                    context.sha());
                    case createReviewComment ->
                            java.util.Map.of(
                                    "body",
                                    "```suggestion\nfixture-complete\n```",
                                    "commit_id",
                                    context.sha(),
                                    "path",
                                    fixture.prefix + ".txt",
                                    "line",
                                    1,
                                    "side",
                                    "RIGHT");
                    case replyReviewComment -> java.util.Map.of("body", "Fixture reply");
                    case createCheckRun ->
                            java.util.Map.of(
                                    "name",
                                    fixture.prefix,
                                    "head_sha",
                                    context.sha(),
                                    "external_id",
                                    fixture.prefix);
                    case updateCheckRun ->
                            java.util.Map.of("status", "completed", "conclusion", "success");
                    default -> null;
                });
    }

    record Capture(String operation, com.fasterxml.jackson.databind.node.ObjectNode recording) {}

    static void requireUsefulResult(GitHubOperations operation, Object result) {
        assertThat(result).as("%s must return a payload", operation).isNotNull();
        switch (result) {
            case Collection<?> values ->
                    assertThat(values).as("%s must return a seeded item", operation).isNotEmpty();
            case ActionsListWorkflowRunsForRepo200Response runs ->
                    assertThat(runs.workflowRuns())
                            .as("runs must include a real build")
                            .isNotEmpty();
            case ActionsListJobsForWorkflowRun200Response jobs ->
                    assertThat(jobs.jobs()).as("jobs must include an executed job").isNotEmpty();
            case ChecksListForRef200Response checks ->
                    assertThat(checks.checkRuns())
                            .as("checks must include an App check run")
                            .isNotEmpty();
            case GitHubGraphQl.ThreadsResponse response -> {
                assertThat(response.data().repository())
                        .as("the review repository is visible")
                        .isNotNull();
                var pull = response.data().repository().pullRequest();
                assertThat(pull).as("the fixture pull request exists").isNotNull();
                assertThat(pull.reviewThreads().nodes())
                        .as("the fixture has review threads")
                        .isNotEmpty();
                for (var thread : pull.reviewThreads().nodes())
                    assertThat(thread.comments().nodes())
                            .as("each fixture thread has comments")
                            .isNotEmpty();
            }
            case GitHubGraphQl.CommentsResponse response -> {
                assertThat(response.data().node()).as("the comment thread is visible").isNotNull();
                assertThat(response.data().node().comments().nodes())
                        .as("the thread has comments")
                        .isNotEmpty();
            }
            case GitHubGraphQl.ResolveResponse response ->
                    assertThat(response.data().resolveReviewThread().thread().isResolved())
                            .as("resolve changes the thread state")
                            .isTrue();
            case GitHubGraphQl.UnresolveResponse response ->
                    assertThat(response.data().unresolveReviewThread().thread().isResolved())
                            .as("unresolve changes the thread state")
                            .isFalse();
            // Only a yes or no is asserted, so a failure prints no log line.
            case String logs ->
                    assertThat(logs.contains("fixture-complete"))
                            .as("the job executed its expected step")
                            .isTrue();
            case GitRef ref ->
                    assertThat(ref._object().sha())
                            .as("the ref identifies a commit")
                            .matches("[a-f0-9]{40,64}");
            case OrgMembership membership ->
                    assertThat(membership.organization().id())
                            .as("membership identifies its organization")
                            .isPositive();
            case RepositoryCollaboratorPermission permission ->
                    assertThat(permission.permission()).as("permission is readable").isNotBlank();
            default ->
                    assertThat(
                                    GitHubJson.mapper(Strictness.STRICT)
                                            .valueToTree(result)
                                            .path("id")
                                            .asLong())
                            .as("%s returns a resource identity", operation)
                            .isPositive();
        }
    }
}
