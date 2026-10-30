# FC02 — The Forgejo Client

**Status:** Analyzed; blocked by [FC04](README.md#fc04).
**Size:** M - one module of nineteen operations, records generated, its answers recorded from a real Forgejo.

**What must be true.** A caller reads and writes what a code review and a build need on Forgejo through the
`forgejo` module, `org.fuin.porelo:porelo-forge-client-forgejo`: pull requests, reviews and their comments,
suggestions, commit statuses and Actions runs, jobs and their logs, branches, webhooks, and the account and its
organization membership - in Forgejo's own terms, knowing nothing of Porelo.

## Why

Forgejo serves its API as Swagger 2.0 (`templates/swagger/v1_json.tmpl` in its repository, `/swagger.v1.json` on a
running server). The repository is GPL-3.0, but the file itself says it is distributed under the MIT license for
interoperability. Forgejo 17's file has 331 paths; every one below is in it, the Actions runs, jobs and logs
included. Forgejo has no API to resolve a review conversation; reviews can be dismissed and undismissed, which the
client offers in its place. The file describes no webhook payloads.

## Plan

- **The operations, 19, reaching 43 definitions with 351 properties (measured on Forgejo 17):**
  `GET`/`POST /repos/{owner}/{repo}/pulls`, `GET .../pulls/{index}`, `GET`/`POST .../pulls/{index}/reviews`,
  `GET .../reviews/{id}/comments`, `POST .../reviews/{id}/dismissals`, `POST .../reviews/{id}/undismissals`,
  `GET`/`POST .../statuses/{sha}`, `GET .../actions/runs`,
  `GET .../actions/runs/{run_id}/jobs`, `GET .../actions/jobs/{job_id}/logs`, `GET .../branches/{branch}`,
  `GET`/`POST .../hooks`, `GET /user`, `GET /user/orgs`, `GET .../collaborators/{collaborator}/permission`.
- **Files:** the root `pom.xml` and `clients/pom.xml` (made by whichever of the two clients comes first),
  `clients/forgejo/pom.xml` and its `README.md`, `clients/forgejo/src/main/openapi/` (the extract of Forgejo 17's
  file, `SOURCE` with the commit of tag `v17.0.1`, `363f3dc`, and the digest, the MIT notice),
  `org.fuin.porelo.forge.forgejo.ForgejoClient` (`HttpClient` and base URI given, the token passed per call),
  `ForgejoWebhook` (checks `X-Forgejo-Signature`; its payload records written by hand from recorded deliveries of
  the pull request, review, review comment and Actions run events), `ForgejoError`.
- **Recordings:** made from a Forgejo 17 in podman on the local VM, by a script of the module's tests that starts
  it, creates what it records and removes it after, failure too.
- **Steps:** the module and its extract; the build calls the adapters use first; then pull requests and reviews;
  then statuses, hooks and the account.
- **Red first:** `ForgejoClientTest` calls `runs(owner, repo, sha)` against the replayed answer with one field
  renamed and must fail, in the strict reading, naming that field.

## Acceptance

- Every call is tested against answers recorded from a real Forgejo, with no network; a recorded answer with a
  changed field makes a test fail by that field's name.
- At run time the module needs only the JDK's `java.net.http.HttpClient`, Jackson and its own records; the build
  fails on a dependency on any Porelo artifact.
- A token reaches the client from its caller for each call, never from a file, a log line or a message of an
  exception; a forge signed by a private certificate authority is reached with the `HttpClient` its caller passes.
- A webhook delivery whose signature does not match its secret is refused before its JSON is parsed, its raw
  body read only up to a bound.

## Open questions

- **Which Forgejo versions?** Recommendation: the current release, 17, and from then on each new major release
  replaces it; an older server is not promised to work.

## To be checked

- The image of Forgejo 17 to record from, pinned by version and digest.
- Which webhook events Forgejo 17 sends for pull requests, reviews, review comments and Actions runs, and their
  names in the `X-Forgejo-Event` header.
