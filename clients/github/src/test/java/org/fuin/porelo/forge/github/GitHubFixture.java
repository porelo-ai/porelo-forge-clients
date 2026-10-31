package org.fuin.porelo.forge.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.IntConsumer;
import org.jspecify.annotations.Nullable;

/** Owns only one run's remote artifacts; creation and shutdown cleanup are serialized. */
final class GitHubFixture implements AutoCloseable {
    static final URI BASE = URI.create("https://api.github.com/");
    static final URI WEB = URI.create("https://github.com/");
    static final String OWNER = "porelo-ai-test";
    static final String REPO = "forge-clients-live";
    static final String ROOT = "repos/" + OWNER + "/" + REPO;
    static final String WORKFLOW = "two-step.yml";

    /** The kept review fixture's label and name, which no run may take as its own prefix. */
    static final String KEPT = "forge-client-live";

    // Long enough for an interrupted call to let go of the fixture, short of a supervisor's kill.
    private static final Duration HOOK_WAIT = Duration.ofSeconds(5);
    // Long enough for a cleanup already running to wait out a cancelled Actions run.
    private static final Duration CLEANUP_WAIT = Duration.ofMinutes(2);
    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String userToken;
    private final String reviewerToken;
    private final String appToken;
    private final String keysToken;
    private final List<String> secrets;
    final String prefix;
    private final Thread shutdown;
    private final ReentrantLock lock = new ReentrantLock();
    private final Object interruption = new Object();
    private volatile @Nullable Thread holder;
    private volatile boolean cleaning;
    private volatile boolean closed;
    private volatile @Nullable String cleanupFailure;
    private Scope scope = Scope.NONE;
    private boolean creationUnconfirmed;
    private boolean dispatchUnseen;
    private boolean hookRegistered;
    private String sha = "";
    private String keyAccount = "";
    // Set before the scenario asks GitHub for one, so an answer lost on the way is cleaned up too.
    private final java.util.Set<Requested> requested = java.util.EnumSet.noneOf(Requested.class);
    private final List<Long> dispatchedRuns = new ArrayList<>();

    /** What a run created and its cleanup removes. */
    private enum Scope {
        NONE,
        ACTIONS_RUNS,
        EVERYTHING
    }

    GitHubFixture(
            HttpClient http,
            String userToken,
            String reviewerToken,
            String appToken,
            String keysToken,
            List<String> secrets,
            String runId) {
        if (http.followRedirects() != HttpClient.Redirect.NEVER)
            throw new IllegalArgumentException("Expected Redirect.NEVER");
        if (!runId.matches("[a-z0-9][a-z0-9-]{0,63}"))
            throw new IllegalArgumentException("Invalid recording run ID");
        if (("forge-client-" + runId).equals(KEPT))
            throw new IllegalArgumentException("A run ID must not name the kept fixture " + KEPT);
        this.http = http;
        this.userToken = userToken;
        this.reviewerToken = reviewerToken;
        this.appToken = appToken;
        this.keysToken = keysToken;
        this.secrets = List.copyOf(secrets);
        prefix = "forge-client-" + runId;
        shutdown =
                new Thread(
                        () ->
                                cleanupOnShutdown(
                                        System.err,
                                        HOOK_WAIT,
                                        CLEANUP_WAIT,
                                        status -> Runtime.getRuntime().halt(status)),
                        "github-fixture-cleanup");
    }

