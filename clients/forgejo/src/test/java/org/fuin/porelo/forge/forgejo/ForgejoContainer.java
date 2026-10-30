package org.fuin.porelo.forge.forgejo;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/** Owns the isolated live fixture, its subprocesses, tokens and cleanup. */
final class ForgejoContainer implements AutoCloseable {
    static final String SERVER_IMAGE =
            "codeberg.org/forgejo/forgejo@sha256:fcd19ff4ebc85fb098a67e8e1d0d5a84d07dd6e8a23edcfdfd5b71a6178953be";
    // Supply the reviewed tag and digest before running either live entry point.
    static final String RUNNER_IMAGE =
            "code.forgejo.org/forgejo/runner:13.2.0@sha256:ca3d5eea46004789a175d1369eec6829d3ea9bfbe2011a06625b4bdaa55f7552";
    static final String OWNER = "review-org";
    static final String REPO = "review-fixture";
    static final String ADMIN = "fixture-admin";
    static final String REVIEWER = "fixture-reviewer";
    private static final String PREFIX = "forge-clients-it-";
    private static final SecureRandom RANDOM = new SecureRandom();
    private final String name = PREFIX + UUID.randomUUID();
    private final String runner = name + "-runner";
    private final HttpClient transport =
            HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .connectTimeout(Duration.ofSeconds(5))
                    .build();
    private final ObjectMapper json = ForgejoJson.mapper(Strictness.LENIENT);
    private final List<String> secrets = new ArrayList<>();
    private final Thread shutdown = new Thread(this::cleanupOnShutdown, "forgejo-fixture-cleanup");
    private final byte[] webhookSecret = randomSecret().getBytes(StandardCharsets.UTF_8);
    private WebhookReceiver receiver;
    private URI base;
    private String adminToken;
    private String reviewerToken;
    private boolean serverOwned;
    private boolean volumeOwned;
    private boolean runnerOwned;
    private boolean closed;

    static ForgejoContainer start() throws IOException {
        if (!RUNNER_IMAGE.matches(
                "code\\.forgejo\\.org/forgejo/runner:[A-Za-z0-9_.-]+@sha256:[a-f0-9]{64}")) {
            throw new IllegalStateException(
                    "Pin ForgejoContainer.RUNNER_IMAGE to a reviewed tag and digest before a live run");
        }
        ForgejoContainer fixture = new ForgejoContainer();
        try {
            fixture.launch();
            return fixture;
        } catch (RuntimeException | IOException ex) {
            try {
                fixture.close();
            } catch (RuntimeException cleanup) {
                cleanup.addSuppressed(ex);
                throw cleanup;
            }
            throw ex;
        }
    }

    private void launch() throws IOException {
        refuseLeftovers("ps", "-a", "--format", "{{.Names}}");
        refuseLeftovers("volume", "ls", "--format", "{{.Name}}");
        Runtime.getRuntime().addShutdownHook(shutdown);
        secrets.add(new String(webhookSecret, StandardCharsets.UTF_8));
        receiver = new WebhookReceiver(webhookSecret);
        String volume = Podman.run("Create fixture volume", "", "volume", "create", name).strip();
        if (!volume.equals(name)) {
            throw new IllegalStateException("Volume creation returned an unexpected name");
        }
        volumeOwned = true;
        serverOwned = true;
        Podman.run(
                "Start Forgejo",
                "",
                "run",
                "--log-driver=none",
                "-d",
                "--name",
                name,
                "--network",
                "slirp4netns:allow_host_loopback=true",
                "-p",
                "127.0.0.1::3000",
                "-v",
                name + ":/data",
                "-e",
                "FORGEJO__security__INSTALL_LOCK=true",
                "-e",
                "FORGEJO__database__DB_TYPE=sqlite3",
                "-e",
                "FORGEJO__actions__ENABLED=true",
                "-e",
                "FORGEJO__webhook__ALLOWED_HOST_LIST=*",
                SERVER_IMAGE);
        String mapping = Podman.run("Read Forgejo port", "", "port", name, "3000/tcp").strip();
        if (!mapping.matches("127\\.0\\.0\\.1:[0-9]{1,5}")) {
            throw new IllegalStateException("Expected one loopback Forgejo port mapping");
        }
        int port = Integer.parseInt(mapping.substring(mapping.lastIndexOf(':') + 1));
        if (port < 1 || port > 65535) {
            throw new IllegalStateException("Invalid Forgejo port mapping");
        }
        base = URI.create("http://127.0.0.1:" + port + "/api/v1/");
        waitForServer();
        String output =
                Podman.run(
                        "Create fixture administrator",
                        "",
                        "exec",
                        "-u",
                        "git",
                        name,
                        "forgejo",
                        "admin",
                        "user",
                        "create",
                        "--username",
                        ADMIN,
                        "--email",
                        ADMIN + "@example.org",
                        "--admin",
                        "--random-password",
                        "--must-change-password=false",
                        "--access-token",
                        "--access-token-scopes",
                        "all");
        var token =
                Pattern.compile(
                                "(?m)^Access token was successfully created[^\\r\\n]*?([a-fA-F0-9]{40})\\s*$")
                        .matcher(output);
        if (!token.find()) {
            throw new IllegalStateException(
                    "Administrator creation did not return the expected token line");
        }
        adminToken = token.group(1);
        secrets.add(adminToken);
        seedAccountsAndRepository();
        startRunner();
    }

