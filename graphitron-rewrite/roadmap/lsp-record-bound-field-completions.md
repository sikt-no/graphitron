---
id: R157
title: "LSP: autocomplete and diagnostics for @field on @record-bound types"
status: Spec
bucket: lsp
theme: lsp
depends-on: [lsp-schema-snapshot-side-channel, emit-input-records]
created: 2026-05-13
last-updated: 2026-05-13
---

# LSP: autocomplete and diagnostics for @field on @record-bound types

`@field(name: "X")` only completes and validates against a jOOQ table today;
when the enclosing GraphQL type's backing is a Java record (or POJO, or
non-table jOOQ record), `FieldCompletions.generate` and
`Diagnostics.validateCatalogColumn` silently return empty. The schema
author gets neither suggestions nor a "name does not match a component
on the backing class" error. The symptom surfaces on input types because
input-side recordy bindings are the common case the user hits, but the
gap is uniform across input and output and across every backing shape
the classifier produces.

## Design: read the lifted model, not the SDL directives

The signal lives in `GraphitronSchema` already: every SDL type is lifted
to a `GraphitronType` variant
(`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/GraphitronType.java`)
that carries its backing class and shape (`JavaRecordInputType`,
`PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`, and
the output-side mirror `JavaRecordType`, `PojoResultType.Backed`,
`JooqRecordType`, `JooqTableRecordType`, `TableType`). The classifier
decides what backs each type — `@record`, `@table`, service-return
introspection, R94's input-record emitter — and writes the answer into
the model. The LSP consults the model.

This decouples R157 from any specific SDL directive. R96 can churn the
producers (drop `@record` on `INPUT_OBJECT`, narrow it on `OBJECT`,
migrate categories to introspection signals); as long as the classifier
still produces `JavaRecordInputType`-or-equivalent for the same SDL types,
R157's LSP arms continue to fire correctly. No `TypeContext.tableNameOf`
companion that sniffs `@record` from the AST; no `@table`-versus-`@record`
fallback branch; no shadow-rule mirroring. One lookup keyed by SDL type
name.

