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
  "covers": "An organization project's fields and options read, issues added to it, their field values read in one query and set several at a time in one mutation, and several issues created in one mutation, over GraphQL, with each answer's cost",
  "openQuestions": 1
}
```

**Why this size.** Five GraphQL operations with hand-written records in `GitHubGraphQl`, two of them built from a
fixed per-item fragment repeated under aliases, and the recorder extended by a project of its own; one module, no
contract another repository reads.

**What must be true.** A caller holding a person's token reads an organization project's fields with their
single-select options, adds issues to the project, reads several issues' field values and sub-issues in one query,
sets several field values of several issues in one mutation, and creates several issues across repositories of one
owner in one mutation. Each answer says what it cost and what remains of the hour's points.

## Plan

Read in today's code: `GitHubGraphQl` (fixed query constants, `Map` variables, hand-written records),
`GitHubClient.graphQl`, `graphQlNode` and its error and `RATE_LIMITED` handling, `GitHubOperations`,
`GitHubRecorder` and `GitHubLiveCheck`.

- **Queries and mutations**, each a constant or a fixed fragment in `GitHubGraphQl`, never text from the caller:
  - `projectFields(org, number)`: the project's `id`, `title` and its fields, paged, with `ProjectV2Field` (`id`,
    `name`, `dataType`) and `ProjectV2SingleSelectField` (`id`, `name`, `options { id name }`).
  - `addProjectItems(projectId, contentIds)`: `addProjectV2ItemById` once per issue under aliases `i0…in`,
    returning each item's `id`.
  - `issueBoard(ids, projectId)`: `nodes(ids:)` over issues, each with `id`, `databaseId`, `number`, `title`,
    `body`, `state`, `stateReason`, `updatedAt`, its repository's `nameWithOwner`, `parent { id }`,
    `subIssues(first: 100)` with their ids and `pageInfo`, `blockedBy(first: 100)`, and `projectItems` filtered to
    the project with their single-select and text field values (`field { id }`, `optionId`, `name`, `text`).
  - `setFieldValues(projectId, values)`: `updateProjectV2ItemFieldValue` once per value under aliases, a value being
    an item, a field and a single-select option id or a text; and `clearProjectV2ItemFieldValue` for a value
    cleared.
  - `createIssues(issues)`: `createIssue` once per issue under aliases, each with `repositoryId`, `title`, `body`
    and an optional `parentIssueId`, returning `id`, `databaseId`, `number` and `url`.
  - Every query asks `rateLimit { cost remaining resetAt }`, read into a `GraphQlCost` record beside the data.
- **Aliased operations.** A batch is bounded (at most 50 aliases, a guard the caller sees as an
  `IllegalArgumentException`); a GraphQL answer with errors on some aliases is a `GitHubException` naming the first,
  as today, since GitHub runs a mutation's fields in order and a partial batch must not read as done. Whether GitHub
  applies the aliases before the failing one is measured and written in the README.
- **Records.** Hand-written records with `@Nullable` where GitHub may send null, required fields checked in their
  constructors, as for the review threads.
- **Recorder and live check.** The recorder creates a project of its own in the test organization per run
  (`createProjectV2`, two single-select fields and a text field), records each call against two issues of its run,
  and deletes the project (`deleteProjectV2`) at the end and on interrupt; the live check reads a kept project
  strictly.
- **Tests.** `GitHubClientTest` replays each recording, as it does the review threads, and asserts the query text sent, the variables and each field
  read. Red first: `setFieldValuesSendsOneAliasPerValue` against a mutation built with one alias for two values,
  and `issueBoardReadsOptionIds` with `optionId` removed from the recording.

## Acceptance

- Each operation replays its recording strictly; the aliased mutations send one alias per item and refuse more than
  the bound.
- An error on any alias fails the call naming GitHub's message; a `RATE_LIMITED` error and a 403 or 429 are rate
  limits with their delay, as for every call.
- The recorder records against a project it made and leaves no project behind; the live check reads the kept project.

## Open questions

- The recorder needs to create and delete organization projects in the test organization: may the organization
  owner's test token be given read and write on organization projects (`project` scope), and may a kept project
  for the live check stay there?

## To be checked

- With the planner's agent: the fields it keeps in the project (state, priority, size and risk as single-select;
  the old id as text), whether it reads the board by issue ids or by the project's items, and the largest batch it
  sends, so the bound fits.
- Whether `Issue.blockedBy` and `Issue.subIssues` in GraphQL return issues of other repositories as REST does, and
  whether `createIssue` takes `parentIssueId` on GitHub.com today; measured by the recorder against the schema
  GitHub serves.
- What one `issueBoard` of 100 issues costs in points, measured.
