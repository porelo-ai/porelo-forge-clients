# FC01 — The GitHub Client

**Status:** Analyzed; blocked by [FC04](README.md#fc04).
**Size:** L - one module, but twenty REST operations, GraphQL beside REST, webhooks and the recordings of all of them.

**What must be true.** A caller reads and writes what a code review and a build need on GitHub through the `github`
module, `org.fuin.porelo:porelo-forge-client-github`: pull requests, reviews, review comments and threads (resolved
only through GraphQL), suggestions, check runs and Actions runs, jobs and their logs, git refs, webhooks, and the
account and its organization membership - in GitHub's own terms, knowing nothing of Porelo. Only github.com is
served, not GitHub Enterprise Server.

## Why

GitHub publishes its REST API as OpenAPI in `github/rest-api-description`, under the MIT license. The OpenAPI 3.0
file has no webhooks; the OpenAPI 3.1 file of API version `2026-03-10` (`descriptions-next/api.github.com/`, 13.9 MB)
has all of them, among them `pull-request-review-thread-resolved` and the check and workflow events. Every path
below is in it. A review thread - its resolution, whether it is outdated - exists only in the GraphQL API
(`PullRequest.reviewThreads`, `resolveReviewThread`, `unresolveReviewThread`), which GitHub publishes as a schema
file but not as OpenAPI.

## Plan

- **The operations, 20, reaching 44 schemas with 713 properties (measured on the 3.1 file):**
  `GET`/`POST /repos/{owner}/{repo}/pulls`, `GET .../pulls/{pull_number}`, `GET`/`POST .../reviews`,
  `GET`/`POST .../pulls/{pull_number}/comments`, `POST .../comments/{comment_id}/replies`,
  `GET .../commits/{ref}/check-runs`, `POST .../check-runs`, `PATCH .../check-runs/{check_run_id}`,
  `GET .../actions/runs`, `GET .../actions/runs/{run_id}/jobs`, `GET .../actions/jobs/{job_id}/logs`,
  `GET .../git/ref/{ref}`, `GET`/`POST .../hooks`, `GET /user`, `GET /user/memberships/orgs/{org}`,
  `GET .../collaborators/{username}/permission`; and the webhooks for pull requests, reviews, review comments,
  review threads, check runs and workflow jobs.
- **Files:** the root `pom.xml` (the module `clients`), `clients/pom.xml` (packaging pom, grouping the clients;
  made by whichever of the two clients comes first), `clients/github/pom.xml` and its `README.md`,
  `clients/github/src/main/openapi/` (the extract, `SOURCE` with commit and digest, the MIT notice),
  `org.fuin.porelo.forge.github.GitHubClient` (the REST calls, `HttpClient` and base URI given, the token passed
  per call), `GitHubGraphQl` (the thread queries and mutations as text, their records written by hand),
  `GitHubWebhook` (reads a delivery by its event name, checks `X-Hub-Signature-256`), `GitHubError` (status and
  message of an error answer), and a log's redirect followed by hand; the client refuses an `HttpClient` that
  follows redirects itself, and sends the token only to the API base's own origin - scheme, host and port.
- **Steps:** the module and its extract; the build calls (refs, runs, jobs, log tail) first, since the adapters
  move onto them; then pull requests and reviews; then GraphQL threads; then webhooks.
- **Recordings, as for every cloud forge:** every build replays them alone, with no network and no credential, from
  files in `src/test/resources/` through an `HttpClient` that answers from them. They are made in a test
  organization of its own, separate from the project's, with one test repository and a GitHub App made only for it,
  by the module's recording test, which runs only by hand. It creates what it records under a prefix with the run's
  id - a branch, a pull request with a review by the second test user, comments, a reply and a resolved thread, a
  check run, a hook, and an Actions run dispatched from a two-step workflow kept in the test repository - and
  removes or closes all of it after, a failure and an interrupt too; it refuses to start over leftovers of its
  prefix and exits non-zero naming anything it could not remove. Before a recording is kept, accounts become
  fictional ones, addresses `example.org`, a signed URL loses its query, and a delivery is signed again with a test
  secret; a test fails on a recording that holds a token's shape, or a delivery whose signature does not verify with
  the test secret.
- **Three credentials, since GitHub splits them:** a GitHub App's installation token for check runs, minted per
  run from the secrets `LIVE_GITHUB_APP_ID` and `LIVE_GITHUB_APP_KEY`; the fine-grained token of a test user who
  administers the test repository, `LIVE_GITHUB_USER_TOKEN`, for everything else - refs, runs, jobs, logs, hooks,
  pull requests, comments, the account and its membership; and a second test user's, `LIVE_GITHUB_REVIEWER_TOKEN`,
  since nobody can approve their own pull request. All reach the run through the environment and are never
  written to a file.
- **Checked live on a schedule and by hand, never on a push:** a workflow `live.yml` (daily at an hour outside the
  working day, and by `workflow_dispatch`) runs the reading calls, in strict reading, against what the test
  repository keeps for it - a pull request with an approving review, comments and a resolved thread, a check run, a
  hook - and against an Actions run it dispatches from the kept workflow, its one write, since a run's log expires;
  it fails naming the call and the field that differ, and fails too when a call it checks answers an empty list. A difference is answered by refreshing:
  the recording test is run by hand, its diff read and committed with the change it needs.
- **Red first:** `GitHubClientTest` builds the client strict and calls `runs(owner, repo, sha)` against the
  replayed answer with one field renamed, which must fail naming that field; then a redirect to another origin must
  arrive there without the `Authorization` header.

## Acceptance

- Every call is tested against GitHub's recorded answers, with no network; a recorded answer with a changed field
  makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; the client sends its requests through the `HttpClient` its caller passes.
- A webhook delivery whose signature does not match its secret is refused before its JSON is parsed, its raw
  body read only up to a bound.
- A log's redirect to another origin carries no token, seen in a test that fails when it does; an `HttpClient`
  that follows redirects itself is refused when the client is built.

## To be checked

- How a GraphQL answer is recorded and replayed as the REST ones are, and whether GitHub's schema file may be read
  to check the hand-written queries at build time.
