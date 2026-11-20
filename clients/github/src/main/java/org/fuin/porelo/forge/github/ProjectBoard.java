package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntFunction;
import org.fuin.porelo.forge.github.GitHubGraphQl.BoardIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.CreatedIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldOptions;
import org.fuin.porelo.forge.github.GitHubGraphQl.FieldValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueOnBoard;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRef;
import org.fuin.porelo.forge.github.GitHubGraphQl.IssueRefs;
import org.fuin.porelo.forge.github.GitHubGraphQl.ItemValue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewIssue;
import org.fuin.porelo.forge.github.GitHubGraphQl.NewOption;
import org.fuin.porelo.forge.github.GitHubGraphQl.PageInfo;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectField;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectFields;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItem;
import org.fuin.porelo.forge.github.GitHubGraphQl.ProjectItems;
import org.fuin.porelo.forge.github.GitHubGraphQl.StatusChange;
import org.fuin.porelo.forge.github.GitHubGraphQl.StatusChanges;
import org.fuin.porelo.forge.github.GitHubGraphQl.UnreadableAnswer;
import org.jspecify.annotations.Nullable;

/**
 * An organization project's board over GraphQL: the fixed documents, the variables a caller's
 * values travel in, and the reading of the answers into records. Unions are read by their {@code
 * __typename}; a variant not modelled is left out.
 */
final class ProjectBoard {
    private static final String COST = "rateLimit { cost remaining resetAt }";
    private static final String PAGE = "pageInfo { hasNextPage endCursor }";
    private static final String REF =
            "nodes { id databaseId number repository { nameWithOwner } } " + PAGE;
    private static final String ISSUE =
            "id databaseId number title body state stateReason updatedAt"
                    + " repository { nameWithOwner }"
                    + " labels(first: 100) { nodes { name } "
                    + PAGE
                    + " }"
                    + " parent { id }"
                    + " subIssues(first: 100) { "
                    + REF
                    + " }"
                    + " blockedBy(first: 100) { "
                    + REF
                    + " }";
    private static final String FIELD = "field { ... on ProjectV2FieldCommon { id } }";
    private static final String VALUES =
            "fieldValues(first: 50) { nodes { __typename"
                    + " ... on ProjectV2ItemFieldSingleSelectValue { optionId name "
                    + FIELD
                    + " }"
                    + " ... on ProjectV2ItemFieldTextValue { text "
                    + FIELD
                    + " }"
                    + " ... on ProjectV2ItemFieldNumberValue { number "
                    + FIELD
                    + " } } "
                    + PAGE
                    + " }";

    static final String PROJECT_FIELDS =
            "query ProjectFields($org: String!, $number: Int!, $after: String) {"
                    + " organization(login: $org) { projectV2(number: $number) { id title updatedAt"
                    + " fields(first: 100, after: $after) { nodes { __typename"
                    + " ... on ProjectV2FieldCommon { id name dataType }"
                    + " ... on ProjectV2SingleSelectField { options { id name color description } } } "
                    + PAGE
                    + " } } } "
                    + COST
                    + " }";

    static final String PROJECT_ITEMS =
            "query ProjectItems($org: String!, $number: Int!, $after: String) {"
                    + " organization(login: $org) { projectV2(number: $number) {"
                    + " items(first: 100, after: $after) { nodes { id"
                    + " content { __typename ... on Issue { "
                    + ISSUE
                    + " } } "
                    + VALUES
                    + " } "
                    + PAGE
                    + " } } } "
                    + COST
                    + " }";

    static final String ISSUE_BOARD =
            "query IssueBoard($ids: [ID!]!) { nodes(ids: $ids) { __typename ... on Issue { "
                    + ISSUE
                    + " projectItems(first: 50) { nodes { id project { id } "
                    + VALUES
                    + " } "
                    + PAGE
                    + " }"
                    + " } } "
                    + COST
                    + " }";

    static final String STATUS_CHANGES =
            "query StatusChanges($id: ID!, $after: String) { node(id: $id) { ... on Issue {"
                    + " timelineItems(first: 100, after: $after, itemTypes: [PROJECT_V2_ITEM_STATUS_CHANGED_EVENT])"
                    + " { nodes { __typename ... on ProjectV2ItemStatusChangedEvent {"
                    + " createdAt previousStatus status actor { __typename login } project { id } } } "
                    + PAGE
                    + " } } } "
                    + COST
                    + " }";

    static final String SET_FIELD_OPTIONS =
            "mutation SetFieldOptions($input: UpdateProjectV2FieldInput!) {"
                    + " updateProjectV2Field(input: $input) { projectV2Field {"
                    + " ... on ProjectV2SingleSelectField { options { id name color description } } } } }";

