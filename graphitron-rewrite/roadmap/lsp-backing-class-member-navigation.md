---
id: R353
title: "LSP goto-definition from an SDL type/field name to its backing Java class and member"
status: Ready
bucket: feature
priority: 5
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-24
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
list; and every Java position the dispatch needs (jOOQ table class, column, consumer class, POJO
accessor) now resolves through the LSP-owned `SourceWalker.Index` via the sealed `DefinitionTarget`
and the join helpers in `Definitions` (`classTarget` / `methodTarget` are public; the column/FK
join `fieldTarget` is currently private). The catalog (`CompletionData`) supplies the structural
join keys (table names, column names, class FQNs); the index supplies the positions. R349 (commit
`1db8756`) moved the consumer-class / method positions onto the index, and the jOOQ table/column
positions have since followed: `CompletionData.SourceLocation` now survives only on `TypeData`
(scalar types), with no `Table.definition()` / `Column.definition()` accessor remaining.

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
     then `Definitions.classTarget(table.classFqn(), sourceIndex)` -> `DefinitionTarget`. This is the
     same source-index join `Definitions.tableDefinition` already uses for the `@table(name:)`
     directive arm; the generated table class is a class in the index, not a catalog-borne
     `SourceLocation`.
   - *Type name* on `RecordBacking` / `PojoBacking` / `JooqRecordBacking.Standalone` -> the public
     `Definitions.classTarget(fqClassName, sourceIndex)` join helper R349 added, yielding a
     `DefinitionTarget` (`Located` -> jump, `SourceAbsent` -> empty). The consumer-class position
     no longer lives on `CompletionData`; it is resolved against the LSP-owned `SourceWalker.Index`.
   - *Field name* on `TableBacking` / `JooqRecordBacking.WithTable` -> resolve the enclosing type's
     table, find the named column, and join `(table.classFqn(), columnName)` against the source index
     -> `DefinitionTarget`. This is the join `Definitions.fieldDefinition` already performs internally
     via the private `fieldTarget`. Because `fieldDefinition` is directive-parameterized (it reads the
     table from the `@field` directive's enclosing type via `DeclarationKind.enclosing(directive.outer())`)
     and `fieldTarget` is private, this arm needs the column-by-name -> `DefinitionTarget` join exposed:
     a small refactor factoring the table+column lookup out of `fieldDefinition`, or widening
     `fieldTarget` to package visibility, so a field-name trigger with no directive cursor can call it.
     There is no `Column.definition()` accessor.
   - *Field name* on `PojoBacking` -> the public `Definitions.methodTarget(fqClassName, memberName,
     catalog, sourceIndex)` join helper, yielding a `DefinitionTarget` (the accessor's
     `Located` position, or `SourceAbsent` / `Ambiguous`).
   - *Field name* on `RecordBacking` -> per decision D1.
   - *Field name* on `JooqRecordBacking.Standalone` -> the public `Definitions.classTarget(fqClassName,
     sourceIndex)` join helper (the same target as the type-name arm on this shape). A `Standalone`
     jOOQ record carries no table (so no column join) and no member-key projection (the completion /
     hover arms decline it: `FieldCompletions` returns an empty list, `Hovers` returns empty), so by
     the same data-availability reasoning as D1 the field-name cursor degrades to the backing class
     rather than no-jumping. Pinned by test (D3).
   - *Field name* / *type name* on `NoBacking.Root` / `NoBacking.UnbackedResult` /
     `NoBacking.UnclassifiedInterface` -> `Optional.empty()` (no Java target).

3. **Chain** `DeclarationDefinitions.compute(...)` into `GraphitronTextDocumentService.definition`
   via another `.or()` after `IntraSchemaDefinitions`, threading `workspace.sourceIndex()` into the
   call the same way the `Definitions.compute` arm does post-R349 (the service-half join helpers
   read positions from that index, not from the catalog). The three providers key on disjoint or
   classifier-separated triggers; the `.or()` ordering is acceptable so long as the shared trigger
   classification (point 1) keeps ownership of a NAME node explicit rather than fall-through.

4. **Honour the single empty-resolution contract already in place.** Every arm resolves through the
   sealed `DefinitionTarget`: `classTarget` / the column join / `methodTarget` each return `Located`
   (jump), `SourceAbsent`, or `Ambiguous`, and `Definitions.resolve` maps that to `Optional<Location>`
   (`Located` -> `asLocation`, which itself yields `Optional.empty()` on an empty-URI
   `SourceLocation`; `SourceAbsent` / `Ambiguous` -> empty so the editor stays put). This is the
   contract R349 and the table/column position migration settled on; `DeclarationDefinitions` reuses
   it unchanged rather than reintroducing a separate catalog-borne `asLocation` path. (A `catalog.getTable`
   / column miss, i.e. a name the catalog does not know, is still a plain `Optional.empty()` before
   the join is even attempted, distinct from a known target whose source is `SourceAbsent`.)

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

**D2 - builds on R349 (and the table/column position migration that followed it).** R349 (commit
`1db8756`, Done) decoupled the consumer-class / method positions onto the LSP-owned
`SourceWalker.Index` and replaced the `UNKNOWN` sentinel with the sealed `DefinitionTarget`. Since
then the jOOQ table and column positions have likewise moved onto the index: `Definitions.tableDefinition`
resolves the `@table` jump through `classTarget(table.classFqn(), sourceIndex)` and `fieldDefinition`
resolves the `@field` jump through the `(classFqn, columnName)` `fieldTarget` join, both yielding a
`DefinitionTarget`. No catalog-borne `Table.definition()` / `Column.definition()` /
`ExternalReference.definition()` / `Method.definition()` accessor survives; `CompletionData.SourceLocation`
remains only on `TypeData` (scalar types). Every R353 arm therefore routes through a source-index
`DefinitionTarget` join. R353 is **correct regardless of source coverage**: when a backing
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
field name on a record component (-> backing class, per D1), field name on a `Standalone` jOOQ
record (-> backing class, the no-table/no-member degrade in design point 2), and a `NoBacking`
declaration name (-> empty).

## Out of scope

- Member-precise source locations for Java record components (follow-up Backlog item, see D1).
- Any change to the directive-argument goto-def in `Definitions` or the SDL-target resolution in
  `IntraSchemaDefinitions`; this item only adds the declaration-name -> Java-target path.
- Hover / completion / inlay behaviour on declaration names (already covered by `DeclarationHovers`
  et al.).

## Dependencies

- **R347** (`lsp-structural-consolidation`, In Progress): coordination, **not** a `depends-on`. If
  R347 lands a shared declaration-name trigger classifier, R353 consumes it (design point 1);
  otherwise R353 factors the classifier out of `DeclarationHovers` itself. Either path is
  self-contained, so R353 is not blocked on R347 landing; the `depends-on` was dropped from the
  front-matter so the README stops rendering R353 as "blocked by" an in-progress item. Coordinate to
  avoid a third copy of the leaf-walk.

## Builds on

- **R349** (Done, commit `1db8756`): supplies the public `Definitions.classTarget` /
  `methodTarget` join helpers, the sealed `DefinitionTarget`, and the LSP-owned
  `SourceWalker.Index` (`Workspace.sourceIndex()`) that every R353 arm resolves through (the
  table-class and column arms via `tableDefinition` / `fieldDefinition`'s source-index joins, the
  reflection-bound class arms via `classTarget`, the POJO accessor arm via `methodTarget`; see D2).
  Not a `depends-on`: it has already landed.

