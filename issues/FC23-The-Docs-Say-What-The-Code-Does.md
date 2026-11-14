# FC23 — The Docs Say What The Code Does

```json
{
  "$schema": "issue-1.json",
  "id": "FC23",
  "title": "The Docs Say What The Code Does",
  "state": "Backlog",
  "size": "S",
  "priority": "Highest",
  "rank": 1,
  "blockedBy": [],
  "covers": "Every sentence of the user-facing documentation checked against the code on main; what the code does not bear out is corrected, and a code fault the docs describe rightly filed as its own bug",
  "openQuestions": 0
}
```

**Why this size.** About 480 lines of documentation in eleven pages and the changelog's unreleased entries, read
against the code; documents only, no behavior change.

**What must be true.** Whoever reads this repository's documentation can trust every command, flag, path, default,
class, method and behavior it names: each one is what the code on main does.

## Plan

- A read-only reviewer of another model reads `README.md`, `CONTRIBUTING.md`, `SECURITY.md`, `clients/README.md`,
  both clients' `README.md`, `doc/records.md`, the three `openapi-*/README.md` and `changelog/unreleased/*.yml` page by
  page against the code and lists every claim the code does not bear out, with the document's line and the code's.
- Each finding is checked in the code itself; only a confirmed one is changed.
- Where the code is right, the document is corrected. Where the document is right and the code is wrong, the fault is
  filed as an issue of its own, in Backlog with its size, and the document stays.
- No behavior changes.

## Acceptance

- Every finding of the reading is listed in the hand-over as confirmed or not, with the count of claims checked,
  confirmed, fixed and filed as bugs.
- The documents' checks, `check-commits`, `check-citations` and `issues check issues` are green on the commit.
