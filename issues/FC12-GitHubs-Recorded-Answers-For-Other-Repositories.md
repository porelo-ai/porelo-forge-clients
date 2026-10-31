# FC12 — GitHub's Recorded Answers For Other Repositories

**Status:** Implementing.
**Size:** S - one test class made public, its recordings moved, one jar execution and a README section; no call and
no recording changes.

**What must be true.** A test in another repository, such as the adapters', replays GitHub's real answers as this
repository recorded them, through an `HttpClient`, from a `tests` jar of `porelo-forge-client-github` (classifier
`tests`) that holds the recordings at a stable resource path and the replaying `HttpClient`, installed and published
like the main jar (Agent Adapters, 2026-10-31).

## Plan

Read in today's code: `ReplayHttpClient` is a package-private test class of `org.fuin.porelo.forge.github`, with
package-private `answer`, `otherwise`, `recording` and the fields `requests` and `redirects`; it reads
`/recordings/<name>.json`, a path at the root that the Forgejo module's recordings use as well. No jar of test
classes is built. The adapters' `StandInTest` needs to change one field of a recorded review comment before it is
replayed, to test that a changed field is held back.

- **The replaying client** moves to `org.fuin.porelo.forge.github.replay`, public and `@NullMarked`:
  `ReplayHttpClient` with `answer(Answer)`, `answer(status, headers, body)`, `otherwise(responder)`,
  `recording(name)`, `requests()` (a copy of the requests sent so far), `redirects(policy)`, and the static
  `recorded(name)`, which returns a recording's answers as a list a caller may change before it queues them; the
  record `Answer` is public. Only synchronous `send` is offered, as today.
- **The recordings** move from `src/test/resources/recordings/` to
  `src/test/resources/org/fuin/porelo/forge/github/recordings/`, so they cannot meet another jar's recordings on one
  classpath; the recorder's execution writes there, and the client's own tests read there.
- **The jar.** `clients/github/pom.xml` gets a `maven-jar-plugin` execution `test-jar` that packs only
  `org/fuin/porelo/forge/github/replay/**` and `org/fuin/porelo/forge/github/recordings/**`: the recorder, the
  scrubber, the fixture and the tests stay out. It is installed and deployed beside the main jar.
- **The README** of the module says how another repository depends on the jar (`<classifier>tests</classifier>`,
  scope `test`), names the resource path and the recordings' file format, and says the recordings change only when
  this repository records again.
- **Red first:** a test in a package of its own, `org.fuin.porelo.forge.github.replaytest`, which sees only public
  API, replays `reviewCommentsOf` through `GitHubClient` with `ReplayHttpClient.recording`, then the same recording
  with one comment's `line` changed through `recorded` and `answer`, and reads the changed line; it does not compile
  until the client is public.

## Acceptance

- A test outside the client's package replays a recorded answer through `GitHubClient`, and a changed copy of it.
- The `tests` jar holds the replaying client and every recording, and no other class; seen by listing the installed
  jar.
- The client's own tests pass unchanged in what they check, on the moved recordings, with no network.
