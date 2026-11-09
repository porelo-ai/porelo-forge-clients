# FC17 — Mutation Testing With PIT

**Status:** Analyzed.
**Size:** S - one profile in the root `pom.xml`, a paragraph in the `README.md` and one run read; no code, no contract.

**What must be true.** Whoever works on the clients can see which faults in their code no test notices: a `pit` profile,
run by hand, not in CI and with no threshold, with PIT and its JUnit 5 plugin pinned, and a report that sorts the
survivors into real gaps and harmless ones.

## Plan

- **The profile.** A `pit` profile in the root `pom.xml`, modeled on the one in `porelo-message-matrix`:
  `pitest-maven` with `pitest-junit5-plugin` as its dependency, both pinned to the newest release when it is built
  (1.30.2 and 1.2.3 today). `targetClasses` is `org.fuin.porelo.forge.*`, overridable by `-Dpit.classes` for a
  change's own classes; `targetTests` is `org.fuin.porelo.forge.*Test`, so `ForgejoLiveIT` and the hand-run GitHub
  recorder and live check never run under it. `excludedClasses` takes the generated records
  (`org.fuin.porelo.forge.*.model.*`, `org.fuin.porelo.forge.openapi.records.*`) and every `package-info`;
  `outputFormats` HTML and XML, `timestampedReports` false, four threads. It is declared in no default build and in
  no workflow.
- **What it mutates, read in the code.** The hand-written main classes: `openapi-extract` (8 classes, `ExtractService`,
  `HttpsSource`, `OpenApiDocument`, `OpenApiExtract`, `Problems`, `ReferenceWalker`, `SchemaCorrection`, `Selection`),
  `clients/forgejo` (11) and `clients/github` (13). `openapi-records` has no main code of its own, only generated
  records, and is skipped.
- **No VM needed.** No unit test starts a process or reaches the network: the only `ProcessBuilder` is the Forgejo
  fixture's `Podman`, reached only from `ForgejoLiveIT` and the recorder, both outside `*Test`; every client test
  replays recorded answers over a fake `HttpClient`, and no test starts a child JVM. So it runs on the host, unlike the
  matrix's, whose tests call `podman` and `systemctl`.
- **What the tests need under PIT.** `openapi-extract`'s `TemplateOverrideTest` reads the system property
  `openapi.templates`, which Surefire sets; the profile passes it to PIT's minions through `jvmArgs`.
- **The command** goes into the root `README.md`, with the note that what only `ForgejoLiveIT` reaches counts as not
  covered:

      ./mvnw -s settings.xml -Ppit test-compile org.pitest:pitest-maven:mutationCoverage

- **The report.** One run on the build's commit, its numbers per module (mutants, killed, survived, not covered) and
  each survivor sorted as a real gap - a fault that can do harm, by `AGENTS.md`'s list - or harmless, with one line
  why, written into this issue's *Acceptance* when done.
- **No changelog entry**: the profile changes nothing that ships or that the default build produces.

## Acceptance

- `./mvnw -s settings.xml -Ppit test-compile org.pitest:pitest-maven:mutationCoverage` runs on the host from a clean
  checkout and writes a report per module under `target/pit-reports/`; the default `verify` and the workflow never run
  PIT, checked by a search of `.github/` and a default build's log.
- The report above, with every survivor sorted.
- Seen to work: a test of a mutated class deleted on purpose leaves a mutant survived that was killed before.

## Open questions

- Are the real gaps the run finds fixed within this issue, each with a test seen red first, or filed as issues of
  their own? Fixing them here can grow the size past S by an amount only the run shows.

## To be checked

- How long one run takes on the host, measured, before the run is announced.