    static GitHubFixture environment(HttpClient http) throws Exception {
        Map<String, String> env = System.getenv();
        String user = required(env, "LIVE_GITHUB_USER_TOKEN");
        String reviewer = required(env, "LIVE_GITHUB_REVIEWER_TOKEN");
        String id = required(env, "LIVE_GITHUB_APP_ID");
        String key = required(env, "LIVE_GITHUB_APP_KEY");
        String keys = required(env, "LIVE_GITHUB_REVIEWER_KEYS_TOKEN");
        String jwt = GitHubAppToken.jwt(id, key, java.time.Instant.now());
        var bootstrap =
                new GitHubFixture(
                        http,
                        user,
                        reviewer,
                        jwt,
                        keys,
                        List.of(user, reviewer, key, jwt, keys),
                        "bootstrap");
        long installation =
                bootstrap.apiWith(jwt, "GET", ROOT + "/installation", null).path("id").asLong();
        if (installation <= 0)
            throw new IllegalStateException("App has no test repository installation");
        String token =
                bootstrap
                        .apiWith(
                                jwt,
                                "POST",
                                "app/installations/" + installation + "/access_tokens",
                                Map.of("repositories", List.of(REPO)))
                        .path("token")
                        .asText();
        if (token.isBlank()) throw new IllegalStateException("App installation token is missing");
        return new GitHubFixture(
                http,
                user,
                reviewer,
                token,
                keys,
                List.of(user, reviewer, key, jwt, token, keys),
                env.getOrDefault("LIVE_GITHUB_RUN_ID", UUID.randomUUID().toString()));
    }

    static String required(Map<String, String> env, String name) {
        String value = env.get(name);
        if (value == null || value.isBlank())
            throw new IllegalArgumentException("Missing environment variable " + name);
        return value;
    }

    void prepare() throws Exception {
        locked(
                () -> {
                    prepareLocked();
                    return null;
                });
    }

