# OpenAPI records check

Generates records from a small fixture description, `src/main/openapi/records.json`, with the same configuration and
overridden templates as every client, and compiles them as main code under NullAway with only Jackson's annotations
and JSpecify on the class path. Its tests read JSON into them strictly and leniently. Built here, never published.
