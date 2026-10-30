# porelo-forge-clients

> **Early bird - work in progress.** Until release 1.0.0, its code and interfaces can change without notice.

Small Java clients of the forges' and build servers' APIs, one module per forge or build server, published to Maven
Central under `org.fuin.porelo`. They cover only what a code review and a build need - pull requests, reviews, review
comments and threads, suggestions, checks and build results, webhooks, the account and its membership - and are not
complete clients of any API. They know nothing of Porelo: Porelo's adapters use them and translate what they return.

No module is here yet; `github` and `forgejo` come first.

## Building

```
./mvnw -B -s settings.xml verify
```

JDK 25. At run time a client needs only the JDK's `java.net.http.HttpClient` and Jackson; its tests run against its
forge's recorded answers, with no network. A dependency on any Porelo artifact fails the build. See
[AGENTS.md](AGENTS.md) for the conventions.

## License

LGPL-3.0-or-later, see [LICENSE](LICENSE).
