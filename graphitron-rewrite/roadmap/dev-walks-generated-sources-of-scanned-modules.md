---
id: R369
title: "graphitron:dev walks generated-sources of scanned reactor modules so goto-definition reaches jOOQ tables in sibling modules"
status: Spec
bucket: bug
priority: 4
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# graphitron:dev walks generated-sources of scanned reactor modules so goto-definition reaches jOOQ tables in sibling modules

Goto-definition (and Javadoc hover) on a `@table` type name, a `@table(name:)`
directive value, or a `@field(name:)` column returns nothing in the common
multi-module layout where jOOQ codegen lives in a *separate* module from the
schema module that runs `graphitron:dev`. The table class is known to the catalog
(its module's `target/classes` is on the scanned classpath, so the FQN and
`classFqn` resolve), but its source position is absent, so every arm lands on
`DefinitionTarget.SourceAbsent` -> `Optional.empty()` (a silent no-jump).

## Root cause

The two root sets `AbstractRewriteMojo` feeds the LSP have *different
lifecycle-sensitivity*, and that is the whole bug:

- `resolveClasspathRoots()` reads `project.getBuild().getOutputDirectory()`
  (`target/classes`). That path exists on disk from any prior build, independent
  of whether the module's lifecycle ran in *this* session. So a single-module
  `mvn -pl <schema> graphitron:dev` still scans every reactor sibling's classes
  (`session.getAllProjects()` returns all modules).
