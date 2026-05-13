---
id: R157
title: "LSP: autocomplete and diagnostics for @field on @record-bound types"
status: Spec
bucket: lsp
theme: lsp
depends-on: [lsp-schema-snapshot-side-channel]
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

This decouples R156 from any specific SDL directive. R96 can churn the
producers (drop `@record` on `INPUT_OBJECT`, narrow it on `OBJECT`,
migrate categories to introspection signals); as long as the classifier
still produces `JavaRecordInputType`-or-equivalent for the same SDL types,
R156's LSP arms continue to fire correctly. No `TypeContext.tableNameOf`
companion that sniffs `@record` from the AST; no `@table`-versus-`@record`
fallback branch; no shadow-rule mirroring. One lookup keyed by SDL type
name.

This is also fine on freshness. The user has explicitly said R156
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
    RecordBacking,    // JavaRecord{Input,}Type
    PojoBacking,      // PojoInputType, PojoResultType.Backed
    JooqRecordBacking,// JooqRecord{Input,}Type, JooqTableRecord{Input,}Type
    TableBacking,     // TableType (jOOQ-table-bound)
    NoBacking { ... } // PojoResultType.NoBacking, RootType, interfaces
```

`RecordBacking` and `PojoBacking` carry a `List<MemberSlot>` of
`(name, displayType)` pairs. `JooqRecordBacking` and `TableBacking`
carry the jOOQ table name so the existing column-set lookup against
`CompletionData.tables()` keeps working through the same data path.
`NoBacking` is the explicit "no field-component completion makes sense
here" arm.

### 2. Producer: extend `CatalogBuilder.buildSnapshot`

`CatalogBuilder.buildSnapshot` today only sees the `TypeDefinitionRegistry`.
Pass it the lifted `GraphitronSchema` too (the build pipeline already has
it; `GraphQLRewriteGenerator` calls `GraphitronSchemaBuilder.build` before
the snapshot step). Walk `schema.types()`; project each `GraphitronType`
variant to the matching `TypeBackingShape`. For `Java*Type` variants the
member slots come from the backing-class catalog scan (extending
`ClasspathScanner` to also read the class-file `Record` attribute via
`Attributes.record()` and project record components onto the existing
`CompletionData.ExternalReference`); for `Pojo*` variants the member
slots come from the existing methods list filtered to JavaBean-style
accessors (drop `get`/`is` prefix, lowercase first letter, no-arg public
methods). The `Record` attribute read and the accessor filter are local
to `ClasspathScanner` and `CatalogBuilder.buildSnapshot` respectively;
neither touches the LSP.

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
R156's consumers read uniformly through `LspSchemaSnapshot.Built` (no
freshness branch) — same convention as
`DirectiveResolution.resolve(LspVocabulary, LspSchemaSnapshot, String)`.
`Unavailable` returns empty completions / no diagnostic / no hover,
matching today's "no info yet" semantics.

### 5. Tests

- `CatalogBuilderTest`: per-variant snapshot projection. Build a small
  `GraphitronSchema` with one each of `JavaRecordInputType`,
  `PojoInputType`, `JooqRecordInputType`, `JooqTableRecordInputType`,
  `JavaRecordType`, `PojoResultType.Backed`, `TableType`; assert the
  snapshot's `typesByName` carries the expected `TypeBackingShape`
  arm with the right slot list.
- `ClasspathScannerTest`: a `record FilmCard(Integer filmId, String title)`
  on the classpath produces an `ExternalReference` whose component list
  is `[filmId: Integer, title: String]`; a plain class produces an empty
  component list.
- `FieldCompletionsTest`: positive test for each `TypeBackingShape`
  variant. Both `type Foo @record(...)` and `input FooInput @record(...)`
  (with corresponding snapshot fixtures) produce the right slot set.
  Negative test: snapshot miss → empty completions; `NoBacking` → empty
  completions.
- `DiagnosticsTest`: `@field(name: "ghost")` on a `RecordBacking` type
  produces an "Unknown component" error keyed by member name;
  `@field(name: "filmId")` on the same produces no error.
- `HoversTest`: hover on `@field(name: "filmId")` renders the slot's
  `displayType`.

### 6. Out of scope

- `@enum(enum: {className:})` types. Enums don't carry `@field(name:)`;
  separate item if it ever matters.
- Migrating `@nodeId(typeName:)`'s metadata projection onto
  `typesByName`. Plausible follow-up after R156, not blocking.
- Per-component nullability / Jakarta-constraint surfacing on
  `MemberSlot`. R12-adjacent; this item only carries name + display
  type.

## Tests / load-bearing checks

The classifier-produced `GraphitronType` variant is the load-bearing
signal R156 depends on. If an arm in `GraphitronSchemaBuilder` or the
classifier silently widens (e.g. an input that should be
`JavaRecordInputType` falls through to `PojoInputType` because a record
component name diverges from the SDL field name), R156's completions
miss. The Tests section above pins the snapshot projection per variant;
no `@LoadBearingClassifierCheck` keys are introduced for this item
because the snapshot consumes the classifier's output rather than
re-deriving it.

## Risk

- **Snapshot wire-up depth.** R139's snapshot side-channel ships
  directive shapes only; R156 broadens the contract to "directive shapes
  + per-type backing shapes." That's a real surface-area increase on the
  LSP/build boundary. Mitigation: lift the projection in
  `CatalogBuilder.buildSnapshot` only, leave the consumer-side dispatch
  in three named call-sites, and pin the round-trip with the per-variant
  `CatalogBuilderTest`.
- **Backing-class catalog coverage.** `ClasspathScanner` walks the
  output classes directory; consumer-authored records on the classpath
  but outside the scan root would surface as snapshot misses, not
  errors. The same caveat already applies to `@service` method lookups;
  R156 inherits the existing scan-root contract without widening it.
