package org.fuin.porelo.forge.github;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.fuin.porelo.forge.github.replay.ReplayHttpClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

class GitHubFixtureTest {
    private static final String RUNS = "/" + GitHubFixture.ROOT + "/actions/runs";
    private static final String NONE = "{\"workflow_runs\":[]}";

    // Prevents a refused setup call from hiding why GitHub refused it, or from leaking a token.
    @Test
    void aRefusedSetupCallNamesGitHubsStatusAndReasonWithoutCredentials() throws Exception {
        var http =
                new ReplayHttpClient()
                        .otherwise(
                                request -> {
                                    String path = request.uri().getRawPath();
                                    if (request.method().equals("POST"))
                                        return new ReplayHttpClient.Answer(
                                                422,
                                                Map.of(),
                                                "{\"message\":\"Refused for synthetic-user-token\"}");
                                    if (path.endsWith("/git/ref/heads/main"))
                                        return new ReplayHttpClient.Answer(
                                                200,
                                                Map.of(),
                                                "{\"object\":{\"sha\":\""
                                                        + "a".repeat(40)
                                                        + "\"}}");
                                    if (path.endsWith("/actions/runs"))
                                        return new ReplayHttpClient.Answer(200, Map.of(), NONE);
                                    if (path.equals("/repos/porelo-ai-test/forge-client-unit"))
                                        return new ReplayHttpClient.Answer(404, Map.of(), "{}");
                                    return new ReplayHttpClient.Answer(200, Map.of(), "[]");
                                });
        var fixture = fixture(http, "unit");
        try {
            assertThatExceptionOfType(GitHubException.class)
                    .as("a refused branch creation fails the setup")
                    .isThrownBy(fixture::prepare)
                    .satisfies(
                            ex ->
                                    assertThat(ex.getMessage())
                                            .as(
                                                    "the failure names the call, GitHub's status and its reason")
                                            .contains("POST", "/git/refs", "422", "Refused for")
                                            .as("no token reaches the message")
                                            .doesNotContain("synthetic-user-token"));
        } finally {
            fixture.close();
        }
    }

    // Prevents a recording run from adopting, and then deleting, the kept review fixture.
    @Test
    void aRunIdNamingTheKeptFixtureIsRefused() {
        assertThatExceptionOfType(IllegalArgumentException.class)
                .as("the kept fixture's name is not a run's prefix")
                .isThrownBy(() -> fixture(new ReplayHttpClient(), "live"));
    }

