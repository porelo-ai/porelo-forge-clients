# Issues

The open tasks of `porelo-forge-clients`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `FC`. A group with nothing in it
is left out.

## Review

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc04"></a>[FC04](FC04-Records-Generated-From-A-Pinned-OpenAPI-Description.md) | M | - | Records generated at build time by openapi-generator (a pinned snapshot until `useRecords` is released) from a pinned, filtered OpenAPI 3.1 or Swagger 2.0 description, with overridden templates; blocks [FC01](README.md#fc01) and [FC02](README.md#fc02) |

## Implementing

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc02"></a>[FC02](FC02-The-Forgejo-Client.md) | M | [FC04](README.md#fc04) | The `forgejo` module: 19 operations from Forgejo 17's MIT Swagger 2.0 description, its answers recorded from a real Forgejo |
| <a id="fc01"></a>[FC01](FC01-The-GitHub-Client.md) | L | [FC04](README.md#fc04) | The `github` module: 18 REST operations from GitHub's MIT OpenAPI 3.1 description, review threads over GraphQL, no webhooks, tested against recorded answers |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc05"></a>[FC05](FC05-Keys-And-Repositories-On-Forgejo.md) | S | [FC02](README.md#fc02) | A Forgejo user's public SSH keys read without a token, a repository read and created in an organization |
| <a id="fc06"></a>[FC06](FC06-Keys-Repositories-And-Commit-Statuses-On-GitHub.md) | S | [FC01](README.md#fc01) | A GitHub user's public SSH authentication and signing keys read without a token, a repository read and created in an organization, a commit's combined status |
| <a id="fc03"></a>[FC03](FC03-The-Forge-Calls-Of-The-Adapters-Move-Here.md) | - | [FC01](README.md#fc01), [FC02](README.md#fc02) | The clients make every call Porelo's build adapters make to GitHub and Forgejo today, with the same requests and answers, published as snapshots |
