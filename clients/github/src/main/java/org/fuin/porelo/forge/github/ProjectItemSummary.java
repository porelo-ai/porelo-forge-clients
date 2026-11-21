package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * An organization project's item as GitHub's REST item list gives it: enough to tell, item by item,
 * whether it changed since the caller last read it.
 *
 * @param id the item's node id, as GraphQL names it
 * @param updatedAt the item's last change, a field value's included
 * @param archived whether GitHub archived it
 * @param contentType what the item holds: {@code Issue}, {@code PullRequest} or {@code DraftIssue}
 * @param issue its issue, or null when it holds none
 * @param values the values of the fields the read named, one per field
 */
public record ProjectItemSummary(
        String id,
        Instant updatedAt,
        boolean archived,
        String contentType,
        @Nullable IssueSummary issue,
        List<ValueSummary> values) {
    /** Requires every part GitHub always sends. */
    public ProjectItemSummary {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(updatedAt, "updatedAt");
        Objects.requireNonNull(contentType, "contentType");
        values = List.copyOf(values);
    }

    /**
     * An item's issue.
     *
     * @param id its node id
     * @param repository its repository, {@code owner/name}
     * @param number its number in its repository
     * @param updatedAt its last change; a sub-issue or blocker linked moves it not
     * @param state {@code open} or {@code closed}
     * @param subIssues how many sub-issues it has, or null when GitHub sent no count
     * @param blockedBy how many issues block it, closed ones included, or null when GitHub sent no
     *     count
     */
    public record IssueSummary(
            String id,
            String repository,
            int number,
            Instant updatedAt,
            String state,
            @Nullable Integer subIssues,
            @Nullable Integer blockedBy) {
        /** Requires every part GitHub always sends. */
        public IssueSummary {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(repository, "repository");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(state, "state");
        }
    }

    /**
     * One field's value; which of option, text and number is set follows the field's kind, and all
     * are null for an empty value or a kind other than {@code single_select}, {@code text} and
     * {@code number}.
     *
     * @param fieldId the field's numeric id
     * @param dataType GitHub's kind of field, such as {@code single_select}
     * @param optionId a single-select value's option id
     * @param text a text value
     * @param number a number value
     */
    public record ValueSummary(
            long fieldId,
            String dataType,
            @Nullable String optionId,
            @Nullable String text,
            @Nullable Double number) {
        /** Requires the field. */
        public ValueSummary {
            Objects.requireNonNull(dataType, "dataType");
        }
    }

    /** Reads one item of the list; a part GitHub always sends that is missing fails the read. */
    static ProjectItemSummary read(JsonNode item) {
        String contentType = text(item, "content_type");
        JsonNode content = item.path("content");
        IssueSummary issue = null;
        if (contentType.equals("Issue")) {
            if (!content.isObject()) throw new Unreadable("content");
            issue = issue(content);
        }
        List<ValueSummary> values = new ArrayList<>();
        JsonNode fields = item.path("fields");
        if (!fields.isMissingNode() && !fields.isNull()) {
            if (!fields.isArray()) throw new Unreadable("fields");
            for (JsonNode field : fields) values.add(value(field));
        }
        return new ProjectItemSummary(
                text(item, "node_id"),
                instant(item, "updated_at"),
                !item.path("archived_at").isMissingNode() && !item.path("archived_at").isNull(),
                contentType,
                issue,
                values);
    }

    private static IssueSummary issue(JsonNode content) {
        return new IssueSummary(
                text(content, "node_id"),
                text(content.path("repository"), "full_name"),
                Math.toIntExact(number(content, "number")),
                instant(content, "updated_at"),
                text(content, "state"),
                count(content.path("sub_issues_summary"), "total"),
                count(content.path("issue_dependencies_summary"), "total_blocked_by"));
    }

    private static ValueSummary value(JsonNode field) {
        long id = number(field, "id");
        String type = text(field, "data_type");
        JsonNode value = field.path("value");
        if (value.isMissingNode() || value.isNull())
            return new ValueSummary(id, type, null, null, null);
        return switch (type) {
            case "single_select" -> new ValueSummary(id, type, text(value, "id"), null, null);
            case "text" -> new ValueSummary(id, type, null, text(value, "raw"), null);
            case "number" -> {
                if (!value.isNumber()) throw new Unreadable("value");
                yield new ValueSummary(id, type, null, null, value.asDouble());
            }
            default -> new ValueSummary(id, type, null, null, null);
        };
    }

    private static @Nullable Integer count(JsonNode summary, String name) {
        if (summary.isMissingNode() || summary.isNull()) return null;
        return Math.toIntExact(number(summary, name));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) throw new Unreadable(name);
        return value.asText();
    }

    private static long number(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isIntegralNumber()) throw new Unreadable(name);
        return value.asLong();
    }

    private static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(text(node, name));
        } catch (DateTimeParseException unreadable) {
            throw new Unreadable(name);
        }
    }

    /** A part of an item GitHub always sends was missing or of another kind. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String name) {
            super("A project item lacks a readable " + name);
        }
    }
}
