package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;

/**
 * Every value the client binds is written and read back with the mapper it uses at run time: on the
 * JVM under the tracing agent, which records the reachability metadata the jar ships, and in a
 * native image built with no metadata but the module's own, which then must read them all.
 */
class GitHubModelRoundTripTest {
    /** Written by the JVM run, read by the native image the same build makes after it. */
    static final Path SAMPLES = Path.of("target", "github-model-samples.json");

    private static final ObjectMapper MAPPER = GitHubJson.mapper(Strictness.STRICT);

    // "runtime" in a native image; the tracing agent's JVM run sets it to "agent".
    private static final boolean NATIVE =
            "runtime".equals(System.getProperty("org.graalvm.nativeimage.imagecode"));

    @BeforeAll
    static void writeTheSamplesOnTheJvm() throws Exception {
        if (!NATIVE) GitHubModelSamples.write(MAPPER, SAMPLES);
    }

    // Prevents a model class from shipping without the metadata a native user needs to bind it.
    @Test
    @DisabledIfSystemProperty(named = "org.graalvm.nativeimage.imagecode", matches = "runtime")
    void everyModelClassHasASample() throws Exception {
        var sampled = new ArrayList<String>();
        for (JsonNode sample : samples()) sampled.add(sample.path("type").asText());
        for (Class<?> type : GitHubModelSamples.modelClasses()) {
            if (type.isInterface() && !GitHubModelSamples.isUnion(type)) continue;
            assertThat(sampled)
                    .as("the model class %s is read back", type.getName())
                    .contains(type.getName());
        }
    }

    // Prevents the agent's recording from shipping a test class or a test library's to native
    // users.
    @Test
    @DisabledIfSystemProperty(named = "org.graalvm.nativeimage.imagecode", matches = "runtime")
    void theShippedMetadataNamesOnlyWhatAUserHas() throws Exception {
        Path main =
                Path.of(
                        GitHubClient.class
                                .getProtectionDomain()
                                .getCodeSource()
                                .getLocation()
                                .toURI());
        JsonNode metadata;
        try (var in =
                GitHubClient.class.getResourceAsStream(
                        "/META-INF/native-image/org.fuin.porelo/porelo-forge-client-github/"
                                + "reachability-metadata.json")) {
            assertThat(in).as("the jar ships its native-image metadata").isNotNull();
            metadata = MAPPER.readTree(in);
        }
        var named = new ArrayList<String>();
        metadata.findValues("type").forEach(type -> named.add(type.asText()));
        assertThat(named).as("the metadata names the classes it registers").isNotEmpty();
        var foreign = new ArrayList<String>();
        for (String name : named) {
            String plain = name.replace("[]", "");
            if (plain.isEmpty() || !Character.isJavaIdentifierStart(plain.charAt(0))) continue;
            Class<?> type;
            try {
                type = Class.forName(plain, false, getClass().getClassLoader());
            } catch (ClassNotFoundException ex) {
                foreign.add(name + " (not on the class path)");
                continue;
            }
            var source = type.getProtectionDomain().getCodeSource();
            if (source == null) continue;
            Path location = Path.of(source.getLocation().toURI());
            boolean ofTheClient = location.equals(main);
            boolean ofARuntimeLibrary =
                    location.toString().contains("com/fasterxml/jackson")
                            || location.toString().contains("org/jspecify");
            if (!ofTheClient && !ofARuntimeLibrary) foreign.add(name + " (from " + location + ")");
        }
        assertThat(foreign).as("only the client's and its run-time libraries' classes").isEmpty();
    }

    // Prevents the native image from missing a class, its accessors, its array or a union's reader.
    @Test
    void everySampleReadsBackAsItWasWritten() throws Exception {
        var failed = new ArrayList<String>();
        for (JsonNode sample : samples()) {
            String type = sample.path("type").asText();
            JsonNode json = sample.path("json");
            try {
                Object value = MAPPER.treeToValue(json, Class.forName(type));
                String expect = sample.path("expect").asText();
                if (expect.equals(Void.class.getName())) {
                    if (value != null) failed.add(type + ": expected no value, read " + value);
                    continue;
                }
                if (value == null || !Class.forName(expect).isInstance(value)) {
                    failed.add(type + ": expected a " + expect + ", read " + value);
                    continue;
                }
                // Through text, as the sample was, so a number is read as the same kind of node.
                JsonNode written = MAPPER.readTree(MAPPER.writeValueAsString(value));
                if (!written.equals(json)) {
                    failed.add(type + ": wrote " + written + " for " + json);
                }
            } catch (Exception | LinkageError ex) {
                failed.add(type + ": " + ex);
            }
        }
        assertThat(failed).as("every sample is read back as it was written").isEmpty();
    }

    private static List<JsonNode> samples() throws Exception {
        assertThat(SAMPLES)
                .as("the JVM run writes the samples before the native image reads them")
                .exists();
        var list = new ArrayList<JsonNode>();
        MAPPER.readTree(Files.readString(SAMPLES)).forEach(list::add);
        assertThat(list).as("samples were written").isNotEmpty();
        return list;
    }
}
