# FC22 — The Issue Heads Checked Every Day

```json
{
  "$schema": "issue-1.json",
  "id": "FC22",
  "title": "The Issue Heads Checked Every Day",
  "state": "Implementing",
  "size": "XS",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "The daily Shared rules check runs the released porelo-release's `issues check issues`, so an issue head its schema refuses, or an index out of the heads' order, turns the check red naming the file and the field"
}
```

**Why this size.** One step in `.github/workflows/shared-rules.yml`, calling the porelo-release the root already pins;
no code, no contract.

**What must be true.** An issue head in `issues/` that the issue schema refuses, or an index that does not match the
heads, is found on `main` within a day: the *Shared rules check* fails and names the file and the field.

## Plan

- Add a step to the `check` job of `.github/workflows/shared-rules.yml` that runs `issues check issues` through the
  root's `exec:exec@release` execution, with the released porelo-release the root pins, on the schedule, on a pull
  request and by hand, as the job's other steps run.

## Acceptance

- The workflow, dispatched by hand on `main`, runs the step and passes.
- On a copy of the issues with a head missing its `size`, the same command exits non-zero and names that file and
  field.

## To be checked

- Whether the released porelo-release the root pins already has `issues check`; if not, the step waits for the
  release that has it.
