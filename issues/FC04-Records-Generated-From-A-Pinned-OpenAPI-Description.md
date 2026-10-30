# FC04 — Records Generated From A Pinned OpenAPI Description

**Status:** Idea.

**What must be true.** A client module gets one Java record per schema its operations use, generated at build time
from a forge's OpenAPI description that is pinned by its source commit and SHA-256 digest and filtered to the
operations the module lists, with nothing at build time but Java, Maven and Jackson.

## The shape

- **A module of its own, `codegen`**, never published, run in a client module's `generate-sources` by
  `exec-maven-plugin`: it reads OpenAPI 3.1 (`components/schemas`, `webhooks`) and Swagger 2.0 (`definitions`),
  keeps the listed operations and webhooks and the schemas they reach, and writes records with Jackson's
  `@JsonProperty` into `target/generated-sources`, in a `@NullMarked` package; a field is `@Nullable` exactly when the
  description lets it be absent or null (OpenAPI 3.1's `type: [..., "null"]` and Swagger's `x-nullable` included), so
  NullAway checks the code that reads them.
- **The extract is committed, the full description is not:** a client module keeps the filtered extract in
  `src/main/openapi/`, with the source's commit, digest and license notice beside it; regenerating the extract from
  the pinned source is one command that refuses a download whose digest differs. A description kept as a template
  (Forgejo's, whose only placeholders are two strings) is valid JSON as it is and is read so.
- **Strict in the tests, lenient at run time:** every reader of a client module - its calls, GraphQL answers and
  webhook deliveries alike - is built with a strictness, its caller's choice. By
  default a field it does not know is ignored, since a forge adds fields within one API version; the tests build it
  strict, refusing unknown fields. In both, a required field may not be missing, and a field the description does
  not let be null may not be null.
- **Why not openapi-generator:** its Java generator writes classes with getters and setters, not records, adds
  `jackson-databind-nullable` at run time unless switched off, and is a 34 MB tool to download and pin; records from
  it need templates of their own anyway.

## Acceptance

- In the strict reading, a recorded answer with a field the record does not know fails to read, naming the field;
  the default reading passes it. In both, an answer that leaves out a required field, or sets to null a field the
  description does not let be null, fails, naming it; a required field that may be null passes as null, an optional
  one that may not passes when absent.
- The generated code compiles with NullAway as an error; a field is `@Nullable` exactly when the description lets it
  be absent or null.
- A schema the listed operations do not reach is not generated; an operation listed but not in the description
  stops the build, naming it.
- A downloaded description whose digest differs from the pin is refused before anything is written.
