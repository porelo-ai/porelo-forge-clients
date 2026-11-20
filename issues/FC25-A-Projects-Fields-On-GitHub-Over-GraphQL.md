# FC25 — A Project's Fields On GitHub Over GraphQL

```json
{
  "$schema": "issue-1.json",
  "id": "FC25",
  "title": "A Project's Fields On GitHub Over GraphQL",
  "state": "Analyzed",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "An organization project's fields read, its items paged with their issues and field values, issues read by id, added to it, their field values set and several issues created in one mutation, each batch saying what was saved, over GraphQL, with each answer's cost",
  "openQuestions": 0
}
```

**Why this size.** Six GraphQL operations with hand-written records in `GitHubGraphQl`, two of them built from a
fixed per-item fragment repeated under aliases, and the recorder extended by a project of its own; one module, no
contract another repository reads.

**What must be true.** A caller holding a person's token reads an organization project's fields with their
single-select options, reads the project's items page by page with their issues and field values, reads chosen
issues by id the same way, adds issues to the project, sets several field values of several issues in one mutation,
and creates several issues across repositories of one owner in one mutation. A batch says which of its parts GitHub
saved and which it refused. Each answer says what it cost and what remains of the hour's points.

## Plan

Read in today's code: `GitHubGraphQl` (fixed query constants, `Map` variables, hand-written records),
`GitHubClient.graphQl`, `graphQlNode` and its error and `RATE_LIMITED` handling, `GitHubOperations`,
`GitHubRecorder` and `GitHubLiveCheck`.

- **Queries and mutations**, each a constant or a fixed fragment in `GitHubGraphQl`, never text from the caller:
  - `projectFields(org, number)`: the project's `id`, `title`, `updatedAt` and its fields, paged, with
    `ProjectV2Field` (`id`, `name`, `dataType`) and `ProjectV2SingleSelectField` (`id`, `name`, `options { id name }`).
    `updatedAt` is also the cheap probe for a field change, if the REST probe of FC26's kind is not offered.
  - `projectItems(org, number, after)`: the board, one page of 100 items with a cursor, each item's `id`, its issue
    (the fields of `issueBoard` below) and its field values; a draft or pull request item is returned with no issue.
  - `addProjectItems(projectId, contentIds)`: `addProjectV2ItemById` once per issue under aliases `i0…in`,
    returning each item's `id`.
  - `issueBoard(ids, projectId)`: `nodes(ids:)` over issues, each with `id`, `databaseId`, `number`, `title`,
    `body`, `state`, `stateReason`, `updatedAt`, its repository's `nameWithOwner`, `parent { id }`,
    `subIssues(first: 100)` with their ids and `pageInfo`, `blockedBy(first: 100)`, and `projectItems` filtered to
    the project with their single-select, text and number field values (`field { id }`, `optionId`, `name`, `text`,
    `number`); used after a save and before one, to see whether the issue changed.
  - `setFieldValues(projectId, values)`: `updateProjectV2ItemFieldValue` once per value under aliases, a value being
    an item, a field and a single-select option id, a text or a number; and `clearProjectV2ItemFieldValue` for a value
    cleared.
  - `createIssues(issues)`: `createIssue` once per issue under aliases, each with `repositoryId`, `title`, `body`
    and an optional `parentIssueId`, returning `id`, `databaseId`, `number` and `url`.
  - Every query asks `rateLimit { cost remaining resetAt }`, read into a `GraphQlCost` record beside the data.
- **Aliased operations.** A batch is bounded (at most 50 aliases, a guard the caller sees as an
  `IllegalArgumentException`). An aliased mutation returns a `Batch` record: per alias, in the caller's order, the
  result or GitHub's message for it, told by the error's `path[0]`; an answer with an error naming no alias, a 403,
  a 429 or `RATE_LIMITED` still fails the whole call as today. Whether GitHub applies the aliases before and after a
  failing one is measured by the recorder and written in the README, and posted to the planner's agent.
- **Records.** Hand-written records with `@Nullable` where GitHub may send null, required fields checked in their
  constructors, as for the review threads.
- **Recorder and live check.** The recorder creates a project of its own in the test organization per run
  (`createProjectV2`, two single-select fields, a text and a number field), records each call against two issues of its run,
  and deletes the project (`deleteProjectV2`) at the end and on interrupt; the live check reads a kept project
  strictly. The organization owner's test token, `LIVE_GITHUB_USER_TOKEN`, reads and writes organization projects
  (`project` scope, or organization projects read and write for a fine-grained token), and one kept project stays
  in the test organization for the live check, which `kept-github` makes unless it is there.
- **Tests.** `GitHubClientTest` replays each recording, as it does the review threads, and asserts the query text sent, the variables and each field
  read. Red first: `setFieldValuesSendsOneAliasPerValue` against a mutation built with one alias for two values,
  `issueBoardReadsOptionIds` with `optionId` removed from the recording, and `aBatchSaysWhichAliasFailed` against an
  answer with `data` for `v0` and an error on `v1`.

## Acceptance

- Each operation replays its recording strictly; the aliased mutations send one alias per item and refuse more than
  the bound.
- An error on any alias fails the call naming GitHub's message; a `RATE_LIMITED` error and a 403 or 429 are rate
  limits with their delay, as for every call.
- A batch with one refused alias returns the others' results and the refused one's message, in order.
- The recorder records against a project it made and leaves no project behind; the live check reads the kept project.

## To be checked

- Whether `Issue.blockedBy` and `Issue.subIssues` in GraphQL return issues of other repositories as REST does, and
  whether `createIssue` takes `parentIssueId` on GitHub.com today; measured by the recorder against the schema
  GitHub serves.
- What one `issueBoard` of 100 issues costs in points, measured.
