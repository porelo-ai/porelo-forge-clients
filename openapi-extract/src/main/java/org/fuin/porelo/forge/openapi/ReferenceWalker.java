package org.fuin.porelo.forge.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Set;

final class ReferenceWalker {
    enum Kind {
        OBJECT,
        MAP,
        NAMED_MAP,
        SCHEMA,
        SCHEMA_MAP,
        EXAMPLE,
        EXAMPLE_MAP,
        LINK,
        LINK_MAP
    }

    private static final Set<String> SCHEMA_MAPS =
            Set.of(
                    "properties",
                    "patternProperties",
                    "$defs",
                    "definitions",
                    "dependentSchemas",
                    "dependencies");
    private static final Set<String> SCHEMA_CHILDREN =
            Set.of(
                    "items",
                    "additionalItems",
                    "additionalProperties",
                    "unevaluatedProperties",
                    "unevaluatedItems",
                    "contains",
                    "not",
                    "if",
                    "then",
                    "else",
                    "propertyNames",
                    "contentSchema",
                    "oneOf",
                    "anyOf",
                    "allOf",
                    "prefixItems");
    private static final Set<String> OBJECT_MAPS =
            Set.of("responses", "headers", "content", "callbacks", "encoding");
    private static final Set<String> DATA =
            Set.of("example", "default", "enum", "const", "security");
    private final boolean swagger;

    ReferenceWalker(boolean swagger) {
        this.swagger = swagger;
    }

    List<String> references(JsonNode root, Kind kind) throws IOException {
        List<String> references = new ArrayList<>();
        walk(root, kind, frame -> collectReference(frame.node(), references));
        return references;
    }

    List<JsonNode> schemas(JsonNode root, Kind kind) throws IOException {
        List<JsonNode> schemas = new ArrayList<>();
        walk(
                root,
                kind,
                frame -> {
                    if (frame.kind() == Kind.SCHEMA) {
                        schemas.add(frame.node());
                    }
                });
        return schemas;
    }

    static Kind componentKind(String section) {
        return switch (section) {
            case "schemas", "definitions" -> Kind.SCHEMA;
            case "examples" -> Kind.EXAMPLE;
            case "links" -> Kind.LINK;
            default -> Kind.OBJECT;
        };
    }

    private void walk(JsonNode root, Kind kind, Visitor visitor) throws IOException {
        Deque<Frame> pending = new ArrayDeque<>();
        pending.add(new Frame(root, kind));
        while (!pending.isEmpty()) {
            Frame frame = pending.removeFirst();
            if (frame.node().isArray()) {
                frame.node().forEach(child -> pending.addLast(new Frame(child, frame.kind())));
            } else if (isMap(frame.kind())) {
                for (var field : frame.node().properties()) {
                    if (frame.kind() != Kind.MAP || !field.getKey().startsWith("x-")) {
                        pending.addLast(new Frame(field.getValue(), entryKind(frame.kind())));
                    }
                }
            } else {
                visitor.visit(frame);
                enqueueChildren(frame, pending);
            }
        }
    }

    private static void collectReference(JsonNode node, List<String> references)
            throws IOException {
        JsonNode reference = node.path("$ref");
        if (!reference.isMissingNode()) {
            if (!reference.isTextual()) {
                throw new IOException("Expected a string for $ref: " + reference);
            }
            references.add(reference.asText());
        }
    }

    private void enqueueChildren(Frame frame, Deque<Frame> pending) {
        if (frame.kind() == Kind.EXAMPLE || frame.kind() == Kind.LINK) {
            return;
        }
        for (var field : frame.node().properties()) {
            String key = field.getKey();
            if (key.equals("$ref") || key.startsWith("x-")) {
                continue;
            }
            if (frame.kind() == Kind.SCHEMA) {
                if (SCHEMA_MAPS.contains(key)) {
                    pending.addLast(new Frame(field.getValue(), Kind.SCHEMA_MAP));
                } else if (SCHEMA_CHILDREN.contains(key)) {
                    pending.addLast(new Frame(field.getValue(), Kind.SCHEMA));
                }
            } else if (!DATA.contains(key)) {
                enqueueObjectField(key, field.getValue(), pending);
            }
        }
    }

    private void enqueueObjectField(String key, JsonNode value, Deque<Frame> pending) {
        if (key.equals("examples")) {
            if (!swagger) {
                pending.addLast(new Frame(value, Kind.EXAMPLE_MAP));
            }
        } else if (key.equals("schema")) {
            pending.addLast(new Frame(value, Kind.SCHEMA));
        } else if (key.equals("links")) {
            pending.addLast(new Frame(value, Kind.LINK_MAP));
        } else if (OBJECT_MAPS.contains(key)) {
            pending.addLast(new Frame(value, key.equals("responses") ? Kind.MAP : Kind.NAMED_MAP));
        } else if (!(key.equals("parameters") && value.isObject())) {
            pending.addLast(new Frame(value, Kind.OBJECT));
        }
    }

    private static boolean isMap(Kind kind) {
        return kind == Kind.MAP
                || kind == Kind.NAMED_MAP
                || kind == Kind.SCHEMA_MAP
                || kind == Kind.EXAMPLE_MAP
                || kind == Kind.LINK_MAP;
    }

    private static Kind entryKind(Kind kind) {
        return switch (kind) {
            case SCHEMA_MAP -> Kind.SCHEMA;
            case EXAMPLE_MAP -> Kind.EXAMPLE;
            case LINK_MAP -> Kind.LINK;
            default -> Kind.OBJECT;
        };
    }

    private record Frame(JsonNode node, Kind kind) {}

    @FunctionalInterface
    private interface Visitor {
        void visit(Frame frame) throws IOException;
    }
}
