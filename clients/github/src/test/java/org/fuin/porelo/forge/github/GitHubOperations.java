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
    updateReviewComment("PATCH", "/repos/{owner}/{repo}/pulls/comments/{comment_id}"),
    createIssueComment("POST", "/repos/{owner}/{repo}/issues/{pull_number}/comments"),
    updateIssueComment("PATCH", "/repos/{owner}/{repo}/issues/comments/{issue_comment_id}"),
    createCheckRun("POST", "/repos/{owner}/{repo}/check-runs"),
    updateCheckRun("PATCH", "/repos/{owner}/{repo}/check-runs/{check_run_id}"),
    pulls("GET", "/repos/{owner}/{repo}/pulls"),
    pullsOfHead("GET", "/repos/{owner}/{repo}/pulls"),
    pull("GET", "/repos/{owner}/{repo}/pulls/{pull_number}"),
    reviews("GET", "/repos/{owner}/{repo}/pulls/{pull_number}/reviews"),
    reviewComments("GET", "/repos/{owner}/{repo}/pulls/{pull_number}/comments"),
    reviewCommentsOf(
            "GET", "/repos/{owner}/{repo}/pulls/{pull_number}/reviews/{review_id}/comments"),
    issueComments("GET", "/repos/{owner}/{repo}/issues/{pull_number}/comments"),
    compare("GET", "/repos/{owner}/{repo}/compare/{basehead}"),
    checkRuns("GET", "/repos/{owner}/{repo}/commits/{ref}/check-runs"),
    runs("GET", "/repos/{owner}/{repo}/actions/runs"),
    jobs("GET", "/repos/{owner}/{repo}/actions/runs/{run_id}/jobs"),
    jobLogs("GET", "/repos/{owner}/{repo}/actions/jobs/{job_id}/logs"),
    ref("GET", "/repos/{owner}/{repo}/git/ref/{ref}"),
    user("GET", "/user"),
    membership("GET", "/user/memberships/orgs/{org}"),
    orgMembership("GET", "/orgs/{org}/memberships/{username}"),
    keys("GET", "/users/{login}/keys"),
    signingKeys("GET", "/users/{login}/ssh_signing_keys"),
    plainKeys("GET", "/{login}.keys"),
    ownKeys("GET", "/user/keys"),
    addKey("POST", "/user/keys"),
    ownSigningKeys("GET", "/user/ssh_signing_keys"),
    addSigningKey("POST", "/user/ssh_signing_keys"),
    repository("GET", "/repos/{owner}/{repo}"),
    createRepository("POST", "/orgs/{org}/repos"),
    status("GET", "/repos/{owner}/{repo}/commits/{ref}/status"),
    permission("GET", "/repos/{owner}/{repo}/collaborators/{username}/permission"),
    reviewThreads("POST", "/graphql"),
    threadComments("POST", "/graphql"),
    resolveReviewThread("POST", "/graphql"),
    // Between resolving and reopening, so the thread is read with its resolver.
    reviewThread("POST", "/graphql"),
    unresolveReviewThread("POST", "/graphql"),
    deleteIssueComment("DELETE", "/repos/{owner}/{repo}/issues/comments/{issue_comment_id}"),
    deleteReviewComment("DELETE", "/repos/{owner}/{repo}/pulls/comments/{reply_id}"),
    deleteKey("DELETE", "/user/keys/{key_id}"),
    deleteSigningKey("DELETE", "/user/ssh_signing_keys/{signing_key_id}");
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
            case pullsOfHead ->
                    client.pullsOfHead(token, c.owner(), c.repo(), head(c), "open", 1, 50);
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
            case updateReviewComment ->
                    client.updateReviewComment(
                            token,
                            c.owner(),
                            c.repo(),
                            c.comment(),
                            JSON.treeToValue(body, PullsUpdateReviewCommentRequest.class));
            case deleteReviewComment -> {
                client.deleteReviewComment(token, c.owner(), c.repo(), c.reply());
                yield Deleted.DELETED;
            }
            case reviewCommentsOf ->
                    client.reviewCommentsOf(
                            token, c.owner(), c.repo(), c.number(), c.review(), 1, 50);
            case issueComments ->
                    client.issueComments(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            java.time.Instant.parse(c.since()),
                            1,
                            50);
            case createIssueComment ->
                    client.createIssueComment(
                            token,
                            c.owner(),
                            c.repo(),
                            c.number(),
                            JSON.treeToValue(body, IssuesUpdateCommentRequest.class));
            case updateIssueComment ->
                    client.updateIssueComment(
                            token,
                            c.owner(),
                            c.repo(),
                            c.issueComment(),
                            JSON.treeToValue(body, IssuesUpdateCommentRequest.class));
            case deleteIssueComment -> {
                client.deleteIssueComment(token, c.owner(), c.repo(), c.issueComment());
                yield Deleted.DELETED;
            }
            case compare -> client.compare(token, c.owner(), c.repo(), c.base(), c.sha());
            case reviewThread -> graphql.reviewThread(token, c.thread());
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
            case orgMembership -> client.orgMembership(token, c.owner(), c.username());
            case keys -> client.keys(c.keyAccount(), 1, 50);
            case signingKeys -> client.signingKeys(c.keyAccount(), 1, 50);
            case plainKeys -> client.plainKeys(c.keyAccount());
            case ownKeys -> client.ownKeys(token, 1, 50);
            case addKey ->
                    client.addKey(
                            token,
                            JSON.treeToValue(
                                    body,
                                    UsersCreatePublicSshKeyForAuthenticatedUserRequest.class));
            case ownSigningKeys -> client.ownSigningKeys(token, 1, 50);
            case addSigningKey ->
                    client.addSigningKey(
                            token,
                            JSON.treeToValue(
                                    body,
                                    UsersCreateSshSigningKeyForAuthenticatedUserRequest.class));
            case repository -> client.repository(token, c.owner(), c.repo());
            case createRepository ->
                    client.createRepository(
                            token,
                            c.owner(),
                            JSON.treeToValue(body, ReposCreateInOrgRequest.class));
            case status -> client.status(token, c.owner(), c.repo(), c.sha(), 1, 50);
            case deleteKey -> {
                client.deleteKey(token, c.key());
                yield Deleted.DELETED;
            }
            case deleteSigningKey -> {
                client.deleteSigningKey(token, c.signingKey());
                yield Deleted.DELETED;
            }
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
                .replace("{reply_id}", Long.toString(c.reply()))
                .replace("{issue_comment_id}", Long.toString(c.issueComment()))
                .replace("{review_id}", Long.toString(c.review()))
                .replace("{basehead}", c.base() + "..." + c.sha())
                .replace("{check_run_id}", Long.toString(c.check()))
                .replace("{run_id}", Long.toString(c.run()))
                .replace("{job_id}", Long.toString(c.job()))
                .replace("{username}", c.username())
                .replace("{login}", c.keyAccount())
                .replace("{key_id}", Long.toString(c.key()))
                .replace("{signing_key_id}", Long.toString(c.signingKey()))
                .replace("{org}", c.owner())
                .replace("{ref}", this == checkRuns || this == status ? c.sha() : c.ref());
    }

    String query(Context c) {
        return switch (this) {
            case pulls -> "state=open&page=1&per_page=50";
            case pullsOfHead ->
                    "state=open&head=" + head(c).replace(":", "%3A") + "&page=1&per_page=50";
            case runs -> "head_sha=" + c.sha() + "&page=1&per_page=50";
            case reviews,
                    reviewComments,
                    reviewCommentsOf,
                    checkRuns,
                    jobs,
                    keys,
                    signingKeys,
                    ownKeys,
                    ownSigningKeys,
                    status ->
                    "page=1&per_page=50";
            case issueComments -> "since=" + c.since().replace(":", "%3A") + "&page=1&per_page=50";
            case compare -> "page=1&per_page=1";
            default -> null;
        };
    }

    /** Whether the call sends no token: an account's public keys. */
    boolean anonymous() {
        return this == keys || this == signingKeys || this == plainKeys;
    }

    /** Whether the call reads a page of the web beside the API rather than the API. */
    boolean web() {
        return this == plainKeys;
    }

    /** The fixture branch as GitHub names a pull request's head, {@code owner:branch}. */
    private static String head(Context c) {
        return c.owner() + ":" + c.ref().substring("heads/".length());
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
            String thread,
            long reply,
            long issueComment,
            long review,
            String since,
            String base,
            String keyAccount,
            long key,
            long signingKey) {}

    /** What a delete answers: nothing but its status. */
    enum Deleted {
        DELETED
    }
}