    private void prepareLocked() throws Exception {
        if (scope != Scope.NONE) throw new IllegalStateException("Fixture is already prepared");
        if (!items("git/matching-refs/heads/" + prefix).isEmpty()
                || pulls().stream().anyMatch(this::hasPrefix)
                || allRuns().stream().anyMatch(this::hasPrefix)
                || repositoryExists()
                || !ownKeys("user/keys").isEmpty()
                || !ownKeys("user/ssh_signing_keys").isEmpty()) {
            throw new IllegalStateException("Refusing leftovers of " + prefix);
        }
        String baseSha =
                api("GET", ROOT + "/git/ref/heads/main", null).path("object").path("sha").asText();
        if (!baseSha.matches("[a-f0-9]{40,64}"))
            throw new IllegalStateException("Main ref has no commit identity");
        Runtime.getRuntime().addShutdownHook(shutdown);
        hookRegistered = true;
        creationUnconfirmed = true;
        try {
            api("POST", ROOT + "/git/refs", Map.of("ref", "refs/heads/" + prefix, "sha", baseSha));
            scope = Scope.EVERYTHING;
            creationUnconfirmed = false;
        } catch (GitHubException ex) {
            // A rejected create must never adopt a competing recorder's resource.
            if (ex.status() >= 400 && ex.status() < 500) creationUnconfirmed = false;
            throw ex;
        }
        var content =
                Map.of(
                        "message",
                        prefix,
                        "content",
                        java.util.Base64.getEncoder()
                                .encodeToString(
                                        ("fixture-complete\n" + prefix + "\n")
                                                .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        "branch",
                        prefix);
        sha =
                api("PUT", ROOT + "/contents/" + prefix + ".txt", content)
                        .path("commit")
                        .path("sha")
                        .asText();
        if (!sha.matches("[a-f0-9]{40,64}"))
            throw new IllegalStateException("Fixture commit has no identity");
        // A status of its own, so the combined status has one to read; it goes with the branch.
        api(
                "POST",
                ROOT + "/statuses/" + sha,
                Map.of("state", "success", "context", prefix, "description", "Fixture status"));
    }

    /**
     * The account whose own keys the keys token lists, adds and deletes, read once: it must be the
     * reviewer, a test account, so no key of anyone else is touched.
     */
    String keyAccount() throws Exception {
        return locked(
                () -> {
                    if (keyAccount.isEmpty()) {
                        String login =
                                apiWith(keysToken, "GET", "user", null).path("login").asText();
                        if (!login.matches("[A-Za-z0-9-]{1,39}"))
                            throw new IllegalStateException("The keys token names no account");
                        if (!login.equals(
                                apiWith(reviewerToken, "GET", "user", null).path("login").asText()))
                            throw new IllegalStateException(
                                    "The keys token must belong to the reviewer");
                        keyAccount = login;
                    }
                    return keyAccount;
                });
    }

    /** What a run asks GitHub to make under its own name, and removes once it has asked. */
    enum Requested {
        REPOSITORY,
        KEYS,
        SIGNING_KEYS
    }

    /**
     * Asks GitHub to make what is named after this run, owning it from before the request on, so an
     * interrupted request is cleaned up too; a refused one leaves it to whoever made it.
     */
    <T> T requesting(Requested resource, Callable<T> create) throws Exception {
        return locked(
                () -> {
                    if (scope != Scope.EVERYTHING)
                        throw new IllegalStateException("Fixture is not prepared");
                    requested.add(resource);
                    try {
                        return create.call();
                    } catch (GitHubException ex) {
                        // A rejected create must never adopt a competing recorder's resource.
                        if (ex.status() >= 400 && ex.status() < 500) requested.remove(resource);
                        throw ex;
                    }
                });
    }

    /**
     * A new SSH public key made for one run, in OpenSSH's form with no comment; its private half is
     * dropped at once.
     */
    static String newPublicKey() throws Exception {
        byte[] encoded =
                java.security.KeyPairGenerator.getInstance("Ed25519")
                        .generateKeyPair()
                        .getPublic()
                        .getEncoded();
        // An X.509 Ed25519 key ends in its 32 raw bytes.
        byte[] raw = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        byte[] type = "ssh-ed25519".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        var blob = java.nio.ByteBuffer.allocate(4 + type.length + 4 + raw.length);
        blob.putInt(type.length).put(type).putInt(raw.length).put(raw);
        return "ssh-ed25519 " + java.util.Base64.getEncoder().encodeToString(blob.array());
    }

    private boolean repositoryExists() throws Exception {
        try {
            api("GET", "repos/" + OWNER + "/" + prefix, null);
            return true;
        } catch (GitHubException ex) {
            if (ex.status() == 404) return false;
            throw ex;
        }
    }

    /** The keys token's own keys of one kind that this run titled. */
    private List<JsonNode> ownKeys(String list) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (int page = 1; page <= 100; page++) {
            JsonNode answer = apiWith(keysToken, "GET", list + "?per_page=100&page=" + page, null);
            if (!answer.isArray())
                throw new IllegalStateException("Expected an array for key discovery");
            for (JsonNode key : answer)
                if (key.path("title").asText().equals(prefix)) result.add(key);
            if (answer.size() < 100) return result;
        }
        throw new IllegalStateException("Key discovery exceeded pagination bound");
    }

    private void deleteOwnKeys(String list) throws Exception {
        for (JsonNode key : ownKeys(list)) {
            long id = key.path("id").asLong();
            if (id <= 0) throw new IllegalStateException("Key has no identity");
            apiWith(keysToken, "DELETE", list + "/" + id, null);
        }
        if (!ownKeys(list).isEmpty()) throw new IllegalStateException("A key remains");
    }

    /** Takes ownership of the Actions runs this check dispatches, and of nothing else. */
    void beginLiveCheck() throws Exception {
        locked(
                () -> {
                    if (scope != Scope.NONE)
                        throw new IllegalStateException("Fixture is already prepared");
                    if (allRuns().stream().anyMatch(this::hasPrefix))
                        throw new IllegalStateException("Refusing leftover run " + prefix);
                    scope = Scope.ACTIONS_RUNS;
                    Runtime.getRuntime().addShutdownHook(shutdown);
                    hookRegistered = true;
                    return null;
                });
    }

