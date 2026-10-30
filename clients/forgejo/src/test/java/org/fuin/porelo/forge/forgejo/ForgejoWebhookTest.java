package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class ForgejoWebhookTest {
    private static final byte[] SECRET = "public-fixture-key".getBytes(StandardCharsets.UTF_8);
    private static final String PULL =
            "{\"action\":\"opened\",\"number\":1,\"pull_request\":{\"id\":7}}";

    @Test
    void anInvalidSignatureIsRefusedBeforeMalformedJsonIsParsed() {
        assertThatThrownBy(
                        () ->
                                new ForgejoWebhook(256)
                                        .read(
                                                stream("not json"),
                                                "00".repeat(32),
                                                "pull_request",
                                                SECRET))
                .as("untrusted input must never reach payload parsing")
                .isInstanceOf(ForgejoException.class)
                .hasMessage("Invalid webhook signature");
    }

    @Test
    void aBodyAboveTheBoundIsRefusedAfterReadingOnlyOneExtraByte() {
        var input = stream("x".repeat(100));
        assertThatThrownBy(
                        () ->
                                new ForgejoWebhook(10)
                                        .read(input, "00".repeat(32), "pull_request", SECRET))
                .as("a sender cannot make the receiver allocate or read an unlimited body")
                .hasMessageContaining("byte limit");
        assertThat(input.available())
                .as("at most the bound plus one byte is consumed")
                .isEqualTo(89);
    }

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
        assertThatThrownBy(
                        () ->
                                new ForgejoWebhook(512)
                                        .read(
                                                stream(PULL + " "),
                                                signature(PULL),
                                                "pull_request",
                                                SECRET))
                .as("even whitespace is signed before any JSON normalization")
                .hasMessage("Invalid webhook signature");
    }

    @Test
    void strictWebhooksRefuseUnknownFieldsWhileLenientWebhooksAcceptThem() {
        String body = PULL.replace("\"action\"", "\"future\":true,\"action\"");
        assertThatThrownBy(
                        () ->
                                new ForgejoWebhook(512, Strictness.STRICT)
                                        .read(
                                                stream(body),
                                                signature(body),
                                                "pull_request",
                                                SECRET))
                .as("strict deliveries detect changes to the envelope")
                .hasMessageContaining("future");
        assertThat(
                        new ForgejoWebhook(512)
                                .read(stream(body), signature(body), "pull_request", SECRET))
                .as("default readers tolerate envelope additions")
                .isInstanceOf(ForgejoWebhook.PullRequestEvent.class);
    }

    @Test
    void missingOrNullReviewIdentityIsRefusedInEitherReading() {
        for (Strictness mode : Strictness.values()) {
            for (String body :
                    new String[] {
                        "{\"action\":\"opened\",\"number\":1}",
                        "{\"action\":\"opened\",\"number\":1,\"pull_request\":null}"
                    }) {
                assertThatThrownBy(
                                () ->
                                        new ForgejoWebhook(512, mode)
                                                .read(
                                                        stream(body),
                                                        signature(body),
                                                        "pull_request",
                                                        SECRET))
                        .as("an authenticated but unidentified delivery cannot trigger review work")
                        .isInstanceOf(ForgejoException.class);
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
