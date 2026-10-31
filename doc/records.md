# How a client's records are made

Every client module gets one Java record per schema its operations use, generated at build time from its forge's
OpenAPI description. The calls are written by hand; only the records are generated. The decisions behind this page
are in [decisions.md](decisions.md).

## The extract

A client keeps a filtered extract of its forge's description in `src/main/openapi/`, made by
[openapi-extract](../openapi-extract/README.md), a Java command that the client's `pom.xml` runs through
`exec-maven-plugin`. The extract's `info` names the source's URL, pinned by commit, and its SHA-256 digest; the
module's `README.md` carries the description's license notice.

- `fetch` downloads the pinned source. It follows redirects but never from HTTPS to HTTP, sends no credential, and
  refuses an answer other than 200 and a body over 64 MB, naming the source. It refuses the body, before anything is
  written, when its digest differs.
- `fetch` then replaces a template's placeholders (`--replace`), keeps the listed operations and webhooks and the
  components they reach, and nothing else. A property a forge sends but its description lacks is added by name
  (`--add-property`), refused when the description already has it, and recorded in the extract's
  `info.x-extracted-from.added`.
- `check` runs in every build, in the client's `validate` phase, and fails naming a listed operation missing from the
  extract, an operation in it that is not listed, and a component nothing listed reaches.

## The generator

openapi-generator's `spring` generator, models only, is configured once in the root's `pluginManagement` (execution
`records`) and run in a client module's `generate-sources`, which names its extract and package. It reads OpenAPI
3.1 with its `webhooks`, and Swagger 2.0, as they are. Among its options are `useRecords`, `useJspecify`,
`openApiNullable=false`, `useBeanValidation=false`, `annotationLibrary=none`, `documentationProvider=none` and
`generateApis=false`; the root's `pom.xml` holds them all. Of the supporting files it writes only
`package-info.java`, marked `@NullMarked`, so NullAway checks the code that reads the records.

The generated code compiles with only `jackson-annotations` and `jspecify` on the class path, nothing of Spring or
Jakarta; [openapi-records](../openapi-records/README.md) holds that with a small fixture description.

## The overridden templates

`openapi-templates/` is the generator's `templateDirectory`. Each file in it is a copy of the pinned generator's own
template with the changes listed here. A test fails, naming the template, when the pinned generator's template differs
from the one an override was copied from, so moving the pin re-copies each.

- `model.mustache`, `record.mustache`, `pojo.mustache` and an empty `generatedAnnotation.mustache` leave out
  Spring's `@DateTimeFormat` and `jakarta.annotation.Generated`. The import of `DateTimeFormat` the generator still
  adds for every date is mapped to `java.time.OffsetDateTime`, a harmless duplicate.
- `jackson_annotations.mustache` marks a required field `@JsonProperty(required = true)`, which Jackson enforces on a
  record's creator. `record.mustache` adds a compact constructor that refuses null in a required field the
  description does not let be null, naming the JSON field; a required field gets no default.
- `enumClass.mustache`, `enumOuterClass.mustache`, `additionalProperties.mustache`, `oneof_interface.mustache` and the
  record's discriminator accessor say `@Nullable` where the generator's code returns null.
- The normalizer `REMOVE_ANYOF_ONEOF_AND_KEEP_PROPERTIES_ONLY` drops a `oneOf` or `anyOf` that only narrows its
  schema's own properties, and `fetch` drops that schema's `discriminator`, since without its `oneOf` the generator
  would make it a class. A schema that keeps `additionalProperties` stays a class.

The overrides cover the shapes of the extracts kept here. A shape they do not - a class with a required field or a
parent, a `byte[]` or qualified type behind an added `@Nullable`, a `oneOf` branch without its discriminator - fails
its client's compile under NullAway, naming the file.

## Reading an answer

Every reader of a client - its calls and webhook deliveries - is built with a strictness, the caller's choice. By
default it is lenient; the tests build it strict.

| In the answer | Lenient | Strict |
|---|---|---|
| A field the record does not know | ignored | refused, naming it |
| A required field missing, or null where the description does not allow null | refused | refused |
| A required field that may be null, set to null | read as null | read as null |
| An optional field absent or null | read as null | read as null |
| An enum value the record does not know, the enum not nullable | refused | refused |
| An enum value the record does not know, the enum nullable | read as null | read as null |

The record itself names the field or value it refuses. A client's exception names only an unknown field, and says of
any other refusal that the JSON is invalid or a required field is missing, because Jackson's messages can carry the
answer, credentials included.

A field is `@Nullable` exactly when the description lets it be absent or null. Swagger 2.0 cannot say null, so there
every field that is not required is `@Nullable`. A JSON name that is not a Java name, such as `+1` and `-1`, gets one
that compiles and reads it back.
