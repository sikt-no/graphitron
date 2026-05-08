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

R102 landed the structural prerequisites: `BatchKey.RecordParentBatchKey` slot on `ChildField.{InterfaceField, UnionField}` plus delegation to `GeneratorUtils.buildRecordParentKeyExtraction` (exhaustive over all four permits). The slot type accepts every permit, but classification reaches only `RowKeyed` and only on the table-backed branch (`FieldBuilder.classifyObjectReturnChildField` at `:538` and `:558`). R105 wires up three of the four currently-unreachable classifier paths on `@record`-backed parents: `RowKeyed` (parent is a `@record` whose backing class is a jOOQ `TableRecord`), `AccessorKeyedSingle` (typed zero-arg `TableRecord`-returning accessor on the parent's backing class, single cardinality), and `AccessorKeyedMany` (same, list / set cardinality). `LifterRowKeyed` for polymorphic returns is deferred — see Out of scope.

## Hub table is a classifier-internal local

Each polymorphic participant resolves its own join path back to a *hub* table (the table participants share an FK to). The new arm computes the hub when it resolves the `RecordParentBatchKey` permit:

- `RowKeyed` on `JooqTableRecordType` parent: hub is the parent's mapped table.
- `AccessorKeyedSingle` / `AccessorKeyedMany` on Pojo / JavaRecord parent: hub is the unique matching accessor's element-Record table.

The hub is consumed at classification time (handed to `resolveChildPolymorphicJoinPaths` as the `parentTable: TableRef` anchor) and never re-read after the field record is constructed. `MultiTablePolymorphicEmitter` operates on the column slots that ride on `parentKey: BatchKey.RecordParentBatchKey` and the `participantJoinPaths` map — both already reified by R102. `validateChildMultiTableParentPk` reads `parentKey.preludeKeyColumns().size()` for the arity cap. Neither consumer needs the hub `Table<?>` reference, so R105 doesn't lift it onto the field record. If a future change introduces a hub-table consumer, the slot lands then with a clear forcing function.

## Implementation

**Polymorphic classifier arm.** Replace the `Rejection.deferred` at `FieldBuilder.java:2737` with a real arm. The arm constructs a builder-internal sealed result `PolymorphicRecordParentResolution.{Resolved(parentKey, hubTable) | Rejected(rejection)}` (per the principles' "Builder-step results are sealed" rule) over the parent shape:

- *`JooqTableRecordType` parent.* `Resolved(RowKeyed(parent.table().primaryKeyColumns()), parent.table())`. Empty PK is the same UnclassifiedField path the existing table-backed arm uses (`FieldBuilder.java:530-537`, `:553-557`).
- *`PojoResultType` / `JavaRecordType` parent.* Delegates to a new sibling helper `deriveBatchKeyFromHubAccessor(fieldName, fieldWrapper, parentResultType)` that:
    1. Resolves the parent's backing class via the existing four-arm switch (`FieldBuilder.java:3034-3046`).
    2. Iterates accessors named after `fieldName` (or `getX` / `isX`) returning `X`, `List<X>`, or `Set<X>` for some concrete `X extends TableRecord` — same matcher loop as `deriveBatchKeyFromTypedAccessor:3052-3097`, factored into a shared private helper.
    3. *Discovers the hub*: the unique matching accessor's element table is the hub. Multiple matching accessors → `Ambiguous`; cardinality mismatch → `CardinalityMismatch`; no match → `None`.
    4. Returns `Resolved(AccessorKeyedSingle(LiftedHop(hub, hub.pkCols()), accessorRef), hub)` or the `…Many` sibling, keyed on `fieldWrapper.isList()`.
- *`JooqRecordType` parent.* `Rejected(structural)` — same shape as today's table-bound case which falls through to None on this parent.
- *No accessor match on a Pojo / JavaRecord parent.* `Rejected(structural)` with the same three-option AUTHOR_ERROR wording `resolveRecordParentBatchKey` produces for table-bound `@record` fields (`FieldBuilder.java:2974`), adjusted to flag that the `@batchKeyLifter` path is currently unsupported on polymorphic returns (see Out of scope).

The classifier then calls `resolveChildPolymorphicJoinPaths(fieldDef, name, parentTypeName, location, hubTable, participants)` — `FieldBuilder.java:3528-3541`, unchanged — passing the resolved hub as the `TableRef parentTable` argument. Per-participant FK auto-discovery falls out: any participant lacking a unique FK to the hub surfaces a `Rejection.structural` naming the participant. The classifier finally constructs `InterfaceField` / `UnionField` with `parentKey`, `parentResultType`, and the resolved per-participant join paths — same shape `classifyObjectReturnChildField` already uses on the table-backed branch.

**Sibling helper, not widened signature.** `deriveBatchKeyFromTypedAccessor` (`FieldBuilder.java:3026-...`) keeps its `tb: TableBoundReturnType` parameter and its two `@LoadBearingClassifierCheck` keys (`accessor-rowkey-shape-resolved`, `accessor-rowkey-cardinality-matches-field`) unchanged. The new `deriveBatchKeyFromHubAccessor` is a sibling sharing the private accessor-iteration helper; the reduction step differs (table-bound case validates against a known `expectedSqlName`; polymorphic case discovers the hub from the matched accessor and returns it).

**Load-bearing keys.** R105 owns:

- *Producer-site rename + new key.* Rename the existing `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven` (one producer at `FieldBuilder.java:425`) to `…-table-backed`, and add `…-record-parent` on the new producer at `classifyChildFieldOnResultType`. The audit (`LoadBearingGuaranteeAuditTest`) forbids two producers under one key, so a per-producer split is forced. Two consumer call sites in `MultiTablePolymorphicEmitter.java` (`:763, :828`) currently reference the old key; each gets a second `@DependsOnClassifierCheck` for the new key (the annotation is repeatable). `GeneratorUtils.buildRecordParentKeyExtraction` doesn't cite this key today; if a citation is added as part of the work, mirror the dual-tag pattern.
- *`accessor-rowkey-shape-resolved-against-hub`* on `deriveBatchKeyFromHubAccessor`. Same shape as the existing `accessor-rowkey-shape-resolved` (single matching public zero-arg non-bridge non-synthetic instance accessor; element type extends `org.jooq.TableRecord`); the identity contract differs ("accessor's element table is the discovered hub" rather than "= field's `@table` return"), so the description spells out the polymorphic-callsite invariant verbatim rather than aliasing the existing key.

## Validator

Extend `validateChildMultiTableParentPk` (`GraphitronSchemaValidator.java:347`). The current `if (!(parentType instanceof TableBackedType tbt)) return` early-exit drops; the validator runs uniformly across parent shapes by reading off `field.parentKey()` directly. All four `RecordParentBatchKey` permits expose `preludeKeyColumns()` (`BatchKey.java:192`) — the same accessor the rows-method prelude uses — so the arity check is a single uniform read:

```java
if (field.parentKey().preludeKeyColumns().size() > 21) {
    errors.add(/* AUTHOR_ERROR keyed on the field name and parent type name */);
}
```

The non-empty invariant is already enforced upstream — `RowKeyed`'s canonical constructor (`BatchKey.java:235-241`) and `JoinStep.LiftedHop`'s constructor (`JoinStep.java:229`) both reject empty key columns at construction time, and the classifier routes empty-PK parents through `UnclassifiedField` so those constructors are unreachable from this path. The validator's job here is purely the upper-bound arity surface, in language tied to the field's source location.

`validateMultiTableParticipants` runs for every multi-table polymorphic field regardless of parent shape; no extension needed there. The "no permit matched" case (Pojo / JavaRecord parent without a typed accessor) surfaces as a classifier-time `UnclassifiedField`, not a validator finding — same shape as the table-bound `@record` field's three-option `AUTHOR_ERROR` produced by `resolveRecordParentBatchKey`.

## Tests

Pipeline-tier coverage in `TypeFetcherGeneratorTest` (`graphitron/src/test/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGeneratorTest.java`). Today's helpers `childInterfaceField` / `childUnionField` (`:1961-1987`) hardwire `RowKeyed` + `JooqTableRecordType`; R105 adds parameterised siblings constructed by direct `InterfaceField` / `UnionField` builders that go through the full classifier path (entry points need to come from a SDL fragment so the new `classifyChildFieldOnResultType` arm is exercised, not bypassed):

- `childInterfaceField_recordParent_rowKeyed` — parent type is `@record`-backed by a jOOQ `TableRecord`; classified as `RowKeyed` via the new arm. Pin equivalence with the existing table-backed `childInterfaceField` via `assertThat(spec).isEqualTo(tableBackedSpec)` on the emitted `TypeSpec`. Drift in either path fails the comparison.
- `childInterfaceField_recordParent_accessorKeyedSingle` — parent is `PojoResultType` exposing a zero-arg `AddressRecord`-returning accessor; single-cardinality polymorphic field.
- `childInterfaceField_recordParent_accessorKeyedMany` — same parent backing class, `List<AddressRecord>`-returning accessor, list-cardinality polymorphic field.
- `childUnionField_recordParent_*` siblings for each of the three shapes above. Per the existing `childUnionField_listForm_emitsSameDataLoaderShapeAsInterfaceField` precedent (`:2042-2058`), the union-arm tests can pin equivalence with the interface-arm fixture rather than asserting body shape independently.

Existing pipeline tests that today exercise the full SDL → classifier path (e.g. `TableFieldPipelineTest`, `LookupTableFieldPipelineTest`) are the canonical pattern for the new fixtures — point at a fixture SDL with a `@record` parent and a polymorphic child, run the classifier, assert the resulting `ChildField.{InterfaceField | UnionField}` carries the expected `parentKey` permit. No code-string assertions on emitted method bodies; the principle bans them. Loader-dispatch shape is read off `field.parentKey().dispatch()` and key arity off `field.parentKey().preludeKeyColumns().size()`. For the accessor-keyed permits, the hub identity is implicit in the resulting `parentKey`'s `LiftedHop` (`((AccessorKeyedSingle) field.parentKey()).hop().targetTable()`).

Validator-tier coverage extends `InterfaceFieldValidationTest` and `UnionFieldValidationTest` with `@record`-parent fixtures: missing accessor on a Pojo / JavaRecord parent → classifier-side `UnclassifiedField` (mirrors the table-bound case); over-21-column hub PK → validator-side arity rejection.

Pipeline tier is the primary signal. Execution-tier coverage on `graphitron-sakila-example` is *not* required by R105: the `loader.loadMany` dispatch on `AccessorKeyedMany` flows through the same DataLoader registry as `loader.load` and the rows-method emitter is unchanged. R102's `AddressOccupantsListBatchingTest` already pins the four-statement batched fanout for the table-backed `RowKeyed` path. If a future change forks the rows-method per `LoaderDispatch`, that's the trigger to add a sibling execution-tier test.

## Out of scope

- *`LifterRowKeyed` for polymorphic returns.* `BatchKeyLifterDirectiveResolver.resolve` derives the lifter's `targetTable` from the field's `@table` element type (`BatchKeyLifterDirectiveResolver.java:147-153`), which doesn't apply to polymorphic returns. Closing the gap requires either (a) extending the `@batchKeyLifter` directive with an explicit hub-naming argument, or (b) inferring the hub from participant-FK convergence — both of which are their own design conversation. Filed as follow-up; for now, `@batchKeyLifter` on a polymorphic-returning field continues to surface a structural rejection, and the new arm's three-option AUTHOR_ERROR wording reflects that.
- Per-participant constraint coverage beyond what `resolveChildPolymorphicJoinPaths` already enforces (e.g. cardinality of participant FKs, multi-tenant-discriminator parity). Filed as follow-up if a real schema surfaces the gap.
- A user-facing `@record`-with-polymorphic-children documentation pass (`docs/getting-started.adoc` or similar). Filed as follow-up; the contract is internal until the public surface is named.
- The connection-arm participant single-PK truncation lift (deferred from R102's rework). Picks up under its own roadmap entry when reached.
- Allowing a polymorphic field to carry an explicit `@reference` directive naming the hub (alternative design B from the design fork notes). Not chosen; per-participant FK auto-discovery in `resolveChildPolymorphicJoinPaths` is the canonical mechanism for the in-scope permits, and adding a directive shape would duplicate the accessor's existing target-table commitment.
