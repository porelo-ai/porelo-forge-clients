# FC21 — Forgejo's Images From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "FC21",
  "title": "Forgejo's Images From A Cache",
  "state": "Idea",
  "size": "-",
  "priority": "Medium",
  "rank": 3,
  "blockedBy": [],
  "covers": "The Build's live Forgejo step loads Forgejo's server and runner images from the Actions cache, keyed by their digests, instead of pulling them from codeberg.org and code.forgejo.org on every run"
}
```

**What must be true.** A Build stays green while codeberg.org or code.forgejo.org is down or slow, as long as the pinned
images have been fetched once. The step *Forgejo's calls against a real Forgejo in podman* takes
`ForgejoContainer.SERVER_IMAGE` and `ForgejoContainer.RUNNER_IMAGE` from the Actions cache, keyed by their digests.
When a digest changes, the cache holds only the new images.

## The shape

- Today every Build pulls both images when `ForgejoContainer` runs them, uncached and without a retry. The pinned
  GraalVM, shfmt, the wrapper's Maven and the local Maven repository are already cached by the `pinned-jdk` action.
- One step before the live test reads both references from `ForgejoContainer.java`, so the Java constant stays the only
  pin. It restores `podman save` archives keyed by both digests with `actions/cache/restore`. On a hit it runs
  `podman load`. On a miss it pulls with a retry, saves, and stores the archive with `actions/cache/save`, as the
  `pinned-jdk` action does for shfmt.
- A test that goes red first: none, since this only changes a workflow. The proof is one Build with a miss and one with
  a hit whose log shows no pull from either registry.

## Acceptance

- A second Build on the same digests loads both images from the cache, and its log shows no request to codeberg.org or
  code.forgejo.org.
- After a digest changes, the cache keeps only the entry for the new digests.

## To be checked

- Whether podman can load a `podman save` archive of an image pulled by digest so that `podman run <name>@sha256:…`
  finds it without contacting the registry, or whether the step must also tag it.
- How older entries are removed: deleting by key through the REST API in the same step needs `actions: write` for the
  job's token. Alternatively, a repository setting for cache retention or size may be enough, if GitHub offers one.
  Whether the Maven cache, keyed by the hash of every `pom.xml` and restored by prefix, keeps old entries in the same way
  and needs the same removal.
- A ready-made container-cache action against the few lines above. Only an action pinned by commit, which
  `check-actions` accepts, is considered.
- The archive size of both images, against the repository's cache limit.
