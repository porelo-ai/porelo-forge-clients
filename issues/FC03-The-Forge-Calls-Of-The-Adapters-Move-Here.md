# FC03 — The Forge Calls Of The Adapters Move Here

**Status:** Idea.

**What must be true.** Every HTTP call that `porelo-adapters` makes to GitHub or Forgejo today is a call of the
`github` or `forgejo` client here, with the same requests and the same handling of every answer, so the adapters keep
only the translation into Porelo's contracts.

## The shape

- **What moves:** the forge calls of the GitHub and Forgejo readers and the decoding of their native answers - a
  branch's head, the runs for a commit, their jobs with paging, the tail of a job's log, redirects followed by hand
  (GitHub sends a log to a second host), opening a pull request on Forgejo, and the error answers by status and
  text. A client returns native records: a run, a job, a log tail, a pull request's address.
- **What stays in `porelo-adapters`:** everything that names Porelo - the reader's entry point, the target turned
  into owner and name, the choice of jobs, the mapping onto Porelo's build types and the refusals with their codes,
  and the contract suite every adapter passes.
- **The transport:** a client takes a `java.net.http.HttpClient` from its caller, which builds it with the
  account's trusted certificate authorities (redirects never followed by the client itself, 15 s to connect). The
  request timeout (60 s), the window of a log's tail and the lower-casing of header names go with the clients.
- **For `porelo-adapters`' artifacts:** its two readers keep their packages, paths and contract and carry the
  client jars inside them; its HTTP helper shrinks to the certificate part. It builds against the clients'
  published snapshot, so the clients publish before the adapters' change is pushed.

## Acceptance

- For each call that moves, the answers the adapters' tests record today pass through the client here and yield
  the same fields; a changed answer makes a test here fail by the field that changed.
- Nothing of Porelo moves with the calls: no contract type, no SARIF, no Porelo artifact in any module's
  dependencies.

