# FC04 — Records Generated From A Pinned OpenAPI Description

**Status:** Implementing.
**Size:** M - the generator's configuration, its overridden templates and a small extract command, no client yet.

**What must be true.** A client module gets one Java record per schema its operations use, generated at build time
by openapi-generator from a forge's OpenAPI description that is pinned by its source commit and SHA-256 digest and
filtered to the operations the module lists, with nothing at run time but Jackson's annotations and JSpecify.

## Plan

- **openapi-generator's `spring` generator, models only**, configured once in the root's `pluginManagement`
  (execution `records`) and run in a client module's `generate-sources`, which names its extract and package:
  `useRecords`, `useJspecify`, `openApiNullable=false`, `useBeanValidation=false`, `annotationLibrary=none`,
  `documentationProvider=none`, `generateApis=false`, and of the supporting files only `package-info.java`, which
  `useJspecify` writes with `@NullMarked`, so NullAway checks the code that reads the records. It reads OpenAPI 3.1
  with its `webhooks` and Swagger 2.0 as they are; a field is JSpecify's `@Nullable` exactly when the description
  lets it be absent or null (Swagger 2.0 cannot say null, so there every field that is not required may be).
- **A snapshot until `useRecords` is released:** it is merged after 7.26.0, so the plugin is pinned to the
  timestamped snapshot `7.27.0-20261030.081701-149` from Sonatype's snapshot repository, which `settings.xml`
  already names; the first release that has it replaces the pin, and no release of this repository is made
  before. A pinned snapshot that Sonatype has removed fails the build, naming it, and is moved to a newer one.
- **What the generator gets wrong is fixed in its configuration and by overriding its templates, never in the
  generated code.** The overrides sit in `openapi-templates/`, given as `templateDirectory`, each a copy of the
  pinned generator's own with the change:
  - `model.mustache`, `record.mustache`, `pojo.mustache` and an empty `generatedAnnotation.mustache` leave out
    Spring's `@DateTimeFormat` and `jakarta.annotation.Generated`; the import the generator adds for every date is
    mapped to `java.time.OffsetDateTime`, a harmless duplicate. Nothing of Spring or Jakarta is needed to compile.
  - `jackson_annotations.mustache` marks a required field `@JsonProperty(required = true)`, which Jackson enforces
    on a record's creator, and `record.mustache` adds a compact constructor that refuses null in a required field
    the description does not let be null, naming the JSON field.
  - `enumClass.mustache`, `enumOuterClass.mustache`, `additionalProperties.mustache`, `oneof_interface.mustache`
    and the record's discriminator accessor say `@Nullable` where the generator's code returns null, so NullAway
    passes.
  - The normalizer `REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY` drops a `oneOf` or `anyOf` that only narrows its
    schema's own properties (GitHub's `checks-create-request` and `checks-update-request`); a schema that keeps
    `additionalProperties` stays a class (GitHub's `integration-permissions`), null-safe through the override.
  - A test fails when the pinned generator's template differs from the one an override was copied from, naming
    it, so moving the pin re-copies each.
- **The extract is committed, the full description is not:** a client module keeps the filtered extract in
  `src/main/openapi/`, the source's URL (pinned by commit) and digest in its `info`, the license notice in the
  module's `README.md`. The command, `openapi-extract`, a Java module never published, run by `exec-maven-plugin`:
  - `fetch` downloads the pinned source, refuses it before writing anything when its digest differs, replaces a
    template's placeholders (Forgejo's two strings), keeps the listed operations and webhooks and the components
    they reach, and drops the `discriminator` of a schema whose `oneOf` or `anyOf` only narrows its own properties,
    since without its `oneOf` the generator would make it a class;
  - `check`, at every build, fails naming a listed operation missing from the extract, an operation in it not
    listed, and a component nothing listed reaches.
- **Strict in the tests, lenient at run time:** every reader of a client module - its calls, GraphQL answers and
  webhook deliveries alike - is built with a strictness, its caller's choice. By default a field it does not know
  is ignored, since a forge adds fields within one API version; the tests build it strict, refusing unknown
  fields. In both, a required field may not be missing, and a field the description does not let be null may not
  be null.

## Acceptance

- In the strict reading, a recorded answer with a field the record does not know fails to read, naming the field;
  the default reading passes it. In both, an answer that leaves out a required field, or sets to null a field the
  description does not let be null, fails, naming it; a required field that may be null passes as null, an optional
  one that may not passes when absent.
- The generated code compiles with NullAway as an error and with only `jackson-annotations` and `jspecify` on the
  class path; a field is `@Nullable` exactly when the description lets it be absent or null.
- A schema the listed operations do not reach is not generated; an operation listed but not in the description
  stops the build, naming it.
- A downloaded description whose digest differs from the pin is refused before anything is written.
- A JSON name that is not a Java name gets one that compiles and reads it back: GitHub's `+1` and `-1`.

## To be checked

- A value of an enum the record does not know fails to read in both readings, since the generator's `fromValue`
  throws; whether the lenient reading must pass it, as a forge adds such values (GitHub's check conclusions) within
  one API version, is decided with the first client that reads one.
