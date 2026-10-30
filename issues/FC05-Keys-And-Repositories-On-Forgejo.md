# FC05 — Keys And Repositories On Forgejo

**Status:** Review (Estimated and Backlog by the operator, 2026-10-30, sprint 6m);
blocked by [FC02](README.md#fc02).
**Size:** S - seven calls added to the `forgejo` module and its tests; no existing call changes.

**What must be true.** A caller reads a Forgejo user's public SSH keys, lists, adds and deletes the
signed-in user's own SSH keys, reads whether a user owns an organization, and reads and creates a repository, through
the `forgejo` module, in Forgejo's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET`/`POST /user/keys`, `DELETE /user/keys/{id}`,
  `GET /users/{username}/orgs/{org}/permissions`, `GET /repos/{owner}/{repo}` and `POST /orgs/{org}/repos`, added to the
  `--operation` list in `clients/forgejo/pom.xml`; the extract `clients/forgejo/src/main/openapi/forgejo.json` is
  fetched again from the same pin, and `check` passes with the seven.
- **`ForgejoClient`:** `keys(token, login, page, limit)`, paged as every other list, with a token, since Forgejo 17 asks
  for one on every `/users/{username}` path (`reqToken()` on the group in `routers/api/v1/api.go`);
  `ownKeys(token, page, limit)`, `addKey(token, body)` with the generated `CreateKeyOption`, and `deleteKey(token, id)`,
  which reads no body from Forgejo's empty answer; a key Forgejo already has is refused by it as an error with its
  status, and the caller finds it in `ownKeys`; `organizationPermissions(token, login, org)`, whose `is_owner` is true
  for an owner of the organization and left out for anyone else, so an absent value reads as null, which the caller
  takes as not an owner; `repository(token, owner, repo)`; `createRepository(token, org, body)` with the generated
  `CreateRepoOption`. Each answer is read in the client's strictness, as every other call.
- **The fixture:** `ForgejoContainer.seedAccountsAndRepository` gives one fixture user an SSH public key made for the
  test, so `keys` answers a non-empty list. `ForgejoScenario` creates the second repository under a fixed name in the
  fixture organization, and adds a second test key to the admin, lists it and deletes it; the container is thrown away
  whole after every run, so the repository is not deleted by a call, and the recording stays the same from run to run.
- **Recordings and live check:** `ForgejoScenario` and `ForgejoOperations` gain the seven, recorded from the pinned
  container as the others are; `ForgejoRecordingTest` checks the queries of `keys` and `ownKeys` (`page`, `limit`).
  `ForgejoLiveIT` runs them in strict reading.
- **`clients/forgejo/README.md`:** names the seven, and says that Forgejo asks for a token even for an account's public
  keys.
- **Red first:** `ForgejoClientTest` calls `keys` against the replayed answer with one field renamed, which must fail
  naming it in the strict reading.

## Acceptance

- The seven calls are tested against Forgejo's recorded answers, with no network, and checked live against the pinned
  container in strict reading.
- An answer without `is_owner` reads as null in both readings, seen in a test that fails when it does not.