    <T> T guarded(Callable<T> action) throws Exception {
        return locked(action);
    }

    JsonNode api(String method, String path, @Nullable Object body) throws Exception {
        return locked(() -> apiWith(userToken, method, path, body));
    }

    String userToken() {
        return userToken;
    }

    String reviewerToken() {
        return reviewerToken;
    }

    String appToken() {
        return appToken;
    }

    String keysToken() {
        return keysToken;
    }

    String sha() {
        return sha;
    }

    List<String> secrets() {
        return secrets;
    }

    /** Dispatches the two-step workflow on a branch, titled with this run's prefix. */
    long dispatch(String branch) throws Exception {
        return locked(
                () -> {
                    dispatchUnseen = true;
                    try {
                        api(
                                "POST",
                                ROOT + "/actions/workflows/" + WORKFLOW + "/dispatches",
                                Map.of("ref", branch, "inputs", Map.of("run_id", prefix)));
                    } catch (GitHubException ex) {
                        // A refused dispatch started no run for cleanup to wait for.
                        if (ex.status() >= 400 && ex.status() < 500) dispatchUnseen = false;
                        throw ex;
                    }
                    return awaitDispatchedRun();
                });
    }

    private long awaitDispatchedRun() throws Exception {
        for (int attempt = 0; attempt < 60; attempt++) {
            var matches =
                    allRuns().stream()
                            .filter(run -> run.path("display_title").asText().equals(prefix))
                            .toList();
            if (matches.size() > 1)
                throw new IllegalStateException("Dispatch returned multiple runs for " + prefix);
            if (!matches.isEmpty()) {
                var run = matches.getFirst();
                long id = run.path("id").asLong();
                if (id <= 0) throw new IllegalStateException("Dispatched run has no identity");
                if (!dispatchedRuns.contains(id)) dispatchedRuns.add(id);
                dispatchUnseen = false;
                if (run.path("status").asText().equals("completed")) {
                    if (!run.path("conclusion").asText().equals("success"))
                        throw new IllegalStateException("Dispatched workflow did not succeed");
                    return id;
                }
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("Workflow did not complete within 120 seconds");
    }

    private List<JsonNode> items(String suffix) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            String separator = suffix.contains("?") ? "&" : "?";
            JsonNode answer =
                    api("GET", ROOT + "/" + suffix + separator + "per_page=100&page=" + page, null);
            if (!answer.isArray())
                throw new IllegalStateException("Expected an array for fixture discovery");
            answer.forEach(result::add);
            if (answer.size() < 100) return result;
        }
        throw new IllegalStateException("Fixture discovery exceeded pagination bound");
    }

    private List<JsonNode> pulls() throws Exception {
        return items("pulls?state=open");
    }

