# FC24 — Issues, Sub-Issues And Dependencies On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "FC24",
  "title": "Issues, Sub-Issues And Dependencies On GitHub",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A repository's issues listed, read, created and updated, an issue's sub-issues and parent, and its blocked-by and blocking dependencies on GitHub, over REST, recorded live",
  "openQuestions": 0
}
```

**Why this size.** Thirteen REST operations in the one `github` module, their records generated from the pinned
description, and the recorder and live check extended by one issue fixture; no contract another repository reads
changes.

**What must be true.** A caller holding a person's token reads a repository's issues page by page, reads one,
creates one with its labels and changes its title, body, labels, and state with the reason `completed` or
`not_planned`; lists an issue's sub-issues and reads its
parent, adds a sub-issue from any repository of the same owner and removes one; and lists, adds and removes the
issues an issue is blocked by and lists the ones it blocks. Each call speaks GitHub's own terms and returns
GitHub's own records.

## Plan

Read in today's code: `GitHubClient`, `GitHubOperations`, `GitHubRecorder`, `GitHubScenario`, `GitHubLiveCheck`,
`clients/github/pom.xml` (the `check-extract` execution) and `src/main/openapi/github.json`, and the pinned
`api.github.com.2026-03-10.json` (SHA-256 `b5bed448…`), which has every operation below with
`enabledForGitHubApps: true`.

- **Operations into the extract**, one `--operation` each in `check-extract` and in the extract's fetch:
  `get|post /repos/{owner}/{repo}/issues`, `get|patch /repos/{owner}/{repo}/issues/{issue_number}`,
  `get /…/{issue_number}/parent`, `get|post /…/{issue_number}/sub_issues`, `delete /…/{issue_number}/sub_issue`,
  `patch /…/{issue_number}/sub_issues/priority`, `get|post /…/{issue_number}/dependencies/blocked_by`,
  `delete /…/{issue_number}/dependencies/blocked_by/{issue_id}`, `get /…/{issue_number}/dependencies/blocking`.
  The records (`Issue`, `IssuesCreateRequest`, `IssuesUpdateRequest`, the sub-issue and dependency requests) are
  generated; a field GitHub sends that the description lacks, or a null it does not allow, gets a `--set` or
  `--add-property` named in the README, as before.
- **`GitHubClient`** gains `issues` (paged, with `state`, `labels`, `since`, sorted by `updated`), `issue`,
  `createIssue`, `updateIssue`, `parentIssue`, `subIssues` (paged), `addSubIssue` (with `replace_parent`),
  `removeSubIssue`, `reprioritizeSubIssue`, `blockedBy` (paged), `addBlockedBy`, `removeBlockedBy` and `blocking`
  (paged), on the existing `call`, `page` and `delete`. A sub-issue and a dependency are named by the other issue's
  `id`, not its number, as GitHub asks; the README says so. `issues` returns pull requests too, as GitHub does; the
  README says the caller tells them by `pull_request`.
- **Recorder and live check.** `GitHubOperations` gets one entry per call. The recorder opens two issues in its
  run's repository and one in the kept fixture repository, links one as the other's sub-issue and blocked-by, reads
  them, removes the links and closes the issues, owning what it made as it does for keys; the live check reads the
  kept fixture's issue strictly.
- **Tests.** `GitHubClientTest` replays each recording, asserting the path, the method, the body and each field read;
  `GitHubExtractTest` lists the new operations. Red first: `issueIsRead` against `issue.json` with `number` changed,
  which must fail by that field, and `addSubIssueSendsTheIssueId` with the request body's `sub_issue_id` taken out.
- **README.** `clients/github/README.md` names the new calls, the id-not-number rule and the pull requests in
  `issues`.

## Acceptance

- Each call replays its recording strictly; a changed field in a recording fails its test by that field.
- The recorder records every call against the test organization and leaves nothing behind; the live check reads the
  kept issue strictly.
- `./mvnw -B -s settings.xml verify` passes with no network.

## To be checked

- Whether GitHub answers a sub-issue from another repository of the same owner, and a dependency across
  repositories, with the same records; measured by the recorder.
