# FC17 — Mutation Testing With PIT

**Status:** Accepted.

**What must be true.** Whoever works on the clients can see which faults in their code no test notices: a `pit` profile,
run by hand, not in CI and with no threshold, with PIT and its JUnit 5 plugin pinned, and a report that sorts the
survivors into real gaps and harmless ones. Where a test calls real tools, such as Forgejo's live check in podman, it
runs on a VM.

The model is the `pit` profile of `porelo-message-matrix`, on its branch `pit-try` until it is pushed, with the report
of that try at `~/.porelo/handover/porelo-message-matrix/pit-014d86a/report.md`.
