# FC11 — A Pull Request's Commits On Forgejo

**Status:** Estimated (Accepted and Estimated by the operator, through Agent Coordinator,
2026-10-31); blocked by
[FC02](README.md#fc02).
**Size:** S - one call added to the `forgejo` module, recorded against a Forgejo in podman; no record other
repositories read changes.

**What must be true.** A caller that writes a code review on Forgejo reads a pull request's commits page by page, and
for each commit its revision, the author's and the committer's account as Forgejo links them (a stable ID, or none
when Forgejo links no account), and Forgejo's verdict on its signature, through the `forgejo` module, in Forgejo's
own terms (the operator's answer of 2026-10-31); a cap Forgejo puts on the list is said.

## Plan

- **The operation:** `GET /repos/{owner}/{repo}/pulls/{index}/commits` of the pinned Forgejo description, added to the
  extract's operations and the `check-extract` execution of `clients/forgejo/pom.xml`. Its answer is a list of
  `Commit`: `sha`, `author` and `committer` as Forgejo's `User` with its `id` (none when Forgejo links no account), and
  `commit.verification` as `PayloadCommitVerification` with `verified`, `reason`, `signature` and `signer`.
- **`ForgejoClient.pullCommits(token, owner, repo, index, page, limit)`:** sends `verification=true` and
  `files=false`, since the caller reads the signature and not the files, and returns the commits with the page's
  `X-Total-Count` and `X-HasMore`, so the cap is said: Forgejo answers at most its configured maximum per page, 50 by
  default, whatever `limit` asks.
- **Recording:** the Forgejo recorder in podman opens a pull request with one commit by a Forgejo account and one by
  an email no account has, and records its commits with a `limit` above the maximum, so the cap is seen.
- **Red first:** `ForgejoClientTest` reads the recorded answer with one field of `verification` renamed, which must
  fail naming it in the strict reading, and a commit with `author` `null` reads with no account.

## Acceptance

- `pullCommits` is tested against Forgejo's recorded answer, with no network, and live against a Forgejo in podman.
- A `limit` above Forgejo's maximum is seen to come back capped, and the client says so through `X-HasMore`.

## To be checked

- Whether Forgejo 17 sends `X-HasMore` on this list, and whether a signature check needs a key the recorder must set
  up first; without one every commit reads unverified, which the recording then shows.
