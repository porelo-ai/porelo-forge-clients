package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.fuin.porelo.forge.github.model.Key;
import org.fuin.porelo.forge.github.model.KeySimple;
import org.fuin.porelo.forge.github.model.PrivateUser;
import org.fuin.porelo.forge.github.model.PublicUser;
import org.fuin.porelo.forge.github.model.PullRequestSimple;
import org.fuin.porelo.forge.github.model.SimpleUser;
import org.fuin.porelo.forge.github.model.SshSigningKey;
import org.jspecify.annotations.Nullable;

/** Scheduled strict reading against a retained fixture; only Actions dispatch writes new data. */
public final class GitHubLiveCheck {
    private GitHubLiveCheck() {}

    /**
     * Reads the approving reviewer's public keys of both kinds without a token, its key page, and
     * the same account's own keys with its keys token; the account keeps one key of each kind.
     */
    static void readKeys(GitHubClient client, String keysToken, @Nullable SimpleUser reviewer)
            throws Exception {
        if (reviewer == null) throw new IllegalStateException("The approving review names no one");
        String login = reviewer.login();
        var keys = read("keys", () -> client.keys(login, 1, 100));
        assertThat(keys).as("the reviewer has a public key").isNotEmpty();
        var signing = read("signingKeys", () -> client.signingKeys(login, 1, 100));
        assertThat(signing).as("the reviewer has a public signing key").isNotEmpty();
        var page = read("plainKeys", () -> client.plainKeys(login));
        assertThat(page)
                .as("the key page lists the same keys")
                .containsExactlyInAnyOrderElementsOf(keys.stream().map(KeySimple::key).toList());
        var own = read("ownKeys", () -> client.ownKeys(keysToken, 1, 100));
        assertThat(own.stream().map(Key::id).toList())
                .as("the reviewer's own keys are those it shows")
                .containsExactlyInAnyOrderElementsOf(keys.stream().map(KeySimple::id).toList());
        var ownSigning = read("ownSigningKeys", () -> client.ownSigningKeys(keysToken, 1, 100));
        assertThat(ownSigning.stream().map(SshSigningKey::id).toList())
                .as("the reviewer's own signing keys are those it shows")
                .containsExactlyInAnyOrderElementsOf(
                        signing.stream().map(SshSigningKey::id).toList());
    }

