# FC06 — Keys, Repositories And Commit Statuses On GitHub

**Status:** Accepted; blocked by [FC01](README.md#fc01).
**Size:** S - five operations added to the `github` module and its tests, no contract other repositories read.

**What must be true.** A caller reads a GitHub user's public SSH authentication keys and SSH signing keys without a
token, reads a repository, creates a repository in an organization, and reads a commit's statuses, through the
`github` module, in GitHub's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET /users/{username}/ssh_signing_keys`,
  `GET /repos/{owner}/{repo}`, `POST /orgs/{org}/repos` and `GET /repos/{owner}/{repo}/commits/{ref}/status`, added to
  the module's operation list; the extract is cut again from the same pinned OpenAPI 3.1 file, and `check` passes
  with the five.
- **`GitHubClient`:** `keys(login)` and `signingKeys(login)` take no token and send no `Authorization` header;
  `repository(token, owner, repo)`, `createRepository(token, org, body)` and `status(token, owner, repo, ref)`, the
  combined status with its list of statuses. Each answer is read in the client's strictness.
- **Recordings and live check:** the recording test records the five in the test organization; the repository it
  creates carries the run's prefix and is removed after, a failure and an interrupt too. Creating a repository in
  the organization needs the test user's token to hold the organization's administration permission. `live.yml`
  checks the four reads.
- **Red first:** `GitHubClientTest` calls `signingKeys` against the replayed answer with one field renamed, which
  must fail naming it in the strict reading, and asserts that the request carried no `Authorization` header.

## Acceptance

- The five calls are tested against GitHub's recorded answers, with no network.
- `keys` and `signingKeys` send no token, seen in a test that fails when they do.
- A repository the recording test creates is gone after the run, a failed run too.

## To be checked

- Whether the test user's fine-grained token may create a repository in the test organization, or the organization
  must allow it first.
