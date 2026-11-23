package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectFields;
import org.fuin.porelo.forge.github.model.Issue;
import org.fuin.porelo.forge.github.model.IssueField;
import org.fuin.porelo.forge.github.model.IssuesCreateRequest;
import org.fuin.porelo.forge.github.model.IssuesUpdateRequest;
import org.fuin.porelo.forge.github.model.Label;
import org.jspecify.annotations.Nullable;

/**
 * Records every issue and project call with one token: makes two issues in the fixture repository,
 * one in the kept repository and a project of its own, records each call in turn, measures what the
 * plans leave open, and deletes all it made, also on interrupt.
 */
final class GitHubIssueScenario implements AutoCloseable {
    // A second repository of the same owner, for links across repositories.
    static final String OTHER_REPO = "forge-clients-live-2";
    // A well-formed issue node id of no issue.
    static final String UNKNOWN_ISSUE = "I_kwDOAAAAAM4AAAAB";

    private final String token;
    private final String prefix;
    private final Consumer<String> report;
    private final RecordingHttpClient transport;
    private final GitHubClient client;
    private final GitHubGraphQl graphql;
    private final GitHubClient setup;
    private final GitHubGraphQl setupGraphql;
    private final ObjectMapper json = GitHubJson.mapper(Strictness.STRICT);
    // The interrupt hook reads these from its own thread.
    private final List<String> madeIssues =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private final List<String> closableIssues =
            java.util.Collections.synchronizedList(new ArrayList<>());
    private volatile @Nullable String madeProject;
    private final Thread shutdown = new Thread(this::cleanUpQuietly);
    private boolean cleaned;

    private final Instant since =
            Instant.now().minus(10, ChronoUnit.MINUTES).truncatedTo(ChronoUnit.SECONDS);
    private long number;
    private long other;
    private String issueA = "pending";
    private String issueB = "pending";
    private long subIssueId;
    private long thirdIssueId;
    private String etag = "pending";
    private String itemsEtag = "pending";
    private int projectNumber;
    private String projectId = "pending";
    private String repositoryId = "pending";
    private List<String> itemIds = List.of();
    private String fieldId = "pending";
    private String statusFieldId = "pending";
    private String statusOptionId = "pending";
    private String doneOptionId = "pending";
    private String textFieldId = "pending";
    private String numberFieldId = "pending";
    private List<Long> fieldDatabaseIds = List.of();

    GitHubIssueScenario(HttpClient http, String token, String runId, Consumer<String> report) {
        this.token = token;
        this.prefix = "forge-client-issues-" + runId;
        this.report = report;
        transport = new RecordingHttpClient(http);
        client =
                new GitHubClient(
                        GitHubFixture.BASE, GitHubFixture.WEB, transport, Strictness.LENIENT);
        graphql = new GitHubGraphQl(GitHubFixture.BASE, transport);
        setup = new GitHubClient(GitHubFixture.BASE, GitHubFixture.WEB, http, Strictness.LENIENT);
        setupGraphql = new GitHubGraphQl(GitHubFixture.BASE, http);
    }

    private GitHubIssueOperations.Context context() {
        return new GitHubIssueOperations.Context(
                GitHubFixture.OWNER,
                GitHubFixture.REPO,
                prefix,
                number,
                other,
                subIssueId,
                thirdIssueId,
                since.toString(),
                etag,
                itemsEtag,
                projectNumber,
                projectId,
                repositoryId,
                List.of(issueA, issueB),
                UNKNOWN_ISSUE,
                itemIds,
                fieldId,
                statusFieldId,
                statusOptionId,
                textFieldId,
                numberFieldId,
                fieldDatabaseIds,
                label());
    }

    /** The run's label, with a slash, a space and a dot, so its name must reach GitHub whole. */
    private String label() {
        return prefix + "/topic a.b";
    }