    /** Checks the retained fixture and dispatches a fresh log-producing workflow. */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("Live checks take no arguments");
        String token = GitHubFixture.required(System.getenv(), "LIVE_GITHUB_USER_TOKEN");
        String keysToken =
                GitHubFixture.required(System.getenv(), "LIVE_GITHUB_REVIEWER_KEYS_TOKEN");
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                var fixture =
                        new GitHubFixture(
                                http,
                                token,
                                token,
                                token,
                                keysToken,
                                List.of(token, keysToken),
                                UUID.randomUUID().toString())) {
            fixture.beginLiveCheck();
            var client =
                    new GitHubClient(
                            GitHubFixture.BASE, GitHubFixture.WEB, http, Strictness.STRICT);
            var graphql = new GitHubGraphQl(GitHubFixture.BASE, http, Strictness.STRICT);
            List<PullRequestSimple> selected = new ArrayList<>();
            for (int page = 1; page <= 1000; page++) {
                final int current = page;
                var pulls =
                        read(
                                "pulls",
                                () ->
                                        client.pulls(
                                                token,
                                                GitHubFixture.OWNER,
                                                GitHubFixture.REPO,
                                                "open",
                                                current,
                                                100));
                for (var pull : pulls)
                    if (pull.labels().stream()
                            .anyMatch(label -> label.name().equals(GitHubFixture.KEPT)))
                        selected.add(pull);
                if (pulls.size() < 100) break;
                if (page == 1000)
                    throw new IllegalStateException("Pull discovery exceeded pagination bound");
            }
            // Only counts and yes or no are asserted, so a failure prints no answer into the CI
            // log.
            assertThat(selected.size())
                    .as("exactly one kept pull request is labeled forge-client-live")
                    .isEqualTo(1);
            long number = selected.getFirst().number();
            var pull =
                    read(
                            "pull",
                            () ->
                                    client.pull(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            number));
            var reviews =
                    read(
                            "reviews",
                            () ->
                                    client.reviews(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            number,
                                            1,
                                            100));
            assertThat(reviews.stream().anyMatch(review -> review.state().equals("APPROVED")))
                    .as("the retained pull has an approving review")
                    .isTrue();
            var comments =
                    read(
                            "reviewComments",
                            () ->
                                    client.reviewComments(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            number,
                                            1,
                                            100));
            assertThat(comments).as("the retained pull has inline review comments").isNotEmpty();
            var checks =
                    read(
                            "checkRuns",
                            () ->
                                    client.checkRuns(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            pull.head().sha(),
                                            1,
                                            100));
            assertThat(checks.checkRuns()).as("the retained head has a check run").isNotEmpty();
            String resolved = readThreads(graphql, token, Math.toIntExact(number));
            assertThat(resolved).as("the retained pull has a resolved thread").isNotNull();
            var thread = read("reviewThread", () -> graphql.reviewThread(token, resolved));
            GitHubScenario.requireUsefulResult(GitHubOperations.reviewThread, thread);
            var ofHead =
                    read(
                            "pullsOfHead",
                            () ->
                                    client.pullsOfHead(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            GitHubFixture.OWNER + ":" + pull.head().ref(),
                                            "open",
                                            1,
                                            100));
            assertThat(ofHead.stream().anyMatch(value -> value.number() == number))
                    .as("the retained pull is found by its head branch")
                    .isTrue();
            long review =
                    java.util.Objects.requireNonNull(
                            comments.getFirst().pullRequestReviewId(),
                            "A review comment names no review");
            var ofReview =
                    read(
                            "reviewCommentsOf",
                            () ->
                                    client.reviewCommentsOf(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            number,
                                            review,
                                            1,
                                            100));
            assertThat(ofReview).as("a review's own comments are read").isNotEmpty();
            read(
                    "issueComments",
                    () ->
                            client.issueComments(
                                    token,
                                    GitHubFixture.OWNER,
                                    GitHubFixture.REPO,
                                    number,
                                    null,
                                    1,
                                    100));
            var comparison =
                    read(
                            "compare",
                            () ->
                                    client.compare(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            pull.base().ref(),
                                            pull.head().sha()));
            GitHubScenario.requireUsefulResult(GitHubOperations.compare, comparison);
            var user = read("user", () -> client.user(token));
            String login =
                    switch (user) {
                        case PrivateUser value -> value.login();
                        case PublicUser value -> value.login();
                        default -> throw new IllegalStateException("Unknown account view");
                    };
            read("membership", () -> client.membership(token, GitHubFixture.OWNER));
            var owner =
                    read(
                            "orgMembership",
                            () -> client.orgMembership(token, GitHubFixture.OWNER, login));
            assertThat(owner.role().equals("admin") && owner.state().equals("active"))
                    .as("the test user owns the test organization")
                    .isTrue();
            readKeys(
                    client,
                    keysToken,
                    reviews.stream()
                            .filter(value -> value.state().equals("APPROVED"))
                            .findFirst()
                            .orElseThrow()
                            .user());
            var repository =
                    read(
                            "repository",
                            () ->
                                    client.repository(
                                            token, GitHubFixture.OWNER, GitHubFixture.REPO));
            assertThat(repository.fullName())
                    .as("the test repository is read by its name")
                    .isEqualTo(GitHubFixture.ROOT.substring("repos/".length()));
            var status =
                    read(
                            "status",
                            () ->
                                    client.status(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            pull.head().sha(),
                                            1,
                                            100));
            GitHubScenario.requireUsefulResult(GitHubOperations.status, status);
            read(
                    "permission",
                    () -> client.permission(token, GitHubFixture.OWNER, GitHubFixture.REPO, login));
            var ref =
                    read(
                            "ref",
                            () ->
                                    client.ref(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            "heads/main"));
            long run = fixture.dispatch("main");
            var runs =
                    read(
                            "runs",
                            () ->
                                    client.runs(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            ref._object().sha(),
                                            1,
                                            100));
            assertThat(runs.workflowRuns().stream().anyMatch(value -> value.id() == run))
                    .as("the fresh dispatch is visible")
                    .isTrue();
            var jobs =
                    read(
                            "jobs",
                            () ->
                                    client.jobs(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            run,
                                            1,
                                            100));
            assertThat(jobs.jobs()).as("the fresh workflow executed a job").isNotEmpty();
            var logs =
                    read(
                            "jobLogs",
                            () ->
                                    client.jobLogs(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            jobs.jobs().getFirst().id()));
            assertThat(logs.contains("fixture-complete"))
                    .as("fresh job logs contain the fixture marker")
                    .isTrue();
        }
    }

    /**
     * Reads every page of the pull's threads and their comments, and reads the first thread's
     * comments once more through the comments query, so that query is checked on every run.
     *
     * @return the node id of the first resolved thread, or null when none is resolved
     */
    static @Nullable String readThreads(GitHubGraphQl graphql, String token, int number)
            throws Exception {
        String resolved = null;
        boolean commentsQueried = false;
        String after = null;
        for (int page = 0; page < 1000; page++) {
            final String cursor = after;
            var threads =
                    read(
                            "reviewThreads",
                            () ->
                                    graphql.reviewThreads(
                                            token,
                                            GitHubFixture.OWNER,
                                            GitHubFixture.REPO,
                                            number,
                                            cursor));
            GitHubScenario.requireUsefulResult(GitHubOperations.reviewThreads, threads);
            var connection = threads.data().repository().pullRequest().reviewThreads();
            for (var thread : connection.nodes()) {
                if (resolved == null && thread.isResolved()) resolved = thread.id();
                if (!commentsQueried) {
                    var first =
                            read(
                                    "threadComments",
                                    () -> graphql.threadComments(token, thread.id(), null));
                    GitHubScenario.requireUsefulResult(GitHubOperations.threadComments, first);
                    assertThat(
                                    first.data()
                                            .node()
                                            .comments()
                                            .nodes()
                                            .equals(thread.comments().nodes()))
                            .as("threadComments reads the same first page as reviewThreads")
                            .isTrue();
                    commentsQueried = true;
                }
                var commentPage = thread.comments();
                for (int comment = 0;
                        comment < 1000 && commentPage.pageInfo().hasNextPage();
                        comment++) {
                    String commentCursor = commentPage.pageInfo().endCursor();
                    if (commentCursor == null)
                        throw new IllegalStateException("Comment cursor is missing");
                    var next =
                            read(
                                    "threadComments",
                                    () ->
                                            graphql.threadComments(
                                                    token, thread.id(), commentCursor));
                    GitHubScenario.requireUsefulResult(GitHubOperations.threadComments, next);
                    commentPage = next.data().node().comments();
                    if (commentPage.pageInfo().hasNextPage()
                            && commentCursor.equals(commentPage.pageInfo().endCursor()))
                        throw new IllegalStateException("Comment cursor repeated");
                    if (comment == 999 && commentPage.pageInfo().hasNextPage())
                        throw new IllegalStateException("Comment paging exceeded bound");
                }
            }
            if (!connection.pageInfo().hasNextPage()) break;
            String next = connection.pageInfo().endCursor();
            if (next == null || next.equals(after))
                throw new IllegalStateException("Thread cursor is missing or repeated");
            after = next;
            if (page == 999) throw new IllegalStateException("Thread paging exceeded bound");
        }
        if (!commentsQueried) throw new IllegalStateException("The pull has no review thread");
        return resolved;
    }

    private static <T> T read(String operation, java.util.concurrent.Callable<T> call)
            throws Exception {
        try {
            return call.call();
        } catch (GitHubException ex) {
            throw new GitHubException(ex.status(), operation + ": " + ex.getMessage());
        }
    }
}
