# FC05 — Keys And Repositories On Forgejo

**Status:** Accepted; blocked by [FC02](README.md#fc02).
**Size:** S - three operations added to the `forgejo` module and its tests, no contract other repositories read.

**What must be true.** A caller reads a Forgejo user's public SSH keys without a token, reads a repository, and
creates a repository in an organization, through the `forgejo` module, in Forgejo's own terms.

## Plan

- **The operations:** `GET /users/{username}/keys`, `GET /repos/{owner}/{repo}` and `POST /orgs/{org}/repos`, added
  to the `--operation` list in `clients/forgejo/pom.xml`; the extract `clients/forgejo/src/main/openapi/forgejo.json`
  is fetched again from the same pin, and `check` passes with the three.
- **`ForgejoClient`:** `keys(login)` takes no token and sends no `Authorization` header; `repository(token, owner,
  repo)`; `createRepository(token, org, body)` with the generated `CreateRepoOption`. Each answer is read in the
  client's strictness, as every other call.
- **Recordings and live check:** `ForgejoScenario` and `ForgejoOperations` gain the three, the recording made from
  the pinned container as the others are; `ForgejoLiveIT` creates the repository under its run's prefix and removes
  it after, a failure too.
- **Red first:** `ForgejoClientTest` calls `keys` against the replayed answer with one field renamed, which must fail
  naming it in the strict reading, and asserts that the request carried no `Authorization` header.

## Acceptance

- The three calls are tested against Forgejo's recorded answers, with no network, and checked live against the
  pinned container in strict reading.
- `keys` sends no token, seen in a test that fails when it does.
- A repository the live check creates is gone after the run, a failed run too.

## To be checked

- Whether Forgejo 17 answers `GET /users/{username}/keys` without a token when `REQUIRE_SIGNIN_VIEW` is off, and
  what it answers when it is on.