    /** The account the token belongs to, which the recordings name as the reviewer. */
    String login() {
        String login =
                data("query { viewer { login } }", Map.of()).path("viewer").path("login").asText();
        if (!login.matches("[A-Za-z0-9-]{1,39}"))
            throw new IllegalStateException("The token's account has no login");
        return login;
    }

    List<GitHubScenario.Capture> run() throws Exception {
        Runtime.getRuntime().addShutdownHook(shutdown);
        repositoryId =
                data(
                                "query($o: String!, $r: String!) { repository(owner: $o, name: $r) { id } }",
                                Map.of("o", GitHubFixture.OWNER, "r", GitHubFixture.REPO))
                        .path("repository")
                        .path("id")
                        .asText();
        makeProject();
        List<GitHubScenario.Capture> captures = new ArrayList<>();
        for (var operation : GitHubIssueOperations.values()) {
            before(operation);
            var context = context();
            var body = body(operation);
            Object result = operation.invoke(client, graphql, token, context, body);
            // What a call made is owned before it is judged, so a failed check still cleans it up.
            if (operation == GitHubIssueOperations.createIssue && result instanceof Issue a)
                made(a);
            if (operation == GitHubIssueOperations.createIssues && result instanceof Batch<?> batch)
                for (var outcome : batch.outcomes())
                    if (outcome.value() instanceof GitHubGraphQl.CreatedIssue created) {
                        madeIssues.add(created.id());
                        closableIssues.add(GitHubFixture.REPO + "#" + created.number());
                    }
            requireUsefulResult(operation, result);
            after(operation, result);
            var exchanges = transport.take();
            if (exchanges.isEmpty())
                throw new IllegalStateException("Operation exchanged no response: " + operation);
            var recording = exchanges.getLast().deepCopy();
            recording.set("request_uri", exchanges.getFirst().path("request_uri"));
            recording.set("exchanges", json.valueToTree(exchanges));
            recording.set("context", json.valueToTree(context));
            if (operation.path(context).equals("/graphql"))
                recording.set(
                        "request_body",
                        json.readTree(GitHubRecordingTest.requestBody(transport.lastRequest())));
            else recording.set("request_body", body);
            captures.add(new GitHubScenario.Capture(operation.name(), recording));
        }
        measureOptionReplacement();
        return List.copyOf(captures);
    }

    private void before(GitHubIssueOperations operation) throws Exception {
        switch (operation) {
            case issue -> {
                var b =
                        setup.createIssue(
                                token, GitHubFixture.OWNER, GitHubFixture.REPO, newIssue("b"));
                made(b);
                other = b.number();
                subIssueId = b.id();
                issueB = b.nodeId();
                var c = setup.createIssue(token, GitHubFixture.OWNER, OTHER_REPO, newIssue("c"));
                made(c);
                thirdIssueId = c.id();
            }
            case issues -> awaitListed();
            case statusChanges ->
                    setupGraphql.setFieldValues(
                            token,
                            projectId,
                            List.of(
                                    FieldValue.option(
                                            itemIds.get(0), statusFieldId, doneOptionId)));
            default -> {}
        }
    }

    /** Waits until GitHub's issue list shows the run's issues, which it may do a little later. */
    private void awaitListed() throws InterruptedException {
        for (int attempt = 0; attempt < 30; attempt++) {
            var listed =
                    setup.issues(
                            token,
                            GitHubFixture.OWNER,
                            GitHubFixture.REPO,
                            "open",
                            null,
                            since,
                            1,
                            50);
            if (listed.items().stream().filter(i -> i.title().startsWith(prefix)).count() >= 2) {
                report("issue list showed the new issues after about " + 2 * attempt + " s");
                return;
            }
            Thread.sleep(2000);
        }
        throw new IllegalStateException("GitHub's list did not show the new issues in a minute");
    }

