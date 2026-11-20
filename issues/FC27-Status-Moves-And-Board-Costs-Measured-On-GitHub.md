# FC27 — Status Moves And Board Costs Measured On GitHub

```json
{
  "$schema": "issue-1.json",
  "id": "FC27",
  "title": "Status Moves And Board Costs Measured On GitHub",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "Whether GitHub lists a project status move made over the API, later or at all, what a board read of 100 issues costs, and whether GraphQL lists sub-issues and blockers of other repositories"
}
```

**What must be true.** A caller that watches a project's board knows from measurements, written in the GitHub
module's README, which status moves `statusChanges` lists, what reading 100 issues with `issueBoard` costs in points,
and whether `Issue.subIssues` and `Issue.blockedBy` in GraphQL list issues of another repository of the same owner as
REST does.

## To be checked

- `statusChanges` listed only an item's first status, whether `setFieldValues` or the project's automation set it; a
  later move by `setFieldValues`, read at once, was not listed. Whether it appears after some seconds, or never for a
  move made over the API, is measured by the recorder reading again after a wait.
- What one `issueBoard` of 100 issues costs; three ids cost 2 points, as measured.
- Whether GraphQL's `subIssues` and `blockedBy` list an issue of another repository, which REST does, as recorded.
