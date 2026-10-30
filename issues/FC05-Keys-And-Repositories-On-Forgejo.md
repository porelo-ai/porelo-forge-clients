# FC05 — Keys And Repositories On Forgejo

**Status:** Analyzed; blocked by [FC02](README.md#fc02).
**Size:** S - three calls added to the `forgejo` module and its tests; no existing call changes.

**What must be true.** A caller reads a Forgejo user's public SSH keys without a token, reads a repository, and
creates a repository in an organization, through the `forgejo` module, in Forgejo's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET /repos/{owner}/{repo}` and `POST /orgs/{org}/repos`, added
  to the `--operation` list in `clients/forgejo/pom.xml`; the extract `clients/forgejo/src/main/openapi/forgejo.json`
  is fetched again from the same pin, and `check` passes with the three.
- **`ForgejoClient`:** `keys(login, page, limit)` takes no token and sends no `Authorization` header, paged as every
  other list; `repository(token, owner, repo)`; `createRepository(token, org, body)` with the generated
  `CreateRepoOption`. Each answer is read in the client's strictness, as every other call.
- **The fixture:** `ForgejoContainer.seedAccountsAndRepository` gives one fixture user an SSH public key made for the
  test, so `keys` answers a non-empty list. `ForgejoScenario` creates the second repository under a fixed name in the
  fixture organization; the container is thrown away whole after every run, so nothing is deleted by a call, and
  the recording stays the same from run to run.
- **Recordings and live check:** `ForgejoScenario` and `ForgejoOperations` gain the three, recorded from the pinned
  container as the others are; `ForgejoRecordingTest` checks `keys`'s query (`page`, `limit`). `ForgejoLiveIT` runs
  them in strict reading.
- **`clients/forgejo/README.md`:** names the three, and says that `keys` takes no token while every other call does.
- **Red first:** `ForgejoClientTest` calls `keys` against the replayed answer with one field renamed, which must fail
  naming it in the strict reading, and asserts that the request carried no `Authorization` header.

## Acceptance

- The three calls are tested against Forgejo's recorded answers, with no network, and checked live against the
  pinned container in strict reading.
- `keys` sends no token, seen in a test that fails when it does.

## To be checked

- Whether Forgejo 17 answers `GET /users/{username}/keys` without a token when `REQUIRE_SIGNIN_VIEW` is off, and
  what it answers when it is on.
