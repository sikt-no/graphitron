---
id: R102
title: "Batched key extraction for ChildField.UnionField / ChildField.InterfaceField via BatchKey"
status: Spec
bucket: architecture
priority: 2
theme: structural-refactor
depends-on: []
---

# Batched key extraction for ChildField.UnionField / ChildField.InterfaceField via BatchKey

`MultiTablePolymorphicEmitter` is the one batched-fetcher path in the codebase that does *not* delegate parent-object key extraction to `GeneratorUtils.buildRecordParentKeyExtraction` (`GeneratorUtils.java:186-196`), the canonical sealed-switch helper that handles all four parent-object shapes (`JooqTableRecordType`, `JooqRecordType`, `JavaRecordType`, `PojoResultType`) against all four `BatchKey.RecordParentBatchKey` permits (`RowKeyed`, `LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany`). Both arms cast `env.getSource()` to `org.jooq.Record` inline — the connection arm at `MultiTablePolymorphicEmitter.java:735-736`, the list arm at `:240` — and read PK columns straight off the parent's `TableRef`. The assumption "parent is a jOOQ Record" is structural; on a `@record`-backed parent (POJO or Java record source) the cast throws at runtime if the field reaches the emitter at all.

The list arm carries a second symptom: it does not register a `DataLoader` at all. Every parent invocation runs its own stage-1 UNION ALL plus per-typename stage-2 SELECTs, with no `parentInput VALUES (idx, parent_pk…)` join and no scatter array, so a list of N parents fans out to ~N stage-1 unions plus ~N participant SELECTs each, with no dedup for repeated parent PKs across siblings. Observed on `graphitron-sakila-example` against `Address.occupants: [AddressOccupant!]!` (union of `Customer | Staff`): a top-level `customers` query selecting `address.occupants` for 5 customers fires 14 child SQL statements (5 stage-1 unions plus 9 per-typename SELECTs). Expected shape under DataLoader batching: 1 stage-1 UNION ALL with `JOIN parentInput` over the distinct parent PKs, plus 1 per-typename SELECT per participant — so 3 child statements regardless of how many parents share each PK. The connection arm already runs the batched DataLoader shape (`buildBatchedConnectionFetcher` at `:671-748`, `buildBatchedConnectionRowsMethod` at `:793-…`); only the list arm is missing it.

The dispatch site is `TypeFetcherGenerator.java:436-461`: both arms pick `MultiTablePolymorphicEmitter.emitMethods` for the non-connection case, which routes to `buildMainFetcher` at `MultiTablePolymorphicEmitter.java:219-309`. The list-arm fix is the same shape as the connection arm minus the windowed-CTE pagination: one DataLoader-registering main fetcher, one `rows<Field>(List<RowN<…>>, env)` rows method, returning `List<List<Record>>` indexed 1:1 with the keys list.

R102 lands the model unification and the list-arm DataLoader fix together: lift `BatchKey.RecordParentBatchKey` plus `GraphitronType.ResultType` onto the two field records, route both arms through `buildRecordParentKeyExtraction`, drop the `parentTable` parameter from `MultiTablePolymorphicEmitter` entirely. The unification is the longevity move; the SQL-count win on the list arm is one of its consequences. Multi-table polymorphic children on `@record`-backed parents become natively supported as a side benefit, replacing today's silent jOOQ-only behaviour.

## Decision: route key extraction through `buildRecordParentKeyExtraction` with a `BatchKey.RecordParentBatchKey` slot

The shape the emitter needs is not the parent's `TableRef`; it is the parent *object* handed to it via `env.getSource()` plus a key-extraction strategy keyed on that object's shape. Routing both arms through `GeneratorUtils.buildRecordParentKeyExtraction` (`GeneratorUtils.java:186-196`) — the canonical key-extraction switch already driven by the single-table batched fetcher — is the principled landing. Its inner `buildFkRowKey` (`:198-226`) already handles all four parent-object shapes and the four-permit `RecordParentBatchKey` sub-seal covers every key-extraction strategy graphitron emits.

### Records and FieldBuilder

`ChildField.InterfaceField` (`ChildField.java:285-297`) and `ChildField.UnionField` (`ChildField.java:304-316`) gain two new components, in identical positions on both records:

