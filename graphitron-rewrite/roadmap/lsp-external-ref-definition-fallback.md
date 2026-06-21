---
id: R349
title: "Decouple LSP source positions from the generator build; type the goto-definition outcome"
status: Backlog
bucket: bug
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# Decouple LSP source positions from the generator build; type the goto-definition outcome

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

## Root model: positions are parse-derived; they must not ride the build

Source positions come from parsing `.java` (`SourceWalker`, parse-only, no attribution,
no classpath resolution), which is independent of compilation; a class is locatable the
instant its source is on disk. goto-def is nonetheless gated on a build because the source
walk runs only inside `CatalogBuilder.build`, which the dev server invokes on the
generator / `.class` cadence (`buildOutputQuietly` at startup, `rebuildCatalog` /
`regenerate` on triggers). So positions refresh on the wrong cadence and a locationless
initial catalog stays locationless until something forces a rebuild. `SourceWalker`
already caches per-file by `.java` mtime; that source-cadence machinery exists and is
being suppressed by only ever invoking it inside `buildOutput`.

There is no "pillar" to invert. Bytecode and source are orthogonal axes joined on the
FQN: bytecode (`ClasspathScanner`) carries what source cannot, accurate erased
descriptors, overload arity, record components, and binary-only classes with no `.java`;
source carries what bytecode cannot, positions, Javadoc, and parameter names (always, no
`-parameters`). Making either the sole spine drops the other axis (a pure-source spine
would drop binary-only classes from completion). The fix is to stop forcing both axes onto
one record built on one cadence.

## Design

Two changes, both expressible at the pipeline tier:

1. **Type the resolution outcome (kills the silent no-op).**
   `CompletionData.SourceLocation.UNKNOWN` is a sentinel (`uri=""`, `line=0`) that
   conflates three distinct outcomes every consumer re-derives from `uri().isEmpty()`:
   source *genuinely absent* (binary-only, a correct no-op), source *present but not
   indexed yet* (the bug, recoverable), and *overload-ambiguous* (a deliberate no-op, per
   `CompletionData.Method`'s contract). Lift it to a sealed outcome, e.g.
   `sealed interface DefinitionTarget { record Located(...); record SourceAbsent(...);
   record Ambiguous(...); }`, decided once by the producer and switched on exhaustively by
   `Definitions` (and the hover path) instead of testing `uri().isEmpty()` at each site.
   The not-yet-indexed / `SourceAbsent` arm is what can drive a non-silent signal rather
   than a dead jump. This is the sealed-over-enum / "consumer switches on a typed outcome,
   not a sentinel" discipline applied to the LSP request path.

2. **LSP-owned source-position index on the source cadence (makes positions
   build-independent).** Stop baking positions into the generator's `CompletionData`. Keep
   `CompletionData` as the structure-of-references artifact the codegen consumer wants
   (bytecode-accurate signatures, overload arity, the FQN ref set, which the generator
   reads and which never needed positions). Have the LSP own a source-position index
   produced by `SourceWalker.walk(sourceRoots)` (`SourceWalker` and `Index` stay in
   `graphitron`, so the `graphitron`-must-not-depend-on-`graphitron-lsp` boundary holds,
   the LSP just drives the walk), keyed by the FQN / `MethodKey` / `FieldKey`
   `SourceWalker.Index` already uses, refreshed by a source-root watcher in the dev goal
   (sibling to the existing classpath watcher). `Definitions` joins "ref from catalog" with
   "position from the source index" at request time on the FQN both already carry, the
   same join `enrichExternalReferences` performs today, moved from build-time to
   request-time and from the generator to the LSP.

   `SourceWalker.CACHE` is `static` / process-wide; an LSP-driven index must either keep
   that contract or move the cache to an instance the LSP owns. Decide explicitly in the
   spec.

## What this is not

- **Not a table-style file-level fallback** (`0:0`) for external refs: without the walk
  there is no source file to point at, and with the decoupling the walk is always
  available when sources exist, so the genuine no-jump case is exactly `SourceAbsent`.
- **Not a change to the codegen consumer:** it keeps consuming the bytecode-derived
  structure and never reads positions.

## Scope split

- **R351 (stopgap, ship first):** dev-goal `compileSourceRoots` / `classpathRoots` parity
  so a scanned class is always a walked class. Unblocks the field case immediately without
  the decoupling.
- **This item (R349, the durable fix):** the typed outcome (1) plus the LSP-owned
  source-position index on the source cadence (2). With both, positions no longer ride the
  build and a source-absent ref is a handled arm rather than a silent dead jump, so the
  class of bug is structurally closed regardless of how roots are wired.

## Acceptance

- A sealed definition-outcome type with `Definitions` (and the hover path) switching
  exhaustively; pipeline-tier tests that the `SourceAbsent` and `Ambiguous` arms are
  reachable and produce the intended behaviours (no jump with a signal vs no jump silently
  by design).
- A source-position lookup the LSP refreshes on `.java` change independent of
  `buildOutput`; a test that a position is available after a source edit without a catalog
  rebuild.
- Out of scope for R347 (LSP-internal consolidation); this is the catalog feed / LSP
  request path.

