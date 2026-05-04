---
id: R78
title: "Typed jOOQ class references for multi-schema correctness (TableRef, ForeignKeyRef)"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
---

# Typed jOOQ class references for multi-schema correctness (TableRef, ForeignKeyRef)

## Bug surfaced

Generated code does not compile against multi-schema jOOQ codegen. Imports are emitted as `<jooqPackage>.tables.X` and `<jooqPackage>.tables.records.XRecord`, with `Tables.X` and `Keys.<FK>` referenced from `<jooqPackage>.Tables` / `<jooqPackage>.Keys`. In a multi-schema layout jOOQ generates one package per schema, so the correct fully-qualified names carry a schema segment (e.g. `<jooqPackage>.<schemaName>.tables.X`). Single-schema setups using the PostgreSQL `public` default, including all in-tree fixtures, happen to flatten and mask the bug; consumer projects with multiple schemas cannot compile the generated output.

## Root cause

The rewrite threads a single `String jooqPackage` through every emitter and concatenates `".tables"`, `".tables.records"`, `"Tables"`, `"Keys"` literals against it at every reference site. The schema-per-table fact known to the catalog is dropped on the floor; emitters reconstruct a guessed FQN that only matches single-schema layouts.

## Proposed direction

Replace string concatenation against `jooqPackage` with javapoet `ClassName` values populated once at parse time from `Table<?>` reflection. Each catalog reflection result already knows its own schema; the model captures it once at parse time, emitters consume the typed value with no derivation.

