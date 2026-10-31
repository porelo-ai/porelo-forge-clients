# FC08 — Comments, Threads And Versions Through The Clients

**Status:** Review; blocked by [FC02](README.md#fc02).
**Size:** L - seventeen calls on two forges and a GraphQL query of its own, as many as the GitHub client began with,
and GitHub recorded again in a session with the operator.

**What must be true.** A caller that writes a code review on GitHub or Forgejo reads, writes, edits and deletes a pull
request's general comments, reading only those since a given time; edits and deletes a review comment; reads one GitHub
review thread with its state and its resolver by its id; reads one review's comments and a pull request's merge base;
finds the open pull request of a branch; and reads a Forgejo server's version, through the `github` and `forgejo` modules, in each forge's own terms. Where
Forgejo's API cannot do what GitHub's does, the client says so.

## Plan

Read against Core's folded Review1 draft (`review1-contract-draft.md`, 2026-10-31, its 2.4 and 4.1-4.2) and today's
code. Every path below is in the pinned descriptions; their digests were checked on 2026-10-31.

- **GitHub, eight REST calls:** `issueComments(owner, repo, number, since, page, perPage)`, `createIssueComment`,
  `updateIssueComment`, `deleteIssueComment` (`.../issues/{issue_number}/comments`, `.../issues/comments/{comment_id}`);
  `updateReviewComment`, `deleteReviewComment` (`.../pulls/comments/{comment_id}`); `reviewCommentsOf(owner, repo,
  number, reviewId, page, perPage)` (`.../reviews/{review_id}/comments`, which `SubmitReview` reads back); and
  `compare(owner, repo, base, head)` (`.../compare/{basehead}`, with `per_page=1`, since only `merge_base_commit` is
  wanted and the answer lists commits and files).
- **A pull request by its branch** (Core's Review1 draft after agy's reading, 10:16Z: `FindChange(target, branch)`):
  GitHub's `pullsOfHead(owner, repo, head, state, page, perPage)` is the pull request list with its `head=owner:branch`
  filter, which the extract already has. Forgejo's list has no such filter in the pinned description's list call;
  Forgejo gets `pullOfBranches(owner, repo, base, head)` (`GET .../pulls/{base}/{head}`) if the pinned description
  has it, otherwise the client reads the open list and the README says so. The draft's `TokenAccount` needs no new
  call: both clients have `user()` (`GET /user`). The draft's `BaseMoved` needs none either: `pull` names the base
  branch and commit, and `compare` the merge base.
- **GitHub GraphQL:** a query `reviewThread(token, id)`, one thread by its node ID with the first page of its
  comments. All three thread queries select `resolvedBy { id databaseId login }`, the thread's `diffSide` and
  `startDiffSide`, and for each comment `id`, `databaseId`, `createdAt` and its author's `login` with `id` and
  `databaseId` of a `User` or `Bot`; an author that is neither stays without an ID, and the records say so with
  `@Nullable`.
- **Forgejo, seven calls and `pullOfBranches`:** `issueComments(owner, repo, index, since)` (Forgejo's list takes `since` and `before`
  and no paging; the whole list is one answer, bounded as [FC09](README.md#fc09) bounds every answer),
  `createIssueComment`, `editIssueComment`, `deleteIssueComment` (`.../issues/comments/{id}`);
  `createReviewComment` (`POST .../pulls/{index}/reviews/{id}/comments`), `deleteReviewComment`
  (`DELETE .../reviews/{id}/comments/{comment}`); and `version()` (`GET /version`). Measured against Forgejo 17.0.1
  on 2026-10-31: `PATCH .../issues/comments/{id}` edits a review comment, so `editIssueComment` is its edit;
  `DELETE` there answers 204 and keeps it; a submitted review, even another account's, takes a new comment; the
  general comment list holds no review comments.
- **Files:** each module's `pom.xml` (the operations for `check-extract`), the extracts fetched again with the new
  operations, `GitHubClient`, `GitHubGraphQl`, `ForgejoClient`, the operation enums of both recorders and live
  checks, both READMEs; the generated records are never edited.
- **Steps, in the order the review stand-in needs them:** the GitHub thread by its id and the extended thread
  queries; the GitHub comment calls; compare; Forgejo's calls and version; the recordings, Forgejo's from its
  container, GitHub's in one session with the operator, as for the first recording.
- **Red first:** `GitHubRecordingTest` gains the operation `reviewThread` before the client has it, so the build
  fails naming it; then the recorded thread's `resolvedBy` must read back with its `databaseId`.

## Acceptance

- Every new call is tested against answers recorded from GitHub and from the pinned Forgejo, in the strict reading;
  a recorded answer with a changed field fails by that field's name, and the live checks read the new reading calls.
- A resolved thread read by its id names its resolver's stable ID; a comment by a user names the author's stable ID.
- Comments read with `since` hold none older than it, seen with a comment written before and one after.
- What Forgejo cannot do (an edit of a review comment, if measured so; thread state; replies) is not offered, and
  its README names each.

## To be checked

- Whether GitHub answers `GET /user` for a GitHub App's installation token, which the draft's `TokenAccount` would
  need if Core writes with the App; GitHub documents it for user tokens only.
- Whether GitHub's `compare` takes a branch with a slash as the client encodes it, `%2F`, measured at the next live
  recording; Forgejo's `pullOfBranches` takes it encoded and as is (measured 2026-10-31).
- A `Bot` author's `databaseId` in GraphQL: the recording had only users; `resolvedBy`, `diffSide` and
  `startDiffSide` were answered as selected.
