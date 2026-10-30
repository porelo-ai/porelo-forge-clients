package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgejoRecorderTest {
    @TempDir Path directory;

    @Test
    void recordingAgainReplacesTheDeliveryThatReplayWillRead() throws Exception {
        var scrubber = new RecordingScrubber(List.of());
        ForgejoRecorder.writeDeliveries(
                directory,
                List.of(new WebhookReceiver.Delivery("pull_request", "{\"number\":1}")),
                scrubber);
        ForgejoRecorder.writeDeliveries(
                directory,
                List.of(new WebhookReceiver.Delivery("pull_request", "{\"number\":2}")),
                scrubber);
        var json = new ObjectMapper();
        var recorded = json.readTree(directory.resolve("webhook-pull_request.json").toFile());
        assertThat(json.readTree(recorded.path("body").asText()).path("number").asInt())
                .as(
                        "a new recording must expose drift instead of silently replaying the previous capture")
                .isEqualTo(2);
    }

    @Test
    void malformedLiveDeliveryCannotExposeRawInputInADiagnostic() {
        assertThatThrownBy(
                        () ->
                                ForgejoRecorder.writeDeliveries(
                                        directory,
                                        List.of(
                                                new WebhookReceiver.Delivery(
                                                        "pull_request",
                                                        "syntheticPrivateMalformedData")),
                                        new RecordingScrubber(
                                                List.of("syntheticPrivateMalformedData"))))
                .as(
                        "a parser exception must never copy an unsanitized live delivery into logs or causes")
                .hasMessageNotContaining("syntheticPrivateMalformedData")
                .hasNoCause();
    }
}
