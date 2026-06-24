---
id: R353
title: "LSP goto-definition from an SDL type/field name to its backing Java class and member"
status: In Review
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
list (R353 widens the slot with the member's arity-0 `accessorMethodName`, see D1); and every Java
position the dispatch needs (jOOQ table class, column, consumer class, record component, POJO
accessor) resolves through the LSP-owned `SourceWalker.Index` via the sealed `DefinitionTarget`
and the join helpers in `Definitions` (`classTarget` / `methodTarget` are public; the column /
record-component / FK join `fieldTarget` and the `resolve` mapper are widened to package-private
for the new provider). The catalog (`CompletionData`) supplies the structural
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
- **Field name** -> the backing member: a jOOQ column, a Java record component, or a POJO accessor
  (all member-precise, see decision D1). A field on a standalone jOOQ record, which carries no table
  and no member key, degrades to the backing class.

## Design

1. **New provider `DeclarationDefinitions`** (sibling to `IntraSchemaDefinitions`, in
   `graphitron-lsp/.../definition/`). It keys on the cursor sitting on a declaration-name token,
   the same trigger `DeclarationHovers` already uses. The leaf-walk-and-classify primitive (NAME
   node -> parent kind -> type-declaration vs. field/input-value declaration) is factored out of
   `DeclarationHovers` into a shared home, `parsing/SdlDeclaration` (sealed over `TypeName` /
   `FieldName`, with the `findContaining` walk). Both the hover trigger and the goto-def trigger
   call it, so they cannot drift; `DeclarationHovers.findContaining` becomes a thin adapter mapping
   `SdlDeclaration` to its hover-content family. (R347, LSP structural consolidation, has not landed
   a shared classifier; `SdlDeclaration` is the home it would consolidate toward, so coordinate to
   avoid a third copy of the walk.) The Java-target resolution body stays separate from
   `IntraSchemaDefinitions`' SDL-target body; only the trigger classification is shared.

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

   For every *field name* arm, the bound column / member is named by the field's `@field(name:)`
   override when it carries one, else by the SDL field name itself; `DeclarationDefinitions` reads the
   override off the field-definition node (`Directives.findAll` + `TypeContext.stringArg`), falling
   back to the field name. This is the per-declaration analogue of the directive value
   `Definitions.fieldDefinition` reads when the cursor is on the `@field(name:)` string.

   - *Field name* on `TableBacking` / `JooqRecordBacking.WithTable` -> resolve the enclosing type's
     table, find the named column (case-insensitive), and join `(table.classFqn(), columnName)` against
     the source index via `Definitions.fieldTarget`, the same join `Definitions.fieldDefinition`
     performs for the `@field` directive arm. `fieldTarget` and `resolve` are widened to
     package-private so the sibling provider reuses them; there is no `Column.definition()` accessor.
   - *Field name* on `PojoBacking` -> find the `MemberSlot` whose `name()` matches the bound member,
     then `Definitions.methodTarget(fqClassName, slot.accessorMethodName(), catalog, sourceIndex)`
     (the accessor's `Located` position, or `SourceAbsent` / `Ambiguous`). The bean rule maps a
     `getFirstName` method to the slot `name = "firstName"` the author writes; the source index keys
     methods by the real method name, so the slot carries `accessorMethodName = "getFirstName"` for
     the join. See D1.
   - *Field name* on `RecordBacking` -> find the `MemberSlot` whose `name()` matches the bound member,
     then `Definitions.fieldTarget(fqClassName, slot.name(), sourceIndex)`. A record component is its
     own arity-0 accessor and the parse-only `SourceWalker` indexes it as a *field* (the implicit
     accessor is synthesised later, so it is absent from the parse tree), so the component name is its
     own field key. Member-precise; see D1.
   - *Field name* on `JooqRecordBacking.Standalone` -> the public `Definitions.classTarget(fqClassName,
     sourceIndex)` join helper (the same target as the type-name arm on this shape). A `Standalone`
     jOOQ record carries no table (so no column join) and no member-key projection (the completion /
     hover arms decline it: `FieldCompletions` returns an empty list, `Hovers` returns empty), so the
     field-name cursor degrades to the backing class rather than no-jumping. Pinned by test (D3).
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

**D1 - member-precise resolution for both records and POJOs, via one widened slot key.**
Resolution is by source-index *key*, not by a carried `SourceLocation` (the R349 contract), so the
relevant question for member precision is whether the slot hands the consumer the key the index is
keyed by. It does not, as projected: `MemberSlot(name, displayType)` carries the name the SDL author
writes, which for a POJO bean accessor is the property name (`getFirstName` projects to `firstName`)
while the source index keys methods by the real method name (`getFirstName`). Passing the slot name
to `methodTarget` therefore never matches, and the arm silently no-jumps. (The original draft of
this decision had the record/POJO data-availability difference inverted: it degraded records, which
in fact resolve cleanly, and left POJOs member-precise, which in fact fail.)

R353 *decides*: widen the slot to `MemberSlot(name, displayType, accessorMethodName)`, populated at
the one projection site that already holds both strings (`CatalogBuilder.projectPojo` /
`projectRecord`), so the bean rule keeps its single home and the LSP never re-derives it. Both axes
are then member-precise:

- **POJO** -> `Definitions.methodTarget(fqClassName, slot.accessorMethodName(), catalog, sourceIndex)`;
  the index keys the accessor by its real method name and arity 0.
- **Record** -> `Definitions.fieldTarget(fqClassName, slot.name(), sourceIndex)`; a component is its
  own arity-0 accessor that the parse-only `SourceWalker` indexes as a *field* (the implicit accessor
  is synthesised after parsing, so `visitMethod` never sees it; `visitVariable` records the component
  as a field). For records `name == accessorMethodName`, so the existing field key suffices.

Goto-def is the only consumer of `accessorMethodName`; the completion / hover / diagnostic arms read
only `name` / `displayType`, so widening the record is backward-compatible for them. This retires the
follow-up "member-precise record components" Backlog item the prior draft deferred: there is nothing
left to upgrade. Both arms are pinned by test (D3).

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
`DeclarationDefinitions` internals. Cases pinned in `DeclarationDefinitionsTest`: type name on a
`@table` type (-> table class), type name on a reflection-bound record / POJO / standalone-jOOQ type
(-> class, plus the `SourceAbsent` -> empty degrade when the source root is not walked), field name
on a `@table` column (-> column), field name with a `@field(name:)` override (-> the overridden
column), field name on a POJO accessor (-> accessor method), field name on a record component
(-> component, member-precise per D1), field name on a `Standalone` jOOQ record (-> backing class,
the no-table/no-member degrade in design point 2), unknown member (-> empty), a `NoBacking`
declaration name (-> empty), a cursor on a directive argument (-> not a trigger, empty), and an
unavailable snapshot (-> empty).

## Implementation (landed)

- `graphitron`: `TypeBackingShape.MemberSlot` gains `accessorMethodName`; `CatalogBuilder.projectPojo`
  passes `method.name()`, `projectRecord` passes `rc.name()`.
- `graphitron-lsp`: new `parsing/SdlDeclaration` (shared trigger classifier); `DeclarationHovers`
  reduced to an adapter over it; new `definition/DeclarationDefinitions` (the provider);
  `Definitions.fieldTarget` / `resolve` widened to package-private; the provider chained into
  `GraphitronTextDocumentService.definition` via a third `.or()`.
- Tests: `DeclarationDefinitionsTest` (pipeline, one case per backing shape per axis); `MemberSlot`
  constructor call sites updated across `FieldCompletionsTest` / `HoversTest` / `DiagnosticsTest`.

## Out of scope

- Any change to the directive-argument goto-def in `Definitions` or the SDL-target resolution in
  `IntraSchemaDefinitions`; this item only adds the declaration-name -> Java-target path.
- Hover / completion / inlay behaviour on declaration names (already covered by `DeclarationHovers`
  et al.).

## Dependencies

- **R347** (`lsp-structural-consolidation`, In Progress): coordination, **not** a `depends-on`. R347
  had not landed a shared declaration-name trigger classifier when R353 shipped, so R353 factored the
  leaf-walk out of `DeclarationHovers` into `parsing/SdlDeclaration` (design point 1), the home R347's
  consolidation would otherwise build. The work was self-contained, so R353 was not blocked on R347;
  the `depends-on` was dropped from the front-matter so the README stops rendering R353 as "blocked
  by" an in-progress item. R347 should consume `SdlDeclaration` rather than add a third copy of the
  walk.

## Builds on

- **R349** (Done, commit `1db8756`): supplies the public `Definitions.classTarget` /
  `methodTarget` join helpers, the sealed `DefinitionTarget`, and the LSP-owned
  `SourceWalker.Index` (`Workspace.sourceIndex()`) that every R353 arm resolves through (the
  table-class and column arms via `tableDefinition` / `fieldDefinition`'s source-index joins, the
  reflection-bound class arms via `classTarget`, the POJO accessor arm via `methodTarget`; see D2).
  Not a `depends-on`: it has already landed.