    private List<JsonNode> allRuns() throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            var answer =
                    api("GET", ROOT + "/actions/runs?per_page=100&page=" + page, null)
                            .path("workflow_runs");
            if (!answer.isArray())
                throw new IllegalStateException("Expected runs for fixture discovery");
            answer.forEach(result::add);
            if (answer.size() < 100) return result;
        }
        throw new IllegalStateException("Run discovery exceeded pagination bound");
    }

    private boolean hasPrefix(JsonNode value) {
        return value.path("head").path("ref").asText().equals(prefix)
                || value.path("head_branch").asText().equals(prefix)
                || value.path("display_title").asText().equals(prefix)
                || value.path("title").asText().equals(prefix);
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Fixture is closed");
    }

    /**
     * Works under the fixture's lock, as the thread the shutdown hook interrupts so it need not
     * wait out a long call or a poll.
     */
    private <T> T locked(Callable<T> action) throws Exception {
        lock.lockInterruptibly();
        Thread outer;
        // Under the hook's monitor, so the hook sees the holder and its cleaning as one.
        synchronized (interruption) {
            outer = holder;
            holder = Thread.currentThread();
        }
        try {
            ensureOpen();
            return action.call();
        } finally {
            synchronized (interruption) {
                holder = outer;
            }
            lock.unlock();
        }
    }

    /** GitHub's own short reason for a refusal, credentials redacted; nothing else of the body. */
    private String reason(String body) {
        try {
            JsonNode message = json.readTree(body).path("message");
            if (!message.isTextual()) return "";
            String text = GitHubJson.safe(message.asText(), secrets);
            return ": " + (text.length() > 300 ? text.substring(0, 300) + "..." : text);
        } catch (IOException | RuntimeException ex) {
            return "";
        }
    }

    private JsonNode apiWith(String token, String method, String path, @Nullable Object body)
            throws Exception {
        URI uri = BASE.resolve(path);
        if (!uri.getScheme().equals("https")
                || !uri.getHost().equals(BASE.getHost())
                || uri.getPort() != BASE.getPort()
                || uri.getRawUserInfo() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException("Setup calls must stay on the GitHub API origin");
        try {
            var request =
                    HttpRequest.newBuilder(uri)
                            .timeout(Duration.ofSeconds(60))
                            .header("Authorization", "Bearer " + token)
                            .header("Accept", "application/vnd.github+json")
                            .header("X-GitHub-Api-Version", "2026-03-10");
            if (body != null) request.header("Content-Type", "application/json");
            var response =
                    http.send(
                            request.method(
                                            method,
                                            body == null
                                                    ? HttpRequest.BodyPublishers.noBody()
                                                    : HttpRequest.BodyPublishers.ofString(
                                                            json.writeValueAsString(body)))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new GitHubException(
                        response.statusCode(),
                        "GitHub fixture call failed: "
                                + method
                                + " "
                                + uri.getRawPath()
                                + " -> "
                                + response.statusCode()
                                + reason(response.body()));
            return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
        } catch (IOException | IllegalArgumentException ex) {
            throw new IllegalStateException("GitHub fixture transport or payload failed");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("GitHub fixture request interrupted");
        }
    }

    @Override
    public void close() throws Exception {
        // Cleanup takes its turn even on an interrupted thread.
        lock.lock();
        try {
            closeLocked();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Interrupts whatever holds the fixture unless it is already cleaning up, waits a bounded time
     * for the lock, cleans up and, on failure, prints what was left before halting non-zero.
     */
    void cleanupOnShutdown(
            PrintStream error, Duration wait, Duration cleanupWait, IntConsumer halt) {
        long start = System.nanoTime();
        boolean anotherCleanup = false;
        try {
            while (true) {
                // Each round, since a thread may take the fixture just after the last one.
                synchronized (interruption) {
                    Thread busy = holder;
                    if (busy != null && !cleaning) busy.interrupt();
                    anotherCleanup |= cleaning;
                }
                if (lock.tryLock(100, TimeUnit.MILLISECONDS)) break;
                // Again, since a cleanup may have begun while the hook waited.
                anotherCleanup |= cleaning;
                // A cleanup seen running gets its time, also while it lets go of the fixture;
                // halting would cut it short.
                long deadline = start + (anotherCleanup ? cleanupWait : wait).toNanos();
                if (System.nanoTime() - deadline > 0) {
                    String failure = cleanupFailure;
                    error.println(
                            failure == null
                                    ? "GitHub cleanup timed out; inspect " + prefix
                                    : failure);
                    halt.accept(1);
                    return;
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            error.println("GitHub cleanup was interrupted; inspect " + prefix);
            halt.accept(1);
            return;
        }
        try {
            closeLocked();
            String failure = cleanupFailure;
            if (failure != null) {
                error.println(failure);
                halt.accept(1);
            }
        } catch (Exception ex) {
            error.println(String.valueOf(ex.getMessage()));
            halt.accept(1);
        } finally {
            lock.unlock();
        }
    }

    private void closeLocked() throws Exception {
        if (closed) return;
        if (scope == Scope.NONE) {
            closed = true;
            removeHook();
            if (creationUnconfirmed) fail("Branch creation was not confirmed; inspect " + prefix);
            return;
        }
        synchronized (interruption) {
            cleaning = true;
        }
        // An interrupted scenario still needs an uninterrupted opportunity to clean up its
        // resources.
        boolean interrupted = Thread.interrupted();
        List<String> failures = new ArrayList<>();
        List<Long> closedPulls = new ArrayList<>();
        try {
            // The pull is closed first, so a comment the user may not delete leaves it closed.
            cleanup(
                    Scope.EVERYTHING,
                    "pull requests",
                    failures,
                    () -> {
                        for (var pull : pulls())
                            if (hasPrefix(pull)) {
                                long number = pull.path("number").asLong();
                                if (number <= 0)
                                    throw new IllegalStateException("Pull request has no number");
                                api("PATCH", ROOT + "/pulls/" + number, Map.of("state", "closed"));
                                if (!api("GET", ROOT + "/pulls/" + number, null)
                                        .path("state")
                                        .asText()
                                        .equals("closed"))
                                    throw new IllegalStateException("Pull request remained open");
                                closedPulls.add(number);
                            }
                    });
            cleanup(
                    Scope.EVERYTHING,
                    "review comments",
                    failures,
                    () -> deleteComments(closedPulls, "pulls/", "/pulls/comments/"));
            cleanup(
                    Scope.EVERYTHING,
                    "general comments",
                    failures,
                    () -> deleteComments(closedPulls, "issues/", "/issues/comments/"));
            cleanup(
                    Scope.EVERYTHING,
                    "check runs",
                    failures,
                    () -> {
                        if (!sha.isBlank()) {
                            var answer =
                                    apiWith(
                                            appToken,
                                            "GET",
                                            ROOT
                                                    + "/commits/"
                                                    + sha
                                                    + "/check-runs?check_name="
                                                    + prefix
                                                    + "&filter=all&per_page=100",
                                            null);
                            var checks = answer.path("check_runs");
                            if (!checks.isArray())
                                throw new IllegalStateException(
                                        "Check discovery returned no array");
                            if (answer.path("total_count").asLong() > checks.size())
                                throw new IllegalStateException("More check runs than one page");
                            for (var check : checks)
                                if (check.path("name").asText().equals(prefix)
                                        && !check.path("status").asText().equals("completed")) {
                                    long id = check.path("id").asLong();
                                    if (id <= 0)
                                        throw new IllegalStateException(
                                                "Check run has no identity");
                                    var completed =
                                            apiWith(
                                                    appToken,
                                                    "PATCH",
                                                    ROOT + "/check-runs/" + id,
                                                    Map.of(
                                                            "status",
                                                            "completed",
                                                            "conclusion",
                                                            "neutral"));
                                    if (!completed.path("status").asText().equals("completed"))
                                        throw new IllegalStateException("Check run remained open");
                                }
                        }
                    });
            cleanup(Scope.ACTIONS_RUNS, "Actions runs", failures, this::removeActionsRuns);
            cleanup(
                    Scope.EVERYTHING,
                    "repository",
                    failures,
                    () -> {
                        if (!requested.contains(Requested.REPOSITORY)) return;
                        if (repositoryExists())
                            api("DELETE", "repos/" + OWNER + "/" + prefix, null);
                        if (repositoryExists())
                            throw new IllegalStateException("Repository remains");
                    });
            cleanup(
                    Scope.EVERYTHING,
                    "keys",
                    failures,
                    () -> {
                        if (requested.contains(Requested.KEYS)) deleteOwnKeys("user/keys");
                    });
            cleanup(
                    Scope.EVERYTHING,
                    "signing keys",
                    failures,
                    () -> {
                        if (requested.contains(Requested.SIGNING_KEYS))
                            deleteOwnKeys("user/ssh_signing_keys");
                    });
            cleanup(
                    Scope.EVERYTHING,
                    "branch",
                    failures,
                    () -> {
                        for (var ref : items("git/matching-refs/heads/" + prefix))
                            if (ref.path("ref").asText().equals("refs/heads/" + prefix))
                                api("DELETE", ROOT + "/git/refs/heads/" + prefix, null);
                        if (items("git/matching-refs/heads/" + prefix).stream()
                                .anyMatch(
                                        ref ->
                                                ref.path("ref")
                                                        .asText()
                                                        .equals("refs/heads/" + prefix)))
                            throw new IllegalStateException("Branch remains");
                    });
        } finally {
            closed = true;
            synchronized (interruption) {
                cleaning = false;
            }
            removeHook();
            if (interrupted) Thread.currentThread().interrupt();
        }
        if (!failures.isEmpty())
            fail("Cleanup left " + String.join(", ", failures) + " for " + prefix);
    }

    /** Deletes every comment of the pulls; one the user may not delete does not keep the others. */
    private void deleteComments(List<Long> pulls, String list, String delete) throws Exception {
        boolean refused = false;
        for (long number : pulls)
            for (var comment : items(list + number + "/comments")) {
                long id = comment.path("id").asLong();
                if (id <= 0) throw new IllegalStateException("Comment has no identity");
                try {
                    api("DELETE", ROOT + delete + id, null);
                } catch (GitHubException ex) {
                    refused = true;
                }
            }
        if (refused) throw new IllegalStateException("A comment remains");
    }

    private void removeActionsRuns() throws Exception {
        List<JsonNode> runs = allRuns();
        // A run dispatched just before an interrupt can take a moment to be listed.
        for (int attempt = 0; dispatchUnseen && runs.stream().noneMatch(this::ownRun); attempt++) {
            if (attempt == 10) throw new IllegalStateException("A dispatched run was never listed");
            Thread.sleep(Duration.ofSeconds(1));
            runs = allRuns();
        }
        for (var run : runs)
            if (ownRun(run)) {
                long id = run.path("id").asLong();
                if (id <= 0) throw new IllegalStateException("Actions run has no identity");
                if (!run.path("status").asText().equals("completed")) {
                    try {
                        api("POST", ROOT + "/actions/runs/" + id + "/cancel", null);
                    } catch (GitHubException ex) {
                        // A run that completed after it was listed can no longer be cancelled.
                        if (ex.status() != 409) throw ex;
                    }
                    awaitCompletion(id);
                }
                api("DELETE", ROOT + "/actions/runs/" + id, null);
            }
        if (allRuns().stream().anyMatch(this::ownRun))
            throw new IllegalStateException("Actions runs remain");
    }

    private void awaitCompletion(long run) throws Exception {
        for (int attempt = 0; attempt < 30; attempt++) {
            if (api("GET", ROOT + "/actions/runs/" + run, null)
                    .path("status")
                    .asText()
                    .equals("completed")) return;
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException("Actions run remained active");
    }

    private boolean ownRun(JsonNode run) {
        return dispatchedRuns.contains(run.path("id").asLong())
                || (dispatchUnseen && run.path("display_title").asText().equals(prefix))
                || (scope == Scope.EVERYTHING && hasPrefix(run));
    }

    private void removeHook() {
        if (!hookRegistered) return;
        try {
            Runtime.getRuntime().removeShutdownHook(shutdown);
        } catch (IllegalStateException ex) {
            /* Shutdown already owns the hook. */
        }
    }

    private void fail(String message) {
        cleanupFailure = message;
        throw new IllegalStateException(message);
    }

    private void cleanup(
            Scope needed, String resource, List<String> failures, CheckedAction action) {
        if (needed == Scope.EVERYTHING && scope != Scope.EVERYTHING) return;
        try {
            action.run();
        } catch (Exception ex) {
            failures.add(resource);
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
