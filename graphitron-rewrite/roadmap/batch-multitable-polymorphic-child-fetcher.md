---
id: R102
title: "Batch DataLoader for non-connection ChildField.UnionField / ChildField.InterfaceField"
status: Spec
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Batch DataLoader for non-connection ChildField.UnionField / ChildField.InterfaceField

`ChildField.UnionField` and `ChildField.InterfaceField` in their non-connection arms emit a synchronous, non-batched per-parent fetcher: every parent invocation runs its own stage-1 UNION ALL plus per-typename stage-2 SELECTs. There is no `DataLoader`, no `parentInput VALUES (idx, parent_pk…)` join, and no scatter array, so a list of N parents fans out to ~N stage-1 unions plus ~N participant SELECTs each, with no dedup for repeated parent PKs across siblings. Observed on `graphitron-sakila-example` against `Address.occupants: [AddressOccupant!]!` (union of `Customer | Staff`): a top-level `customers` query selecting `address.occupants` for 5 customers fires 14 child SQL statements (5 stage-1 unions plus 9 per-typename SELECTs), where the same address PK fires its full chain twice for two pairs of siblings sharing addresses. Expected shape under DataLoader batching: 1 stage-1 UNION ALL with `JOIN parentInput` over the distinct parent PKs, plus 1 per-typename SELECT per participant — so 3 child statements regardless of how many parents share each PK.

The dispatch site is `TypeFetcherGenerator.java:436-461`: both arms pick `MultiTablePolymorphicEmitter.emitMethods` for the non-connection case, which routes to `buildMainFetcher` at `MultiTablePolymorphicEmitter.java:219-309`. `buildMainFetcher` reads `Record parentRecord = (Record) env.getSource()` inline, runs stage 1 with per-branch `WHERE participant_fk = parentRecord.parent_pk`, and dispatches per typename in the same call. The connection arm at the same emitter already does the right thing — `buildBatchedConnectionFetcher` (`:671-748`) registers a path-keyed `DataLoader<RowN<…>, ConnectionResult>` and `buildBatchedConnectionRowsMethod` (`:793-…`) builds the `parentInput VALUES` table, joins it into the per-branch UNION ALL, dispatches per typename, and scatters typed Records into a `result[outerIdx]` array. The list-arm fix is the same shape minus the windowed-CTE pagination: one DataLoader-registering main fetcher, one `rows<Field>(List<RowN<…>>, env)` rows method, returning `List<List<Record>>` indexed 1:1 with the keys list.

## Decision: lift a `BatchKey.RowKeyed` slot onto the records, not a bare `TableRef`

The list-arm fetcher's first need is the parent-side DataLoader key tuple. The shape the emitter relies on is "non-empty list of parent PK columns whose `RowN<…>` arity matches the DataLoader key element type" — so the slot should carry that decided shape, not a `TableRef` from which the emitter rederives it. The right type is `BatchKey.RowKeyed` (`BatchKey.java:232`), the existing framework-derived `ParentKeyed` permit whose component is `List<ColumnRef> parentKeyColumns`. Precedent: `FieldBuilder.deriveSplitQueryBatchKey` (`FieldBuilder.java:2854-2858`) already builds `new BatchKey.RowKeyed(parentTable.primaryKeyColumns())` for the same kind of framework-derived parent-side key on split-query child fields, with no developer-facing source declaration. The rule from R61/R70/R71/R74 — variant identity tracks key shape — is already encoded for us; we consume one of the existing permits, no new permit needed.

### Records and FieldBuilder

`ChildField.InterfaceField` (`ChildField.java:285-297`) and `ChildField.UnionField` (`ChildField.java:304-316`) are identical in component shape today. Both gain one new component, `BatchKey.RowKeyed parentKey`, in the same position on each record. The connection vs. non-connection arm distinction stays where it is — at the dispatch site via `returnType().wrapper() instanceof FieldWrapper.Connection` — so no new sub-variant is introduced.

`FieldBuilder.classifyObjectReturnChildField` (the constructors at `FieldBuilder.java:520-522` and `:531-533`) takes one new derivation step: `new BatchKey.RowKeyed(parentTable.primaryKeyColumns())`, where `parentTable` is the enclosing parent type's `TableRef`. Construction is the only call site of either record, so the change is local. The validator (next section) guarantees `parentTable.primaryKeyColumns()` is non-empty before the constructor runs; classification rejects the inverse upstream.

The single non-DataLoader case for child fields is "the source object already carries the data we need," which is a different shape entirely (resolved via accessor on the parent backing class, not via stage-1/stage-2 SQL). That arm does not multiplex through `MultiTablePolymorphicEmitter`, so the lift onto these two records is unconditional and no sealed sub-variant is introduced.

## Validator: reject empty-PK parent on non-connection arm too

