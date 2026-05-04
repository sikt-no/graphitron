---
id: R78
title: "Typed jOOQ class references for multi-schema correctness (TableRef, KeyRef)"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
---

# Typed jOOQ class references for multi-schema correctness (TableRef, KeyRef)

## Bug surfaced

Generated code does not compile against multi-schema jOOQ codegen. Imports are emitted as `<jooqPackage>.tables.X` and `<jooqPackage>.tables.records.XRecord`, with `Tables.X` and `Keys.<FK>` referenced from `<jooqPackage>.Tables` / `<jooqPackage>.Keys`. In a multi-schema layout jOOQ generates one package per schema, so the correct fully-qualified names carry a schema segment (e.g. `<jooqPackage>.<schemaName>.tables.X`). Single-schema setups using the PostgreSQL `public` default, including all in-tree fixtures, happen to flatten and mask the bug; consumer projects with multiple schemas cannot compile the generated output.

## Root cause

The rewrite threads a single `String jooqPackage` through every emitter and concatenates `".tables"`, `".tables.records"`, `"Tables"`, `"Keys"` literals against it at every reference site. The schema-per-table fact known to the catalog is dropped on the floor; emitters reconstruct a guessed FQN that only matches single-schema layouts.

## Proposed direction

Replace string concatenation against `jooqPackage` with javapoet `ClassName` values populated once at parse time from `Table<?>` reflection. Each catalog reflection result already knows its own schema; the model captures it once at parse time, emitters consume the typed value with no derivation.

