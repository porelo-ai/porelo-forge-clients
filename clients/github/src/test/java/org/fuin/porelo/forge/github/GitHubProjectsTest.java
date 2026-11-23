package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewOption;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;

/**
 * A project's board over GraphQL: what each request carries, how a batch's parts are told apart,
 * and how an answer's errors, unknown ids and unmodelled union variants are read.
 */
class GitHubProjectsTest {
    private static final URI BASE = URI.create("https://api.github.com/");
    private static final String TOKEN = "synthetic-access-token";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COST =
            "\"rateLimit\":{\"cost\":1,\"remaining\":4999,\"resetAt\":\"2026-01-01T01:00:00Z\"}";
    private static final String PAGE = "\"pageInfo\":{\"hasNextPage\":false,\"endCursor\":null}";
    private static final String REFS = "{\"totalCount\":0,\"nodes\":[]," + PAGE + "}";

    private static ReplayHttpClient answering(String body) {
        return new ReplayHttpClient().answer(200, Map.of(), body);
    }

    private static GitHubGraphQl graphQl(ReplayHttpClient http) {
        return new GitHubGraphQl(BASE, http, Strictness.STRICT);
    }

    private static JsonNode request(ReplayHttpClient http) throws Exception {
        assertThat(http.requests()).as("one request").hasSize(1);
        return JSON.readTree(GitHubRecordingTest.requestBody(http.requests().get(0)));
    }

    private static String issue(String id) {
        return issue(id, "null");
    }

    private static String issue(String id, String closedAt) {
        return issue(id, closedAt, "{\"totalCount\":0,\"nodes\":[]}");
    }

    private static String issue(String id, String closedAt, String fieldValues) {
        return "{\"__typename\":\"Issue\",\"id\":\""
                + id
                + "\",\"databaseId\":5000000001,\"number\":7,\"title\":\"T\",\"body\":null,"
                + "\"state\":\"OPEN\",\"stateReason\":null,\"updatedAt\":\"2026-01-01T00:00:00Z\","
                + "\"closedAt\":"
                + closedAt
                + ",\"issueFieldValues\":"
                + fieldValues
                + ","
                + "\"repository\":{\"nameWithOwner\":\"o/r\"},"
                + "\"labels\":{\"totalCount\":1,\"nodes\":[{\"name\":\"story\"}],"
                + PAGE
                + "},\"parent\":null,\"subIssues\":"
                + REFS
                + ",\"blockedBy\":"
                + REFS;
    }

    private static final String TYPED =
            issue("I_1", "\"2026-02-03T04:05:06Z\"") + ",\"issueType\":{\"name\":\"Story\"}";
    private static final String UNTYPED = issue("I_2") + ",\"issueType\":null";

    // Prevents a caller's text from becoming part of a GraphQL document.
    @Test
    void callerValuesTravelOnlyInVariables() throws Exception {
        String hostile = "x\") { __schema { types { name } } } #";
        var http =
                answering(
                        "{\"data\":{\"a0\":{\"issue\":{\"id\":\"I_1\",\"databaseId\":1,\"number\":1,"
                                + "\"url\":\"https://github.com/o/r/issues/1\"}}}}");
        graphQl(http)
                .createIssues(
                        TOKEN, List.of(new NewIssue("R_1", hostile, hostile, null, List.of())));
        JsonNode sent = request(http);
        assertThat(sent.path("query").asText())
                .as("no caller text in the document")
                .doesNotContain(hostile)
                .doesNotContain("x\\\"");
        assertThat(sent.path("variables").path("v0").path("title").asText())
                .as("the title as a variable")
                .isEqualTo(hostile);
    }

    // Prevents values for several items from collapsing into one part, or a part from going unsent.
    @Test
    void setFieldValuesSendsOneAliasPerValue() throws Exception {
        var http =
                answering(
                        "{\"data\":{\"a0\":{\"projectV2Item\":{\"id\":\"PVTI_1\"}},"
                                + "\"a1\":{\"projectV2Item\":{\"id\":\"PVTI_2\"}},"
                                + "\"a2\":{\"projectV2Item\":{\"id\":\"PVTI_3\"}}}}");
        var batch =
                graphQl(http)
                        .setFieldValues(
                                TOKEN,
                                "P_1",
                                List.of(
                                        FieldValue.option("PVTI_1", "F_STATE", "OPT_1"),
                                        FieldValue.number("PVTI_2", "F_RANK", 3),
                                        FieldValue.clear("PVTI_3", "F_STATE")));
        JsonNode sent = request(http);
        String query = sent.path("query").asText();
        assertThat(query)
                .as("the first two update")
                .contains(
                        "a0: updateProjectV2ItemFieldValue(input: $v0)",
                        "a1: updateProjectV2ItemFieldValue(input: $v1)");
        assertThat(query)
                .as("the third clears")
                .contains(
                        "a2: clearProjectV2ItemFieldValue(input: $v2)",
                        "$v2: ClearProjectV2ItemFieldValueInput!");
        assertThat(
                        sent.path("variables")
                                .path("v0")
                                .path("value")
                                .path("singleSelectOptionId")
                                .asText())
                .as("the option")
                .isEqualTo("OPT_1");
        assertThat(sent.path("variables").path("v1").path("value").path("number").asDouble())
                .as("the number")
                .isEqualTo(3.0);
        assertThat(sent.path("variables").path("v2").has("value"))
                .as("a clear sends no value")
                .isFalse();
        assertThat(batch.complete()).as("all saved").isTrue();
        assertThat(batch.outcomes())
                .extracting(Batch.Outcome::value)
                .as("each item in order")
                .containsExactly("PVTI_1", "PVTI_2", "PVTI_3");
    }

