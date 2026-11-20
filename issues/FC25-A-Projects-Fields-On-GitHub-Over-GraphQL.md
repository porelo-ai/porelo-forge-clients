# FC25 — A Project's Fields On GitHub Over GraphQL

```json
{
  "$schema": "issue-1.json",
  "id": "FC25",
  "title": "A Project's Fields On GitHub Over GraphQL",
  "state": "Backlog",
  "size": "M",
  "priority": "High",
  "rank": 2,
  "blockedBy": [],
  "covers": "An organization project's fields read, its items paged with their issues and field values, issues read by id, added to it, their field values and a field's options set, an issue's status moves read, and several issues created in one mutation, each batch saying what was saved, over GraphQL, with each answer's cost"
}
```

**Why this size.** Eight GraphQL operations with hand-written records in `GitHubGraphQl`, three of them built from a
fixed per-item fragment repeated under aliases, and the recorder extended by a project of its own; one module, no
contract another repository reads.

**What must be true.** A caller holding a person's token reads an organization project's fields with their
single-select options, replaces a single-select field's options once when a project is set up, reads the project's items page by page with their issues and field values, reads chosen
issues by id the same way, adds issues to the project, sets several field values of several issues in one mutation,
and creates several issues across repositories of one owner in one mutation. A batch says which of its parts GitHub
saved and which it refused. Each answer says what it cost and what remains of the hour's points.

## Plan

- **Order.** Every method and record below is published first, failing loud, by the first step of
  [FC24](README.md#fc24). This issue's calls are built after [FC24](README.md#fc24)'s, behind that fixed surface; a signature
  found wrong while building changes only after it is agreed with the planner's agent.

Read in today's code: `GitHubGraphQl` (fixed query constants, `Map` variables, hand-written records),
`GitHubClient.graphQl`, `graphQlNode` and its error and `RATE_LIMITED` handling, `GitHubOperations`,
`GitHubRecorder` and `GitHubLiveCheck`.

- **Queries and mutations**, each a constant or a fixed fragment in `GitHubGraphQl`, never text from the caller:
  - `projectFields(org, number)`: the project's `id`, `title`, `updatedAt` and its fields, paged, with
    `ProjectV2Field` (`id`, `name`, `dataType`) and `ProjectV2SingleSelectField` (`id`, `name`, `options { id name }`).
    `updatedAt` is also the cheap probe for a field change, should the REST probe of [FC26](README.md#fc26) not see one.
  - `setFieldOptions(fieldId, options)`: `updateProjectV2Field` with `singleSelectOptions`, each option a `name`,
    `color` and `description`, replacing the field's options in the caller's order and returning them with their
    ids. GitHub gives every option a new id, one whose name stays included, as measured, so a caller takes the ids
    from this answer; the README says so. Whether the items keep their values across the replacement is measured by
    the recorder, written in the README and posted to the planner's agent.
  - `statusChanges(id, after)`: one issue's timeline items of type `PROJECT_V2_ITEM_STATUS_CHANGED_EVENT`, paged by
    100 with a cursor, each with `createdAt`, `previousStatus`, `status`, `actor { login }` and its project's `id`;
    the moves of a project's own `Status` field. A custom single-select field's change appears in no timeline item,
    and the README says so.
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
  strictly. The recorder and the live check make the issue and project calls with `LIVE_GITHUB_REVIEWER_TOKEN`, the test
  organization member's token, which holds Issues and organization Projects read and write on all of the organization's
  repositories; a member may create projects there, as measured, and administers the projects it creates. One kept project stays in the test organization for the live check, which `kept-github`
  makes unless it is there.
- **What the answers may hold.** Every caller value travels in `variables`, each alias's typed, such as `$v0: ID!`;
  the repeated fragment carries only the index. Union lists (`fields`, `fieldValues`, `timelineItems`,
  `nodes(ids:)`) ask `__typename`, and a variant not modelled, such as an iteration field or a repository value, is
  left out before the records are built; a field value's `field` is read through `ProjectV2FieldCommon`.
  `Issue.projectItems` has no project argument, so `issueBoard` asks each item's `project { id }` and keeps the given
  project's. `nodes(ids:)` answers null or `{}` for an unknown id, with a `NOT_FOUND` error at `["nodes", i]`; both
  read as null in their place and the error is dropped. `blockedBy`, `subIssues`, `labels` and `fieldValues` carry
  `pageInfo`.
- **Bounds.** An empty list, a blank id, more than 50 parts, more than 100 ids for `issueBoard`, a duplicate id, or two
  values for one item and field are an `IllegalArgumentException` before anything is sent.
- **Errors and limits.** All of an answer's `errors` are read: any `RATE_LIMITED`, any error whose `path[0]` names no
  alias sent, or an alias with neither data nor error fails the whole call; otherwise each alias's error becomes its
  refused outcome. Every message is scrubbed of the token and the variables' credentials, as thrown ones are.
  `graphQl` goes through `exchange`, so a GraphQL failure carries the answer's `RateLimit` and a `Batch` the
  headers' limit; only queries can ask `rateLimit`, so a mutation has no `GraphQlCost`.
- **Tests.** `GitHubClientTest` replays each recording, as it does the review threads, and asserts the query text sent, the variables and each field
  read. Red first: `setFieldValuesSendsOneAliasPerValue` against a mutation built with one alias for two values,
  `issueBoardReadsOptionIds` with `optionId` removed from the recording, `setFieldOptionsSendsEachOption` with one
  option's `color` left out of the request, `statusChangesReadsThePreviousStatus` with `previousStatus` removed
  from the recording, and `aBatchSaysWhichAliasFailed` against an
  answer with `data` for `v0` and an error on `v1`.

## Acceptance

- Each operation replays its recording strictly; the aliased mutations send one alias per item and refuse more than
  the bound.
- An error on one alias is that part's refused outcome with GitHub's message, scrubbed; a `RATE_LIMITED` error, an
  error naming no alias, a 403 or a 429 fails the whole call, a rate limit with its delay, as for every call.
- A batch with one refused alias returns the others' results and the refused one's message, in order.
- The recorder records against a project it made and leaves no project behind; the live check reads the kept project.

## To be checked

- Whether `Issue.blockedBy` and `Issue.subIssues` in GraphQL return issues of other repositories as REST does, and
  whether `createIssue` takes `parentIssueId` on GitHub.com today; measured by the recorder against the schema
  GitHub serves.
- What one `issueBoard` of 100 issues costs in points, measured.
