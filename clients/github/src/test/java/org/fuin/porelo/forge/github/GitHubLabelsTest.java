package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.Map;
import org.fuin.porelo.forge.github.model.IssuesUpdateLabelRequest;
import org.junit.jupiter.api.Test;

class GitHubLabelsTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final String TOKEN = "synthetic-access-token";
    private static final String LABEL =
            "{\"id\":1,\"node_id\":\"LA_1\",\"url\":\"https://example.org/l\",\"name\":\"defect\","
                    + "\"description\":null,\"color\":\"0e8a16\",\"default\":false}";

    // Prevents a label's name from reaching another path, deleting what the caller never named.
    @Test
    void aLabelsNameReachesTheDeleteAsOneSegment() {
        var http = new ChunkedTransport(204, Map.of(), "");
        new GitHubClient(BASE, http).deleteLabel(TOKEN, "o", "r", "area/../hooks 1.x?y#z");
        assertThat(http.sent.get(0).uri().getRawPath())
                .as("every slash, dot, space and query mark is encoded")
                .isEqualTo("/repos/o/r/labels/area%2F%2E%2E%2Fhooks%201%2Ex%3Fy%23z");
        assertThat(http.sent.get(0).uri().getRawQuery()).as("no query reaches GitHub").isNull();
        assertThat(http.sent.get(0).method()).as("a delete").isEqualTo("DELETE");

        var other = new ChunkedTransport(204, Map.of(), "");
        new GitHubClient(BASE, other).deleteLabel(TOKEN, "o", "r", "50%/größe");
        assertThat(other.sent.get(0).uri().getRawPath())
                .as("a percent sign and non-ASCII letters are encoded too")
                .isEqualTo("/repos/o/r/labels/50%25%2Fgr%C3%B6%C3%9Fe");
    }

    // Prevents a dot segment or an empty name from addressing the label list or the repository.
    @Test
    void aNameThatIsADotSegmentOrBlankIsRefused() {
        var http = new ChunkedTransport(204, Map.of(), "");
        for (String name : new String[] {"..", ".", " "}) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the name %s", name)
                    .isThrownBy(
                            () -> new GitHubClient(BASE, http).deleteLabel(TOKEN, "o", "r", name));
        }
        assertThat(http.sent).as("nothing was sent").isEmpty();
    }

    // Prevents a label's name from reaching another path, changing what the caller never named.
    @Test
    void aLabelsNameReachesTheUpdateAsOneSegment() {
        var http = new ChunkedTransport(200, Map.of(), LABEL);
        new GitHubClient(BASE, http)
                .updateLabel(
                        TOKEN,
                        "o",
                        "r",
                        "area/../hooks 1.x?y#z",
                        new IssuesUpdateLabelRequest("area/hooks", null, null, null));
        assertThat(http.sent.get(0).uri().getRawPath())
                .as("every slash, dot, space and query mark is encoded")
                .isEqualTo("/repos/o/r/labels/area%2F%2E%2E%2Fhooks%201%2Ex%3Fy%23z");
        assertThat(http.sent.get(0).uri().getRawQuery()).as("no query reaches GitHub").isNull();
        assertThat(http.sent.get(0).method()).as("a change").isEqualTo("PATCH");
    }

    // Prevents a rename from clearing the label's color or description the caller left out.
    @Test
    void anUpdateSendsOnlyWhatTheCallerGave() throws Exception {
        var http = new ChunkedTransport(200, Map.of(), LABEL);
        new GitHubClient(BASE, http)
                .updateLabel(
                        TOKEN,
                        "o",
                        "r",
                        "bug",
                        new IssuesUpdateLabelRequest("defect", null, null, null));
        assertThat(new ObjectMapper().readTree(GitHubRecordingTest.requestBody(http.sent.get(0))))
                .as("the new name alone")
                .isEqualTo(new ObjectMapper().readTree("{\"new_name\":\"defect\"}"));
    }

    // Prevents an organization's issue fields from being cut short: GitHub serves them unpaged,
    // and a next page would be fields the caller never sees.
    @Test
    void anOrganizationsIssueFieldsAreReadWholeOrNotAtAll() {
        var http = new ChunkedTransport(200, Map.of(), "[]");
        new GitHubClient(BASE, http).issueFields(TOKEN, "a/b.c");
        assertThat(http.sent.get(0).uri().getRawPath())
                .as("the organization is one path segment")
                .isEqualTo("/orgs/a%2Fb%2Ec/issue-fields");

        var paged =
                new ChunkedTransport(
                        200,
                        Map.of(
                                "Link",
                                java.util.List.of(
                                        "<https://example.org/orgs/o/issue-fields?page=2>;"
                                                + " rel=\"next\"")),
                        "[]");
        assertThatExceptionOfType(IllegalStateException.class)
                .as("a next page")
                .isThrownBy(() -> new GitHubClient(BASE, paged).issueFields(TOKEN, "o"));
    }
}
