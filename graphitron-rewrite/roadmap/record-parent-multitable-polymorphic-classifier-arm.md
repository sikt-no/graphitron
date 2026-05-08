---
id: R105
title: "@record-parent multi-table polymorphic ChildField classifier arm"
status: Spec
bucket: architecture
priority: 5
theme: structural-refactor
depends-on: []
---

# @record-parent multi-table polymorphic ChildField classifier arm

Multi-table polymorphic interface / union child fields on `@record`-backed parents are deferred today: the `ReturnTypeRef.PolymorphicReturnType` arm of `FieldBuilder.classifyChildFieldOnResultType` (`FieldBuilder.java:2737`) returns `Rejection.deferred("@record type returning a polymorphic type is not yet supported", "")`. Schema authors using `@record` parents (POJO or Java record sources) cannot model unions or interfaces of multiple participant tables as child fields; the workarounds are to flatten the union into a single concrete type or move the parent to a table-backed source.

R102 landed the structural prerequisites: `BatchKey.RecordParentBatchKey` slot on `ChildField.{InterfaceField, UnionField}` plus delegation to `GeneratorUtils.buildRecordParentKeyExtraction` (exhaustive over all four permits). The slot type accepts every permit, but classification reaches only `RowKeyed` and only on the table-backed branch (`FieldBuilder.classifyObjectReturnChildField` at `:538` and `:558`). R105 wires up the missing classifier arm so all four permits — `RowKeyed`, `LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany` — become reachable when the parent is `@record`-backed.

## Anchor-table model

Each polymorphic participant resolves its own join path back to a *hub* table (the table participants share an FK to). Currently the table-backed arm fabricates a `JooqTableRecordType(parentTypeName, ..., parentTableType.table())` so downstream code can read the hub via `parentResultType.table()` (`FieldBuilder.java:539-540`); two consumers (`MultiTablePolymorphicEmitter`'s `parentInput` VALUES table and `validateChildMultiTableParentPk`'s arity rule) re-derive the same fact off that field. R105's new arm has four permits with three different hub sources, so re-deriving the hub at every consumer would multiply that smell by four.

Lift the hub to a typed component on `ChildField.{InterfaceField, UnionField}`:

```java
record InterfaceField(
    ...,
    BatchKey.RecordParentBatchKey parentKey,
    GraphitronType.ResultType parentResultType,
    TableRef parentAnchorTable          // NEW
) implements ChildField { ... }
```

`parentAnchorTable` is the SQL identity participants FK to. Producer-side mapping per permit:

| Permit | parentResultType (key extraction) | parentAnchorTable (JOIN anchor) |
|---|---|---|
| `RowKeyed` (table-backed parent) | `JooqTableRecordType(parentTable)` | `parentTable` |
| `RowKeyed` (`@record` parent backed by jOOQ TableRecord) | `JooqTableRecordType(parentTable)` | `parentTable` |
| `LifterRowKeyed` (`@record` + `@batchKeyLifter`) | `PojoResultType` / `JavaRecordType` | `lifter.hop().targetTable()` |
| `AccessorKeyedSingle` (`@record` + typed Record accessor, single) | `PojoResultType` / `JavaRecordType` | `accessor.hop().targetTable()` |
| `AccessorKeyedMany` (`@record` + typed Record accessor, list/set) | `PojoResultType` / `JavaRecordType` | `accessor.hop().targetTable()` |

`parentResultType` keeps its R102 meaning (parent-object key-extraction shape, threaded into `buildRecordParentKeyExtraction`). The two slots have distinct semantics; on the table-backed `RowKeyed` arm they happen to point at the same table.

## Implementation

