package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.URI;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubConditionalTest {
    private static final URI BASE = URI.create("https://example.org/");
    private static final String TOKEN = "synthetic-access-token";
    private static final Map<String, List<String>> LIMIT =
            Map.of("x-ratelimit-limit", List.of("5000"), "x-ratelimit-remaining", List.of("4999"));

    /**
     * An empty page, as GitHub answers for a project without items. GitHub's own example item holds
     * null values in its untyped {@code content}, which the reader refuses.
     */
    private static final String NO_ITEMS = "[]";

    private static String issues() throws Exception {
        return GitHubClientTest.extractExample("issue-items").toString();
    }

    private static Map<String, List<String>> with(String name, String value) {
        var headers = new HashMap<>(LIMIT);
        headers.put(name, List.of(value));
        return headers;
    }

    private static String sentTag(ChunkedTransport http) {
        return http.sent.getLast().headers().firstValue("If-None-Match").orElse(null);
    }

    // Prevents a poll that found nothing changed from failing as if GitHub had refused it.
    @Test
    void notModifiedIsNotAFailure() {
        var tagged = new ChunkedTransport(304, with("etag", "W/\"new\""), "");
        var answer =
                new GitHubClient(BASE, tagged)
                        .issuesIfChanged(TOKEN, "o", "r", null, null, null, 1, 10, "W/\"old\"");
        assertThat(answer.changed()).as("a 304 is not changed").isFalse();
        assertThat(answer.value()).as("a 304 carries no value").isNull();
        assertThat(answer.etag()).as("the answer's own tag is kept").isEqualTo("W/\"new\"");
        assertThat(answer.rateLimit())
                .as("the rate limit of a 304 is read")
                .isEqualTo(new RateLimit(5000L, 4999L, null, null));

        var untagged = new ChunkedTransport(304, LIMIT, "");
        var items =
                new GitHubClient(BASE, untagged)
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, "\"sent\"");
        assertThat(items.changed()).as("a 304 of the project's items is not changed").isFalse();
        assertThat(items.etag()).as("a 304 without a tag keeps the one sent").isEqualTo("\"sent\"");

        var unasked = new GitHubClient(BASE, new ChunkedTransport(304, LIMIT, ""));
        assertThatExceptionOfType(GitHubException.class)
                .as("a 304 to a read that sent no tag has no tag to keep")
                .isThrownBy(() -> unasked.projectItemsIfChanged(TOKEN, "o", 1, null, 10, null))
                .satisfies(ex -> assertThat(ex.status()).as("and names it").isEqualTo(304));
    }

    // Prevents every poll from reading the whole list again, or a first read from sending a tag.
    @Test
    void ifNoneMatchIsSentAndNoneWithoutATag() throws Exception {
        var tagged = new ChunkedTransport(200, LIMIT, issues());
        new GitHubClient(BASE, tagged)
                .issuesIfChanged(TOKEN, "o", "r", null, null, null, 1, 10, "W/\"known\"");
        assertThat(sentTag(tagged))
                .as("the caller's tag is sent unchanged")
                .isEqualTo("W/\"known\"");
        var first = new ChunkedTransport(200, LIMIT, issues());
        new GitHubClient(BASE, first)
                .issuesIfChanged(TOKEN, "o", "r", null, null, null, 1, 10, null);
        assertThat(sentTag(first)).as("a first read sends no tag").isNull();

        var items = new ChunkedTransport(200, LIMIT, NO_ITEMS);
        new GitHubClient(BASE, items).projectItemsIfChanged(TOKEN, "o", 1, null, 10, "\"p\"");
        assertThat(sentTag(items)).as("the project's items send the tag too").isEqualTo("\"p\"");
        var plain = new ChunkedTransport(200, LIMIT, "[]");
        new GitHubClient(BASE, plain).ownKeys(TOKEN, 1, 1);
        assertThat(sentTag(plain)).as("a plain read sends no tag").isNull();
    }

    // Prevents a changed list from being dropped or paired with a stale tag that hides the next
    // change.
    @Test
    void aChangedAnswerCarriesItsValueAndNewTag() throws Exception {
        var headers = with("etag", "W/\"abc\"");
        headers.put(
                "link",
                List.of("<https://example.org/repositories/1/issues?page=2>; rel=\"next\""));
        var http = new ChunkedTransport(200, headers, issues());
        Instant since = Instant.parse("2026-01-02T03:04:05Z");
        var answer =
                new GitHubClient(BASE, http)
                        .issuesIfChanged(
                                TOKEN, "o", "r", "all", List.of("story"), since, 2, 10, "\"x\"");
        assertThat(answer.changed()).as("a 200 is changed").isTrue();
        assertThat(answer.value().items())
                .as("the value is GitHub's list")
                .extracting(issue -> issue.number())
                .containsExactly(1347);
        assertThat(answer.value().more()).as("the next link says more follow").isTrue();
        assertThat(answer.etag()).as("the weak tag is kept as sent").isEqualTo("W/\"abc\"");
        assertThat(answer.rateLimit())
                .as("a 200 carries its rate limit")
                .isEqualTo(new RateLimit(5000L, 4999L, null, null));
        assertThat(http.sent.getLast().uri())
                .as("the filters are sent, newest change first")
                .isEqualTo(
                        URI.create(
                                "https://example.org/repos/o/r/issues?state=all&labels=story"
                                        + "&since=2026-01-02T03%3A04%3A05Z&sort=updated"
                                        + "&direction=desc&page=2&per_page=10"));
    }

    // Prevents a tag holding a line break from smuggling a header into the request.
    @Test
    void anInvalidEntityTagIsRefusedBeforeSending() {
        var http = new ChunkedTransport(200, LIMIT, "[]");
        var client = new GitHubClient(BASE, http);
        for (String etag : List.of("", "  ", "\"a\"\r\nX-Injected: 1", "\"a\"\n", " \"a\"")) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the issues refuse the tag %s", etag)
                    .isThrownBy(
                            () ->
                                    client.issuesIfChanged(
                                            TOKEN, "o", "r", null, null, null, 1, 1, etag))
                    .withMessage("Invalid entity tag");
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the project's items refuse the tag %s", etag)
                    .isThrownBy(() -> client.projectItemsIfChanged(TOKEN, "o", 1, null, 1, etag))
                    .withMessage("Invalid entity tag");
        }
        assertThat(http.sent).as("nothing was sent").isEmpty();
    }

    // Prevents a label holding a comma from being split by GitHub into two filters.
    @Test
    void labelsAreLeftOutOrRefused() throws Exception {
        var empty = new ChunkedTransport(200, LIMIT, issues());
        new GitHubClient(BASE, empty)
                .issuesIfChanged(TOKEN, "o", "r", null, List.of(), null, 1, 10, null);
        assertThat(empty.sent.getLast().uri().getRawQuery())
                .as("an empty list sends no labels")
                .isEqualTo("sort=updated&direction=desc&page=1&per_page=10");
        var two = new ChunkedTransport(200, LIMIT, issues());
        new GitHubClient(BASE, two)
                .issuesIfChanged(
                        TOKEN, "o", "r", null, List.of("story", "to do"), null, 1, 10, null);
        assertThat(two.sent.getLast().uri().getRawQuery())
                .as("the names are joined by commas, each encoded")
                .startsWith("labels=story,to%20do&");
        var http = new ChunkedTransport(200, LIMIT, issues());
        var client = new GitHubClient(BASE, http);
        for (List<String> labels : List.of(List.of("a,b"), List.of(" "), List.of("ok", ""))) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the labels %s are refused", labels)
                    .isThrownBy(
                            () ->
                                    client.issuesIfChanged(
                                            TOKEN, "o", "r", null, labels, null, 1, 10, null));
        }
        assertThat(http.sent).as("no refused list was sent").isEmpty();
    }

    // Prevents a poll from reading the first page forever, or a last page from looping back.
    @Test
    void theProjectItemsCursorIsReadFromTheNextLink() {
        var next =
                with(
                        "link",
                        "<https://example.org/orgs/o/projectsV2/1/items?before=Qg%3D%3D>;"
                                + " rel=\"prev\", <https://example.org/orgs/o/projectsV2/1/items"
                                + "?per_page=10&after=Y3Vy%3D%3D>; rel=\"next\"");
        var http = new ChunkedTransport(200, next, NO_ITEMS);
        var page =
                new GitHubClient(BASE, http)
                        .projectItemsIfChanged(TOKEN, "o", 1, "c/d=", 10, null)
                        .value();
        assertThat(page.after()).as("the cursor is decoded").isEqualTo("Y3Vy==");
        assertThat(http.sent.getLast().uri())
                .as("the cursor sent is encoded")
                .isEqualTo(
                        URI.create(
                                "https://example.org/orgs/o/projectsV2/1/items?per_page=10"
                                        + "&after=c%2Fd%3D"));

        var last =
                with(
                        "link",
                        "<https://example.org/orgs/o/projectsV2/1/items?before=Qg%3D%3D>;"
                                + " rel=\"prev\"");
        var lastPage =
                new GitHubClient(BASE, new ChunkedTransport(200, last, NO_ITEMS))
                        .projectItemsIfChanged(TOKEN, "o", 1, "x", 10, null)
                        .value();
        assertThat(lastPage.after()).as("the last page has no cursor").isNull();
        var unlinked =
                new GitHubClient(BASE, new ChunkedTransport(200, LIMIT, NO_ITEMS))
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, null)
                        .value();
        assertThat(unlinked.after()).as("no link has no cursor").isNull();

        var cursorless =
                with("link", "<https://example.org/orgs/o/projectsV2/1/items>; rel=\"next\"");
        var stuck = new GitHubClient(BASE, new ChunkedTransport(200, cursorless, NO_ITEMS));
        assertThatExceptionOfType(GitHubException.class)
                .as("a next link without a cursor would read the same page again")
                .isThrownBy(() -> stuck.projectItemsIfChanged(TOKEN, "o", 1, null, 10, null));
    }

    // Prevents a caller's poll from failing forever on a tag the client handed it but cannot send.
    @Test
    void anAnswerTagThatCannotBeSentBackIsNotKept() {
        var notModified = new ChunkedTransport(304, with("etag", "\"caf\u00e9\""), "");
        var unchanged =
                new GitHubClient(BASE, notModified)
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, "\"sent\"");
        assertThat(unchanged.etag()).as("a 304 keeps the tag sent").isEqualTo("\"sent\"");
        var changed =
                new GitHubClient(BASE, new ChunkedTransport(200, with("etag", ""), NO_ITEMS))
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, null);
        assertThat(changed.etag()).as("a 200 has no tag to keep").isNull();
    }

    // Prevents a caller following the cursors from reading the same page forever.
    @Test
    void aNextLinkBackToTheSamePageFails() {
        var same =
                with(
                        "link",
                        "<https://example.org/orgs/o/projectsV2/1/items?per_page=10&after=c%2Fd%3D>;"
                                + " rel=\"next\"");
        var client = new GitHubClient(BASE, new ChunkedTransport(200, same, NO_ITEMS));
        assertThatExceptionOfType(GitHubException.class)
                .as("a next link to the page just read")
                .isThrownBy(() -> client.projectItemsIfChanged(TOKEN, "o", 1, "c/d=", 10, null));
    }

    // Prevents a refusal of a conditional read from reading as an unchanged list.
    @Test
    void aRefusalOtherThanNotModifiedStillFails() {
        String refused = "{\"message\":\"refused\"}";
        for (int status : List.of(403, 404, 500)) {
            var issues = new GitHubClient(BASE, new ChunkedTransport(status, LIMIT, refused));
            assertThatExceptionOfType(GitHubException.class)
                    .as("the issues fail on %s", status)
                    .isThrownBy(
                            () ->
                                    issues.issuesIfChanged(
                                            TOKEN, "o", "r", null, null, null, 1, 10, "\"e\""))
                    .satisfies(
                            ex -> assertThat(ex.status()).as("with its status").isEqualTo(status));
            var items = new GitHubClient(BASE, new ChunkedTransport(status, LIMIT, refused));
            assertThatExceptionOfType(GitHubException.class)
                    .as("the project's items fail on %s", status)
                    .isThrownBy(() -> items.projectItemsIfChanged(TOKEN, "o", 1, null, 10, "\"e\""))
                    .satisfies(
                            ex -> assertThat(ex.status()).as("with its status").isEqualTo(status));
        }
    }

    // Prevents a project's items, whose content GitHub sends with nulls, from failing every read.
    @Test
    void aProjectItemWithNullsInItsContentIsRead() throws Exception {
        var extract =
                new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(java.nio.file.Path.of("src/main/openapi/github.json").toFile());
        String item =
                extract.path("components")
                        .path("examples")
                        .path("projects-v2-item-with-content")
                        .path("value")
                        .toString();
        var http = new ChunkedTransport(200, LIMIT, "[" + item + "]");
        var read =
                new GitHubClient(BASE, http, Strictness.STRICT)
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, null);
        assertThat(read.value()).as("an answer").isNotNull();
        assertThat(read.value().items()).as("GitHub's example item read").hasSize(1);
    }
}
