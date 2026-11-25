package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Writes the metadata the jar ships from what the tracing agent recorded: its reflection alone,
 * sorted by type so a new recording differs only where the mapping did. The agent's resources are
 * the test run's own (JUnit's and Surefire's service files); the client reads no resource.
 */
final class GitHubNativeMetadata {
    private GitHubNativeMetadata() {}

    /**
     * Reads the agent's merged output, the first argument, checks it against the samples the
     * round-trip test wrote, the second, and writes the shipped metadata, the third.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3) {
            throw new IllegalArgumentException(
                    "Expected the agent's output, the samples and the shipped file");
        }
        Path recorded = Path.of(args[0]);
        if (!Files.isRegularFile(recorded)) {
            throw new IllegalStateException(
                    recorded + " is missing: record with -Pnative -Dagent=true first");
        }
        ObjectMapper mapper = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
        JsonNode agent = mapper.readTree(recorded.toFile());
        var unknown = new ArrayList<String>();
        agent.fieldNames()
                .forEachRemaining(
                        name -> {
                            if (!name.equals("reflection") && !name.equals("resources")) {
                                unknown.add(name);
                            }
                        });
        if (!unknown.isEmpty()) {
            throw new IllegalStateException("The agent recorded more than reflection: " + unknown);
        }
        List<JsonNode> entries = new ArrayList<>();
        agent.path("reflection").forEach(entries::add);
        if (entries.isEmpty()) throw new IllegalStateException("The agent recorded no reflection");
        requireEverySampledClass(mapper, Path.of(args[1]), entries);
        entries.sort(Comparator.comparing(entry -> entry.path("type").toString()));
        ObjectNode shipped = mapper.createObjectNode();
        ArrayNode reflection = shipped.putArray("reflection");
        entries.forEach(reflection::add);
        Path target = Path.of(args[2]);
        Files.createDirectories(target.toAbsolutePath().getParent());
        Files.writeString(target, mapper.writeValueAsString(shipped) + "\n");
    }

    /** A recording cut short by a failed sample would ship without the classes after it. */
    private static void requireEverySampledClass(
            ObjectMapper mapper, Path samples, List<JsonNode> entries) throws Exception {
        if (!Files.isRegularFile(samples)) {
            throw new IllegalStateException(
                    samples + " is missing: the round-trip test did not run");
        }
        var recorded = new java.util.HashSet<String>();
        entries.forEach(entry -> recorded.add(entry.path("type").asText()));
        var missing = new java.util.TreeSet<String>();
        for (JsonNode sample : mapper.readTree(samples.toFile())) {
            String expect = sample.path("expect").asText();
            if (!expect.startsWith("java.") && !recorded.contains(expect)) missing.add(expect);
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The agent recorded no access to " + missing);
        }
    }
}
