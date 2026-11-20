# FC29 — The Maven Cache Saved When A Build Fetched

```json
{
  "$schema": "issue-1.json",
  "id": "FC29",
  "title": "The Maven Cache Saved When A Build Fetched",
  "state": "Implementing",
  "size": "XS",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "Each CI run restores the local Maven repository and saves it again when it fetched a jar or pom, so versions that move under unchanged poms are not asked of Central on every run"
}
```

**Why this size.** The shared cache form is copied into the `pinned-jdk` action, one new action and three workflows,
with no code of the clients.

**What must be true.** A CI run that fetched a jar or pom the restored cache lacked saves the cache. The next run
then fetches nothing again, even when the parent pom or an imported BOM moved while this repository's poms stayed
the same.

## Plan

- The `pinned-jdk` action restores the local Maven repository under a key holding the run id and attempt. It falls back to
  the poms' hash and then to any Maven key, touches a marker, and gives the key as its output `maven-cache-key`.
- A new `maven-cache-save` action checks, by status-change time against the marker, whether a jar or pom came in
  after the restore. Only then does it save the cache under that key.
- `build.yml`, `shared-rules.yml` and `workflows.yml` each give the `pinned-jdk` step an id and end their single job
  with the save step, run always.

## Acceptance

- The workflows load as YAML, and `check-actions` and the document checks pass.
- The first Build after the push logs "fetched after the restore, among others: ..." and saves. The next one logs
  "nothing fetched after the restore: nothing saved", and its Maven output shows no jar or pom downloaded.
