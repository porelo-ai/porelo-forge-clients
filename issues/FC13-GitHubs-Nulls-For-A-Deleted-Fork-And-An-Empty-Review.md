# FC13 — GitHub's Nulls For A Deleted Fork And An Empty Review

**Status:** Accepted (by the operator, through Agent Coordinator, 2026-10-31).

**What must be true.** A caller reads every pull request and review GitHub answers through the `github` module, also a
pull request from a deleted fork, whose `head.repo` GitHub sends as null, and a review whose `body` is null; such an
item reads with that value as null instead of failing the whole read (Agent Adapters' live test, 2026-10-31).
