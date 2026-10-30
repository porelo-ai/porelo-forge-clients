package org.fuin.porelo.forge.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class OpenApiDocument {
    private final ObjectNode source;
    private final Map<String, JsonNode> components = new LinkedHashMap<>();
    private final boolean supportsWebhooks;
    private final ObjectNode paths;
    private final ReferenceWalker walker;

    OpenApiDocument(ObjectNode source) throws IOException {
        this.source = source;
        String version = source.path("openapi").asText();
        if (!(version.startsWith("3.") || source.path("swagger").asText().equals("2.0"))) {
            throw new IOException("Expected OpenAPI 3.x or Swagger 2.0");
        }
        supportsWebhooks = version.startsWith("3.1.");
        walker = new ReferenceWalker(source.path("swagger").asText().equals("2.0"));
        ExtractService.requireObject(source.path("info"), "info");
        ExtractService.requireObject(source.path("paths"), "paths");
        if (version.startsWith("3.")) {
            JsonNode sections = source.path("components");
            if (!sections.isMissingNode()) {
                ExtractService.requireObject(sections, "components");
                for (Map.Entry<String, JsonNode> section : sections.properties()) {
                    if (!section.getKey().startsWith("x-")) {
                        index("/components/" + escape(section.getKey()), section.getValue());
                    }
                }
            }
        } else {
            for (String section : List.of("definitions", "parameters", "responses")) {
                if (source.has(section)) {
                    index("/" + section, source.path(section));
                }
            }
        }
        paths = source.withObject("/paths").deepCopy();
        for (String path : names(paths)) {
            if (!path.startsWith("x-")) {
                paths.set(path, resolvePathItem(paths.path(path), new LinkedHashSet<>()));
            }
        }
    }

    ObjectNode extract(Selection selection) throws IOException {
        List<String> problems = missing(selection);
        if (!problems.isEmpty()) {
            throw new Problems(problems);
        }
        ObjectNode extract = selected(selection);
        Set<String> reached = reached(extract);
        prune(extract, reached);
        SchemaCorrection.apply(extract);
        return extract;
    }

    void check(Selection selection) throws IOException {
        List<String> problems = missing(selection);
        Set<String> operations = operations(paths);
        for (String operation : operations) {
            if (!selection.operations().contains(operation)) {
                problems.add("Unlisted operation: " + operation);
            }
        }
        for (String webhook : names(source.path("webhooks"))) {
            if (!webhook.startsWith("x-") && !selection.webhooks().contains(webhook)) {
                problems.add("Unlisted webhook: " + webhook);
            }
        }
        Set<String> reached = reached(selected(selection));
        for (String component : components.keySet()) {
            if (!reached.contains(component)) {
                problems.add("Unreached component: #" + component);
            }
        }
        if (!problems.isEmpty()) {
            throw new Problems(problems);
        }
    }

    private void index(String prefix, JsonNode entries) throws IOException {
        ExtractService.requireObject(entries, "#" + prefix);
        for (Map.Entry<String, JsonNode> entry : entries.properties()) {
            components.put(prefix + "/" + escape(entry.getKey()), entry.getValue());
        }
    }

    private List<String> missing(Selection selection) {
        List<String> problems = new ArrayList<>();
        Set<String> available = operations(paths);
        for (String operation : selection.operations()) {
            if (!available.contains(operation)) {
                problems.add("Missing operation: " + operation);
            }
        }
        for (String webhook : selection.webhooks()) {
            if (!supportsWebhooks || !source.path("webhooks").has(webhook)) {
                problems.add("Missing webhook: " + webhook);
            }
        }
        return problems;
    }

    private ObjectNode selected(Selection selection) throws IOException {
        ObjectNode extract = source.deepCopy();
        extract.set("paths", paths.deepCopy());
        ObjectNode paths = ExtractService.requireObject(extract.path("paths"), "paths");
        for (String path : names(paths)) {
            if (path.startsWith("x-")) {
                continue;
            }
            ObjectNode item = ExtractService.requireObject(paths.path(path), "path " + path);
            boolean kept = false;
            for (String method : names(item)) {
                if (Selection.METHODS.contains(method)) {
                    if (selection.operations().contains(method + " " + path)) {
                        kept = true;
                    } else {
                        item.remove(method);
                    }
                }
            }
            if (!kept) {
                paths.remove(path);
            }
        }
        if (extract.has("webhooks")) {
            ObjectNode webhooks =
                    ExtractService.requireObject(extract.path("webhooks"), "webhooks");
            for (String webhook : names(webhooks)) {
                if (!webhook.startsWith("x-")
                        && (!supportsWebhooks || !selection.webhooks().contains(webhook))) {
                    webhooks.remove(webhook);
                }
            }
        }
        extract.remove("x-webhooks");
        return extract;
    }

    private Set<String> reached(ObjectNode extract) throws IOException {
        Set<String> reached = new LinkedHashSet<>();
        Deque<String> pending = new ArrayDeque<>();
        pending.addAll(walker.references(extract.path("paths"), ReferenceWalker.Kind.MAP));
        pending.addAll(walker.references(extract.path("webhooks"), ReferenceWalker.Kind.MAP));
        while (!pending.isEmpty()) {
            String reference = pending.removeFirst();
            String pointer = pointer(reference);
            if (source.at(pointer).isMissingNode()) {
                throw new IOException("Unresolved $ref: " + reference);
            }
            String owner = owner(pointer);
            if (reached.add(owner)) {
                // A nested pointer retains the whole reusable entry, including its other refs.
                pending.addAll(
                        walker.references(
                                Objects.requireNonNull(components.get(owner)), kind(owner)));
            }
        }
        return reached;
    }

    private static ReferenceWalker.Kind kind(String owner) {
        if (owner.startsWith("/definitions/")) {
            return ReferenceWalker.Kind.SCHEMA;
        }
        if (owner.startsWith("/components/")) {
            return ReferenceWalker.componentKind(owner.split("/", 4)[2]);
        }
        return ReferenceWalker.Kind.OBJECT;
    }

    private ObjectNode resolvePathItem(JsonNode node, Set<String> resolving) throws IOException {
        ObjectNode item = ExtractService.requireObject(node, "path item");
        if (!item.has("$ref")) {
            return item.deepCopy();
        }
        JsonNode ref = item.path("$ref");
        if (!ref.isTextual()) {
            throw new IOException("Expected a string for path item $ref");
        }
        String pointer = pointer(ref.asText());
        if (!resolving.add(pointer)) {
            throw new IOException("Cyclic path item $ref: " + ref.asText());
        }
        ObjectNode resolved = resolvePathItem(source.at(pointer), resolving);
        for (var field : item.properties()) {
            if (!field.getKey().equals("$ref")) {
                if (resolved.has(field.getKey())) {
                    throw new IOException(
                            "Conflicting path item field alongside $ref: " + field.getKey());
                }
                resolved.set(field.getKey(), field.getValue().deepCopy());
            }
        }
        return resolved;
    }

    private static String pointer(String reference) throws IOException {
        if (!reference.startsWith("#/")) {
            throw new IOException("Expected a local JSON pointer for $ref: " + reference);
        }
        try {
            String fragment = URI.create(reference).getFragment();
            if (fragment == null) {
                throw new IllegalArgumentException("No fragment");
            }
            return fragment;
        } catch (IllegalArgumentException ex) {
            throw new IOException("Invalid $ref: " + reference, ex);
        }
    }

    private String owner(String pointer) throws IOException {
        String candidate = pointer;
        while (!candidate.isEmpty()) {
            if (components.containsKey(candidate)) {
                return candidate;
            }
            candidate = candidate.substring(0, candidate.lastIndexOf('/'));
        }
        throw new IOException("$ref does not target a reusable component: #" + pointer);
    }

    private void prune(ObjectNode extract, Set<String> reached) {
        for (String pointer : components.keySet()) {
            if (!reached.contains(pointer)) {
                int separator = pointer.lastIndexOf('/');
                ObjectNode section = (ObjectNode) extract.at(pointer.substring(0, separator));
                section.remove(unescape(pointer.substring(separator + 1)));
            }
        }
    }

    private static Set<String> operations(JsonNode paths) {
        Set<String> operations = new LinkedHashSet<>();
        for (Map.Entry<String, JsonNode> path : paths.properties()) {
            if (path.getKey().startsWith("x-")) {
                continue;
            }
            for (String method : names(path.getValue())) {
                if (Selection.METHODS.contains(method)) {
                    operations.add(method + " " + path.getKey());
                }
            }
        }
        return operations;
    }

    private static List<String> names(JsonNode node) {
        List<String> names = new ArrayList<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static String escape(String token) {
        return token.replace("~", "~0").replace("/", "~1");
    }

    private static String unescape(String token) {
        return token.replace("~1", "/").replace("~0", "~");
    }
}
