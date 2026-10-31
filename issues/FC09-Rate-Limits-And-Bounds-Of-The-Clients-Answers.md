# FC09 — Rate Limits And Bounds Of The Clients' Answers

**Status:** Accepted (operator, 2026-10-31); blocked by [FC02](README.md#fc02).

**What must be true.** A caller tells a forge's rate limit from a refusal and knows how long to wait before it tries
again, knows when a list it reads page by page has ended, and never reads an answer without a bound, through the
`github` and `forgejo` modules.

## The shape

- **Rate limits:** `GitHubException` and `ForgejoException` carry the delay a forge asks for (`Retry-After`) and its
  rate-limit headers (limit, remaining, reset) when it sends them. GitHub answers a rate limit as 403 or 429; the
  exception says which it is, so a 403 for a missing permission is not taken for one.
- **The end of a list:** a call that reads one page of a list tells the caller whether another page follows, from
  the forge's `Link` header or its total count, not only from the page's size.
- **Bounded answers:** every answer is read up to a byte bound, as the job log already is; one above it fails naming
  the bound, with no part of the answer in the message.
- Each is tested against recorded and hand-made answers: a rate-limited answer of each forge, a list whose last page
  is full, and an answer over the bound.

## To be checked

- Which rate-limit headers and statuses Forgejo sends, measured against a real Forgejo with its limiter on.
