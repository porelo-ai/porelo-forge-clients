# Contributing

Changes to porelo-forge-clients follow the project's guide: how an issue is written, how a change is proposed and
reviewed, and what a commit carries. It is one page for every Porelo repository:
[Contributing to Porelo](https://porelo-ai.github.io/porelo-project/contributing/).

## In this repository

- **Building and testing** is as the README's [Building](README.md#building) says: JDK 25, and
  `./mvnw -B -s settings.xml verify` builds every module, runs the tests against each forge's recorded answers with
  no network, and runs the shared checks (`check-actions`, `check-shared`, `check-citations`, `check-readmes`).
  `./mvnw -B -s settings.xml -Pdocuments test` runs the tests that read documents.
- **What each client covers** is in its own README, [github](clients/github/README.md) and
  [forgejo](clients/forgejo/README.md); how its records are generated is [doc/records.md](doc/records.md), and why
  it is built as it is, [the decisions](doc/decisions.md).
- **Requirements** are in [issues/README.md](issues/README.md).
