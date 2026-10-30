# Issues

The open tasks of `porelo-forge-clients`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `FC`. A group with nothing in it
is left out.

## Analyzed

| # | Size | Blocked by | What it covers | Open questions |
|---|---|---|---|---|
| <a id="fc01"></a>[FC01](FC01-The-GitHub-Client.md) | L | [FC04](README.md#fc04) | The `github` module: 20 REST operations and the webhooks from GitHub's MIT OpenAPI 3.1 description, review threads over GraphQL, tested against recorded answers | 1 |
| <a id="fc02"></a>[FC02](FC02-The-Forgejo-Client.md) | M | [FC04](README.md#fc04) | The `forgejo` module: 17 operations from Forgejo 17's MIT Swagger 2.0 description, its answers recorded from a real Forgejo | 1 |

## Accepted

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc03"></a>[FC03](FC03-The-Forge-Calls-Of-The-Adapters-Move-Here.md) | - | [FC01](README.md#fc01), [FC02](README.md#fc02) | The clients make every call Porelo's build adapters make to GitHub and Forgejo today, with the same requests and answers, published as snapshots |

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc04"></a>[FC04](FC04-Records-Generated-From-A-Pinned-OpenAPI-Description.md) | - | - | `codegen`: records generated at build time from a pinned, filtered OpenAPI 3.1 or Swagger 2.0 description, with Java and Jackson only; blocks [FC01](README.md#fc01) and [FC02](README.md#fc02) |