This is also fine on freshness. The user has explicitly said R157
does not need to react instantly to in-editor edits — stale-but-correct
is fine. The dev-pipeline run rebuilds the snapshot on file save (the
same cadence as today's `LspSchemaSnapshot` updates from R139); between
runs the LSP sees the last good projection. No tree-sitter walk over
the open buffer.

## Implementation shape

### 1. Extend the snapshot side-channel with a per-type backing projection

R139 (`lsp-schema-snapshot-side-channel`, In Review) already ships an
`LspSchemaSnapshot` carrying `List<DirectiveShape>`. Add a sibling
projection: `Map<String, TypeBackingShape> typesByName`, where
`TypeBackingShape` is a sealed projection over the
record-component / accessor-method / column-set variants the LSP cares
about. Naming and depth match `DirectiveShape` / `InputValueShape`'s
existing pattern (sealed projection, no graphql-java leak, copy-of in
the canonical constructor).

```
sealed interface TypeBackingShape permits
    RecordBacking,     // JavaRecord{Input,}Type
    PojoBacking,       // PojoInputType, PojoResultType.Backed
    JooqRecordBacking, // JooqRecord{Input,}Type, JooqTableRecord{Input,}Type
    TableBacking,      // TableType + any GraphitronType carrying a jOOQ
                       // table binding (incl. table-backed interfaces)
    NoBacking          // sealed: Root | UnbackedResult | UnclassifiedInterface
```

`RecordBacking` and `PojoBacking` carry a `List<MemberSlot>` of
`(name, displayType)` pairs. `JooqRecordBacking` and `TableBacking`
carry the jOOQ table name so the existing column-set lookup against
`CompletionData.tables()` keeps working through the same data path.

`NoBacking` is itself sealed because the three sub-arms support
observably-different diagnostics later (a `@field` site under `Root` is
a category error; under `UnbackedResult` the right reviewer hint is
"add `@record` or `@table`"; `UnclassifiedInterface` is the genuine
"no answer yet" case). Interfaces *with* a table binding flow through
`TableBacking` — the existing `interfaceTypeWithTableDirectiveAlsoResolvesColumns`
test in `FieldCompletionsTest` pins that behaviour and must keep
passing.

### 2. Producer: extend `CatalogBuilder.buildSnapshot`

`CatalogBuilder.buildSnapshot` today only sees the `TypeDefinitionRegistry`.
Pass it the lifted `GraphitronSchema` too (the build pipeline already has
it; `GraphQLRewriteGenerator` calls `GraphitronSchemaBuilder.build` before
the snapshot step). Walk `schema.types()`; project each `GraphitronType`
variant to the matching `TypeBackingShape`.

Both projection rules — "read record components off the class-file
`Record` attribute" and "drop `get`/`is`, lowercase first letter, no-arg
public method" — are class-file-level questions, so both belong in
`ClasspathScanner`, the single place that owns class-file reading.
Concretely: `ExternalReference` grows from `(name, className, description,
methods)` to `(name, className, description, methods, recordComponents)`
where `recordComponents` is empty for non-records and populated from
`Attributes.record()` for records. `CatalogBuilder.buildSnapshot` then
becomes a pure projector: for `Java*Type` it reads `recordComponents`;
for `Pojo*` it filters `methods` to bean-accessor shape; for `JooqRecord*`
and `Table*` it forwards the jOOQ table name. The LSP is unaware of
either rule.

### 3. Consumer: snapshot lookup, not SDL parse

In `FieldCompletions.generate`, `Diagnostics.validateCatalogColumn`, and
`Hovers.columnHover`:

1. Resolve the enclosing type *name* via
   `TypeContext.enclosingTypeDefinition(...)` +
   `TypeContext.declaredNameOf(...)`. Keep these — they're cheap,
   buffer-local, and don't read directives.
2. Look up the name in the snapshot's `typesByName`.
3. Dispatch on the `TypeBackingShape` variant:
   - `RecordBacking` / `PojoBacking` → completions / validation / hover
     against the member-slot list.
   - `JooqRecordBacking` / `TableBacking` → existing
     `CompletionData.getTable(...)` path.
   - `NoBacking` or snapshot miss → empty (same observational shape as
     today's empty return).

The old `TypeContext.tableNameOf` path goes away from the three consumer
sites; the helper itself stays for the moment because other arms
(`@nodeId(typeName:)`'s metadata projection) still call it. A follow-up
can remove it once those arms also migrate to the snapshot.

### 4. Snapshot freshness

`LspSchemaSnapshot.Built.Current` vs. `Built.Previous` already encodes
"last good snapshot" vs. "stale because the latest parse failed".
R157's consumers read uniformly through `LspSchemaSnapshot.Built` (no
freshness branch) — same convention as
`DirectiveResolution.resolve(LspVocabulary, LspSchemaSnapshot, String)`.
`Unavailable` returns empty completions / no diagnostic / no hover,
matching today's "no info yet" semantics.

### 5. Tests

Primary tier — pipeline test exercising the full producer → snapshot →
consumer round trip:

- A new pipeline test (alongside the existing snapshot-pipeline tests
  introduced under R139) compiles a realistic `.graphqls` containing one
  SDL type per `TypeBackingShape` arm, with real backing classes
  (`record FilmCard(Integer filmId, String title)`, a POJO with bean
  accessors, a jOOQ-record-bound type, a jOOQ-table-bound type, a root,
  an interface with and without `@table`) on the test classpath. Runs
  `CatalogBuilder.buildSnapshot` for real, asserts the LSP completion /
  diagnostic / hover handlers return the right slots for each. This is
  the test most likely to catch silent classifier widening.

Supporting unit tests:

- `CatalogBuilderTest`: per-variant projection from a hand-built
  `GraphitronSchema` to `typesByName`.
- `ClasspathScannerTest`: `record FilmCard(...)` populates
  `recordComponents`; a plain class leaves it empty.
- `FieldCompletionsTest`: positive test per `TypeBackingShape` arm;
  negatives for snapshot miss and each `NoBacking` sub-arm.
- `DiagnosticsTest`: unknown component name on `RecordBacking` →
  "Unknown component" error; known name → no error.
- `HoversTest`: hover on `@field(name: "filmId")` renders the slot's
  `displayType`.

### 6. Out of scope

- `@enum(enum: {className:})` types. Enums don't carry `@field(name:)`;
  separate item if it ever matters.
- `@reference(key:)` (see `ReferenceCompletions`). FKs are intrinsically
  a jOOQ-table concept; record/POJO backings have no FKs to reference.
  The directive stays on the existing `TypeContext.tableNameOf` path; on
  non-table backings it correctly returns no suggestions.
- `union` types. A `@field` site on a union itself is meaningless; the
  union flows to `NoBacking.UnbackedResult` and produces no completions,
  while each member is handled on its own as its own SDL type.
- Migrating `@nodeId(typeName:)`'s metadata projection onto `typesByName`.
  That directive reads a *different* type's table (R152 is the active
  bug there), so bundling it into R157 conflates two scopings. Deferred.
- Per-component nullability / Jakarta-constraint surfacing on
  `MemberSlot`. R12-adjacent; this item only carries name + display
  type.

## Tests / load-bearing checks

The classifier-produced `GraphitronType` variant is the load-bearing
signal R157 depends on. If an arm in `GraphitronSchemaBuilder` or the
classifier silently widens (e.g. an input that should be
`JavaRecordInputType` falls through to `PojoInputType` because a record
component name diverges from the SDL field name), R157's completions
silently degrade to the wrong member list rather than fail loudly.

Mitigation: declare one `@LoadBearingClassifierCheck` key on the
producer (`javarecordinputtype-backs-record-class` and the output-side
mirror) at the projection site in `CatalogBuilder.buildSnapshot`, paired
with `@DependsOnClassifierCheck` on the consumer-side dispatch in
`FieldCompletions` / `Diagnostics` / `Hovers`. A future classifier
change that widens those variants then trips the key audit instead of
silently returning empty completions.

## Risk

- **Snapshot wire-up depth.** R139's snapshot side-channel ships
  directive shapes only; R157 broadens the contract to "directive shapes
  + per-type backing shapes." That's a real surface-area increase on the
  LSP/build boundary. Mitigation: lift the projection in
  `CatalogBuilder.buildSnapshot` only, leave the consumer-side dispatch
  in three named call-sites, and pin the round-trip with the pipeline
  test in §5.
- **R94 not yet Done.** `JavaRecordInputType` is produced by R94
  (`emit-input-records`, currently Spec). Until R94 lands, the
  `JavaRecordInputType` arm of the producer is dead code under the
  Sakila fixtures and the corresponding LSP fork is exercised only by
  the hand-built `CatalogBuilderTest` fixture. The front-matter
  `depends-on` captures the ordering; R157's pipeline test should land
  after R94 ships so it has a real producer to drive.
- **Backing-class catalog coverage.** `ClasspathScanner` walks the
  output classes directory; consumer-authored records on the classpath
  but outside the scan root would surface as snapshot misses, not
  errors. The same caveat already applies to `@service` method lookups;
  R157 inherits the existing scan-root contract without widening it.
