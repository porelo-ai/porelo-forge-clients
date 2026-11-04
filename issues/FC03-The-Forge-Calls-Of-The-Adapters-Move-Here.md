# FC03 — The Forge Calls Of The Adapters Move Here

**Status:** Analyzed.
**Size:** M - tests in both client modules from answers of another repository, one workflow step and two small client
gaps; no client record changes, and the adapters' own move is theirs.

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

## Plan

Read in today's code: `GitHubClient`, `ForgejoClient` and `.github/workflows/build.yml` here, `GitHubReader`,
`ForgejoReader` and `Http` in `porelo-adapters`, and all 99 answer files of its `adapters/*/src/test/resources/recordings`.

- **The calls are all here.** `GitHubReader` asks `git/ref/heads/<branch>`, the repository, `actions/runs?head_sha`,
  `actions/runs/<id>/jobs` and `actions/jobs/<id>/logs`; `ForgejoReader` asks the same runs, jobs and logs, plus
  `branches/<branch>`, the repository, `pulls?state=open` and `POST pulls`. Each has its client call, and no new
  operation enters an extract.
- **Requests that differ.** GitHub's `X-GitHub-Api-Version` is `2022-11-28` in the adapter and `2026-03-10` in the client;
  see *Open questions*. Authorization and `Accept` are the same on both forges, and paths and paging were compared before.
- **Answers that differ.** GitHub's recorded success answers are cut to the fields the adapter reads: a ref holds only
  `object`, of the four its schema requires, and a run holds four of the 26. The client refuses a missing required field
  in either strictness, so they cannot pass through it as they are; see *Open questions*. Forgejo's description requires
  nothing, and its recordings are mostly whole, so they are expected to pass; whether the strict reading takes each is
  the first thing the test shows.
- **Error answers.** The bodies are `{"message": ...}` and come through `GitHubException` and `ForgejoException`, which
  carry the status and Forgejo's or GitHub's own words, scrubbed. A caller can tell every case the adapters tell: 401,
  403, 404, 429 and a rate limit by `rateLimited()` and `delay()`, a redirect by its status, and no answer by
  `delivery()`. `ForgejoReader` also reads a job's log 404 by the text `job has not been executed yet`; a test holds that
  this text reaches the caller.
- **The log's tail.** A client keeps the last 1 MiB and the adapters 64 KiB. A caller cuts what it gets, so the client
  stays as it is.
- **Publishing.** `.github/workflows/build.yml` gets a step after `deploy` that probes the snapshot index for the exact
  version with retries, and fails naming what it asked and got.
- **Tests.** `GitHubAdapterAnswersTest` and `ForgejoAdapterAnswersTest` hold copies of the adapters' answers under
  `src/test/resources/adapter-answers/`, with no Porelo type, and assert each field the adapter reads and each error by
  status, text and delay. Red first: each test with one field changed in its answer, which must fail by that field.

## Acceptance

- For each of these calls, the answers `porelo-adapters`' tests record today pass through the client here and yield
  the same fields; a changed answer makes a test here fail by the field that changed. Answers cut below what the client
  requires are settled as *Open questions* decides.
- Every push to `main` that starts a Build publishes the clients as a snapshot, the version read back from the snapshot
  repository.
- Nothing of Porelo comes with the calls: no contract type, no SARIF, no Porelo artifact in any module's
  dependencies.

## Open questions

- **GitHub's cut answers.** Recommended: for GitHub's success answers, the test reads the client's own whole recordings of
  the same calls and asserts the fields the adapter reads, and it takes the adapter's answers as they are only for
  errors; that needs no live GitHub. The alternative is recording the adapters' cases whole from GitHub, which is the
  acceptance as written and needs a run against real failures and a rate limit. Or the client could accept missing fields,
  which gives up the strictness that finds a changed answer, and is not recommended.
- **GitHub's API version.** Recommended: the client's `2026-03-10` stays, since its records are generated from that
  description, and the adapter takes it when it moves, a small departure from "the same requests". The alternative, the
  client sending `2022-11-28`, reads answers its records were not made from.
- **Pushes of only documents.** The workflow starts no Build on those. Recommended: they publish nothing, as the
  acceptance above now says, since no client changed.