    private void after(GitHubIssueOperations operation, Object result) throws Exception {
        switch (operation) {
            case createIssue -> {
                var a = (Issue) result;
                number = a.number();
                issueA = a.nodeId();
                typeIssueA();
            }
            case issuesIfChanged -> {
                var read = (Conditional<?>) result;
                etag = Objects.requireNonNull(read.etag(), "the issue list's entity tag");
                report(
                        "issues ETag weak="
                                + etag.startsWith("W/")
                                + " remaining="
                                + remaining(read));
            }
            case issuesNotModified -> {
                var read = (Conditional<?>) result;
                report("issues 304 changed=" + read.changed() + " remaining=" + remaining(read));
            }
            case parentIssueNone -> report("parent of an issue without one: " + describe(result));
            case refusedPermissions ->
                    report(
                            "refusal: "
                                    + describe(result)
                                    + " accepted="
                                    + ((GitHubException) result).acceptedPermissions());
            case projectFields -> readFields((ProjectFields) result);
            case setFieldOptions -> {
                var options = ((GitHubGraphQl.FieldOptions) result).options();
                report(
                        "setFieldOptions answered "
                                + options.size()
                                + " options, names "
                                + options.stream().map(GitHubGraphQl.FieldOption::name).toList());
            }
            case addProjectItems -> {
                var batch = (Batch<?>) result;
                itemIds =
                        batch.outcomes().stream()
                                .map(outcome -> (String) Objects.requireNonNull(outcome.value()))
                                .toList();
            }
            case setFieldValuesPartly -> {
                var batch = (Batch<?>) result;
                var board = setupGraphql.issueBoard(token, List.of(issueA, issueB), projectId);
                report(
                        "partial batch: outcomes "
                                + batch.outcomes().stream()
                                        .map(o -> o.error() == null ? "saved" : "refused")
                                        .toList()
                                + "; part before the refused one applied="
                                + hasText(board, 0, textFieldId, "partly")
                                + ", part after applied="
                                + hasNumber(board, 1, numberFieldId, 7));
            }
            case projectItemsIfChanged -> {
                var read = (Conditional<?>) result;
                itemsEtag = Objects.requireNonNull(read.etag(), "the items' entity tag");
                report(
                        "items ETag weak="
                                + itemsEtag.startsWith("W/")
                                + " remaining="
                                + remaining(read));
            }
            case projectItemsNotModified -> {
                var read = (Conditional<?>) result;
                report("items 304 changed=" + read.changed() + " remaining=" + remaining(read));
                setup.projectItemsIfChanged(
                        token,
                        GitHubFixture.OWNER,
                        projectNumber,
                        null,
                        50,
                        fieldDatabaseIds,
                        itemsEtag);
                setupGraphql.setFieldValues(
                        token,
                        projectId,
                        List.of(FieldValue.number(itemIds.get(0), numberFieldId, 11)));
                var again =
                        setup.projectItemsIfChanged(
                                token,
                                GitHubFixture.OWNER,
                                projectNumber,
                                null,
                                50,
                                fieldDatabaseIds,
                                itemsEtag);
                report("items after only a field value changed: changed=" + again.changed());
            }
            case addCrossSubIssue -> measureCrossSubIssue();
            case issueBoard -> {
                report("issueBoard of 3 ids cost=" + ((IssueBoard) result).cost().cost());
                measureBoardCost();
            }
            case statusChanges -> {
                int read = ((GitHubGraphQl.StatusChanges) result).changes().size();
                report("status moves read: " + read);
                awaitStatusMove(read);
            }
            case createIssues -> {
                var batch = (Batch<?>) result;
                var child =
                        setup.parentIssue(
                                token,
                                GitHubFixture.OWNER,
                                GitHubFixture.REPO,
                                ((GitHubGraphQl.CreatedIssue)
                                                Objects.requireNonNull(
                                                        batch.outcomes().get(1).value()))
                                        .number());
                report(
                        "createIssue with parentIssueId: parent is the first issue="
                                + child.nodeId().equals(issueA));
            }
            default -> {}
        }
    }

