package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
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
import java.util.regex.Pattern;
import org.fuin.porelo.forge.forgejo.model.*;
import org.jspecify.annotations.Nullable;

/**
 * Synchronous Forgejo 17 API calls. The caller owns the transport and supplies a token per call.
 */
public final class ForgejoClient {
    private static final int MAX_PLAIN_KEYS_BYTES = 256 * 1024;
    private static final Pattern NEXT_LINK =
            Pattern.compile("rel=\"?next\"?(\\s|;|,|$)", Pattern.CASE_INSENSITIVE);
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
    public Page<PullRequest> pulls(
            String token, String owner, String repo, String state, int page, int limit) {
        return page(
                token,
                repo(owner, repo) + "/pulls?state=" + segment(state) + paging(page, limit),
                PullRequest.class,
                page,
                limit);
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

    /**
     * Lists one page of a pull request's commits with their signature verdicts and without their
     * files. Forgejo answers at most its configured maximum per page, 50 by default, whatever the
     * limit asks; the page's {@code more} and {@code total} still tell the rest.
     */
    public Page<Commit> pullCommits(
            String token, String owner, String repo, long index, int page, int limit) {
        return page(
                token,
                pullPath(owner, repo, index)
                        + "/commits?verification=true&files=false"
                        + paging(page, limit),
                Commit.class,
                page,
                limit);
    }

    /** Lists one page of reviews. */
    public Page<PullReview> reviews(
            String token, String owner, String repo, long index, int page, int limit) {
        return page(
                token,
                pullPath(owner, repo, index) + "/reviews?" + pageQuery(page, limit),
                PullReview.class,
                page,
                limit);
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

    /**
     * Reads the open pull request from a head branch into a base branch; a pair with none is a
     * {@link ForgejoException} with status 404.
     */
    public PullRequest pullOfBranches(
            String token, String owner, String repo, String base, String head) {
        return call(
                token,
                "GET",
                repo(owner, repo) + "/pulls/" + segment(base) + "/" + segment(head),
                null,
                json.constructType(PullRequest.class));
    }

    /**
     * Adds a comment to a review; Forgejo takes one on a submitted review too, even another
     * account's.
     */
    public PullReviewComment createReviewComment(
            String token,
            String owner,
            String repo,
            long index,
            long reviewId,
            CreatePullReviewComment body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                reviewPath(owner, repo, index, reviewId) + "/comments",
                body,
                json.constructType(PullReviewComment.class));
    }

    /** Deletes a review's comment. */
    public void deleteReviewComment(
            String token, String owner, String repo, long index, long reviewId, long id) {
        delete(token, reviewPath(owner, repo, index, reviewId) + "/comments/" + positive(id));
    }

    /**
     * Reads a pull request's general comments, all in one answer, since Forgejo pages none; only
     * those updated since the given time when it is given.
     */
    public List<Comment> issueComments(
            String token, String owner, String repo, long index, @Nullable Instant since) {
        return call(
                token,
                "GET",
                issuePath(owner, repo, index)
                        + "/comments"
                        + (since == null ? "" : "?since=" + segment(since.toString())),
                null,
                listType(Comment.class));
    }

    /** Writes a general comment on a pull request. */
    public Comment createIssueComment(
            String token, String owner, String repo, long index, CreateIssueCommentOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                issuePath(owner, repo, index) + "/comments",
                body,
                json.constructType(Comment.class));
    }

