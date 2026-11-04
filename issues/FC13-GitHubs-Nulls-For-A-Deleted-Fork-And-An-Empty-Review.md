# FC13 — GitHub's Nulls For A Deleted Fork And An Empty Review

**Status:** Review.
**Size:** M - schema corrections in the `github` extract, whose generated records other repositories read: a
field that was never null becomes nullable.

**What must be true.** A caller reads every pull request and review GitHub answers through the `github` module, also a
pull request from a deleted fork, whose `head.repo` GitHub sends as null, with `head.user` and `head.label` too when the
fork's account is gone, and a review whose `body` is null; such an item reads with those values as null instead of
failing the whole read.

## Plan

- **What GitHub answers,** measured on 800 closed public pull requests of three repositories: 149 had `head.repo` as
  `null`, none left it out; 3 also had `head.user` and `head.label` as `null`, their fork's account being gone; `ref` and
  `sha` stayed, and `base` was whole in all. A review's `body` was seen as `""`; `null` was not seen in the few read, and
  stays corrected since a null would fail the whole list.
- **The extract:** `--set` corrections when the extract is fetched again, named in `info.x-extracted-from.set` and in
  `clients/github/README.md`: in both schemas `head.repo` and `head.user` become an `anyOf` of `null` and their schema
  (`pull-request-simple`'s `head.user` already is one) and `head.label` becomes `["string", "null"]`, and
  `pull-request-review.body` becomes `["string", "null"]`. The fields stay required, so a left-out one still fails.
- **What callers see:** `repo()`, `user()` and `label()` of `PullRequestHead` and `PullRequestSimpleHead` and
  `PullRequestReview.body()` are `@Nullable`; a caller compiled with NullAway is told at its next build where it read
  them without a check.
- **Red first:** `GitHubClientTest` reads the recorded `pulls`, `pull` and `reviews` answers with those values set
  to `null`, strictly and leniently; each read fails before the correction on the whole list.

## Acceptance

- A pull request from a deleted fork and a review with no text read, with that value `null`, from GitHub's recorded
  answers changed only there, with no network.
- Every other recorded answer reads as before.
