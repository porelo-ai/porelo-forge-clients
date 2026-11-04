# FC11 — A Pull Request's Commits On Forgejo

**Status:** Review.
**Size:** S - one call added to the `forgejo` module, recorded against a Forgejo in podman; no record other
repositories read changes.

**What must be true.** A caller that writes a code review on Forgejo reads a pull request's commits page by page, and
for each commit its revision, the author's and the committer's account as Forgejo links them (a stable ID, or none
when Forgejo links no account), and Forgejo's verdict on its signature, through the `forgejo` module, in Forgejo's
own terms; a cap Forgejo puts on the list is said.

## Plan

- **The operation:** `GET /repos/{owner}/{repo}/pulls/{index}/commits` of the pinned Forgejo description, added to the
  extract's operations and the `check-extract` execution of `clients/forgejo/pom.xml`. Its answer is a list of
  `Commit`: `sha`, `author` and `committer` as Forgejo's `User` with its `id` (none when Forgejo links no account), and
  `commit.verification` as `PayloadCommitVerification` with `verified`, `reason`, `signature` and `signer`.
- **`ForgejoClient.pullCommits(token, owner, repo, index, page, limit)`:** sends `verification=true` and
  `files=false`, since the caller reads the signature and not the files, and returns a `Page<Commit>` that also brings
  the caller the page's `X-Total-Count` and `X-HasMore`, so the cap is said: Forgejo answers at most its configured
  maximum per page, 50 by default, whatever `limit` asks.
- **`Page`:** gets a `total` from `X-Total-Count`, with its two-argument constructor kept, and `more` is true on
  `X-HasMore: true`. Reckoning `page * limit` from a `limit` above the cap alone would report the end too early; measured
  on this list, Forgejo 17 also links a next page there, so a caller sees the end wrongly only where neither is sent.
- **Recording:** the Forgejo recorder in podman opens a pull request with one commit by a Forgejo account and one by
  an email no account has, and a second of 51 commits read with `limit=100`, so the cap is seen. A signature check needs
  no key set up: an unsigned commit reads `verified` false, reason `gpg.error.not_signed_commit`.
- **Red first:** `ForgejoClientTest` reads the recorded answer with one field of `verification` renamed, which must
  fail naming it in the strict reading, and a commit with `author` `null` reads with no account.

## Acceptance

- `pullCommits` is tested against Forgejo's recorded answer, with no network, and live against a Forgejo in podman.
- A `limit` above Forgejo's maximum is seen to come back capped, and the client says so through `X-HasMore`.
