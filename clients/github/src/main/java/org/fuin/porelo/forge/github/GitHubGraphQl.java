package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * GitHub review thread queries and resolution mutations, and an organization project's board: its
 * fields, its items with their issues, and aliased mutations that save several parts at once.
 * Paging is by explicit cursor. Every caller value travels in the request's variables, never in its
 * text.
 */
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

    /** At most this many parts go into one aliased mutation. */
    public static final int MAX_BATCH = 50;

    /** At most this many issues are read by id in one query. */
    public static final int MAX_ISSUES = 100;

    /**
     * Reads an organization project's id, title, last change and a page of its fields, each
     * single-select field with its options.
     *
     * @param after the cursor of the page of fields to read, null for the first
     */
    public ProjectFields projectFields(
            String token, String org, int projectNumber, @Nullable String after) {
        ProjectBoard.requireId(org, "an organization");
        positive(projectNumber);
        return query(
                token,
                ProjectBoard.request(
                        ProjectBoard.PROJECT_FIELDS,
                        ProjectBoard.variables(
                                "org", org, "number", projectNumber, "after", after)),
                ProjectBoard::projectFields);
    }

    /**
     * Reads a page of 100 of an organization project's items, each with its issue and its field
     * values; an item that is a draft or a pull request has no issue.
     *
     * @param after the cursor of the page to read, null for the first
     */
    public ProjectItems projectItems(
            String token, String org, int projectNumber, @Nullable String after) {
        ProjectBoard.requireId(org, "an organization");
        positive(projectNumber);
        return query(
                token,
                ProjectBoard.request(
                        ProjectBoard.PROJECT_ITEMS,
                        ProjectBoard.variables(
                                "org", org, "number", projectNumber, "after", after)),
                ProjectBoard::projectItems);
    }

    /**
     * Reads issues by their node ids, each with its item and field values in the given project. An
     * id GitHub does not know, or one of no issue, reads as null in its place.
     *
     * @param issueIds one to {@value #MAX_ISSUES} distinct issue node ids
     */
    public IssueBoard issueBoard(String token, List<String> issueIds, String projectId) {
        ProjectBoard.requireIds(issueIds, MAX_ISSUES, "issue id");
        ProjectBoard.requireId(projectId, "a project id");
        var answer =
                client.graphQlAnswer(
                        token,
                        ProjectBoard.request(
                                ProjectBoard.ISSUE_BOARD, ProjectBoard.variables("ids", issueIds)));
        // An unknown id reads null in its place, with an error GitHub raises for that place alone.
        if (answer.envelope().path("errors")
                instanceof com.fasterxml.jackson.databind.node.ArrayNode errors) {
            errors.removeIf(
                    error ->
                            error.path("type").asText().equals("NOT_FOUND")
                                    && error.path("path").size() == 2
                                    && error.path("path").path(0).asText().equals("nodes"));
        }
        answer.failOnErrors();
        return read(answer, data -> ProjectBoard.issueBoard(data, issueIds.size(), projectId));
    }

    /**
     * Adds issues to a project in one mutation; each saved part is the new item's id.
     *
     * @param contentIds one to {@value #MAX_BATCH} distinct issue node ids
     */
    public Batch<String> addProjectItems(String token, String projectId, List<String> contentIds) {
        ProjectBoard.requireIds(contentIds, MAX_BATCH, "issue id");
        ProjectBoard.requireId(projectId, "a project id");
        List<Map<String, Object>> inputs = new java.util.ArrayList<>();
        for (String id : contentIds) inputs.add(Map.of("projectId", projectId, "contentId", id));
        return batch(
                token,
                ProjectBoard.batch(
                        "AddProjectItems",
                        "AddProjectV2ItemByIdInput",
                        inputs,
                        index -> "addProjectV2ItemById(input: $v" + index + ") { item { id } }"),
                inputs.size(),
                part -> ProjectBoard.itemId(part, "item"));
    }

    /**
     * Sets or clears several field values of a project's items in one mutation; each saved part is
     * the item's id.
     *
     * @param values one to {@value #MAX_BATCH}, no two for the same item and field
     */
    public Batch<String> setFieldValues(String token, String projectId, List<FieldValue> values) {
        ProjectBoard.requireSize(values, MAX_BATCH, "field values");
        ProjectBoard.requireId(projectId, "a project id");
        java.util.Set<String> targets = new java.util.HashSet<>();
        List<Map<String, Object>> inputs = new java.util.ArrayList<>();
        for (FieldValue value : values) {
            ProjectBoard.requireId(value.itemId(), "an item id");
            ProjectBoard.requireId(value.fieldId(), "a field id");
            if (value.optionId() != null) ProjectBoard.requireId(value.optionId(), "an option id");
            if (value.number() != null && !Double.isFinite(value.number()))
                throw new IllegalArgumentException("Expected a finite number");
            if (!targets.add(value.itemId() + "\n" + value.fieldId())) {
                throw new IllegalArgumentException("Two values for one item and field");
            }
            inputs.add(ProjectBoard.fieldValueInput(projectId, value));
        }
        // Clearing takes another input type, so each part names its own variable's type.
        StringBuilder header = new StringBuilder("mutation SetFieldValues(");
        StringBuilder fields = new StringBuilder();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            boolean clear = values.get(index).clears();
            header.append(index == 0 ? "" : ", ")
                    .append("$v")
                    .append(index)
                    .append(": ")
                    .append(
                            clear
                                    ? "ClearProjectV2ItemFieldValueInput!"
                                    : "UpdateProjectV2ItemFieldValueInput!");
            fields.append(" a")
                    .append(index)
                    .append(": ")
                    .append(
                            clear
                                    ? "clearProjectV2ItemFieldValue"
                                    : "updateProjectV2ItemFieldValue")
                    .append("(input: $v")
                    .append(index)
                    .append(") { projectV2Item { id } }");
            variables.put("v" + index, inputs.get(index));
        }
        return batch(
                token,
                ProjectBoard.request(
                        header.append(") {").append(fields).append(" }").toString(), variables),
                inputs.size(),
                part -> ProjectBoard.itemId(part, "projectV2Item"));
    }

    /**
     * Replaces a single-select field's options with the given ones, in their order. GitHub gives
     * every option a new id, one whose name stays included, so a caller takes the ids from the
     * answer.
     */
    public FieldOptions setFieldOptions(String token, String fieldId, List<NewOption> options) {
        ProjectBoard.requireId(fieldId, "a field id");
        ProjectBoard.requireSize(options, MAX_BATCH, "options");
        java.util.Set<String> names = new java.util.HashSet<>();
        for (NewOption option : options) {
            ProjectBoard.requireId(option.name(), "an option name");
            ProjectBoard.requireId(option.color(), "an option color");
            if (!names.add(option.name()))
                throw new IllegalArgumentException("The same option name twice");
        }
        var answer =
                client.graphQlAnswer(
                        token,
                        ProjectBoard.request(
                                ProjectBoard.SET_FIELD_OPTIONS,
                                ProjectBoard.optionsInput(fieldId, options)));
        answer.failOnErrors();
        return read(answer, data -> ProjectBoard.fieldOptions(data, answer.rateLimit()));
    }

    /**
     * Reads a page of 100 of an issue's moves on a project's own {@code Status} field, oldest
     * first. A change of any other single-select field appears in no timeline.
     *
     * @param after the cursor of the page to read, null for the first
     */
    public StatusChanges statusChanges(String token, String issueId, @Nullable String after) {
        ProjectBoard.requireId(issueId, "an issue id");
        return query(
                token,
                ProjectBoard.request(
                        ProjectBoard.STATUS_CHANGES,
                        ProjectBoard.variables("id", issueId, "after", after)),
                ProjectBoard::statusChanges);
    }

    /**
     * Creates several issues, in repositories of one owner, in one mutation.
     *
     * @param issues one to {@value #MAX_BATCH}
     */
    public Batch<CreatedIssue> createIssues(String token, List<NewIssue> issues) {
        ProjectBoard.requireSize(issues, MAX_BATCH, "issues");
        List<Map<String, Object>> inputs = new java.util.ArrayList<>();
        for (NewIssue issue : issues) {
            ProjectBoard.requireId(issue.repositoryId(), "a repository id");
            ProjectBoard.requireId(issue.title(), "a title");
            if (issue.parentIssueId() != null)
                ProjectBoard.requireId(issue.parentIssueId(), "a parent issue id");
            if (!issue.labelIds().isEmpty())
                ProjectBoard.requireIds(issue.labelIds(), MAX_ISSUES, "label id");
            inputs.add(ProjectBoard.issueInput(issue));
        }
        return batch(
                token,
                ProjectBoard.batch(
                        "CreateIssues",
                        "CreateIssueInput",
                        inputs,
                        index ->
                                "createIssue(input: $v"
                                        + index
                                        + ") { issue { id databaseId number url } }"),
                inputs.size(),
                ProjectBoard::createdIssue);
    }

    private <T> T query(
            String token,
            Map<String, Object> request,
            java.util.function.Function<JsonNode, T> reader) {
        var answer = client.graphQlAnswer(token, request);
        answer.failOnErrors();
        return read(answer, reader);
    }

    private static <T> T read(
            GitHubClient.GraphQlAnswer answer, java.util.function.Function<JsonNode, T> reader) {
        try {
            return reader.apply(answer.envelope().path("data"));
        } catch (UnreadableAnswer
                | NullPointerException
                | IllegalArgumentException
                | ArithmeticException unreadable) {
            throw new GitHubException(
                    200,
                    "GitHub's GraphQL answer could not be read"
                            + (unreadable instanceof UnreadableAnswer missing
                                    ? ": " + missing.getMessage()
                                    : ""),
                    Delivery.ANSWERED,
                    answer.rateLimit(),
                    false);
        }
    }

    private <T> Batch<T> batch(
            String token,
            Map<String, Object> request,
            int parts,
            java.util.function.Function<JsonNode, T> reader) {
        var answer = client.graphQlAnswer(token, request);
        if (answer.rateLimited()) answer.failOnErrors();
        return read(answer, data -> ProjectBoard.batch(answer, parts, reader));
    }

    private static int positive(int value) {
        if (value <= 0) throw new IllegalArgumentException("Expected a positive number");
        return value;
    }

    /** A part of a GraphQL answer that is missing or of another kind; names only the field. */
    static final class UnreadableAnswer extends RuntimeException {
        UnreadableAnswer(String field) {
            super(field, null, false, false);
        }
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

    /**
     * A single-select field's option.
     *
     * @param id the option's id, which a value names
     * @param name its name
     * @param color its color, such as {@code GRAY}
     * @param description its description
     */
    public record FieldOption(
            String id, String name, @Nullable String color, @Nullable String description) {
        /** Requires the id and name. */
        public FieldOption {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    /**
     * A project's field.
     *
     * @param id the field's id
     * @param name its name
     * @param dataType GitHub's kind of field, such as {@code SINGLE_SELECT}, {@code TEXT} or {@code
     *     NUMBER}
     * @param options a single-select field's options, empty for any other
     */
    public record ProjectField(String id, String name, String dataType, List<FieldOption> options) {
        /** Requires every part. */
        public ProjectField {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dataType, "dataType");
            options = List.copyOf(options);
        }
    }

    /**
     * An organization project and a page of its fields.
     *
     * @param id the project's node id
     * @param title its title
     * @param updatedAt its last change, a field value's included
     * @param fields this page of its fields
     * @param pageInfo the cursor of the next page of fields
     * @param cost what the query cost
     */
    public record ProjectFields(
            String id,
            String title,
            Instant updatedAt,
            List<ProjectField> fields,
            PageInfo pageInfo,
            GraphQlCost cost) {
        /** Requires every part. */
        public ProjectFields {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(updatedAt, "updatedAt");
            fields = List.copyOf(fields);
            Objects.requireNonNull(pageInfo, "pageInfo");
            Objects.requireNonNull(cost, "cost");
        }
    }

    /**
     * Another issue an issue names, as a sub-issue or a blocking one.
     *
     * @param id its node id
     * @param databaseId its {@code id} in REST, which REST's sub-issue and dependency calls take
     * @param number its number in its repository
     * @param repository its repository, {@code owner/name}
     */
    public record IssueRef(String id, long databaseId, int number, String repository) {
        /** Requires every part. */
        public IssueRef {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(repository, "repository");
        }
    }

    /**
     * The first page of issues an issue names.
     *
     * @param nodes the issues
     * @param pageInfo whether more follow
     */
    public record IssueRefs(List<IssueRef> nodes, PageInfo pageInfo) {
        /** Requires every part. */
        public IssueRefs {
            nodes = List.copyOf(nodes);
            Objects.requireNonNull(pageInfo, "pageInfo");
        }
    }

    /**
     * An issue as the board shows it.
     *
     * @param id its node id
     * @param databaseId its {@code id} in REST
     * @param number its number in its repository
     * @param title its title
     * @param body its text
     * @param state {@code OPEN} or {@code CLOSED}
     * @param stateReason why it is in its state, such as {@code COMPLETED} or {@code NOT_PLANNED}
     * @param updatedAt its last change
     * @param repository its repository, {@code owner/name}
     * @param labels the names of its first 100 labels
     * @param parentId its parent's node id, or null when it has none
     * @param subIssues its first 100 sub-issues
     * @param blockedBy the first 100 issues it is blocked by
     */
    public record BoardIssue(
            String id,
            long databaseId,
            int number,
            String title,
            @Nullable String body,
            String state,
            @Nullable String stateReason,
            Instant updatedAt,
            String repository,
            List<String> labels,
            @Nullable String parentId,
            IssueRefs subIssues,
            IssueRefs blockedBy) {
        /** Requires every part GitHub always sends. */
        public BoardIssue {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(state, "state");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(repository, "repository");
            labels = List.copyOf(labels);
            Objects.requireNonNull(subIssues, "subIssues");
            Objects.requireNonNull(blockedBy, "blockedBy");
        }
    }

    /**
     * One field value of a project item; which of option, text and number is set follows the
     * field's kind.
     *
     * @param fieldId the field's id
     * @param optionId a single-select value's option id
     * @param name a single-select value's option name
     * @param text a text value
     * @param number a number value
     */
    public record ItemValue(
            String fieldId,
            @Nullable String optionId,
            @Nullable String name,
            @Nullable String text,
            @Nullable Double number) {
        /** Requires the field. */
        public ItemValue {
            Objects.requireNonNull(fieldId, "fieldId");
        }
    }

    /**
     * A project's item.
     *
     * @param id the item's id, which a field value names
     * @param archived whether GitHub archived it, so it no longer shows on the board
     * @param issue its issue, or null for a draft or a pull request
     * @param values its single-select, text and number field values
     */
    public record ProjectItem(
            String id, boolean archived, @Nullable BoardIssue issue, List<ItemValue> values) {
        /** Requires every part. */
        public ProjectItem {
            Objects.requireNonNull(id, "id");
            values = List.copyOf(values);
        }
    }

    /**
     * A page of a project's items.
     *
     * @param items the items
     * @param pageInfo the cursor of the next page
     * @param cost what the query cost
     * @param totalCount how many items the whole project holds, on every page
     */
    public record ProjectItems(
            List<ProjectItem> items, PageInfo pageInfo, GraphQlCost cost, int totalCount) {
        /** Requires every part. */
        public ProjectItems {
            items = List.copyOf(items);
            Objects.requireNonNull(pageInfo, "pageInfo");
            Objects.requireNonNull(cost, "cost");
            if (totalCount < 0) throw new IllegalArgumentException("Expected a count of 0 or more");
        }
    }

    /**
     * An issue read by id, with its place in one project.
     *
     * @param issue the issue
     * @param itemId its item in the project, or null when it is not in it
     * @param values its field values in the project
     */
    public record IssueOnBoard(BoardIssue issue, @Nullable String itemId, List<ItemValue> values) {
        /** Requires every part. */
        public IssueOnBoard {
            Objects.requireNonNull(issue, "issue");
            values = List.copyOf(values);
        }
    }

    /**
     * Issues read by id, in the order asked.
     *
     * @param issues one per id asked, null where GitHub knows no issue by it
     * @param cost what the query cost
     */
    public record IssueBoard(List<@Nullable IssueOnBoard> issues, GraphQlCost cost) {
        /** Keeps the issues from changing; a null stays in its place. */
        public IssueBoard {
            issues = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(issues));
            Objects.requireNonNull(cost, "cost");
        }
    }

    /**
     * A field value to set or clear on a project's item; at most one of option, text and number is
     * given, and none clears the value.
     *
     * @param itemId the item's id
     * @param fieldId the field's id
     * @param optionId the option of a single-select field
     * @param text the text of a text field
     * @param number the number of a number field
     */
    public record FieldValue(
            String itemId,
            String fieldId,
            @Nullable String optionId,
            @Nullable String text,
            @Nullable Double number) {
        /** Requires the item and field and at most one value. */
        public FieldValue {
            Objects.requireNonNull(itemId, "itemId");
            Objects.requireNonNull(fieldId, "fieldId");
            int given =
                    (optionId != null ? 1 : 0) + (text != null ? 1 : 0) + (number != null ? 1 : 0);
            if (given > 1) throw new IllegalArgumentException("A field value is of one kind");
        }

        /** Sets a single-select field to an option. */
        public static FieldValue option(String itemId, String fieldId, String optionId) {
            return new FieldValue(itemId, fieldId, Objects.requireNonNull(optionId), null, null);
        }

        /** Sets a text field. */
        public static FieldValue text(String itemId, String fieldId, String text) {
            return new FieldValue(itemId, fieldId, null, Objects.requireNonNull(text), null);
        }

        /** Sets a number field. */
        public static FieldValue number(String itemId, String fieldId, double number) {
            return new FieldValue(itemId, fieldId, null, null, number);
        }

        /** Clears a field. */
        public static FieldValue clear(String itemId, String fieldId) {
            return new FieldValue(itemId, fieldId, null, null, null);
        }

        /** Returns whether this clears the field. */
        public boolean clears() {
            return optionId == null && text == null && number == null;
        }
    }

    /**
     * An option a single-select field is to have; GitHub requires all three parts.
     *
     * @param name its name
     * @param color its color, one of GitHub's: {@code GRAY}, {@code BLUE}, {@code GREEN}, {@code
     *     YELLOW}, {@code ORANGE}, {@code RED}, {@code PINK} or {@code PURPLE}
     * @param description its description, empty for none
     */
    public record NewOption(String name, String color, String description) {
        /** Requires every part. */
        public NewOption {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(color, "color");
            Objects.requireNonNull(description, "description");
        }
    }

    /**
     * A single-select field's options after they were replaced.
     *
     * @param options the options with their new ids, in their order
     * @param rateLimit what the answer's headers said of the rate limit
     */
    public record FieldOptions(List<FieldOption> options, @Nullable RateLimit rateLimit) {
        /** Keeps the options from changing. */
        public FieldOptions {
            options = List.copyOf(options);
        }
    }

    /**
     * One move of an issue on a project's own {@code Status} field.
     *
     * @param createdAt when it moved
     * @param previousStatus the status before, null when it had none
     * @param status the status after, null when it was cleared
     * @param actor the login of who moved it, null when GitHub names nobody
     * @param projectId the project's node id, null when GitHub no longer shows it
     */
    public record StatusChange(
            Instant createdAt,
            @Nullable String previousStatus,
            @Nullable String status,
            @Nullable String actor,
            @Nullable String projectId) {
        /** Requires the time. */
        public StatusChange {
            Objects.requireNonNull(createdAt, "createdAt");
        }
    }

    /**
     * A page of an issue's status moves.
     *
     * @param changes the moves, oldest first
     * @param pageInfo the cursor of the next page
     * @param cost what the query cost
     */
    public record StatusChanges(List<StatusChange> changes, PageInfo pageInfo, GraphQlCost cost) {
        /** Requires every part. */
        public StatusChanges {
            changes = List.copyOf(changes);
            Objects.requireNonNull(pageInfo, "pageInfo");
            Objects.requireNonNull(cost, "cost");
        }
    }

    /**
     * An issue to create.
     *
     * @param repositoryId the repository's node id
     * @param title its title
     * @param body its text, null for none
     * @param parentIssueId the node id of the issue to make it a sub-issue of, null for none
     * @param labelIds the node ids of its labels
     */
    public record NewIssue(
            String repositoryId,
            String title,
            @Nullable String body,
            @Nullable String parentIssueId,
            List<String> labelIds) {
        /** Requires the repository and title. */
        public NewIssue {
            Objects.requireNonNull(repositoryId, "repositoryId");
            Objects.requireNonNull(title, "title");
            labelIds = List.copyOf(labelIds);
        }
    }

    /**
     * An issue GitHub created.
     *
     * @param id its node id
     * @param databaseId its {@code id} in REST
     * @param number its number in its repository
     * @param url its page
     */
    public record CreatedIssue(String id, long databaseId, int number, URI url) {
        /** Requires every part. */
        public CreatedIssue {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(url, "url");
        }
    }
}
