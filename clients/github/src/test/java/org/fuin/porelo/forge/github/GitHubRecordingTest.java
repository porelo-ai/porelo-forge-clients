package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class GitHubRecordingTest {
    private static final ObjectMapper JSON = GitHubJson.mapper(Strictness.STRICT);
    static final URI BASE = URI.create("https://example.org/");
    static final String TOKEN = "synthetic-access-token";

    // Prevents API drift, incorrect targets and lost write bodies from passing offline replay.
    @ParameterizedTest(name = "{0} reads its GitHub example recording")
    @EnumSource(GitHubOperations.class)
    void everyOperationReadsItsRecordingStrictly(GitHubOperations operation) throws Exception {
        JsonNode recording = recording(operation.name());
        var context = JSON.treeToValue(recording.path("context"), GitHubOperations.Context.class);
        var http = new ReplayHttpClient().recording(operation.name());
        Object result =
                operation.invoke(
                        new GitHubClient(BASE, http, Strictness.STRICT),
                        new GitHubGraphQl(BASE, http, Strictness.STRICT),
                        TOKEN,
                        context,
                        recording.path("request_body"));
        GitHubScenario.requireUsefulResult(operation, result);
        var request = http.requests.getFirst();
        assertThat(request.method())
                .as("%s uses its documented verb", operation)
                .isEqualTo(operation.method);
        assertThat(request.uri().getRawPath())
                .as("%s addresses its fixture", operation)
                .isEqualTo(operation.path(context));
        assertThat(request.uri().getRawQuery())
                .as("%s preserves filters and pagination", operation)
                .isEqualTo(operation.query(context));
        assertThat(request.uri())
                .as("%s sends the captured relative target", operation)
                .isEqualTo(BASE.resolve(recording.path("request_uri").asText()));
        assertThat(request.headers().firstValue("Authorization"))
                .as("%s authenticates only to the API", operation)
                .contains("Bearer " + TOKEN);
        assertThat(request.headers().firstValue("Accept"))
                .as("%s requests GitHub JSON", operation)
                .contains("application/vnd.github+json");
        assertThat(request.headers().firstValue("X-GitHub-Api-Version"))
                .as("%s pins the API contract", operation)
                .contains("2026-03-10");
        if (operation.method.equals("GET") || operation.method.equals("DELETE")) {
            assertThat(request.bodyPublisher().orElseThrow().contentLength())
                    .as("reads send no mutation body")
                    .isZero();
        } else {
            JsonNode sent = JSON.readTree(requestBody(request));
            if (operation.path(context).equals("/graphql")) {
                assertThat(sent.path("variables"))
                        .as("GraphQL sends variables separately from its query")
                        .isEqualTo(recording.path("request_body").path("variables"));
                String query =
                        switch (operation) {
                            case reviewThreads -> GitHubGraphQl.REVIEW_THREADS;
                            case reviewThread -> GitHubGraphQl.REVIEW_THREAD;
                            case threadComments -> GitHubGraphQl.THREAD_COMMENTS;
                            case resolveReviewThread -> GitHubGraphQl.RESOLVE_REVIEW_THREAD;
                            case unresolveReviewThread -> GitHubGraphQl.UNRESOLVE_REVIEW_THREAD;
                            default -> throw new AssertionError("Unexpected GraphQL operation");
                        };
                assertThat(sent.path("query").asText())
                        .as("the requested GraphQL operation is sent")
                        .isEqualTo(query);
            } else {
                // Fields the model adds beyond the recorded input, such as defaults, are not
                // compared.
                for (var field : recording.path("request_body").properties()) {
                    assertThat(sent.path(field.getKey()))
                            .as("%s retains request field %s", operation, field.getKey())
                            .isEqualTo(field.getValue());
                }
            }
        }
        assertThat(http.requests)
                .as("only the documented log redirect needs another exchange")
                .hasSize(operation == GitHubOperations.jobLogs ? 2 : 1);
    }

    static JsonNode recording(String name) throws Exception {
        try (var stream =
                GitHubRecordingTest.class.getResourceAsStream("/recordings/" + name + ".json")) {
            if (stream == null) throw new IllegalStateException("Missing recording " + name);
            return JSON.readTree(stream);
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
}
