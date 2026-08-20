---
id: R748
title: "Decouple the roadmap tool from the generator reactor"
status: Spec
bucket: dx
priority: 5
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-20
---

# Decouple the roadmap tool from the generator reactor

> Remove the two dependency edges that tie `roadmap-tool` to the generator build:
> the `graphitron-model` dependency (which exists only because two schema-docs
> subcommands are homed in the tool) and the DuckDB JNI artifact (which exists
> only to parse two input file formats). Three moves, no new modules, no new
> mechanisms: the schema-drift gate becomes a meta-test in `graphitron-model`,
> the schema-reference renderer becomes a class in `graphitron-model` invoked
> from the existing docs execution slot, and the two coverage reports parse
> their inputs in Java. End state: `roadmap-tool`'s only main-scope dependency
> is snakeyaml, and a cold `mvn -pl roadmap-tool exec:java` compiles one small
> pure-Java module in seconds instead of requiring a reactor build.

## Motivation

The roadmap tool is the lowest-cadence module in the reactor and the one every
session touches first (item creation, status flips, README regeneration), yet
it is the most expensive module to use cold: its pom declares `graphitron-model`
at `${project.version}`, so `mvn -pl roadmap-tool exec:java` on a fresh checkout
first builds the fact-store module and, through it, the jOOQ codegen toolchain.
The tool also carries `duckdb_jdbc`, a JNI-bundled artifact whose bundled
binaries cover three platforms, as the query engine for two audit reports whose
data volumes are thousands of rows.

Neither dependency reflects a real need of the tool:

- The `graphitron-model` edge serves exactly two subcommands,
  `render-schema-reference` and `check-schema-identifiers`, which call
  `GraphitronModelStore.open()` and `StoreCatalog.read(...)`. No generated
  model classes, no capture logic; the census they read is derived by H2 from
  the fact schema DDL. Their change cadence tracks the DDL and the store, not
  the roadmap tool: they are `graphitron-model` tooling that happened to be
  homed here. Moving them to the module whose cadence they share is not a
  workaround; it is the correct homing, and it keeps the shared-reader
  invariant (every census consumer reads through `StoreCatalog`, never a
  second derivation) in one module instead of across a dependency edge.
- The DuckDB edge serves exactly one feature: `read_json_auto` over the
  per-module classifier-trace JSONL files and `read_csv_auto` over the JaCoCo
  CSVs. Every other table in those reports is already staged through plain
  JDBC inserts, and the aggregation SQL is dialect-neutral. An analytical
  engine ingesting kilobytes is machinery without a matching cost.

The payoff is proportionate on both sides of the cut. For the tool: cold use in
seconds, pure-Java everywhere a JVM runs, and a dependency list (snakeyaml)
that makes the module's actual shape visible. For the reactor: the docs
module's dependency on `graphitron-model` becomes a declared, true
relationship (it consumes the model's schema renderer) instead of an edge
reached implicitly through the tool. The docs module keeps a `provided`
dependency on `roadmap-tool` for `render-roadmap-adoc` and `check-adoc-xrefs`,
which is fine: that edge names code docs really runs. Nothing user-visible
changes: same CLI commands, same skills, same gate coverage at the same build
phases, same docs site.

## Design

### 1. `check-schema-identifiers` becomes a meta-test in `graphitron-model`

The gate is a pure guard: boot the fact store, read `StoreCatalog`, scan the
authored pages under `docs/architecture/` for backtick-quoted identifiers, fail
on one the schema no longer declares. The repo's idiom for exactly this shape
is the meta-test; `RoadmapReferenceGuardTest` is the precedent. Rewrite
`SchemaIdentifierDriftCheck` (and its test) as a `graphitron-model` test-tier
class. The store classes are already on that module's test classpath, so the
exec execution in `roadmap-tool/pom.xml`, the `Main` dispatch arm, and the
exit-code-versus-exception dance are deleted rather than moved.

Owned behavior change: the exec execution survives `-Pquick` and `-DskipTests`
(exec executions are untouched by `maven.test.skip`), so today this gate is
*stronger* than every sibling guard. The move demotes it to the common class.
We read that as removing an accidental inconsistency rather than losing a
decided strength: the gate was stronger by habitat, not by decision, and
nothing distinguishes schema-identifier drift from roadmap-citation drift,
which already tolerates `-Pquick`. CI's full build still gates trunk. The move
also buys two compensations worth having: feedback moves from near the end of
the reactor (`roadmap-tool` `verify`) to near its start (`graphitron-model`
tests), so a DDL rename fails minutes earlier, and the store boot rides the
module's own test harness instead of a cold open.

Carry-over requirements: the exec version's three vacuity floors (missing
authored tree, zero pages scanned, empty identifier universe) survive as
assertions, plus a scanned-count floor per the `RoadmapReferenceGuardTest`
pattern, since the test reaches `docs/architecture/` by walking to the repo
root. That walk mints a third copy of the repo-root locator (beside
`GuardScope.locateRepoRoot()` in `graphitron` and the one in `roadmap-tool`);
acknowledged, and small enough to live with. The failure message must keep
naming the offending pages and identifiers verbatim. The check now runs on
every model test run instead of once per reactor build; the cost is a docs-tree
walk plus a harness store boot, and is negligible.

