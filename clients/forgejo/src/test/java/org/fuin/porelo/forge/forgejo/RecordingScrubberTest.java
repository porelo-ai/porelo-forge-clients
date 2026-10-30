package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingScrubberTest {
    // Prevents plain logs from aborting recording or losing text after a JSON-looking prefix.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(
            strings = {
                "[INFO] synthetic-private-value finished",
                "{not json} synthetic-private-value",
                "{\"token\":\"synthetic-private-value\"} trailing synthetic-private-value",
                "[1] [2] synthetic-private-value"
            })
    void jsonLookingLogsFallBackToScrubbedText(String text) {
        var result =
                new RecordingScrubber(List.of("synthetic-private-value"))
                        .scrub(com.fasterxml.jackson.databind.node.TextNode.valueOf(text));
        assertThat(result.asText())
                .as("all log text survives with each credential removed")
                .isEqualTo(text.replace("synthetic-private-value", "[redacted]"));
    }

    // Prevents credentials in an entire embedded JSON value from escaping key-based scrubbing.
    @Test
    void aCompleteJsonValueIsScrubbedByItsKeys() throws Exception {
        var result =
                new RecordingScrubber(List.of())
                        .scrub(
                                com.fasterxml.jackson.databind.node.TextNode.valueOf(
                                        " [{\"token\":\"unknown-private-value\"}] \n"));
        assertThat(new ObjectMapper().readTree(result.asText()).at("/0/token").asText())
                .as("an embedded credential key is scrubbed even without a known secret")
                .isEqualTo("[redacted]");
    }

    // Prevents private credentials and machine addresses from entering recordings.
    @Test
    void credentialsAndLiveAddressesNeverReachTheRecordingFileContent() throws Exception {
        String sensitive = "synthetic-private-value";
        var json = new ObjectMapper();
        var recording = json.createObjectNode();
        recording.put(
                "body",
                "{\"config\":{\"secret\":\""
                        + sensitive
                        + "\"},\"authorization_header\":\"unlisted-private-value\","
                        + "\"url\":\"http://127.0.0.1:35421/log?token=unknown-private-value\",\"note\":\""
                        + sensitive
                        + "\"}");
        recording.put(
                "location", "https://storage.example.net/path?signature=unknown-query-credential");
        String result =
                json.writeValueAsString(new RecordingScrubber(List.of(sensitive)).scrub(recording));
        assertThat(result)
                .as(
                        "only scrubbed bytes may reach disk, including embedded JSON and URL query credentials")
                .doesNotContain(
                        sensitive,
                        "unlisted-private-value",
                        "unknown-private-value",
                        "unknown-query-credential",
                        "127.0.0.1",
                        "35421",
                        "storage.example.net")
                .contains("public-fixture-key", "example.org");
    }
}
