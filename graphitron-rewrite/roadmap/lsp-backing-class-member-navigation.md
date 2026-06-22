---
id: R353
title: "LSP goto-definition from an SDL type/field name to its backing Java class and member"
status: Spec
bucket: feature
priority: 5
theme: lsp
depends-on: [lsp-structural-consolidation]
created: 2026-06-21
last-updated: 2026-06-22
---

# LSP goto-definition from an SDL type/field name to its backing Java class and member

Goto-definition in the schema LSP today only fires when the cursor sits on a
*directive argument value* (`Definitions.compute` keys on `Directives.findContaining`:
`@table(name:)`/`@reference(path:)` jump to the jOOQ table class, `@field(name:)` to
the column, `@service(service:{className:})` to the Java class/method) or on an
*intra-schema type reference* (`IntraSchemaDefinitions` jumps `Film` in `films: [Film!]!`
to its `type Film` declaration). There is no jump from the **declaration name itself** to
the Java code the type is bound to: putting the cursor on `Customer` in
`type Customer @table(name:"customer")`, or on a type bound by reflection
(`JavaRecordType`/`JooqRecordType`/`PojoResultType`, which carry no class-naming directive
at all, so the type name is the *only* navigation handle), returns "No definitions found".
Likewise there is no jump from a field name (`firstName: String! @field(name:"FIRST_NAME")`)
to the backing column / record component / accessor; the author has to land precisely inside
the directive string, and reflection-bound members offer no handle whatsoever.

This matters because the schema is the consumer's primary surface and the type/field name is
where the cursor naturally rests; the directive argument is an implementation detail an author
should not have to target. Most of the data already exists: `LspSchemaSnapshot.Built.typesByName()`
projects a `TypeBackingShape` per SDL type carrying the backing class FQN
(`RecordBacking`/`PojoBacking`/`JooqRecordBacking.fqClassName`) or the jOOQ table name
(`TableBacking`/`JooqRecordBacking.WithTable.tableName`) plus a `MemberSlot(name, displayType)`
list; `CompletionData` holds source locations for jOOQ tables and columns, and (since R349, commit
`1db8756`) the consumer-class / method positions live in the LSP-owned `SourceWalker.Index`,
resolved through the public `Definitions.classTarget` / `methodTarget` join helpers and the sealed
`DefinitionTarget`.

## Goal

When the cursor sits on an SDL **declaration name** (a type name or a field/input-value name,
not a directive argument), goto-definition jumps to the Java the model bound that declaration to:

- **Type name** -> the backing class. For `@table`/jOOQ-record types, the jOOQ-generated table
  class; for reflection-bound types (`RecordBacking`/`PojoBacking`/`JooqRecordBacking.Standalone`),
  the consumer's Java class. This is the *only* navigation handle for reflection-bound types,
  which carry no class-naming directive.
- **Field name** -> the backing member: a jOOQ column, a POJO accessor, or (degraded, see decision
  D1) the backing class for a Java record component.

## Design

1. **New provider `DeclarationDefinitions`** (sibling to `IntraSchemaDefinitions`, in
   `graphitron-lsp/.../definition/`). It keys on the cursor sitting on a declaration-name token,
   the same trigger `DeclarationHovers` already uses. It must **not** re-derive "is this leaf a
   declaration name?" with its own guards: reuse the leaf-walk-and-classify primitive that
   `DeclarationHovers.findContaining` performs (NAME node -> parent kind -> type-declaration vs.
   field/input-value declaration). If R347 (LSP structural consolidation, In Progress) lands a
   shared classifier for this, consume it; otherwise factor the classification out of
   `DeclarationHovers` into a shared home so the hover trigger and the goto-def trigger cannot
   drift. The Java-target resolution body stays separate from `IntraSchemaDefinitions`'
   SDL-target body; only the trigger classification is shared.

2. **Dispatch on the resolved `TypeBackingShape`** from `snapshot.typeBacking(typeName)` via an
   **exhaustive switch with no `default`** (mirroring `Definitions.compute` over `Behavior`), so a
   future `TypeBackingShape` permit forces a goto-def decision rather than silently resolving to
   empty. All arms, including the three `NoBacking` arms, are named:
   - *Type name* on `TableBacking` / `JooqRecordBacking.WithTable` -> `catalog.getTable(tableName)`,
     then `Table.definition()` (jOOQ half, still a catalog-borne `SourceLocation`).
   - *Type name* on `RecordBacking` / `PojoBacking` / `JooqRecordBacking.Standalone` -> the public
     `Definitions.classTarget(fqClassName, sourceIndex)` join helper R349 added, yielding a
     `DefinitionTarget` (`Located` -> jump, `SourceAbsent` -> empty). The consumer-class position
     no longer lives on `CompletionData`; it is resolved against the LSP-owned `SourceWalker.Index`.
   - *Field name* on `TableBacking` / `JooqRecordBacking.WithTable` -> reuse the existing
     `Definitions.fieldDefinition` column path (`getTable(...).columns()` -> `Column.definition()`).
   - *Field name* on `PojoBacking` -> the public `Definitions.methodTarget(fqClassName, memberName,
     catalog, sourceIndex)` join helper, yielding a `DefinitionTarget` (the accessor's
     `Located` position, or `SourceAbsent` / `Ambiguous`).
   - *Field name* on `RecordBacking` -> per decision D1.
   - *Field name* / *type name* on `NoBacking.Root` / `NoBacking.UnbackedResult` /
     `NoBacking.UnclassifiedInterface` -> `Optional.empty()` (no Java target).

