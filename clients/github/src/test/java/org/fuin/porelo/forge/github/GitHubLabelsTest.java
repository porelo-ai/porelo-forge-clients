package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubLabelsTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final String TOKEN = "synthetic-access-token";

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
}
