package org.fuin.porelo.forge.forgejo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class ForgejoRecordingTest {
    private static final ObjectMapper JSON = ForgejoJson.mapper(Strictness.STRICT);

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
        }
    }

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
            assertThatThrownBy(
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
                    .as("recorded API drift must fail with the changed field name")
                    .hasMessageContaining("renamed_commit_sha");
        }
    }
}
