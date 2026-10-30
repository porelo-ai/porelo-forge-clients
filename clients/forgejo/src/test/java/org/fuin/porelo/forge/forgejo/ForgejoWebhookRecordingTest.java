package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoWebhookRecordingTest {
    @ParameterizedTest(name = "{0} verifies and reads its recorded delivery")
    @ValueSource(strings = {"pull_request", "pull_request_approved", "action_run_success"})
    void recordedDeliveriesAreSignedAfterScrubbingAndReadStrictly(String event) throws Exception {
        try (var input = getClass().getResourceAsStream("/recordings/webhook-" + event + ".json")) {
            assertThat(input)
                    .as(
                            "every operation and delivery has its recording, made by ForgejoRecorder: %s",
                            event)
                    .isNotNull();
            var recording = new ObjectMapper().readTree(input);
            assertThat(recording.path("event").asText())
                    .as("the recorded header selects the payload schema")
                    .isEqualTo(event);
            var payload =
                    new ForgejoWebhook(WebhookReceiver.LIMIT, Strictness.STRICT)
                            .read(
                                    new ByteArrayInputStream(
                                            recording
                                                    .path("body")
                                                    .asText()
                                                    .getBytes(StandardCharsets.UTF_8)),
                                    recording.path("signature").asText(),
                                    event,
                                    RecordingScrubber.TEST_SECRET.getBytes(StandardCharsets.UTF_8));
            assertThat(payload)
                    .as(
                            "the recording remains authenticated after address and credential scrubbing")
                    .isNotNull();
        }
    }
}