- `TableRef` carries — alongside the existing `tableName: String` (SQL identity, kept as the catalog lookup key) and `primaryKeyColumns` — three javapoet `ClassName` fields populated by reflection: `tableClass` (the `<schemaPackage>.tables.<X>` class, e.g. `Film`), `recordClass` (`<schemaPackage>.tables.records.<X>Record`), and `tablesClass` (the schema's `Tables` constants class). Plus the `Tables`-side field name as `String` (e.g. `"FILM"`). The simple-class `String javaClassName` field goes away — it was only ever used to rebuild a `ClassName` at every read site.
- New `KeyRef` for FK references: the `Keys` class as `ClassName` plus the FK constant name as `String`. Replaces the bare `String fkJavaConstant` on `JoinStep.FkJoin` and the `keysClass` lookup at every emit site. The `Keys` class belongs to the FK origin table's schema, so each `KeyRef` carries its own host class and cross-schema FKs join correctly without any per-emitter schema arithmetic.
- `JooqCatalog.TableEntry` becomes the single typed source. Its API: `tableClass(): ClassName`, `recordClass(): ClassName` (from `Table.getRecordType()`), `tablesClass(): ClassName`, `pkColumnRefs(): List<ColumnRef>`, and `toTableRef(sqlName): TableRef` as the one factory. Both `BuildContext.resolveTable` and `ServiceCatalog.buildTableRef` collapse to `tableEntry.toTableRef(sqlName)` — the duplicated PK-column materialisation in those two methods is consolidated as a side-effect of this change.
- `ClassName` directly on the model records, not behind a domain wrapper. Follows the precedent of `BatchKey`, `MethodRef`, `HelperRef`, `LifterRef`, `AccessorRef`, `ResultAssembly`, `PayloadAssembly`, `RowsMethodShape`, `ErrorChannel`, `DefaultedSlot`, `ChildField`. The `Tables` / `Keys` simple-name literals are jOOQ codegen conventions kept inside `JooqCatalog`, not stored on every ref.
- No separate `TableRecordRef`. The record class is the same table viewed from the projection axis; `Table.getRecordType()` produces it from the same `Table<?>` the `tableClass` came from. Per the design-principles rule "each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries that a sibling cannot — otherwise it's probably a field on an existing record," `recordClass: ClassName` is a field on `TableRef`. Every consumer that needs it already has the `TableRef` in hand.

## Catalog-miss handling

Today `BuildContext.resolveTable` returns `new TableRef(sqlName, "", "", List.of())` and `synthesizeFkJoin` returns `fkJavaConstant = ""` when the catalog has no entry. Empty-string sentinels survive `String javaClassName` but `ClassName` has no good empty value. The fix is structural, not a new sentinel: catalog-miss classifies the containing type as `UnclassifiedType` (and the field as `UnclassifiedField`) before any `TableRef` / `KeyRef` is constructed. Emit-site code never sees a partially-resolved ref.

Concretely, `BuildContext.resolveTable` becomes `Optional<TableRef>` (or returns through the existing `Resolved.{Ok | Rejected}` shape used elsewhere in the builder), and the call sites that today consume the empty-shape fallback are reachable only on the resolved arm. Same shape for `synthesizeFkJoin`'s `fkJavaConstant` fallback. This expands R78's surface area but is the only design that survives Java's type system: the alternatives (`@Nullable ClassName` propagated through 100+ call sites, or `Optional<TableRef>` propagated through the model itself) lose more than they save.

## `jooqPackage` survivors

After the migration, `jooqPackage` is gone from every emitter that takes a `TableRef` / `KeyRef`. Four production sites legitimately keep it as the schema-package root for codegen helpers that scan or produce files in that namespace:

- `JooqCatalog` constructor — loads `<jooqPackage>.DefaultCatalog` reflectively. Unchanged.
- `CatalogBuilder` — converts to a filesystem path (`jooqPackage.replace('.', '/')`) for catalog-jar build wiring. Unchanged.
- `NodeIdEncoderClassGenerator` — the rare empty-node-types arm where no `TableRef` is in scope.
- `EntityFetcherDispatchClassGenerator` — currently takes `jooqPackage`; audit during Phase 1 whether it can take typed refs from the resolved entities instead, or whether it legitimately needs the schema-package root for its dispatch class.

The success criterion is "no emitter constructs `<jooqPackage>.tables.X`, `<jooqPackage>.tables.records.X`, `<jooqPackage>.Tables`, or `<jooqPackage>.Keys` by string concatenation." Bare `jooqPackage` for distinct purposes (filesystem paths, catalog loading, output-package roots for utility classes) is fine.

## Phasing

Four independently buildable phases:

1. **Catalog-side typed `TableEntry` API + TableRef migration.** Replace `javaClassName: String` with `tableClass: ClassName`, `recordClass: ClassName`, `tablesClass: ClassName` on `TableRef`. `JooqCatalog.TableEntry` exposes the typed values; `BuildContext.resolveTable` and `ServiceCatalog.buildTableRef` collapse to a single factory. Catalog-miss returns `Optional<TableRef>` and consumers handle absence via `UnclassifiedType` / `UnclassifiedField` rather than empty sentinels. Drops `+ ".tables"` from every emit site (`GeneratorUtils.ResolvedTableNames`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `MultiTablePolymorphicEmitter`, `SplitRowsMethodEmitter`, `TypeClassGenerator`, `SelectMethodBody`, `TableMethodDirectiveResolver`, `ExternalFieldDirectiveResolver`, etc.). ~50 main sites + 107 test sites.
2. **`KeyRef` on `FkJoin`.** Drops `keysClass = ClassName.get(jooqPackage, "Keys")` from `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`. ~3–5 main sites; cross-schema FK references now compile correctly because each `KeyRef` carries its own host `Keys` class.
3. **Drop `+ ".tables.records"` concatenation.** Switches `TypeFetcherGenerator` (lines 1050, 1118), `ServiceDirectiveResolver` (line 267), and the strict-return computation in `computeExpectedServiceReturnType` to read `tableRef.recordClass()`. ~5 main sites.
4. **Multi-schema fixture + regression test.** Two-schema jOOQ-codegen output added to `graphitron-fixtures-codegen` so pipeline and compilation tiers can reuse it. Compilation-tier test fails on Phase 1's pre-state and passes after.

Phase 1 is the load-bearing change — once it lands, Phases 2 and 3 are mechanical. Phase 4's fixture lands last so the regression check sees the fully-corrected behaviour.

## Touchpoints

Approximate scope from a `jooqPackage` audit (251 references in `graphitron/src/main`):

- `model/`: `TableRef` (rename + new typed fields), `JoinStep.FkJoin` (`KeyRef` replaces `String fkJavaConstant`).
- Catalog boundary: `JooqCatalog.TableEntry` (new typed API + `toTableRef` factory), `JooqCatalog.findForeignKey` / `fkJavaConstantName`, `ServiceCatalog.buildTableRef`, `BuildContext.resolveTable` and FK-resolution helpers (`synthesizeFkJoin`).
- Emitters with `<jooqPackage>.tables`, `<jooqPackage>.tables.records`, `<jooqPackage>.Tables`, `<jooqPackage>.Keys` concatenations: `GeneratorUtils`, `FetcherEmitter`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter`, `TypeClassGenerator`, `TypeFetcherGenerator`, `SelectMethodBody`, `NodeIdEncoderClassGenerator`, plus the `*DirectiveResolver` family.
- After the migration, `jooqPackage` no longer threads through any emitter that takes a `TableRef` / `KeyRef`. It survives at the four sites listed under "`jooqPackage` survivors" above.

## Test posture

A multi-schema test fixture is missing; sakila uses only `public` and the `nodeidfixture` / `idreffixture` schemas live in their own package roots, so neither exercises the failure. The fixture lives in `graphitron-fixtures-codegen` (not inline in tests) so pipeline and compilation tiers can reuse it; it exercises at least table-class, record-class, `Tables.<FIELD>`, and `Keys.<FK>` references across the schema boundary. The compilation tier should fail without this change and pass with it.

For the 107 `new TableRef(...)` test sites, migration uses a fixtures helper (e.g. `TestTables.film()` returning a fully-populated `TableRef` with a stable test-only package), not a constructor overload. Helpers absorb future `TableRef` evolution without re-touching every test; a backward-compat overload would bias every future change toward preserving the legacy shape.

## Rejected alternatives

- `String schemaPackage` field on `TableRef` with a `tablePackage(fallbackJooqPackage)` helper. Rejected because it still reconstructed FQNs at every consumer; the design here remembers typed references instead. Captured so a future Spec author does not retry it.
- A domain-level `QualifiedClassRef` / `QualifiedFieldRef` wrapper around `ClassName`. Rejected because every recent model record (`BatchKey`, `MethodRef`, `HelperRef`, `LifterRef`, `AccessorRef`, `ResultAssembly`, `PayloadAssembly`, `RowsMethodShape`, `ErrorChannel`, `DefaultedSlot`, `ChildField`) carries javapoet `ClassName` / `TypeName` directly. Inventing a wrapper is generality without a forcing function.
- Separate `TableRecordRef` carrying just the record class. Rejected because the record class is the same table viewed from the projection axis (`Table.getRecordType()` on the same `Table<?>`), and every consumer that needs the record class already has the `TableRef` in hand. Per the design-principles rule that new sub-taxonomies must carry distinct information a sibling cannot, the record class is a field on `TableRef`, not a separate variant.
- Sentinel empty `TableRef` (`@Nullable ClassName`, an `EMPTY` sentinel `ClassName`, or `Optional<TableRef>` propagated through the model). Rejected for the structural fix in "Catalog-miss handling": catalog-miss classifies as `UnclassifiedType` before any ref is built, so emit sites never see a partial ref.
