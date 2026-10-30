# FC04 — Records Generated From A Pinned OpenAPI Description

**Status:** Idea.

**What must be true.** A client module gets one Java record per schema its operations use, generated at build time
by openapi-generator from a forge's OpenAPI description that is pinned by its source commit and SHA-256 digest and
filtered to the operations the module lists, with nothing at run time but Jackson's annotations and JSpecify.

## The shape

- **openapi-generator's `spring` generator, models only**, configured once in the parent's `pluginManagement` and
  run in a client module's `generate-sources`: `useRecords`, `useJspecify`, `openApiNullable=false`,
  `useBeanValidation=false`, `annotationLibrary=none`, `documentationProvider=none`, `generateApis=false`,
  `generateSupportingFiles=false`. It reads OpenAPI 3.1 with its `webhooks` and Swagger 2.0 as they are, and
  writes records with Jackson's `@JsonProperty` into `target/generated-sources`; a field is JSpecify's `@Nullable`
  exactly when the description lets it be absent or null (Swagger 2.0 cannot say null, so there every field may
  be). A `package-info.java` written by hand marks the package `@NullMarked`, so NullAway checks the code that
  reads them.
- **A snapshot until `useRecords` is released:** it is merged after 7.26.0, so the plugin is pinned to the
  timestamped snapshot `7.27.0-20261030.081701-149` from Sonatype's snapshot repository, which `settings.xml`
  already names; the first release that has it replaces the pin, and no release of this repository is made
  before. A pinned snapshot that Sonatype has removed fails the build, naming it, and is moved to a newer one.
- **What the generator gets wrong is fixed by overriding its templates, never in the generated code:** the
  overridden templates sit in one directory, given as `templateDirectory`, each a copy of the pinned generator's
  own with the change:
  - `pojo.mustache` leaves out Spring's `@DateTimeFormat` and `jakarta.annotation.Generated`, so nothing of
    Spring or Jakarta is needed to compile;
  - the `oneOf` interface's template writes code that compiles where the generator's does not (GitHub's
    `checks-create-request`, whose interface names an enum it never writes, and guesses subtype names the
    description does not give);
  - a schema that stays a class (one with `additionalProperties`, or an `anyOf`) is named, and each is either
    made a record by the overridden template or accepted as a class.
- **The extract is committed, the full description is not:** a client module keeps the filtered extract in
  `src/main/openapi/`, with the source's commit, digest and license notice beside it. Regenerating it from the
  pinned source is one command, a Java class in an unpublished module run by `exec-maven-plugin`, which keeps the
  listed operations and webhooks and the schemas they reach, and refuses a download whose digest differs. A
  description kept as a template (Forgejo's, whose only placeholders are two strings) is valid JSON as it is and
  is read so.
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

- How a missing required field and a null in a non-nullable one are refused: the generated records carry neither
  `@JsonProperty(required = true)` nor a check; whether Jackson's creator checks are enough or the overridden
  template adds a compact constructor that checks.
- Which templates must be overridden, and how each follows the generator's own when the pin moves: a check that
  fails when the pinned generator's template differs from the one the override was copied from.
