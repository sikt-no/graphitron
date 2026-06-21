---
id: R349
title: "LSP goto-definition silently fails for @service/@condition class refs when the source walk yields no index"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# LSP goto-definition silently fails for @service/@condition class refs when the source walk yields no index

Goto-definition on a `@service` / `@condition` / `@externalField` class or method
reference returns no location in a live `graphitron:dev` session ("No definitions
found"), while jOOQ `@table` jumps and intra-schema type jumps still work. Confirmed
in an Emacs/eglot session: `eglot-xref-backend` is active and the
`textDocument/definition` round-trip succeeds, but the server returns `[]` for class
refs; class-name *completion* in the same position works, so the external references
themselves are built (the `ClasspathScanner` pass over the reactor's `target/classes`
is fine). The gap is that the reference's *source location* stays
`CompletionData.SourceLocation.UNKNOWN`: `CatalogBuilder.enrichExternalReferences`
sets a ref's location and Javadoc together from the source-walk `classDecl`, and when
`SourceWalker.walk(compileSourceRoots)` yields no index for that class the location is
left `UNKNOWN` and goto-def silently no-ops.

## Reproduction (mechanism confirmed correct; the trigger is source-root coverage)

Reproduced end-to-end on the real sakila service module by driving the full
`CatalogBuilder.build` (the two inputs the dev server feeds it) and then
`Definitions.compute`, both modes:

- **classes + sources on the build:** the `SampleQueryService` external ref gets a
  real location and goto-def returns `SampleQueryService.java:27`.
- **classes on the classpath but the source root NOT on `compileSourceRoots`** (the
  field-report shape): the ref is still present (so completion works) but its
  `definition()` is `UNKNOWN` (uri="", line=0) and goto-def returns `Optional.empty()`.

So the scan, walk, FQN-join, and request path are all correct; the symptom is purely a
function of whether the `@service` class's source directory is among
`compileSourceRoots`. The JRE theory is ruled out (the field report's
`getSystemJavaCompiler()` was non-null and the walk works). The failure occurs only when
the live dev session builds the catalog with `compileSourceRoots` that does **not** cover
the module holding the `@service`/`@condition` source: the compiled classes are on the
classpath (`ClasspathScanner` finds them, so completion works), but their sources are
never walked, so `enrichExternalReferences` leaves the location `UNKNOWN`. This is the
cross-module shape: services in a different module, or services consumed as a built
dependency, so the module's source root is absent from the session's `getAllProjects()`
source list even when its `target/classes` is present.

## Why it is invisible and total (not partial like tables)

Tables survive the same missing-walk condition because `CatalogBuilder.buildTable`
synthesizes a *file-level* fallback location (`0:0`) from the jOOQ output path on disk
whenever the generated `.java` exists; external references have **no equivalent
fallback**, so a missing walk degrades them from "jump to the class" all the way to
"do nothing". The degradation is also completely silent: `SourceWalker.parse` returns
an empty map when `ToolProvider.getSystemJavaCompiler()` is `null` (line ~209), with no
log line, so a consumer whose `graphitron:dev` JVM is a JRE (or whose `JAVA_HOME`
points at a JRE) loses all service-class navigation with no signal at all. This is the
trigger for the field report and explains the "never worked" framing.

## Confirmed cause and ruled-out triggers

- **Confirmed:** the service module's source root is not on the dev session's
  `compileSourceRoots`, so `SourceWalker` never indexes those classes and their
  locations stay `UNKNOWN`. Reproduced above. The mechanism (scan + walk + FQN-join +
  request path) is otherwise correct.
- **Ruled out:** no system Java compiler (the reporter's compiler was set and the walk
  works); FQN-join mismatch (sakila top-level service FQNs match exactly).

The asymmetry to investigate in the mojo: `resolveClasspathRoots` and
`resolveCompileSourceRoots` both iterate `session.getAllProjects()`, yet in the field
case the classpath covered the service module while the source roots did not. Determine
why (dev launched from a sub-module so `getAllProjects()` is narrowed; services consumed
as a binary dependency whose sources are not a reactor module; generated-source roots
not registered when dev runs outside the `generate-sources` phase) and bring the two
into parity, or detect and report the gap.

## Proposed fix

1. **Source-root / classpath-root parity** in the dev goal: any module whose compiled
   classes are scanned for `@service` candidates should have its source root walked, so
   a scannable class is always a locatable class.
2. **Make the gap non-silent**: when an external reference is scanned but no source is
   found for it (walk empty, or source root not covered), emit a one-time dev-goal
   warning naming the unlocatable class(es), so the degradation surfaces instead of a
   silent dead goto-def. (A file-level fallback like the table path is not available
   here: without the walk there is no source file to point at.)

Acceptance: a pipeline-tier fixture asserting that a `@service` class scanned from a
reactor module's `target/classes` resolves to a non-`UNKNOWN` location when that
module's source root is on the build, and a test that the scanned-but-unlocatable case
emits a signal rather than degrading silently. Out of scope for R347 (LSP-internal
consolidation); this is the catalog feed.

