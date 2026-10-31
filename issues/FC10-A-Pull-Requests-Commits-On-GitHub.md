# FC10 — A Pull Request's Commits On GitHub

**Status:** Analyzed (Accepted by the operator, through Agent Coordinator, 2026-10-31).
**Size:** S - one call added to the `github` module, its recording and live check; no record other repositories read
changes.

**What must be true.** A caller that writes a code review on GitHub reads a pull request's commits page by page, and
for each commit its revision, the author's and the committer's account as GitHub links them (a stable ID, or none
when GitHub links no account), and GitHub's verdict on its signature, through the `github` module, in GitHub's own
terms (the operator's answer of 2026-10-31).

## Plan

- **The operation:** `GET /repos/{owner}/{repo}/pulls/{pull_number}/commits`, added to the extract's operations in
  the `check-extract` execution of `clients/github/pom.xml` and to the recorded args; its answer is a list of the
  extract's `commit`, which `compare` already reads, so no schema is added: `sha`, `author` and `committer` as the
  `oneOf` of `null`, `simple-user` and an empty object that a commit by no GitHub account needs, with the account's
  `id`, and `commit.verification` with `verified`, `reason`, `signature`, `payload` and `verified_at`.
- **`GitHubClient.pullCommits(token, owner, repo, number, page, perPage)`:** returns `List<Commit>`, read in the
  client's strictness. Its Javadoc and `clients/github/README.md` say what GitHub's description says: the list stops at
  250 commits, and a page past them is empty, so a caller that needs more reads the branch's commits instead.
- **Recording and live check:** `GitHubOperations.pullCommits` after `pull`, on the fixture's pull request, whose
  commits include the one by an unknown account that `compare` already records; `requireUsefulResult` wants at least
  one commit. `GitHubLiveCheck` reads the kept pull request's commits.
- **Red first:** `GitHubClientTest` reads the recorded answer with a commit's `author` as `null` and as `{}`, and with
  one field of `verification` renamed, which must fail naming it in the strict reading.

## Acceptance

- `pullCommits` is tested against GitHub's recorded answer, with no network: the revision, both accounts' ids or none,
  and the signature's verdict are read.
- A commit by no GitHub account reads with no account, as `null` and as an empty object.

## To be checked

- At this issue's live recording, left open by [FC08](README.md#fc08): whether GitHub answers `GET /user` for a GitHub
  App's installation token; whether `compare` takes a branch with a slash as the client encodes it, `%2F`; a `Bot`
  author's `databaseId` in GraphQL; and general comments read since a time, seen with one written before it and one
  after.
- Whether GitHub signs the fixture's commits made through the contents API (`verified` `true`, reason `valid`), so the
  recording holds a verified and an unverified signature.
