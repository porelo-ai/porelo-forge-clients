package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ForgejoWebhookRecordingTest {
    // Prevents scrubbing or routing errors from invalidating recorded webhook contracts.
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
            switch (event) {
                case "pull_request" ->
                        assertThat(payload)
                                .as("a pull request delivery selects its recorded contract")
                                .isInstanceOf(ForgejoWebhook.PullRequestEvent.class);
                case "pull_request_approved" -> {
                    assertThat(payload)
                            .as("an approval selects the review contract")
                            .isInstanceOf(ForgejoWebhook.ReviewEvent.class);
                    assertThat(((ForgejoWebhook.ReviewEvent) payload).review().type())
                            .as("the recorded approval carries its verdict")
                            .isEqualTo("pull_request_review_approved");
                }
                case "action_run_success" -> {
                    assertThat(payload)
                            .as("an Actions delivery selects the run contract")
                            .isInstanceOf(ForgejoWebhook.ActionsRunEvent.class);
                    assertThat(((ForgejoWebhook.ActionsRunEvent) payload).run())
                            .as("the recorded Actions delivery identifies its run")
                            .isNotNull();
                }
                default -> throw new AssertionError(event);
            }
        }
    }
}
