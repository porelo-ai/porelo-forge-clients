package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ForgejoWebhookTest {
    private static final byte[] SECRET = "public-fixture-key".getBytes(StandardCharsets.UTF_8);
    private static final String PULL =
            "{\"action\":\"opened\",\"number\":1,\"pull_request\":{\"id\":7}}";

    // Prevents caller errors from consuming a delivery before required metadata is validated.
    @Test
    void nullArgumentsFailBeforeReadingTheBody() {
        var reader = new ForgejoWebhook(512);
        var input = stream(PULL);
        java.util.List<Runnable> calls =
                java.util.List.of(
                        () -> reader.read(null, signature(PULL), "pull_request", SECRET),
                        () -> reader.read(input, null, "pull_request", SECRET),
                        () -> reader.read(input, signature(PULL), null, SECRET),
                        () -> reader.read(input, signature(PULL), "pull_request", null));
        for (Runnable call : calls) {
            assertThatExceptionOfType(NullPointerException.class)
                    .as("all read arguments are required before I/O")
                    .isThrownBy(call::run);
        }
        assertThat(input.available())
                .as("invalid calls leave the delivery unread")
                .isEqualTo(PULL.length());
    }

    // Prevents authenticated deliveries from being routed to the wrong payload contract.
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
        "pull_request, PullRequestEvent, opened",
        "pull_request_approved, ReviewEvent, pull_request_review_approved",
        "pull_request_rejected, ReviewEvent, pull_request_review_rejected",
        "pull_request_comment, ReviewEvent, pull_request_review_comment",
        "action_run_success, ActionsRunEvent, success",
        "action_run_failure, ActionsRunEvent, failure"
    })
    void everySupportedEventRoutesToItsPayload(String event, String type, String action) {
        String body =
                event.startsWith("action_run_")
                        ? "{\"action\":\"" + action + "\",\"run\":{\"id\":7}}"
                        : event.equals("pull_request")
                                ? PULL
                                : PULL.substring(0, PULL.length() - 1)
                                        + ",\"review\":{\"type\":\""
                                        + action
                                        + "\"}}";
        var payload =
                new ForgejoWebhook(512, Strictness.STRICT)
                        .read(stream(body), signature(body), event, SECRET);
        Class<? extends ForgejoWebhook.Payload> expected =
                switch (type) {
                    case "PullRequestEvent" -> ForgejoWebhook.PullRequestEvent.class;
                    case "ReviewEvent" -> ForgejoWebhook.ReviewEvent.class;
                    case "ActionsRunEvent" -> ForgejoWebhook.ActionsRunEvent.class;
                    default -> throw new AssertionError(type);
                };
        assertThat(payload).as("%s selects its documented payload", event).isInstanceOf(expected);
        if (payload instanceof ForgejoWebhook.ReviewEvent review) {
            assertThat(review.review().type())
                    .as("the review verdict survives routing")
                    .isEqualTo(action);
        } else if (payload instanceof ForgejoWebhook.ActionsRunEvent run) {
            assertThat(run.run().id()).as("the Actions delivery identifies its run").isEqualTo(7L);
        }
    }

    // Prevents authenticated but unsupported events from triggering work under another schema.
    @Test
    void anUnknownSignedEventIsRefused() {
        assertThatExceptionOfType(ForgejoException.class)
                .as("authentication alone does not authorize an unknown event contract")
                .isThrownBy(
                        () ->
                                new ForgejoWebhook(512)
                                        .read(
                                                stream(PULL),
                                                signature(PULL),
                                                "unknown_event",
                                                SECRET))
                .withMessage("Unsupported webhook event");
    }

    // Prevents unauthenticated input from reaching payload parsing.
    @Test
    void anInvalidSignatureIsRefusedBeforeMalformedJsonIsParsed() {
        assertThatExceptionOfType(ForgejoException.class)
                .as("untrusted input must never reach payload parsing")
                .isThrownBy(
                        () ->
                                new ForgejoWebhook(256)
                                        .read(
                                                stream("not json"),
                                                "00".repeat(32),
                                                "pull_request",
                                                SECRET))
                .withMessage("Invalid webhook signature");
    }

    // Prevents unbounded webhook input from exhausting receiver memory.
    @Test
    void aBodyAboveTheBoundIsRefusedAfterReadingOnlyOneExtraByte() {
        var input = stream("x".repeat(100));
        assertThatExceptionOfType(ForgejoException.class)
                .as("a sender cannot make the receiver allocate or read an unlimited body")
                .isThrownBy(
                        () ->
                                new ForgejoWebhook(10)
                                        .read(input, "00".repeat(32), "pull_request", SECRET))
                .withMessageContaining("byte limit");
        assertThat(input.available())
                .as("at most the bound plus one byte is consumed")
                .isEqualTo(89);
    }

    // Prevents altered raw deliveries from passing signature verification.
    @Test
    void aValidSignatureAuthenticatesTheExactRawBytes() {
        var payload =
                new ForgejoWebhook(PULL.length(), Strictness.STRICT)
                        .read(
                                stream(PULL),
                                "41c36dfe8f9a0a91bd87f1af14fd13a490bc6c2a006f46974a08b1d2e87e2cf7",
                                "pull_request",
                                SECRET);
        assertThat(payload)
                .as("an authenticated event is parsed to its specific record")
                .isInstanceOf(ForgejoWebhook.PullRequestEvent.class);
        assertThat(((ForgejoWebhook.PullRequestEvent) payload).pullRequest().id())
                .as("the delivery identifies the changed review")
                .isEqualTo(7L);
        assertThatExceptionOfType(ForgejoException.class)
                .as("even whitespace is signed before any JSON normalization")
                .isThrownBy(
                        () ->
                                new ForgejoWebhook(512)
                                        .read(
                                                stream(PULL + " "),
                                                signature(PULL),
                                                "pull_request",
                                                SECRET))
                .withMessage("Invalid webhook signature");
    }

    // Prevents strict verification from overlooking changes in delivery envelopes.
    @Test
    void strictWebhooksRefuseUnknownFieldsWhileLenientWebhooksAcceptThem() {
        String body = PULL.replace("\"action\"", "\"future\":true,\"action\"");
        assertThatExceptionOfType(ForgejoException.class)
                .as("strict deliveries detect changes to the envelope")
                .isThrownBy(
                        () ->
                                new ForgejoWebhook(512, Strictness.STRICT)
                                        .read(
                                                stream(body),
                                                signature(body),
                                                "pull_request",
                                                SECRET))
                .withMessageContaining("future");
        assertThat(
                        new ForgejoWebhook(512)
                                .read(stream(body), signature(body), "pull_request", SECRET))
                .as("default readers tolerate envelope additions")
                .isInstanceOf(ForgejoWebhook.PullRequestEvent.class);
    }

    // Prevents unidentified deliveries from triggering review work.
    @Test
    void missingOrNullReviewIdentityIsRefusedInEitherReading() {
        for (Strictness mode : Strictness.values()) {
            for (String body :
                    new String[] {
                        "{\"action\":\"opened\",\"number\":1}",
                        "{\"action\":\"opened\",\"number\":1,\"pull_request\":null}"
                    }) {
                assertThatExceptionOfType(ForgejoException.class)
                        .as("an authenticated but unidentified delivery cannot trigger review work")
                        .isThrownBy(
                                () ->
                                        new ForgejoWebhook(512, mode)
                                                .read(
                                                        stream(body),
                                                        signature(body),
                                                        "pull_request",
                                                        SECRET));
            }
        }
    }

    static String signature(String body) {
        return HexFormat.of()
                .formatHex(ForgejoWebhook.hmac(body.getBytes(StandardCharsets.UTF_8), SECRET));
    }

    private static ByteArrayInputStream stream(String body) {
        return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8));
    }
}
