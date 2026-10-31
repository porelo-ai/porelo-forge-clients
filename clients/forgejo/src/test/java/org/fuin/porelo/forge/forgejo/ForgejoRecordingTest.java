package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForgejoRecordingTest {
    private static final ObjectMapper JSON = ForgejoJson.mapper(Strictness.STRICT);

    // Prevents API drift and incorrect request targets from passing replay checks.
    @ParameterizedTest(name = "{0} reads its real Forgejo recording")
    @EnumSource(ForgejoOperations.class)
    void everyOperationReadsItsRecordingStrictly(ForgejoOperations operation) throws Exception {
        try (var stream =
                getClass().getResourceAsStream("/recordings/" + operation.name() + ".json")) {
            assertThat(stream)
                    .as(
                            "every operation has its recording, made by ForgejoRecorder: %s",
                            operation.name())
                    .isNotNull();
            JsonNode recording = JSON.readTree(stream);
            var context =
                    JSON.treeToValue(recording.path("context"), ForgejoOperations.Context.class);
            var transport = new ReplayHttpClient().recording(operation.name());
            var client =
                    new ForgejoClient(
                            URI.create("http://example.org/api/v1/"), transport, Strictness.STRICT);
            Object result =
                    operation.invoke(
                            client,
                            "synthetic-access-token",
                            context,
                            recording.path("request_body"));
            ForgejoScenario.requireUsefulResult(operation, result);
            assertThat(transport.requests.getFirst().method())
                    .as("%s uses the recorded API operation", operation)
                    .isEqualTo(operation.method);
            var sent = transport.requests.getFirst();
            if (recording.path("request_body").isNull()
                    || recording.path("request_body").isMissingNode()) {
                assertThat(sent.bodyPublisher().map(body -> body.contentLength()).orElse(0L))
                        .as("%s sends no body", operation)
                        .isZero();
            } else {
                requireSent(
                        operation,
                        "",
                        recording.path("request_body"),
                        JSON.readTree(requestBody(sent)));
            }
            assertThat(transport.requests.getFirst().uri().getRawPath())
                    .as("%s addresses the seeded resource", operation)
                    .isEqualTo(operation.path(context));
            String expectedQuery =
                    switch (operation) {
                        case pulls -> "state=open&page=1&limit=50";
                        case issueComments -> "since=" + context.since().replace(":", "%3A");
                        case runs -> "head_sha=" + context.actionsSha() + "&page=1&limit=50";
                        case reviews, statuses, hooks, organizations, keys, ownKeys ->
                                "page=1&limit=50";
                        default -> null;
                    };
            assertThat(transport.requests.getFirst().uri().getRawQuery())
                    .as("%s retains its filters and pagination for the recorded context", operation)
                    .isEqualTo(expectedQuery);
            assertThat(recording.path("request_uri").isTextual())
                    .as("%s was recorded with the request the live run sent", operation)
                    .isTrue();
            URI recordedUri = URI.create(recording.path("request_uri").asText());
            assertThat(transport.requests.getFirst().uri().getRawPath())
                    .as("%s addresses what the live run addressed", operation)
                    .isEqualTo(recordedUri.getRawPath());
            assertThat(transport.requests.getFirst().uri().getRawQuery())
                    .as("%s sends the exact query captured during the live run", operation)
                    .isEqualTo(recordedUri.getRawQuery());
        }
    }

    /**
     * Requires every recorded value at its place in what was sent; the records also send their
     * empty defaults, which Forgejo took live.
     */
    private static void requireSent(
            ForgejoOperations operation, String at, JsonNode recorded, JsonNode sent) {
        if (recorded.isObject()) {
            assertThat(sent.isObject()).as("%s sends %s as an object", operation, at).isTrue();
            sent.properties().stream()
                    .filter(field -> !recorded.has(field.getKey()))
                    .forEach(
                            field ->
                                    assertThat(
                                                    field.getValue().isNull()
                                                            || (field.getValue().isContainerNode()
                                                                    && field.getValue().isEmpty()))
                                            .as(
                                                    "%s sends only an empty default for %s.%s",
                                                    operation, at, field.getKey())
                                            .isTrue());
            recorded.properties()
                    .forEach(
                            field ->
                                    requireSent(
                                            operation,
                                            at + "." + field.getKey(),
                                            field.getValue(),
                                            sent.path(field.getKey())));
        } else if (recorded.isArray()) {
            assertThat(sent.isArray()).as("%s sends %s as a list", operation, at).isTrue();
            assertThat(sent.size())
                    .as("%s sends %s whole", operation, at)
                    .isEqualTo(recorded.size());
            for (int i = 0; i < recorded.size(); i++)
                requireSent(operation, at + "[" + i + "]", recorded.get(i), sent.path(i));
        } else {
            assertThat(sent).as("%s sends %s", operation, at).isEqualTo(recorded);
        }
    }

    static String requestBody(java.net.http.HttpRequest request) {
        var subscriber =
                java.net.http.HttpResponse.BodySubscribers.ofString(
                        java.nio.charset.StandardCharsets.UTF_8);
        request.bodyPublisher()
                .orElseThrow()
                .subscribe(
                        new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                            public void onSubscribe(
                                    java.util.concurrent.Flow.Subscription subscription) {
                                subscriber.onSubscribe(subscription);
                            }

                            public void onNext(java.nio.ByteBuffer buffer) {
                                subscriber.onNext(java.util.List.of(buffer));
                            }

                            public void onError(Throwable error) {
                                subscriber.onError(error);
                            }

                            public void onComplete() {
                                subscriber.onComplete();
                            }
                        });
        return subscriber.getBody().toCompletableFuture().join();
    }

    // Prevents renamed recorded fields from silently losing build identity.
    @org.junit.jupiter.api.Test
    void renamingAFieldInTheRecordedRunFailsByName() throws Exception {
        try (var stream = getClass().getResourceAsStream("/recordings/runs.json")) {
            assertThat(stream).as("the run recording exists, made by ForgejoRecorder").isNotNull();
            String body = JSON.readTree(stream).path("body").asText();
            assertThat(body)
                    .as("the mutation must change a real field of the recorded run")
                    .contains("\"commit_sha\"");
            var transport =
                    new ReplayHttpClient()
                            .answer(
                                    200,
                                    java.util.Map.of(),
                                    body.replace("\"commit_sha\"", "\"renamed_commit_sha\""));
            assertThatExceptionOfType(ForgejoException.class)
                    .as("recorded API drift must fail with the changed field name")
                    .isThrownBy(
                            () ->
                                    new ForgejoClient(
                                                    URI.create("http://example.org/api/v1/"),
                                                    transport,
                                                    Strictness.STRICT)
                                            .runs(
                                                    "synthetic-access-token",
                                                    "owner",
                                                    "repo",
                                                    "abc",
                                                    1,
                                                    10))
                    .withMessageContaining("renamed_commit_sha");
        }
    }
}
