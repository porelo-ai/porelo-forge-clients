package org.fuin.porelo.forge.github;

import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Makes the pull request the live check reads, which stays open between runs: labeled {@code
 * forge-client-live}, with an approving review, a resolved inline comment, a check run and a commit
 * status. It makes none while one is there, and the label comes last, so a half-made one is never
 * read as the kept one.
 */
public final class GitHubKeptFixture {
    private static final String BRANCH = "forge-client-kept";
    private static final String FILE = BRANCH + ".txt";
    private static final String ROOT = GitHubFixture.ROOT;

    private GitHubKeptFixture() {}

    /** Makes the kept pull request from the live environment. */
    public static void main(String[] args) throws Exception {
        if (args.length != 0) throw new IllegalArgumentException("It takes no arguments");
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                var fixture = GitHubFixture.environment(http)) {
            long number =
                    ensure(fixture, new GitHubGraphQl(GitHubFixture.BASE, http, Strictness.STRICT));
            System.out.println("kept pull request: " + number);
        }
    }

    /** Says the kept pull request's number, made now unless it was there. */
    static long ensure(GitHubFixture fixture, GitHubGraphQl graphql) throws Exception {
        for (var pull : fixture.api("GET", ROOT + "/pulls?state=open&per_page=100", null)) {
            for (var label : pull.path("labels")) {
                if (label.path("name").asText().equals(GitHubFixture.KEPT)) {
                    return pull.path("number").asLong();
                }
            }
        }
        String main =
                fixture.api("GET", ROOT + "/git/ref/heads/main", null)
                        .path("object")
                        .path("sha")
                        .asText();
        // Refused while it exists, so an earlier half-made run is never built upon or removed.
        fixture.api("POST", ROOT + "/git/refs", Map.of("ref", "refs/heads/" + BRANCH, "sha", main));
        Thread removal = new Thread(() -> removeBranch(fixture), "kept-fixture-cleanup");
        Runtime.getRuntime().addShutdownHook(removal);
        try {
            long number = build(fixture, graphql);
            Runtime.getRuntime().removeShutdownHook(removal);
            return number;
        } catch (Exception failure) {
            Runtime.getRuntime().removeShutdownHook(removal);
            removeBranch(fixture);
            throw failure;
        }
    }

    private static long build(GitHubFixture fixture, GitHubGraphQl graphql) throws Exception {
        String sha =
                fixture.api(
                                "PUT",
                                ROOT + "/contents/" + FILE,
                                Map.of(
                                        "message",
                                        "The commit the live check reads",
                                        "branch",
                                        BRANCH,
                                        "content",
                                        Base64.getEncoder()
                                                .encodeToString(
                                                        "fixture-complete\n"
                                                                .getBytes(StandardCharsets.UTF_8))))
                        .path("commit")
                        .path("sha")
                        .asText();
        fixture.api(
                "POST",
                ROOT + "/statuses/" + sha,
                Map.of("state", "success", "context", "forge-client/kept"));
        fixture.apiAs(
                fixture.appToken(),
                "POST",
                ROOT + "/check-runs",
                Map.of(
                        "name",
                        "forge-client-kept",
                        "head_sha",
                        sha,
                        "status",
                        "completed",
                        "conclusion",
                        "success"));
        long number =
                fixture.api(
                                "POST",
                                ROOT + "/pulls",
                                Map.of(
                                        "title",
                                        "The pull request the live check reads",
                                        "head",
                                        BRANCH,
                                        "base",
                                        "main"))
                        .path("number")
                        .asLong();
        if (number < 1) throw new IllegalStateException("The kept pull request has no number");
        String pull = ROOT + "/pulls/" + number;
        fixture.apiAs(
                fixture.reviewerToken(),
                "POST",
                pull + "/reviews",
                Map.of("event", "APPROVE", "body", "Approved", "commit_id", sha));
        fixture.apiAs(
                fixture.reviewerToken(),
                "POST",
                pull + "/comments",
                Map.of(
                        "body",
                        "A comment to resolve",
                        "commit_id",
                        sha,
                        "path",
                        FILE,
                        "line",
                        1,
                        "side",
                        "RIGHT"));
        String thread =
                graphql.reviewThreads(
                                fixture.userToken(),
                                GitHubFixture.OWNER,
                                GitHubFixture.REPO,
                                Math.toIntExact(number),
                                null)
                        .data()
                        .repository()
                        .pullRequest()
                        .reviewThreads()
                        .nodes()
                        .getFirst()
                        .id();
        graphql.resolveReviewThread(fixture.userToken(), thread);
        fixture.api(
                "POST",
                ROOT + "/issues/" + number + "/labels",
                Map.of("labels", List.of(GitHubFixture.KEPT)));
        return number;
    }

    private static void removeBranch(GitHubFixture fixture) {
        try {
            fixture.api("DELETE", ROOT + "/git/refs/heads/" + BRANCH, null);
        } catch (Exception failed) {
            System.err.println("The branch " + BRANCH + " is left behind");
        }
    }
}
