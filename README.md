# porelo-forge-clients

<img src="doc/images/crazy-chicken.svg" width="350" alt="Crazy chicken - just hatched">

> **Crazy chicken - just hatched.** Interfaces, formats and even the purpose may change any day. Use it only to try ideas, never to build on.

Small Java clients of the forges' and build servers' APIs, one module per forge or build server, published to Maven
Central under `org.fuin.porelo`. They cover only what a code review and a build need - pull requests, reviews, review
comments and threads, suggestions, checks and build results, webhooks, the account and its membership - and are not
complete clients of any API. They know nothing of Porelo: Porelo's adapters use them and translate what they return.

- [openapi-extract](openapi-extract/README.md) - the command that keeps a client's extract of a forge's pinned
  OpenAPI description, built here and never published.

The records of each client are generated at build time by openapi-generator, with the templates in
`openapi-templates/` overriding its own. `github` and `forgejo` come first.

## Building

```
./mvnw -B -s settings.xml verify
```

JDK 25. At run time a client needs only the JDK's `java.net.http.HttpClient` and Jackson; its tests run against its
forge's recorded answers, with no network. A dependency on any Porelo artifact fails the build. See
[AGENTS.md](AGENTS.md) for the conventions.

## License

LGPL-3.0-or-later, see [LICENSE](LICENSE).
