package org.fuin.porelo.forge.openapi;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

final class SchemaCorrection {
    private SchemaCorrection() {}

    static void apply(ObjectNode document) throws IOException {
        ReferenceWalker walker = new ReferenceWalker(document.has("swagger"));
        correctSchemas(walker, document.path("paths"), ReferenceWalker.Kind.MAP);
        correctSchemas(walker, document.path("webhooks"), ReferenceWalker.Kind.MAP);
        correctSchemas(walker, document.path("definitions"), ReferenceWalker.Kind.SCHEMA_MAP);
        correctSchemas(walker, document.path("parameters"), ReferenceWalker.Kind.NAMED_MAP);
        correctSchemas(walker, document.path("responses"), ReferenceWalker.Kind.NAMED_MAP);
        for (var section : document.path("components").properties()) {
            if (!section.getKey().startsWith("x-")) {
                for (JsonNode entry : section.getValue()) {
                    correctSchemas(walker, entry, ReferenceWalker.componentKind(section.getKey()));
                }
            }
        }
    }

    private static void correctSchemas(
            ReferenceWalker walker, JsonNode root, ReferenceWalker.Kind kind) throws IOException {
        for (JsonNode schema : walker.schemas(root, kind)) {
            correct(schema, walker);
        }
    }

    private static void correct(JsonNode node, ReferenceWalker walker) throws IOException {
        if (!(node instanceof ObjectNode schema)) {
            return;
        }
        if (schema.path("properties").isObject()
                && schema.has("discriminator")
                && (hasBranches(schema.path("oneOf")) || hasBranches(schema.path("anyOf")))
                && walker.references(schema.path("oneOf"), ReferenceWalker.Kind.SCHEMA).isEmpty()
                && walker.references(schema.path("anyOf"), ReferenceWalker.Kind.SCHEMA).isEmpty()) {
            schema.remove("discriminator");
        }
    }

    private static boolean hasBranches(JsonNode branches) {
        return branches.isArray() && !branches.isEmpty();
    }
}