3. **Chain** `DeclarationDefinitions.compute(...)` into `GraphitronTextDocumentService.definition`
   via another `.or()` after `IntraSchemaDefinitions`, threading `workspace.sourceIndex()` into the
   call the same way the `Definitions.compute` arm does post-R349 (the service-half join helpers
   read positions from that index, not from the catalog). The three providers key on disjoint or
   classifier-separated triggers; the `.or()` ordering is acceptable so long as the shared trigger
   classification (point 1) keeps ownership of a NAME node explicit rather than fall-through.

4. **Honour the two empty-resolution contracts already in place.** The jOOQ-half arms
   (`Table.definition()` / `Column.definition()`) resolve through `Definitions.asLocation`, where a
   `SourceLocation.UNKNOWN` (empty URI) yields `Optional.empty()` so the editor stays put. The
   service-half arms resolve through the sealed `DefinitionTarget` R349 introduced: `Located` jumps,
   `SourceAbsent` / `Ambiguous` yield empty. Both are existing contracts this item reuses, not new
   ones; `DeclarationDefinitions` maps each arm's outcome to `Optional<Location>` exactly as
   `Definitions.resolve` already does.

## Decisions

**D1 - member fidelity for Java records (scope-limiting, decided, not a runtime fork).**
`CompletionData.RecordComponent` and the projection's `MemberSlot` carry no `SourceLocation`.
Rather than leave an "add a location or degrade" fork at the dispatch site, R353 *decides*:
field-name on a `RecordBacking` resolves to the **backing class declaration** (the same
`Definitions.classTarget(fqClassName, sourceIndex)` target as the type name on that type). POJO
accessors keep their member-precise position through `Definitions.methodTarget(...)` because the
source index already keys methods by `(fqn, name, arity)`; the record/POJO divergence reflects a
real data-availability difference (the `MemberSlot` projection carries no member key for record
components), not an unmade decision, and is pinned by test (D3). Threading a member-precise
location through `RecordComponent` (and onto `MemberSlot`) from the source walk is **split into a
follow-up Backlog item**, which would upgrade the record arm from class-precise to
component-precise without changing the dispatch
shape.

**D2 - builds on R349, which has landed.** R349 (commit `1db8756`, Done) decoupled the
service-half positions onto the LSP-owned `SourceWalker.Index` and replaced the `UNKNOWN` sentinel
with the sealed `DefinitionTarget`. The `RecordBacking`/`PojoBacking`/`Standalone` *type-name* arms
and the `PojoBacking` *field-name* arm therefore route through R349's public `classTarget` /
`methodTarget` join helpers rather than the removed `ExternalReference.definition()` /
`Method.definition()` accessors. R353 is **correct regardless of source coverage**: when a backing
class's source root is not walked the join returns `DefinitionTarget.SourceAbsent` -> empty (design
point 4), and when it is indexed the same arm jumps. R349 is no longer a `depends-on` (it is Done);
the relationship is "builds on", pinned by R353's own test (D3).

**D3 - test tier: pipeline.** Goto-def-per-backing-shape is behaviour (SDL declaration name +
catalog/snapshot -> resolved `Location`), so the primary signal lives at the pipeline tier
alongside `IntraSchemaDefinitionTest` / `DefinitionsTest`, asserting the resolved `Location`
end-to-end against a realistic schema + catalog snapshot. No per-arm unit tests on
`DeclarationDefinitions` internals. Cases to pin: type name on a `@table` type (-> table class),
type name on a reflection-bound record/POJO type (-> class, and the `SourceAbsent` -> empty degrade
when the source root is not walked), field name on a `@table` column (-> column), field name on a
POJO accessor (-> accessor method),
field name on a record component (-> backing class, per D1), and a `NoBacking` declaration name
(-> empty).

## Out of scope

- Member-precise source locations for Java record components (follow-up Backlog item, see D1).
- Any change to the directive-argument goto-def in `Definitions` or the SDL-target resolution in
  `IntraSchemaDefinitions`; this item only adds the declaration-name -> Java-target path.
- Hover / completion / inlay behaviour on declaration names (already covered by `DeclarationHovers`
  et al.).

## Dependencies

- **R347** (`lsp-structural-consolidation`, In Progress): if it lands a shared declaration-name
  trigger classifier, R353 consumes it (design point 1); otherwise R353 factors the classifier out
  of `DeclarationHovers`. Coordinate to avoid a third copy of the leaf-walk.

## Builds on

- **R349** (Done, commit `1db8756`): supplies the public `Definitions.classTarget` /
  `methodTarget` join helpers, the sealed `DefinitionTarget`, and the LSP-owned
  `SourceWalker.Index` (`Workspace.sourceIndex()`) that the reflection-bound class arms and the
  POJO accessor arm resolve through (see D2). Not a `depends-on`: it has already landed.

