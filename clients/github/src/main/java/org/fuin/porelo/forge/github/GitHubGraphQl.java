package org.fuin.porelo.forge.github;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** GitHub review thread queries and resolution mutations, with explicit cursor pagination. */
public final class GitHubGraphQl {
    /** Reads a page of threads and the first page of each thread's comments. */
    public static final String REVIEW_THREADS =
            """
            query ReviewThreads($owner: String!, $repo: String!, $number: Int!, $after: String) {
              repository(owner: $owner, name: $repo) {
                pullRequest(number: $number) {
                  reviewThreads(first: 100, after: $after) {
                    nodes {
                      id isResolved isOutdated path line diffSide startDiffSide
                      resolvedBy { login id databaseId }
                      comments(first: 100) {
                        nodes {
                          id databaseId body createdAt
                          author { login ... on User { id databaseId } ... on Bot { id databaseId } }
                        }
                        pageInfo { hasNextPage endCursor }
                      }
                    }
                    pageInfo { hasNextPage endCursor }
                  }
                }
              }
            }
            """;

    /** Continues a thread's comments when its first page was incomplete. */
    public static final String THREAD_COMMENTS =
            """
            query ThreadComments($id: ID!, $after: String) {
              node(id: $id) {
                ... on PullRequestReviewThread {
                  comments(first: 100, after: $after) {
                    nodes {
                      id databaseId body createdAt
                      author { login ... on User { id databaseId } ... on Bot { id databaseId } }
                    }
                    pageInfo { hasNextPage endCursor }
                  }
                }
              }
            }
            """;

    /** Reads one thread by its node ID, its state, its resolver and the first page of comments. */
    public static final String REVIEW_THREAD =
            """
            query ReviewThread($id: ID!) {
              node(id: $id) {
                ... on PullRequestReviewThread {
                  id isResolved isOutdated path line diffSide startDiffSide
                  resolvedBy { login id databaseId }
                  comments(first: 100) {
                    nodes {
                      id databaseId body createdAt
                      author { login ... on User { id databaseId } ... on Bot { id databaseId } }
                    }
                    pageInfo { hasNextPage endCursor }
                  }
                }
              }
            }
            """;

    /** Resolves a review thread. */
    public static final String RESOLVE_REVIEW_THREAD =
            """
            mutation ResolveReviewThread($id: ID!) {
              resolveReviewThread(input: {threadId: $id}) { thread { id isResolved } }
            }
            """;

    /** Reopens a review thread. */
    public static final String UNRESOLVE_REVIEW_THREAD =
            """
            mutation UnresolveReviewThread($id: ID!) {
              unresolveReviewThread(input: {threadId: $id}) { thread { id isResolved } }
            }
            """;

    private final GitHubClient client;

    /** Creates a lenient GraphQL reader using the supplied API base and transport. */
    public GitHubGraphQl(URI base, HttpClient http) {
        this(base, http, Strictness.LENIENT);
    }

    /** Creates a GraphQL reader; automatic redirects are refused. */
    public GitHubGraphQl(URI base, HttpClient http, Strictness strictness) {
        client = new GitHubClient(base, http, strictness);
    }

