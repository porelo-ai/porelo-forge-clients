# FC15 — Sources And Javadoc Jars In A Release

**Status:** Idea

**What must be true.** A release of the clients carries a sources jar and a javadoc jar beside each client's jar, as
Maven Central requires, so a tag `v<version>` passes Central's validation instead of failing it once the release is
under way. Today the effective pom under `central-sonatype-release` runs neither `maven-source-plugin` nor
`maven-javadoc-plugin`; a snapshot is not held to that and publishes without them.