    /** Edits a general comment, or a review comment by its id, which has no edit of its own. */
    public Comment editIssueComment(
            String token, String owner, String repo, long id, EditIssueCommentOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "PATCH",
                repo(owner, repo) + "/issues/comments/" + positive(id),
                body,
                json.constructType(Comment.class));
    }

    /**
     * Deletes a general comment. Forgejo answers a review comment's id with success and keeps the
     * comment; {@link #deleteReviewComment} deletes that.
     */
    public void deleteIssueComment(String token, String owner, String repo, long id) {
        delete(token, repo(owner, repo) + "/issues/comments/" + positive(id));
    }

    /** Reads the server's version, such as {@code 17.0.1}. */
    public ServerVersion version(String token) {
        return call(token, "GET", "version", null, json.constructType(ServerVersion.class));
    }

    /** Lists one page of commit statuses. */
    public Page<CommitStatus> statuses(
            String token, String owner, String repo, String sha, int page, int limit) {
        return page(
                token,
                repo(owner, repo) + "/statuses/" + segment(sha) + "?" + pageQuery(page, limit),
                CommitStatus.class,
                page,
                limit);
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
    public Page<ActionRun> runs(
            String token, String owner, String repo, String sha, int page, int limit) {
        Answered<ListActionRunResponse> answer =
                exchange(
                        token,
                        "GET",
                        repo(owner, repo)
                                + "/actions/runs?head_sha="
                                + segment(sha)
                                + paging(page, limit),
                        null,
                        json.constructType(ListActionRunResponse.class));
        List<ActionRun> runs = answer.value().workflowRuns();
        Long total = answer.value().totalCount();
        return new Page<>(
                runs == null ? List.of() : runs,
                more(answer.headers(), page, limit)
                        || (total != null && (long) page * limit < total),
                total);
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
    public Page<Hook> hooks(String token, String owner, String repo, int page, int limit) {
        return page(
                token,
                repo(owner, repo) + "/hooks?" + pageQuery(page, limit),
                Hook.class,
                page,
                limit);
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
    public Page<Organization> organizations(String token, int page, int limit) {
        return page(token, "user/orgs?" + pageQuery(page, limit), Organization.class, page, limit);
    }

    /** Lists one page of an account's public SSH keys; Forgejo asks for a token for it too. */
    public Page<PublicKey> keys(String token, String login, int page, int limit) {
        return page(
                token,
                "users/" + segment(login) + "/keys?" + pageQuery(page, limit),
                PublicKey.class,
                page,
                limit);
    }

    /**
     * Reads an account's public SSH keys without a token, as the lines of Forgejo's page {@code
     * <login>.keys} beside the API, which leaves out the keys' comments. A missing login is a
     * {@link ForgejoException} with status 404; no failure reads as an empty list.
     */
    public List<String> plainKeys(String login) {
        if (!base.getRawPath().endsWith("/api/v1/")) {
            throw new IllegalStateException(
                    "The API base must end in /api/v1/ to find the web root");
        }
        URI uri = base.resolve("../../" + segment(login) + ".keys");
        KeyPage page = new KeyPage();
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
            throw interrupted();
        } catch (IOException | IllegalArgumentException ex) {
            // A transport may fail the exchange once the page stops reading; its verdict stands.
            ForgejoException verdict = page.verdict;
            throw verdict != null ? verdict : failed(ex);
        }
        ForgejoException verdict = page.verdict;
        if (verdict != null) {
            throw verdict;
        }
        return new String(bytes, StandardCharsets.UTF_8)
                .lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .toList();
    }

    private static boolean isPlainText(String contentType) {
        int semicolon = contentType.indexOf(';');
        return (semicolon < 0 ? contentType : contentType.substring(0, semicolon))
                .strip()
                .equalsIgnoreCase("text/plain");
    }

    /**
     * Reads a key page: a refused answer is not read at all, and a plain text one only up to one
     * byte past its bound. Its verdict says why it stopped.
     */
    private static final class KeyPage implements HttpResponse.BodyHandler<byte[]> {
        volatile @Nullable ForgejoException verdict;

        @Override
        public HttpResponse.BodySubscriber<byte[]> apply(HttpResponse.ResponseInfo info) {
            if (info.statusCode() != 200) {
                verdict =
                        new ForgejoException(
                                info.statusCode(),
                                "Forgejo key page request failed",
                                Delivery.ANSWERED,
                                rateLimit(info.headers()));
                return BoundedBytes.refusing();
            }
            if (!isPlainText(info.headers().firstValue("Content-Type").orElse(""))) {
                verdict = new ForgejoException(200, "Forgejo key page is not plain text");
                return BoundedBytes.refusing();
            }
            // A transport that retries may call again; only this answer's verdict counts.
            verdict = null;
            return BoundedBytes.whole(
                    MAX_PLAIN_KEYS_BYTES,
                    () ->
                            verdict =
                                    new ForgejoException(
                                            200, "Forgejo key page exceeds its bound"));
        }
    }

    /** Lists one page of the authenticated account's own SSH keys. */
    public Page<PublicKey> ownKeys(String token, int page, int limit) {
        return page(token, "user/keys?" + pageQuery(page, limit), PublicKey.class, page, limit);
    }

    /** Adds an SSH key to the authenticated account; a key Forgejo already has is refused. */
    public PublicKey addKey(String token, CreateKeyOption body) {
        Objects.requireNonNull(body, "body");
        return call(token, "POST", "user/keys", body, json.constructType(PublicKey.class));
    }

    /** Deletes one of the authenticated account's own SSH keys. */
    public void deleteKey(String token, long id) {
        delete(token, "user/keys/" + positive(id));
    }

    /**
     * Reads an account's permissions in an organization. Forgejo 17 leaves out {@code is_owner} for
     * anyone but an owner, so {@code isOwner()} is null then.
     */
    public OrganizationPermissions organizationPermissions(String token, String login, String org) {
        return call(
                token,
                "GET",
                "users/" + segment(login) + "/orgs/" + segment(org) + "/permissions",
                null,
                json.constructType(OrganizationPermissions.class));
    }

    /** Creates a repository in an organization. */
    public Repository createRepository(String token, String org, CreateRepoOption body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                "orgs/" + segment(org) + "/repos",
                body,
                json.constructType(Repository.class));
    }

    /** Reads a repository. */
    public Repository repository(String token, String owner, String repo) {
        return call(token, "GET", repo(owner, repo), null, json.constructType(Repository.class));
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
     * Reads a job log, following at most five redirects and stripping authentication off origin. A
     * log is read up to 64 MiB, of which its last 1 MiB is kept, since a build reads a log's tail.
     */
    public JobLog jobLogs(String token, String owner, String repo, long jobId) {
        URI uri = base.resolve(repo(owner, repo) + "/actions/jobs/" + positive(jobId) + "/logs");
        boolean authenticate = true;
        for (int redirects = 0; redirects <= 5; redirects++) {
            BoundedAnswer answer = BoundedAnswer.log();
            HttpResponse<String> response = send(token, "GET", uri, null, authenticate, answer);
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
                return new JobLog(response.body(), answer.cut());
            }
        }
        throw new ForgejoException(0, "Too many log redirects");
    }

    private <T> T call(
            String token, String method, String path, @Nullable Object body, JavaType type) {
        Answered<T> answer = exchange(token, method, path, body, type);
        return answer.value();
    }

    private <T> Page<T> page(String token, String path, Class<T> element, int page, int limit) {
        Answered<List<T>> answer = exchange(token, "GET", path, null, listType(element));
        return new Page<>(
                answer.value(), more(answer.headers(), page, limit), total(answer.headers()));
    }

    /**
     * Forgejo says when more follows, links a next page when there is one and counts the whole
     * list; a full page without any of them is the last one. The count alone reckons with the limit
     * asked, which a limit above Forgejo's maximum makes too large.
     */
    private static boolean more(HttpHeaders headers, int page, int limit) {
        if (headers.firstValue("x-hasmore").orElse("").strip().equals("true")) {
            return true;
        }
        for (String value : headers.allValues("link")) {
            if (NEXT_LINK.matcher(value).find()) {
                return true;
            }
        }
        Long total = total(headers);
        return total != null && (long) page * limit < total;
    }

    private static @Nullable Long total(HttpHeaders headers) {
        String total = headers.firstValue("x-total-count").orElse("").strip();
        return total.matches("[0-9]{1,18}") ? Long.valueOf(total) : null;
    }

    private <T> Answered<T> exchange(
            String token, String method, String path, @Nullable Object body, JavaType type) {
        HttpResponse<String> response =
                send(token, method, base.resolve(path), body, true, BoundedAnswer.json());
        List<String> credentials = new ArrayList<>();
        credentials.add(token);
        if (body != null) {
            collectCredentials(json.valueToTree(body), credentials);
        }
        credentials.sort(java.util.Comparator.comparingInt(String::length).reversed());
        check(response, credentials);
        try {
            T value = ForgejoJson.read(json, response.body(), type, credentials);
            return new Answered<>(value, response.headers());
        } catch (ForgejoException undecodable) {
            throw new ForgejoException(
                    undecodable.status(),
                    Objects.requireNonNull(undecodable.getMessage()),
                    Delivery.ANSWERED,
                    null);
        }
    }

    private HttpResponse<String> send(
            String token,
            String method,
            URI uri,
            @Nullable Object body,
            boolean authenticate,
            BoundedAnswer answer) {
        if (token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126)) {
            throw new IllegalArgumentException("Invalid access token");
        }
        HttpResponse<String> response;
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
            response = http.send(request.build(), answer);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw interrupted();
        } catch (JsonProcessingException unwritable) {
            throw new ForgejoException(
                    0, "Forgejo request could not be written", Delivery.NOT_SENT, null);
        } catch (IOException | IllegalArgumentException ex) {
            // A transport may fail the exchange once the answer stops reading; its verdict stands.
            ForgejoException exceeded = answer.exceeded();
            throw exceeded != null ? exceeded : failed(ex);
        }
        ForgejoException exceeded = answer.exceeded();
        if (exceeded != null) {
            throw exceeded;
        }
        return response;
    }

    private static ForgejoException interrupted() {
        return new ForgejoException(
                0, "Forgejo request interrupted", Delivery.SENT_UNANSWERED, null);
    }

    /**
     * The JDK does not say whether bytes left before a failure, so anything after the connection
     * opened counts as sent. The message stays fixed: transport exceptions can include URLs,
     * headers and credential-bearing input.
     */
    private static ForgejoException failed(Exception ex) {
        boolean notSent = ex instanceof IllegalArgumentException || neverConnected(ex);
        return new ForgejoException(
                0,
                "Forgejo request failed",
                notSent ? Delivery.NOT_SENT : Delivery.SENT_UNANSWERED,
                null);
    }

    private static boolean neverConnected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Reads what an answer says of a rate limit. */
    static @Nullable RateLimit rateLimit(HttpHeaders headers) {
        return RateLimit.read(headers, Instant.now());
    }

    private record Answered<T>(T value, HttpHeaders headers) {}

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
            throw new ForgejoException(
                    response.statusCode(),
                    message,
                    Delivery.ANSWERED,
                    rateLimit(response.headers()));
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

    private void delete(String token, String path) {
        HttpResponse<String> response =
                send(token, "DELETE", base.resolve(path), null, true, BoundedAnswer.json());
        check(response, List.of(token));
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

    private static String issuePath(String owner, String repo, long index) {
        return repo(owner, repo) + "/issues/" + positive(index);
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