    private void readFields(ProjectFields fields) {
        List<Long> named = new java.util.ArrayList<>();
        for (ProjectField field : fields.fields()) {
            if (List.of("Kind", "Key", "Rank", "Status").contains(field.name()))
                named.add(field.databaseId());
            switch (field.name()) {
                case "Kind" -> fieldId = field.id();
                case "Key" -> textFieldId = field.id();
                case "Rank" -> numberFieldId = field.id();
                case "Status" -> {
                    statusFieldId = field.id();
                    statusOptionId = field.options().getFirst().id();
                    doneOptionId = field.options().getLast().id();
                }
                default -> {}
            }
        }
        for (String id : List.of(fieldId, textFieldId, numberFieldId, statusFieldId))
            if (id.equals("pending")) throw new IllegalStateException("The project lacks a field");
        fieldDatabaseIds = List.copyOf(named);
    }

    /**
     * Sets an option on an item, replaces the field's options keeping that option's name, and says
     * whether the item kept its value and the option its id.
     */
    private void measureOptionReplacement() throws Exception {
        var before = setupGraphql.projectFields(token, GitHubFixture.OWNER, projectNumber, null);
        var kind =
                before.fields().stream()
                        .filter(f -> f.id().equals(fieldId))
                        .findFirst()
                        .orElseThrow();
        var story = kind.options().getFirst();
        setupGraphql.setFieldValues(
                token, projectId, List.of(FieldValue.option(itemIds.get(1), fieldId, story.id())));
        var replaced = setupGraphql.setFieldOptions(token, fieldId, GitHubIssueOperations.OPTIONS);
        var board = setupGraphql.issueBoard(token, List.of(issueB), projectId);
        ItemValue kept = value(board, 0, fieldId);
        report(
                "setFieldOptions again: same name kept its id="
                        + replaced.options().getFirst().id().equals(story.id())
                        + "; item kept a value="
                        + (kept != null)
                        + (kept == null
                                ? ""
                                : ", its option id the new one="
                                        + replaced.options()
                                                .getFirst()
                                                .id()
                                                .equals(kept.optionId())));
    }

    /**
     * Gives the first issue the repository's issue type {@code Task}, or its first type when it has
     * no {@code Task}, so the board's reads are recorded with a typed issue beside untyped ones.
     */
    private void typeIssueA() {
        var types =
                data(
                                "query IssueTypes($i: ID!) { node(id: $i) { ... on Issue {"
                                        + " repository { issueTypes(first: 25)"
                                        + " { nodes { id name } } } } } }",
                                Map.of("i", issueA))
                        .path("node")
                        .path("repository")
                        .path("issueTypes")
                        .path("nodes");
        if (types.isEmpty())
            throw new IllegalStateException("the repository has no issue type to give");
        var type = types.get(0);
        for (var each : types) if (each.path("name").asText().equals("Task")) type = each;
        data(
                "mutation TypeIssue($i: ID!, $t: ID!) {"
                        + " updateIssueIssueType(input: {issueId: $i, issueTypeId: $t})"
                        + " { issue { id } } }",
                Map.of("i", issueA, "t", type.path("id").asText()));
        report("issue a typed " + type.path("name").asText());
    }

    /** Says which repositories GraphQL names among an issue's sub-issues, one being elsewhere. */
    private void measureCrossSubIssue() {
        var subIssues = setupGraphql.issueSubIssues(token, issueA, null);
        report(
                "GraphQL subIssues with one in another repository lists "
                        + subIssues.nodes().stream()
                                .map(GitHubGraphQl.IssueRef::repository)
                                .toList());
    }

    /** Says what reading up to 100 real issues of the fixture repository costs in points. */
    private void measureBoardCost() {
        var ids =
                setup
                        .issues(
                                token,
                                GitHubFixture.OWNER,
                                GitHubFixture.REPO,
                                "all",
                                null,
                                null,
                                1,
                                100)
                        .items()
                        .stream()
                        .filter(i -> i.pullRequest() == null)
                        .map(Issue::nodeId)
                        .toList();
        var board = setupGraphql.issueBoard(token, ids, projectId);
        report("issueBoard of " + ids.size() + " issues cost=" + board.cost().cost());
    }

