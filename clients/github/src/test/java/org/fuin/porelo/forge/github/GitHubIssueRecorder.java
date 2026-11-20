package org.fuin.porelo.forge.github;

import java.net.http.HttpClient;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Manual-only recorder of the issue and project calls, with the organization member's token alone;
 * raw live answers and the token never reach a file.
 */
public final class GitHubIssueRecorder {
    private GitHubIssueRecorder() {}

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /**
     * Names every bot a recording holds, such as the project's automation moving a status, a
     * fixture bot; another account it was not told about is said by place and kind, never by name,
     * and the scrubber then refuses it.
     */
    static void bots(
            com.fasterxml.jackson.databind.JsonNode node,
            java.util.Map<String, String> identities,
            String operation,
            String path) {
        if (node.isObject()) {
            String login = node.path("login").asText("");
            if (!login.isEmpty() && !identities.containsKey(login)) {
                String kind = node.path("__typename").asText(node.path("type").asText("?"));
                if (kind.equals("Bot")) identities.put(login, "fixture-bot-" + identities.size());
                else
                    System.out.println(
                            "UNTOLD account in " + operation + " at " + path + " kind=" + kind);
            }
            node.properties()
                    .forEach(
                            f ->
                                    bots(
                                            f.getValue(),
                                            identities,
                                            operation,
                                            path + "/" + f.getKey()));
        } else if (node.isArray()) {
            for (int i = 0; i < node.size(); i++)
                bots(node.get(i), identities, operation, path + "/" + i);
        } else if (node.isTextual() && node.asText().stripLeading().startsWith("{")) {
            try {
                bots(JSON.readTree(node.asText()), identities, operation, path + "(json)");
            } catch (java.io.IOException notJson) {
                // Text that only starts like JSON.
            }
        }
    }

    /** Records into the one directory argument; the token comes only from the environment. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a recordings directory");
        Path directory =
                GitHubRecorder.checkedDirectory(Path.of(args[0]).toAbsolutePath().normalize());
        String token = GitHubFixture.required(System.getenv(), "LIVE_GITHUB_REVIEWER_TOKEN");
        String runId = UUID.randomUUID().toString().substring(0, 8);
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                var scenario = new GitHubIssueScenario(http, token, runId, System.out::println)) {
            String login = scenario.login();
            var captures = scenario.run();
            var identities = GitHubIssueScenario.identities(login);
            for (var capture : captures)
                bots(capture.recording(), identities, capture.operation(), "");
            var scrubber = new RecordingScrubber(List.of(token), identities);
            GitHubRecorder.writeAll(directory, captures, scrubber);
        }
    }
}