- `TableRef` carries — alongside the existing `tableName: String` (SQL identity, kept as the catalog lookup key) and `primaryKeyColumns` — three javapoet `ClassName` fields populated by reflection: `tableClass` (the `<schemaPackage>.tables.<X>` class, e.g. `Film`), `recordClass` (`<schemaPackage>.tables.records.<X>Record`), and `constantsClass` (the schema's `Tables` constants class). Plus the `Tables`-side field name as `String` (e.g. `"FILM"`). The simple-class `String javaClassName` field goes away — it was only ever used to rebuild a `ClassName` at every read site. The third field is named `constantsClass` rather than `tablesClass` deliberately: `tableClass` and `tablesClass` would differ by a single `s` and code review of 50+ migrated sites would not catch a swap. `constantsClass` describes what the `Tables` class actually is (a holder for table constants) and reads distinctly next to its siblings.
- New `ForeignKeyRef` for FK references: the `Keys` class as `ClassName` plus the FK constant name as `String`. Replaces the bare `String fkJavaConstant` on `JoinStep.FkJoin` and the `keysClass` lookup at every emit site. The `Keys` class belongs to the FK origin table's schema, so each `ForeignKeyRef` carries its own host class and cross-schema FKs join correctly without any per-emitter schema arithmetic. Named `ForeignKeyRef` (not `KeyRef`) because in jOOQ "Key" covers PKs and FKs both, and the host class is literally `Keys` containing both kinds; PKs already live on `TableRef.primaryKeyColumns` and have no `Ref` cousin, so the asymmetry is real and the name should reflect the FK-only scope.
- `JooqCatalog.TableEntry` becomes the single typed source. Its API: `tableClass(): ClassName`, `recordClass(): ClassName` (from `Table.getRecordType()`), `constantsClass(): ClassName`, `pkColumnRefs(): List<ColumnRef>`, and `toTableRef(sqlName): TableRef` as the one factory. Both `BuildContext.resolveTable` and `ServiceCatalog.buildTableRef` collapse to `tableEntry.toTableRef(sqlName)` — the duplicated PK-column materialisation in those two methods is consolidated as a side-effect of this change. Note the collapse is asymmetric: `ServiceCatalog.resolveTable` already returns `Optional<TableRef>`; `BuildContext.resolveTable` is the side that returns the empty-shape sentinel and migrates to match. The `sqlName` parameter on `toTableRef` is the directive-supplied form (case-preserved for error messages), not `entry.table().getName()` — the catalog lookup key is preserved from the user-visible name even when jOOQ canonicalises differently.
- `JooqCatalog.findKey(sqlConstraintName): Optional<ForeignKeyRef>` replaces today's `fkJavaConstantName(): Optional<String>`. The new shape returns the typed ref directly so the empty-string `.orElse("")` fallback at `BuildContext.synthesizeFkJoin` becomes impossible by construction; on miss, the containing field is rejected upstream (see "Catalog-miss handling").
- `ClassName` directly on the model records, not behind a domain wrapper. Follows the precedent of `BatchKey`, `MethodRef`, `HelperRef`, `LifterRef`, `AccessorRef`, `ResultAssembly`, `PayloadAssembly`, `RowsMethodShape`, `ErrorChannel`, `DefaultedSlot`, `ChildField`. The `Tables` / `Keys` simple-name literals are jOOQ codegen conventions kept inside `JooqCatalog`, not stored on every ref.
- No separate `TableRecordRef`. The record class is the same table viewed from the projection axis; `Table.getRecordType()` produces it from the same `Table<?>` the `tableClass` came from. Per the design-principles rule "each new sub-taxonomy proposal comes with a one-line note on what distinct information it carries that a sibling cannot — otherwise it's probably a field on an existing record," `recordClass: ClassName` is a field on `TableRef`. Every consumer that needs it already has the `TableRef` in hand.

## Catalog-miss handling

Today `BuildContext.resolveTable` returns `new TableRef(sqlName, "", "", List.of())` and `synthesizeFkJoin` returns `fkJavaConstant = ""` when the catalog has no entry. Empty-string sentinels survive `String javaClassName` but `ClassName` has no good empty value. The fix is structural, not a new sentinel: catalog-miss classifies the containing type as `UnclassifiedType` (and the field as `UnclassifiedField`) before any `TableRef` / `ForeignKeyRef` is constructed. Emit-site code never sees a partially-resolved ref.

Concretely, `BuildContext.resolveTable` becomes `Optional<TableRef>` (or returns through the existing `Resolved.{Ok | Rejected}` shape used elsewhere in the builder), and the call sites that today consume the empty-shape fallback are reachable only on the resolved arm. Same shape for `synthesizeFkJoin`'s FK-constant fallback. This expands R78's surface area but is the only design that survives Java's type system: the alternatives (`@Nullable ClassName` propagated through 100+ call sites, or `Optional<TableRef>` propagated through the model itself) lose more than they save.

Optional propagation touches a small enumerable set inside `BuildContext`. The four `synthesizeFkJoin` call sites — implicit-FK inference (`parsePath`, today line 500), the `{table:}` and `{key:}` arms of `parsePathElement` (today lines ~626 and ~648), and the NodeId synthesis shim (today line 1086) — all pre-resolve the FK via `catalog.findForeignKey(...)` or `findForeignKeysBetweenTables(...)` and so already operate on the resolved arm. The shim site additionally calls `resolveTable(targetTableOpt.get())` on a name pulled from a present-`Optional`; that call lifts to `Optional<TableRef>` and propagates upward to the existing `Unresolved` return when absent, with no new control flow. `synthesizeFkJoin` itself becomes `Optional<FkJoin>`: when `originTable` or `targetTable` cannot resolve (catalog-miss on a FK whose endpoint is not in the catalog jar), the helper returns empty and the surrounding builder routes to `UnclassifiedField` along the same path that handles `findKey` misses.

## `jooqPackage` survivors

After the migration, `jooqPackage` is gone from every emitter that takes a `TableRef` / `ForeignKeyRef`. Two production sites legitimately keep it as the schema-package root for codegen helpers that scan or produce files in that namespace:

- `JooqCatalog` constructor — loads `<jooqPackage>.DefaultCatalog` reflectively. Unchanged.
- `CatalogBuilder` — converts to a filesystem path (`jooqPackage.replace('.', '/')`) for catalog-jar build wiring. Unchanged.

`NodeIdEncoderClassGenerator` is *not* a survivor. The non-empty arm builds a single `ClassName.get(jooqPackage, "Tables")` and reuses it across every `NodeType`'s decode method (today's lines 156-163, 191-229), which is multi-schema-broken whenever node types live in different schemas. Each `NodeType` already carries a `TableRef`; the migration reads `nt.table().constantsClass()` per node type instead of synthesising one from `jooqPackage`. The empty-node-types short-circuit at the top of the method retains its early return and stops needing `jooqPackage` at all (the parameter drops from the signature).