`GraphitronSchemaValidator.validateChildConnectionParentPk` (`GraphitronSchemaValidator.java:329-360`) already rejects `@asConnection` on a multi-table interface/union child whose parent type has no primary key, with rejection message "@asConnection on a multi-table interface/union child field requires the parent type '...' to have a primary key; the DataLoader-batched windowed CTE form keys on the parent table's PK." The list-arm batched form keys the same way, so the same rejection applies; the `instanceof FieldWrapper.Connection` short-circuit at the head of that method is removed (or generalised), and the error message drops "@asConnection" and the windowed-CTE phrasing in favour of "non-empty primary key on the parent type, since the DataLoader key tuple is built from `parentTable.primaryKeyColumns()`." Both `validateInterfaceField` (`:551-555`) and `validateUnionField` (`:557-562`) already call into this method, so the new rejection fires uniformly across both arms with no new dispatch.

The `IllegalStateException` at `MultiTablePolymorphicEmitter.java:682-693` for `parentKeyArity == 0` is removed once the validator covers the case, per *Validator mirrors classifier invariants* and *Classifier guarantees shape emitter assumptions*. The arity > 22 throw stays — that's a structural jOOQ limit independent of the load-bearing classifier guarantee, and the validator can mirror it as a sibling rejection in the same method (filed as a small follow-up if needed; not required for R102 to ship).

## Load-bearing classifier check

New paired annotation, keyed `multitable-polymorphic-child.parent-has-nonempty-pk`. Mirrors the shape of `column-field-requires-table-backed-parent` (`FieldBuilder.java:3146-3152` ↔ `TypeFetcherGenerator.java:287-292`):

- **Producer** on `FieldBuilder.classifyObjectReturnChildField`. Description: "`ChildField.InterfaceField` and `ChildField.UnionField` are constructed only here; this method runs only on parents that the validator has admitted as having a non-empty primary key. Lets the rows-method emitter construct the DataLoader key tuple from `parentKey().parentKeyColumns()` with no defensive empty-list check at emit time."
- **Consumer** on the new shared rows-method helper (and on the existing `buildBatchedConnectionFetcher` arity check site, which is also covered by the same guarantee). `reliesOn`: "Builds the `RowN<…>` DataLoader key tuple straight off `parentKey().parentKeyColumns()`. The hard fail (no fallback path) is the form the load-bearing guarantee takes here: navigation and drift annunciation, not guard elision."

`LoadBearingGuaranteeAuditTest.productionAnnotationsAreConsistent` (`LoadBearingGuaranteeAuditTest.java:58`) picks the pair up automatically once both annotations are present.

## Emitter: two entry points, shared helpers

Keep `emitMethods` (list arm) and `emitConnectionMethods` (connection arm) as distinct entry points — the two arms genuinely fork on identity (windowed-CTE pagination vs. simple `JOIN parentInput`, DataLoader value type `ConnectionResult` vs. `List<List<Record>>`, different rows-method signatures). Collapsing them into one switch on `wrapper instanceof Connection` would be the *N-way `instanceof` chain* the sealed-switch principle warns against.

The uniformly-true moves get extracted as helpers parameterised on the parts that vary:

- **`parentInput VALUES` emitter.** Builds the `VALUES (idx, pk1, pk2, …)` derived table from a `BatchKey.RowKeyed`, the same on both arms.
- **DataLoader key-tuple builder.** Constructs `DSL.row(parentRecord.field1(), parentRecord.field2(), …)` from `parentKey.parentKeyColumns()`.
- **Per-typename dispatcher.** The stage-2 dispatch over `participantJoinPaths` (the typename → record-class scatter) is identity-shaped between list and connection; today it lives inline twice and gets extracted to a private static helper.

`buildBatchedConnectionFetcher` (`:671-748`) and `buildBatchedConnectionRowsMethod` (`:793-…`) read `parentKey()` off the `ChildField.{InterfaceField,UnionField}` record passed through the call chain; their existing `parentTable` parameter stays for non-PK uses (alias derivation, qualified column refs in the windowed-CTE form). The list arm grows two new private static helpers — `buildBatchedListFetcher` and `buildBatchedListRowsMethod` — with the same key-extraction and `parentInput VALUES` shape but no windowed-CTE form. `buildMainFetcher` (`:219-309`) is removed; `emitMethods` routes the `isList` branch to the new batched helpers, and the per-parent fallback for non-list multi-table polymorphic fields stays inside a renamed helper (`buildScalarPerParentFetcher` or similar) since a single-source-record field has nothing to batch over.

