package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.fuin.porelo.forge.github.model.*;

/** The inventory shared by live capture and strict offline replay. */
enum GitHubOperations {
    createPull("POST", "/repos/{owner}/{repo}/pulls"),
    createReview("POST", "/repos/{owner}/{repo}/pulls/{pull_number}/reviews"),
    createReviewComment("POST", "/repos/{owner}/{repo}/pulls/{pull_number}/comments"),
    replyReviewComment(
            "POST", "/repos/{owner}/{repo}/pulls/{pull_number}/comments/{comment_id}/replies"),
    createCheckRun("POST", "/repos/{owner}/{repo}/check-runs"),
    updateCheckRun("PATCH", "/repos/{owner}/{repo}/check-runs/{check_run_id}"),
    pulls("GET", "/repos/{owner}/{repo}/pulls"),
    pull("GET", "/repos/{owner}/{repo}/pulls/{pull_number}"),
    reviews("GET", "/repos/{owner}/{repo}/pulls/{pull_number}/reviews"),
    reviewComments("GET", "/repos/{owner}/{repo}/pulls/{pull_number}/comments"),
    checkRuns("GET", "/repos/{owner}/{repo}/commits/{ref}/check-runs"),
    runs("GET", "/repos/{owner}/{repo}/actions/runs"),
    jobs("GET", "/repos/{owner}/{repo}/actions/runs/{run_id}/jobs"),
    jobLogs("GET", "/repos/{owner}/{repo}/actions/jobs/{job_id}/logs"),
    ref("GET", "/repos/{owner}/{repo}/git/ref/{ref}"),
    user("GET", "/user"),
    membership("GET", "/user/memberships/orgs/{org}"),
    permission("GET", "/repos/{owner}/{repo}/collaborators/{username}/permission"),
    reviewThreads("POST", "/graphql"),
    threadComments("POST", "/graphql"),
    resolveReviewThread("POST", "/graphql"),
    unresolveReviewThread("POST", "/graphql");
    final String method;
    private final String template;
    private static final ObjectMapper JSON = GitHubJson.mapper(Strictness.STRICT);

    GitHubOperations(String method, String template) {
        this.method = method;
        this.template = template;
    }

    Object invoke(
            GitHubClient client, GitHubGraphQl graphql, String token, Context c, JsonNode body)
            throws Exception {
        return switch (this) {
            case jobLogs -> client.jobLogs(token, c.owner(), c.repo(), c.job());
            case runs -> client.runs(token, c.owner(), c.repo(), c.sha(), 1, 50);
            case jobs -> client.jobs(token, c.owner(), c.repo(), c.run(), 1, 50);
            case createCheckRun ->
                    client.createCheckRun(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, ChecksCreateRequest.class));
            case updateCheckRun ->
                    client.updateCheckRun(
                            token,
                            c.owner(),
                            c.repo(),
                            c.check(),
                            JSON.treeToValue(body, ChecksUpdateRequest.class));
            case permission -> client.permission(token, c.owner(), c.repo(), c.username());
            case checkRuns -> client.checkRuns(token, c.owner(), c.repo(), c.sha(), 1, 50);
            case ref -> client.ref(token, c.owner(), c.repo(), c.ref());
            case pulls -> client.pulls(token, c.owner(), c.repo(), "open", 1, 50);
            case createPull ->
                    client.createPull(
                            token,
                            c.owner(),
                            c.repo(),
                            JSON.treeToValue(body, PullsCreateRequest.class));
            case pull -> client.pull(token, c.owner(), c.repo(), c.number());
            case reviewComments ->
                    client.reviewComments(token, c.owner(), c.repo(), c.number(), 1, 50);
            case createReviewComment ->
                    client.createReviewComment(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            JSON.treeToValue(body, PullsCreateReviewCommentRequest.class));
            case replyReviewComment ->
                    client.replyReviewComment(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            c.comment(),
                            JSON.treeToValue(body, PullsCreateReplyForReviewCommentRequest.class));
            case reviews -> client.reviews(token, c.owner(), c.repo(), c.number(), 1, 50);
            case createReview ->
                    client.createReview(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            JSON.treeToValue(body, PullsCreateReviewRequest.class));
            case user -> client.user(token);
            case membership -> client.membership(token, c.owner());
            case reviewThreads ->
                    graphql.reviewThreads(
                            token, c.owner(), c.repo(), Math.toIntExact(c.number()), null);
            case threadComments -> graphql.threadComments(token, c.thread(), null);
            case resolveReviewThread -> graphql.resolveReviewThread(token, c.thread());
            case unresolveReviewThread -> graphql.unresolveReviewThread(token, c.thread());
        };
    }

    String path(Context c) {
        return template.replace("{owner}", c.owner())
                .replace("{repo}", c.repo())
                .replace("{pull_number}", Long.toString(c.number()))
                .replace("{comment_id}", Long.toString(c.comment()))
                .replace("{check_run_id}", Long.toString(c.check()))
                .replace("{run_id}", Long.toString(c.run()))
                .replace("{job_id}", Long.toString(c.job()))
                .replace("{username}", c.username())
                .replace("{org}", c.owner())
                .replace("{ref}", this == checkRuns ? c.sha() : c.ref());
    }

    String query(Context c) {
        return switch (this) {
            case pulls -> "state=open&page=1&per_page=50";
            case runs -> "head_sha=" + c.sha() + "&page=1&per_page=50";
            case reviews, reviewComments, checkRuns, jobs -> "page=1&per_page=50";
            default -> null;
        };
    }

    record Context(
            String owner,
            String repo,
            String sha,
            long number,
            long comment,
            long check,
            long run,
            long job,
            String username,
            String ref,
            String thread) {}
}
