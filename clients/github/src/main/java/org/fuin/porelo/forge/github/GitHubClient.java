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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.fuin.porelo.forge.github.model.*;
import org.jspecify.annotations.Nullable;

/** Synchronous GitHub API calls. The caller owns the transport and supplies a token per call. */
public final class GitHubClient {
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json;

    /** Creates a lenient reader with an API base such as {@code https://api.github.com/}. */
    public GitHubClient(URI base, HttpClient http) {
        this(base, http, Strictness.LENIENT);
    }

    /** Creates a reader; the supplied transport must never follow redirects automatically. */
    public GitHubClient(URI base, HttpClient http, Strictness strictness) {
        validateUri(base);
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("The API base must have no query or fragment");
        }
        this.base = URI.create(base.toASCIIString().replaceAll("/+$", "") + "/");
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
        JsonNode envelope =
                call(
                        token,
                        "POST",
                        "graphql",
                        Objects.requireNonNull(body, "body"),
                        json.constructType(JsonNode.class));
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
        HttpResponse<String> response = send(token, method, base.resolve(path), body, true);
        List<String> credentials = new ArrayList<>();
        credentials.add(token);
        if (body != null) {
            collectCredentials(json.valueToTree(body), credentials);
        }
        credentials.sort(java.util.Comparator.comparingInt(String::length).reversed());
        check(response, credentials);
        return GitHubJson.read(json, response.body(), type, credentials);
    }

    private HttpResponse<String> send(
            String token, String method, URI uri, @Nullable Object body, boolean authenticate) {
        if (token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Invalid access token");
        }
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2026-03-10");
            if (authenticate && sameOrigin(base, uri)) {
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
