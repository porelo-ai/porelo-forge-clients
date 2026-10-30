# FC04 — Records Generated From A Pinned OpenAPI Description

**Status:** Review.
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
    the description does not let be null, naming the JSON field, before any default the generator assigns: a
    required field gets no default.
  - `enumClass.mustache`, `enumOuterClass.mustache`, `additionalProperties.mustache`, `oneof_interface.mustache`
    and the record's discriminator accessor say `@Nullable` where the generator's code returns null, so NullAway
    passes.
  - The normalizer `REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY` drops a `oneOf` or `anyOf` that only narrows its
    schema's own properties (GitHub's `checks-create-request` and `checks-update-request`); a schema that keeps
    `additionalProperties` stays a class (GitHub's `integration-permissions`, which has no required field).
  - The overrides cover the shapes the two forges' extracts have. A shape they do not - a class with a required
    field or a parent, a `byte[]` or qualified type behind an added `@Nullable`, a `oneOf` branch without its
    discriminator - fails its client's compile under NullAway, naming the file, and is fixed then.
  - A test fails when the pinned generator's template differs from the one an override was copied from, naming
    it, so moving the pin re-copies each.
- **`openapi-records`, a module never published,** generates records from a small fixture description with the
  same configuration and compiles them as main code under NullAway with only `jackson-annotations` and `jspecify`;
  its tests read JSON into them in both readings.
- **The extract is committed, the full description is not:** a client module keeps the filtered extract in
  `src/main/openapi/`, the source's URL (pinned by commit) and digest in its `info`, the license notice in the
  module's `README.md`. The command, `openapi-extract`, a Java module never published, run by `exec-maven-plugin`:
  - `fetch` downloads the pinned source, following redirects but never from HTTPS to HTTP, sending no
    credential, refusing an answer other than 200, naming the source, and a body over 64 MB; it refuses the
    body before writing anything when its digest differs, replaces a
    template's placeholders (Forgejo's two strings), keeps the listed operations and webhooks and the components
    they reach, and drops the `discriminator` of a schema whose `oneOf` or `anyOf` only narrows its own properties,
    since without its `oneOf` the generator would make it a class; a property a forge sends but its description
    lacks is added by name (`--add-property`), refused when the description already has it, and recorded in the
    extract's `info`;
  - `check`, at every build, fails naming a listed operation missing from the extract, an operation in it not
    listed, and a component nothing listed reaches.
- **Strict in the tests, lenient at run time:** every reader of a client module - its calls, GraphQL answers and
  webhook deliveries alike - is built with a strictness, its caller's choice. By default a field it does not know
  is ignored, since a forge adds fields within one API version; the tests build it strict, refusing unknown
  fields. In both, a required field may not be missing nor null unless the description lets it be null; an
  optional field may be absent or null, since forges send null for optional fields their descriptions do not mark.

## Acceptance

- In the strict reading, a recorded answer with a field the record does not know fails to read, naming the field;
  the default reading passes it. In both, an answer that leaves out a required field, or sets it to null when the
  description does not let it be null, fails, naming it; a required field that may be null passes as null; an
  optional field passes absent or null.
- An enum value the record does not know fails to read in both readings, naming it, when the description does not
  let the enum be null; when it does, the generator's `fromValue` reads it as null in both.
- The generated code compiles with NullAway as an error and with only `jackson-annotations` and `jspecify` on the
  class path; a field is `@Nullable` exactly when the description lets it be absent or null.
- A schema the listed operations do not reach is not generated; an operation listed but not in the description
  stops the build, naming it.
- A downloaded description whose digest differs from the pin is refused before anything is written.
- A JSON name that is not a Java name gets one that compiles and reads it back: GitHub's `+1` and `-1`.
