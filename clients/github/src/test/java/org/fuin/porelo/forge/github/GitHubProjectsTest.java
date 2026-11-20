package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
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
    private static final String REFS = "{\"nodes\":[]," + PAGE + "}";

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
        return "{\"__typename\":\"Issue\",\"id\":\""
                + id
                + "\",\"databaseId\":5000000001,\"number\":7,\"title\":\"T\",\"body\":null,"
                + "\"state\":\"OPEN\",\"stateReason\":null,\"updatedAt\":\"2026-01-01T00:00:00Z\","
                + "\"repository\":{\"nameWithOwner\":\"o/r\"},"
                + "\"labels\":{\"nodes\":[{\"name\":\"story\"}],"
                + PAGE
                + "},\"parent\":null,\"subIssues\":"
                + REFS
                + ",\"blockedBy\":"
                + REFS;
    }

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

    // Prevents a rate limit hidden behind a part's error from reading as a refused part.
    @Test
    void aRateLimitAnywhereFailsTheWholeBatch() {
        var http =
                answering(
                        "{\"data\":{\"a0\":null,\"a1\":null},\"errors\":["
                                + "{\"type\":\"FORBIDDEN\",\"path\":[\"a0\"],\"message\":\"no\"},"
                                + "{\"type\":\"RATE_LIMITED\",\"path\":[\"a1\"],\"message\":\"limit\"}]}");
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
                        + "{\"id\":\"PVTI_1\",\"project\":{\"id\":\"P_1\"},\"fieldValues\":{\"nodes\":["
                        + "{\"__typename\":\"ProjectV2ItemFieldSingleSelectValue\",\"optionId\":\"OPT_1\",\"name\":\"Todo\",\"field\":{\"id\":\"F_STATE\"}},"
                        + "{\"__typename\":\"ProjectV2ItemFieldRepositoryValue\"},"
                        + "{\"__typename\":\"ProjectV2ItemFieldTextValue\",\"text\":\"OLD-1\",\"field\":{\"id\":\"F_ID\"}}],"
                        + PAGE
                        + "}},"
                        + "{\"id\":\"PVTI_OTHER\",\"project\":{\"id\":\"P_2\"},\"fieldValues\":{\"nodes\":[],"
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
                                + "{\"__typename\":\"ProjectV2SingleSelectField\",\"id\":\"F_STATE\",\"name\":\"Status\",\"dataType\":\"SINGLE_SELECT\",\"options\":[{\"id\":\"OPT_1\",\"name\":\"Todo\",\"color\":\"GRAY\",\"description\":\"\"}]},"
                                + "{\"__typename\":\"ProjectV2Field\",\"id\":\"F_ID\",\"name\":\"Id\",\"dataType\":\"TEXT\"},"
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

        var items =
                answering(
                        "{\"data\":{\"organization\":{\"projectV2\":{\"items\":{\"nodes\":["
                                + "{\"id\":\"PVTI_1\",\"content\":"
                                + issue("I_1")
                                + "},\"fieldValues\":{\"nodes\":[],"
                                + PAGE
                                + "}},"
                                + "{\"id\":\"PVTI_2\",\"content\":{\"__typename\":\"DraftIssue\"},\"fieldValues\":{\"nodes\":[],"
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
    // without items, which a caller would answer by writing again.
    @Test
    void anAnswerMissingAListFailsInsteadOfReadingEmpty() {
        String noItems = "{\"data\":{\"nodes\":[" + issue("I_1") + "}]," + COST + "}}";
        assertThatExceptionOfType(GitHubException.class)
                .as("an issue without its project items")
                .isThrownBy(
                        () -> graphQl(answering(noItems)).issueBoard(TOKEN, List.of("I_1"), "P_1"));
        String noProject =
                "{\"data\":{\"nodes\":["
                        + issue("I_1")
                        + ",\"projectItems\":{\"nodes\":[{\"id\":\"PVTI_1\",\"project\":null,"
                        + "\"fieldValues\":{\"nodes\":[],"
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
                        + "{\"id\":\"PVTI_1\",\"content\":{\"__typename\":\"DraftIssue\"},\"fieldValues\":{\"nodes\":["
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