- `BatchKey.RecordParentBatchKey parentKey` — the parent-object key-extraction strategy (`RowKeyed` for table-backed parents, the other three permits for `@record`-backed parents).
- `GraphitronType.ResultType parentResultType` — the parent's object shape, threaded through to `buildRecordParentKeyExtraction` so `env.getSource()` is cast and read against the right Java type.

`FieldBuilder.classifyObjectReturnChildField` (the constructors at `FieldBuilder.java:520-522` and `:531-533`) is the only construction site of either record. It supplies `parentKey` and `parentResultType` from data already in scope at classification time:

- For table-backed parents (the existing case), `parentKey` is `new BatchKey.RowKeyed(parentTable.primaryKeyColumns())` — same shape as `FieldBuilder.deriveSplitQueryBatchKey` (`FieldBuilder.java:2854-2858`) — and `parentResultType` is `JooqTableRecordType` (or `JooqRecordType` for the synthetic-table case).
- For `@record`-backed parents, classification reuses the existing single-table batched-fetcher derivation producers: `BatchKeyLifterDirectiveResolver` for `LifterRowKeyed` and `FieldBuilder.deriveBatchKeyFromTypedAccessor` for `AccessorKeyedSingle` / `AccessorKeyedMany`. The parent-side key derivation is independent of the child's polymorphic shape — both producers consult the parent class's structure (FK-equivalent `@batchKeyLifter` static method or typed zero-arg accessor), not the child field's return type — so neither needs adapting for the multi-table polymorphic case. `parentResultType` is `JavaRecordType` or `PojoResultType` from the `@record` directive's resolved class. Multi-table polymorphic children on `@record` parents fall out as a side benefit of the unification, replacing today's runtime `ClassCastException` (the `(Record) env.getSource()` cast at `MultiTablePolymorphicEmitter.java:240` against a POJO source) with a working code path.

The connection vs. non-connection arm distinction stays where it is — at the dispatch site via `returnType().wrapper() instanceof FieldWrapper.Connection` — so no new sub-variant is introduced. The single non-DataLoader case for child fields ("the source object already carries the data we need") is a different shape that does not multiplex through `MultiTablePolymorphicEmitter`, so the lift on these two records is unconditional.

## `BatchKey`: non-empty key-column invariant across all permits

Every `BatchKey` permit's key-column list is morally non-empty — a `BatchKey` without a key column is not a coherent batch-key declaration, and no producer in the codebase today legitimately constructs one. The invariant is currently undefended: `BatchKey.java:124` even has an `if (keyColumns.isEmpty()) return ClassName.get("org.jooq", "Row")` defensive fallback in the `containerType` helper, dead code for a case the type permits but no producer hits. R102 closes this by making non-empty a type-system guarantee, not a producer/consumer convention.

Compact canonical constructors land on every permit whose semantics require a non-empty column tuple:

| Permit | Component carrying the list | Constructor check |
|---|---|---|
| `RowKeyed` | `parentKeyColumns` | direct |
| `RecordKeyed` | `parentKeyColumns` | direct |
| `MappedRowKeyed` | `parentKeyColumns` | direct |
| `MappedRecordKeyed` | `parentKeyColumns` | direct |
| `TableRecordKeyed` | `parentKeyColumns` | direct |
| `MappedTableRecordKeyed` | `parentKeyColumns` | direct |
| `LifterRowKeyed` | `hop.targetSideColumns()` | via `JoinStep.LiftedHop` |
| `AccessorKeyedSingle` | `hop.targetSideColumns()` | via `JoinStep.LiftedHop` |
| `AccessorKeyedMany` | `hop.targetSideColumns()` | via `JoinStep.LiftedHop` |

The three `LiftedHop`-delegated permits get the invariant by lifting it to `JoinStep.LiftedHop`'s own canonical constructor — invariant lives where the source-of-truth list lives, not three places downstream. Implementation walks every existing `LiftedHop` producer to confirm none legitimately produce empty `targetSideColumns()`; the sealed-switch sweep catches any missed site at compile time.

