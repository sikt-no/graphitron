---
id: R78
title: "Typed jOOQ class references for multi-schema correctness (TableRef, TableRecordRef, KeyRef)"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
---

# Typed jOOQ class references for multi-schema correctness (TableRef, TableRecordRef, KeyRef)

## Bug surfaced

Generated code does not compile against multi-schema jOOQ codegen. Imports are emitted as `<jooqPackage>.tables.X` and `<jooqPackage>.tables.records.XRecord`, with `Tables.X` and `Keys.<FK>` referenced from `<jooqPackage>.Tables` / `<jooqPackage>.Keys`. In a multi-schema layout jOOQ generates one package per schema, so the correct fully-qualified names carry a schema segment (e.g. `<jooqPackage>.<schemaName>.tables.X`). Single-schema setups using the PostgreSQL `public` default — including all in-tree fixtures — happen to flatten and mask the bug; consumer projects with multiple schemas cannot compile the generated output.

## Root cause

The rewrite threads a single `String jooqPackage` through every emitter and concatenates `".tables"`, `".tables.records"`, `"Tables"`, `"Keys"` literals against it at every reference site. The schema-per-table fact known to the catalog is dropped on the floor; emitters reconstruct a guessed FQN that only matches single-schema layouts.

## Proposed direction

Replace string concatenation against `jooqPackage` with javapoet `ClassName` values and small reference records carried through the model. Each catalog reflection result already knows its own schema; the model captures it once at parse time, emitters consume the typed value with no derivation.

Concretely (final shape to be settled in Spec phase):

- `TableRef` carries the table class as `ClassName`, the schema's `Tables` class as `ClassName`, and the `Tables`-side field name as `String`. Catalog populates them from `Table<?>` reflection. Drops `jooqPackage` from every emitter that takes a `TableRef`.
- New `TableRecordRef` for the record class — `ClassName` of the record — also catalog-populated. Replaces the `<jooqPackage>.tables.records.XRecord` concatenation in `TypeFetcherGenerator`, `ServiceDirectiveResolver`, etc.
- New `KeyRef` for FK references: the `Keys` class as `ClassName` plus the FK constant name as `String`. Replaces the bare `String fkJavaConstant` on `JoinStep.FkJoin` and the `keysClass` lookup at every emit site. Multi-schema cross-FKs work automatically because each FK carries its own host class.
- `JooqCatalog.TableEntry` exposes the catalog-side typed values (as `ClassName` or as plain methods over the wrapped `Table<?>`); `ServiceCatalog` / `BuildContext` hand them to `TableRef` and friends.

This follows the existing precedent of `BatchKey` and `MethodRef` carrying javapoet `TypeName` values from parse time, so the model layer already crosses that boundary intentionally.

## Open design questions

- Whether to put `ClassName` directly on the model records or wrap behind a domain-level `QualifiedClassRef` / `QualifiedFieldRef` (plain strings). `BatchKey` / `MethodRef` precedent argues for the typed form; a domain wrapper would be reusable across all "static member reference" facts.
- Naming and shape of the ref hierarchy: separate `TableRecordRef` and `KeyRef` records, or fold record-class and keys into a shared `QualifiedClassRef` / `QualifiedFieldRef` pair?
- Whether the `Keys` and `Tables` simple-name literals (`"Keys"` / `"Tables"`) are stored or treated as jOOQ codegen conventions kept at the consumer.
- Test-fixture impact: ~50+ `new TableRef(...)` sites across the test tree need the new constructor shape. Worth a 4-arg convenience overload during the migration, or do all in one shot?

## Touchpoints

Approximate scope from a `jooqPackage` audit (251 references in `graphitron/src/main`):

- `model/`: `TableRef`, `JoinStep.FkJoin`, plus new `TableRecordRef` / `KeyRef`.
- Catalog boundary: `JooqCatalog.TableEntry`, `JooqCatalog.findForeignKey` / `fkJavaConstantName`, `ServiceCatalog.buildTableRef`, `BuildContext.resolveTable` and FK-resolution helpers.
- Emitters with `<jooqPackage>.tables`, `<jooqPackage>.tables.records`, `<jooqPackage>.Tables`, `<jooqPackage>.Keys` concatenations: `GeneratorUtils`, `FetcherEmitter`, `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`, `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter`, `TypeClassGenerator`, `TypeFetcherGenerator`, `SelectMethodBody`, `NodeIdEncoderClassGenerator`, plus the `*DirectiveResolver` family.
- After the migration, `jooqPackage` should no longer be threaded through any emitter that takes a `TableRef` / `KeyRef` / `TableRecordRef`. It survives only at the catalog boundary (loading `DefaultCatalog`) and possibly `NodeIdEncoderClassGenerator` for the rare empty-node-types case.

## Test posture

A multi-schema test fixture is missing; sakila uses only `public` and the `nodeidfixture` / `idreffixture` schemas live in their own package roots, so neither exercises the failure. Adding a fixture with two schemas under a shared base package, exercising at least table-class, record-class, `Tables.<FIELD>`, and `Keys.<FK>` references across the schema boundary, is in scope so the bug stays fixed. The compilation tier should fail without this change and pass with it.

## Prior work in flight

Two `wip:` commits on branch `claude/fix-jooq-imports-zigSN` (`ead3c20`, `bcc3b76`) explored an early approach that added a `String schemaPackage` field to `TableRef` with a `tablePackage(fallbackJooqPackage)` helper. That approach was rejected during design discussion because it kept reconstructing FQNs at every consumer instead of remembering typed references. The work is preserved in branch history but not on trunk; it will be reset before this item enters Spec.
