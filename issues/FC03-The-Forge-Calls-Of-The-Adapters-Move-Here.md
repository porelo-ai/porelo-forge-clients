# FC03 — The Forge Calls Of The Adapters Move Here

**Status:** Accepted.

**What must be true.** The `github` and `forgejo` clients make every call to GitHub and Forgejo that Porelo's build
adapters make today, with the same requests and the same handling of every answer, and are published as snapshots,
so a caller can drop its own HTTP calls and keep only the translation into its own types.

## The shape

- **The calls:** a branch's head, the runs for a commit, their jobs, paged on GitHub (the adapter and the client ask
  Forgejo's list with no page), the tail of a job's log, redirects followed by hand (GitHub sends a log to a second
  host), opening a pull request on Forgejo, and the error answers by status and text. A client returns native records:
  a run, a job, a log tail, a pull request's address.
- **What stays with the caller:** everything that names Porelo - its entry points, its build types, the choice of
  jobs and the refusals with their codes.
- **The transport:** a client takes a `java.net.http.HttpClient` from its caller, which builds it with the trusted
  certificate authorities it knows (redirects never followed by the client itself, 15 s to connect). The request
  timeout (60 s), the window of a log's tail and the lower-casing of header names belong to the clients.

## What stands

- **In the clients already:** `ref` and `branch` for a branch's head, `runs`, `jobs`, `jobLogs` with its redirects
  followed by hand, Forgejo's `createPull`, and the 60 s request timeout.
- **Still with the adapters:** `GitHubReader` and `ForgejoReader` in `porelo-adapters` make these calls through their
  own `Http`.
- **Not kept yet:** no test here takes the answers the adapters' tests record. `.github/workflows/build.yml` publishes
  with no read-back, and starts no Build on a push of only documents, issues or workflows. A client keeps a log's last
  1 MiB, and the adapters 64 KiB, `Build.TAIL`.

## Acceptance

- For each of these calls, the answers `porelo-adapters`' tests record today pass through the client here and yield
  the same fields; a changed answer makes a test here fail by the field that changed.
- Every push to `main` publishes the clients as a snapshot, the version read back from the snapshot repository.
- Nothing of Porelo comes with the calls: no contract type, no SARIF, no Porelo artifact in any module's
  dependencies.

## To be checked

- Whether the clients send the adapters' headers and treat each error answer as they do; only paths, paging and
  redirects have been compared.
