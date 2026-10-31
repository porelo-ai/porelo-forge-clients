# FC13 — GitHub's Nulls For A Deleted Fork And An Empty Review

**Status:** Analyzed (Accepted by the operator, through Agent Coordinator, 2026-10-31).
**Size:** M - two schema corrections in the `github` extract, whose generated records other repositories read: a
field that was never null becomes nullable.

**What must be true.** A caller reads every pull request and review GitHub answers through the `github` module, also a
pull request from a deleted fork, whose `head.repo` GitHub sends as null, and a review whose `body` is null; such an
item reads with that value as null instead of failing the whole read (Agent Adapters' live test, 2026-10-31).

## Plan

- **The extract:** the pinned description has `head.repo` of `pull-request` and of `pull-request-simple` as a required
  `repository`, and a review's `body` as a required `string`, so the generated records refuse `null`. Two `--set`
  corrections in the recorded args, named in `info.x-extracted-from.set` and in `clients/github/README.md` like the
  label's `archived_by`: `head.repo` becomes an `anyOf` of `null` and `repository` in both schemas, and
  `pull-request-review.body` becomes `["string", "null"]`. Nothing else changes; `base.repo` stays required, since a
  pull request's base is the repository asked about.
- **What callers see:** `PullRequestHead.repo()`, `PullRequestSimpleHead.repo()` and `PullRequestReview.body()` are
  `@Nullable`; the README says when GitHub sends them as `null`. A caller compiled with NullAway is told at its next
  build where it read them without a check.
- **Red first:** `GitHubClientTest` reads the recorded `pulls`, `pull` and `reviews` answers with `head.repo` and
  `body` set to `null`, strictly and leniently; each read fails today on the whole list.

## Acceptance

- A pull request from a deleted fork and a review with no text read, with that value `null`, from GitHub's recorded
  answers changed only there, with no network.
- Every other recorded answer reads as before.

## To be checked

- Whether Agent Adapters' answer had `head.repo` as `null` or left it out, and whether `head.user` came too; the
  correction takes both forms if so.
