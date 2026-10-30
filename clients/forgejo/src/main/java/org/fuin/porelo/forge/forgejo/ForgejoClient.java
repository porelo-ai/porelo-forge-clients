package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.fuin.porelo.forge.forgejo.model.*;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous Forgejo 17 API calls. The caller owns the transport and supplies a token per call.
 */
public final class ForgejoClient {
    private final URI base;
    private final HttpClient http;
    private final ObjectMapper json;

    /** Creates a lenient reader with an API base such as {@code https://example.org/api/v1/}. */
    public ForgejoClient(URI base, HttpClient http) {
        this(base, http, Strictness.LENIENT);
    }

    /** Creates a reader; the supplied transport must never follow redirects automatically. */
    public ForgejoClient(URI base, HttpClient http, Strictness strictness) {
        validateUri(base);
        if (base.getRawQuery() != null || base.getRawFragment() != null) {
            throw new IllegalArgumentException("The API base must have no query or fragment");
        }
        this.base = URI.create(base.toASCIIString().replaceAll("/+$", "") + "/");
        this.http = Objects.requireNonNull(http);
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("The HTTP client must use Redirect.NEVER");
        }
        json = ForgejoJson.mapper(strictness);
    }

    /** Lists one page of pull requests in the requested state. */
    public List<PullRequest> pulls(
            String token, String owner, String repo, String state, int page, int limit) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/pulls?state=" + segment(state) + paging(page, limit),
                null,
                listType(PullRequest.class));
    }

    /** Creates a pull request. */
    public PullRequest createPull(
            String token, String owner, String repo, CreatePullRequestOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/pulls",
                body,
                json.constructType(PullRequest.class));
    }

    /** Reads a pull request. */
    public PullRequest pull(String token, String owner, String repo, long index) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, index),
                null,
                json.constructType(PullRequest.class));
    }

    /** Lists one page of reviews. */
    public List<PullReview> reviews(
            String token, String owner, String repo, long index, int page, int limit) {
        return call(
                token,
                "GET",
                pullPath(owner, repo, index) + "/reviews?" + pageQuery(page, limit),
                null,
                listType(PullReview.class));
    }

    /** Creates a review with its optional inline comments. */
    public PullReview createReview(
            String token, String owner, String repo, long index, CreatePullReviewOptions body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                pullPath(owner, repo, index) + "/reviews",
                body,
                json.constructType(PullReview.class));
    }

    /** Reads the comments of a review. */
    public List<PullReviewComment> reviewComments(
            String token, String owner, String repo, long index, long id) {
        return call(
                token,
                "GET",
                reviewPath(owner, repo, index, id) + "/comments",
                null,
                listType(PullReviewComment.class));
    }

    /** Dismisses a review verdict; repository administration permission is required. */
    public PullReview dismissReview(
            String token,
            String owner,
            String repo,
            long index,
            long id,
            DismissPullReviewOptions body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                reviewPath(owner, repo, index, id) + "/dismissals",
                body,
                json.constructType(PullReview.class));
    }

    /** Restores a dismissed review verdict. */
    public PullReview undismissReview(
            String token, String owner, String repo, long index, long id) {
        return call(
                token,
                "POST",
                reviewPath(owner, repo, index, id) + "/undismissals",
                null,
                json.constructType(PullReview.class));
    }

    /** Lists one page of commit statuses. */
    public List<CommitStatus> statuses(
            String token, String owner, String repo, String sha, int page, int limit) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/statuses/" + segment(sha) + "?" + pageQuery(page, limit),
                null,
                listType(CommitStatus.class));
    }

    /** Creates a commit status. */
    public CommitStatus createStatus(
            String token, String owner, String repo, String sha, CreateStatusOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/statuses/" + segment(sha),
                body,
                json.constructType(CommitStatus.class));
    }

    /** Lists one page of Actions runs for a commit. */
    public ListActionRunResponse runs(
            String token, String owner, String repo, String sha, int page, int limit) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/actions/runs?head_sha=" + segment(sha) + paging(page, limit),
                null,
                json.constructType(ListActionRunResponse.class));
    }

    /** Reads the jobs of an Actions run; Forgejo returns a JSON array. */
    public List<ActionRunJob> jobs(String token, String owner, String repo, long runId) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/actions/runs/" + positive(runId) + "/jobs",
                null,
                listType(ActionRunJob.class));
    }

    /** Reads a branch, encoding its name as one path segment. */
    public Branch branch(String token, String owner, String repo, String branch) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/branches/" + segment(branch),
                null,
                json.constructType(Branch.class));
    }

    /** Lists one page of repository webhooks. */
    public List<Hook> hooks(String token, String owner, String repo, int page, int limit) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/hooks?" + pageQuery(page, limit),
                null,
                listType(Hook.class));
    }

    /** Creates a repository webhook. */
    public Hook createHook(String token, String owner, String repo, CreateHookOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token, "POST", repo(owner, repo) + "/hooks", body, json.constructType(Hook.class));
    }

    /** Reads the authenticated account. */
    public User user(String token) {
        return call(token, "GET", "user", null, json.constructType(User.class));
    }

    /** Lists one page of the authenticated account’s organizations. */
    public List<Organization> organizations(String token, int page, int limit) {
        return call(
                token,
                "GET",
                "user/orgs?" + pageQuery(page, limit),
                null,
                listType(Organization.class));
    }

    /** Reads a collaborator’s repository permission. */
    public RepoCollaboratorPermission permission(
            String token, String owner, String repo, String collaborator) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/collaborators/" + segment(collaborator) + "/permission",
                null,
                json.constructType(RepoCollaboratorPermission.class));
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
                    throw new ForgejoException(response.statusCode(), "Too many log redirects");
                }
                String location =
                        response.headers()
                                .firstValue("Location")
                                .orElseThrow(
                                        () ->
                                                new ForgejoException(
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
                    throw new ForgejoException(response.statusCode(), "Invalid log redirect URI");
                }
            } else {
                check(response, List.of(token));
                return response.body();
            }
        }
        throw new ForgejoException(0, "Too many log redirects");
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
        return ForgejoJson.read(json, response.body(), type, credentials);
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
                            .header("Accept", "application/json");
            if (authenticate && sameOrigin(base, uri)) {
                request.header("Authorization", "token " + token);
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
            throw new ForgejoException(0, "Forgejo request interrupted");
        } catch (IOException | IllegalArgumentException ex) {
            // Transport exceptions can include URLs, headers and credential-bearing input.
            throw new ForgejoException(0, "Forgejo request failed");
        }
    }

    private void check(HttpResponse<String> response, List<String> credentials) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = "Forgejo request failed";
            try {
                var value = json.readTree(response.body());
                if (value != null && value.path("message").isTextual()) {
                    message = ForgejoJson.safe(value.path("message").asText(), credentials);
                }
            } catch (JsonProcessingException ex) {
                // A non-JSON error page is not safe to include in an exception.
            }
            throw new ForgejoException(response.statusCode(), message);
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

    private static String reviewPath(String owner, String repo, long index, long id) {
        return pullPath(owner, repo, index) + "/reviews/" + positive(id);
    }

    private static String paging(int page, int limit) {
        return "&" + pageQuery(page, limit);
    }

    private static String pageQuery(int page, int limit) {
        return "page=" + positive(page) + "&limit=" + positive(limit);
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
