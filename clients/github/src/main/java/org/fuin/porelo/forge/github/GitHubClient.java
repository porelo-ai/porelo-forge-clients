package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.forge.github.model.*;
import org.jspecify.annotations.Nullable;

/** Synchronous GitHub API calls. The caller owns the transport and supplies a token per call. */
public final class GitHubClient {
    private static final URI WEB = URI.create("https://github.com/");

    private final URI base;
    private final URI web;
    private final HttpClient http;
    private final ObjectMapper json;

    /** Creates a lenient reader with an API base such as {@code https://api.github.com/}. */
    public GitHubClient(URI base, HttpClient http) {
        this(base, http, Strictness.LENIENT);
    }

    /**
     * Creates a reader for github.com's web pages beside the API; the supplied transport must never
     * follow redirects automatically.
     */
    public GitHubClient(URI base, HttpClient http, Strictness strictness) {
        this(base, WEB, http, strictness);
    }

    /**
     * Creates a reader with the web base its key pages are read from, such as {@code
     * https://github.com/}; the supplied transport must never follow redirects automatically.
     */
    public GitHubClient(URI base, URI web, HttpClient http, Strictness strictness) {
        this.base = root(base, "API base");
        this.web = root(web, "web base");
        this.http = Objects.requireNonNull(http);
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("The HTTP client must use Redirect.NEVER");
        }
        json = GitHubJson.mapper(strictness);
    }

    /** Lists one page of pull requests in the requested state. */
    public List<PullRequestSimple> pulls(
            String token, String owner, String repo, String state, int page, int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/pulls?state=" + segment(state) + paging(page, perPage),
                null,
                listType(PullRequestSimple.class));
    }

    /**
     * Reads the pull requests in a state whose head is a branch, named {@code owner:branch} as
     * GitHub names a head of another fork.
     */
    public List<PullRequestSimple> pullsOfHead(
            String token,
            String owner,
            String repo,
            String head,
            String state,
            int page,
            int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/pulls?state="
                        + segment(state)
                        + "&head="
                        + segment(head)
                        + paging(page, perPage),
                null,
                listType(PullRequestSimple.class));
    }

    /** Creates a pull request. */
    public PullRequest createPull(
            String token, String owner, String repo, PullsCreateRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/pulls",
                body,
                json.constructType(PullRequest.class));
    }

    /** Reads a pull request. */
    public PullRequest pull(String token, String owner, String repo, long number) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, number),
                null,
                json.constructType(PullRequest.class));
    }

    /** Lists one page of reviews. */
    public List<PullRequestReview> reviews(
            String token, String owner, String repo, long number, int page, int perPage) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, number) + "/reviews?" + pageQuery(page, perPage),
                null,
                listType(PullRequestReview.class));
    }

    /** Creates a review, optionally with inline comments. */
    public PullRequestReview createReview(
            String token, String owner, String repo, long number, PullsCreateReviewRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                pullPath(owner, repo, number) + "/reviews",
                body,
                json.constructType(PullRequestReview.class));
    }

    /** Lists one page of inline review comments. */
    public List<PullRequestReviewComment> reviewComments(
            String token, String owner, String repo, long number, int page, int perPage) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, number) + "/comments?" + pageQuery(page, perPage),
                null,
                listType(PullRequestReviewComment.class));
    }

    /** Creates an inline comment; its body may contain a GitHub suggestion. */
    public PullRequestReviewComment createReviewComment(
            String token,
            String owner,
            String repo,
            long number,
            PullsCreateReviewCommentRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                pullPath(owner, repo, number) + "/comments",
                body,
                json.constructType(PullRequestReviewComment.class));
    }

    /** Replies to an inline review comment. */
    public PullRequestReviewComment replyReviewComment(
            String token,
            String owner,
            String repo,
            long number,
            long commentId,
            PullsCreateReplyForReviewCommentRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                pullPath(owner, repo, number) + "/comments/" + positive(commentId) + "/replies",
                body,
                json.constructType(PullRequestReviewComment.class));
    }

    /** Changes the text of an inline review comment. */
    public PullRequestReviewComment updateReviewComment(
            String token,
            String owner,
            String repo,
            long commentId,
            PullsUpdateReviewCommentRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "PATCH",
                repo(owner, repo) + "/pulls/comments/" + positive(commentId),
                body,
                json.constructType(PullRequestReviewComment.class));
    }

    /** Deletes an inline review comment. */
    public void deleteReviewComment(String token, String owner, String repo, long commentId) {
        delete(token, repo(owner, repo) + "/pulls/comments/" + positive(commentId));
    }

    /** Lists one page of the inline comments of one review. */
    public List<ReviewComment> reviewCommentsOf(
            String token,
            String owner,
            String repo,
            long number,
            long reviewId,
            int page,
            int perPage) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, number)
                        + "/reviews/"
                        + positive(reviewId)
                        + "/comments?"
                        + pageQuery(page, perPage),
                null,
                listType(ReviewComment.class));
    }

    /**
     * Lists one page of a pull request's general comments, those GitHub keeps on its issue; with a
     * time, only those changed at or after it.
     */
    public List<IssueComment> issueComments(
            String token,
            String owner,
            String repo,
            long number,
            @Nullable Instant since,
            int page,
            int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/issues/"
                        + positive(number)
                        + "/comments?"
                        + (since == null ? "" : "since=" + segment(since.toString()) + "&")
                        + pageQuery(page, perPage),
                null,
                listType(IssueComment.class));
    }

    /** Writes a general comment on a pull request. */
    public IssueComment createIssueComment(
            String token, String owner, String repo, long number, IssuesUpdateCommentRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/issues/" + positive(number) + "/comments",
                body,
                json.constructType(IssueComment.class));
    }

    /** Changes the text of a general comment. */
    public IssueComment updateIssueComment(
            String token,
            String owner,
            String repo,
            long commentId,
            IssuesUpdateCommentRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "PATCH",
                repo(owner, repo) + "/issues/comments/" + positive(commentId),
                body,
                json.constructType(IssueComment.class));
    }

    /** Deletes a general comment. */
    public void deleteIssueComment(String token, String owner, String repo, long commentId) {
        delete(token, repo(owner, repo) + "/issues/comments/" + positive(commentId));
    }

    /**
     * Compares two commits; its merge base is the old side of a pull request's diff. Only the first
     * commit and file are asked for, since the answer lists both and the merge base needs neither.
     */
    public CommitComparison compare(
            String token, String owner, String repo, String base, String head) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/compare/"
                        + segment(base)
                        + "..."
                        + segment(head)
                        + "?page=1&per_page=1",
                null,
                json.constructType(CommitComparison.class));
    }

    /** Lists one page of check runs for a commit reference. */
    public ChecksListForRef200Response checkRuns(
            String token, String owner, String repo, String ref, int page, int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/commits/"
                        + segments(ref)
                        + "/check-runs?"
                        + pageQuery(page, perPage),
                null,
                json.constructType(ChecksListForRef200Response.class));
    }

    /** Creates a check run using a GitHub App installation token. */
    public CheckRun createCheckRun(
            String token, String owner, String repo, ChecksCreateRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/check-runs",
                body,
                json.constructType(CheckRun.class));
    }

    /** Updates a check run using a GitHub App installation token. */
    public CheckRun updateCheckRun(
            String token, String owner, String repo, long checkRunId, ChecksUpdateRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "PATCH",
                repo(owner, repo) + "/check-runs/" + positive(checkRunId),
                body,
                json.constructType(CheckRun.class));
    }

    /** Lists one page of Actions runs for a commit. */
    public ActionsListWorkflowRunsForRepo200Response runs(
            String token, String owner, String repo, String sha, int page, int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/actions/runs?head_sha="
                        + segment(sha)
                        + paging(page, perPage),
                null,
                json.constructType(ActionsListWorkflowRunsForRepo200Response.class));
    }

    /** Lists one page of jobs in an Actions run. */
    public ActionsListJobsForWorkflowRun200Response jobs(
            String token, String owner, String repo, long runId, int page, int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/actions/runs/"
                        + positive(runId)
                        + "/jobs?"
                        + pageQuery(page, perPage),
                null,
                json.constructType(ActionsListJobsForWorkflowRun200Response.class));
    }

    /** Reads a git reference such as {@code heads/main}, encoding each of its segments. */
    public GitRef ref(String token, String owner, String repo, String ref) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/git/ref/" + segments(ref),
                null,
                json.constructType(GitRef.class));
    }

    /** Reads the authenticated public or private account, as selected by GitHub. */
    public UsersGetAuthenticated200Response user(String token) {
        return call(
                token,
                "GET",
                "user",
                null,
                json.constructType(UsersGetAuthenticated200Response.class));
    }

    /** Reads the authenticated account's membership in an organization. */
    public OrgMembership membership(String token, String org) {
        return call(
                token,
                "GET",
                "user/memberships/orgs/" + segment(org),
                null,
                json.constructType(OrgMembership.class));
    }

    /** Reads an account's membership in an organization: an owner is an active {@code admin}. */
    public OrgMembership orgMembership(String token, String org, String login) {
        return call(
                token,
                "GET",
                "orgs/" + segment(org) + "/memberships/" + segment(login),
                null,
                json.constructType(OrgMembership.class));
    }

    /** Lists one page of an account's public SSH authentication keys, without a token. */
    public List<KeySimple> keys(String login, int page, int perPage) {
        return exchange(
                null,
                "GET",
                "users/" + segment(login) + "/keys?" + pageQuery(page, perPage),
                null,
                listType(KeySimple.class));
    }

    /** Lists one page of an account's public SSH signing keys, without a token. */
    public List<SshSigningKey> signingKeys(String login, int page, int perPage) {
        return exchange(
                null,
                "GET",
                "users/" + segment(login) + "/ssh_signing_keys?" + pageQuery(page, perPage),
                null,
                listType(SshSigningKey.class));
    }

    /**
     * Reads an account's public SSH authentication keys without a token, as the lines of GitHub's
     * page {@code <login>.keys} beside the API, which leaves out the keys' titles. A missing login
     * is a {@link GitHubException} with status 404; no failure reads as an empty list.
     */
    public List<String> plainKeys(String login) {
        URI uri = web.resolve(segment(login) + ".keys");
        GitHubKeyPage page = new GitHubKeyPage();
        byte[] bytes;
        try {
            // The body is read inside send, so the request timeout covers it too.
            bytes =
                    http.send(
                                    HttpRequest.newBuilder(uri)
                                            .timeout(Duration.ofSeconds(60))
                                            .header("Accept", "text/plain")
                                            .GET()
                                            .build(),
                                    page)
                            .body();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubException(0, "GitHub request interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            // A transport may fail the exchange once the page stops reading; its verdict stands.
            GitHubException verdict = page.verdict;
            throw verdict != null ? verdict : new GitHubException(0, "GitHub request failed");
        }
        GitHubException verdict = page.verdict;
        if (verdict != null) {
            throw verdict;
        }
        return new String(bytes, StandardCharsets.UTF_8)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    /** Lists one page of the authenticated account's own SSH authentication keys. */
    public List<Key> ownKeys(String token, int page, int perPage) {
        return call(
                token, "GET", "user/keys?" + pageQuery(page, perPage), null, listType(Key.class));
    }

    /**
     * Adds an SSH authentication key to the authenticated account; a key GitHub already has is
     * refused with its status, and the caller finds it in {@link #ownKeys}.
     */
    public Key addKey(String token, UsersCreatePublicSshKeyForAuthenticatedUserRequest body) {
        Objects.requireNonNull(body, "body");
        return call(token, "POST", "user/keys", body, json.constructType(Key.class));
    }

    /** Deletes one of the authenticated account's own SSH authentication keys. */
    public void deleteKey(String token, long keyId) {
        delete(token, "user/keys/" + positive(keyId));
    }

    /** Lists one page of the authenticated account's own SSH signing keys. */
    public List<SshSigningKey> ownSigningKeys(String token, int page, int perPage) {
        return call(
                token,
                "GET",
                "user/ssh_signing_keys?" + pageQuery(page, perPage),
                null,
                listType(SshSigningKey.class));
    }

    /**
     * Adds an SSH signing key to the authenticated account; a key GitHub already has is refused
     * with its status, and the caller finds it in {@link #ownSigningKeys}.
     */
    public SshSigningKey addSigningKey(
            String token, UsersCreateSshSigningKeyForAuthenticatedUserRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                "user/ssh_signing_keys",
                body,
                json.constructType(SshSigningKey.class));
    }

    /** Deletes one of the authenticated account's own SSH signing keys. */
    public void deleteSigningKey(String token, long keyId) {
        delete(token, "user/ssh_signing_keys/" + positive(keyId));
    }

    /** Reads a repository. */
    public FullRepository repository(String token, String owner, String repo) {
        return call(
                token, "GET", repo(owner, repo), null, json.constructType(FullRepository.class));
    }

    /** Creates a repository in an organization. */
    public FullRepository createRepository(String token, String org, ReposCreateInOrgRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                "orgs/" + segment(org) + "/repos",
                body,
                json.constructType(FullRepository.class));
    }

    /**
     * Reads a commit's combined status, with one page of its statuses, the newest of each context.
     */
    public CombinedCommitStatus status(
            String token, String owner, String repo, String ref, int page, int perPage) {
        return call(
                token,
                "GET",
                repo(owner, repo)
                        + "/commits/"
                        + segments(ref)
                        + "/status?"
                        + pageQuery(page, perPage),
                null,
                json.constructType(CombinedCommitStatus.class));
    }

    /** Reads a collaborator's repository permission. */
    public RepositoryCollaboratorPermission permission(
            String token, String owner, String repo, String username) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/collaborators/" + segment(username) + "/permission",
                null,
                json.constructType(RepositoryCollaboratorPermission.class));
    }

    <T> T graphQl(String token, Object body, Class<T> type) {
        return graphQl(token, body, type, false);
    }

    /**
     * Reads one node by its ID: an ID GitHub does not know, answered with a null node and a {@code
     * NOT_FOUND} error on it, and one of another kind, answered with an empty node, read as a null
     * node.
     */
    <T> T graphQlNode(String token, Object body, Class<T> type) {
        return graphQl(token, body, type, true);
    }

    private <T> T graphQl(String token, Object body, Class<T> type, boolean node) {
        JsonNode envelope =
                call(
                        token,
                        "POST",
                        "graphql",
                        Objects.requireNonNull(body, "body"),
                        json.constructType(JsonNode.class));
        if (node && envelope.path("data") instanceof ObjectNode data) {
            JsonNode found = data.path("node");
            if (found.isObject() && found.isEmpty()) data.putNull("node");
            if (found.isNull()
                    && envelope instanceof ObjectNode object
                    && object.path("errors")
                            instanceof com.fasterxml.jackson.databind.node.ArrayNode list) {
                list.removeIf(
                        error ->
                                error.path("type").asText().equals("NOT_FOUND")
                                        && error.path("path").size() == 1
                                        && error.path("path").path(0).asText().equals("node"));
            }
        }
        JsonNode errors = envelope.path("errors");
        // An empty or null list of errors is the absence of errors.
        if (envelope instanceof ObjectNode object
                && (errors.isNull() || (errors.isArray() && errors.isEmpty()))) {
            object.remove("errors");
        }
        if (envelope.has("errors")) {
            JsonNode message = envelope.path("errors").path(0).path("message");
            throw new GitHubException(
                    200,
                    GitHubJson.safe(
                            message.isTextual() ? message.asText() : "GraphQL request failed",
                            List.of(token)));
        }
        return GitHubJson.convert(json, envelope, json.constructType(type), List.of(token));
    }

    /**
     * Reads a job log, following at most five redirects and stripping authentication off origin.
     */
    public String jobLogs(String token, String owner, String repo, long jobId) {
        Objects.requireNonNull(token, "token");
        URI uri = base.resolve(repo(owner, repo) + "/actions/jobs/" + positive(jobId) + "/logs");
        boolean authenticate = true;
        for (int redirects = 0; redirects <= 5; redirects++) {
            HttpResponse<String> response = send(token, "GET", uri, null, authenticate);
            if (List.of(301, 302, 303, 307, 308).contains(response.statusCode())) {
                if (redirects == 5) {
                    throw new GitHubException(response.statusCode(), "Too many log redirects");
                }
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(
                                        () ->
                                                new GitHubException(
                                                        response.statusCode(),
                                                        "Log redirect has no Location"));
                try {
                    URI previous = uri;
                    uri = uri.resolve(location);
                    validateUri(uri);
                    // A server that echoes the token into a foreign address, raw or
                    // percent-encoded, would leak it there.
                    if (!sameOrigin(base, uri)
                            && (uri.toASCIIString().contains(token)
                                    || uri.toString().contains(token)
                                    || (uri.getQuery() != null && uri.getQuery().contains(token))
                                    || (uri.getPath() != null && uri.getPath().contains(token)))) {
                        throw new IllegalArgumentException();
                    }
                    if (uri.getRawFragment() != null
                            || (previous.getScheme().equalsIgnoreCase("https")
                                    && !uri.getScheme().equalsIgnoreCase("https"))) {
                        throw new IllegalArgumentException();
                    }
                    authenticate = authenticate && sameOrigin(base, uri);
                } catch (IllegalArgumentException ex) {
                    throw new GitHubException(response.statusCode(), "Invalid log redirect URI");
                }
            } else {
                check(response, List.of(token));
                return response.body();
            }
        }
        throw new GitHubException(0, "Too many log redirects");
    }

    private <T> T call(
            String token, String method, String path, @Nullable Object body, JavaType type) {
        return exchange(Objects.requireNonNull(token, "token"), method, path, body, type);
    }

    /** Calls the API; a null token sends none. */
    private <T> T exchange(
            @Nullable String token,
            String method,
            String path,
            @Nullable Object body,
            JavaType type) {
        HttpResponse<String> response = send(token, method, base.resolve(path), body, true);
        List<String> credentials = new ArrayList<>();
        if (token != null) {
            credentials.add(token);
        }
        if (body != null) {
            collectCredentials(json.valueToTree(body), credentials);
        }
        credentials.sort(java.util.Comparator.comparingInt(String::length).reversed());
        check(response, credentials);
        return GitHubJson.read(json, response.body(), type, credentials);
    }

    private void delete(String token, String path) {
        Objects.requireNonNull(token, "token");
        HttpResponse<String> response = send(token, "DELETE", base.resolve(path), null, true);
        check(response, List.of(token));
    }

    private HttpResponse<String> send(
            @Nullable String token,
            String method,
            URI uri,
            @Nullable Object body,
            boolean authenticate) {
        if (token != null && (token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126))) {
            throw new IllegalArgumentException("Invalid access token");
        }
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2026-03-10");
            if (token != null && authenticate && sameOrigin(base, uri)) {
                request.header("Authorization", "Bearer " + token);
            }
            if (body != null) {
                request.header("Content-Type", "application/json");
                request.method(
                        method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
            } else {
                request.method(method, HttpRequest.BodyPublishers.noBody());
            }
            return http.send(
                    request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new GitHubException(0, "GitHub request interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            // Transport exceptions can include URLs, headers and credential-bearing input.
            throw new GitHubException(0, "GitHub request failed");
        }
    }

    private void check(HttpResponse<String> response, List<String> credentials) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "GitHub request failed";
            try {
                var value = json.readTree(response.body());
                if (value != null && value.path("message").isTextual()) {
                    message = GitHubJson.safe(value.path("message").asText(), credentials);
                }
            } catch (JsonProcessingException ex) {
                // A non-JSON error page is not safe to include in an exception.
            }
            throw new GitHubException(response.statusCode(), message);
        }
    }

    private static void collectCredentials(JsonNode value, List<String> credentials) {
        if (value.isObject()) {
            for (var field : value.properties()) {
                if (field.getValue().isTextual()
                        && Set.of(
                                        "secret",
                                        "token",
                                        "password",
                                        "authorization",
                                        "authorization_header")
                                .contains(field.getKey().toLowerCase(Locale.ROOT))) {
                    credentials.add(field.getValue().asText());
                } else {
                    collectCredentials(field.getValue(), credentials);
                }
            }
        } else if (value.isArray()) {
            for (JsonNode element : value) {
                collectCredentials(element, credentials);
            }
        }
    }

    private JavaType listType(Class<?> element) {
        return json.getTypeFactory().constructCollectionType(List.class, element);
    }

    private static String repo(String owner, String repo) {
        return "repos/" + segment(owner) + "/" + segment(repo);
    }

    private static String pullPath(String owner, String repo, long index) {
        return repo(owner, repo) + "/pulls/" + positive(index);
    }

    private static String paging(int page, int limit) {
        return "&" + pageQuery(page, limit);
    }

    private static String pageQuery(int page, int limit) {
        return "page=" + positive(page) + "&per_page=" + positive(limit);
    }

    private static long positive(long value) {
        if (value <= 0) {
            throw new IllegalArgumentException("An identifier, page or limit must be positive");
        }
        return value;
    }

    private static String segment(String value) {
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            throw new IllegalArgumentException(
                    "An API path or query value must not be blank or a dot segment");
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8)
                .replace("+", "%20")
                .replace(".", "%2E");
    }

    /** Encodes each segment of a multi-segment reference; empty and dot segments are refused. */
    private static String segments(String value) {
        List<String> encoded = new ArrayList<>();
        for (String part : value.split("/", -1)) {
            encoded.add(segment(part));
        }
        return String.join("/", encoded);
    }

    private static URI root(URI uri, String name) {
        validateUri(uri);
        if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException("The " + name + " must have no query or fragment");
        }
        return URI.create(uri.toASCIIString().replaceAll("/+$", "") + "/");
    }

    private static void validateUri(URI uri) {
        if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null
                || uri.getRawUserInfo() != null) {
            throw new IllegalArgumentException(
                    "Expected an HTTP URI with a host and no user information");
        }
    }

    private static boolean sameOrigin(URI left, URI right) {
        return left.getScheme().equalsIgnoreCase(right.getScheme())
                && left.getHost().equalsIgnoreCase(right.getHost())
                && port(left) == port(right);
    }

    private static int port(URI uri) {
        return uri.getPort() == -1
                ? (uri.getScheme().equalsIgnoreCase("https") ? 443 : 80)
                : uri.getPort();
    }
}
