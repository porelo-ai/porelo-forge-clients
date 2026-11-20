# FC26 — Conditional Reads With ETag On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "FC26",
  "title": "Conditional Reads With ETag On GitHub",
  "state": "Backlog",
  "size": "S",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "A repository's issue list and a project's item list read with a known ETag, a 304 answered as not changed instead of a failure, the new ETag and the rate limit returned with each answer, and a refusal naming the permissions GitHub accepts"
}
```

**Why this size.** One change in `GitHubClient`'s transport, a result record, one more REST operation and one more
field of `GitHubException`; one module and its tests.

**What must be true.** A caller that polls a repository's issues, or an organization project's items, reads them
again with the ETag it got, and learns either that nothing changed or the new answer with its new ETag. Every
answer, success or failure, says the rate limit GitHub reported, so a caller paces itself before it is refused, and
a refusal for a missing permission says which permissions GitHub would have accepted.

## Plan

- **Order.** `Conditional`, the two conditional reads and `acceptedPermissions()` are published first by the
  first step of [FC24](README.md#fc24), the reads failing loud and `acceptedPermissions()` built. This issue's
  calls are built by a sub-agent in a worktree of its own, beside FC24 and FC25, and rebased onto them before its
  hand-over, since all three change `GitHubClient`. A signature found wrong while building changes only after it is
  agreed with the planner's agent.

Read in today's code: `GitHubClient.exchange`, `send` and `check`, `RateLimit.read` and `isRateLimit`,
`Page`, and `GitHubAnswersTest`'s rate-limit tests.

- **The call.** `send` takes an optional `If-None-Match` value; `exchange` treats a 304 as an answer, not a
  failure, reading no body. A new record `Conditional<T>(@Nullable T value, @Nullable String etag, boolean changed,
  @Nullable RateLimit rateLimit)` carries it; `changed` is false on a 304, when `value` is null.
- **Which reads.** `issuesIfChanged`, the conditional twin of FC24's issue list (sorted by `updated`, with
  `since`), and `projectItemsIfChanged`, over `get /orgs/{org}/projectsV2/{project_number}/items`, which the pinned
  description answers with 200 or 304, paged by `before`, `after` and `per_page`; the operation goes into the
  extract and its record is generated. Each takes the ETag it last got or none. The plain reads stay as they are.
  GraphQL has no ETag, so the board query is not conditional; the README says so.
- **Permissions.** `GitHubException.acceptedPermissions()`, the `X-Accepted-GitHub-Permissions` header of a
  refused answer or null, comes built with the surface; this issue records it live.
- **Rate limits.** The 403 and 429 rule from before stays and covers GitHub's secondary limits: a 403 or 429 with
  `Retry-After`, or with a message naming a rate limit and no headers, is a rate limit whose `delay()` is that
  header, else the reset, else one minute. New is only that `Conditional` carries the `RateLimit` of a successful
  answer too.
- **Tests.** Replays of a 200 with `etag` and a 304 for the same request. Red first: `notModifiedIsNotAFailure`,
  which fails today with a `GitHubException` of status 304, `ifNoneMatchIsSent`, which finds no header today, and
  `aRefusalNamesTheAcceptedPermissions` against a 403 with the header.

## Acceptance

- A 304 returns `changed == false` with no value and the rate limit; a 200 returns the value and its ETag; the ETag
  sent is the one the caller gave, and no ETag is sent when it gave none.
- The recorder records one 304 live for the issue list and the project's items, and the README states whether
  GitHub counted it against the limit, as measured from `x-ratelimit-remaining` before and after.
- A 403 for a missing permission carries `acceptedPermissions()` as GitHub sent it; one without the header has null.

## To be checked

- Whether the project items' ETag changes when only a field value changes, which is what the probe is for;
  measured by the recorder. If it does not, the README says so and the probe is `projectFields`' `updatedAt`.
- Whether GitHub sends a weak ETag (`W/"…"`) and expects it back unchanged; measured by the recorder.
