# FC20 — The OpenAPI Templates As A Maven Artifact

```json
{
  "$schema": "issue-1.json",
  "id": "FC20",
  "title": "The OpenAPI Templates As A Maven Artifact",
  "state": "Implementing",
  "size": "M",
  "priority": "Medium",
  "rank": 1,
  "blockedBy": [],
  "covers": "The overridden openapi-generator templates published as a jar another repository adds to the generator plugin, read by `templateResourcePath`, with the check that names a template whose original moved; the clients use the same files"
}
```

**Why this size.** a new published module, the template test moved into it, the clients' generation switched to it, and
a contract other repositories build against: its resource path, the generator version it fits and its license;
its plan was read by a reviewer of another model and takes the answers.

**What must be true.** Another repository generates records with the same templates as the clients by adding one
published artifact as a dependency of `openapi-generator-maven-plugin` and naming its resource path, with no copy of
a template of its own; the artifact says which generator release it fits, and a move of that release that changes an
original fails this repository's build, naming the template.

## Plan

- **The module.** `openapi-templates/` becomes a Maven module, `porelo-forge-client-openapi-templates`, packaging
  `jar`, published with the clients. The `*.mustache` files move to
  `src/main/resources/org/fuin/porelo/forge/openapi/templates/`, read by the plugin's `templateResourcePath`; whether
  the path takes a leading `/` is measured, not assumed. The jar has no runtime dependency. A `@NullMarked`
  `package-info.java` with one public constant, the generator release the templates fit, gives the release build
  sources and javadoc to package, and the NullMarked test holds it.
- **The check moves with them.** `TemplateOverrideTest` moves from `openapi-extract` into the new module with the test
  dependencies `openapi-generator`, JUnit and AssertJ; `openapi-extract` loses those and its `openapi.templates`
  property. The listing test reads the template directory of the module's own sources, ignoring `META-INF`, and a new
  test holds the constant equal to the build's `openapi-generator.version`.
- **The clients use the same files.** The root's `records` execution reads the templates from the module's resource
  directory by `templateDirectory`, so a reactor build that stops before `package` never resolves a stale jar from the
  local repository. That the published jar works on the plugin's class path is shown by the throwaway project below.
- **Gaps closed in the templates.** `pojo.mustache` qualifies its `@Nullable` as
  `@org.jspecify.annotations.Nullable`. A schema with `additionalProperties` becomes a record only under
  `useSpringBoot4`, which a consumer on Spring Boot 4 sets; there `record.mustache`'s `getAdditionalProperty` returns a
  qualified `@Nullable`, and its compact constructor refuses a null value in the map, which Jackson fills past the
  mapper's setting for nulls. Both are red first in `openapi-records` through a second execution with
  `useSpringBoot4` over a fixture schema with `additionalProperties`: NullAway refuses the getter, and a read of
  `{"a": null}` succeeds.
- **What a user reads.** The module's `README.md` says in a few sentences what it is: models only, the generator
  release it fits (the constant), that it is the whole template set of an execution and is not mixed with a
  directory of one's own, that the generated code needs `jackson-annotations` and `jspecify` and is meant to compile
  under NullAway, which catches the shapes the templates do not cover. It links `doc/records.md` for the plugin
  configuration instead of copying it. The root `README.md` links the module. A changelog entry under *added*.
- **License** (operator, decided). The module is Apache-2.0, its pom's `licenses` overriding the root's; the jar
  carries `META-INF/LICENSE` with Apache-2.0's text and `META-INF/NOTICE` naming openapi-generator as the origin of
  every template and saying each was changed. `doc/decisions.md` records it, and this repository's part of
  `AGENTS.md` says that this module alone is Apache-2.0 and that its two license files are the third kind of
  non-Java file.
- **Red first.** The client build with the generator's own templates fails to compile, since they write
  `@DateTimeFormat`; and the throwaway project's record without the jar on the plugin's class path lets a null
  through where the compact constructor refuses it with the jar.

## Acceptance

- `./mvnw -s settings.xml clean verify` and `./mvnw -s settings.xml test` on a clean checkout pass with the templates
  read from the module.
- A throwaway project outside this repository, with the installed jar on the plugin's class path and
  `templateResourcePath`, generates a record from a description with a required non-nullable property and a
  `date-time` one; it compiles with `jackson-annotations` and `jspecify` alone, and its constructor refuses null in
  the required property. Without the jar the null goes through.
- `TemplateOverrideTest` fails, naming the template, when one original's digest is changed by hand.

## To be checked

- That the release profile packages the module's sources and javadoc jars and the flattened pom names Apache-2.0.
