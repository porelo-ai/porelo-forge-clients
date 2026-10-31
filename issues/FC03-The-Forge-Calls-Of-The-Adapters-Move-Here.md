# FC03 — The Forge Calls Of The Adapters Move Here

**Status:** Accepted; blocked by [FC02](README.md#fc02).

**What must be true.** The `github` and `forgejo` clients make every call to GitHub and Forgejo that Porelo's build
adapters make today, with the same requests and the same handling of every answer, and are published as snapshots,
so a caller can drop its own HTTP calls and keep only the translation into its own types.

## The shape

- **The calls:** a branch's head, the runs for a commit, their jobs with paging, the tail of a job's log, redirects
  followed by hand (GitHub sends a log to a second host), opening a pull request on Forgejo, and the error answers by
  status and text. A client returns native records: a run, a job, a log tail, a pull request's address.
- **What stays with the caller:** everything that names Porelo - its entry points, its build types, the choice of
  jobs and the refusals with their codes.
- **The transport:** a client takes a `java.net.http.HttpClient` from its caller, which builds it with the trusted
  certificate authorities it knows (redirects never followed by the client itself, 15 s to connect). The request
  timeout (60 s), the window of a log's tail and the lower-casing of header names belong to the clients.

## Acceptance

- For each of these calls, the answers `porelo-adapters`' tests record today pass through the client here and yield
  the same fields; a changed answer makes a test here fail by the field that changed.
- Every push to `main` publishes the clients as a snapshot, the version read back from the snapshot repository.
- Nothing of Porelo comes with the calls: no contract type, no SARIF, no Porelo artifact in any module's
  dependencies.
