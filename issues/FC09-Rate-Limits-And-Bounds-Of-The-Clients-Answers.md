# FC09 — Rate Limits And Bounds Of The Clients' Answers

**Status:** Backlog (Estimated and Backlog by the operator, 2026-10-31); blocked by [FC02](README.md#fc02).
**Size:** M - small changes in both modules, but every list call changes its return type, and both forges are
recorded again, GitHub's in a session with the operator.

**What must be true.** A caller tells a forge's rate limit from a refusal and knows how long to wait before it tries
again, knows when a list it reads page by page has ended, knows whether a failed request may have reached the forge,
and never reads an answer without a bound, through the `github` and `forgejo` modules.

## Plan

Read in today's code: `GitHubException` and `ForgejoException` carry only a status and a message; every call reads
its whole answer with `BodyHandlers.ofString`, the job logs of both forges too; only Forgejo's `<login>.keys` page is
read up to a bound (`BoundedBytes`, 256 KiB); list calls return a bare `List<T>`, or GitHub's envelope record for
check runs, runs and jobs; the recorders keep only the `content-type` and `location` headers.

- **Rate limits.** Each exception gets `@Nullable RateLimit rateLimit()`, a record of the forge's `limit`,
  `remaining`, `reset` (an `Instant`) and `retryAfter` (a `Duration`), each nullable, read from `x-ratelimit-*` and
  `Retry-After` (seconds or an HTTP date), and `boolean rateLimited()`. GitHub's is true on a 429, and on a 403 that
  has `x-ratelimit-remaining: 0`, a `Retry-After`, or a message naming a rate limit; a 403 without any of them stays
  a refusal. `delay()` follows GitHub's own rule: `Retry-After`, else until `reset`, else one minute. A GraphQL answer
  whose first error has `type: RATE_LIMITED` is one too. Forgejo's follows what *To be checked* measures.
- **Sent or not sent.** Both clients throw status 0 today for a transport failure and for an answer that does not
  decode alike, and a write cannot tell whether it reached the forge (Core's folded Review1 draft, 2.4). Each
  exception gets `Delivery delivery()`: `NOT_SENT` (refused before sending: a bad argument, an invalid token, a
  connection never made), `SENT_UNANSWERED` (the request went out and no answer came: a timeout, a reset, an
  interrupt) and `ANSWERED` (a status came back, the answer perhaps undecodable or over its bound). The JDK's
  `HttpClient` does not say whether bytes left before a failure, so a failure after the connection opened counts as
  sent; a `ConnectException` counts as not sent.
- **The end of a list.** Every paged list call returns `Page<T>(List<T> items, boolean more)`, a record of each
  module. `more` comes from the `Link` header's `rel="next"`; GitHub's envelope calls also read `total_count`,
  Forgejo's calls `X-Total-Count`. A full page without a next link is the last one. No caller breaks: the adapters
  do not use the clients yet, and will move onto them with `Page`.
- **Bounded answers.** Both clients read through one bounded body handler, made from Forgejo's `BoundedBytes`: an
  answer 16 MiB, an error answer 64 KiB (only its `message` is read). Past the bound the reading stops and the
  client throws `"<forge> answer exceeds <n> bytes"`, with no part of the answer. A job log is read up to 64 MiB and
  keeps its last 1 MiB, since a build reads a log's tail; `jobLogs` then returns a `JobLog(String text, boolean cut)`
  (operator, 2026-10-31).
- **Recordings.** Both recorders also keep `link`, `x-total-count`, `retry-after` and `x-ratelimit-*`, scrubbed like
  any address. Review comments are recorded one per page as well, so a full last page is recorded. A rate-limited
  answer is not provoked from GitHub; it is made by hand from GitHub's documented headers.
- **Steps:** the exceptions and their rate limit; the bounded handler; `Page` and the list calls; the recorders and
  the recordings, Forgejo's from its container, GitHub's in one session with the operator, as before.
- **Red first:** `GitHubClientTest` answers `pulls` with a hand-made 403 that has `x-ratelimit-remaining: 0` and a
  `reset`, which must be `rateLimited()` with the delay up to that reset; the same 403 without the headers must not
  be.

## Acceptance

- A rate-limited answer of each forge, recorded or made by hand from its documentation, is told from a 403 for a
  missing permission, and gives the delay; a test fails when the 403 is taken for a rate limit.
- A failure before sending, one while waiting for the answer, and an undecodable answer each name their delivery,
  from hand-made transports.
- A list whose last page is full reports no further page, and a page with a next link reports one, both from
  recorded answers.
- An answer one byte over its bound fails naming the bound, its message holds no byte of the answer, and the reading
  stops there; seen for a JSON answer and an error answer on both forges.
- A job log over 1 MiB keeps exactly its last 1 MiB and says it was cut, one at 1 MiB is whole and not cut, and one
  over 64 MiB fails naming the bound; on both forges.

## To be checked

- Which rate-limit headers and statuses Forgejo sends, measured against the pinned Forgejo with its limiter on, or
  that it has none for its API.