    private ProjectBoard() {}

    // Variables

    static Map<String, Object> variables(@Nullable Object... pairs) {
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            Object value = pairs[index + 1];
            if (value != null) variables.put((String) pairs[index], value);
        }
        return variables;
    }

    static Map<String, Object> request(String query, Map<String, Object> variables) {
        return Map.of("query", query, "variables", variables);
    }

    /**
     * An aliased mutation: one field per part, aliased {@code a<i>}, each taking its input from the
     * variable {@code $v<i>}; only the index enters the document.
     */
    static Map<String, Object> batch(
            String name,
            String inputType,
            List<Map<String, Object>> inputs,
            IntFunction<String> field) {
        StringBuilder header = new StringBuilder("mutation ").append(name).append('(');
        StringBuilder body = new StringBuilder();
        Map<String, Object> variables = new LinkedHashMap<>();
        for (int index = 0; index < inputs.size(); index++) {
            header.append(index == 0 ? "" : ", ")
                    .append("$v")
                    .append(index)
                    .append(": ")
                    .append(inputType)
                    .append('!');
            body.append(" a").append(index).append(": ").append(field.apply(index));
            variables.put("v" + index, inputs.get(index));
        }
        return request(header.append(") {").append(body).append(" }").toString(), variables);
    }

    static Map<String, Object> fieldValueInput(String projectId, FieldValue value) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("projectId", projectId);
        input.put("itemId", value.itemId());
        input.put("fieldId", value.fieldId());
        if (!value.clears()) {
            Map<String, Object> set = new LinkedHashMap<>();
            if (value.optionId() != null) set.put("singleSelectOptionId", value.optionId());
            if (value.text() != null) set.put("text", value.text());
            if (value.number() != null) set.put("number", value.number());
            input.put("value", set);
        }
        return input;
    }

    static Map<String, Object> issueInput(NewIssue issue) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("repositoryId", issue.repositoryId());
        input.put("title", issue.title());
        if (issue.body() != null) input.put("body", issue.body());
        if (issue.parentIssueId() != null) input.put("parentIssueId", issue.parentIssueId());
        if (!issue.labelIds().isEmpty()) input.put("labelIds", issue.labelIds());
        return input;
    }

    static Map<String, Object> optionsInput(String fieldId, List<NewOption> options) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (NewOption option : options) {
            list.add(
                    Map.of(
                            "name", option.name(),
                            "color", option.color(),
                            "description", option.description()));
        }
        return Map.of("input", Map.of("fieldId", fieldId, "singleSelectOptions", list));
    }

    // Bounds

    /** Refuses an empty list, too many parts, a blank id or one given twice, before sending. */
    static void requireIds(List<String> ids, int max, String what) {
        requireSize(ids, max, what);
        Set<String> seen = new HashSet<>();
        for (String id : ids) {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("A blank " + what);
            if (!seen.add(id)) throw new IllegalArgumentException("The same " + what + " twice");
        }
    }

    static void requireSize(List<?> parts, int max, String what) {
        if (parts.isEmpty() || parts.size() > max) {
            throw new IllegalArgumentException("Expected 1 to " + max + " of " + what);
        }
    }

    static void requireId(@Nullable String id, String what) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("Expected " + what);
    }

    // Reading

    static ProjectFields projectFields(JsonNode data) {
        JsonNode project = data.path("organization").path("projectV2");
        if (!project.isObject()) throw new UnreadableAnswer("organization.projectV2");
        JsonNode fields = project.path("fields");
        List<ProjectField> read = new ArrayList<>();
        for (JsonNode node : nodes(fields, "fields")) {
            if (!node.has("id")) continue;
            List<FieldOption> options = new ArrayList<>();
            if (node.has("options"))
                for (JsonNode option : list(node.path("options"), "options"))
                    options.add(option(option));
            read.add(
                    new ProjectField(
                            text(node, "id"), text(node, "name"), text(node, "dataType"), options));
        }
        return new ProjectFields(
                text(project, "id"),
                text(project, "title"),
                instant(project, "updatedAt"),
                read,
                pageInfo(fields),
                cost(data));
    }

    static ProjectItems projectItems(JsonNode data) {
        JsonNode items = data.path("organization").path("projectV2").path("items");
        if (!items.isObject()) throw new UnreadableAnswer("organization.projectV2.items");
        List<ProjectItem> read = new ArrayList<>();
        for (JsonNode node : nodes(items, "items")) {
            JsonNode content = node.path("content");
            BoardIssue issue =
                    content.path("__typename").asText().equals("Issue") ? issue(content) : null;
            read.add(new ProjectItem(text(node, "id"), issue, values(node)));
        }
        return new ProjectItems(read, pageInfo(items), cost(data));
    }

    /** Each id's issue in its place; an id of no issue, or one GitHub does not know, reads null. */
    static IssueBoard issueBoard(JsonNode data, int asked, String projectId) {
        JsonNode nodes = data.path("nodes");
        if (!nodes.isArray() || nodes.size() != asked) throw new UnreadableAnswer("nodes");
        List<@Nullable IssueOnBoard> read = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (!node.path("__typename").asText().equals("Issue")) {
                read.add(null);
                continue;
            }
            String itemId = null;
            List<ItemValue> values = List.of();
            for (JsonNode item : nodes(node.path("projectItems"), "projectItems")) {
                if (projectId.equals(text(item.path("project"), "id"))) {
                    itemId = text(item, "id");
                    values = values(item);
                }
            }
            read.add(new IssueOnBoard(issue(node), itemId, values));
        }
        return new IssueBoard(read, cost(data));
    }

    static StatusChanges statusChanges(JsonNode data) {
        JsonNode timeline = data.path("node").path("timelineItems");
        if (!timeline.isObject()) throw new UnreadableAnswer("node.timelineItems");
        List<StatusChange> read = new ArrayList<>();
        for (JsonNode node : nodes(timeline, "timelineItems")) {
            if (!node.path("__typename").asText().equals("ProjectV2ItemStatusChangedEvent"))
                continue;
            read.add(
                    new StatusChange(
                            instant(node, "createdAt"),
                            optional(node, "previousStatus"),
                            optional(node, "status"),
                            optional(node.path("actor"), "login"),
                            optional(node.path("project"), "id")));
        }
        return new StatusChanges(read, pageInfo(timeline), cost(data));
    }

    static FieldOptions fieldOptions(JsonNode data, @Nullable RateLimit rateLimit) {
        JsonNode options = data.path("updateProjectV2Field").path("projectV2Field").path("options");
        if (!options.isArray())
            throw new UnreadableAnswer("updateProjectV2Field.projectV2Field.options");
        List<FieldOption> read = new ArrayList<>();
        for (JsonNode option : options) read.add(option(option));
        return new FieldOptions(read, rateLimit);
    }

    static String itemId(JsonNode part, String payload) {
        return text(part.path(payload), "id");
    }

    static CreatedIssue createdIssue(JsonNode part) {
        JsonNode issue = part.path("issue");
        return new CreatedIssue(
                text(issue, "id"),
                number(issue, "databaseId"),
                Math.toIntExact(number(issue, "number")),
                URI.create(text(issue, "url")));
    }

    /**
     * Reads an aliased mutation's answer: per part, in order, its value or the message GitHub gave
     * for it. An error naming no part sent, or a part with neither, fails the whole call.
     */
    static <T> Batch<T> batch(
            GitHubClient.GraphQlAnswer answer, int parts, Function<JsonNode, T> read) {
        JsonNode envelope = answer.envelope();
        Map<Integer, String> refused = new LinkedHashMap<>();
        for (JsonNode error : envelope.path("errors")) {
            String alias = error.path("path").path(0).asText();
            Integer index = aliasIndex(alias, parts);
            if (index == null || refused.containsKey(index))
                throw answer.failure(answer.scrubbed(error));
            refused.put(index, answer.scrubbed(error));
        }
        JsonNode data = envelope.path("data");
        List<Batch.Outcome<T>> outcomes = new ArrayList<>();
        for (int index = 0; index < parts; index++) {
            JsonNode part = data.path("a" + index);
            String error = refused.get(index);
            if (error != null) {
                outcomes.add(Batch.Outcome.refused(error));
            } else if (part.isObject()) {
                outcomes.add(Batch.Outcome.of(read.apply(part)));
            } else {
                throw answer.failure("GitHub answered no part " + index + " of the batch");
            }
        }
        return new Batch<>(outcomes, answer.rateLimit());
    }

    private static @Nullable Integer aliasIndex(String alias, int parts) {
        if (!alias.matches("a(0|[1-9][0-9]{0,2})")) return null;
        int index = Integer.parseInt(alias.substring(1));
        return index < parts ? index : null;
    }

    private static BoardIssue issue(JsonNode node) {
        List<String> labels = new ArrayList<>();
        for (JsonNode label : nodes(node.path("labels"), "labels")) labels.add(text(label, "name"));
        JsonNode parent = node.path("parent");
        return new BoardIssue(
                text(node, "id"),
                number(node, "databaseId"),
                Math.toIntExact(number(node, "number")),
                text(node, "title"),
                optional(node, "body"),
                text(node, "state"),
                optional(node, "stateReason"),
                instant(node, "updatedAt"),
                text(node.path("repository"), "nameWithOwner"),
                labels,
                parent.isObject() ? text(parent, "id") : null,
                refs(node.path("subIssues")),
                refs(node.path("blockedBy")));
    }

    private static IssueRefs refs(JsonNode connection) {
        if (!connection.isObject()) throw new UnreadableAnswer("an issue's connection");
        List<IssueRef> refs = new ArrayList<>();
        for (JsonNode node : nodes(connection, "an issue's connection")) {
            refs.add(
                    new IssueRef(
                            text(node, "id"),
                            number(node, "databaseId"),
                            Math.toIntExact(number(node, "number")),
                            text(node.path("repository"), "nameWithOwner")));
        }
        return new IssueRefs(refs, pageInfo(connection));
    }

    private static List<ItemValue> values(JsonNode item) {
        List<ItemValue> values = new ArrayList<>();
        for (JsonNode value : nodes(item.path("fieldValues"), "fieldValues")) {
            String kind = value.path("__typename").asText();
            switch (kind) {
                case "ProjectV2ItemFieldSingleSelectValue" ->
                        values.add(
                                new ItemValue(
                                        fieldOf(value),
                                        text(value, "optionId"),
                                        text(value, "name"),
                                        null,
                                        null));
                case "ProjectV2ItemFieldTextValue" ->
                        values.add(
                                new ItemValue(
                                        fieldOf(value), null, null, text(value, "text"), null));
                case "ProjectV2ItemFieldNumberValue" ->
                        values.add(
                                new ItemValue(
                                        fieldOf(value),
                                        null,
                                        null,
                                        null,
                                        decimal(value, "number")));
                default -> {
                    // A kind of value the board does not read, such as a date or a repository.
                }
            }
        }
        return values;
    }

    private static FieldOption option(JsonNode option) {
        return new FieldOption(
                text(option, "id"),
                text(option, "name"),
                optional(option, "color"),
                optional(option, "description"));
    }

    private static PageInfo pageInfo(JsonNode connection) {
        JsonNode page = connection.path("pageInfo");
        if (!page.path("hasNextPage").isBoolean()) throw new UnreadableAnswer("pageInfo");
        return new PageInfo(page.path("hasNextPage").asBoolean(), optional(page, "endCursor"));
    }

    private static GraphQlCost cost(JsonNode data) {
        JsonNode limit = data.path("rateLimit");
        if (!limit.path("cost").isInt() || !limit.path("remaining").isInt()) {
            throw new UnreadableAnswer("rateLimit");
        }
        return new GraphQlCost(
                limit.path("cost").asInt(),
                limit.path("remaining").asInt(),
                instant(limit, "resetAt"));
    }

    private static String text(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isTextual()) throw new UnreadableAnswer(name);
        return value.asText();
    }

    private static @Nullable String optional(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (value.isMissingNode() || value.isNull()) return null;
        if (!value.isTextual()) throw new UnreadableAnswer(name);
        return value.asText();
    }

    private static long number(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.canConvertToLong()) throw new UnreadableAnswer(name);
        return value.asLong();
    }

    private static Instant instant(JsonNode node, String name) {
        try {
            return Instant.parse(text(node, name));
        } catch (java.time.format.DateTimeParseException unreadable) {
            throw new UnreadableAnswer(name);
        }
    }

    /** A connection's {@code nodes}, each an object; a connection or list missing fails. */
    private static List<JsonNode> nodes(JsonNode connection, String what) {
        if (!connection.isObject()) throw new UnreadableAnswer(what);
        return list(connection.path("nodes"), what + ".nodes");
    }

    private static List<JsonNode> list(JsonNode array, String what) {
        if (!array.isArray()) throw new UnreadableAnswer(what);
        List<JsonNode> elements = new ArrayList<>();
        for (JsonNode element : array) {
            if (!element.isObject()) throw new UnreadableAnswer(what);
            elements.add(element);
        }
        return elements;
    }

    private static String fieldOf(JsonNode value) {
        return text(value.path("field"), "id");
    }

    private static double decimal(JsonNode node, String name) {
        JsonNode value = node.path(name);
        if (!value.isNumber() || !Double.isFinite(value.asDouble()))
            throw new UnreadableAnswer(name);
        return value.asDouble();
    }
}