The dead `containerType` empty-list fallback at `BatchKey.java:124` (and its `RecordN` sibling at `:133`) is removed in the same change — the type-system invariant makes the branches unreachable. The audit-tier `BatchKeyTest` extends with one parameterised case per permit confirming construction with an empty list throws `IllegalArgumentException`.

## Validator: reject empty-PK parent on both arms

`GraphitronSchemaValidator.validateChildConnectionParentPk` (`GraphitronSchemaValidator.java:329-360`) is renamed to `validateChildMultiTableParentPk` and generalised to fire on both connection and non-connection arms. The `instanceof FieldWrapper.Connection` short-circuit at the head goes away, and the rejection message reframes from `@asConnection`-specific to the underlying invariant: "non-empty primary key on the parent type, since the DataLoader key tuple is built from the parent's PK columns." Both `validateInterfaceField` (`:551-555`) and `validateUnionField` (`:557-562`) already call into this method, so the new rejection fires uniformly across both arms with no new dispatch.

The `IllegalStateException` at `MultiTablePolymorphicEmitter.java:682-693` for `parentKeyArity == 0` is removed; the type-system invariant from the previous section (non-empty `parentKeyColumns` enforced at `BatchKey.RowKeyed` construction) plus the validator rejection cover the case, per *Validator mirrors classifier invariants* and *Classifier guarantees shape emitter assumptions*. The arity > 22 throw stays — that's a structural jOOQ limit independent of the load-bearing classifier guarantee, and the validator gains a sibling rejection in the same method to mirror it.

Participant-side invariants follow the same pattern. The connection-arm helper at `:824` reads `participants.get(0).table().primaryKeyColumns().get(0)` — single-column participant PK is required. Implementation walks `validateMultiTableParticipants` (`GraphitronSchemaValidator.java:270-311`) to confirm any connection-arm-only gates on participant-PK shape are generalised to fire on both arms alongside the parent-PK rename; the list-arm batched form has the same participant-PK requirements as the connection arm.

For `@record`-backed parents, the canonical-constructor sweep on `BatchKey` carries the non-empty invariant on every permit (`LifterRowKeyed` via `JoinStep.LiftedHop`, `AccessorKeyedSingle` / `AccessorKeyedMany` likewise), so the validator inherits that fact. The validator's new work on the `@record`-parent path is the same author-facing diagnostic the table-backed path got: rejection when classification produces no matching `RecordParentBatchKey` derivation (no `@batchKeyLifter`, no typed accessor matching the field). `BatchKeyLifterDirectiveResolver` and `FieldBuilder.deriveBatchKeyFromTypedAccessor` already produce typed `Rejected` arms today for the single-table batched-fetcher path; classification of multi-table polymorphic children on `@record` parents routes the same `Rejected` arms into `UnclassifiedField` so the validator surfaces them with the actionable diagnostic the existing single-table arm already carries.

## Load-bearing classifier check

New paired annotation, keyed `multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven`. The narrower load-bearing fact is not "PK is non-empty" (the canonical-constructor invariant already carries that) but "the multi-table polymorphic emitter delegates parent-object key extraction to `buildRecordParentKeyExtraction` with no parallel inline path." Mirrors the shape of `column-field-requires-table-backed-parent` (`FieldBuilder.java:3146-3152` ↔ `TypeFetcherGenerator.java:287-292`):

- **Producer** on `FieldBuilder.classifyObjectReturnChildField`. Description: "`ChildField.InterfaceField` and `ChildField.UnionField` are constructed only here, with both `parentKey: BatchKey.RecordParentBatchKey` and `parentResultType: GraphitronType.ResultType` resolved at classification time. Lets the multi-table polymorphic emitter delegate to `GeneratorUtils.buildRecordParentKeyExtraction` with no parallel inline cast-to-Record path."
- **Consumer** on the shared rows-method helper that calls `buildRecordParentKeyExtraction`. `reliesOn`: "Reads `parentKey()` and `parentResultType()` straight off the field record and hands them to `buildRecordParentKeyExtraction`. The hard fail (no jOOQ-Record fallback at this site) is the form the load-bearing guarantee takes here: navigation and drift annunciation, not guard elision. Empty key columns are unreachable per the type-system invariant on `BatchKey.RowKeyed` and its siblings."