The dispatch site at `TypeFetcherGenerator.java:436-461` collapses: both arms (Interface and Union) already mirror each other; the `parentTable` argument disappears from the call (the connection arm reads it from the record now too via `f.parentKey()` plus the existing `parentTable` local for non-PK uses, which is unchanged for the connection arm's other needs).

## Tests

- **L4 (pipeline).** New cases in `TypeFetcherGeneratorTest` alongside the existing `childInterfaceField` / `childUnionField` fixtures (`TypeFetcherGeneratorTest.java:1941-2016`):
  - `childInterfaceField_listForm_emitsOneDataLoaderRegistrationAndOneRowsMethod`: assert exactly one `DataLoaderRegistry.put`-style helper plus one `rows<Field>` method per non-connection union/interface list field; no per-parent `env.getSource()` reads in the main fetcher body.
  - `childInterfaceField_listForm_keyTupleArityMatchesParentPk`: pin the DataLoader key element type to `RowN<…>` of the right arity for both single-PK (`address`) and composite-PK (`film_actor`) parent fixtures.
  - `childUnionField_listForm_emitsSameDataLoaderShapeAsInterfaceField`: equivalence pin between the two arms (mirrors the existing `childUnionField_emitsSameTwoStageStructureAsInterfaceField`).
- **L3 (validator).** New parameterised cases in `InterfaceFieldValidationTest` and `UnionFieldValidationTest`: assert the new rejection ("non-empty primary key required on parent type for multi-table interface/union child") fires for both list and connection arms when the parent type has empty PK, and does not fire when the parent has a PK of any arity.
- **L6 (execution).** New `AddressOccupantsListBatchingTest` in `graphitron-sakila-example`, modelled on `AccessorDerivedBatchKeyTest.java:44-113`. Registers an `org.jooq.ExecuteListener` via `DefaultExecuteListenerProvider`, runs a top-level `customers(first: 5)` query selecting `address.occupants { ... on Customer { … } ... on Staff { … } }`, asserts `QUERY_COUNT == 4` (one customers query plus one stage-1 UNION ALL plus two per-typename SELECTs — three child statements), down from today's 14 (5 stage-1 + 9 stage-2). The test pins the exact statement count, not just an upper bound, so a regression to per-parent fanout fails loudly.
- **Audit.** `LoadBearingGuaranteeAuditTest` is satisfied by the new annotation pair without test changes.

## Acceptance criteria

- `ChildField.InterfaceField` and `ChildField.UnionField` carry a `BatchKey.RowKeyed parentKey` component. `FieldBuilder.classifyObjectReturnChildField` constructs both records with `new BatchKey.RowKeyed(parentTable.primaryKeyColumns())`.
- `GraphitronSchemaValidator.validateChildConnectionParentPk` (renamed if scope warrants, e.g. `validateChildMultiTableParentPk`) fires for both connection and non-connection arms when the parent type has empty PK; rejection message names "non-empty primary key" without `@asConnection` framing.
- `MultiTablePolymorphicEmitter.buildMainFetcher` is replaced by `buildBatchedListFetcher` + `buildBatchedListRowsMethod` for the list arm. The new helpers register a path-keyed `DataLoader<RowN<…>, List<List<Record>>>`, build the `parentInput VALUES` join, dispatch per typename, and scatter typed Records into a `result[outerIdx]` array indexed 1:1 with the keys list. The non-list multi-table polymorphic single-field arm keeps its per-parent shape under a renamed helper.
- The empty-PK `IllegalStateException` at `MultiTablePolymorphicEmitter.java:682-693` is removed; the load-bearing-classifier-check pair (`multitable-polymorphic-child.parent-has-nonempty-pk`) replaces it as the architectural guarantee.
- `emitMethods` and `emitConnectionMethods` stay as distinct entry points. Three private static helpers (`parentInputValues`, `dataLoaderKeyTuple`, `dispatchPerTypename` or equivalent names) host the moves shared between list and connection arms.
- `TypeFetcherGeneratorTest` covers the list-arm DataLoader registration, key-tuple arity, and Interface/Union equivalence for both single-PK and composite-PK parent tables.
- `InterfaceFieldValidationTest` / `UnionFieldValidationTest` cover the new validator rejection on both arms.
- `graphitron-sakila-example`'s `AddressOccupantsListBatchingTest` pins exactly four executed statements for the canonical 5-customer fanout, replacing today's 14.
- `LoadBearingGuaranteeAuditTest` passes with no manual additions.

## Roadmap entries (siblings / dependencies)

- **Sibling of** [R74 / `accessor-row-record-shapes.md`](accessor-row-record-shapes.md) and the R61/R70/R71 line: shape-as-variant-identity on `BatchKey`. R102 consumes an existing `BatchKey.RowKeyed` permit rather than adding a new one — the encoding rule has already paid the cost we benefit from.
- **Mirrors validator-rejection framing from** the R88 `@record`-accessor change (see `changelog.md` entry "record-accessor-validation, R88"): runtime `IllegalStateException` removed in favour of a validator rejection plus a load-bearing-classifier-check pair. Same pattern, different field-classification arm.
- **Unblocks** any later refactor that needs uniform shape across both child-field arms (e.g. a generic batched-child-fetcher abstraction): once `parentKey` is present on both records, the connection arm and the list arm finally read from the same typed slot rather than rederiving from `parentTable` independently.
