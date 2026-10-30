# FC07 — Plain Key Lists On Forgejo

**Status:** Review (Estimated and Backlog by the operator, 2026-10-30, sprint 6m).
**Size:** XS - one call in `ForgejoClient` and its tests, outside the API and its extract.

**What must be true.** A caller reads a Forgejo account's public SSH keys without a token, as the lines of Forgejo's
page `<login>.keys` (Forgejo leaves out the keys' comments), and tells "no such login" apart from any other failure.

## Plan

- **`ForgejoClient.plainKeys(login)`** returns `List<String>`, the page's lines in order, empty lines left out. The page
  sits beside the API, at the web root: the base `…/api/v1/` resolved against `../../<login>.keys`. It sends no
  `Authorization` header, follows no redirect, reads `text/plain` up to a bound and refuses a longer answer. A 404 is a
  `ForgejoException` with status 404, which the caller reads as "no such login"; every other status, or an answer that
  is not plain text, is a `ForgejoException` with its status, never an empty list. The login is one path segment,
  encoded, with dot segments refused, as everywhere else.
- **The fixture and recording:** `ForgejoScenario` reads the reviewer's seeded key through `plainKeys`; the recording is
  made live with the others, and `ForgejoLiveIT` checks that the line equals the seeded key.
- **`clients/forgejo/README.md`:** names `plainKeys` as the one call that takes no token.
- **Red first:** `ForgejoClientTest` replays the recorded page and asserts that no `Authorization` header was sent; a
  404 must arrive as a `ForgejoException` with status 404, and a 500 must not read as an empty list.

## Acceptance

- `plainKeys` returns the seeded key's line from the live container and from its recording.
- No token is sent, seen in a test that fails when it is.
- A 404 is told apart from other failures; no failure reads as an empty list.
