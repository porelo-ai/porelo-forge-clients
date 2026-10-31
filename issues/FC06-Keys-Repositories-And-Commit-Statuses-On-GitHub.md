# FC06 — Keys, Repositories And Commit Statuses On GitHub

**Status:** Implementing (Estimated and Backlog by the operator, 2026-10-30, sprint 6m).
**Size:** M - thirteen calls added to the `github` module, its recordings and its live check; no existing call changes.

**What must be true.** A caller reads a GitHub user's public SSH authentication keys, through the API and as the
lines of `github.com/<login>.keys`, and SSH signing keys, all without a token; lists, adds and deletes the signed-in
user's own keys of both kinds; reads whether a user owns an organization; reads and creates a repository; and reads a
commit's statuses, through the `github` module, in GitHub's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET /users/{username}/ssh_signing_keys`, `GET`/`POST /user/keys`,
  `DELETE /user/keys/{key_id}`, `GET`/`POST /user/ssh_signing_keys`,
  `DELETE /user/ssh_signing_keys/{ssh_signing_key_id}`, `GET /orgs/{org}/memberships/{username}`,
  `GET /repos/{owner}/{repo}`, `POST /orgs/{org}/repos` and `GET /repos/{owner}/{repo}/commits/{ref}/status`, added to
  the module's operation list; the extract is cut again from the same pinned OpenAPI 3.1 file, and `check` passes with
  the twelve. A thirteenth call, `plainKeys(login)`, reads `<web base>/<login>.keys` outside the API: the web base
  (`https://github.com/`) is given beside the API base, no `Authorization` header is sent, no redirect is followed,
  `text/plain` is read up to a bound, and a 404 is a `GitHubException` with status 404 while no other failure reads as
  an empty list.
- **`GitHubClient`:** `keys(login, page, perPage)` and `signingKeys(login, page, perPage)` take no token and send no
  `Authorization` header; `ownKeys`, `addKey`, `deleteKey`, `ownSigningKeys`, `addSigningKey` and `deleteSigningKey`,
  with the token, where a key GitHub already has is refused by it as an error with its status and the caller finds it in
  the list; `membership(token, org, login)`: an owner of the organization has `role` `admin` and `state` `active`, since
  an invited owner who has not accepted is `pending`; `repository(token, owner, repo)`,
  `createRepository(token, org, body)` and `status(token, owner, repo, ref, page, perPage)`, the combined status with
  its list of statuses. Each answer is read in the client's strictness.
- **What the test organization keeps for it:** the reviewer test user, a machine account, has one SSH authentication
  key and one SSH signing key made for the test, so no key of a person's own account is added or deleted; the commit
  the live check reads carries one commit status, set once by hand.
- **Tokens:** the admin test user's token, owned by the organization on all its repositories, adds *Administration:
  write* and *Commit statuses: read* to what the pull requests need. A token owned by an organization takes no account
  permissions, so the reviewer test user's own keys go through a second token owned by that account, on public
  repositories only, with *Git SSH keys: write* and *SSH signing keys: write*, given as
  `LIVE_GITHUB_REVIEWER_KEYS_TOKEN`.
- **Recordings and live check:** the recording test records the thirteen, adding a second key of each kind made for the
  test, listing it and deleting it; the repository it creates carries the run's prefix and is deleted after, which needs
  a delete call in the test code only. A key or repository a failed or interrupted run leaves is removed too. `live.yml`
  checks the eight reads.
- **`clients/github/README.md`:** names the thirteen, and says that the three key reads take no token.
- **Red first:** `GitHubClientTest` calls `signingKeys` against the replayed answer with one field renamed, which must
  fail naming it in the strict reading, and asserts that the request carried no `Authorization` header.

## Acceptance

- The thirteen calls are tested against GitHub's recorded answers, with no network.
- `keys`, `signingKeys` and `plainKeys` send no token, seen in a test that fails when they do; a 404 from `plainKeys` is
  told apart from other failures.
- A repository the recording test creates is gone after the run, a failed run too.

## To be checked

- That the permissions under *Tokens* are enough, measured by the recording against the test organization.
