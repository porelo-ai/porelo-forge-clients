# OpenAPI extract

This unpublished build tool fetches a SHA-256-pinned JSON OpenAPI 3.x or Swagger 2.0 description and keeps selected
operations, webhooks, and their transitive references. It uses Jackson and the JDK, knows no Porelo contracts, and
is installed locally for build use but never deployed or published.

Run `org.fuin.porelo.forge.openapi.OpenApiExtract` with the module and its dependencies on the class path:

```
fetch --source https://example.org/api.json --sha256 <64 hex digits> --out extract.json --operation "get /items"
check --extract extract.json --operation "get /items"
```

Repeat `--operation "<lowercase method> <path>"` and `--webhook <name>` for the exact selection; at least one
operation is required. Fetch also accepts repeated `--replace <from>=<to>` arguments, applied in order before
parsing but after digest verification. Webhooks use OpenAPI 3.1's `webhooks`; `x-webhooks` is discarded.
Path item references are expanded before selecting operations. Other references must be local JSON pointers into
the description's reusable components, definitions, parameters, or responses; external references fail. Literal
examples and default values are preserved as data. Fetch writes only after validation succeeds; check is read-only and exits
with status 1 and one line per problem if the committed selection or reference closure has drifted.
