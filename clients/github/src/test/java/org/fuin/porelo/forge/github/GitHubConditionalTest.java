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

    // Prevents a change look from missing a change: GitHub's own time is the next look's start,
    // since the caller's clock may run ahead, and the fields named keep an unchanged board at 304.
    @Test
    void aChangeLookNamesItsFieldsAndCarriesGitHubsTime() {
        var unchanged =
                new ChunkedTransport(304, with("date", "Sat, 21 Nov 2026 16:40:05 GMT"), "");
        var answer =
                new GitHubClient(BASE, unchanged)
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, List.of(11L, 22L), "\"p\"");
        assertThat(unchanged.sent.getLast().uri().getRawQuery())
                .as("the field ids are sent as one list")
                .isEqualTo("per_page=10&fields=11,22");
        assertThat(answer.date())
                .as("a 304 carries GitHub's time")
                .isEqualTo(Instant.parse("2026-11-21T16:40:05Z"));
        var changed =
                new ChunkedTransport(200, with("date", "Sat, 21 Nov 2026 16:41:00 GMT"), "[]");
        assertThat(
                        new GitHubClient(BASE, changed)
                                .issuesIfChanged(TOKEN, "o", "r", null, null, null, 1, 10, null)
                                .date())
                .as("a 200 carries GitHub's time")
                .isEqualTo(Instant.parse("2026-11-21T16:41:00Z"));
        for (String date : List.of("yesterday", "")) {
            var unreadable = new ChunkedTransport(200, with("date", date), "[]");
            assertThat(
                            new GitHubClient(BASE, unreadable)
                                    .projectItemsIfChanged(TOKEN, "o", 1, null, 10, null)
                                    .date())
                    .as("no time is made up from the date %s", date)
                    .isNull();
        }
        var http = new ChunkedTransport(200, LIMIT, NO_ITEMS);
        var client = new GitHubClient(BASE, http);
        List<Long> tooMany = java.util.stream.LongStream.rangeClosed(1, 51).boxed().toList();
        for (List<Long> ids : List.of(tooMany, List.of(0L), List.of(5L, -1L))) {
            assertThatExceptionOfType(IllegalArgumentException.class)
                    .as("the field ids %s are refused", ids)
                    .isThrownBy(
                            () -> client.projectItemsIfChanged(TOKEN, "o", 1, null, 10, ids, null));
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

    /** One item as GitHub's REST list sends it, cut to the parts read, with the given content. */
    private static String restItem(String contentType, String content) {
        return "{\"id\":7,\"node_id\":\"PVTI_1\",\"content_type\":\""
                + contentType
                + "\",\"updated_at\":\"2026-11-21T15:33:01Z\",\"archived_at\":null,"
                + "\"content\":"
                + content
                + ",\"fields\":["
                + "{\"id\":448097099,\"name\":\"Status\",\"data_type\":\"single_select\","
                + "\"value\":{\"id\":\"98236657\",\"name\":{\"raw\":\"Done\",\"html\":\"Done\"},"
                + "\"color\":\"PURPLE\"}},"
                + "{\"id\":448097434,\"name\":\"Worker\",\"data_type\":\"text\","
                + "\"value\":{\"raw\":\"planner\",\"html\":\"planner\"}},"
                + "{\"id\":448097452,\"name\":\"Rank\",\"data_type\":\"number\",\"value\":0},"
                + "{\"id\":448097220,\"name\":\"Priority\",\"data_type\":\"single_select\","
                + "\"value\":null},"
                + "{\"id\":448097100,\"name\":\"Labels\",\"data_type\":\"labels\",\"value\":[]}]}";
    }

    private static final String REST_ISSUE =
            "{\"node_id\":\"I_1\",\"number\":46,\"state\":\"closed\","
                    + "\"updated_at\":\"2026-11-21T15:32:31Z\","
                    + "\"repository\":{\"full_name\":\"o/r\"},"
                    + "\"sub_issues_summary\":{\"total\":5,\"completed\":5,\"percent_completed\":100},"
                    + "\"issue_dependencies_summary\":{\"blocked_by\":0,\"total_blocked_by\":1,"
                    + "\"blocking\":0,\"total_blocking\":0}}";

    // Prevents a change of an item from going unseen after a changed page: each item carries what
    // a caller compares with what it holds - its and its issue's times, its links' counts and its
    // values - and a part GitHub always sends that is missing fails instead of reading as
    // unchanged.
    @Test
    void eachItemOfAChangedPageCarriesWhatTellsItsChange() {
        var http = new ChunkedTransport(200, LIMIT, "[" + restItem("Issue", REST_ISSUE) + "]");
        var item =
                new GitHubClient(BASE, http)
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, List.of(448097099L), null)
                        .value()
                        .items()
                        .getFirst();
        assertThat(item.id()).as("the item's node id").isEqualTo("PVTI_1");
        assertThat(item.updatedAt())
                .as("the item's time")
                .isEqualTo(Instant.parse("2026-11-21T15:33:01Z"));
        assertThat(item.archived()).as("not archived").isFalse();
        assertThat(item.issue())
                .as("its issue, with its links counted, closed blockers included")
                .isEqualTo(
                        new ProjectItemSummary.IssueSummary(
                                "I_1",
                                "o/r",
                                46,
                                Instant.parse("2026-11-21T15:32:31Z"),
                                "closed",
                                5,
                                1));
        assertThat(item.values())
                .as("each field's value by its kind, an empty one and another kind without one")
                .containsExactly(
                        new ProjectItemSummary.ValueSummary(
                                448097099L, "single_select", "98236657", null, null),
                        new ProjectItemSummary.ValueSummary(
                                448097434L, "text", null, "planner", null),
                        new ProjectItemSummary.ValueSummary(448097452L, "number", null, null, 0.0),
                        new ProjectItemSummary.ValueSummary(
                                448097220L, "single_select", null, null, null),
                        new ProjectItemSummary.ValueSummary(
                                448097100L, "labels", null, null, null));

        String uncounted = REST_ISSUE.replaceAll(",\"sub_issues_summary\".*}}$", "}");
        var draft =
                new GitHubClient(
                                BASE,
                                new ChunkedTransport(
                                        200,
                                        LIMIT,
                                        "["
                                                + restItem("DraftIssue", "{\"title\":\"d\"}")
                                                + ","
                                                + restItem("Issue", uncounted)
                                                + "]"))
                        .projectItemsIfChanged(TOKEN, "o", 1, null, 10, null)
                        .value()
                        .items();
        assertThat(draft.get(0).issue()).as("a draft holds no issue").isNull();
        assertThat(draft.get(1).issue().subIssues())
                .as("no count is made up when GitHub sends none")
                .isNull();
        assertThat(draft.get(1).issue().blockedBy()).as("nor for blockers").isNull();

        for (String broken :
                List.of(
                        restItem("Issue", REST_ISSUE).replace("\"node_id\":\"PVTI_1\",", ""),
                        restItem(
                                "Issue",
                                REST_ISSUE.replace("\"updated_at\":\"2026-11-21T15:32:31Z\",", "")),
                        restItem("Issue", REST_ISSUE).replace("\"value\":0", "\"value\":\"0\""),
                        restItem("Issue", "null"),
                        restItem("Issue", "\"I_1\""))) {
            var client =
                    new GitHubClient(BASE, new ChunkedTransport(200, LIMIT, "[" + broken + "]"));
            assertThatExceptionOfType(GitHubException.class)
                    .as("an item missing a part fails: %s", broken)
                    .isThrownBy(() -> client.projectItemsIfChanged(TOKEN, "o", 1, null, 10, null));
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
