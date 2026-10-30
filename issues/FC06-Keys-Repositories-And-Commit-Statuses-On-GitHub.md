# FC06 — Keys, Repositories And Commit Statuses On GitHub

**Status:** Analyzed; blocked by [FC01](README.md#fc01).
**Size:** S - five calls added to the `github` module and its tests; no existing call changes.

**What must be true.** A caller reads a GitHub user's public SSH authentication keys and SSH signing keys without a
token, reads a repository, creates a repository in an organization, and reads a commit's statuses, through the
`github` module, in GitHub's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET /users/{username}/ssh_signing_keys`,
  `GET /repos/{owner}/{repo}`, `POST /orgs/{org}/repos` and `GET /repos/{owner}/{repo}/commits/{ref}/status`, added to
  the module's operation list; the extract is cut again from the same pinned OpenAPI 3.1 file, and `check` passes
  with the five.
- **`GitHubClient`:** `keys(login, page, perPage)` and `signingKeys(login, page, perPage)` take no token and send no
  `Authorization` header; `repository(token, owner, repo)`, `createRepository(token, org, body)` and
  `status(token, owner, repo, ref, page, perPage)`, the combined status with its list of statuses. Each answer is
  read in the client's strictness.
- **What the test organization keeps for it:** the admin test user has one SSH authentication key and one SSH
  signing key made for the test, and the commit the live check reads carries one commit status, set once by hand.
- **Recordings and live check:** the recording test records the five; the repository it creates carries the run's
  prefix and is deleted after, a failure and an interrupt too, which needs a delete call in the test code only.
  `live.yml` checks the four reads.
- **`clients/github/README.md`:** names the five, and says that the two key reads take no token.
- **Red first:** `GitHubClientTest` calls `signingKeys` against the replayed answer with one field renamed, which
  must fail naming it in the strict reading, and asserts that the request carried no `Authorization` header.

## Acceptance

- The five calls are tested against GitHub's recorded answers, with no network.
- `keys` and `signingKeys` send no token, seen in a test that fails when they do.
- A repository the recording test creates is gone after the run, a failed run too.

## To be checked

- Which fine-grained permissions the admin test user's token needs beyond those for pull requests, runs and logs:
  by GitHub's documentation, *Administration: write* on the organization's repositories (all repositories, not
  only the test repository) to create and delete one, and *Commit statuses: read*. Measured against the test
  organization, then given to the operator to set.