    /**
     * Reads an issue's status moves again for two minutes and says whether and when the move set
     * over the API appears after the read that missed it.
     */
    private void awaitStatusMove(int readAtOnce) throws InterruptedException {
        for (int waited = 5; waited <= 120; waited += 5) {
            Thread.sleep(5000);
            int read = setupGraphql.statusChanges(token, issueA, null).changes().size();
            if (read > readAtOnce) {
                report("status move by setFieldValues listed after about " + waited + " s");
                return;
            }
        }
        report("status move by setFieldValues not listed in 120 s");
    }

    private IssuesCreateRequest newIssue(String suffix) throws Exception {
        return json.treeToValue(
                json.valueToTree(
                        Map.of("title", prefix + "-" + suffix, "body", "Fixture issue " + suffix)),
                IssuesCreateRequest.class);
    }

    private JsonNode body(GitHubIssueOperations operation) {
        return json.valueToTree(
                switch (operation) {
                    case createIssue -> Map.of("title", prefix + "-a", "body", "Fixture issue a");
                    case updateIssue -> Map.of("body", "Fixture issue a, edited");
                    case refusedPermissions -> Map.of("name", prefix, "head_sha", "0".repeat(40));
                    case createLabel ->
                            Map.of(
                                    "name",
                                    label(),
                                    "color",
                                    "0e8a16",
                                    "description",
                                    "Fixture label");
                    default -> Map.of();
                });
    }

    /** Deletes this run's label and any an earlier run left behind. */
    private void removeLabels() {
        var labels = setup.labels(token, GitHubFixture.OWNER, GitHubFixture.REPO, 1, 100);
        for (var label : labels.items()) {
            if (label.name().startsWith("forge-client-issues-")
                    && label.name().endsWith("/topic a.b"))
                setup.deleteLabel(token, GitHubFixture.OWNER, GitHubFixture.REPO, label.name());
        }
    }

    private void made(Issue issue) {
        madeIssues.add(issue.nodeId());
        closableIssues.add(repositoryOf(issue) + "#" + issue.number());
    }

    private static String repositoryOf(Issue issue) {
        String path = issue.repositoryUrl().getPath();
        return path.substring(path.lastIndexOf('/') + 1);
    }

    private void makeProject() throws Exception {
        String org =
                data(
                                "query($o: String!) { organization(login: $o) { id } }",
                                Map.of("o", GitHubFixture.OWNER))
                        .path("organization")
                        .path("id")
                        .asText();
        JsonNode project =
                data(
                                "mutation($i: CreateProjectV2Input!) { createProjectV2(input: $i) { projectV2 { id number } } }",
                                Map.of("i", Map.of("ownerId", org, "title", prefix)))
                        .path("createProjectV2")
                        .path("projectV2");
        projectId = project.path("id").asText();
        madeProject = projectId;
        projectNumber = project.path("number").asInt();
        String field =
                "mutation($i: CreateProjectV2FieldInput!) { createProjectV2Field(input: $i) { projectV2Field { ... on ProjectV2FieldCommon { id } } } }";
        data(
                field,
                Map.of(
                        "i",
                        Map.of(
                                "projectId",
                                projectId,
                                "dataType",
                                "SINGLE_SELECT",
                                "name",
                                "Kind",
                                "singleSelectOptions",
                                List.of(
                                        Map.of(
                                                "name",
                                                "Story",
                                                "color",
                                                "GRAY",
                                                "description",
                                                "")))));
        data(field, Map.of("i", Map.of("projectId", projectId, "dataType", "TEXT", "name", "Key")));
        data(
                field,
                Map.of("i", Map.of("projectId", projectId, "dataType", "NUMBER", "name", "Rank")));
    }

