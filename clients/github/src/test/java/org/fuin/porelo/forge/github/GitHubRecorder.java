package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/** Manual-only recorder; raw live responses and credentials never reach a file. */
public final class GitHubRecorder {
    private static final ObjectMapper JSON = new ObjectMapper();
    static final Pattern CREDENTIAL =
            Pattern.compile(
                    "gh[pousr]_[A-Za-z0-9_]+|github_pat_[A-Za-z0-9_]+|"
                            + "eyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+|"
                            + "-----BEGIN (?:RSA )?PRIVATE KEY-----");

    private GitHubRecorder() {}

    /** Records into the one directory argument; credentials come only from the environment. */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected a recordings directory");
        Path directory = checkedDirectory(Path.of(args[0]).toAbsolutePath().normalize());
        try (var http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();
                var fixture = GitHubFixture.environment(http)) {
            fixture.prepare();
            var captures = new GitHubScenario(fixture, http).run();
            String user = fixture.api("GET", "user", null).path("login").asText();
            // A review can be attributed to another account; discover it without persisting tokens.
            var review =
                    captures.stream()
                            .filter(capture -> capture.operation().equals("createReview"))
                            .findFirst()
                            .orElseThrow();
            String reviewer =
                    JSON.readTree(review.recording().path("body").asText())
                            .path("user")
                            .path("login")
                            .asText();
            if (user.isBlank() || reviewer.isBlank() || user.equals(reviewer))
                throw new IllegalStateException("Recording requires two distinct test accounts");
            var scrubber =
                    new RecordingScrubber(
                            fixture.secrets(),
                            Map.of(
                                    GitHubFixture.OWNER,
                                    "fixture-org",
                                    GitHubFixture.REPO,
                                    "fixture-repo",
                                    user,
                                    "fixture-user",
                                    reviewer,
                                    "fixture-reviewer"));
            writeAll(directory, captures, scrubber);
        }
    }

    /**
     * Checks the existing part of the path is real before anything is created, then creates the
     * directory.
     */
    static Path checkedDirectory(Path directory) throws IOException {
        Path existing = directory;
        while (existing != null && !Files.exists(existing, LinkOption.NOFOLLOW_LINKS))
            existing = existing.getParent();
        if (existing == null || !existing.equals(existing.toRealPath()))
            throw new IllegalArgumentException("Recordings path must not contain symlinks");
        Files.createDirectories(directory);
        if (!directory.equals(directory.toRealPath()))
            throw new IllegalArgumentException("Recordings path must not contain symlinks");
        return directory;
    }

    /**
     * Scrubs and checks every capture in memory first; a refusal leaves the directory as it was.
     */
    static void writeAll(
            Path directory, List<GitHubScenario.Capture> captures, RecordingScrubber scrubber)
            throws Exception {
        Map<Path, String> outputs = new LinkedHashMap<>();
        List<JsonNode> scrubbed =
                scrubber.scrubAll(
                        captures.stream().map(capture -> (JsonNode) capture.recording()).toList());
        for (int i = 0; i < captures.size(); i++)
            if (outputs.put(
                            directory.resolve(captures.get(i).operation() + ".json"),
                            render(scrubbed.get(i)))
                    != null)
                throw new IllegalStateException("Two captures for " + captures.get(i).operation());
        for (var output : outputs.entrySet()) writeText(output.getKey(), output.getValue());
    }

    static String render(JsonNode scrubbed) throws Exception {
        String output = JSON.writerWithDefaultPrettyPrinter().writeValueAsString(scrubbed) + "\n";
        if (CREDENTIAL.matcher(output).find())
            throw new IllegalStateException("Refusing a credential-shaped recording");
        return output;
    }

    static void write(Path path, JsonNode scrubbed) throws Exception {
        writeText(path, render(scrubbed));
    }

    private static void writeText(Path path, String output) throws Exception {
        Path temporary = Files.createTempFile(path.getParent(), "scrubbed-", ".json");
        try {
            Files.writeString(temporary, output, StandardCharsets.UTF_8);
            Files.move(
                    temporary,
                    path,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