`LoadBearingGuaranteeAuditTest.productionAnnotationsAreConsistent` (`LoadBearingGuaranteeAuditTest.java:58`) picks the pair up automatically once both annotations are present.

## Emitter: two entry points, shared helpers, no `parentTable` plumbing

The list and connection arms construct structurally identical SQL modulo pagination. Both:

1. Build a `parentInput VALUES (idx, pk1, pk2, …)` derived table over the DataLoader keys.
2. Stage 1: `SELECT … FROM <participant_i> JOIN parentInput ON <participant_fk> = parentInput.<pk>` UNION ALL'd across participants.
3. Stage 2: per typename, fetch the matching participant rows by participant PK.
4. Scatter typed Records into a `result[outerIdx]` array indexed 1:1 with the keys list.

The connection arm interposes a windowed-CTE (`ROW_NUMBER() OVER (PARTITION BY idx)`) plus seek pagination between stages 1 and 2; the list arm skips it. That single fork is the only architecturally-significant difference between the two arms.

The two entry points stay distinct (`emitMethods` and `emitConnectionMethods`) — fork on identity, not on a runtime `instanceof` switch — but the shared moves all live in helpers parameterised on the parts that vary:

- **`parentInput VALUES` emitter** (new, in `MultiTablePolymorphicEmitter`). Takes `(BatchKey.RecordParentBatchKey parentKey, List<RowN<…>> keys)` and emits the `VALUES` table builder; identical on both arms.
- **DataLoader key extraction** (delegate to existing `GeneratorUtils.buildRecordParentKeyExtraction`). Takes `(BatchKey.RecordParentBatchKey, GraphitronType.ResultType, …)` and emits the per-parent key tuple construction. Replaces the inline `(($T) env.getSource()).get(...)` casts at `MultiTablePolymorphicEmitter.java:735-736` (connection arm) and the inline `Record parentRecord = (Record) env.getSource()` at `:240` (list arm). One delegation site per arm; both arms gain the four-shape parent-object coverage for free.
- **Per-typename dispatcher** (new, in `MultiTablePolymorphicEmitter`). The stage-2 dispatch over `participantJoinPaths` (typename → record-class scatter) is identity-shaped between list and connection; today it lives inline twice and gets extracted to a private static helper.

`parentTable` drops out of every helper signature in `MultiTablePolymorphicEmitter`. Its sole uses today (`buildBatchedConnectionFetcher` at `:680-697` and `:735-736`; `buildBatchedConnectionRowsMethod` at `:813-820`) are all parent-key-tuple construction — the very thing `buildRecordParentKeyExtraction` already abstracts. `buildBatchedConnectionFetcher`, `buildBatchedConnectionRowsMethod`, and the new `buildBatchedListFetcher` / `buildBatchedListRowsMethod` (replacing `buildMainFetcher` at `:219-309`) all take only `(ChildField field, ctx, outputPackage, …)` — they read `field.parentKey()` and `field.parentResultType()` directly. The non-list multi-table polymorphic single-field arm — a structurally-different shape with nothing to batch — moves into a separate `buildScalarPerParentFetcher` helper that does not consume the load-bearing classifier check.

The dispatch site at `TypeFetcherGenerator.java:436-461` collapses too: the `parentTable` argument disappears from every `MultiTablePolymorphicEmitter` call. The dispatch-site `parentTable` local at `:126` stays — it's still derived once from `GraphitronType.TableBackedType` for *other* field types (e.g. `ColumnField`) — but stops being passed into `MultiTablePolymorphicEmitter`'s entry points.

## Tests

