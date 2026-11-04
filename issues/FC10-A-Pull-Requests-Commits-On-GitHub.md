# FC10 — A Pull Request's Commits On GitHub

**Status:** Implementing.
**Size:** S - one call added to the `github` module, its recording and live check; no record other repositories read
changes.

**What must be true.** A caller that writes a code review on GitHub reads a pull request's commits page by page, and
for each commit its revision, the author's and the committer's account as GitHub links them (a stable ID, or none
when GitHub links no account), and GitHub's verdict on its signature, through the `github` module, in GitHub's own
terms.

## Plan

- **The operation:** `GET /repos/{owner}/{repo}/pulls/{pull_number}/commits`, added to the extract's operations in
  the `check-extract` execution of `clients/github/pom.xml`; its answer is a list of the
  extract's `commit`, which `compare` already reads, so no schema is added: `sha`, `author` and `committer` as the
  `oneOf` of `null`, `simple-user` and an empty object that a commit by no GitHub account needs, with the account's
  `id`, and `commit.verification` with `verified`, `reason`, `signature`, `payload` and `verified_at`.
- **`GitHubClient.pullCommits(token, owner, repo, number, page, perPage)`:** returns `Page<Commit>`, like every paged
  list of the client, read in the client's strictness. Its Javadoc and `clients/github/README.md` say what GitHub's
  description says: the list stops at 250 commits, so a caller that needs more reads the branch's commits instead.
- **Recording and live check:** `GitHubOperations.pullCommits` after `pull`, on the fixture's pull request;
  `GitHubScenario.requireUsefulResult` wants at least one commit. `GitHubLiveCheck` reads the kept pull request's
  commits.
- **What the fixture holds:** its pull request has one commit, by the test user and unsigned. The commit with GitHub's
  own committer, recorded as `fixture-committer-1`, and with `verified` `true` is the base commit in the `compare`
  recording, not one of the pull request's.
- **Red first:** `GitHubClientTest` reads the commits of the recorded `compare` answer, with a commit's `author` as
  `null` and as `{}`, and with `verification.verified` renamed, which fails naming the missing field in both readings.
- **Extract:** fetching again from the kept source and digest, with its kept settings, reproduces the extract byte for
  byte; the only change is the one path.

## Acceptance

- `pullCommits` is tested against GitHub's recorded answer, with no network: the revision, both accounts' ids or none,
  and the signature's verdict are read.
- A commit by no GitHub account reads with no account, as `null` and as an empty object.

## To be checked

- The live part is built once GitHub's API answers normally, since an answer recorded while it is degraded proves
  nothing: `pullCommits` in `GitHubOperations`, with its `GitHubScenario.requireUsefulResult` use, its recording, and
  its read in `GitHubLiveCheck`. Without a recording the `GitHubOperations` entry fails `GitHubRecordingTest`, so it
  comes with the recording.
- At this issue's live recording, left open by [FC08](README.md#fc08): whether GitHub answers `GET /user` for a GitHub
  App's installation token; whether `compare` takes a branch with a slash as the client encodes it, `%2F`; a `Bot`
  author's `databaseId` in GraphQL; and general comments read since a time, seen with one written before it and one
  after.
- How the recording comes to hold a verified and an unverified signature, and a commit by an account the scrubber was
  not told about, since the fixture's commit made through the contents API reads `verified` `false`, reason `unsigned`.
- Whether a page past the 250 commits is empty, which GitHub's description does not say.
