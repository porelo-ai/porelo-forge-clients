package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordingHttpClientTest {
    // Prevents signed storage query credentials from entering scrubbed log recordings.
    @Test
    void capturesApiQueriesWithoutPersistingRedirectCredentials() throws Exception {
        String storageSignature = "unlisted-storage-signature";
        var replay =
                new ReplayHttpClient()
                        .answer(200, Map.of(), "{\"workflow_runs\":[]}")
                        .answer(
                                302,
                                Map.of(
                                        "location",
                                        List.of(
                                                "https://storage.example.org/log?signature="
                                                        + storageSignature)),
                                "")
                        .answer(200, Map.of(), "fixture-complete");
        var capture = new RecordingHttpClient(replay);
        var client = new ForgejoClient(URI.create("https://example.org/api/v1/"), capture);
        var json = new ObjectMapper();
        var scrubber = new RecordingScrubber(List.of());

        client.runs("synthetic-access-token", "owner", "repo", "abc", 1, 50);
        var api = scrubber.scrub(capture.take().getFirst());
        assertThat(URI.create(api.path("request_uri").asText()).getRawQuery())
                .as("the live API filters and paging must survive recording")
                .isEqualTo("head_sha=abc&page=1&limit=50");

        client.jobLogs("synthetic-access-token", "owner", "repo", 1);
        var exchanges = capture.take();
        assertThat(exchanges)
                .as("the regression exercises the API and storage requests")
                .hasSize(2);
        String recording = json.writeValueAsString(scrubber.scrub(json.valueToTree(exchanges)));
        assertThat(recording)
                .as(
                        "only API query parameters may persist; signed redirect queries are credentials")
                .doesNotContain(storageSignature)
                .contains("fixture-complete");
    }
}