    private void refuseLeftovers(String... command) {
        String output = Podman.run("Inspect fixture leftovers", "", command);
        var leftovers = output.lines().filter(value -> value.startsWith(PREFIX)).toList();
        if (!leftovers.isEmpty()) {
            if (leftovers.stream()
                    .anyMatch(value -> !value.matches("forge-clients-it-[A-Za-z0-9-]+"))) {
                throw new IllegalStateException("An invalid fixture resource name was returned");
            }
            throw new IllegalStateException(
                    "Refusing to start over fixture leftovers: " + String.join(", ", leftovers));
        }
    }

    private void waitForServer() {
        for (int attempt = 0; attempt < 90; attempt++) {
            try {
                var response =
                        transport.send(
                                HttpRequest.newBuilder(base.resolve("version"))
                                        .timeout(Duration.ofSeconds(2))
                                        .GET()
                                        .build(),
                                HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200
                        && json.readTree(response.body())
                                .path("version")
                                .asText()
                                .startsWith("17.")) {
                    return;
                }
                if (response.statusCode() == 200) {
                    throw new IllegalStateException(
                            "The fixture must serve Forgejo major version 17");
                }
            } catch (IOException ex) {
                // A freshly started server may not accept connections yet.
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Forgejo startup interrupted");
            }
            pause();
        }
        throw new IllegalStateException(
                "Forgejo did not serve version 17 before the startup deadline");
    }

    private void seedAccountsAndRepository() throws IOException {
        api("POST", "orgs", Map.of("username", OWNER, "full_name", "Review fixture"));
        api(
                "POST",
                "orgs/" + OWNER + "/repos",
                Map.of("name", REPO, "auto_init", true, "default_branch", "main"));
        api("PATCH", repoPath(), Map.of("has_actions", true));
        String password = randomSecret();
        secrets.add(password);
        api(
                "POST",
                "admin/users",
                Map.of(
                        "username",
                        REVIEWER,
                        "email",
                        REVIEWER + "@example.org",
                        "password",
                        password,
                        "must_change_password",
                        false));
        String basic =
                Base64.getEncoder()
                        .encodeToString(
                                (REVIEWER + ":" + password).getBytes(StandardCharsets.UTF_8));
        secrets.add(basic);
        reviewerToken =
                request(
                                "POST",
                                "users/" + REVIEWER + "/tokens",
                                Map.of("name", "fixture", "scopes", List.of("all")),
                                "Basic " + basic)
                        .path("sha1")
                        .asText();
        if (!reviewerToken.matches("[a-fA-F0-9]{40}")) {
            throw new IllegalStateException("Reviewer token response did not contain a token");
        }
        secrets.add(reviewerToken);
        api("PUT", repoPath() + "/collaborators/" + REVIEWER, Map.of("permission", "write"));
        api(
                "POST",
                repoPath() + "/branches",
                Map.of("new_branch_name", "feature", "old_branch_name", "main"));
        content("feature", "review.txt", "Review this line.\n");
    }

