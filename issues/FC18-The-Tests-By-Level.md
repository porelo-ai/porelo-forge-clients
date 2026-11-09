# FC18 — The Tests By Level

**Status:** Analyzed.
**Size:** M - two modules' test fixtures, each about S, with no main code and no contract.

**What must be true.** The clients' tests follow the pyramid: every flow of the clients and of their test fixtures that
can do harm is proven by a unit test over fakes in seconds, the real Forgejo in podman shows only what nothing lower can,
and no unit test waits on the wall clock.

## The suites by level

Measured on the host, from a clean build of `main` 87a0aa9, with the pinned GraalVM.

| Level | Suite | Tests | Measured time |
|---|---|---|---|
| Unit | `openapi-extract`: extract, HTTPS source, template override, over a stub `HttpClient` | 66 | 2.2 s |
| Unit | `openapi-records`: the generated records' strict reading | 22 | 0.9 s |
| Unit | `forgejo`: client, answers, webhooks, recordings replayed, scrubber, recorder, podman command guard | 111 | 2.5 s |
| Unit | `github`: client, answers, keys, recordings replayed, recorder, fixture, live check's logic, replay from outside | 219 | 11.5 s |
| Integration | `ForgejoLiveIT`: every Forgejo operation and three webhook deliveries against a real Forgejo 17 and its runner in podman, the Build's second step | 1 (40 operations, 3 deliveries) | 62.5 s; the step 128 s |
| Integration, by hand | `GitHubRecorder` and the `github-live` profile's `GitHubLiveCheck` against github.com, with tokens, never in the Build | - | not run: it needs the GitHub tokens |
| End-to-end | none: a library has no product a user meets; the clients meet it in the adapters' repository | 0 | - |

By count, 418 of 419 tests in the Build are unit tests. By run time, the one integration test takes 62.5 s against 17.2 s
for all unit tests; the whole Build's first step, `verify` with every shared check, took 80 s. Every operation of both
clients is replayed in a unit test from a recording made against the real forge, so the live runs check that the
recordings still match the forge, not the clients' own logic.

## What only an integration run proves

- **The Forgejo fixture's own clean-up.** `ForgejoContainer`'s refusal to start over leftovers
  (`refuseLeftovers`), its removal of what it made (`close`, `remove`) and its shutdown hook (`cleanupOnShutdown`)
  run only in `ForgejoLiveIT` and in the recorder, and only on their good path: no test has seen a refused start, a
  failed removal or the hook's halt. A fault there leaves the run's containers or volume on the machine, the shared CI
  runner or a VM among them. The GitHub fixture's clean-up, by contrast, has unit tests over a fake transport (`GitHubFixtureTest`).
- No client flow: every request and every answer of both clients is proven by unit tests over recorded answers.

## Tests that wait where a fake would do

- **`GitHubFixtureTest` waits on the wall clock.** `GitHubFixture` polls with `Thread.sleep` of 1 s and 2 s
  (`removeActionsRuns`, `awaitDispatchedRun`, `awaitCompletion`), so
  `cleanupWaitsForAnAcceptedDispatchThatIsNotListedYet` sleeps 1 s for real; three shutdown-hook tests sleep 300 ms,
  wait on latches for up to 3 s and assert an elapsed time below 3 s, which a loaded machine can break.
- The Forgejo scenario waits into the next whole second (`afterAnEarlierComment`) and the fixture polls every second
  (`pause`); both wait on a real Forgejo in the integration run, where the wait is the forge's own, and stay.
- No unit test needs the network: each uses a fake `HttpClient` (`ReplayHttpClient`, `ChunkedTransport`,
  `StubHttpClient`), the webhook tests a receiver on `127.0.0.1` only in the integration run.

## Plan

- **The Forgejo fixture's clean-up over a fake podman (S).** `Podman.run` is static and `ForgejoContainer` calls it
  in thirteen places (`clients/forgejo/src/test/.../ForgejoContainer.java`); those calls go behind a small interface
  that `ForgejoContainer` takes, the real one wrapping `Podman`, and the shutdown hook's `Runtime.halt(1)` behind a
  halter the test can replace. Unit tests over a fake prove that a start refuses leftovers and names them, that `close`
  removes exactly what the run made, also after a failed start, and throws naming what it could not remove, and that
  the shutdown hook halts non-zero then. Red first: a `close` that skips the runner's container.
- **The GitHub fixture's waits through an injected sleeper and clock (S).** `GitHubFixture` takes a sleeper and a
  clock, the live check the real ones; `GitHubFixtureTest` passes a fake that advances time without waiting, and the
  shutdown-hook tests assert the bound by the fake clock, not by `System.nanoTime`. Red first: a polling loop that
  stops one attempt early, seen by the fake clock.

## Acceptance

- The unit suite's time drops by the waits removed, and no test in it calls `Thread.sleep` or asserts an elapsed
  wall-clock time; checked by a search of `src/test` outside the integration fixtures.
- A `ForgejoContainer.close` broken to keep one resource fails a unit test that names it.
- `ForgejoLiveIT` still passes in the Build, unchanged in what it proves.

## To be checked

- That `ForgejoContainer`'s constructor, which registers the hook and starts creating resources, can take the fake
  without starting anything real; if it cannot, the start moves into a method of its own, said in this plan first.
