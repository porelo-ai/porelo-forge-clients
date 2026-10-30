package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ForgejoRecorderTest {
    @TempDir Path directory;

    // Prevents live verification from accepting unmodeled fields that strict replay would refuse.
    @Test
    void liveDeliveryVerificationIsStrict() {
        var deliveries =
                List.of(
                        new WebhookReceiver.Delivery(
                                "pull_request",
                                "{\"action\":\"opened\",\"number\":1,\"pull_request\":{},\"future\":true}"),
                        new WebhookReceiver.Delivery(
                                "pull_request_approved",
                                "{\"action\":\"reviewed\",\"number\":1,\"pull_request\":{},\"review\":{\"type\":\"pull_request_review_approved\"}}"),
                        new WebhookReceiver.Delivery(
                                "action_run_success", "{\"action\":\"success\",\"run\":{}}"));
        assertThatExceptionOfType(ForgejoException.class)
                .as("live verification must expose API drift before re-recording")
                .isThrownBy(() -> ForgejoRecorder.verifyDeliveries(deliveries))
                .withMessageContaining("future");
    }

    // Prevents stale captures from concealing API changes after recording again.
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

    // Prevents private malformed delivery bytes from leaking in diagnostics.
    @Test
    void malformedLiveDeliveryCannotExposeRawInputInADiagnostic() {
        assertThatExceptionOfType(IllegalStateException.class)
                .as(
                        "a parser exception must never copy an unsanitized live delivery into logs or causes")
                .isThrownBy(
                        () ->
                                ForgejoRecorder.writeDeliveries(
                                        directory,
                                        List.of(
                                                new WebhookReceiver.Delivery(
                                                        "pull_request",
                                                        "syntheticPrivateMalformedData")),
                                        new RecordingScrubber(
                                                List.of("syntheticPrivateMalformedData"))))
                .withMessageNotContaining("syntheticPrivateMalformedData")
                .withNoCause();
    }
}