    // Prevents a live check from closing pull requests or deleting branches it never created.
    @Test
    void aLiveCheckCleansOnlyTheActionsRunsItOwns() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), runs("completed"))
                        .answer(200, Map.of(), runs("completed"))
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), NONE);
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        fixture.dispatch("main");

        fixture.close();

        assertThat(http.requests().stream().map(request -> request.uri().getRawPath()))
                .as("cleanup touches only Actions runs")
                .allMatch(path -> path.startsWith(RUNS) || path.endsWith("/dispatches"));
        assertThat(deletes(http)).as("the owned run is deleted").containsExactly(RUNS + "/7");
    }

    // Prevents a live check from adopting runs with matching names that it did not dispatch.
    @Test
    void aLiveCheckDoesNotAdoptRunsMerelyBecauseTheirNamesMatch() throws Exception {
        String unrelated =
                "{\"workflow_runs\":[{\"id\":8,\"head_branch\":\"forge-client-unit\"},"
                        + "{\"id\":9,\"display_title\":\"forge-client-unit\"}]}";
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .answer(200, Map.of(), unrelated)
                        .answer(200, Map.of(), unrelated);
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();

        fixture.close();

        assertThat(http.requests())
                .as("cleanup only discovers unrelated runs")
                .allMatch(request -> request.method().equals("GET"));
    }

    // Prevents a run that completes between listing and cancelling from being left behind.
    @Test
    void aRunThatCompletesBeforeItsCancelIsStillDeleted() throws Exception {
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), runs("completed"))
                        .answer(200, Map.of(), runs("in_progress"))
                        .answer(409, Map.of(), "{\"message\":\"Cannot cancel a completed run\"}")
                        .answer(200, Map.of(), "{\"status\":\"completed\"}")
                        .answer(204, Map.of(), "")
                        .answer(200, Map.of(), NONE);
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        fixture.dispatch("main");

        fixture.close();

        assertThat(deletes(http))
                .as("the completed run is deleted after the refused cancel")
                .containsExactly(RUNS + "/7");
    }

    // Prevents an interrupted run from exiting without naming what it left on GitHub.
    @Test
    void theShutdownHookPrintsWhatCleanupLeftAndHaltsNonZero() throws Exception {
        var http = new ReplayHttpClient().answer(200, Map.of(), NONE).answer(500, Map.of(), "{}");
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        var error = new ByteArrayOutputStream();
        List<Integer> halts = new CopyOnWriteArrayList<>();

        fixture.cleanupOnShutdown(
                new PrintStream(error, true, StandardCharsets.UTF_8),
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                halts::add);

        assertThat(error.toString(StandardCharsets.UTF_8))
                .as("the hook names the leftover and the run")
                .contains("Actions runs", fixture.prefix)
                .doesNotContain("synthetic-user-token");
        assertThat(halts).as("a failed cleanup halts non-zero").containsExactly(1);
    }

    // Prevents a shutdown hook from waiting behind a polling dispatch until it is killed.
    @Test
    @Timeout(10)
    void theShutdownHookInterruptsAPollingDispatchAndCleansUp() throws Exception {
        var cancelled = new AtomicBoolean();
        var deleted = new AtomicBoolean();
        var polling = new CountDownLatch(1);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .otherwise(
                                request -> {
                                    if (request.uri().getRawPath().equals(RUNS))
                                        polling.countDown();
                                    return github(request, cancelled, deleted);
                                });
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        var failure = new AtomicReference<Throwable>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                fixture.dispatch("main");
                            } catch (Exception ex) {
                                failure.set(ex);
                            }
                        });
        worker.setDaemon(true);
        worker.start();
        assertThat(polling.await(3, TimeUnit.SECONDS)).as("dispatch reached polling").isTrue();
        List<Integer> halts = new CopyOnWriteArrayList<>();
        long start = System.nanoTime();

        fixture.cleanupOnShutdown(
                new PrintStream(new ByteArrayOutputStream(), true, StandardCharsets.UTF_8),
                Duration.ofSeconds(3),
                Duration.ofSeconds(3),
                halts::add);

        long elapsed = System.nanoTime() - start;
        worker.interrupt();
        worker.join(Duration.ofSeconds(5));
        assertThat(halts).as("cleanup ran instead of giving up").isEmpty();
        assertThat(Duration.ofNanos(elapsed))
                .as("the hook did not wait out its bound")
                .isLessThan(Duration.ofSeconds(3));
        assertThat(deleted).as("the dispatched run is deleted").isTrue();
        assertThat(failure.get()).as("the dispatch was interrupted").isNotNull();
    }

    // Prevents an accepted dispatch from leaking when shutdown precedes its appearance in the run
    // list.
    @Test
    void cleanupWaitsForAnAcceptedDispatchThatIsNotListedYet() throws Exception {
        var lists = new AtomicInteger();
        var cancelled = new AtomicBoolean();
        var deleted = new AtomicBoolean();
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .otherwise(
                                request -> {
                                    if (request.method().equals("POST")
                                            && request.uri().getPath().endsWith("/dispatches")) {
                                        Thread.currentThread().interrupt();
                                        return new ReplayHttpClient.Answer(204, Map.of(), "");
                                    }
                                    if (request.uri().getPath().equals(RUNS)
                                            && lists.getAndIncrement() == 0)
                                        return new ReplayHttpClient.Answer(200, Map.of(), NONE);
                                    return github(request, cancelled, deleted);
                                });
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        try {
            assertThatExceptionOfType(InterruptedException.class)
                    .as("the scenario is interrupted after the accepted dispatch")
                    .isThrownBy(() -> fixture.dispatch("main"));
            fixture.close();
        } finally {
            Thread.interrupted();
        }

        assertThat(deleted).as("cleanup finds and deletes the delayed dispatch").isTrue();
    }

    // Prevents a shutdown hook from waiting indefinitely behind cleanup already running on another
    // thread.
    @Test
    @Timeout(10)
    void theShutdownDeadlineAlsoBoundsWaitingForAnotherCleanup() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new Semaphore(0);
        var first = new AtomicBoolean(true);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .otherwise(
                                request -> {
                                    if (first.getAndSet(false)) {
                                        entered.countDown();
                                        finish.acquireUninterruptibly();
                                    }
                                    return new ReplayHttpClient.Answer(200, Map.of(), NONE);
                                });
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        var workerFailure = new AtomicReference<Exception>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                fixture.close();
                            } catch (Exception ex) {
                                workerFailure.set(ex);
                            }
                        });
        worker.setDaemon(true);
        var error = new ByteArrayOutputStream();
        var halted = new CountDownLatch(1);
        List<Integer> halts = new CopyOnWriteArrayList<>();
        var hook =
                new Thread(
                        () ->
                                fixture.cleanupOnShutdown(
                                        new PrintStream(error, true, StandardCharsets.UTF_8),
                                        Duration.ofMillis(50),
                                        Duration.ofMillis(50),
                                        status -> {
                                            halts.add(status);
                                            halted.countDown();
                                        }));
        hook.setDaemon(true);
        worker.start();
        boolean bounded;
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS))
                    .as("another cleanup owns the lock")
                    .isTrue();
            hook.start();
            bounded = halted.await(3, TimeUnit.SECONDS);
        } finally {
            finish.release();
            worker.join(Duration.ofSeconds(3));
            hook.join(Duration.ofSeconds(3));
        }

        assertThat(bounded)
                .as("the hook halts before the other cleanup is allowed to finish")
                .isTrue();
        assertThat(halts).as("the unresolved cleanup cannot report success").containsExactly(1);
        assertThat(error.toString(StandardCharsets.UTF_8))
                .as("the timeout names the resources to inspect")
                .contains("cleanup", fixture.prefix);
        assertThat(workerFailure.get())
                .as("the existing cleanup can finish when released")
                .isNull();
    }

    // Prevents the shutdown hook from halting the JVM under a cleanup that is still running, which
    // would leave what that cleanup had yet to remove.
    @Test
    @Timeout(10)
    void theShutdownHookWaitsOutACleanupAlreadyRunning() throws Exception {
        var entered = new CountDownLatch(1);
        var finish = new Semaphore(0);
        var first = new AtomicBoolean(true);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .otherwise(
                                request -> {
                                    if (first.getAndSet(false)) {
                                        entered.countDown();
                                        finish.acquireUninterruptibly();
                                    }
                                    return new ReplayHttpClient.Answer(200, Map.of(), NONE);
                                });
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        var workerFailure = new AtomicReference<Exception>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                fixture.close();
                            } catch (Exception ex) {
                                workerFailure.set(ex);
                            }
                        });
        worker.setDaemon(true);
        List<Integer> halts = new CopyOnWriteArrayList<>();
        var hook =
                new Thread(
                        () ->
                                fixture.cleanupOnShutdown(
                                        new PrintStream(
                                                new ByteArrayOutputStream(),
                                                true,
                                                StandardCharsets.UTF_8),
                                        Duration.ofMillis(50),
                                        Duration.ofSeconds(5),
                                        halts::add));
        hook.setDaemon(true);
        worker.start();
        try {
            assertThat(entered.await(3, TimeUnit.SECONDS))
                    .as("another cleanup owns the lock")
                    .isTrue();
            hook.start();
            Thread.sleep(300);
        } finally {
            finish.release();
            worker.join(Duration.ofSeconds(3));
            hook.join(Duration.ofSeconds(3));
        }

        assertThat(halts).as("the hook let the running cleanup finish").isEmpty();
        assertThat(workerFailure.get()).as("the running cleanup finished").isNull();
    }

    // Prevents the shutdown hook from halting a cleanup that began while it waited for the
    // fixture, which would leave what that cleanup had yet to remove.
    @Test
    @Timeout(10)
    void theShutdownHookWaitsOutACleanupThatBeganWhileItWaited() throws Exception {
        var calls = new AtomicInteger();
        var calling = new CountDownLatch(1);
        var cleaningUp = new CountDownLatch(1);
        var finish = new Semaphore(0);
        var http =
                new ReplayHttpClient()
                        .answer(200, Map.of(), NONE)
                        .otherwise(
                                request -> {
                                    if (calls.getAndIncrement() == 0) {
                                        // Ends only once the hook has interrupted it, so the hook
                                        // looked before any cleanup began.
                                        calling.countDown();
                                        while (!Thread.currentThread().isInterrupted())
                                            Thread.onSpinWait();
                                        return new ReplayHttpClient.Answer(500, Map.of(), NONE);
                                    }
                                    if (cleaningUp.getCount() > 0) {
                                        cleaningUp.countDown();
                                        finish.acquireUninterruptibly();
                                    }
                                    return new ReplayHttpClient.Answer(200, Map.of(), NONE);
                                });
        var fixture = fixture(http, "unit");
        fixture.beginLiveCheck();
        var scenarioFailure = new AtomicReference<Exception>();
        var workerFailure = new AtomicReference<Exception>();
        var worker =
                new Thread(
                        () -> {
                            try {
                                try {
                                    fixture.api("GET", "/user", null);
                                } catch (Exception ex) {
                                    scenarioFailure.set(ex);
                                }
                                fixture.close();
                            } catch (Exception ex) {
                                workerFailure.set(ex);
                            }
                        });
        worker.setDaemon(true);
        List<Integer> halts = new CopyOnWriteArrayList<>();
        var hook =
                new Thread(
                        () ->
                                fixture.cleanupOnShutdown(
                                        new PrintStream(
                                                new ByteArrayOutputStream(),
                                                true,
                                                StandardCharsets.UTF_8),
                                        Duration.ofMillis(50),
                                        Duration.ofSeconds(5),
                                        halts::add));
        hook.setDaemon(true);
        worker.start();
        try {
            assertThat(calling.await(3, TimeUnit.SECONDS))
                    .as("the scenario holds the lock")
                    .isTrue();
            hook.start();
            assertThat(cleaningUp.await(3, TimeUnit.SECONDS))
                    .as("the interrupted scenario cleans up")
                    .isTrue();
            Thread.sleep(300);
        } finally {
            finish.release();
            worker.join(Duration.ofSeconds(3));
            hook.join(Duration.ofSeconds(3));
        }

        assertThat(scenarioFailure.get()).as("the hook interrupted the scenario").isNotNull();
        assertThat(halts).as("the hook let the cleanup finish").isEmpty();
        assertThat(workerFailure.get()).as("the cleanup finished").isNull();
    }

    private static ReplayHttpClient.Answer github(
            HttpRequest request, AtomicBoolean cancelled, AtomicBoolean deleted) {
        String path = request.uri().getRawPath();
        String status = cancelled.get() ? "completed" : "queued";
        if (request.method().equals("POST") && path.endsWith("/cancel")) {
            cancelled.set(true);
            return new ReplayHttpClient.Answer(202, Map.of(), "{}");
        }
        if (request.method().equals("POST")) return new ReplayHttpClient.Answer(204, Map.of(), "");
        if (request.method().equals("DELETE")) {
            deleted.set(true);
            return new ReplayHttpClient.Answer(204, Map.of(), "");
        }
        if (path.equals(RUNS + "/7"))
            return new ReplayHttpClient.Answer(200, Map.of(), "{\"status\":\"" + status + "\"}");
        return new ReplayHttpClient.Answer(200, Map.of(), deleted.get() ? NONE : runs(status));
    }

    private static String runs(String status) {
        return "{\"workflow_runs\":[{\"id\":7,\"display_title\":\"forge-client-unit\",\"status\":\""
                + status
                + "\",\"conclusion\":\"success\"}]}";
    }

    private static List<String> deletes(ReplayHttpClient http) {
        return http.requests().stream()
                .filter(request -> request.method().equals("DELETE"))
                .map(request -> request.uri().getRawPath())
                .toList();
    }

    private static GitHubFixture fixture(ReplayHttpClient http, String runId) {
        return new GitHubFixture(
                http,
                "synthetic-user-token",
                "synthetic-reviewer-token",
                "synthetic-app-token",
                "synthetic-keys-token",
                List.of(
                        "synthetic-user-token",
                        "synthetic-reviewer-token",
                        "synthetic-app-token",
                        "synthetic-keys-token"),
                runId);
    }
}