    private void startRunner() throws IOException {
        // An offline registration: the fixture makes the shared secret; the server reads it from
        // stdin, and the runner
        // from its runner file inside its own container, which is removed with it. Neither is on a
        // command line.
        byte[] random = new byte[20];
        RANDOM.nextBytes(random);
        String secret = HexFormat.of().formatHex(random);
        secrets.add(secret);
        Podman.run(
                "Register fixture runner",
                secret,
                "exec",
                "-i",
                "-u",
                "git",
                name,
                "forgejo",
                "forgejo-cli",
                "actions",
                "register",
                "--secret-stdin",
                "--name",
                runner,
                "--labels",
                "host");
        // Forgejo takes the runner's id from the secret's first sixteen characters as bytes; its
        // token is the whole secret.
        String hex =
                HexFormat.of()
                        .formatHex(secret.substring(0, 16).getBytes(StandardCharsets.US_ASCII));
        String uuid =
                hex.substring(0, 8)
                        + "-"
                        + hex.substring(8, 12)
                        + "-"
                        + hex.substring(12, 16)
                        + "-"
                        + hex.substring(16, 20)
                        + "-"
                        + hex.substring(20);
        var identity =
                json.createObjectNode()
                        .put("id", 0)
                        .put("uuid", uuid)
                        .put("name", runner)
                        .put("token", secret)
                        .put("address", "http://127.0.0.1:3000");
        identity.putArray("labels").add("host:host");
        runnerOwned = true;
        Podman.run(
                "Create fixture runner",
                "",
                "create",
                "--log-driver=none",
                "--name",
                runner,
                "--network",
                "container:" + name,
                "--entrypoint",
                "/bin/sh",
                RUNNER_IMAGE,
                "-ec",
                "mkdir -p /tmp/fixture && cd /tmp/fixture && while [ ! -s .runner ]; do sleep 0.2; done;"
                        + " exec forgejo-runner daemon");
        Podman.run("Start fixture runner", "", "start", runner);
        Podman.run(
                "Hand the fixture runner its identity",
                json.writeValueAsString(identity),
                "exec",
                "-i",
                runner,
                "/bin/sh",
                "-ec",
                "mkdir -p /tmp/fixture && cat > /tmp/fixture/.runner.part && mv /tmp/fixture/.runner.part"
                        + " /tmp/fixture/.runner");
    }

    JsonNode api(String method, String path, Object body) {
        return request(method, path, body, "token " + adminToken);
    }

