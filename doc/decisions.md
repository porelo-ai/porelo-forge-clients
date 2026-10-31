# Decisions

What holds and why: one row per decision, and its full text below. How the records are made is in
[records.md](records.md).

| Covers | Decision |
|---|---|
| Records | [The generator is pinned to a snapshot until `useRecords` is released](#the-generator-is-pinned-to-a-snapshot-until-userecords-is-released) |
| Records | [What the generator gets wrong is fixed in its configuration and templates, never in its output](#what-the-generator-gets-wrong-is-fixed-in-its-configuration-and-templates-never-in-its-output) |
| Records | [The extract is committed, the full description is not](#the-extract-is-committed-the-full-description-is-not) |
| Records | [Strict in the tests, lenient at run time](#strict-in-the-tests-lenient-at-run-time) |

## The generator is pinned to a snapshot until `useRecords` is released

No release of openapi-generator has `useRecords` yet, so the plugin is pinned to a timestamped snapshot from
Sonatype's snapshot repository, which `settings.xml` names; the root's `pom.xml` holds the pin. The first release that
has `useRecords` replaces it, and no release of this repository is made before. A pinned snapshot that Sonatype
has removed fails the build, naming it, and is moved to a newer one.

## What the generator gets wrong is fixed in its configuration and templates, never in its output

Generated code is never edited or committed. A fix goes into the generator's configuration or into an overridden
template in `openapi-templates/`, a copy of the pinned generator's own with the change, so every client and every
regeneration gets it, and a test notices when the generator's own template moves.

## The extract is committed, the full description is not

A client keeps only the filtered extract of its forge's description, with the source's URL pinned by commit and its
digest. A full description is large (GitHub's is about 14 MB); the extract shows in review exactly which operations
and schemas a change adds, and the build checks it against the listed operations.

## Strict in the tests, lenient at run time

A forge adds fields within one API version, so at run time a field the record does not know is ignored, and a client
keeps working. The tests read strictly and refuse an unknown field, so a recorded answer that has drifted from the
extract is seen. In both readings a required field that is missing, or null where the description does not allow
it, is refused; forges send null for optional fields their descriptions do not mark, so an optional field may be
absent or null.
