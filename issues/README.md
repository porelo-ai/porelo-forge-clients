# Issues

The open tasks of `porelo-forge-clients`, grouped by state; the order within a group is the priority. The rules for an
issue, and who sets which state, are in [AGENTS.md](../AGENTS.md). *Open questions*, in *Analyzed* only, counts what
waits on the operator: the items under an issue's own *Open questions*. Its prefix is `FC`. A group with nothing in it
is left out.

## Idea

| # | Size | Blocked by | What it covers |
|---|---|---|---|
| <a id="fc01"></a>[FC01](FC01-The-GitHub-Client.md) | - | - | The `github` module: pull requests, reviews, threads over GraphQL, suggestions, checks, Actions runs and logs, refs, webhooks, the account and its membership, tested against recorded answers |
| <a id="fc02"></a>[FC02](FC02-The-Forgejo-Client.md) | - | - | The `forgejo` module: the same for Forgejo, its answers recorded from a real Forgejo |
| <a id="fc03"></a>[FC03](FC03-The-Forge-Calls-Of-The-Adapters-Move-Here.md) | - | [FC01](README.md#fc01), [FC02](README.md#fc02) | The clients make every call Porelo's build adapters make to GitHub and Forgejo today, with the same requests and answers, published as snapshots |
