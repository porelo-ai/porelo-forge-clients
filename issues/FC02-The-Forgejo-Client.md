# FC02 — The Forgejo Client

**Status:** Analyzed; blocked by [FC04](README.md#fc04).
**Size:** M - one module of nineteen operations, records generated, its answers recorded from and checked live
against a Forgejo container on every build.

**What must be true.** A caller reads and writes what a code review and a build need on Forgejo through the
`forgejo` module, `org.fuin.porelo:porelo-forge-client-forgejo`: pull requests, reviews and their comments,
suggestions, commit statuses and Actions runs, jobs and their logs, branches, webhooks, and the account and its
organization membership - in Forgejo's own terms, knowing nothing of Porelo. Only Forgejo 17 is served; an
older or newer major release is not promised to work.

## Why

Forgejo serves its API as Swagger 2.0 (`templates/swagger/v1_json.tmpl` in its repository, `/swagger.v1.json` on a
running server). The repository is GPL-3.0, but the file itself says it is distributed under the MIT license for
interoperability. Forgejo 17's file has 331 paths; every one below is in it, the Actions runs, jobs and logs
included. Forgejo has no API to resolve a review conversation. A review as a whole can be dismissed and
undismissed, which withdraws its verdict, needs the repository's administration right, and is offered as that. The
file describes no webhook payloads.

## Plan

- **The operations, 19, reaching 43 definitions with 351 properties (measured on Forgejo 17):**
  `GET`/`POST /repos/{owner}/{repo}/pulls`, `GET .../pulls/{index}`, `GET`/`POST .../pulls/{index}/reviews`,
  `GET .../reviews/{id}/comments`, `POST .../reviews/{id}/dismissals`, `POST .../reviews/{id}/undismissals`,
  `GET`/`POST .../statuses/{sha}`, `GET .../actions/runs`,
  `GET .../actions/runs/{run_id}/jobs`, `GET .../actions/jobs/{job_id}/logs`, `GET .../branches/{branch}`,
  `GET`/`POST .../hooks`, `GET /user`, `GET /user/orgs`, `GET .../collaborators/{collaborator}/permission`.
- **Files:** the root `pom.xml` and `clients/pom.xml` (made by whichever of the two clients comes first),
  `clients/forgejo/pom.xml` and its `README.md`, `clients/forgejo/src/main/openapi/` (the extract of Forgejo 17's
  file, `SOURCE` with the commit of tag `v17.0.1`, `363f3dc30beef902b187af5a5244ff7af44901e6`, and the digest, the
  MIT notice), `org.fuin.porelo.forge.forgejo.ForgejoClient` (`HttpClient` and base URI given, refused if it follows
  redirects itself; the token passed per call and sent only to the base's own origin; a job log's redirect, where
  the server answers with one, followed by hand),
  `ForgejoWebhook` (checks `X-Forgejo-Signature`; its payload records written by hand from recorded deliveries of
  the pull request, review, review comment and Actions run events), `ForgejoError`.
- **Recordings, as for every open-source forge:** the unit tests replay them alone, with no network; they are made
  from a container of `codeberg.org/forgejo/forgejo:17.0.1`, pinned by its index digest
  `sha256:fcd19ff4ebc85fb098a67e8e1d0d5a84d07dd6e8a23edcfdfd5b71a6178953be`. Its accounts are made for the recording;
  its addresses become `example.org` before a recording is kept, and a delivery is then signed again with a test
  secret; a test fails on a delivery whose signature does not verify with it.
- **Seeded, then checked live on every build:** an integration test (`ForgejoLiveIT`, failsafe) starts the container
  with podman on a free port of the loopback address and a volume of its own, both named with the run's id. It
  creates the administrator inside the container with a random password and an access token printed once, which
  it reads from the command's output into memory and never logs; no password or token is on a command line. It
  seeds through the API - an organization, a repository with two branches, two users, a pull request with an
  approving review by the second user and a comment, a commit status, a hook, and an Actions run with its job - and
  runs every call in strict reading, failing when a call it checks answers an empty list. It removes the container
  and volume it made, after a failure and on an interrupt too, refuses to start over a container or volume of its
  prefix left behind, and exits non-zero naming anything it could not remove. The Build runs it as its own step
  after the unit tests.
- **Steps:** the module and its extract; the build calls the adapters use first; then pull requests and reviews;
  then statuses, hooks and the account.
- **Red first:** `ForgejoClientTest` builds the client strict and calls `runs(owner, repo, sha)` against the
  replayed answer with one field renamed, which must fail naming that field.

## Acceptance

- Every call is tested against answers recorded from a real Forgejo, with no network; a recorded answer with a
  changed field makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; a forge signed by a private certificate authority is reached with the `HttpClient` its caller passes.
- A webhook delivery whose signature does not match its secret is refused before its JSON is parsed, its raw
  body read only up to a bound.
- A redirect to another origin carries no token, seen in a test that fails when it does; an `HttpClient` that
  follows redirects itself is refused when the client is built.

## To be checked

- How long the live check takes on a GitHub runner, from start of the container to its removal, measured.
- Whether an Actions run and its job can be seeded without a runner attached, or the live check needs a Forgejo
  runner container beside it.
- Which options of `forgejo admin user create` give a random password and an access token on its output in
  Forgejo 17.
- Which webhook events Forgejo 17 sends for pull requests, reviews, review comments and Actions runs, and their
  names in the `X-Forgejo-Event` header.
