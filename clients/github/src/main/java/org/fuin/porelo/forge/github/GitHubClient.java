package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.fuin.porelo.forge.github.model.*;
import org.jspecify.annotations.Nullable;

/** Synchronous GitHub API calls. The caller owns the transport and supplies a token per call. */
public final class GitHubClient {
    static final URI WEB = URI.create("https://github.com/");
    private static final Pattern NEXT_LINK =
            Pattern.compile("rel=\"?next\"?(\\s|;|,|$)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK = Pattern.compile("<([^>]*)>([^,<]*)");

    /** Visible ASCII, with spaces only inside, as a weak tag such as {@code W/"abc"} allows. */
    private static final Pattern ENTITY_TAG =
            Pattern.compile("[\\x21-\\x7e]([\\x20-\\x7e]*[\\x21-\\x7e])?");

    private static final Pattern ACCEPTED_PERMISSIONS =
            Pattern.compile("[a-z_]+=[a-z_]+(\\s*[;,]\\s*[a-z_]+=[a-z_]+)*");

    /** The timeout each request carries, as the JDK applies it, when the caller names none. */
    public static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(60);

    /**
     * How long to wait after a rate limit whose answer names no wait, when the caller names none.
     */
    public static final Duration DEFAULT_RATE_LIMIT_DELAY = Duration.ofMinutes(1);

    private final URI base;
    private final URI web;
    private final HttpClient http;
    private final ObjectMapper json;
    private final Duration requestTimeout;
    private final Duration rateLimitDelay;

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
        this(base, web, http, strictness, DEFAULT_REQUEST_TIMEOUT, DEFAULT_RATE_LIMIT_DELAY);
    }

    /**
     * Creates a reader with its own time limits; the supplied transport must never follow redirects
     * automatically.
     *
     * @param requestTimeout the timeout each request carries, as the JDK applies it
     * @param rateLimitDelay how long to wait after a rate limit whose answer names no wait
     */
    public GitHubClient(
            URI base,
            URI web,
            HttpClient http,
            Strictness strictness,
            Duration requestTimeout,
            Duration rateLimitDelay) {
        this.requestTimeout = requirePositive(requestTimeout, "request timeout");
        this.rateLimitDelay = requirePositive(rateLimitDelay, "rate-limit delay");
        this.base = root(base, "API base");
        this.web = root(web, "web base");
        this.http = Objects.requireNonNull(http);
        if (http.followRedirects() != HttpClient.Redirect.NEVER) {
            throw new IllegalArgumentException("The HTTP client must use Redirect.NEVER");
        }
        json = GitHubJson.mapper(strictness);
    }

