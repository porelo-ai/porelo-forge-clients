# FC01 — The GitHub Client

**Status:** Analyzed; blocked by [FC04](README.md#fc04).
**Size:** L - one module, but twenty REST operations, GraphQL beside REST, webhooks and the recordings of all of them.

**What must be true.** A caller reads and writes what a code review and a build need on GitHub through the `github`
module, `org.fuin.porelo:porelo-forge-client-github`: pull requests, reviews, review comments and threads (resolved
only through GraphQL), suggestions, check runs and Actions runs, jobs and their logs, git refs, webhooks, and the
account and its organization membership - in GitHub's own terms, knowing nothing of Porelo.

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
- **Recordings, as for every cloud forge:** every build replays them alone, with no network and no credential.
  They are made from a test organization of the project's own through the module's recording test, which runs only
  with a token in its environment, and replayed from files in `src/test/resources/` by an `HttpClient` that answers
  from them; webhook deliveries recorded from a test repository's hook. Before a recording is kept, accounts become
  fictional ones, addresses `example.org`, a signed URL loses its query, and a delivery is signed again with a test
  secret; a test fails on a recording that holds a token's shape, or a delivery whose signature does not verify
  with the test secret.
- **Checked live on a schedule and by hand, never on a push:** a workflow `live.yml` (daily at an hour outside the
  working day, and by `workflow_dispatch`) runs the same calls against the test organization in strict reading, with
  the GitHub App's key and id as the secrets `GITHUB_LIVE_APP_KEY` and `GITHUB_LIVE_APP_ID`, an installation token
  minted per run and never written to a file; it fails naming the call and the field that differ. A difference is
  answered by refreshing: the recording test is run by hand, its diff read and committed with the change it needs.
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

## Open questions

- **GitHub Enterprise Server too, or github.com only?** Its descriptions are separate files per version; supporting
  it means a second extract and recordings from a running server. Recommendation: github.com only for now.
- **Which account and organization to record from?** Check runs and organization membership need a repository in
  an organization and, for check runs, a GitHub App installed there. Recommendation: a test repository in the
  project's own organization, with a GitHub App made only for it.

## To be checked

- How a GraphQL answer is recorded and replayed as the REST ones are, and whether GitHub's schema file may be read
  to check the hand-written queries at build time.
