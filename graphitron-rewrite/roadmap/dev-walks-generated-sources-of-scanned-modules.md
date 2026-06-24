---
id: R369
title: "graphitron:dev walks generated-sources of scanned reactor modules so goto-definition reaches jOOQ tables in sibling modules"
status: Backlog
bucket: bug
priority: 4
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# graphitron:dev walks generated-sources of scanned reactor modules so goto-definition reaches jOOQ tables in sibling modules

Goto-definition (and Javadoc hover) on a `@table` type name or `@table(name:)`
directive value returns nothing for the common multi-module layout where jOOQ
codegen lives in a *separate* module from the schema module that runs
`graphitron:dev`. The table class is known to the catalog (its module's
`target/classes` is on the scanned classpath, so the FQN and `classFqn` resolve),
but its source position is absent: the LSP-owned `SourceWalker.Index` only walks
roots returned by `MavenProject.getCompileSourceRoots()`, and the jOOQ sibling's
generated-sources directory is registered there only when that module's
`generate-sources` phase runs *in the same Maven session*. A single-module
`mvn -pl <schema-module> graphitron:dev` invocation does not run the sibling's
lifecycle, so the directory exists on disk (from a prior build) but is never
walked. `Definitions.tableDefinition` / `DeclarationDefinitions` then land on
`DefinitionTarget.SourceAbsent` -> `Optional.empty()` (a silent no-jump). The
field-level (`@field(name:)` -> column) jump fails the same way for the same
reason.

There is no working command that fixes this from the user side: dropping `-am`
omits the sibling's codegen, while adding `-am` pulls the aggregator parent into
the reactor and `graphitron:dev` (a direct, long-running goal Maven invokes once
per reactor project) then fails on the parent with `<outputPackage> is required
for this goal`. Reported in the field; confirmed by the `graphitron:dev` startup
diagnostic reading "scanning 4 reactor classpath root(s), 3 source root(s)" (the
scanned-but-unwalked module is the jOOQ sibling). The `DevMojo` comment at the
seed site already anticipates this exact "classes scanned but source root not
walked" mismatch (R351) but only as prose; nothing surfaces or repairs it.

Candidate directions (decide at Spec):

- **Auto-include generated-sources on disk.** For every reactor module whose
  classes `AbstractRewriteMojo` already scans (`session.getAllProjects()`), also
  walk its existing `${build.directory}/generated-sources/*` subdirectories,
  independent of whether that module's lifecycle ran in-session. Layout-agnostic
  (covers jOOQ, graphitron, any generator), and the parse-only walk tolerates
  over-inclusion cheaply. `AbstractRewriteMojo` already knows the
  `generated-sources/graphitron` convention (the `outputDirectory` default param),
  so the pattern is established.
- **Startup warning** naming any module with a scanned classpath root but no
  corresponding walked source root, turning the silent `4 vs 3` into an actionable
  line. Complements rather than replaces the auto-include.

Out of scope / to settle at Spec: table classes that arrive only as a published
dependency JAR (no reactor source at all) cannot be walked by source-root
inclusion; that is a separate, harder problem (decompiled / sources-jar
navigation) and should not block the reactor-source case. Test tier likely
unit-tier over hand-built `MavenProject` roots (the `collectExistingDirs`
parity test is the precedent), since standing up a live `graphitron:dev`
session against a multi-module reactor is not a pipeline-tier fixture shape.