    private static Duration requirePositive(Duration duration, String name) {
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException("The " + name + " must be positive");
        }
        return duration;
    }

    /** Lists one page of pull requests in the requested state. */
    public Page<PullRequestSimple> pulls(
            String token, String owner, String repo, String state, int page, int perPage) {
        return page(
                token,
                repo(owner, repo) + "/pulls?state=" + segment(state) + paging(page, perPage),
                PullRequestSimple.class);
    }

    /**
     * Reads the pull requests in a state whose head is a branch, named {@code owner:branch} as
     * GitHub names a head of another fork.
     */
    public Page<PullRequestSimple> pullsOfHead(
            String token,
            String owner,
            String repo,
            String head,
            String state,
            int page,
            int perPage) {
        return page(
                token,
                repo(owner, repo)
                        + "/pulls?state="
                        + segment(state)
                        + "&head="
                        + segment(head)
                        + paging(page, perPage),
                PullRequestSimple.class);
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

    /**
     * Lists one page of a pull request's commits. GitHub's description says the list stops at 250
     * commits, so a caller that needs more reads the branch's commits instead.
     */
    public Page<Commit> pullCommits(
            String token, String owner, String repo, long number, int page, int perPage) {
        return page(
                token,
                pullPath(owner, repo, number) + "/commits?" + pageQuery(page, perPage),
                Commit.class);
    }

    /** Lists one page of reviews. */
    public Page<PullRequestReview> reviews(
            String token, String owner, String repo, long number, int page, int perPage) {
        return page(
                token,
                pullPath(owner, repo, number) + "/reviews?" + pageQuery(page, perPage),
                PullRequestReview.class);
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
    public Page<PullRequestReviewComment> reviewComments(
            String token, String owner, String repo, long number, int page, int perPage) {
        return page(
                token,
                pullPath(owner, repo, number) + "/comments?" + pageQuery(page, perPage),
                PullRequestReviewComment.class);
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
    public Page<ReviewComment> reviewCommentsOf(
            String token,
            String owner,
            String repo,
            long number,
            long reviewId,
            int page,
            int perPage) {
        return page(
                token,
                pullPath(owner, repo, number)
                        + "/reviews/"
                        + positive(reviewId)
                        + "/comments?"
                        + pageQuery(page, perPage),
                ReviewComment.class);
    }

    /**
     * Lists one page of a pull request's general comments, those GitHub keeps on its issue; with a
     * time, only those changed at or after it.
     */
    public Page<IssueComment> issueComments(
            String token,
            String owner,
            String repo,
            long number,
            @Nullable Instant since,
            int page,
            int perPage) {
        return page(
                token,
                repo(owner, repo)
                        + "/issues/"
                        + positive(number)
                        + "/comments?"
                        + (since == null ? "" : "since=" + segment(since.toString()) + "&")
                        + pageQuery(page, perPage),
                IssueComment.class);
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
    public Page<CheckRun> checkRuns(
            String token, String owner, String repo, String ref, int page, int perPage) {
        Answered<ChecksListForRef200Response> answer =
                exchange(
                        Objects.requireNonNull(token, "token"),
                        "GET",
                        repo(owner, repo)
                                + "/commits/"
                                + segments(ref)
                                + "/check-runs?"
                                + pageQuery(page, perPage),
                        null,
                        json.constructType(ChecksListForRef200Response.class));
        return envelopePage(
                answer.value().checkRuns(), answer.value().totalCount(), answer, page, perPage);
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
    public Page<WorkflowRun> runs(
            String token, String owner, String repo, String sha, int page, int perPage) {
        Answered<ActionsListWorkflowRunsForRepo200Response> answer =
                exchange(
                        Objects.requireNonNull(token, "token"),
                        "GET",
                        repo(owner, repo)
                                + "/actions/runs?head_sha="
                                + segment(sha)
                                + paging(page, perPage),
                        null,
                        json.constructType(ActionsListWorkflowRunsForRepo200Response.class));
        return envelopePage(
                answer.value().workflowRuns(), answer.value().totalCount(), answer, page, perPage);
    }

    /** Lists one page of jobs in an Actions run. */
    public Page<Job> jobs(
            String token, String owner, String repo, long runId, int page, int perPage) {
        Answered<ActionsListJobsForWorkflowRun200Response> answer =
                exchange(
                        Objects.requireNonNull(token, "token"),
                        "GET",
                        repo(owner, repo)
                                + "/actions/runs/"
                                + positive(runId)
                                + "/jobs?"
                                + pageQuery(page, perPage),
                        null,
                        json.constructType(ActionsListJobsForWorkflowRun200Response.class));
        return envelopePage(
                answer.value().jobs(), answer.value().totalCount(), answer, page, perPage);
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
    public Page<KeySimple> keys(String login, int page, int perPage) {
        return publicPage(
                "users/" + segment(login) + "/keys?" + pageQuery(page, perPage), KeySimple.class);
    }

    /** Lists one page of an account's public SSH signing keys, without a token. */
    public Page<SshSigningKey> signingKeys(String login, int page, int perPage) {
        return publicPage(
                "users/" + segment(login) + "/ssh_signing_keys?" + pageQuery(page, perPage),
                SshSigningKey.class);
    }

    /**
     * Reads an account's public SSH authentication keys without a token, as the lines of GitHub's
     * page {@code <login>.keys} beside the API, which leaves out the keys' titles. A missing login
     * is a {@link GitHubException} with status 404; no failure reads as an empty list.
     */
    public List<String> plainKeys(String login) {
        URI uri = web.resolve(segment(login) + ".keys");
        GitHubKeyPage page = new GitHubKeyPage(rateLimitDelay);
        byte[] bytes;
        try {
            // The body is read inside send, so the request timeout covers it too.
            bytes =
                    http.send(
                                    HttpRequest.newBuilder(uri)
                                            .timeout(requestTimeout)
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
            GitHubException verdict = page.verdict;
            throw verdict != null ? verdict : failed(ex);
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
    public Page<Key> ownKeys(String token, int page, int perPage) {
        return page(token, "user/keys?" + pageQuery(page, perPage), Key.class);
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

    /** Lists one page of a repository's labels, each with its name, color and description. */
    public Page<Label> labels(String token, String owner, String repo, int page, int perPage) {
        return page(token, repo(owner, repo) + "/labels?" + pageQuery(page, perPage), Label.class);
    }

    /**
     * Creates a repository label; its color is six hexadecimal digits without a leading {@code #}.
     */
    public Label createLabel(
            String token, String owner, String repo, IssuesCreateLabelRequest label) {
        Objects.requireNonNull(label, "label");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/labels",
                label,
                json.constructType(Label.class));
    }

    /**
     * Deletes a repository label by its name, sent as one path segment whatever it holds, such as a
     * slash.
     */
    public void deleteLabel(String token, String owner, String repo, String name) {
        delete(token, repo(owner, repo) + "/labels/" + segment(name));
    }

    /**
     * Changes a repository label named by its current name, sent as one path segment: {@code
     * newName}, color and description, each left null staying as it is. Whatever carries the label
     * keeps it under its new name.
     */
    public Label updateLabel(
            String token, String owner, String repo, String name, IssuesUpdateLabelRequest label) {
        Objects.requireNonNull(label, "label");
        return call(
                token,
                "PATCH",
                repo(owner, repo) + "/labels/" + segment(name),
                label,
                json.constructType(Label.class));
    }

    /**
     * Lists an organization's issue fields, each with its id, name, data type and, for a select
     * field, its options; GitHub answers them all at once, unpaged.
     */
    public List<IssueField> issueFields(String token, String org) {
        Page<IssueField> fields =
                page(token, "orgs/" + segment(org) + "/issue-fields", IssueField.class);
        // Read unpaged, so a next page would be fields silently left out.
        if (fields.more()) {
            throw new IllegalStateException("GitHub paged the issue fields, read here unpaged");
        }
        return fields.items();
    }

    /** Lists one page of the authenticated account's own SSH signing keys. */
    public Page<SshSigningKey> ownSigningKeys(String token, int page, int perPage) {
        return page(
                token, "user/ssh_signing_keys?" + pageQuery(page, perPage), SshSigningKey.class);
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

    /**
     * Reads a page of a repository's issues, sorted by their last update, newest first. GitHub
     * lists pull requests here too; a caller tells them by {@code pullRequest}.
     *
     * @param state {@code open}, {@code closed} or {@code all}; null reads GitHub's default, open
     * @param labels label names an issue must all carry; null or empty asks for none, and a name
     *     may not be blank or hold a comma, which GitHub would split
     * @param since only issues updated at or after this moment; null asks for all
     */
    public Page<Issue> issues(
            String token,
            String owner,
            String repo,
            @Nullable String state,
            @Nullable List<String> labels,
            @Nullable Instant since,
            int page,
            int perPage) {
        return page(
                token, issuesPath(owner, repo, state, labels, since, page, perPage), Issue.class);
    }

    /**
     * Reads a page of a repository's issues as {@link #issues} does, sent with the entity tag of
     * the caller's last read, so GitHub answers 304 when nothing changed.
     *
     * @param etag the tag the last answer carried, or null for a first read
     */
    public Conditional<Page<Issue>> issuesIfChanged(
            String token,
            String owner,
            String repo,
            @Nullable String state,
            @Nullable List<String> labels,
            @Nullable Instant since,
            int page,
            int perPage,
            @Nullable String etag) {
        return conditional(
                token,
                issuesPath(owner, repo, state, labels, since, page, perPage),
                listType(Issue.class),
                etag,
                (List<Issue> items, HttpHeaders headers) -> new Page<>(items, nextLink(headers)));
    }

    /** Builds the issue list's path, sorted by the last update, newest first. */
    private static String issuesPath(
            String owner,
            String repo,
            @Nullable String state,
            @Nullable List<String> labels,
            @Nullable Instant since,
            int page,
            int perPage) {
        StringBuilder path = new StringBuilder(repo(owner, repo)).append("/issues?");
        if (state != null) {
            path.append("state=").append(segment(state)).append('&');
        }
        if (labels != null && !labels.isEmpty()) {
            path.append("labels=").append(labelNames(labels)).append('&');
        }
        if (since != null) {
            path.append("since=").append(segment(since.toString())).append('&');
        }
        return path.append("sort=updated&direction=desc&")
                .append(pageQuery(page, perPage))
                .toString();
    }

    /** GitHub splits the list at commas, so a name holding one would become two filters. */
    private static String labelNames(List<String> labels) {
        List<String> encoded = new ArrayList<>();
        for (String label : labels) {
            if (label.isBlank() || label.contains(",")) {
                throw new IllegalArgumentException(
                        "A label name must not be blank or hold a comma");
            }
            encoded.add(segment(label));
        }
        return String.join(",", encoded);
    }

    /** Reads one issue by its number. */
    public Issue issue(String token, String owner, String repo, long issueNumber) {
        return call(
                token,
                "GET",
                issuePath(owner, repo, issueNumber),
                null,
                json.constructType(Issue.class));
    }

    /**
     * Creates an issue. GitHub drops labels in silence for an account without push access to the
     * repository.
     */
    public Issue createIssue(String token, String owner, String repo, IssuesCreateRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "POST",
                repo(owner, repo) + "/issues",
                body,
                json.constructType(Issue.class));
    }

    /**
     * Changes an issue; a field left null stays as it is. Closing takes {@code state} {@code
     * closed} with {@code stateReason} {@code completed} or {@code not_planned}.
     */
    public IssuesUpdate200Response updateIssue(
            String token, String owner, String repo, long issueNumber, IssuesUpdateRequest body) {
        Objects.requireNonNull(body, "body");
        return call(
                token,
                "PATCH",
                issuePath(owner, repo, issueNumber),
                body,
                json.constructType(IssuesUpdate200Response.class));
    }

    /**
     * Reads an issue's parent. GitHub answers 404 for an issue without one, as for a missing issue;
     * an issue's {@code parentIssueUrl} says beforehand whether it has one.
     */
    public Issue parentIssue(String token, String owner, String repo, long issueNumber) {
        return call(
                token,
                "GET",
                issuePath(owner, repo, issueNumber) + "/parent",
                null,
                json.constructType(Issue.class));
    }

    /** Reads a page of an issue's sub-issues, in their order. */
    public Page<Issue> subIssues(
            String token, String owner, String repo, long issueNumber, int page, int perPage) {
        return page(
                token,
                issuePath(owner, repo, issueNumber) + "/sub_issues?" + pageQuery(page, perPage),
                Issue.class);
    }

    /**
     * Makes an issue of the same owner, named by its {@code id}, not its number, a sub-issue of
     * this one.
     *
     * @param replaceParent whether to move it from a parent it already has, which GitHub refuses
     *     otherwise
     * @return the parent issue
     */
    public Issue addSubIssue(
            String token,
            String owner,
            String repo,
            long issueNumber,
            long subIssueId,
            boolean replaceParent) {
        return call(
                token,
                "POST",
                issuePath(owner, repo, issueNumber) + "/sub_issues",
                new IssuesAddSubIssueRequest(positive(subIssueId), replaceParent),
                json.constructType(Issue.class));
    }

    /**
     * Removes a sub-issue, named by its {@code id}, from this issue.
     *
     * @return the parent issue
     */
    public Issue removeSubIssue(
            String token, String owner, String repo, long issueNumber, long subIssueId) {
        // GitHub's path names no sub-issue, so the delete carries it in its body.
        return call(
                token,
                "DELETE",
                issuePath(owner, repo, issueNumber) + "/sub_issue",
                new IssuesRemoveSubIssueRequest(positive(subIssueId)),
                json.constructType(Issue.class));
    }

    /**
     * Moves a sub-issue, named by its {@code id}, after or before another sub-issue of this issue;
     * exactly one of {@code afterId} and {@code beforeId} is given.
     *
     * @return the parent issue
     */
    public Issue reprioritizeSubIssue(
            String token,
            String owner,
            String repo,
            long issueNumber,
            long subIssueId,
            @Nullable Long afterId,
            @Nullable Long beforeId) {
        if ((afterId == null) == (beforeId == null)) {
            throw new IllegalArgumentException("Expected exactly one of afterId and beforeId");
        }
        return call(
                token,
                "PATCH",
                issuePath(owner, repo, issueNumber) + "/sub_issues/priority",
                new IssuesReprioritizeSubIssueRequest(
                        positive(subIssueId),
                        afterId == null ? null : positive(afterId),
                        beforeId == null ? null : positive(beforeId)),
                json.constructType(Issue.class));
    }

    /** Reads a page of the issues this issue is blocked by. */
    public Page<Issue> blockedBy(
            String token, String owner, String repo, long issueNumber, int page, int perPage) {
        return page(
                token,
                issuePath(owner, repo, issueNumber)
                        + "/dependencies/blocked_by?"
                        + pageQuery(page, perPage),
                Issue.class);
    }

    /**
     * Marks this issue as blocked by another, named by its {@code id}, not its number.
     *
     * @return the blocking issue
     */
    public Issue addBlockedBy(
            String token, String owner, String repo, long issueNumber, long blockingIssueId) {
        return call(
                token,
                "POST",
                issuePath(owner, repo, issueNumber) + "/dependencies/blocked_by",
                new IssuesAddBlockedByDependencyRequest(positive(blockingIssueId)),
                json.constructType(Issue.class));
    }

    /**
     * Removes the issue named by its {@code id} from those this issue is blocked by.
     *
     * @return the issue no longer blocking
     */
    public Issue removeBlockedBy(
            String token, String owner, String repo, long issueNumber, long blockingIssueId) {
        return call(
                token,
                "DELETE",
                issuePath(owner, repo, issueNumber)
                        + "/dependencies/blocked_by/"
                        + positive(blockingIssueId),
                null,
                json.constructType(Issue.class));
    }

    /** Reads a page of the issues this issue blocks. */
    public Page<Issue> blocking(
            String token, String owner, String repo, long issueNumber, int page, int perPage) {
        return page(
                token,
                issuePath(owner, repo, issueNumber)
                        + "/dependencies/blocking?"
                        + pageQuery(page, perPage),
                Issue.class);
    }

    /**
     * Reads a page of an organization project's items, sent with the entity tag of the caller's
     * last read, so GitHub answers 304 when nothing changed. A caller polls the first page to learn
     * whether the project changed; the board itself is read through GraphQL.
     *
     * @param after the cursor of the page to read, null for the first
     * @param etag the tag the last answer carried, or null for a first read
     */
    public Conditional<CursorPage<ProjectItemSummary>> projectItemsIfChanged(
            String token,
            String org,
            int projectNumber,
            @Nullable String after,
            int perPage,
            @Nullable String etag) {
        return projectItemsIfChanged(token, org, projectNumber, after, perPage, List.of(), etag);
    }

    /** At most this many fields are named in one read of a project's items. */
    public static final int MAX_FIELDS = 50;

    /**
     * Reads a page of an organization project's items as {@link #projectItemsIfChanged(String,
     * String, int, String, int, String)} does, with the values of the fields named. A change of any
     * of those values, of an item's issue or of the items themselves changes the tag; named, the
     * fields also keep an unchanged project's tag stable from read to read.
     *
     * @param fieldIds the fields' numeric ids, {@link GitHubGraphQl.ProjectField#databaseId()}, at
     *     most {@value #MAX_FIELDS}; empty names none
     * @param etag the tag the last answer carried, or null for a first read
     */
    public Conditional<CursorPage<ProjectItemSummary>> projectItemsIfChanged(
            String token,
            String org,
            int projectNumber,
            @Nullable String after,
            int perPage,
            List<Long> fieldIds,
            @Nullable String etag) {
        if (fieldIds.size() > MAX_FIELDS)
            throw new IllegalArgumentException("Expected at most " + MAX_FIELDS + " field ids");
        StringBuilder fields = new StringBuilder();
        for (long id : fieldIds)
            fields.append(fields.isEmpty() ? "&fields=" : ",").append(positive(id));
        return conditional(
                token,
                "orgs/"
                        + segment(org)
                        + "/projectsV2/"
                        + positive(projectNumber)
                        + "/items?per_page="
                        + positive(perPage)
                        + fields
                        + (after == null ? "" : "&after=" + segment(after)),
                json.constructType(JsonNode.class),
                etag,
                (JsonNode items, HttpHeaders headers) ->
                        new CursorPage<>(projectItems(items), nextCursor(headers, after)));
    }

    /**
     * Reads with the caller's entity tag; a 304 is no failure but an answer without a body, which
     * keeps the tag the caller sent when GitHub names none.
     */
    private <T, R> Conditional<R> conditional(
            String token,
            String path,
            JavaType type,
            @Nullable String etag,
            BiFunction<T, HttpHeaders, R> value) {
        Objects.requireNonNull(token, "token");
        HttpResponse<String> response =
                send(
                        token,
                        "GET",
                        base.resolve(path),
                        null,
                        true,
                        BoundedAnswer.json(rateLimitDelay),
                        etag);
        HttpHeaders headers = response.headers();
        RateLimit limit = RateLimit.read(headers, Instant.now());
        // A tag that could not be sent back is not kept, so the caller's next read can be sent.
        String answeredTag =
                headers.firstValue("etag")
                        .filter(tag -> ENTITY_TAG.matcher(tag).matches())
                        .orElse(null);
        if (response.statusCode() == 304) {
            String kept = answeredTag != null ? answeredTag : etag;
            if (kept == null) {
                // GitHub answers 304 only to a tag, so a 304 to a first read is not an answer.
                throw refusal(
                        304,
                        "GitHub answered 304 to a read without a tag",
                        headers,
                        rateLimitDelay);
            }
            return new Conditional<>(null, kept, false, limit, date(headers));
        }
        List<String> credentials = List.of(token);
        check(response, credentials);
        T read;
        try {
            read = GitHubJson.read(json, response.body(), type, credentials);
            return new Conditional<>(
                    value.apply(read, headers), answeredTag, true, limit, date(headers));
        } catch (GitHubException undecodable) {
            // A failure that already names GitHub's status is GitHub's own and passes unchanged.
            if (undecodable.status() != 0) throw undecodable;
            throw answered(response.statusCode(), undecodable, limit);
        }
    }

    /** GitHub's time of an answer, null when its {@code Date} header is missing or unreadable. */
    static @Nullable Instant date(HttpHeaders headers) {
        return headers.firstValue("date")
                .map(
                        text -> {
                            try {
                                return DateTimeFormatter.RFC_1123_DATE_TIME.parse(
                                        text.trim(), Instant::from);
                            } catch (DateTimeParseException unreadable) {
                                return null;
                            }
                        })
                .orElse(null);
    }

    /**
     * Reads project items by hand rather than into the generated model, which describes their
     * {@code content} and {@code fields} only as objects; a missing part fails the whole page.
     */
    private static List<ProjectItemSummary> projectItems(JsonNode items) {
        if (!items.isArray()) throw unreadableItems("A project's items are not a list");
        List<ProjectItemSummary> read = new ArrayList<>();
        try {
            for (JsonNode item : items) read.add(ProjectItemSummary.read(item));
        } catch (ProjectItemSummary.Unreadable | ArithmeticException unreadable) {
            throw unreadableItems(Objects.requireNonNull(unreadable.getMessage()));
        }
        return read;
    }

    private static GitHubException unreadableItems(String message) {
        return undecodable(message);
    }

    /** An answer that cannot be decoded, its status given where the answer is at hand. */
    private static GitHubException undecodable(String message) {
        return new GitHubException(0, message, Delivery.ANSWERED, null, false);
    }

    /** The next page's cursor; one that names the page just read would read it forever. */
    private static @Nullable String nextCursor(HttpHeaders headers, @Nullable String sent) {
        String next = afterCursor(headers);
        if (next != null && next.equals(sent)) {
            throw undecodable("GitHub's next link names the page just read");
        }
        return next;
    }

    /** The {@code after} parameter of the next link, or null when no next link follows. */
    private static @Nullable String afterCursor(HttpHeaders headers) {
        for (String value : headers.allValues("link")) {
            Matcher link = LINK.matcher(value);
            while (link.find()) {
                if (NEXT_LINK.matcher(link.group(2)).find()) {
                    return cursorOf(link.group(1));
                }
            }
        }
        return null;
    }

    /** A next link without a readable cursor would read the same page again, so it fails. */
    private static String cursorOf(String next) {
        try {
            String query = URI.create(next).getRawQuery();
            if (query != null) {
                for (String parameter : query.split("&")) {
                    if (parameter.startsWith("after=")) {
                        String after =
                                URLDecoder.decode(
                                        parameter.substring("after=".length()),
                                        StandardCharsets.UTF_8);
                        if (!after.isBlank()) {
                            return after;
                        }
                    }
                }
            }
        } catch (IllegalArgumentException unreadable) {
            // The failure below says so without quoting any part of the link.
        }
        throw undecodable("GitHub's next link names no readable cursor");
    }

    private static String issuePath(String owner, String repo, long issueNumber) {
        return repo(owner, repo) + "/issues/" + positive(issueNumber);
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
        GraphQlAnswer answer = graphQlAnswer(token, body);
        JsonNode envelope = answer.envelope();
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
        answer.failOnErrors();
        if (envelope instanceof ObjectNode object) object.remove("errors");
        try {
            return GitHubJson.convert(
                    json, envelope, json.constructType(type), answer.credentials());
        } catch (GitHubException undecodable) {
            throw answered(answer.status(), undecodable, answer.rateLimit());
        }
    }

    /**
     * Sends a GraphQL request and returns its envelope as GitHub answered it, errors included, with
     * the answer's headers and the credentials its messages are scrubbed of.
     */
    GraphQlAnswer graphQlAnswer(String token, Object body) {
        Objects.requireNonNull(body, "body");
        Answered<JsonNode> answer =
                exchange(
                        Objects.requireNonNull(token, "token"),
                        "POST",
                        "graphql",
                        body,
                        json.constructType(JsonNode.class));
        JsonNode envelope = answer.value();
        JsonNode errors = envelope.path("errors");
        // An empty or null list of errors is the absence of errors.
        if (envelope instanceof ObjectNode object
                && (errors.isNull() || (errors.isArray() && errors.isEmpty()))) {
            object.remove("errors");
        }
        return new GraphQlAnswer(
                answer.status(),
                envelope,
                RateLimit.read(answer.headers(), Instant.now()),
                credentials(token, body),
                rateLimitDelay);
    }

    /**
     * A GraphQL answer: its envelope, what its headers said of the rate limit, and the credentials
     * any message from it is scrubbed of.
     */
    record GraphQlAnswer(
            int status,
            JsonNode envelope,
            @Nullable RateLimit rateLimit,
            List<String> credentials,
            Duration rateLimitDelay) {
        /** Keeps the credentials from changing. */
        GraphQlAnswer {
            credentials = List.copyOf(credentials);
        }

        /** GitHub's message, scrubbed of every credential the request carried. */
        String scrubbed(JsonNode error) {
            JsonNode message = error.path("message");
            return GitHubJson.safe(
                    message.isTextual() ? message.asText() : "GraphQL request failed", credentials);
        }

        /** Whether any error, not only the first, says GitHub's rate limit was reached. */
        boolean rateLimited() {
            for (JsonNode error : envelope.path("errors")) {
                if (error.path("type").asText().equals("RATE_LIMITED")) return true;
            }
            return false;
        }

        /** Fails with the rate limit's error, else the first, when the answer has any. */
        void failOnErrors() {
            // An error removed after reading, such as a known NOT_FOUND, leaves an empty list.
            if (envelope.path("errors").isEmpty()) return;
            JsonNode named = envelope.path("errors").path(0);
            if (rateLimited()) {
                for (JsonNode error : envelope.path("errors")) {
                    if (error.path("type").asText().equals("RATE_LIMITED")) named = error;
                }
            }
            throw failure(scrubbed(named));
        }

        /**
         * Whether any error says GitHub ended the query for the resources it used, which GitHub
         * names by its message alone.
         */
        boolean resourceLimited() {
            for (JsonNode error : envelope.path("errors")) {
                if (error.path("message")
                        .asText()
                        .startsWith("Resource limits for this query exceeded")) return true;
            }
            return false;
        }

        /** A failure of the whole call, a rate or resource limit when any error says so. */
        GitHubException failure(String message) {
            return new GitHubException(
                    status,
                    message,
                    Delivery.ANSWERED,
                    rateLimit,
                    rateLimited(),
                    null,
                    resourceLimited(),
                    rateLimitDelay);
        }
    }

    /**
     * Reads a job log, following at most five redirects and stripping authentication off origin. A
     * log is read to its end, however long, keeping its last 1 MiB, since a build reads a log's
     * tail.
     */
    public JobLog jobLogs(String token, String owner, String repo, long jobId) {
        Objects.requireNonNull(token, "token");
        URI uri = base.resolve(repo(owner, repo) + "/actions/jobs/" + positive(jobId) + "/logs");
        boolean authenticate = true;
        for (int redirects = 0; redirects <= 5; redirects++) {
            BoundedAnswer answer = BoundedAnswer.log(rateLimitDelay);
            HttpResponse<String> response =
                    send(token, "GET", uri, null, authenticate, answer, null);
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
                return new JobLog(response.body(), answer.cut());
            }
        }
        throw new GitHubException(0, "Too many log redirects");
    }

    private <T> T call(
            String token, String method, String path, @Nullable Object body, JavaType type) {
        Answered<T> answer =
                exchange(Objects.requireNonNull(token, "token"), method, path, body, type);
        return answer.value();
    }

    private <T> Page<T> page(String token, String path, Class<T> element) {
        return listed(Objects.requireNonNull(token, "token"), path, element);
    }

    private <T> Page<T> publicPage(String path, Class<T> element) {
        return listed(null, path, element);
    }

    private <T> Page<T> listed(@Nullable String token, String path, Class<T> element) {
        Answered<List<T>> answer = exchange(token, "GET", path, null, listType(element));
        return new Page<>(answer.value(), nextLink(answer.headers()));
    }

    /** An envelope also counts the whole list, so a page says whether more follow either way. */
    private static <T> Page<T> envelopePage(
            @Nullable List<T> items,
            @Nullable Number total,
            Answered<?> answer,
            int page,
            int perPage) {
        return new Page<>(
                items == null ? List.of() : items,
                nextLink(answer.headers())
                        || (total != null && (long) page * perPage < total.longValue()));
    }

    /** GitHub links a next page when there is one; a full page without the link is the last. */
    private static boolean nextLink(HttpHeaders headers) {
        for (String value : headers.allValues("link")) {
            if (NEXT_LINK.matcher(value).find()) {
                return true;
            }
        }
        return false;
    }

    /** Calls the API; a null token sends none. */
    private <T> Answered<T> exchange(
            @Nullable String token,
            String method,
            String path,
            @Nullable Object body,
            JavaType type) {
        HttpResponse<String> response =
                send(
                        token,
                        method,
                        base.resolve(path),
                        body,
                        true,
                        BoundedAnswer.json(rateLimitDelay),
                        null);
        List<String> credentials = credentials(token, body);
        check(response, credentials);
        try {
            T value = GitHubJson.read(json, response.body(), type, credentials);
            return new Answered<>(value, response.statusCode(), response.headers());
        } catch (GitHubException undecodable) {
            throw answered(
                    response.statusCode(),
                    undecodable,
                    RateLimit.read(response.headers(), Instant.now()));
        }
    }

    /** The token and every credential the body carries, the longest first, to scrub messages of. */
    private List<String> credentials(@Nullable String token, @Nullable Object body) {
        List<String> credentials = new ArrayList<>();
        if (token != null) {
            credentials.add(token);
        }
        if (body != null) {
            collectCredentials(json.valueToTree(body), credentials);
        }
        credentials.sort(java.util.Comparator.comparingInt(String::length).reversed());
        return credentials;
    }

    /** An answer GitHub gave that cannot be decoded, which is no failure to send the request. */
    private static GitHubException answered(
            int status, GitHubException undecodable, @Nullable RateLimit limit) {
        return new GitHubException(
                status,
                "GitHub answered "
                        + status
                        + " with an answer that cannot be decoded: "
                        + undecodable.getMessage(),
                Delivery.ANSWERED,
                limit,
                false);
    }

    private void delete(String token, String path) {
        Objects.requireNonNull(token, "token");
        HttpResponse<String> response =
                send(
                        token,
                        "DELETE",
                        base.resolve(path),
                        null,
                        true,
                        BoundedAnswer.json(rateLimitDelay),
                        null);
        check(response, List.of(token));
    }

    private HttpResponse<String> send(
            @Nullable String token,
            String method,
            URI uri,
            @Nullable Object body,
            boolean authenticate,
            BoundedAnswer answer,
            @Nullable String ifNoneMatch) {
        if (token != null && (token.isBlank() || token.chars().anyMatch(c -> c < 33 || c > 126))) {
            throw new IllegalArgumentException("Invalid access token");
        }
        // A tag holding a line break would add a header of its own to the request.
        if (ifNoneMatch != null && !ENTITY_TAG.matcher(ifNoneMatch).matches()) {
            throw new IllegalArgumentException("Invalid entity tag");
        }
        HttpResponse<String> response;
        try {
            HttpRequest.Builder request =
                    HttpRequest.newBuilder(uri)
                            .timeout(requestTimeout)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2026-03-10");
            if (token != null && authenticate && sameOrigin(base, uri)) {
                request.header("Authorization", "Bearer " + token);
            }
            if (ifNoneMatch != null) {
                request.header("If-None-Match", ifNoneMatch);
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
            throw new GitHubException(
                    0, "GitHub request could not be written", Delivery.NOT_SENT, null, false);
        } catch (IOException | IllegalArgumentException ex) {
            // A transport may fail the exchange once the answer stops reading; its verdict stands.
            GitHubException exceeded = answer.exceeded();
            throw exceeded != null ? exceeded : failed(ex);
        }
        GitHubException exceeded = answer.exceeded();
        if (exceeded != null) {
            throw exceeded;
        }
        return response;
    }

    private static GitHubException interrupted() {
        return new GitHubException(
                0, "GitHub request interrupted", Delivery.SENT_UNANSWERED, null, false);
    }

    /**
     * The JDK does not say whether bytes left before a failure, so anything after the connection
     * opened counts as sent. The message stays fixed: transport exceptions can include URLs,
     * headers and credential-bearing input.
     */
    private static GitHubException failed(Exception ex) {
        boolean notSent = ex instanceof IllegalArgumentException || neverConnected(ex);
        return new GitHubException(
                0,
                "GitHub request failed",
                notSent ? Delivery.NOT_SENT : Delivery.SENT_UNANSWERED,
                null,
                false);
    }

    private static boolean neverConnected(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof ConnectException || t instanceof HttpConnectTimeoutException) {
                return true;
            }
        }
        return false;
    }

    /** Makes the failure of a refused answer, telling a rate limit from a refusal. */
    static GitHubException refusal(
            int status, String message, HttpHeaders headers, Duration rateLimitDelay) {
        RateLimit limit = RateLimit.read(headers, Instant.now());
        return new GitHubException(
                status,
                message,
                Delivery.ANSWERED,
                limit,
                isRateLimit(status, message, limit),
                acceptedPermissions(headers),
                false,
                rateLimitDelay);
    }

    /**
     * GitHub's own rule: a 429, or a 403 with no requests remaining, a wait asked or a message
     * naming a rate limit. Any other 403 is a refusal.
     */
    private static boolean isRateLimit(int status, String message, @Nullable RateLimit limit) {
        if (status == 429) {
            return true;
        }
        if (status != 403) {
            return false;
        }
        return (limit != null
                        && (Long.valueOf(0).equals(limit.remaining())
                                || limit.retryAfter() != null))
                || message.toLowerCase(Locale.ROOT).contains("rate limit");
    }

    /**
     * GitHub's header, kept only when it is a plain list such as {@code issues=write; pulls=read}.
     */
    private static @Nullable String acceptedPermissions(HttpHeaders headers) {
        return headers.firstValue("x-accepted-github-permissions")
                .map(String::strip)
                .filter(value -> !value.isEmpty() && ACCEPTED_PERMISSIONS.matcher(value).matches())
                .orElse(null);
    }

    private record Answered<T>(T value, int status, HttpHeaders headers) {}

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
            throw refusal(response.statusCode(), message, response.headers(), rateLimitDelay);
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
