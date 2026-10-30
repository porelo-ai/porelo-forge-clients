# FC04 — Records Generated From A Pinned OpenAPI Description

**Status:** Idea.

**What must be true.** A client module gets one Java record per schema its operations use, generated at build time
from a forge's OpenAPI description that is pinned by its source commit and SHA-256 digest and filtered to the
operations the module lists, with nothing at build time but Java, Maven and Jackson.

## The shape

- **A module of its own, `codegen`**, never published, run in a client module's `generate-sources` by
  `exec-maven-plugin`: it reads OpenAPI 3.1 (`components/schemas`, `webhooks`) and Swagger 2.0 (`definitions`),
  keeps the listed operations and webhooks and the schemas they reach, and writes records with Jackson's
  `@JsonProperty` into `target/generated-sources`, in a `@NullMarked` package, every field the description does not
  require or marks nullable `@Nullable`, so NullAway checks the code that reads them.
- **The extract is committed, the full description is not:** a client module keeps the filtered extract in
  `src/main/openapi/`, with the source's commit, digest and license notice beside it; regenerating the extract from
  the pinned source is one command that refuses a download whose digest differs. A description kept as a template
  (Forgejo's, whose only placeholders are two strings) is valid JSON as it is and is read so.
- **Strict in the tests, lenient at run time:** a record ignores a field it does not know when a client reads a
  live answer, since a forge adds fields within one API version; the tests read every recorded answer with unknown
  fields refused.
- **Why not openapi-generator:** its Java generator writes classes with getters and setters, not records, adds
  `jackson-databind-nullable` at run time unless switched off, and is a 34 MB tool to download and pin; records from
  it need templates of their own anyway.

## Acceptance

- In the tests' strict reading, a recorded answer with a field the record does not know, or without a field the
  description requires, fails to read, naming the field; the same answer read at run time passes.
- The generated code compiles with NullAway as an error, with no field nullable that the description requires.
- A schema the listed operations do not reach is not generated; an operation listed but not in the description
  stops the build, naming it.
- A downloaded description whose digest differs from the pin is refused before anything is written.