    /** Reads a page of threads; a null cursor requests the first page. */
    public ThreadsResponse reviewThreads(
            String token, String owner, String repo, int number, @Nullable String after) {
        if (number <= 0 || owner.isBlank() || repo.isBlank()) {
            throw new IllegalArgumentException(
                    "Expected an owner, repository and positive pull number");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("owner", owner);
        variables.put("repo", repo);
        variables.put("number", number);
        if (after != null) variables.put("after", after);
        return client.graphQl(
                token,
                Map.of("query", REVIEW_THREADS, "variables", variables),
                ThreadsResponse.class);
    }

    /** Reads another page of comments for a thread. */
    public CommentsResponse threadComments(String token, String id, @Nullable String after) {
        requireId(id);
        Map<String, Object> variables = new LinkedHashMap<>();
        variables.put("id", id);
        if (after != null) variables.put("after", after);
        return client.graphQl(
                token,
                Map.of("query", THREAD_COMMENTS, "variables", variables),
                CommentsResponse.class);
    }

    /** Reads one thread identified by its GraphQL node ID; an unknown ID reads a null node. */
    public ThreadResponse reviewThread(String token, String id) {
        requireId(id);
        return client.graphQlNode(
                token,
                Map.of("query", REVIEW_THREAD, "variables", Map.of("id", id)),
                ThreadResponse.class);
    }

    /** Resolves a thread identified by its GraphQL node ID. */
    public ResolveResponse resolveReviewThread(String token, String id) {
        requireId(id);
        return client.graphQl(
                token,
                Map.of("query", RESOLVE_REVIEW_THREAD, "variables", Map.of("id", id)),
                ResolveResponse.class);
    }

    /** Reopens a thread identified by its GraphQL node ID. */
    public UnresolveResponse unresolveReviewThread(String token, String id) {
        requireId(id);
        return client.graphQl(
                token,
                Map.of("query", UNRESOLVE_REVIEW_THREAD, "variables", Map.of("id", id)),
                UnresolveResponse.class);
    }

    private static void requireId(String id) {
        if (id.isBlank()) throw new IllegalArgumentException("Expected a thread node ID");
    }

    /** Cursor state of a GitHub connection. */
    public record PageInfo(Boolean hasNextPage, @Nullable String endCursor) {
        /** Requires pagination state. */
        public PageInfo {
            Objects.requireNonNull(hasNextPage, "hasNextPage");
        }
    }

    /**
     * An account as GitHub names it; a user's or a bot's stable IDs are given, those of another
     * kind of account, or of a deleted one, are not.
     */
    public record Account(String login, @Nullable String id, @Nullable Long databaseId) {
        /** Requires the login. */
        public Account {
            Objects.requireNonNull(login, "login");
        }
    }

    /**
     * A review comment's node ID, REST identity, Markdown body, time and author; the author of a
     * deleted account is null.
     */
    public record Comment(
            String id,
            @Nullable Long databaseId,
            String body,
            OffsetDateTime createdAt,
            @Nullable Account author) {
        /**
         * Requires the comment's identity, text and time; some REST identities may be unavailable.
         */
        public Comment {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(body, "body");
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /** One page of a thread's comments. */
    public record Comments(List<Comment> nodes, PageInfo pageInfo) {
        /** Copies the non-null comment collection. */
        public Comments {
            nodes = List.copyOf(nodes);
            Objects.requireNonNull(pageInfo, "pageInfo");
        }
    }

    /**
     * Thread state, source location and side of the diff, {@code LEFT} or {@code RIGHT} in GitHub's
     * terms; outdated threads may lack a current line, a single line has no start side, and an
     * unresolved thread, or one whose resolver is gone, has no resolver.
     */
    public record ReviewThread(
            String id,
            Boolean isResolved,
            Boolean isOutdated,
            String path,
            @Nullable Integer line,
            String diffSide,
            @Nullable String startDiffSide,
            @Nullable Account resolvedBy,
            Comments comments) {
        /** Requires the selected thread fields. */
        public ReviewThread {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(isResolved, "isResolved");
            Objects.requireNonNull(isOutdated, "isOutdated");
            Objects.requireNonNull(path, "path");
            Objects.requireNonNull(diffSide, "diffSide");
            Objects.requireNonNull(comments, "comments");
        }
    }

    /** One page of review threads. */
    public record Threads(List<ReviewThread> nodes, PageInfo pageInfo) {
        /** Copies the non-null thread collection. */
        public Threads {
            nodes = List.copyOf(nodes);
            Objects.requireNonNull(pageInfo, "pageInfo");
        }
    }

    /** The selected pull request connection. */
    public record PullRequest(Threads reviewThreads) {
        /** Requires the connection. */
        public PullRequest {
            Objects.requireNonNull(reviewThreads, "reviewThreads");
        }
    }

    /** Repository selection; an absent pull request is represented by null. */
    public record Repository(@Nullable PullRequest pullRequest) {}

    /** Query data; a repository hidden from the token may be null. */
    public record ThreadsData(@Nullable Repository repository) {}

    /** The GraphQL review thread envelope. */
    public record ThreadsResponse(ThreadsData data) {
        /** Requires query data. */
        public ThreadsResponse {
            Objects.requireNonNull(data, "data");
        }
    }

    /** A selected thread node, or null when absent. */
    public record CommentsData(@Nullable CommentsNode node) {}

    /** The thread's comment connection. */
    public record CommentsNode(Comments comments) {
        /** Requires comments. */
        public CommentsNode {
            Objects.requireNonNull(comments, "comments");
        }
    }

    /** The comment pagination envelope. */
    public record CommentsResponse(CommentsData data) {
        /** Requires query data. */
        public CommentsResponse {
            Objects.requireNonNull(data, "data");
        }
    }

    /** A selected thread, or null when no thread has the ID. */
    public record ThreadData(@Nullable ReviewThread node) {}

    /** The single thread envelope. */
    public record ThreadResponse(ThreadData data) {
        /** Requires query data. */
        public ThreadResponse {
            Objects.requireNonNull(data, "data");
        }
    }

    /** Thread state after a mutation. */
    public record ThreadState(String id, Boolean isResolved) {
        /** Requires identity and state. */
        public ThreadState {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(isResolved, "isResolved");
        }
    }

    /** Mutation payload. */
    public record ThreadPayload(ThreadState thread) {
        /** Requires the changed thread. */
        public ThreadPayload {
            Objects.requireNonNull(thread, "thread");
        }
    }

    /** Resolve mutation data. */
    public record ResolveData(ThreadPayload resolveReviewThread) {
        /** Requires the mutation payload. */
        public ResolveData {
            Objects.requireNonNull(resolveReviewThread, "resolveReviewThread");
        }
    }

    /** Resolve mutation envelope. */
    public record ResolveResponse(ResolveData data) {
        /** Requires mutation data. */
        public ResolveResponse {
            Objects.requireNonNull(data, "data");
        }
    }

    /** Unresolve mutation data. */
    public record UnresolveData(ThreadPayload unresolveReviewThread) {
        /** Requires the mutation payload. */
        public UnresolveData {
            Objects.requireNonNull(unresolveReviewThread, "unresolveReviewThread");
        }
    }

    /** Unresolve mutation envelope. */
    public record UnresolveResponse(UnresolveData data) {
        /** Requires mutation data. */
        public UnresolveResponse {
            Objects.requireNonNull(data, "data");
        }
    }
}
