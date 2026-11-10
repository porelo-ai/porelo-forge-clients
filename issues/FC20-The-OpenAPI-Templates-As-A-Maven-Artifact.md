# FC20 — The OpenAPI Templates As A Maven Artifact

```json
{
  "$schema": "issue-1.json",
  "id": "FC20",
  "title": "The OpenAPI Templates As A Maven Artifact",
  "state": "Backlog",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The overridden openapi-generator templates published as a jar another repository adds to the generator plugin, read by `templateResourcePath`, with the check that names a template whose original moved; the clients use the same jar"
}
```

**Why this size.** a new published module, the template test moved into it, the clients' generation switched to it, and
a contract other repositories build against: its resource path, the generator version it fits and its license.

**What must be true.** Another repository generates records with the same templates as the clients by adding one
published artifact as a dependency of `openapi-generator-maven-plugin` and naming its resource path, with no copy of
a template of its own; the artifact says which generator release it fits, and a move of that release that changes an
original fails this repository's build, naming the template.

## Plan

- **The module.** `openapi-templates/` becomes a Maven module, `porelo-forge-client-openapi-templates`, packaging
  `jar`, published with the clients. The `*.mustache` files move to
  `src/main/resources/org/fuin/porelo/forge/openapi/templates/`, so the plugin reads them by
  `templateResourcePath` `/org/fuin/porelo/forge/openapi/templates`. The jar carries no class and no dependency.
- **The check moves with them.** `TemplateOverrideTest` moves from `openapi-extract` into the new module and reads the
  overrides from its own resources; its two tests stay as they are, so the digest of each original is held where the
  templates live. `openapi-extract` loses its test dependency on `openapi-generator`.
- **The clients use the jar.** The root's `records` execution replaces `templateDirectory` by `templateResourcePath`
  and adds the new module as a dependency of the plugin; the reactor builds it before `openapi-records` and the
  clients. `openapi-records` stays the check that the generated records compile under NullAway with only
  `jackson-annotations` and `jspecify`.
- **What a user reads.** The module's `README.md` names the generator release the templates fit (the root's
  `openapi-generator.version`, read from the build into the jar's manifest), the plugin configuration of the
  `records` execution to copy, the two dependencies the generated code needs at compile time and that it is models
  only. `doc/records.md` points to it instead of `openapi-templates/` as a directory. A changelog entry under *added*.
- **License.** Each template is a copy of openapi-generator's own, under Apache-2.0, with a change. The jar carries a
  `NOTICE` naming that origin and its license, and the module's license is Apache-2.0, as for an interface others
  build against; the clients stay `LGPL-3.0-or-later`.
- **Red first.** The client build with `templateDirectory` removed and the jar not yet on the plugin's class path
  fails, since the generator's own templates write `@DateTimeFormat`, which does not compile without Spring.

## Acceptance

- `./mvnw -s settings.xml clean verify` passes with the templates read only from the jar: no `templateDirectory`
  remains, and removing the jar from the plugin's dependencies fails a client's compile.
- A throwaway project outside this repository generates a record from a three-property description with only the
  installed jar added to the plugin, and the record compiles with `jackson-annotations` and `jspecify` alone.
- `TemplateOverrideTest` fails, naming the template, when one original's digest is changed by hand.

## To be checked

- Whether the published module's flattened pom and the release build's sources and javadoc jars work for a jar
  without classes.
