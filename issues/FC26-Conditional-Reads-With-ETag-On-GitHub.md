# FC26 — Conditional Reads With ETag On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "FC26",
  "title": "Conditional Reads With ETag On GitHub",
  "state": "Analyzed",
  "size": "S",
  "priority": "High",
  "rank": 3,
  "blockedBy": [],
  "covers": "GitHub's REST reads of issues sent with a known ETag, a 304 answered as not changed instead of a failure, the new ETag returned with each answer, and the rate limit read on success",
  "openQuestions": 0
}
```

**Why this size.** One change in `GitHubClient`'s transport and a result record, used by the read calls of issues;
one module and its tests.

**What must be true.** A caller that keeps an issue list, an issue or its sub-issues reads them again with the ETag
it got, and learns either that nothing changed, at no cost to its hourly limit, or the new answer with its new ETag.
Every answer, success or failure, says the rate limit GitHub reported, so a caller paces itself before it is refused.

## Plan

Read in today's code: `GitHubClient.exchange`, `send` and `check`, `RateLimit.read` and `isRateLimit`,
`Page`, and `GitHubAnswersTest`'s rate-limit tests.

- **The call.** `send` takes an optional `If-None-Match` value; `exchange` treats a 304 as an answer, not a
  failure, reading no body. A new record `Conditional<T>(@Nullable T value, @Nullable String etag, boolean changed,
  @Nullable RateLimit rateLimit)` carries it; `changed` is false on a 304, when `value` is null.
- **Which reads.** Conditional twins of the issue reads of the GitHub client, `issuesIfChanged`,
  `issueIfChanged`, `subIssuesIfChanged` and `blockedByIfChanged`, each taking the ETag it last got or none. The
  plain reads stay as they are. GraphQL has no ETag, so the board query is not conditional; the README says so.
- **Rate limits.** The 403 and 429 rule from before stays and covers GitHub's secondary limits: a 403 or 429 with
  `Retry-After`, or with a message naming a rate limit and no headers, is a rate limit whose `delay()` is that
  header, else the reset, else one minute. New is only that `Conditional` carries the `RateLimit` of a successful
  answer too.
- **Tests.** Replays of a 200 with `etag` and a 304 for the same request. Red first: `notModifiedIsNotAFailure`,
  which fails today with a `GitHubException` of status 304, and `ifNoneMatchIsSent`, which finds no header today.

## Acceptance

- A 304 returns `changed == false` with no value and the rate limit; a 200 returns the value and its ETag; the ETag
  sent is the one the caller gave, and no ETag is sent when it gave none.
- The recorder records one 304 live for a list and for one issue, and the README states whether GitHub counted it
  against the limit, as measured from `x-ratelimit-remaining` before and after.

## To be checked

- With the planner's agent: which reads its cache repeats, so only those get a conditional twin, and whether it
  also wants the rate limit of a GraphQL answer's headers beside `rateLimit { cost }`.
- Whether GitHub sends a weak ETag (`W/"…"`) and expects it back unchanged; measured by the recorder.
