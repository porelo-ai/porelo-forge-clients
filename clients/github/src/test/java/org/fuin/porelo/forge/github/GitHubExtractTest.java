package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GitHubExtractTest {
    // GitHub's identifiers have passed the 32-bit range; one the description leaves without a
    // format, or gives another, reads as an int.
    @Test
    void everyIntegerIdentifierIsInt64() throws Exception {
        JsonNode extract =
                new ObjectMapper().readTree(Path.of("src/main/openapi/github.json").toFile());
        List<String> checked = new ArrayList<>();
        List<String> found = new ArrayList<>();

        collect(extract, "#", checked, found);

        assertThat(checked).as("integer identifiers seen").hasSizeGreaterThan(40);
        assertThat(found).as("integer identifiers not int64").isEmpty();
    }

    private static void collect(
            JsonNode node, String path, List<String> checked, List<String> found) {
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                collect(node.get(index), path + "/" + index, checked, found);
            }
            return;
        }
        if (!node.isObject()) return;
        for (Map.Entry<String, JsonNode> property : node.path("properties").properties()) {
            String name = property.getKey();
            JsonNode schema = property.getValue();
            boolean integer =
                    schema.path("type").asText().equals("integer")
                            || (schema.path("type").isArray()
                                    && schema.path("type").toString().contains("\"integer\""));
            if (integer && (name.equals("id") || name.endsWith("_id"))) {
                checked.add(path + "/properties/" + name);
                if (!schema.path("format").asText().equals("int64"))
                    found.add(path + "/properties/" + name);
            }
        }
        for (Map.Entry<String, JsonNode> child : node.properties()) {
            collect(child.getValue(), path + "/" + child.getKey(), checked, found);
        }
    }
}
