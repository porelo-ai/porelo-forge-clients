package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class GitHubIssueScenarioTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode json(String text) throws Exception {
        return JSON.readTree(text);
    }

    // A real change to the board passed off as GitHub's own change report would record a 304
    // against a board that is no longer the one the replay's first answer shows.
    @Test
    void aChangedFieldValueOfAnItemIsNamedByWhereItIs() throws Exception {
        var before = json("[{\"id\":1,\"fields\":[{\"id\":7,\"value\":3}]},{\"id\":2}]");
        var after = json("[{\"id\":1,\"fields\":[{\"id\":7,\"value\":11}]},{\"id\":2}]");

        assertThat(GitHubIssueScenario.changes(before, after, ""))
                .as("the one changed value, by its JSON pointer")
                .containsExactly("/0/fields/0/value");
    }

    @Test
    void anItemAddedOrAFieldGainedIsNamed() throws Exception {
        var before = json("[{\"id\":1}]");

        assertThat(GitHubIssueScenario.changes(before, json("[{\"id\":1},{\"id\":2}]"), ""))
                .as("a list of another length is named whole")
                .containsExactly("/");
        assertThat(GitHubIssueScenario.changes(before, json("[{\"id\":1,\"archived\":true}]"), ""))
                .as("a field only the second answer has")
                .containsExactly("/0/archived");
    }

    @Test
    void theSameItemsInAnotherKeyOrderAreNoChange() throws Exception {
        var before = json("[{\"id\":1,\"content_type\":\"Issue\"}]");
        var after = json("[{\"content_type\":\"Issue\",\"id\":1}]");

        assertThat(GitHubIssueScenario.changes(before, after, ""))
                .as("only GitHub's own change report, so the read is made again")
                .isEmpty();
    }
}
