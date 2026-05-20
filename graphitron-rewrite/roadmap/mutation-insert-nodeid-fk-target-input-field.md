---
id: R189
title: "FK-target `@nodeId` input fields on `@mutation(typeName: INSERT)`"
status: Backlog
bucket: feature
theme: nodeid
depends-on: []
created: 2026-05-20
last-updated: 2026-05-20
---

# FK-target `@nodeId` input fields on `@mutation(typeName: INSERT)`

## Problem

`MutationInputResolver.resolveInput` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:463-479`) rejects `InputField.ColumnReferenceField` / `InputField.CompositeColumnReferenceField` on every `@mutation` verb with a deferred-rejection pointing at R24. R24's actual scope is the *output-side* JOIN-with-projection emitter (rooted-at-parent NodeId encoding through a JOIN; see `nodeidreferencefield-join-projection-form.md`); the *input-side* INSERT case does not touch that path. The current rejection prevents authoring otherwise-canonical INSERT mutations like:

```graphql
input OpprettCampusInput @table(name: "CAMPUS") {
  larestedId: ID! @nodeId(typeName: "Larested")
  ...
}
type Mutation { opprettCampus(input: [OpprettCampusInput!]!): OpprettCampusPayload @mutation(typeName: INSERT) }
```

even though the legacy graphitron generator handles them today. The same shape works at the input field level on `Query`-side `@table` arguments and on the same-table NodeId arm of `@mutation` inputs; only the FK-target arm is gated.

## Why INSERT is structurally trivial

The classifier (`BuildContext.classifyInputField` → `NodeIdLeafResolver.Resolved.FkTarget.DirectFk`, `BuildContext.java:1832-1845`) already produces carriers with everything the INSERT emitter needs:

- `liftedSourceColumns` — the FK columns on the input's *own* table (`CAMPUS.LARESTED_ID` in the example) that receive the decoded values. No JOIN to the parent table is involved.
- `extraction` — narrowed to `CallSiteExtraction.NodeIdDecodeKeys` at the type level, identical to the same-table NodeId carrier the INSERT emitter handles today.

INSERT writes decoded values straight into `liftedSourceColumns`. The only delta from the existing `ColumnField` (NodeIdDecodeKeys) / `CompositeColumnField` handling is *which* column-ref slot is read off the carrier.

## Scope

INSERT only. `UPDATE` / `DELETE` `@value` and `@lookupKey` interactions with FK-target carriers stay deferred; they're a separate item because:

- The UPDATE / UPSERT SET emitters cast `tia.setFields()` entries unconditionally to `InputField.ColumnField` (`TypeFetcherGenerator.java:3758`, `:3797`, `:4044`, plus the direct-return UPDATE around `:1997`); opening `InputField.SetField` permits without widening every cast is a `ClassCastException` waiting to fire.
- The UPDATE / DELETE WHERE bindings come from `EnumMappingResolver.buildLookupBindings` (`EnumMappingResolver.java:312-358`), whose switch is `case ColumnField` / `case CompositeColumnField` / `case null, default ->` no-op for references; reference carriers would silently drop from `fieldBindings()`, under-counting in the PK-coverage check at `MutationInputResolver.java:496-512`.
- Opening `InputField.LookupKeyField` / `InputField.SetField` permits is a model-shape decision, not bookkeeping; the sealed permits currently encode "value-bearing scalar carrier, no JOIN context" and widening them touches every site that pattern-matches on the permit plus the audit-annotated invariants. Decisions about `@value` on a reference carrier (does it count as PK coverage? what's the SET-clause shape?) want their own Spec.
- `UPSERT` is already refused outright at `MutationInputResolver.java:325-331` under R144's cardinality-safety regime (R145 territory); no reference-carrier code on the UPSERT path runs today, so there is nothing to widen there.

## Sketch of the fix

1. `MutationInputResolver.resolveInput` — in the per-field loop around line 436, admit `ColumnReferenceField` / `CompositeColumnReferenceField` when `kind == DmlKind.INSERT`. Keep the deferred rejection for `UPDATE` / `DELETE`, with a tighter message pointing at the follow-up Backlog item for the SET / lookup-WHERE arms rather than at R24.
2. `TypeFetcherGenerator` INSERT-path helpers — widen the carrier dispatch to read column refs from `liftedSourceColumns()` and the decoder from `extraction()`:
   - `anyNodeIdCarrier` (`:1806`) — recognize the two reference variants (both carry `NodeIdDecodeKeys` by type-narrowing).
   - `buildInsertColumnList` (`:1897`) — for reference variants, emit one column ref per entry in `liftedSourceColumns`.
   - `buildPerCellValueList` (`:1840`) — for arity-1 reference, emit `__insertKey_<fi>.value1()` against `liftedSourceColumns.get(0)`; for composite, loop over `liftedSourceColumns` and read `value<i+1>()`.
   - `buildInsertDecodeLocals` (`:1935`) — extract `NodeIdDecodeKeys` from the two reference variants.
3. Both `buildRecordInsertChain` (`:3704`) and `buildBulkRecordPerRowInsertBody` (`:4013`) route through those four helpers, so widening the helpers covers single-row and bulk in one shot.
4. Add a pipeline-tier fixture exercising an INSERT with a `@table` input carrying a `@nodeId(typeName: T)` field that points at a separate parent table (the user's `OpprettCampusInput` shape).
5. Update the per-site `@DependsOnClassifierCheck` annotations on the INSERT helpers and `@LoadBearingClassifierCheck` notes on `MutationInputResolver.resolveInput` to widen the admissible-carrier wording (mentioned today as "only `ColumnField` / `CompositeColumnField`").

## Follow-ups

- A separate Backlog item for `UPDATE` / `DELETE` FK-target `@nodeId` (SET and WHERE arms). Captures the cast-site widening, the `buildLookupBindings` widening, and the model-shape decision on `LookupKeyField` / `SetField` permits.
- R24 stays scoped to the output-side JOIN-with-projection emitter; the rejection's hand-off comment in `MutationInputResolver.java` is misleading and should be repointed (or removed) by this item's implementation.