    // Prevents a partly saved batch from reading as done, and a refusal from carrying the token.
    @Test
    void aBatchSaysWhichAliasFailed() {
        var http =
                answering(
                        "{\"data\":{\"a0\":{\"item\":{\"id\":\"PVTI_1\"}},\"a1\":null},"
                                + "\"errors\":[{\"type\":\"FORBIDDEN\",\"path\":[\"a1\"],"
                                + "\"message\":\"no access with "
                                + TOKEN
                                + "\"}]}");
        var batch = graphQl(http).addProjectItems(TOKEN, "P_1", List.of("I_1", "I_2"));
        assertThat(batch.complete()).as("not every part was saved").isFalse();
        assertThat(batch.outcomes().get(0).value()).as("the first saved").isEqualTo("PVTI_1");
        assertThat(batch.outcomes().get(1).error())
                .as("the second refused")
                .contains("no access")
                .doesNotContain(TOKEN);
    }

    // Prevents the parts GitHub saved before a rate limit from being lost and made twice on retry.
    @Test
    void aRateLimitedPartKeepsThePartsSaved() {
        var http =
                answering(
                        "{\"data\":{\"a0\":{\"item\":{\"id\":\"PVTI_1\"}},\"a1\":null,\"a2\":null},"
                                + "\"errors\":["
                                + "{\"type\":\"FORBIDDEN\",\"path\":[\"a1\"],\"message\":\"no\"},"
                                + "{\"type\":\"RATE_LIMITED\",\"path\":[\"a2\"],\"message\":\"limit\"}]}");
        var batch = graphQl(http).addProjectItems(TOKEN, "P_1", List.of("I_1", "I_2", "I_3"));
        assertThat(batch.outcomes().get(0).value()).as("the saved part").isEqualTo("PVTI_1");
        assertThat(batch.outcomes().get(1).rateLimited()).as("a refusal is no limit").isFalse();
        assertThat(batch.outcomes().get(2).rateLimited()).as("the limited part").isTrue();
        assertThat(batch.outcomes().get(2).error()).as("GitHub's words").isEqualTo("limit");
        assertThat(batch.complete()).as("not every part was saved").isFalse();
        assertThat(batch.rateLimited()).as("a later try may pass").isTrue();
        assertThat(batch.delay(java.time.Instant.EPOCH)).as("how long to wait").isNotNull();
    }

    // Prevents a second error on one part from losing the parts saved, and hiding its rate limit.
    @Test
    void aPartWithTwoErrorsKeepsThePartsSavedAndItsRateLimit() {
        var http =
                new ReplayHttpClient()
                        .answer(
                                200,
                                Map.of("retry-after", List.of("30")),
                                "{\"data\":{\"a0\":{\"item\":{\"id\":\"PVTI_1\"}},\"a1\":null},"
                                        + "\"errors\":["
                                        + "{\"type\":\"FORBIDDEN\",\"path\":[\"a1\"],\"message\":\"no\"},"
                                        + "{\"type\":\"RATE_LIMITED\",\"path\":[\"a1\"],\"message\":\"limit\"}]}");
        var batch = graphQl(http).addProjectItems(TOKEN, "P_1", List.of("I_1", "I_2"));
        assertThat(batch.outcomes().get(0).value()).as("the saved part").isEqualTo("PVTI_1");
        assertThat(batch.outcomes().get(1).rateLimited()).as("its rate limit kept").isTrue();
        assertThat(batch.delay(java.time.Instant.EPOCH))
                .as("the answer's own wait")
                .isEqualTo(java.time.Duration.ofSeconds(30));
    }

    // Prevents a batch refused for other reasons from reading as one to try again later.
    @Test
    void aBatchWithoutARateLimitAsksNoWait() {
        var http =
                answering(
                        "{\"data\":{\"a0\":null},\"errors\":["
                                + "{\"type\":\"FORBIDDEN\",\"path\":[\"a0\"],\"message\":\"no\"}]}");
        var batch = graphQl(http).addProjectItems(TOKEN, "P_1", List.of("I_1"));
        assertThat(batch.rateLimited()).as("no rate limit").isFalse();
        assertThat(batch.delay(java.time.Instant.EPOCH)).as("no wait").isNull();
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a saved part is never rate-limited")
                .isThrownBy(() -> new Batch.Outcome<>("PVTI_1", null, true));
    }