- `resolveCompileSourceRoots()` reads `project.getCompileSourceRoots()`. That
  carries the static `src/main/java` for every module, but a *generated*-sources
  root (jOOQ's `target/generated-sources/jooq`) is appended only when the jOOQ
  codegen plugin executes, i.e. during that module's `generate-sources` phase. A
  single-module dev session never runs the sibling's lifecycle, so the directory
  is on disk but absent from `getCompileSourceRoots()` and never walked.

The `resolveCompileSourceRoots()` javadoc and the `DevMojo` seed-site comment both
assert the R351 invariant "a class scanned for completion is a class whose source
root is walked for goto-definition", but the asymmetry above breaks it precisely
for generated sources. Confirmed in the field: the `graphitron:dev` startup
diagnostic reads "scanning 4 reactor classpath root(s), 3 source root(s)"; the
scanned-but-unwalked module is the jOOQ sibling. No user-side command fixes it:
dropping `-am` omits the sibling's codegen; adding `-am` pulls the aggregator
parent into the reactor, and `graphitron:dev` (a direct, long-running goal Maven
invokes once per reactor project) fails on the parent with `<outputPackage> is
required for this goal`.

## Design

1. **Restore parity by making the source side disk-based for generated sources.**
   New package-private `static List<String> generatedSourceRoots(MavenProject)`:
   the immediate existing subdirectories of `${project.build.directory}/generated-sources/`
   (`target/generated-sources/jooq`, `.../graphitron`, `.../annotations`, ...).
   `resolveCompileSourceRoots()` widens its per-project extractor from
   `p.getCompileSourceRoots()` to `getCompileSourceRoots() ∪ generatedSourceRoots(p)`.
   `collectExistingDirs` already de-duplicates by normalised absolute path, so a
   subdir that the plugin *did* register (full-lifecycle goals like `generate`)
   collapses with the discovered one: no double-walk, idempotent. This is layout-
   agnostic (any generator under the conventional dir) and lifecycle-independent,
   the same property the classpath side already has; it lifts the field count from
   3 to 4 for the reported case. The now-stale `resolveCompileSourceRoots()`
   javadoc (`AbstractRewriteMojo.java:238-240`, "the pre-`mvn compile` state where
   generated sources are absent contributes nothing") is rewritten in the same
   commit to describe the disk-convention coverage, so the live documentation does
   not assert a behaviour the resolver no longer has.

2. **Startup warning for any residual scanned-but-unwalked module.** The
   scanned-but-unwalked determination is a *per-module set difference* (which
   `MavenProject` has a scanned `target/classes` but contributes zero walked
   source root), not the count comparison the current
   `DevMojo.java:152-155` diagnostic does, and `collectExistingDirs` deliberately
   discards the owning-project provenance that difference needs. So it is lifted
   into a pure package-private function beside `collectExistingDirs` (e.g.
   `static List<String> unwalkedScannedModules(Iterable<MavenProject>)` returning
   module identifiers), unit-pinned directly the way `collectExistingDirs` is;
   `DevMojo` only renders its output as a `WARN`. This keeps the warning from being
   an inline invariant claim that no test pins, and gives it the project provenance
   the two flattened `List<Path>` resolvers throw away. It complements, rather than
   replaces, the auto-include: the auto-include fixes the reactor-source case
   outright; the warning names the residue (e.g. table classes that arrive only as
   a dependency JAR, see out-of-scope).

## Decisions

**D1 - disk-convention scan, not plugin-config parsing.** Discover generated roots
by walking `target/generated-sources/*` on disk, not by reading the jOOQ (or any)
plugin's `<directory>` out of the module POM. POM-config parsing is plugin-specific
and fragile (the directory is configurable, the plugin coordinate varies); the disk
convention is what every code generator already follows. The parse-only
`SourceWalker` tolerates over-inclusion cheaply (annotation-processor output,
graphitron's own emitted resolvers): the per-file mtime cache amortises the
one-time parse across refreshes. The over-inclusion is harmless for the class /
field maps (`SourceWalker.merge` is first-wins `putIfAbsent`), but *not*
unconditionally for the method map: a cross-file `(FQN, methodName, arity)`
collision is dropped to `ambiguousMethods` and routes goto-def to
`DefinitionTarget.Ambiguous` (a silent no-jump). Now that graphitron's own output
root (`target/generated-sources/graphitron`, the `outputDirectory` default) is
walked, a method-key collision between an emitted resolver and a catalog-pointed
developer / jOOQ declaration would convert a `Located` jump into `Ambiguous`. This
cannot arise in practice: graphitron emits into its consumer-chosen
`outputPackage`, disjoint by construction from the jOOQ table package and developer
service packages the catalog joins against, so no shared FQN exists to collide on.
A test case pins it (a graphitron output root alongside a jOOQ root leaves the
table-class jump `Located`, not `Ambiguous`) rather than leaving the disjointness
as an unpinned prose claim.

**D2 - fix in the shared resolver, not a dev-only branch.** Both the catalog walk
and the dev source-watcher read `ctx.compileSourceRoots()` from the one
`resolveCompileSourceRoots()`; widening it there keeps the single resolution path
and preserves the R351 "scan path and walk path cannot drift" guarantee (it
actively *restores* it). Under a full-lifecycle goal where the plugin already
registered the root, the dedup makes the change a no-op.

**D3 - test tier: unit.** Both pieces are pure `MavenProject`/path resolution with
no schema or generator involvement, so they test at the unit tier over hand-built
`MavenProject` objects and `@TempDir`, extending the existing parity family
(`AbstractRewriteMojoTest.collectExistingDirs_classpathAndSourceRootsCoverSameReactorProjects`,
`..._skipsMissingAndNullDirectories`). Standing up a live `graphitron:dev` session
against a multi-module reactor is not a unit-tier fixture shape; the resolution
logic is the falsifiable unit.

## Test plan

- `generatedSourceRoots` / `resolveCompileSourceRoots`: a module whose
  `getCompileSourceRoots()` carries only `src/main/java` but whose
  `target/generated-sources/jooq` exists on disk -> the resolved source roots
  include the jooq dir (the core regression; would be empty pre-fix).
- Multiple generator subdirs (`jooq` + `graphitron` + `annotations`) all included;
  an absent `generated-sources` dir contributes nothing; a subdir the plugin
  already registered appears exactly once (dedup, no double-walk).
- Parity restored: for a reactor where one sibling has generated sources on disk
  but unbuilt this session, source-root count tracks classpath-root count.
- Method-key collision guard: a graphitron output root walked alongside a jOOQ
  root leaves a table-class jump `Located`, not `Ambiguous` (pins the D1
  output-package-disjointness argument).
- `unwalkedScannedModules`: a module with `target/classes` but no source root at
  all -> returned (and rendered as a `WARN` naming it); the parity case -> not
  returned (no warning).
- The end-to-end "table-class source position resolves once its root is walked"
  claim already rests on existing `SourceWalkerTest` coverage of jOOQ-table
  position recovery (R90 / R352); R369 only changes *which roots reach the walk*,
  so it does not re-pin the walk itself.

## Out of scope

- Table classes that arrive only as a *published dependency JAR* with no reactor
  source: source-root inclusion cannot help (there is no `.java` to walk). That is
  a separate, harder problem (sources-jar / decompiled navigation) and must not
  block the reactor-source case this item fixes.
- Any change to the resolution arms in `Definitions` / `DeclarationDefinitions` or
  to `SourceWalker` itself; the `SourceAbsent` -> empty contract is correct, the
  defect is upstream in which roots reach the walk.

## Builds on

- **R351** (Done): the classpath/source-root parity invariant and the shared
  `collectExistingDirs` traversal this item widens; the existing parity test is
  the precedent for the new cases.
- **R352 / R90** (Done): the LSP-owned `SourceWalker.Index` and the
  `DefinitionTarget` empty-resolution contract every jump arm routes through.
