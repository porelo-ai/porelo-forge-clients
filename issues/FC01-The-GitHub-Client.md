# FC01 — The GitHub Client

**Status:** Idea.

**What must be true.** A caller reads and writes what a code review and a build need on GitHub through the `github`
module, `org.fuin.porelo:porelo-forge-client-github`: pull requests, reviews, review comments and threads (resolved
only through GraphQL), suggestions, check runs and Actions runs, jobs and their logs, git refs, webhooks, and the
account and its organization membership - in GitHub's own terms, knowing nothing of Porelo.

## Acceptance

- Every call is tested against GitHub's recorded answers, with no network; a recorded answer with a changed field
  makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; the client sends its requests through the `HttpClient` its caller passes.

## To be checked

- GitHub's published OpenAPI description: its license, how complete it is for the paths used, and how records are
  generated from it at build time with the smallest dependencies.
- Which GraphQL queries the review threads need, and whether their answers can be recorded as the REST ones are.