`EntityFetcherDispatchClassGenerator` takes `jooqPackage` today and threads it through `SelectMethodBody`, which still concatenates `jooqPackage + ".tables"` against `entity.table().javaClassName()` (today line 81). The migration switches `SelectMethodBody` to read `entity.table().tableClass()` directly; the `jooqPackage` parameter then drops out of `EntityFetcherDispatchClassGenerator`'s public surface in the same change. No emitter survives needing `jooqPackage` other than the two infrastructure sites listed above.

The success criterion is "no emitter constructs `<jooqPackage>.tables.X`, `<jooqPackage>.tables.records.X`, `<jooqPackage>.Tables`, or `<jooqPackage>.Keys` by string concatenation." Bare `jooqPackage` for the two infrastructure purposes above (filesystem path; catalog loading) is fine.

## Phasing

Four independently buildable phases. The fixture lands first so the compilation tier turns red before any migration code is written; each subsequent phase shrinks the failure surface against a known-failing baseline. This matches "validator mirrors classifier invariants" from the design principles: every classifier decision that implies a generator branch fails at validate time if the branch is unimplemented, and the same shape applies here for the emitter branch.

1. **Multi-schema fixture + regression test.** Two-schema jOOQ-codegen output added to `graphitron-fixtures-codegen` so pipeline and compilation tiers can reuse it; the fixture's two schemas should share at least one table name so the resolution-time disambiguation question (see "Out of scope" below) surfaces explicitly rather than masquerading as a green test. Compilation tier asserts the generated output compiles against the multi-schema layout. After this phase the compilation tier is red, with a failure message that names the missing schema segment in the FQN. Phases 2-4 each turn a slice green.
2. **Catalog-side typed `TableEntry` API + TableRef migration.** Replace `javaClassName: String` with `tableClass: ClassName`, `recordClass: ClassName`, `constantsClass: ClassName` on `TableRef`. `JooqCatalog.TableEntry` exposes the typed values; `BuildContext.resolveTable` and `ServiceCatalog.buildTableRef` collapse to a single factory (`BuildContext` aligns to `ServiceCatalog`'s already-correct `Optional<TableRef>` shape — the collapse is one-sided alignment, not a merge of two equally-broken halves). Catalog-miss returns `Optional<TableRef>` and consumers handle absence via `UnclassifiedType` / `UnclassifiedField` rather than empty sentinels. Drops `+ ".tables"` and `ClassName.get(jooqPackage, "Tables")` from every emit site (`GeneratorUtils.ResolvedTableNames`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `MultiTablePolymorphicEmitter`, `SplitRowsMethodEmitter`, `TypeClassGenerator`, `SelectMethodBody`, `FetcherEmitter`, `NodeIdEncoderClassGenerator`'s per-NodeType decode arm, `TableMethodDirectiveResolver`, `ExternalFieldDirectiveResolver`, etc.). ~50 main sites; the existing `TestFixtures` helper (already centralising `new TableRef(...)` for test code) is extended to expose `tableClass`/`recordClass`/`constantsClass` so the 107 test sites flip uniformly without each test re-touching.
3. **`ForeignKeyRef` on `FkJoin`.** Replaces `String fkJavaConstant` with `ForeignKeyRef` on `JoinStep.FkJoin`; `JooqCatalog.findKey(sqlConstraintName): Optional<ForeignKeyRef>` replaces `fkJavaConstantName(): Optional<String>`. Drops `keysClass = ClassName.get(jooqPackage, "Keys")` from `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`. ~3–5 main sites; cross-schema FK references now compile correctly because each `ForeignKeyRef` carries its own host `Keys` class.
4. **Drop `+ ".tables.records"` concatenation.** Switches `TypeFetcherGenerator` (lines 1050, 1118), `ServiceDirectiveResolver` (line 267), and the strict-return computation in `computeExpectedServiceReturnType` to read `tableRef.recordClass()`. ~5 main sites; closes out the last `<jooqPackage>.tables.records` site and turns the compilation tier fully green.

Phase 1 establishes the forcing function; Phase 2 is the load-bearing change; Phases 3 and 4 are mechanical.

## Touchpoints

Approximate scope from a `jooqPackage` audit (251 references in `graphitron/src/main`):

