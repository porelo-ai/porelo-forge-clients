package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import java.net.http.HttpHeaders;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * What GitHub itself said in a failed answer: its request id, its message and each error detail,
 * every text scrubbed of the request's credentials.
 */
record GitHubWords(@Nullable String requestId, @Nullable String message, List<String> details) {
    /** No answer, or one that said nothing of its own. */
    static final GitHubWords NONE = new GitHubWords(null, null, List.of());

    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9:-]{1,64}");

    /** Keeps the details from changing. */
    GitHubWords {
        details = List.copyOf(details);
    }

    /** Only the answer's request id, for an answer whose words are the client's own. */
    static GitHubWords requestIdOf(HttpHeaders headers, List<String> credentials) {
        return new GitHubWords(requestId(headers, credentials), null, List.of());
    }

    /** A REST answer's request id, top-level {@code message} and {@code errors[]} entries. */
    static GitHubWords rest(
            HttpHeaders headers, @Nullable JsonNode body, List<String> credentials) {
        String id = requestId(headers, credentials);
        if (body == null) return new GitHubWords(id, null, List.of());
        JsonNode message = body.path("message");
        List<String> details = new ArrayList<>();
        for (JsonNode error : body.path("errors")) {
            String detail = restDetail(error);
            if (detail != null) details.add(GitHubJson.safe(detail, credentials));
        }
        return new GitHubWords(
                id, said(message) ? GitHubJson.safe(message.asText(), credentials) : null, details);
    }

    /** A GraphQL answer's request id, the named error's message and every error. */
    static GitHubWords graphQl(
            @Nullable String requestId, JsonNode named, JsonNode errors, List<String> credentials) {
        JsonNode message = named.path("message");
        List<String> details = new ArrayList<>();
        for (JsonNode error : errors) {
            String detail = graphQlDetail(error);
            if (detail != null) details.add(GitHubJson.safe(detail, credentials));
        }
        return new GitHubWords(
                requestId,
                said(message) ? GitHubJson.safe(message.asText(), credentials) : null,
                details);
    }

    /**
     * The answer's {@code X-GitHub-Request-Id}, kept only when it is a plain token that holds none
     * of the request's credentials.
     */
    static @Nullable String requestId(HttpHeaders headers, List<String> credentials) {
        return headers.firstValue("x-github-request-id")
                .filter(value -> REQUEST_ID.matcher(value).matches())
                .filter(
                        value ->
                                credentials.stream()
                                        .noneMatch(c -> !c.isEmpty() && value.contains(c)))
                .orElse(null);
    }

    /** Whether GitHub gave a message of its own: a blank one says nothing. */
    private static boolean said(JsonNode message) {
        return message.isTextual() && !message.asText().isBlank();
    }

    private static @Nullable String restDetail(JsonNode error) {
        if (error.isTextual()) return error.asText();
        if (error.path("message").isTextual()) return error.path("message").asText();
        List<String> parts = new ArrayList<>();
        for (String name : List.of("resource", "field", "code")) {
            if (error.path(name).isTextual()) parts.add(name + "=" + error.path(name).asText());
        }
        return parts.isEmpty() ? null : String.join(", ", parts);
    }

    private static @Nullable String graphQlDetail(JsonNode error) {
        if (!error.path("message").isTextual()) return null;
        List<String> where = new ArrayList<>();
        if (error.path("type").isTextual()) where.add("type " + error.path("type").asText());
        if (error.path("path").isArray() && !error.path("path").isEmpty()) {
            List<String> path = new ArrayList<>();
            error.path("path").forEach(step -> path.add(step.asText()));
            where.add("path " + String.join(".", path));
        }
        String message = error.path("message").asText();
        return where.isEmpty() ? message : message + " (" + String.join(", ", where) + ")";
    }
}
