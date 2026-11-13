# FC15 — Sources And Javadoc Jars In A Release

```json
{
  "$schema": "issue-1.json",
  "id": "FC15",
  "title": "Sources And Javadoc Jars In A Release",
  "state": "Idea",
  "size": "-",
  "priority": "Low",
  "rank": 1,
  "blockedBy": [],
  "covers": "A release carries each client's sources and javadoc jars, which Maven Central requires and the release build does not make"
}
```

**What must be true.** A release of the clients carries a sources jar and a javadoc jar beside each client's jar, as
Maven Central requires, so a tag `v<version>` passes Central's validation instead of failing it once the release is
under way. Today the effective pom under `central-sonatype-release` runs neither `maven-source-plugin` nor
`maven-javadoc-plugin`; a snapshot is not held to that and publishes without them.