**Model lift.** Add `TableRef parentAnchorTable` to `ChildField.InterfaceField` and `ChildField.UnionField` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java:291-326`). Update the existing producer at `FieldBuilder.classifyObjectReturnChildField:538-543, 558-563` to pass `parentTableType.table()` in. Update `MultiTablePolymorphicEmitter` and `validateChildMultiTableParentPk` to read `field.parentAnchorTable()` instead of `parentResultType.table()` / `parentTableType.table()` re-derivation.

This phase is a structural refactor: no new classifier behaviour, no test-tier additions beyond the existing pipeline-tier coverage continuing to pass. Lands first because every subsequent phase depends on the new slot.

**Polymorphic classifier arm.** Replace the `Rejection.deferred` at `FieldBuilder.java:2737` with a real arm. The arm:

1. Resolves `parentKey: BatchKey.RecordParentBatchKey` and `anchorTable: TableRef` via a new helper `resolveRecordParentKeyForPolymorphic(fieldDef, parentTypeName, parentResultType, parentBackingClass, fieldWrapper)`. The helper is a builder-internal sealed result (`PolymorphicRecordParentResolution.{Resolved | Rejected}`), per the principles' "Builder-step results are sealed" rule. Producer arms inside the helper:
    - `JooqTableRecordType` parent: returns `Resolved(RowKeyed(parent.table().pkCols()), parent.table())`.
    - `@batchKeyLifter` directive present: delegates to a new `BatchKeyLifterDirectiveResolver.resolveForPolymorphic(parentTypeName, fieldDef, parentResultType)` overload that resolves the lifter without consulting any field-level `@table` element type. Hub is the lifter's declared `targetTable`.
    - Typed-accessor parent (POJO / Java record with `TableRecord`-returning zero-arg accessor): delegates to a new sibling `deriveBatchKeyFromHubAccessor(fieldName, fieldWrapper, parentResultType, parentBackingClass)` that scans the parent's accessors, picks the unique element-Record-table match, and returns `AccessorKeyedSingle` / `AccessorKeyedMany` keyed on `fieldWrapper.isList()`. Hub is the accessor's element table.
    - No producer matches: `Rejected` with the three-option `AUTHOR_ERROR` message (mirrors `resolveRecordParentBatchKey` for table-bound fields).
2. Reuses `resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName, location, anchorTable, participants)` — `:3528-3541`, unchanged — by passing the resolved hub as `parentTable: TableRef`. Per-participant FK auto-discovery falls out: any participant lacking a unique FK to the hub surfaces a `Rejection.structural` naming the participant.
3. Constructs `InterfaceField` / `UnionField` with `parentKey`, `parentResultType`, `parentAnchorTable = anchorTable`, and the resolved per-participant join paths.

**Sibling helpers, not widened signatures.** `deriveBatchKeyFromTypedAccessor` (`FieldBuilder.java:3026-...`) keeps its `tb: TableBoundReturnType` parameter and its two `@LoadBearingClassifierCheck` keys (`accessor-rowkey-shape-resolved`, `accessor-rowkey-cardinality-matches-field`) unchanged. The new `deriveBatchKeyFromHubAccessor` is a sibling sharing private match-and-reduce internals; it carries its own load-bearing key (`accessor-rowkey-shape-resolved-against-hub`) describing the polymorphic-callsite invariant ("accessor's element table = hub table; cardinality matches the polymorphic field's wrapper"). Same separation for `BatchKeyLifterDirectiveResolver`: existing entry `resolve(parentTypeName, fieldDef, parentResultType, elementTypeName)` is unchanged; new `resolveForPolymorphic(parentTypeName, fieldDef, parentResultType)` is a parallel arm. Two sibling resolvers, two load-bearing keys, no widened generics.

**Load-bearing keys.** R105 owns:

- `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven-record-parent` on the new producer at `classifyChildFieldOnResultType`. Sibling to the existing `…-table-backed` key (renamed from R102's bare `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven` so the producer-site distinction is navigable). Update the emitter's `@DependsOnClassifierCheck` at `MultiTablePolymorphicEmitter.java:693, 763, 828, 1138` and `GeneratorUtils.buildRecordParentKeyExtraction` to depend on both keys (one annotation per consumer is permitted; the audit groups by key, not by site).
- `multitable-polymorphic-child.participant-paths-anchor-on-hub` on `resolveChildPolymorphicJoinPaths`. Asserts: every entry of `participantJoinPaths` has its first hop's source side equal to `field.parentAnchorTable()`. The polymorphic emitter's rows-method JOIN clause depends on this; without the key the invariant is only enforced by `mvn compile -pl :graphitron-sakila-example` against real jOOQ.
- `accessor-rowkey-shape-resolved-against-hub` on `deriveBatchKeyFromHubAccessor`. Same shape as the existing `accessor-rowkey-shape-resolved` (single matching public zero-arg non-bridge non-synthetic instance accessor; element type extends `org.jooq.TableRecord`), but the identity check is "accessor's element table = hub" instead of "= field's `@table` return".
- `lifter-classifies-as-record-table-field` description widens to list the polymorphic projection target alongside `RecordTableField` / `RecordLookupTableField`. The key name stays; only the prose updates. (Two-key split would require renaming the consumer-side annotations on every existing dependent; not worth the churn for a description that fits one expanded sentence.)

## Validator

Extend `validateChildMultiTableParentPk` (`GraphitronSchemaValidator.java:347`). The current `if (!(parentType instanceof TableBackedType tbt)) return` early-exit becomes a sealed switch over the `parentResultType` axis. Predicate per arm:

- `JooqTableRecordType` parent: existing PK-non-empty + ≤21 column cap on `parent.table().pkCols()`. (No behaviour change; the `RowKeyed`-on-`@record` arm classifies parent as `JooqTableRecordType` and routes through this same check.)
- `PojoResultType` / `JavaRecordType` parent with `@batchKeyLifter`: assert via `BatchKeyLifterDirectiveResolver`'s arity check (already enforced ≤ jOOQ's `Row22` cap; the validator does not duplicate, only documents source-of-authority).
- `PojoResultType` / `JavaRecordType` parent with typed accessor: hub element table's PK ≤21 column cap. The hub PK is the element table's PK by construction (per `accessor-rowkey-shape-resolved-against-hub`'s element-table identity).
- Three-option `AUTHOR_ERROR` rejection when none of the above match (mirrors classifier-side `Rejection.structural` shape).

The existing `validateMultiTableParticipants` runs for every multi-table polymorphic field regardless of parent shape; no extension needed there.

## Tests

Pipeline-tier coverage in `TypeFetcherGeneratorTest` (`graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGeneratorTest.java`). Today's helpers `childInterfaceField` / `childUnionField` (`:1961-1987`) hardwire `RowKeyed` + `JooqTableRecordType`; R105 adds parameterised siblings:

- `childInterfaceField_recordParent_rowKeyed` (parent backed by jOOQ TableRecord, classified as `RowKeyed` via the new arm). Pin equivalence with the existing table-backed `childInterfaceField` via `assertThat(typeSpec).isEqualTo(...)` on the model-level `TypeSpec`. Drift in either path fails the comparison.
- `childInterfaceField_recordParent_lifterRowKeyed` (parent is `JavaRecordType` carrying a `@batchKeyLifter` directive whose resolved hop targets a hub table; participants FK to the hub).
- `childInterfaceField_recordParent_accessorKeyedSingle` (parent is `PojoResultType` exposing a zero-arg `Address`-Record accessor; single-cardinality polymorphic field). 
- `childInterfaceField_recordParent_accessorKeyedMany` (same, but `List<AddressRecord>` accessor and a list-cardinality polymorphic field).
- `childUnionField_recordParent_*` siblings for each of the four shapes above. Per the existing `childUnionField_listForm_emitsSameDataLoaderShapeAsInterfaceField` precedent (`:2042-2058`), the union-arm tests can pin equivalence with the interface-arm fixture instead of asserting body shape independently.

No code-string assertions on emitted method bodies; the principle bans them. Equivalence is asserted at the `TypeSpec` level (or model level when finer granularity matters); cardinality / loader-dispatch shape is read off `field.parentKey().dispatch()` and `RowN` / `RecordN` arity is read off `parentKey.preludeKeyColumns().size()`.

Validator-tier coverage extends `InterfaceFieldValidationTest` and `UnionFieldValidationTest` with `@record`-parent fixtures: missing accessor + missing lifter + non-table-backed source → `AUTHOR_ERROR`; over-21-column hub PK → arity rejection.

Pipeline tier is the primary signal. Execution-tier coverage on `graphitron-sakila-example` is *not* required by R105: the `loader.loadMany` dispatch on `AccessorKeyedMany` flows through the same DataLoader registry as `loader.load` and the rows-method emitter is unchanged. R102's `AddressOccupantsListBatchingTest` already pins the four-statement batched fanout. If a future change forks the rows-method per `LoaderDispatch`, that's the trigger to add a sibling execution-tier test.

## Out of scope

- Per-participant constraint coverage beyond what `resolveChildPolymorphicJoinPaths` already enforces (e.g. cardinality of participant FKs, multi-tenant-discriminator parity). Filed as follow-up if a real schema surfaces the gap.
- A user-facing `@record`-with-polymorphic-children documentation pass (`docs/getting-started.adoc` or similar). Filed as follow-up; the contract is internal until the public surface is named.
- The connection-arm participant single-PK truncation lift (deferred from R102's rework). Picks up under its own roadmap entry when reached.
- Allowing a polymorphic field to carry an explicit `@reference` directive naming the hub (alternative design B from the design fork notes). Not chosen; the per-participant FK auto-discovery in `resolveChildPolymorphicJoinPaths` is the canonical mechanism, and adding a directive shape would duplicate the lifter / accessor's existing target-table commitment.
