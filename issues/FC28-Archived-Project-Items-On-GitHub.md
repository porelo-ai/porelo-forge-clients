# FC28 — Archived Project Items On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "FC28",
  "title": "Archived Project Items On GitHub",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "A project item read over GraphQL says whether it is archived, so an archived item no longer reads as one on the board"
}
```

**Why this size.** One field more in one query and one record of the `github` module, its test and one recording.

**What must be true.** A caller reading an organization project's items with `projectItems` learns of each item
whether GitHub archived it, so an archived item is not taken for one on the board.

## Plan

- `ProjectBoard.PROJECT_ITEMS` asks each item's `isArchived`; `GitHubGraphQl.ProjectItem` gains `boolean archived`
  after its `id`, read as required, so an answer without it fails.
- Red first: `projectFieldsAndItemsAreRead` with an archived item, which fails until the field is read.
- The `projectItems` recording is made again by the recorder, and the README names the field.

## Acceptance

- An archived item reads `archived` true, a live one false; an answer without `isArchived` fails.
- `./mvnw -B -s settings.xml verify` passes with no network.