    private JsonNode data(String query, Map<String, Object> variables) {
        var answer = setup.graphQlAnswer(token, Map.of("query", query, "variables", variables));
        answer.failOnErrors();
        return answer.envelope().path("data");
    }

    /** Deletes the project and every issue made; one GitHub keeps is closed and said. */
    void cleanUp() {
        synchronized (this) {
            if (cleaned) return;
            cleaned = true;
        }
        // An interrupted run must still send its cleanup; the flag is set again afterwards.
        boolean interrupted = Thread.interrupted();
        try {
            removeAll();
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private void removeAll() {
        List<String> failures = new ArrayList<>();
        List<String> issues;
        List<String> closable;
        synchronized (madeIssues) {
            issues = List.copyOf(madeIssues);
            closable = List.copyOf(closableIssues);
        }
        if (madeProject != null) {
            try {
                data(
                        "mutation($i: DeleteProjectV2Input!) { deleteProjectV2(input: $i) { projectV2 { id } } }",
                        Map.of("i", Map.of("projectId", madeProject)));
            } catch (RuntimeException failed) {
                failures.add("project");
            }
        }
        // Always, so a label an earlier run left behind goes too.
        try {
            removeLabels();
        } catch (RuntimeException failed) {
            failures.add("label");
        }
        int deleted = 0;
        List<String> kept = new ArrayList<>();
        for (int index = 0; index < issues.size(); index++) {
            try {
                data(
                        "mutation($i: DeleteIssueInput!) { deleteIssue(input: $i) { clientMutationId } }",
                        Map.of("i", Map.of("issueId", issues.get(index))));
                deleted++;
            } catch (RuntimeException refused) {
                // Deleting an issue takes a repository's administrator; a member closes it.
                kept.add(closable.get(index));
            }
        }
        int closed = close(kept);
        report(
                "cleanup: deleted "
                        + deleted
                        + ", closed "
                        + closed
                        + " of "
                        + issues.size()
                        + " issues, project "
                        + (failures.contains("project") ? "kept" : "deleted"));
        if (closed < kept.size()) failures.add((kept.size() - closed) + " issues left open");
        if (!failures.isEmpty())
            throw new IllegalStateException("Could not remove what the run made: " + failures);
    }

    private int close(List<String> issues) {
        int closed = 0;
        for (String closable : issues) {
            try {
                int hash = closable.lastIndexOf('#');
                long issueNumber = Long.parseLong(closable.substring(hash + 1));
                setup.updateIssue(
                        token,
                        GitHubFixture.OWNER,
                        closable.substring(0, hash),
                        issueNumber,
                        json.treeToValue(
                                json.valueToTree(
                                        Map.of("state", "closed", "state_reason", "not_planned")),
                                IssuesUpdateRequest.class));
                closed++;
            } catch (Exception failed) {
                // Counted as left open.
            }
        }
        return closed;
    }

    private void cleanUpQuietly() {
        try {
            cleanUp();
        } catch (RuntimeException failed) {
            report(failed.getMessage());
        }
    }

    @Override
    public void close() {
        try {
            cleanUp();
        } finally {
            try {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            } catch (IllegalStateException shuttingDown) {
                // The hook runs or ran already.
            }
        }
    }

    private void report(String line) {
        report.accept("MEASURED " + line);
    }

    private static String remaining(Conditional<?> read) {
        return read.rateLimit() == null ? "none" : String.valueOf(read.rateLimit().remaining());
    }

    private static String describe(Object result) {
        return result instanceof GitHubException refused
                ? "refused " + refused.status()
                : "answered";
    }

    private static @Nullable ItemValue value(IssueBoard board, int index, String field) {
        var issue = Objects.requireNonNull(board.issues().get(index), "an issue on the board");
        return issue.values().stream()
                .filter(v -> v.fieldId().equals(field))
                .findFirst()
                .orElse(null);
    }

    private static boolean hasText(IssueBoard board, int index, String field, String text) {
        var found = value(board, index, field);
        return found != null && text.equals(found.text());
    }

    private static boolean hasNumber(IssueBoard board, int index, String field, double number) {
        var found = value(board, index, field);
        return found != null && found.number() != null && found.number() == number;
    }

    /** What a recording must hold to be worth replaying. */
    static void requireUsefulResult(GitHubIssueOperations operation, Object result) {
        assertThat(result).as("%s must return a payload", operation).isNotNull();
        switch (operation) {
            case parentIssueNone ->
                    assertThat(((GitHubException) result).status())
                            .as("an issue without a parent answers 404")
                            .isEqualTo(404);
            case refusedPermissions ->
                    assertThat(((GitHubException) result).status())
                            .as("a call the token may not make is refused")
                            .isEqualTo(403);
            case issuesNotModified, projectItemsNotModified ->
                    assertThat(((Conditional<?>) result).changed())
                            .as("%s answers not changed", operation)
                            .isFalse();
            case issuesIfChanged, projectItemsIfChanged ->
                    assertThat(((Conditional<?>) result).etag())
                            .as("%s carries an entity tag", operation)
                            .isNotBlank();
            case createLabel -> {
                var label = (Label) result;
                assertThat(label.name())
                        .as("the label keeps its whole name")
                        .endsWith("/topic a.b");
                assertThat(label.color()).as("its color, without #").isEqualTo("0e8a16");
                assertThat(label.description()).as("its description").isEqualTo("Fixture label");
            }
            case labels ->
                    assertThat(((Page<?>) result).items())
                            .as("the repository's labels include the run's")
                            .anyMatch(l -> ((Label) l).name().endsWith("/topic a.b"));
            case issueFields -> {
                @SuppressWarnings("unchecked")
                var fields = (List<IssueField>) result;
                var priority =
                        fields.stream()
                                .filter(f -> f.name().equals("Priority"))
                                .findFirst()
                                .orElseThrow(() -> new AssertionError("no field Priority"));
                assertThat(priority.dataType())
                        .as("Priority is a single select")
                        .isEqualTo(IssueField.DataTypeEnum.SINGLE_SELECT);
                assertThat(priority.options())
                        .as("Priority's options carry their ids and names")
                        .isNotEmpty()
                        .allMatch(o -> o.id() > 0 && !o.name().isBlank());
            }
            case issues, subIssues, blockedBy, blocking ->
                    assertThat(((Page<?>) result).items())
                            .as("%s lists an issue", operation)
                            .isNotEmpty();
            case addProjectItems, setFieldValues, createIssues ->
                    assertThat(((Batch<?>) result).complete())
                            .as("%s saved every part", operation)
                            .isTrue();
            case setFieldValuesPartly ->
                    assertThat(((Batch<?>) result).complete()).as("a part was refused").isFalse();
            case itemValues ->
                    assertThat(((GitHubGraphQl.ListPage<?>) result).nodes())
                            .as("the item's values are read by its id")
                            .isNotEmpty();
            case projectItems ->
                    assertThat(((GitHubGraphQl.ProjectItems) result).items())
                            .as("the board has items")
                            .isNotEmpty();
            case issueBoard -> {
                var board = ((IssueBoard) result).issues();
                assertThat(board).as("one place per id").hasSize(3);
                assertThat(board.get(0)).as("the first issue is read").isNotNull();
                assertThat(board.get(2)).as("an unknown id reads null").isNull();
            }
            default -> {}
        }
    }

    static Map<String, String> identities(String login) {
        Map<String, String> identities = new LinkedHashMap<>();
        identities.put(GitHubFixture.OWNER, "fixture-org");
        identities.put(OTHER_REPO, "fixture-other-repo");
        identities.put(GitHubFixture.REPO, "fixture-repo");
        identities.put(login, "fixture-reviewer");
        return identities;
    }
}
