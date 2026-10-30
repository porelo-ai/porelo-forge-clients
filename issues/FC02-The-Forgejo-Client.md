# FC02 — The Forgejo Client

**Status:** Idea.

**What must be true.** A caller reads and writes what a code review and a build need on Forgejo through the
`forgejo` module, `org.fuin.porelo:porelo-forge-client-forgejo`: pull requests, reviews and their comments,
suggestions, commit statuses and Actions runs, jobs and their logs, git refs, webhooks, and the account and its
organization membership - in Forgejo's own terms, knowing nothing of Porelo.

## Acceptance

- Every call is tested against answers recorded from a real Forgejo, with no network; a recorded answer with a
  changed field makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; a forge signed by a private certificate authority is reached with the `HttpClient` its caller passes.

## To be checked

- Forgejo's published OpenAPI description: its license, how complete it is for the paths used, and how records are
  generated from it at build time with the smallest dependencies.
- Which Forgejo version the answers are recorded from, and how that version is pinned.
