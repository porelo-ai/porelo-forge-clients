package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecordingScrubberTest {
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
