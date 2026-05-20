---
id: R189
title: FK-target `@nodeId` input fields on `@mutation` (INSERT / UPDATE / DELETE)
status: Ready
bucket: feature
theme: nodeid
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# FK-target `@nodeId` input fields on `@mutation` (INSERT / UPDATE / DELETE)

## Problem

`MutationInputResolver.resolveInput` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:463-479`) rejects `InputField.ColumnReferenceField` / `InputField.CompositeColumnReferenceField` on every `@mutation` verb with a deferred-rejection pointing at R24. R24's actual scope is the *output-side* JOIN-with-projection emitter (rooted-at-parent NodeId encoding through a JOIN; see `nodeidreferencefield-join-projection-form.md`); the *input-side* DML cases do not touch that path. The current rejection prevents authoring otherwise-canonical mutations like:

```graphql
input OpprettCampusInput @table(name: "CAMPUS") {
  larestedId: ID! @nodeId(typeName: "Larested")
  ...
}
type Mutation { opprettCampus(input: [OpprettCampusInput!]!): OpprettCampusPayload @mutation(typeName: INSERT) }
```

even though the legacy graphitron generator handles the shape across INSERT / UPDATE / DELETE today. The same shape works at the input-field level on `Query`-side `@table` arguments and on the same-table NodeId arm of `@mutation` inputs; only the FK-target arm is gated.

## Why the carrier is verb-uniform

The classifier (`BuildContext.classifyInputField` → `NodeIdLeafResolver.Resolved.FkTarget.DirectFk`, `BuildContext.java:1832-1845`) already produces carriers with everything every DML emitter needs. `NodeIdLeafResolver.Resolved.FkTarget` is sealed `DirectFk | TranslatedFk`, but the `TranslatedFk` arm at `BuildContext.java:1846-1849` produces an `InputFieldResolution.Unresolved` outright; only `DirectFk` ever reaches `ColumnReferenceField` / `CompositeColumnReferenceField` at the input-field level. The `id-reference` synthesis shim at `BuildContext.buildInputNodeIdReference` (`BuildContext.java:1867-1897`) is the only other producer of these two carriers, and it constructs the same `liftedSourceColumns` + `NodeIdDecodeKeys` shape that `DirectFk` does. The widening below is therefore scoped to the `DirectFk`-shaped carrier; `TranslatedFk` admission is out of scope and stays a separate future item if it ever surfaces a use case.

- `liftedSourceColumns` — the FK columns on the input's *own* table (`CAMPUS.LARESTED_ID` in the example) that receive the decoded values or carry the WHERE-side filter. No JOIN to the parent table is involved.
- `extraction` — narrowed to `CallSiteExtraction.NodeIdDecodeKeys` at the type level, identical to the same-table NodeId carrier the existing DML emitters handle.

Every DML verb operates on the same shape with verb-specific SQL surrounding it:

- **INSERT** writes the decoded tuple into `liftedSourceColumns`.
- **UPDATE SET** writes the decoded tuple into `liftedSourceColumns` in SET position.
- **UPDATE WHERE / DELETE WHERE** compares `liftedSourceColumns` against the decoded tuple.

The architectural questions that look open ("does an FK whose `liftedSourceColumns` cover the PK count as PK coverage?", "does `@value` on a reference field count toward the all-`@value`-marked rejection?", "what's the SET-clause shape?") have consistent default answers once stated: yes, yes, and the same write as INSERT. They're not forks, they're the consistent extension of the existing per-verb rules across the new carrier.

## Scope

INSERT, UPDATE, DELETE. UPSERT stays out of scope because `MutationInputResolver.java:325-331` refuses every UPSERT outright today under R144's cardinality-safety regime; no reference-carrier code on the UPSERT path runs and R145 owns that arm.

The single roadmap item buys verb-uniform admission across the surface that *does* run today, which matches the principle the architect cited in the rewrite design doc (`rewrite-design-principles.adoc:107-122`): when a classifier relaxation affects N consumer sites, the audit happens in one commit across all N, not stretched across separate items that each see partial state.

## Sketch of the fix

### Classifier admission

1. `MutationInputResolver.resolveInput` (the per-field loop around line 436) — drop the deferred rejection for `ColumnReferenceField` / `CompositeColumnReferenceField`, admit them on INSERT, UPDATE, and DELETE. The UPSERT refusal at line 325-331 already catches that arm upstream.
2. Re-point or remove the R24 reference in the rejection text; R24 is the output-side JOIN-with-projection item and is unrelated to input-side carriers. Doing this without leaving a dangling reference is part of the implementation work.

### Permits widening

3. `InputField.java:32, 40` — widen the `LookupKeyField` and `SetField` sealed permits to include `ColumnReferenceField` and `CompositeColumnReferenceField`. Restate the permit-Javadoc to reflect that the admissible-carrier set is now "value-bearing scalar carrier *or* FK-target reference carrier whose `liftedSourceColumns` are on the input's own table" — i.e., still "no JOIN context at the emit site," just sourced from `liftedSourceColumns()` instead of `column()`.

### Validator-side binding walker

4. `EnumMappingResolver.buildLookupBindings` (`EnumMappingResolver.java:299-361`) — add `case ColumnReferenceField` / `case CompositeColumnReferenceField` arms. The arity-1 reference reuses the exact `MapGroup` shape the arity-1 `ColumnField` NodeId path already builds at `EnumMappingResolver.java:337-338` via the non-`Direct` extraction branch (`:334-336`); the only delta is `target column = liftedSourceColumns().get(0)` instead of `cf.column()`. The composite reference reuses the `DecodedRecordGroup` shape the `CompositeColumnField` arm builds at `:347-352`; the only delta is iterating `liftedSourceColumns()` instead of `ccf.columns()` to assign `RecordBinding` target columns. No new `InputColumnBindingGroup` permit is introduced; this is the load-bearing fix that prevents the PK-coverage check at `MutationInputResolver.java:496-512` from silently under-counting reference contributions.

### Emitter dispatch (INSERT)

5. `TypeFetcherGenerator` INSERT-path helpers — widen the switch on carrier shape to read column refs from `liftedSourceColumns()` and the decoder from `extraction()`:
   - `anyNodeIdCarrier` (`:1806`).
   - `buildInsertColumnList` (`:1897`) — one column ref per `liftedSourceColumns` entry.
   - `buildPerCellValueList` (`:1840`) — arity-1 reads `__insertKey_<fi>.value1()`; composite reads `value<i+1>()` per slot.
   - `buildInsertDecodeLocals` (`:1935`) — extract `NodeIdDecodeKeys` from the two reference variants.
   - Both `buildRecordInsertChain` (`:3704`) and `buildBulkRecordPerRowInsertBody` (`:4013`) route through these four helpers.

### Emitter dispatch (UPDATE / UPSERT SET)

6. Every `(InputField.ColumnField) sf` cast over an iterator of `tia.setFields()` entries — at the time of writing eight sites in `TypeFetcherGenerator` (lines 2017, 2102, 2127, 2145, 2300, 3758, 3797, 4044), but the invariant is the cast-shape, not the line list. With `SetField` widened, every such cast is a runtime CCE if left as-is. Replace each with a switch over the carrier variants, emitting the SET write against `column()` (today's path) or against each `liftedSourceColumns` entry with the matching `value<i+1>()` decoded slot. UPSERT-SET sites are reached from the existing `buildRecordUpsertChain` path; UPSERT itself is still refused, but the helpers it shares with UPDATE are on this list.

### Emitter dispatch (UPDATE / DELETE WHERE)

7. `buildLookupWhereSingleRow` and its bulk siblings — these consume `tia.fieldBindings()`, so the validator widening in step 4 lets them produce correct WHERE predicates for the new carriers without further per-arm work. Audit each call site to confirm; this is consumption-of-classifier-output, not parallel logic.

### Audit annotations

8. Update three named `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` annotations whose text encodes the "only `ColumnField` / `CompositeColumnField`" assumption. One of the three is a *semantic* change to the classifier guarantee itself; the other two are *wording-only* refreshes that re-state the same invariant over a widened carrier set.

   *Semantic change* (the load-bearing one called out in Risks):
   - `mutation-input.where-columns-cover-pk` (`MutationInputResolver.java:312-317`) — the set of columns counted toward PK coverage genuinely grows. Re-state that contributed filter columns now include `ColumnReferenceField.liftedSourceColumns()` and `CompositeColumnReferenceField.liftedSourceColumns()`. This is the check the validator-side under-counting failure mode rides on; step 9's negative-rejection fixture is what exercises it.

   *Wording-only refreshes* (no behavioural delta; the invariants already hold over the widened set):
   - `mutation-input.update-set-fields-equal-value-marked` (`MutationInputResolver.java:318-323`) — re-state that `setFields()` is the `@value`-marked admissible-carrier set, where "admissible carrier" now includes the reference variants.
   - `mutation-input.lookup-binding-decoded-record-arity-matches-carrier-columns` (`EnumMappingResolver.java:292-298`) — re-state the arity guarantee for the new arms (arity-1 reference → one-slot `MapGroup`; composite reference → N-slot `DecodedRecordGroup` with `liftedSourceColumns().size() == N`).

### Test surface

9. Pipeline-tier fixtures exercising the user's `OpprettCampusInput` shape on each of the three admitted verbs (one INSERT, one UPDATE, one DELETE). Verify both the arity-1 and composite-key arms; the rooted-at-parent fixture R50 added in `nodeidfixture` (`parent_node` + `child_ref`) is the natural execution-tier driver for the composite arm.

   *Negative-rejection fixture for the under-counting failure mode.* In addition to the acceptance fixtures, a pipeline-tier fixture whose FK input field's `liftedSourceColumns()` *exactly cover* the input table's primary key. This fixture asserts that the resolver produces *no* `mutation-input.where-columns-cover-pk` rejection. Without it, step 4's load-bearing widening is silently regressable: the validator can drop its reference-carrier arms, generated code still compiles (the `case null, default ->` no-op makes the binding list shorter but well-formed), the compilation tier passes, and the execution tier never runs because the schema is rejected upstream. Only this specific assertion catches that regression. The fixture sits next to the INSERT acceptance fixture and shares its schema.

## Risks

The strongest argument for doing this work as one item is the asymmetric failure mode of leaving the validator-side widening (step 4) out of any partial implementation. The other carrier-shape failures — emitter cast-site widening (step 6), permit-Javadoc lag, audit-annotation lag — surface either at compile time of the generated code (caught by the compilation tier) or at pipeline-tier test runtime. The `case null, default ->` no-op in `buildLookupBindings` is different: it produces *compiling* generated code that just under-counts reference contributions in `fieldBindings()`, which then fires a false "missing PK column" rejection on schemas where the FK column actually covers the PK via `liftedSourceColumns`. That failure mode slips past both the compilation tier and the execution tier; only a pipeline-tier assertion on the rejection text catches it. Steps 4 and 8 together are what closes that gap.

R24 stays scoped to the output-side JOIN-with-projection emitter; this item's implementation removes the misleading R24 hand-off in `MutationInputResolver.java:466-470` as part of the rejection-text refresh in step 2.