    // Prevents a rate limit on the whole request, or an unreadable batch, from reading as a batch.
    @Test
    void aRateLimitNamingNoPartFailsTheWholeBatch() {
        var http =
                answering(
                        "{\"data\":null,\"errors\":[{\"type\":\"RATE_LIMITED\",\"message\":\"limit\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("the whole call fails")
                .isThrownBy(
                        () -> graphQl(http).addProjectItems(TOKEN, "P_1", List.of("I_1", "I_2")))
                .satisfies(ex -> assertThat(ex.rateLimited()).as("as a rate limit").isTrue());
        var unnamed =
                answering(
                        "{\"data\":{\"a0\":{\"item\":{\"id\":\"PVTI_1\"}}},\"errors\":[{\"message\":\"broken\"}]}");
        assertThatExceptionOfType(GitHubException.class)
                .as("an error naming no part fails the call")
                .isThrownBy(() -> graphQl(unnamed).addProjectItems(TOKEN, "P_1", List.of("I_1")));
        var silent = answering("{\"data\":{}}");
        assertThatExceptionOfType(GitHubException.class)
                .as("a part with neither value nor error fails the call")
                .isThrownBy(() -> graphQl(silent).addProjectItems(TOKEN, "P_1", List.of("I_1")));
    }

    // Prevents a request GitHub would refuse, or one that writes a value twice, from being sent.
    @Test
    void badBatchesAreRefusedBeforeSending() {
        var http = answering("{}");
        var graphQl = graphQl(http);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("empty")
                .isThrownBy(() -> graphQl.addProjectItems(TOKEN, "P_1", List.of()));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("twice")
                .isThrownBy(() -> graphQl.addProjectItems(TOKEN, "P_1", List.of("I_1", "I_1")));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("blank")
                .isThrownBy(() -> graphQl.issueBoard(TOKEN, List.of(" "), "P_1"));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("over 50 parts")
                .isThrownBy(
                        () ->
                                graphQl.addProjectItems(
                                        TOKEN,
                                        "P_1",
                                        java.util.stream.IntStream.range(0, 51)
                                                .mapToObj(i -> "I_" + i)
                                                .toList()));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("two values for one item and field")
                .isThrownBy(
                        () ->
                                graphQl.setFieldValues(
                                        TOKEN,
                                        "P_1",
                                        List.of(
                                                FieldValue.text("PVTI_1", "F_1", "a"),
                                                FieldValue.clear("PVTI_1", "F_1"))));
        assertThat(http.requests()).as("nothing sent").isEmpty();
    }

    // Prevents an unknown id, or another project's values, from being read as this project's issue.
    @Test
    void issueBoardReadsOptionIds() {
        String item =
                ",\"projectItems\":{\"nodes\":["
                        + "{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},\"fieldValues\":{\"totalCount\":3,\"nodes\":["
                        + "{\"__typename\":\"ProjectV2ItemFieldSingleSelectValue\",\"optionId\":\"OPT_1\",\"name\":\"Todo\",\"field\":{\"id\":\"F_STATE\"}},"
                        + "{\"__typename\":\"ProjectV2ItemFieldRepositoryValue\"},"
                        + "{\"__typename\":\"ProjectV2ItemFieldTextValue\",\"text\":\"OLD-1\",\"field\":{\"id\":\"F_ID\"}}],"
                        + PAGE
                        + "}},"
                        + "{\"id\":\"PVTI_OTHER\",\"project\":{\"id\":\"P_2\"},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}";
        var http =
                answering(
                        "{\"data\":{\"nodes\":["
                                + issue("I_1")
                                + item
                                + ",null,{}],"
                                + COST
                                + "},\"errors\":[{\"type\":\"NOT_FOUND\",\"path\":[\"nodes\",1],\"message\":\"no\"}]}");
        var board = graphQl(http).issueBoard(TOKEN, List.of("I_1", "I_X", "PR_1"), "P_1");
        assertThat(board.issues()).as("one place per id").hasSize(3);
        var first = board.issues().get(0);
        assertThat(first).as("the issue read").isNotNull();
        assertThat(first.itemId()).as("this project's item").isEqualTo("PVTI_1");
        assertThat(first.values()).as("the modelled values only").hasSize(2);
        assertThat(first.values().get(0).optionId()).as("the option id").isEqualTo("OPT_1");
        assertThat(first.issue().databaseId()).as("an id past 32 bits").isEqualTo(5_000_000_001L);
        assertThat(first.issue().labels()).as("its labels").containsExactly("story");
        assertThat(board.issues().get(1)).as("an unknown id").isNull();
        assertThat(board.issues().get(2)).as("an id of no issue").isNull();
        assertThat(board.cost().cost()).as("its cost").isEqualTo(1);
    }

    // Prevents a person's moves from being read without the status they came from.
    @Test
    void statusChangesReadsThePreviousStatus() {
        var http =
                answering(
                        "{\"data\":{\"node\":{\"timelineItems\":{\"nodes\":["
                                + "{\"__typename\":\"ProjectV2ItemStatusChangedEvent\",\"createdAt\":\"2026-01-01T00:00:00Z\","
                                + "\"previousStatus\":\"Todo\",\"status\":\"Done\",\"actor\":{\"login\":\"someone\"},\"project\":{\"id\":\"P_1\"}},"
                                + "{\"__typename\":\"AddedToProjectV2Event\"}],"
                                + PAGE
                                + "}},"
                                + COST
                                + "}}");
        var changes = graphQl(http).statusChanges(TOKEN, "I_1", null);
        assertThat(changes.changes()).as("only status moves").hasSize(1);
        assertThat(changes.changes().get(0).previousStatus())
                .as("where it came from")
                .isEqualTo("Todo");
        assertThat(changes.changes().get(0).actor()).as("who moved it").isEqualTo("someone");
    }

    // Prevents an option from being sent without the color or description GitHub requires.
    @Test
    void setFieldOptionsSendsEachOption() throws Exception {
        var http =
                answering(
                        "{\"data\":{\"updateProjectV2Field\":{\"projectV2Field\":{\"options\":["
                                + "{\"id\":\"n1\",\"name\":\"Todo\",\"color\":\"GRAY\",\"description\":\"\"}]}}}}");
        var options =
                graphQl(http)
                        .setFieldOptions(TOKEN, "F_1", List.of(new NewOption("Todo", "GRAY", "")));
        JsonNode option =
                request(http).path("variables").path("input").path("singleSelectOptions").path(0);
        assertThat(option.path("color").asText()).as("the color sent").isEqualTo("GRAY");
        assertThat(option.has("description")).as("the description sent").isTrue();
        assertThat(options.options().get(0).id()).as("the new id read").isEqualTo("n1");
    }

    // Prevents a project's board from being read without its fields' option ids.
    @Test
    void projectFieldsAndItemsAreRead() {
        var fields =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"id\":\"P_1\",\"title\":\"Board\",\"updatedAt\":\"2026-01-01T00:00:00Z\","
                                + "\"fields\":{\"nodes\":["
                                + "{\"__typename\":\"ProjectV2SingleSelectField\",\"id\":\"F_STATE\",\"databaseId\":448097099,\"name\":\"Status\",\"dataType\":\"SINGLE_SELECT\",\"options\":[{\"id\":\"OPT_1\",\"name\":\"Todo\",\"color\":\"GRAY\",\"description\":\"\"}]},"
                                + "{\"__typename\":\"ProjectV2Field\",\"id\":\"F_ID\",\"databaseId\":448097434,\"name\":\"Id\",\"dataType\":\"TEXT\"},"
                                + "{\"__typename\":\"ProjectV2IterationField\"}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var read = graphQl(fields).projectFields(TOKEN, "o", 1, null);
        assertThat(read.fields()).as("the modelled fields").hasSize(2);
        assertThat(read.fields().get(0).options().get(0).id())
                .as("an option id")
                .isEqualTo("OPT_1");
        assertThat(read.fields())
                .extracting(GitHubGraphQl.ProjectField::databaseId)
                .as("each field's numeric id, which the REST item list names fields by")
                .containsExactly(448097099L, 448097434L);

        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":205,\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + issue("I_1")
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}},"
                                + "{\"id\":\"PVTI_2\",\"isArchived\":true,\"content\":{\"__typename\":\"DraftIssue\"},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var page = graphQl(items).projectItems(TOKEN, "o", 1, null);
        assertThat(page.items()).as("both items").hasSize(2);
        assertThat(page.items().get(0).issue()).as("an issue item").isNotNull();
        assertThat(page.items().get(1).issue()).as("a draft has no issue").isNull();
        assertThat(page.items().get(0).archived()).as("a live item").isFalse();
        assertThat(page.items().get(1).archived()).as("an archived item").isTrue();
        assertThat(page.totalCount())
                .as("the whole project's item count, beyond this page")
                .isEqualTo(205);
    }

    // Prevents a story from being known only by its label: a project's items read each issue's
    // type name, or null when the issue has none.
    @Test
    void anItemsIssueReadsItsType() throws Exception {
        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":2,\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + TYPED
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}},"
                                + "{\"id\":\"PVTI_2\",\"isArchived\":false,\"content\":"
                                + UNTYPED
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var page = graphQl(items).projectItems(TOKEN, "o", 1, null);
        assertThat(request(items).path("query").asText())
                .as("the item read asks for the issue's type")
                .contains("issueType { name }");
        assertThat(page.items().get(0).issue().issueType()).as("a typed issue").isEqualTo("Story");
        assertThat(page.items().get(1).issue().issueType()).as("an untyped issue").isNull();
    }

    // Prevents a story's test day from being guessed: a project's items read when each issue was
    // closed, or null while it never was.
    @Test
    void anItemsIssueReadsWhenItWasClosed() throws Exception {
        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":2,\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + TYPED
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}},"
                                + "{\"id\":\"PVTI_2\",\"isArchived\":false,\"content\":"
                                + UNTYPED
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var page = graphQl(items).projectItems(TOKEN, "o", 1, null);
        assertThat(request(items).path("query").asText())
                .as("the item read asks when the issue was closed")
                .contains(" closedAt ");
        assertThat(page.items().get(0).issue().closedAt())
                .as("a closed issue's time")
                .isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
        assertThat(page.items().get(1).issue().closedAt()).as("an open issue's").isNull();
    }

    // Prevents a priority set on GitHub from going unseen: each board issue carries its
    // single-select field values by field and option name, other kinds left out, as GitHub sends
    // them.
    @Test
    void anItemsIssueReadsItsSelectedFieldOptions() throws Exception {
        String values =
                "{\"totalCount\":3,\"nodes\":["
                        + "{\"__typename\":\"IssueFieldSingleSelectValue\",\"name\":\"Urgent\","
                        + "\"field\":{\"name\":\"Priority\"}},"
                        + "{\"__typename\":\"IssueFieldDateValue\"},"
                        + "{\"__typename\":\"IssueFieldSingleSelectValue\",\"name\":\"Low\","
                        + "\"field\":{\"name\":\"Effort\"}}]}";
        var items = answering(itemsWith(issue("I_1", "null", values) + ",\"issueType\":null"));
        var page = graphQl(items).projectItems(TOKEN, "o", 1, null);
        assertThat(request(items).path("query").asText())
                .as("the item read asks for the issue's field values")
                .contains("issueFieldValues(first: 100)");
        assertThat(page.items().get(0).issue().fieldOptions())
                .as("the select values, the date left out")
                .containsExactly(
                        new GitHubGraphQl.IssueFieldOption("Priority", "Urgent"),
                        new GitHubGraphQl.IssueFieldOption("Effort", "Low"));

        String more = "{\"totalCount\":101,\"nodes\":[{\"__typename\":\"IssueFieldDateValue\"}]}";
        var cut = answering(itemsWith(issue("I_1", "null", more) + ",\"issueType\":null"));
        assertThatExceptionOfType(GitHubException.class)
                .as("more values than one read holds, which would drop some")
                .isThrownBy(() -> graphQl(cut).projectItems(TOKEN, "o", 1, null));
    }

    // Prevents a priority from being read as missing when GitHub's answer is broken: a select
    // value without its field's or its option's name fails the read.
    @Test
    void aSelectedFieldOptionWithoutItsNamesFailsTheRead() {
        for (String value :
                List.of(
                        "{\"__typename\":\"IssueFieldSingleSelectValue\",\"name\":\"High\",\"field\":{}}",
                        "{\"__typename\":\"IssueFieldSingleSelectValue\","
                                + "\"field\":{\"name\":\"Priority\"}}")) {
            String values = "{\"totalCount\":1,\"nodes\":[" + value + "]}";
            var broken = answering(itemsWith(issue("I_1", "null", values) + ",\"issueType\":null"));
            assertThatExceptionOfType(GitHubException.class)
                    .as("a select value reading %s", value)
                    .isThrownBy(() -> graphQl(broken).projectItems(TOKEN, "o", 1, null));
        }
    }

    private static String itemsWith(String issue) {
        return "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                + issue
                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                + PAGE
                + "}}],"
                + PAGE
                + "}}},"
                + COST
                + "}}";
    }

    // Prevents a closed issue from passing as never closed when GitHub's answer is broken: a
    // missing or unreadable closing time fails the read.
    @Test
    void anUnreadableClosingTimeFailsTheRead() {
        for (String typed :
                List.of(
                        issue("I_1").replace("\"closedAt\":null,", ""),
                        issue("I_1", "123"),
                        issue("I_1", "\"yesterday\""))) {
            var items =
                    answering(
                            "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                                    + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                    + typed
                                    + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                    + PAGE
                                    + "}}],"
                                    + PAGE
                                    + "}}},"
                                    + COST
                                    + "}}");
            assertThatExceptionOfType(GitHubException.class)
                    .as("an issue reading <%s>", typed)
                    .isThrownBy(() -> graphQl(items).projectItems(TOKEN, "o", 1, null));
        }
    }

    // Prevents a story's test day from being guessed: an issue read by its node id reads when it
    // was closed, or null while it never was.
    @Test
    void anIssueReadByIdReadsWhenItWasClosed() throws Exception {
        String onBoard = ",\"projectItems\":{\"nodes\":[]," + PAGE + "}}";
        var board =
                answering(
                        "{\"data\":{\"nodes\":["
                                + TYPED
                                + onBoard
                                + ","
                                + UNTYPED
                                + onBoard
                                + "],"
                                + COST
                                + "}}");
        var issues = graphQl(board).issueBoard(TOKEN, List.of("I_1", "I_2"), "P_1").issues();
        assertThat(issues.get(0).issue().closedAt())
                .as("a closed issue's time")
                .isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
        assertThat(issues.get(1).issue().closedAt()).as("an open issue's").isNull();
    }

    // Prevents a story from being known only by its label: an issue read by its node id reads
    // its type name, or null when the issue has none.
    @Test
    void anIssueReadByIdReadsItsType() throws Exception {
        String onBoard =
                ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},"
                        + "\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}";
        var board =
                answering(
                        "{\"data\":{\"nodes\":["
                                + TYPED
                                + onBoard
                                + ","
                                + UNTYPED
                                + onBoard
                                + "],"
                                + COST
                                + "}}");
        var issues = graphQl(board).issueBoard(TOKEN, List.of("I_1", "I_2"), "P_1").issues();
        assertThat(request(board).path("query").asText())
                .as("the read by node id asks for the issue's type")
                .contains("issueType { name }");
        assertThat(issues.get(0).issue().issueType()).as("a typed issue").isEqualTo("Story");
        assertThat(issues.get(1).issue().issueType()).as("an untyped issue").isNull();
    }

    // Prevents a blocker the token cannot read from passing as no blocker: an issue's lists carry
    // GitHub's whole count beside the nodes it shows.
    @Test
    void anIssuesBlockersAndSubIssuesCarryGitHubsCount() throws Exception {
        String ref =
                "{\"id\":\"I_9\",\"databaseId\":9,\"number\":9,"
                        + "\"repository\":{\"nameWithOwner\":\"o/r\"}}";
        String counted =
                issue("I_1")
                                .replace(
                                        "\"subIssues\":" + REFS,
                                        "\"subIssues\":{\"totalCount\":3,\"nodes\":[],"
                                                + PAGE
                                                + "}")
                                .replace(
                                        "\"blockedBy\":" + REFS,
                                        "\"blockedBy\":{\"totalCount\":2,\"nodes\":["
                                                + ref
                                                + "],"
                                                + PAGE
                                                + "}")
                        + ",\"issueType\":null";
        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + counted
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var issue = graphQl(items).projectItems(TOKEN, "o", 1, null).items().get(0).issue();
        assertThat(issue.blockedBy().totalCount())
                .as("every blocker GitHub counts, one of them unreadable")
                .isEqualTo(2);
        assertThat(issue.blockedBy().nodes()).as("the blockers it shows").hasSize(1);
        assertThat(issue.subIssues().totalCount()).as("every sub-issue GitHub counts").isEqualTo(3);
    }

    // Prevents one of the board's reads from leaving the counts out, so its caller would take a
    // list with an unreadable blocker as complete, or a label or value past the first page as
    // no change.
    @Test
    void eachBoardReadAsksForItsListsCounts() {
        for (String query : List.of(ProjectBoard.PROJECT_ITEMS, ProjectBoard.ISSUE_BOARD))
            assertThat(query)
                    .as("each read asks for its lists' counts")
                    .contains("blockedBy(first: $blockedBy) { totalCount ")
                    .contains("subIssues(first: $subIssues) { totalCount ")
                    .contains("labels(first: $labels) { totalCount ")
                    .contains("fieldValues(first: $fieldValues) { totalCount ");
    }

    // Prevents a label or value added past the first page from leaving the board unchanged to its
    // caller: an issue's labels and an item's values carry GitHub's whole count.
    @Test
    void anIssuesLabelsAndAnItemsValuesCarryGitHubsCount() throws Exception {
        String labelled =
                issue("I_1")
                                .replace(
                                        "\"labels\":{\"totalCount\":1,",
                                        "\"labels\":{\"totalCount\":4,")
                        + ",\"issueType\":null";
        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + labelled
                                + "},\"fieldValues\":{\"totalCount\":7,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var item = graphQl(items).projectItems(TOKEN, "o", 1, null).items().get(0);
        assertThat(item.issue().labelCount()).as("every label GitHub counts").isEqualTo(4);
        assertThat(item.valueCount()).as("every value GitHub counts").isEqualTo(7);

        var board =
                answering(
                        "{\"data\":{\"nodes\":["
                                + labelled
                                + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},"
                                + "\"fieldValues\":{\"totalCount\":5,\"nodes\":[],"
                                + PAGE
                                + "}},{\"id\":\"PVTI_9\",\"project\":{\"id\":\"P_2\"},"
                                + "\"fieldValues\":{\"totalCount\":1,\"nodes\":["
                                + "{\"__typename\":\"ProjectV2ItemFieldRepositoryValue\"}],"
                                + PAGE
                                + "}}],"
                                + PAGE
                                + "}},"
                                + labelled.replace("\"id\":\"I_1\"", "\"id\":\"I_2\"")
                                + ",\"projectItems\":{\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + COST
                                + "}}");
        var issues = graphQl(board).issueBoard(TOKEN, List.of("I_1", "I_2"), "P_1").issues();
        assertThat(issues.get(0).issue().labelCount())
                .as("an issue read by id counts its labels")
                .isEqualTo(4);
        assertThat(issues.get(0).valueCount())
                .as("an issue read by id counts its values in this project, not another's")
                .isEqualTo(5);
        assertThat(issues.get(1).valueCount()).as("an issue off the board has none").isZero();
    }

    // Prevents an answer without a count from reading as a list GitHub counted empty.
    @Test
    void anAnswerWithoutALabelOrValueCountFails() {
        String uncounted =
                issue("I_1").replace("\"labels\":{\"totalCount\":1,", "\"labels\":{")
                        + ",\"issueType\":null";
        for (String[] answer :
                new String[][] {
                    {uncounted, "\"totalCount\":0,", "labels"},
                    {issue("I_1") + ",\"issueType\":null", "", "fieldValues"}
                }) {
            String body =
                    "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                            + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                            + answer[0]
                            + "},\"fieldValues\":{"
                            + answer[1]
                            + "\"nodes\":[],"
                            + PAGE
                            + "}}],"
                            + PAGE
                            + "}}},"
                            + COST
                            + "}}";
            assertThatExceptionOfType(GitHubException.class)
                    .as("an answer without the %s count", answer[2])
                    .isThrownBy(() -> graphQl(answering(body)).projectItems(TOKEN, "o", 1, null))
                    .withMessageContaining("totalCount");
        }
        String uncountedById =
                "{\"data\":{\"nodes\":["
                        + issue("I_1")
                        + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},"
                        + "\"fieldValues\":{\"nodes\":[],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}],"
                        + COST
                        + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("an issue read by id without its values' count")
                .isThrownBy(
                        () ->
                                graphQl(answering(uncountedById))
                                        .issueBoard(TOKEN, List.of("I_1"), "P_1"))
                .withMessageContaining("totalCount");
    }

    // Prevents a board too heavy for one page of 100 from never reading: the caller sets the
    // page's size, and nothing outside 1 to 100 is sent.
    @Test
    void aPageOfItemsHasTheSizeTheCallerGives() throws Exception {
        var http =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":0,"
                                + "\"nodes\":[],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        graphQl(http).projectItems(TOKEN, "o", 1, null, 25);
        assertThat(request(http).path("variables").path("first").asInt())
                .as("the page size sent")
                .isEqualTo(25);
        for (int first : new int[] {0, 101}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a page of %d refused before sending", first)
                    .isThrownBy(
                            () ->
                                    graphQl(answering("{}"))
                                            .projectItems(TOKEN, "o", 1, null, first));
        }
    }

    // Prevents a smaller nested list from losing what lies past it: the sizes reach GitHub, and a
    // list with more to read says so with its cursor, for labels and values as for links.
    @Test
    void nestedListsHaveTheCallersSizesAndSayWhetherMoreFollow() throws Exception {
        String more = "\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"%s\"}";
        String issue =
                issue("I_1")
                        .replace(
                                "\"labels\":{\"totalCount\":1,\"nodes\":[{\"name\":\"story\"}],"
                                        + PAGE,
                                "\"labels\":{\"totalCount\":1,\"nodes\":[{\"name\":\"story\"}],"
                                        + more.formatted("L1"));
        var http =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,"
                                + "\"nodes\":[{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                                + issue
                                + "},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + more.formatted("V1")
                                + "}}],"
                                + PAGE
                                + "}}},"
                                + COST
                                + "}}");
        var item =
                graphQl(http)
                        .projectItems(
                                TOKEN,
                                "o",
                                1,
                                null,
                                50,
                                new GitHubGraphQl.ItemLimits(20, 50, 20, 20))
                        .items()
                        .getFirst();
        JsonNode variables = request(http).path("variables");
        assertThat(
                        List.of(
                                variables.path("labels").asInt(),
                                variables.path("subIssues").asInt(),
                                variables.path("blockedBy").asInt(),
                                variables.path("fieldValues").asInt()))
                .as("each nested list's size sent")
                .containsExactly(20, 50, 20, 20);
        assertThat(item.issue().labelsPage())
                .as("more labels follow, from their cursor")
                .isEqualTo(new GitHubGraphQl.PageInfo(true, "L1"));
        assertThat(item.valuesPage())
                .as("more values follow, from their cursor")
                .isEqualTo(new GitHubGraphQl.PageInfo(true, "V1"));

        var board =
                answering(
                        "{\"data\":{\"nodes\":["
                                + issue
                                + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},"
                                + "\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + more.formatted("V2")
                                + "}}],"
                                + PAGE
                                + "}}],"
                                + COST
                                + "}}");
        var onBoard = graphQl(board).issueBoard(TOKEN, List.of("I_1"), "P_1").issues().getFirst();
        assertThat(onBoard.valuesPage())
                .as("an issue read by id says too whether more values follow")
                .isEqualTo(new GitHubGraphQl.PageInfo(true, "V2"));
        assertThat(request(board).path("variables").path("fieldValues").asInt())
                .as("an issue read by id takes the default sizes")
                .isEqualTo(50);

        var past =
                answering(
                        "{\"data\":{\"nodes\":["
                                + issue
                                + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_9\",\"project\":{\"id\":\"P_2\"},"
                                + "\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                                + PAGE
                                + "}}],"
                                + more.formatted("P1")
                                + "}}],"
                                + COST
                                + "}}");
        assertThatExceptionOfType(GitHubException.class)
                .as(
                        "an issue whose item may lie past its first projects is not read as off the board")
                .isThrownBy(() -> graphQl(past).issueBoard(TOKEN, List.of("I_1"), "P_1"));

        for (int size : new int[] {0, 101}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("a list size of %d is refused", size)
                    .isThrownBy(() -> new GitHubGraphQl.ItemLimits(size, 1, 1, 1));
        }
    }

    private static String rest(String connection, String nodes) {
        return "{\"data\":{\"node\":{\""
                + connection
                + "\":{\"nodes\":["
                + nodes
                + "],\"pageInfo\":{\"hasNextPage\":true,\"endCursor\":\"N2\"}}},"
                + COST
                + "}}";
    }

    // Prevents the rest of a list from being read from the wrong place or in part: each read asks
    // for its node's own list from the cursor given, reads it with whether more follow, and a node
    // of another kind fails instead of reading as an empty list.
    @Test
    void theRestOfAListIsReadByItsCursor() throws Exception {
        String ref =
                "{\"id\":\"I_2\",\"databaseId\":2,\"number\":2,\"repository\":{\"nameWithOwner\":\"o/r\"}}";
        var labels = answering(rest("labels", "{\"name\":\"story\"}"));
        var subs = answering(rest("subIssues", ref));
        var blockers = answering(rest("blockedBy", ref));
        var values =
                answering(
                        rest(
                                "fieldValues",
                                "{\"__typename\":\"ProjectV2ItemFieldTextValue\",\"text\":\"K-1\","
                                        + "\"field\":{\"id\":\"F_ID\"}}"));
        var more = new GitHubGraphQl.PageInfo(true, "N2");
        assertThat(graphQl(labels).issueLabels(TOKEN, "I_1", "L1"))
                .as("the labels after the cursor")
                .isEqualTo(
                        new GitHubGraphQl.ListPage<>(
                                List.of("story"),
                                more,
                                new GraphQlCost(
                                        1, 4999, java.time.Instant.parse("2026-01-01T01:00:00Z"))));
        assertThat(graphQl(subs).issueSubIssues(TOKEN, "I_1", "S1").nodes())
                .as("the sub-issues after the cursor")
                .containsExactly(new GitHubGraphQl.IssueRef("I_2", 2, 2, "o/r"));
        assertThat(graphQl(blockers).issueBlockedBy(TOKEN, "I_1", "B1").pageInfo())
                .as("the blockers say more follow")
                .isEqualTo(more);
        assertThat(graphQl(values).itemValues(TOKEN, "PVTI_1", "V1").nodes())
                .as("the values after the cursor")
                .containsExactly(new GitHubGraphQl.ItemValue("F_ID", null, null, "K-1", null));
        for (var sent :
                List.of(
                        List.of(labels, "labels", "I_1", "L1"),
                        List.of(subs, "subIssues", "I_1", "S1"),
                        List.of(blockers, "blockedBy", "I_1", "B1"),
                        List.of(values, "fieldValues", "PVTI_1", "V1"))) {
            JsonNode request = request((ReplayHttpClient) sent.get(0));
            assertThat(request.path("query").asText())
                    .as("the query asks for the node's own list")
                    .contains(sent.get(1) + "(first: 100, after: $after)");
            assertThat(
                            List.of(
                                    request.path("variables").path("id").asText(),
                                    request.path("variables").path("after").asText()))
                    .as("the node and cursor sent")
                    .containsExactly((String) sent.get(2), (String) sent.get(3));
        }

        var otherKind = answering("{\"data\":{\"node\":{}," + COST + "}}");
        assertThatExceptionOfType(GitHubException.class)
                .as("a node that is no issue has no labels to read")
                .isThrownBy(() -> graphQl(otherKind).issueLabels(TOKEN, "PVTI_1", null));
        var none = answering("{\"data\":{\"node\":null," + COST + "}}");
        assertThatExceptionOfType(GitHubException.class)
                .as("no node, no values")
                .isThrownBy(() -> graphQl(none).itemValues(TOKEN, "PVTI_1", null));
    }

    // Prevents a caller from reading GitHub's end of a too costly query as any other failure, so
    // it can read the same board in smaller pages instead of stopping.
    @Test
    void aQueryGitHubEndsForItsResourcesIsToldApart() {
        String ended =
                "{\"data\":{\"organization\":{\"projectV2\":{\"items\":null}}},\"errors\":["
                        + "{\"message\":\"Resource limits for this query exceeded.\","
                        + "\"locations\":[{\"line\":1,\"column\":1}]}]}";
        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> graphQl(answering(ended)).projectItems(TOKEN, "o", 1, null, 100))
                .satisfies(
                        failure ->
                                assertThat(failure.resourceLimited())
                                        .as("a resource limit")
                                        .isTrue());
        String other =
                "{\"data\":null,\"errors\":[{\"message\":\"Something went wrong while executing"
                        + " your query.\"}]}";
        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> graphQl(answering(other)).projectItems(TOKEN, "o", 1, null, 100))
                .satisfies(
                        failure ->
                                assertThat(failure.resourceLimited())
                                        .as("any other failure is none")
                                        .isFalse());
    }

    // Prevents an answer missing a part from reading as a board with that part empty.
    @Test
    void anAnswerMissingAPartFailsNamingIt() {
        var http = answering("{\"data\":{\"organization\":{\"projectV2\":null}," + COST + "}}");
        assertThatExceptionOfType(GitHubException.class)
                .isThrownBy(() -> graphQl(http).projectFields(TOKEN, "o", 1, null))
                .withMessageContaining("organization.projectV2");
    }

    // Prevents an answer that lost a list from reading as an issue off the board or a board
    // Prevents a board answer that cannot be read from being blamed on another status.
    @Test
    void anUnreadableBoardAnswerNamesItsStatus() {
        var http = new ReplayHttpClient().answer(203, Map.of(), "{\"data\":{\"nodes\":[]}}");
        assertThatExceptionOfType(GitHubException.class)
                .as("an answer without its cost")
                .isThrownBy(() -> graphQl(http).issueBoard(TOKEN, List.of("I_1"), "P_1"))
                .satisfies(
                        ex -> {
                            assertThat(ex.delivery())
                                    .as("it was answered")
                                    .isEqualTo(Delivery.ANSWERED);
                            assertThat(ex.status())
                                    .as("it carries the status GitHub answered with")
                                    .isEqualTo(203);
                        });
    }

    // without items, which a caller would answer by writing again.
    @Test
    void anAnswerMissingAListFailsInsteadOfReadingEmpty() {
        String uncounted =
                "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"totalCount\":1,\"nodes\":["
                        + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":"
                        + issue("I_1")
                                .replace(
                                        "\"blockedBy\":" + REFS,
                                        "\"blockedBy\":{\"nodes\":[]," + PAGE + "}")
                        + ",\"issueType\":null},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}},"
                        + COST
                        + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("a blocker list without GitHub's count")
                .isThrownBy(() -> graphQl(answering(uncounted)).projectItems(TOKEN, "o", 1, null));
        String noItems = "{\"data\":{\"nodes\":[" + issue("I_1") + "}]," + COST + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("an issue without its project items")
                .isThrownBy(
                        () -> graphQl(answering(noItems)).issueBoard(TOKEN, List.of("I_1"), "P_1"));
        String noProject =
                "{\"data\":{\"nodes\":["
                        + issue("I_1")
                        + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":null,"
                        + "\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}],"
                        + COST
                        + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("an item without its project")
                .isThrownBy(
                        () ->
                                graphQl(answering(noProject))
                                        .issueBoard(TOKEN, List.of("I_1"), "P_1"));
        String itemsWithoutNodes =
                "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{"
                        + PAGE
                        + "}}},"
                        + COST
                        + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("a board page without its items")
                .isThrownBy(
                        () ->
                                graphQl(answering(itemsWithoutNodes))
                                        .projectItems(TOKEN, "o", 1, null));
        String valueWithoutField =
                "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"nodes\":["
                        + "{\"id\":\"PVTI_1\",\"isArchived\":false,\"content\":{\"__typename\":\"DraftIssue\"},\"fieldValues\":{\"totalCount\":1,\"nodes\":["
                        + "{\"__typename\":\"ProjectV2ItemFieldTextValue\",\"text\":\"x\",\"field\":null}],"
                        + PAGE
                        + "}}],"
                        + PAGE
                        + "}}},"
                        + COST
                        + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("a modelled value without its field")
                .isThrownBy(
                        () ->
                                graphQl(answering(valueWithoutField))
                                        .projectItems(TOKEN, "o", 1, null));
    }

    // Prevents an item whose archived state GitHub did not say from reading as one on the board.
    @Test
    void anItemWithoutItsArchivedStateFails() {
        for (String archived : List.of("", "\"isArchived\":null,", "\"isArchived\":\"false\",")) {
            String answer =
                    "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"nodes\":["
                            + "{\"id\":\"PVTI_1\","
                            + archived
                            + "\"content\":{\"__typename\":\"DraftIssue\"},\"fieldValues\":{\"totalCount\":0,\"nodes\":[],"
                            + PAGE
                            + "}}],"
                            + PAGE
                            + "}}},"
                            + COST
                            + "}}";
            assertThatExceptionOfType(GitHubException.class)
                    .as("an item with %s", archived.isEmpty() ? "no archived state" : archived)
                    .isThrownBy(() -> graphQl(answering(answer)).projectItems(TOKEN, "o", 1, null))
                    .withMessageContaining("isArchived");
        }
    }

    // Prevents a part with a blank id or a number GitHub cannot store from being sent.
    @Test
    void blankPartsAreRefusedBeforeSending() {
        var http = answering("{}");
        var graphQl = graphQl(http);
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a blank item")
                .isThrownBy(
                        () ->
                                graphQl.setFieldValues(
                                        TOKEN, "P_1", List.of(FieldValue.text(" ", "F_1", "a"))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a blank option")
                .isThrownBy(
                        () ->
                                graphQl.setFieldValues(
                                        TOKEN,
                                        "P_1",
                                        List.of(FieldValue.option("PVTI_1", "F_1", ""))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a number that is none")
                .isThrownBy(
                        () ->
                                graphQl.setFieldValues(
                                        TOKEN,
                                        "P_1",
                                        List.of(FieldValue.number("PVTI_1", "F_1", Double.NaN))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a blank title")
                .isThrownBy(
                        () ->
                                graphQl.createIssues(
                                        TOKEN,
                                        List.of(new NewIssue("R_1", " ", null, null, List.of()))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a blank parent")
                .isThrownBy(
                        () ->
                                graphQl.createIssues(
                                        TOKEN,
                                        List.of(new NewIssue("R_1", "T", null, "", List.of()))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("a label twice")
                .isThrownBy(
                        () ->
                                graphQl.createIssues(
                                        TOKEN,
                                        List.of(
                                                new NewIssue(
                                                        "R_1",
                                                        "T",
                                                        null,
                                                        null,
                                                        List.of("L_1", "L_1")))));
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("an option without a name")
                .isThrownBy(
                        () ->
                                graphQl.setFieldOptions(
                                        TOKEN, "F_1", List.of(new NewOption(" ", "GRAY", ""))));
        assertThat(http.requests()).as("nothing sent").isEmpty();
    }
}
