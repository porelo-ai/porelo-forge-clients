# FC14 — The GitHub Recorder's Ownership Of What It Made

**Status:** Implementing.
**Size:** S - one condition in the recorder's fixture and one in a test's transport, with a test each; no call of the
client changes.

**What must be true.** The GitHub recorder removes every repository and key a run made, also when a later request of
the same kind in that run is refused: a refused create gives up only the ownership that this one request began, never
one an earlier request established. The test of the key page's refusal on a transport that fails on the stop fails
when the page does not stop the reading (second reading of [FC06](README.md#fc06), 2026-10-31).

## Plan

- **`GitHubFixture.requesting`:** gives a resource up on a 4xx refusal only when this request added it to what the run
  owns.
- **`GitHubKeyPageTest`:** its transport that fails on the stop throws an `AssertionError` when the page did not
  cancel, which no catch in the client turns into the refusal.
- **Red first:** `GitHubFixtureKeysTest` asks for a key, then for a second one that GitHub refuses with 422, and sees
  the first deleted on close; and the key page test fails with the page's stop on a refusal removed.

## Acceptance

- A run whose second key request is refused still deletes its first key.
- The key page refusal test fails when the page does not stop the reading.
