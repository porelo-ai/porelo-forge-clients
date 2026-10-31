# Issues

The open tasks of `porelo-forge-clients`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `FC`. A group with nothing in it
is left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc05"></a>[FC05](FC05-Keys-And-Repositories-On-Forgejo.md) | S | [FC02](README.md#fc02) | A Forgejo user's public SSH keys read, the signed-in user's own keys listed, added and deleted, whether a user owns an organization, a repository read and created in an organization |
| <a id="fc08"></a>[FC08](FC08-Comments-Threads-And-Versions-Through-The-Clients.md) | L | [FC02](README.md#fc02) | A pull request's general comments read since a time, written, edited and deleted on both forges, GitHub review comments edited and deleted, one review's comments, a merge base, one GitHub thread with its state and resolver read by its id, a pull request found by its branch, Forgejo's version, and what Forgejo's API cannot do stated |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc02"></a>[FC02](FC02-The-Forgejo-Client.md) | M | - | The `forgejo` module: 19 operations from Forgejo 17's MIT Swagger 2.0 description, its answers recorded from a real Forgejo |

## Backlog

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc06"></a>[FC06](FC06-Keys-Repositories-And-Commit-Statuses-On-GitHub.md) | M | - | A GitHub user's public SSH authentication and signing keys read without a token, the signed-in user's own keys of both kinds listed, added and deleted, whether a user owns an organization, a repository read and created in an organization, a commit's combined status |
| <a id="fc09"></a>[FC09](FC09-Rate-Limits-And-Bounds-Of-The-Clients-Answers.md) | M | [FC02](README.md#fc02) | A rate limit told from a refusal with the delay to wait, the end of a paged list known, whether a failed request was sent, and every answer read up to a bound, on both forges |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc03"></a>[FC03](FC03-The-Forge-Calls-Of-The-Adapters-Move-Here.md) | - | [FC02](README.md#fc02) | The clients make every call Porelo's build adapters make to GitHub and Forgejo today, with the same requests and answers, published as snapshots |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc10"></a>[FC10](FC10-A-Pull-Requests-Commits-On-GitHub.md) | - | - | A GitHub pull request's commits read page by page, each with its revision, the author's and committer's linked accounts and GitHub's signature verdict |
| <a id="fc11"></a>[FC11](FC11-A-Pull-Requests-Commits-On-Forgejo.md) | - | [FC02](README.md#fc02) | A Forgejo pull request's commits read page by page, each with its revision, the author's and committer's linked accounts and Forgejo's signature verdict, and any cap on the list |
| <a id="fc12"></a>[FC12](FC12-GitHubs-Recorded-Answers-For-Other-Repositories.md) | - | - | GitHub's recorded answers and the replaying `HttpClient` in a `tests` jar of the github module, for the adapters' tests |
| <a id="fc13"></a>[FC13](FC13-GitHubs-Nulls-For-A-Deleted-Fork-And-An-Empty-Review.md) | - | - | A GitHub pull request from a deleted fork (`head.repo` null) and a review with a null `body` read instead of failing the read |

## Finished

| # | What was built |
|---|---|
| <a id="fc01"></a>FC01 — The GitHub Client | The `github` module: 18 REST operations from GitHub's OpenAPI description and review threads over GraphQL, tested strictly against answers recorded live and scrubbed, with the hand-run recorder and the live check; described in [clients/github/README.md](../clients/github/README.md). |
| <a id="fc07"></a>FC07 — Plain Key Lists On Forgejo | `ForgejoClient.plainKeys` reads an account's public SSH keys from its `<login>.keys` page without a token, and a missing login is told apart from any other failure. |
| <a id="fc04"></a>FC04 — Records Generated From A Pinned OpenAPI Description | Records generated at build time by openapi-generator from a pinned, filtered OpenAPI 3.1 or Swagger 2.0 extract with overridden templates, the `openapi-extract` command and the `openapi-records` check; described in [doc/records.md](../doc/records.md). |