### 2. `render-schema-reference` becomes a `graphitron-model` class

The renderer cannot be a test: it produces site pages into the Asciidoctor
staging tree and must run when tests are skipped. Move `SchemaReferencePages`
(and `SchemaReferencePagesTest`) into `graphitron-model`'s main source tree
with a small `main` entry point. The docs pom keeps the same execution slot,
phase (`process-resources`, base build), arguments, and staging output; the
diff is one `mainClass` line and adding a `provided` dependency on
`graphitron-model` (the `roadmap-tool` one stays for the roadmap staging
steps). The renderer's non-vacuity floors (empty catalog, relation on no page,
blank comment text) move unchanged and keep gating `-P!docs` builds.
`check-adoc-xrefs` continues to verify authored links into the generated pages
against the staged tree, unaffected.

Main scope is a deliberate charter call, not a default. The tension is real:
`modules.adoc` charters `graphitron-model` as the DDL, its generated compile
surface, and the H2 bootstrap, and a docs renderer is a view landing in the
model's jar. Three facts decide it anyway. The renderer's input contract
already lives in this module: `CommentRenderabilityGateTest` holds the accepted
comment subset the renderer interpolates verbatim, and the move co-locates
producer and contract instead of binding them through prose across a module
seam. `ModelCodegenDriver` is the standing precedent for a build tool in this
module's main tree; the module's pom defends against dependency pollution, not
class pollution, and the renderer adds classes only. And the alternative homes
fail concretely: a test-jar home is *not viable* because `-Pquick` sets
`maven.test.skip=true`, which skips test compilation, leaving the docs
`process-resources` execution with no artifact exactly in the build that must
still render; docs itself is pom-packaged with no compile surface.

Doc touchpoints the move implies: the `modules.adoc` rows for both
`graphitron-model` and `roadmap-tool`, and the generated page header, which
currently reads "Generated by `graphitron-roadmap-tool` render-schema-reference"
and would rot on day one.

### 3. The coverage reports drop DuckDB

`LeafCoverageReport` and `SourceCoverageReport` replace engine-side ingestion
with Java-side parsing: each JSONL trace line parses in Java, the JaCoCo CSV
parses with a header-plus-values reader (its values contain no quoted
separators), and the group-bys move to Java streams over records.
`TierVocabulary` already carries the tier ordering as a comparator; its
SQL-generating half retires, leaving the comparator as the ordering's sole
carrier. Rendered output must be byte-identical for identical inputs, which
the existing verify-mode drift check (`leaf-coverage --verify`) pins for the
committed report.

JSONL parser choice is the implementer's, with one enforcer either way: the
pinned `org.yaml:snakeyaml` is a YAML **1.1** processor (the 1.2
JSON-superset claim belongs to `snakeyaml-engine`, a different artifact), and
1.1 scalar resolution has traps for unquoted values that read as booleans or
numbers (`on`, `no`, `007`). Either use snakeyaml guarded by a test that
parses what the trace writer actually emits, adversarial scalars included, or
write the ~20-line JSON tokenizer and pin it with the same test. The reader
re-derives the trace format independently either way, exactly as
`read_json_auto` did, so no new writer/reader seam appears.

