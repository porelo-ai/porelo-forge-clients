# FC21 — Forgejo's Images From A Cache

```json
{
  "$schema": "issue-1.json",
  "id": "FC21",
  "title": "Forgejo's Images From A Cache",
  "state": "Review",
  "size": "S",
  "priority": "High",
  "rank": 1,
  "blockedBy": [],
  "covers": "The Build's live Forgejo step loads Forgejo's server and runner images from the Actions cache, keyed by their digests, instead of pulling them from codeberg.org and code.forgejo.org on every run"
}
```

**Why this size.** One workflow step and no code: the proof is one Build that misses the cache and one that hits it.

**What must be true.** A Build stays green while codeberg.org or code.forgejo.org is down or slow, as long as the pinned
images have been fetched once. The step *Forgejo's calls against a real Forgejo in podman* takes
`ForgejoContainer.SERVER_IMAGE` and `ForgejoContainer.RUNNER_IMAGE` from the Actions cache, keyed by their digests,
and an entry no Build reads any more goes away by itself.

## Plan

- Today every Build pulls both images when `ForgejoContainer` runs them, uncached and without a retry. The fixture's
  runner takes jobs on its `host` label only, so these two images are the step's only container downloads.
- `podman save` drops the digest: an image loaded from its archive, in either format and even after `podman tag`, is not
  found as `<name>@sha256:…`, so `podman run` pulls again. Measured on podman with the runner image. What keeps it is an
  OCI archive of the whole image index, `skopeo copy --all docker://<name>@<digest> oci-archive:<file>`. Loaded with
  `podman load` and tagged with its name, it is found by `<name>[:<tag>]@<digest>`, and `podman create --pull=never`
  takes it. The runner's archive is 39 MB. GitHub's `ubuntu-24.04` image carries skopeo beside podman, so the
  step checks for it and fails plainly if it is missing.
- One step before the live test reads both references out of `ForgejoContainer.java` with `sed`, so the Java constants
  stay the only pin, and fails if it finds fewer than two. The key is `forgejo-images-` followed by the SHA-256 of both
  references. `actions/cache/restore` brings back the folder the archives lie in. On a miss, each archive is fetched by
  skopeo, tried again by `pinned_retry` of the `pinned-jdk` action's `pinned.sh`, then `actions/cache/save` stores the
  folder, as the `pinned-jdk` action does for shfmt. On a hit or after a fetch, each archive is loaded and tagged with
  its name, and `podman image exists` must find each reference before the test starts.
- Removal: GitHub's cache deletes an entry that no run has read for 7 days, and removes the least recently used
  entries when a repository goes over its cache size. An entry for an old pair of digests is no longer read once the
  constants change, so it is gone within a week. That needs no `actions: write` for the job's token and no step of its
  own. The Maven cache's older entries go the same way.
- No ready-made action: none of them keeps an index digest, and the step is a few lines on tools the runner already
  carries.
- A test that goes red first: none, since only a workflow changes. The step is run locally first, with skopeo from its
  own container image standing in for the runner's, followed by the live test with `--pull=never` proven on the
  loaded images.

## Acceptance

- A second Build on the same digests loads both images from the cache, and its log shows no request to codeberg.org or
  code.forgejo.org.
- A cache entry for digests no constant names any more is gone within 7 days, removed by GitHub.