- **L1 (`BatchKeyTest`).** Parameterised case per permit asserting that construction with an empty key-column list throws `IllegalArgumentException`. Six direct (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`, `MappedRecordKeyed`, `TableRecordKeyed`, `MappedTableRecordKeyed`) plus three via `JoinStep.LiftedHop` (`LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany`). One additional case asserting `JoinStep.LiftedHop` itself rejects empty `targetSideColumns()`.
- **L4 (pipeline).** New cases in `TypeFetcherGeneratorTest` alongside the existing `childInterfaceField` / `childUnionField` fixtures (`TypeFetcherGeneratorTest.java:1941-2016`):
  - `childInterfaceField_listForm_emitsOneDataLoaderRegistrationAndOneRowsMethod`: assert exactly one `DataLoaderRegistry.put`-style helper plus one `rows<Field>` method per non-connection union/interface list field; no per-parent `env.getSource()` reads in the main fetcher body.
  - `childInterfaceField_listForm_keyTupleArityMatchesParentPk`: pin the DataLoader key element type to `RowN<…>` of the right arity for both single-PK (`address`) and composite-PK (`film_actor`) parent fixtures.
  - `childUnionField_listForm_emitsSameDataLoaderShapeAsInterfaceField`: equivalence pin between the two arms (mirrors the existing `childUnionField_emitsSameTwoStageStructureAsInterfaceField`).
  - `childInterfaceField_routesParentKeyExtractionThroughBuildRecordParentKeyExtraction`: structural assertion that the emitted body delegates to the helper, not an inline cast-to-Record. The same structural assertion runs against the connection arm to confirm the delegation lands there too.
  - **`@record`-parent coverage.** Fixtures for each of the four `BatchKey.RecordParentBatchKey` permits as the multi-table polymorphic parent's key strategy: `RowKeyed` (table-backed parent), `LifterRowKeyed` (`@record` parent + `@batchKeyLifter`), `AccessorKeyedSingle` / `AccessorKeyedMany` (`@record` parent + typed accessor). Each pins the resulting key extraction to the matching `buildRecordParentKeyExtraction` arm. Coverage runs symmetrically across `InterfaceField` and `UnionField`.
- **L3 (validator).** New parameterised cases in `InterfaceFieldValidationTest` and `UnionFieldValidationTest`: assert the new rejection ("non-empty primary key required on parent type for multi-table interface/union child") fires for both list and connection arms when the parent type has empty PK, and does not fire when the parent has a PK of any arity. Sibling case for the >22 PK arity rejection.
- **L6 (execution).** New `AddressOccupantsListBatchingTest` in `graphitron-sakila-example`, modelled on `AccessorDerivedBatchKeyTest.java:44-113`. Registers an `org.jooq.ExecuteListener` via `DefaultExecuteListenerProvider`, runs a top-level `customers(first: 5)` query selecting `address.occupants { ... on Customer { … } ... on Staff { … } }`, asserts `QUERY_COUNT == 4` (one customers query plus one stage-1 UNION ALL plus two per-typename SELECTs — three child statements), down from today's 14 (5 stage-1 + 9 stage-2). Exact-count is the right grain because each of the four statements maps to a documented architectural commitment (one DataLoader registration, one stage-1 UNION ALL, one stage-2 SELECT per participant typename); upper-bound would let a regression to a 5- or 6-statement intermediate slip through. **Forward-pointer:** if a future change splits the batched UNION ALL across multiple SQL roundtrips (e.g. per-participant `loadMany` dispatch), the count rises and the test re-pins to the new architectural commitment; the exact-count grain stays correct because each new statement still maps to a specific design choice.
- **Audit.** `LoadBearingGuaranteeAuditTest` is satisfied by the new annotation pair without test changes.

## Acceptance criteria

- `ChildField.InterfaceField` and `ChildField.UnionField` carry two new components: `BatchKey.RecordParentBatchKey parentKey` and `GraphitronType.ResultType parentResultType`. `FieldBuilder.classifyObjectReturnChildField` constructs both records with both components resolved at classification time, choosing the right `RecordParentBatchKey` permit per parent kind.
- Every `BatchKey` permit with a key-column component enforces non-emptiness via a compact canonical constructor: six direct (`RowKeyed`, `RecordKeyed`, `MappedRowKeyed`, `MappedRecordKeyed`, `TableRecordKeyed`, `MappedTableRecordKeyed`), three via `JoinStep.LiftedHop`'s constructor (`LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany`). The dead `containerType` empty-list fallbacks at `BatchKey.java:124` and `:133` are removed.
- `GraphitronSchemaValidator.validateChildConnectionParentPk` is renamed to `validateChildMultiTableParentPk` and fires for both connection and non-connection arms when the parent type has empty PK or PK arity > 22; rejection message names "non-empty primary key" without `@asConnection` framing.
- `MultiTablePolymorphicEmitter` no longer takes `parentTable` on any helper or entry-point signature. `buildMainFetcher` (`:219-309`) is replaced by `buildBatchedListFetcher` + `buildBatchedListRowsMethod`. The new helpers and the existing `buildBatchedConnectionFetcher` / `buildBatchedConnectionRowsMethod` all delegate parent-object key extraction to `GeneratorUtils.buildRecordParentKeyExtraction`, reading `parentKey()` and `parentResultType()` off the field record.
- Inline `(($T) env.getSource()).get($T.$L.$L)` (`MultiTablePolymorphicEmitter.java:735-736`) and `Record parentRecord = (Record) env.getSource()` (`:240`) are removed; both arms route through the helper.
- The empty-PK `IllegalStateException` at `MultiTablePolymorphicEmitter.java:682-693` is removed; the canonical-constructor invariant on `BatchKey.RowKeyed` plus the validator rejection cover the case. The arity > 22 throw is mirrored as a validator rejection in `validateChildMultiTableParentPk`.
- `emitMethods` and `emitConnectionMethods` stay as distinct entry points. Three private static helpers host the moves shared between list and connection arms: a parent-input `VALUES` table emitter, a per-typename stage-2 dispatcher, and the delegation site to `GeneratorUtils.buildRecordParentKeyExtraction`.
- The new load-bearing-classifier-check pair (`multitable-polymorphic-child.parent-key-extraction-is-batchkey-driven`) annotates `FieldBuilder.classifyObjectReturnChildField` (producer) and the multi-table emitter delegation site (consumer); `LoadBearingGuaranteeAuditTest` passes with no manual additions.
- `TypeFetcherGeneratorTest` covers the list-arm DataLoader registration, key-tuple arity, Interface/Union equivalence, the `buildRecordParentKeyExtraction` delegation pin, and all four `BatchKey.RecordParentBatchKey` permits as the parent-key strategy on both single-PK and composite-PK parent tables.
- `InterfaceFieldValidationTest` / `UnionFieldValidationTest` cover the new validator rejection on both arms (empty PK, > 22 PK arity).
- `BatchKeyTest` covers the non-empty invariant on every permit and on `JoinStep.LiftedHop`.
- `graphitron-sakila-example`'s `AddressOccupantsListBatchingTest` pins exactly four executed statements for the canonical 5-customer fanout, replacing today's 14, with the per-statement architectural-commitment mapping documented inline.

## Roadmap entries (siblings / dependencies)

- **Sibling of** [R74 / `accessor-row-record-shapes.md`](accessor-row-record-shapes.md) and the R61/R70/R71 line: shape-as-variant-identity on `BatchKey`. R102 consumes the existing `BatchKey.RecordParentBatchKey` sub-seal rather than adding a new permit — the encoding rule has already paid the cost we benefit from. R102 also adds the non-emptiness invariant across every `BatchKey` permit's key-column list, closing a long-standing gap in the type-system guarantees of the sub-seal.
- **Mirrors validator-rejection framing from** the R88 `@record`-accessor change (see `changelog.md` entry "record-accessor-validation, R88"): runtime `IllegalStateException` removed in favour of a validator rejection plus a load-bearing-classifier-check pair. Same pattern, different field-classification arm.
- **Unifies a long-standing model gap.** The multi-table polymorphic emitter is the one batched-fetcher path that bypasses `GeneratorUtils.buildRecordParentKeyExtraction` and casts to `org.jooq.Record` inline. R102 closes this; multi-table polymorphic children gain native support on `@record`-backed parents (`LifterRowKeyed`, `AccessorKeyedSingle`, `AccessorKeyedMany` derivation arms) as a side benefit, replacing today's silent jOOQ-only behaviour.
- **Unblocks** any later refactor that needs uniform shape across both child-field arms: once `parentKey` and `parentResultType` are present on both records and `parentTable` is gone from `MultiTablePolymorphicEmitter`'s helper signatures, the connection arm and the list arm read from the same typed slots and delegate to the same key-extraction helper rather than rederiving from `parentTable` independently.