### Seam details

- `BuildFailure` (26 lines) stays in `roadmap-tool` for its many remaining
  consumers; the moved renderer throws `graphitron-model`'s own equivalent (or
  `IllegalStateException` with the same messages), and the meta-test asserts
  instead of throwing.
- `InertSpans` is the sharpest seam in the design, and "take a copy" is not
  the last word, because the class's own javadoc pins the invariant a copy
  breaks: the inert span forms and the recognizer that reads them back live in
  one place so emitters and the checks policing them cannot drift. The copy
  seam would land exactly across a live producer/consumer pair: the model-side
  renderer *emits* spans into staging that roadmap-tool's `check-adoc-xrefs`
  *masks* when checking that same tree. Resolution, in two parts. The
  model-side emitter is a floor, not a fork: everything `SchemaReferencePages`
  emits through `monospace()` is store-identifier-shaped except the check
  clauses, so the moved emitter carries only the plus form and fails the build
  on content `plusFormFits` rejects, dropping the pass-macro machinery
  entirely; drift-by-divergence collapses to drift-by-omission with an
  enforcer. The drift check's scanning needs (`maskInert`, `BlockContext`) do
  move as a copy, and that copy gets the repo's standard enforcer for a fact
  stated twice: a meta-test asserting the copied member bodies stay
  byte-identical to `roadmap-tool`'s originals. The full `InertSpans` stays in
  `roadmap-tool` for its other renderers and checks.
- `roadmap-tool/pom.xml` drops the `graphitron-model` and `duckdb_jdbc`
  declarations once their consumers are gone. No remaining source line in the
  module touches H2, jOOQ, or DuckDB (verified: zero such imports; DuckDB was
  reached only through JDBC driver loading in the two reports).

## Verification

- Full reactor build green, including the docs module: the generated schema
  reference in staging is identical before and after the move (diff the staged
  `schema/` directory across the two builds).
- The moved gate still bites: introduce a dead identifier into an authored
  architecture page locally and observe the `graphitron-model` test fail with
  the page and identifier named.
- The coverage reports still round-trip: regenerate
  `roadmap/inference-axis-coverage.adoc` and the migration fragment from an
  instrumented build and confirm the verify mode passes, then confirm the
  DuckDB-era committed output needed no content change (formatting-neutral
  rewrite).
- Cold-use check: from a clean clone with an empty local repository,
  `mvn -pl roadmap-tool exec:java -Dexec.args='next-id roadmap'` succeeds
  without building any other module.

## Rejected designs

- **Publish the tool at its own version and pin it** (this item's original
  framing): after the decoupling, pinning saves one small pure-Java compile
  per build but costs a release workflow, a pin property, rehosted gates, and
  two-step lockstep changes; machinery without a matching cost.
- **Pin or shade `graphitron-model` inside the tool**: the gate must check
  docs against the checkout's current DDL, and a second boot implementation
  invites H2-version and statement-splitter drift.
- **maven-site-plugin for the schema reference**: it renders, it does not
  generate; the generator survives unchanged and the site forks into a second
  Doxia pipeline outside the AsciiDoc xref graph.
- **AsciidoctorJ extensions for the schema reference**: extensions cannot add
  documents and the page set is data, so authored stubs would reappear;
  generation would also move behind the profile-gated HTML render, un-gating
  `-P!docs` builds.
- **Replace DuckDB with H2 in the coverage reports**: works, but keeps an SQL
  engine for group-bys over thousands of rows and adds H2 as a new direct
  dependency of the tool.
- **A small model-adjacent tooling module hosting renderer, drift check, and
  one shared `InertSpans`**: dissolves the charter tension and the copy seam
  in one move, but costs a new reactor module and its row in every module
  enumeration for two classes and a helper; the floor-plus-meta-test
  resolution above buys the same safety without the module.