    private JsonNode request(String method, String path, Object body, String authorization) {
        try {
            var builder =
                    HttpRequest.newBuilder(base.resolve(path))
                            .timeout(Duration.ofSeconds(30))
                            .header("Authorization", authorization)
                            .header("Content-Type", "application/json");
            var publisher =
                    body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body));
            var response =
                    transport.send(
                            builder.method(method, publisher).build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException(
                        "Fixture API " + method + " failed with status " + response.statusCode());
            }
            return response.body().isBlank()
                    ? json.createObjectNode()
                    : json.readTree(response.body());
        } catch (IOException ex) {
            throw new IllegalStateException("Fixture API could not exchange JSON");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture API interrupted");
        }
    }

    void content(String branch, String path, String content) {
        api(
                "POST",
                repoPath() + "/contents/" + path,
                Map.of(
                        "branch",
                        branch,
                        "message",
                        "Add live fixture",
                        "content",
                        Base64.getEncoder()
                                .encodeToString(content.getBytes(StandardCharsets.UTF_8))));
    }

    void workflow() {
        content(
                "main",
                ".forgejo/workflows/fixture.yml",
                "name: fixture\non: [push]\njobs:\n  build:\n    runs-on: host\n    steps:\n      - run: echo fixture-complete\n");
    }

    JsonNode waitForJobs() {
        for (int attempt = 0; attempt < 120; attempt++) {
            if (!Podman.run(
                            "Inspect fixture runner",
                            "",
                            "inspect",
                            "-f",
                            "{{.State.Running}}",
                            runner)
                    .strip()
                    .equals("true")) {
                throw new IllegalStateException(
                        "The fixture runner exited before the job completed");
            }
            JsonNode runs = api("GET", repoPath() + "/actions/runs", null).path("workflow_runs");
            if (!runs.isEmpty()) {
                long runId = runs.get(0).path("id").asLong();
                JsonNode jobs = api("GET", repoPath() + "/actions/runs/" + runId + "/jobs", null);
                if (!jobs.isArray()) {
                    throw new IllegalStateException(
                            "Expected the Forgejo job endpoint to return an array");
                }
                if (!jobs.isEmpty() && jobs.get(0).path("status").asText().equals("success")) {
                    ObjectNode result = json.createObjectNode();
                    result.put("run", runId);
                    result.put("job", jobs.get(0).path("id").asLong());
                    result.put("sha", runs.get(0).path("commit_sha").asText());
                    return result;
                }
                if (!jobs.isEmpty()
                        && List.of("failure", "cancelled", "skipped")
                                .contains(jobs.get(0).path("status").asText())) {
                    throw new IllegalStateException("The fixture job terminated without success");
                }
            }
            pause();
        }
        throw new IllegalStateException(
                "The fixture Actions job did not complete before its deadline");
    }

    List<WebhookReceiver.Delivery> deliveries() {
        return receiver.deliveries();
    }

    URI base() {
        return base;
    }

    HttpClient transport() {
        return transport;
    }

    String adminToken() {
        return adminToken;
    }

    String reviewerToken() {
        return reviewerToken;
    }

    String hookUrl() {
        return receiver.urlFromContainer();
    }

    String hookSecret() {
        return new String(webhookSecret, StandardCharsets.UTF_8);
    }

    List<String> secrets() {
        return List.copyOf(secrets);
    }

    static String repoPath() {
        return "repos/" + OWNER + "/" + REPO;
    }

    private static String randomSecret() {
        byte[] value = new byte[32];
        new SecureRandom().nextBytes(value);
        return HexFormat.of().formatHex(value);
    }

    static void pause() {
        try {
            Thread.sleep(1000);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Fixture wait interrupted");
        }
    }

    private void cleanupOnShutdown() {
        try {
            close();
        } catch (RuntimeException ex) {
            System.err.println(ex.getMessage());
            Runtime.getRuntime().halt(1);
        }
    }

    /**
     * Removes only this run's containers and volume, reporting any resources it could not remove.
     */
    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        boolean interrupted = Thread.interrupted();
        List<String> failed = new ArrayList<>();
        if (runnerOwned) {
            remove(failed, runner, "rm", "-f", runner);
        }
        if (serverOwned) {
            remove(failed, name, "rm", "-f", name);
        }
        if (volumeOwned) {
            remove(failed, name, "volume", "rm", name);
        }
        if (receiver != null) {
            receiver.close();
        }
        transport.close();
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
        if (!failed.isEmpty()) {
            throw new IllegalStateException(
                    "Could not remove owned fixture resources: " + String.join(", ", failed));
        }
        closed = true;
        if (Thread.currentThread() != shutdown) {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        }
    }

    private void remove(List<String> failed, String resource, String... command) {
        try {
            Podman.run("Remove owned fixture resource", "", command);
            String kind = command[0].equals("volume") ? "volume" : "container";
            String remaining =
                    kind.equals("volume")
                            ? Podman.run(
                                    "Observe volume removal",
                                    "",
                                    "volume",
                                    "ls",
                                    "--format",
                                    "{{.Name}}")
                            : Podman.run(
                                    "Observe container removal",
                                    "",
                                    "ps",
                                    "-a",
                                    "--format",
                                    "{{.Names}}");
            if (remaining.lines().anyMatch(resource::equals)) {
                failed.add(resource);
            }
        } catch (RuntimeException ex) {
            failed.add(resource);
        }
    }
}
