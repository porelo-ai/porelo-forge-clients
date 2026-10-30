# FC03 — The Forge Calls Of The Adapters Move Here

**Status:** Idea.

**What must be true.** Every HTTP call that `porelo-adapters` makes to GitHub or Forgejo today is a call of the
`github` or `forgejo` client here, with the same requests and the same handling of every answer, so the adapters keep
only the translation into Porelo's contracts.

## Acceptance

- For each call that moves, the answers the adapters' tests record today pass through the client here and yield
  the same fields; a changed answer makes a test here fail by the field that changed.
- Nothing of Porelo moves with the calls: no contract type, no SARIF, no Porelo artifact in any module's
  dependencies.

## To be checked

- Which classes of `porelo-adapters` move, and which stay because they translate - asked of its agent.
- Whether its HTTP helper (the account's trusted certificate authorities) moves, or the clients take an
  `HttpClient` from their caller.
- What the move changes for `porelo-adapters`' published artifacts, and in which order the two repositories build
  and release it.
