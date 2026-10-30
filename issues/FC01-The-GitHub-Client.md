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
- **Files:** `clients/pom.xml` (packaging pom, grouping the clients), `clients/github/pom.xml`,
  `clients/github/src/main/openapi/` (the extract, `SOURCE` with commit and digest, the MIT notice),
  `org.fuin.porelo.forge.github.GitHubClient` (the REST calls, `HttpClient` and base URI given, the token passed
  per call), `GitHubGraphQl` (the thread queries and mutations as text, their records written by hand),
  `GitHubWebhook` (reads a delivery by its event name, checks `X-Hub-Signature-256`), `GitHubError` (status and
  message of an error answer), redirects followed by hand, and only to the hosts GitHub names for a log.
- **Steps:** the module and its extract; the build calls (refs, runs, jobs, log tail) first, since the adapters
  move onto them; then pull requests and reviews; then GraphQL threads; then webhooks.
- **Red first:** `GitHubClientTest` reads the recorded answer of `GET .../actions/runs` with one field renamed and
  must fail naming that field.

## Acceptance

- Every call is tested against GitHub's recorded answers, with no network; a recorded answer with a changed field
  makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; the client sends its requests through the `HttpClient` its caller passes.
- A webhook delivery whose signature does not match its secret is refused before its body is read.

## Open questions

- **GitHub Enterprise Server too, or github.com only?** Its descriptions are separate files per version; supporting
  it means a second extract and recordings from a running server. Recommendation: github.com only for now.

## To be checked

- How a GraphQL answer is recorded and replayed as the REST ones are, and whether GitHub's schema file may be read
  to check the hand-written queries at build time.