- `model/`: `TableRef` (rename + new typed fields), `JoinStep.FkJoin` (`ForeignKeyRef` replaces `String fkJavaConstant`).
- Catalog boundary: `JooqCatalog.TableEntry` (new typed API + `toTableRef` factory), `JooqCatalog.findForeignKey` / `findKey` (replaces `fkJavaConstantName`), `ServiceCatalog.buildTableRef`, `BuildContext.resolveTable` and FK-resolution helpers (`synthesizeFkJoin`).
- Emitters with `<jooqPackage>.tables`, `<jooqPackage>.tables.records`, `<jooqPackage>.Tables`, `<jooqPackage>.Keys` concatenations: `GeneratorUtils`, `FetcherEmitter`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter`, `TypeClassGenerator`, `TypeFetcherGenerator`, `SelectMethodBody`, `NodeIdEncoderClassGenerator`, plus the `*DirectiveResolver` family.
- After the migration, `jooqPackage` no longer threads through any emitter that takes a `TableRef` / `ForeignKeyRef`. It survives only at the two sites listed under "`jooqPackage` survivors" above.

## Test posture

A multi-schema test fixture is missing; sakila uses only `public` and the `nodeidfixture` / `idreffixture` schemas live in their own package roots, so neither exercises the failure. The fixture lives in `graphitron-fixtures-codegen` (not inline in tests) so pipeline and compilation tiers can reuse it; it exercises at least table-class, record-class, `Tables.<FIELD>`, and `Keys.<FK>` references across the schema boundary, and includes at least one SQL table name shared between the two schemas so the resolution-time disambiguation question (see "Out of scope" below) surfaces. The compilation tier should fail without this change and pass with it.

For the 107 `new TableRef(...)` test sites, migration extends the existing `TestFixtures` helper (which already centralises ~30 of those constructions today) so each `TableRef` factory grows the new typed fields once. Helpers absorb future `TableRef` evolution without re-touching every test; a backward-compat overload would bias every future change toward preserving the legacy shape.

## Rejected alternatives

- `String schemaPackage` field on `TableRef` with a `tablePackage(fallbackJooqPackage)` helper. Rejected because it still reconstructed FQNs at every consumer; the design here remembers typed references instead. Captured so a future Spec author does not retry it.
- A domain-level `QualifiedClassRef` / `QualifiedFieldRef` wrapper around `ClassName`. Rejected because every recent model record (`BatchKey`, `MethodRef`, `HelperRef`, `LifterRef`, `AccessorRef`, `ResultAssembly`, `PayloadAssembly`, `RowsMethodShape`, `ErrorChannel`, `DefaultedSlot`, `ChildField`) carries javapoet `ClassName` / `TypeName` directly. Inventing a wrapper is generality without a forcing function.
- Separate `TableRecordRef` carrying just the record class. Rejected because the record class is the same table viewed from the projection axis (`Table.getRecordType()` on the same `Table<?>`), and every consumer that needs the record class already has the `TableRef` in hand. Per the design-principles rule that new sub-taxonomies must carry distinct information a sibling cannot, the record class is a field on `TableRef`, not a separate variant.
- Sentinel empty `TableRef` (`@Nullable ClassName`, an `EMPTY` sentinel `ClassName`, or `Optional<TableRef>` propagated through the model). Rejected for the structural fix in "Catalog-miss handling": catalog-miss classifies as `UnclassifiedType` before any ref is built, so emit sites never see a partial ref.
- Naming the FK ref `KeyRef` (parallel to `TableRef`) and the constants-class field `tablesClass` (parallel to `tableClass`). Rejected for review-clarity reasons: in jOOQ "Key" covers PKs and FKs both (the host class `Keys` literally holds both kinds), and `tableClass`/`tablesClass` differ by a single `s` so a swap would not catch reviewer eye across 50+ migrated sites. Use `ForeignKeyRef` and `constantsClass` to keep the names self-disambiguating.

## Out of scope (follow-up Backlog stub)

R78 fixes the *emit* side: typed `ClassName` references so generated code carries the right schema segment in every FQN. It does not address the *resolution* side, where `JooqCatalog.findTable(sqlName)` does `catalog.schemaStream().flatMap(...).findFirst()` and silently picks whichever schema iterates first when two schemas share a table name. The Phase 1 fixture surfaces this (its two schemas share at least one table name), but the resolution itself wants its own roadmap item — the design space includes "first schema wins with a warning", "fail-loudly when ambiguous", and "extend `@table(name:)` to accept `schema.name`". The Phase 1 fixture's behaviour with whichever option is chosen will be the spec author's first concrete example.
