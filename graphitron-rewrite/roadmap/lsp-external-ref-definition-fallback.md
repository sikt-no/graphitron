---
id: R349
title: "Decouple LSP source positions from the generator build; type the goto-definition outcome"
status: In Review
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

Two changes (target tiers named under Acceptance):

1. **Type the resolution outcome (kills the silent no-op).**
   `CompletionData.SourceLocation.UNKNOWN` is a sentinel (`uri=""`, `line=0`) that erases
   three distinct outcomes behind one value: source *genuinely absent* (binary-only, a
   correct no-op), source *present but not indexed yet* (the bug, recoverable), and
   *overload-ambiguous* (a deliberate no-op, per `CompletionData.Method`'s contract). The
   sole consumer, `Definitions`, collapses all three to "no jump" through one
   `uri().isEmpty()` test (localized in `Definitions.asLocation`, `Definitions.java:196`,
   with the overload-skip filter at `Definitions.java:143`), so the recoverable case is
   indistinguishable from the two correct no-ops, which is why the not-yet-indexed failure
   is silent. Lift it to a sealed outcome, e.g. `sealed interface DefinitionTarget { record
   Located(...); record SourceAbsent(...); record Ambiguous(...); }`, decided once by the
   producer and switched on exhaustively by `Definitions` (its sole consumer) in place of
   the `uri().isEmpty()` sentinel test. The `SourceAbsent` arm is what drives a non-silent
   response rather than a dead jump. This is the sealed-over-enum / "consumer switches on a
   typed outcome, not a sentinel" discipline applied to the LSP request path. (Hover does
   not read `SourceLocation`: `Hovers` / `DeclarationHovers` consume the Javadoc
   `description`, not the position, so the hover path is untouched by this change.)

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
   that contract or move the cache to an instance the LSP owns. **Decision: keep it
   static.** The cache is content-addressed by path + mtime, so it is correct regardless of
   how many drivers walk through it, and after this change two drivers do: the build-cadence
   `CatalogBuilder.build` (which still walks for jOOQ positions and for the Javadoc it lifts
   into `description`) and the new source-cadence LSP watcher. Both walk overlapping file
   sets, so the shared cache makes the second walk a near-free map merge rather than a
   re-parse; that is a performance feature, not a coupling hazard, while the walk runs in two
   places. The forcing function to move it to an instance is the build-cadence walk going
   away entirely, deferred to R352 when the jOOQ half and hover also move onto the source
   index; at that point the LSP is the sole walker and the cache moves with it.

## Implementation scope (bounded to the service half; full decoupling is R352)

The durable fix this item ships is scoped to the **service half** (the reported bug):
`CompletionData.ExternalReference` / `Method` drop their `definition` field, and
`Definitions` resolves class / method positions from the LSP-owned source index through the
sealed `DefinitionTarget`. Three boundaries are drawn as deliberate transitional states,
each filed as **R352**:

- the **jOOQ half** (`Table` / `Column` / `Reference`) keeps its build-cadence
  `SourceLocation` in `CompletionData`, since its positions come from walking generated
  build artifacts that change only on a build;
- **hover stays untouched**: `description` keeps being lifted from Javadoc on the build
  cadence in `CatalogBuilder`, so a `SourceWalker.Decl`'s `javadoc` and `location` ride
  different cadences (accepted transient hover/goto-def skew during a live edit);
- the **static cache** stays shared (above).

R352 retires `CompletionData.SourceLocation` entirely. Keeping R349 to the service half
keeps the bug fix proportionate and leaves the jOOQ goto-def path, which works today,
unperturbed.

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

These changes live in the LSP request path, the catalog/walker, and the dev goal, not in
the SDL → classified-model → generated-TypeSpec pipeline, so the coverage is LSP-tier,
catalog/walker-tier, and dev-goal integration, not `@PipelineTier`:

- **Typed outcome (change 1), LSP-tier in `DefinitionsTest`:** the sealed
  definition-outcome with `Definitions` switching exhaustively; tests that the
  `SourceAbsent` and `Ambiguous` arms are reachable and produce the intended behaviours
  (no jump with a signal vs no jump silently by design). The `Ambiguous` arm extends the
  existing overload-ambiguity coverage there (`methodWithUnknownLocationReturnsEmpty`).
- **LSP-owned source index (change 2), catalog/walker-tier:** `SourceWalkerTest` for the
  index keyed by FQN / `MethodKey` / `FieldKey`; `CatalogBuilderSourceTest` for the
  request-time join over real walked sources (the harness that already drives
  `SourceWalker` against a compiled fixture). `CatalogBuilderSnapshotTest` if the snapshot
  surface changes.
- **Source cadence (change 2), dev-goal integration in `CatalogRefreshTest` /
  `DevServerTest`:** a position is available after a `.java` edit without a `buildOutput`
  catalog rebuild (the behaviour the source-root watcher provides).
- Out of scope for R347 (LSP-internal consolidation); this is the catalog feed / LSP
  request path.

