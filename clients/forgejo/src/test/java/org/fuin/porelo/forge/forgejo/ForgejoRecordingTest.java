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
            assertThat(transport.requests.getFirst().uri().getRawPath())
                    .as("%s addresses the seeded resource", operation)
                    .isEqualTo(operation.path(context));
            String expectedQuery =
                    switch (operation) {
                        case pulls -> "state=open&page=1&limit=50";
                        case runs -> "head_sha=" + context.actionsSha() + "&page=1&limit=50";
                        case reviews, statuses, hooks, organizations -> "page=1&limit=50";
                        default -> null;
                    };
            assertThat(transport.requests.getFirst().uri().getRawQuery())
                    .as("%s retains its filters and pagination for the recorded context", operation)
                    .isEqualTo(expectedQuery);
            assertThat(recording.path("request_uri").isTextual())
                    .as("%s was recorded with the request the live run sent", operation)
                    .isTrue();
            URI recordedUri = URI.create(recording.path("request_uri").asText());
            assertThat(transport.requests.getFirst().uri().getRawQuery())
                    .as("%s sends the exact query captured during the live run", operation)
                    .isEqualTo(recordedUri.getRawQuery());
        }
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
