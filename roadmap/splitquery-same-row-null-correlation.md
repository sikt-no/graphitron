---
id: R1043
title: "A @splitQuery to the same table with no @reference is rejected with an author error, never reaching the emitter with a null correlation"
status: In Review
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# A @splitQuery to the same table with no @reference is rejected with an author error, never reaching the emitter with a null correlation

## Goal

A `@splitQuery` field whose return type is bound to the same table as its parent, with no `@reference`, fails classification with an author error that names the field, both types and the table, and says what to write instead. It never passes validation and then crashes the generator. Today `graphitron:generate` reports no author errors and then fails with `NullPointerException: correlation` thrown from `LaunchSource.CorrelatedChain`'s compact constructor, reached through `LauncherCommands.batchedChildRow` and `EmitPlan.produce`; the message names neither the field nor the type, so the reporter had to bisect the schema to find it. The invariant this item leaves behind: no batched child field reaches the emitter without a parent correlation (the predicate that ties each batched child row to the parent row it belongs to).

```graphql
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant {
  brukernavn: String! @field(name: "FEIDE_BRUKER") @shareable
  personProfil: PersonProfil @splitQuery   # PersonProfil is @table(name: "PERSON"): rejected, with a remedy
}
```

After this item the build stops at this field with:

> Field 'FeideBruker.personProfil': @splitQuery fetches a child in a batch keyed on each parent's correlation to it, but 'PersonProfil' is bound to the same table as 'FeideBruker' ('PERSON') and the field has no @reference, so there is no parent key to batch on. To return the parent's own row, state the correlation with @reference(path: [{condition: ...}]); or, if no other field needs 'PersonProfil' as a table type, drop @table from 'PersonProfil' and @splitQuery from this field so the type nests on the parent row. To read rows of 'PERSON' independent of the parent, narrowed by this field's @condition or @lookupKey arguments, drop @splitQuery.

The wording is the implementer's to tune; the facts it carries (the coordinate, the directive actually applied, both types, the table, and the three remedies with their preconditions) are the requirement.

## Evidence

Reported from the sis port on 2026-10-15 against trunk `e4cfef7c1`:

```graphql
type FeideBruker @table(name: "PERSON") @key(fields: "brukernavn") @defaultTenant {
  brukernavn: String! @field(name: "FEIDE_BRUKER") @shareable
}
extend type FeideBruker {
  personProfil: PersonProfil @splitQuery   # PersonProfil is @table(name: "PERSON"), no @reference
}
```

```
Caused by: java.lang.NullPointerException: correlation
    at java.util.Objects.requireNonNull
    at no.sikt.graphitron.command.LaunchSource$CorrelatedChain.<init> (LaunchSource.java:104)
    at no.sikt.graphitron.plan.LauncherCommands.batchedRow (LauncherCommands.java:854)
    at no.sikt.graphitron.plan.LauncherCommands.batchedRow (LauncherCommands.java:842)
    at no.sikt.graphitron.plan.LauncherCommands.batchedChildRow (LauncherCommands.java:273)
    at no.sikt.graphitron.plan.LauncherCommands.produce (LauncherCommands.java:213)
    at no.sikt.graphitron.plan.EmitPlan.produce (EmitPlan.java:167)
```

Isolated by the reporter: dropping `@splitQuery` from this one field, or removing the field, makes the build succeed. sis no longer depends on it (the `@splitQuery` was a 9.3.2 workaround, removed in sis `58e862f94f`), so priority is driven by other schemas hitting the same crash.

The reporter's reading (empty join path, no correlation arm landed, the carrier invariant admitting null) is confirmed below.

## What the tree does today

Traced on trunk `08b6b99f`:

- `BuildContext.parsePath` runs foreign-key inference only when the start and target tables differ, so a same-table field with no `@reference` gets an empty join path. `BuildContext.buildParentCorrelation` maps an empty path to `ParentCorrelationResolution.Resolved(null)`.
- `FieldBuilder`'s `TableBoundReturnType` child arm (the one that calls `ctx.buildParentCorrelation(referencePath.elements(), parentTableType.table())`) then mints a table-sourced `ChildField.BatchedTableField` with that null correlation whenever `@splitQuery` is present, for the plain read and the `@lookupKey` read alike. `deriveSplitQuerySource` accepts the null and falls back to the parent's primary key for the batch key, which its javadoc describes as "the empty-joinPath standalone shape".
- `ParentCorrelation.checkCarrierInvariant` admits a null correlation on an empty path (it exists for the inline `TableField` standalone shape), so `BatchedTableField`'s compact constructor accepts the record.
- The null then reaches `LauncherCommands.batchedRow` (or `batchedLookupRow` for the `@lookupKey` variant), whose `LaunchSource.CorrelatedChain` / `CorrelatedLookupChain` compact constructors `requireNonNull(correlation)`. That is the reported stack. `BatchedRowsFragments.prelude` carries a third, unreachable guard for the same state ("standalone references render inline, never as batched rows methods").

No other batched mint carries the hole: `PivotSpec` pins a `@pivot` path to one FK hop, the `TableInterfaceType` arm rejects an empty path in `validateSingleHopFkJoin`, a routine chain always has at least one step, and the record-sourced arms land `OnLiftedSlots` or an FK correlation. The `@defaultTenant` / `@key` parent does not change the route: classification takes the same `TableBoundReturnType` arm, and tenancy only feeds `tenancyOf` at launch. The tests below pin both parents anyway, since the reporter asked.

What the inline twin means: without `@splitQuery` the same field classifies as `ChildField.TableField` with an empty path and renders through `ProjectionUnitRenderer`'s standalone arm, a multiset over the field's own table with `WHERE DSL.noCondition()`. That is an uncorrelated read of the whole table, narrowed only by the field's `@condition` / `@lookupKey` arguments (`NodeIdReferenceFilterPipelineTest.inlineReferenceField_conditionOnlyFilter_emptyJoinPath_standaloneSubquery` is the one fixture, and it pins only that rendering does not throw). It is not "the parent's own row".

## Decision: reject

`@splitQuery` names a delivery anchor: a batched re-query keyed on each parent's correlation to the child, so one statement serves a batch of parents and its rows scatter back to the parent each belongs to. Delivery and correlation are independent axes; the directive picks the first and reads the second, it does not supply it. A same-table field with no `@reference` has no correlation, so the anchor the directive names has nothing to key on. The two ways to make it build anyway both fuse the axes. Keying on the parent's primary key (the same-row reading) would make `@splitQuery` change which rows the field returns, since its inline twin is an uncorrelated read. Broadcasting one uncorrelated read to every parent would match the inline twin's rows but is a different anchor from the keyed batch the directive names. The classifier already rejects the routine form of this case on the same ground (`classifyChildRoutineChain`: "@splitQuery on an uncorrelated routine-backed field has nothing to key the batch on", documented in `routine.adoc` § Fetch forms and @splitQuery; its own comment notes that broadcast-to-all "would be a different anchor than @splitQuery names"), and the same-table polymorphic child is rejected in `resolveChildPolymorphicJoinPaths` rule 1b. This item applies the rule to the table child.

The parent's own row, which is what Graphitron 9 produced here and what the reporter meant, is expressible today:

- `@reference(path: [{condition: {className: ..., method: ...}}])` with a condition method equating the two aliases' keys, which `buildParentCorrelation` lands on `ParentCorrelation.OnParentJoin` (the arm that anchors the parent table and keys the batch on its primary key); it composes with `@splitQuery` as any condition-join path does, and works when the return type must stay `@table` because other fields use it;
- or a return type with no `@table`, which nests on the parent row (`ChildField.NestingField`), one statement, no batch. `@splitQuery` must go too: on a nesting field it is rejected as `[deferred]` (`splitQuery.adoc` § Constraints).

The third remedy, dropping `@splitQuery`, keeps the inline uncorrelated read. It is only the right answer when the field's arguments narrow that read, which is why the message scopes it; the unnarrowed inline shape answers a single-cardinality field with an arbitrary row, which R1045 owns.

## Implementation

- `FieldBuilder`, `TableBoundReturnType` child arm. Today `deriveSplitQuerySource(tbtParentCorrelation, ...)` runs for every field before the `hasSplitQuery` fork, so it sees the inline twin's null correlation too. Move it inside the `if (hasSplitQuery)` branch, and precede it there with the guard: when the correlation is null, return `UnclassifiedField` with `Rejection.structural(<message from the Goal>)`. Place the guard ahead of the `@lookupKey` gate's split-specific checks so the plain and lookup reads share one rejection. The `@lookupKey` resolution itself (`resolveAtChild(returnType, hasSplitQuery)`) reads no correlation and can stay where it is.
- The directive named in the message comes from the coordinate's delivery row, not a constant. `hasSplitQuery` is `forcesSplitDelivery`, which is `DeliveryFacts.Row.forcesSplitDelivery()`, the union of `@splitQuery` and `@tenantFanOut`, so a same-table field carrying only `@tenantFanOut` reaches the same mint. Read `ctx.facts.delivery().rowFor(fieldDef)` and name whichever markers are present. If the tenant fold rejects `@tenantFanOut` on this shape before the child arm runs, the implementer says so in the item and the `@tenantFanOut` test row asserts that earlier rejection instead.
- Rejection kind: `Rejection.structural`, which routes the diagnostic as "edit the schema". `InvalidSchema.DirectiveConflict` is for directives that co-occur in a rejected combination, and its typed directive list is the hook for a "remove this directive" fix-it. Here one directive meets a missing `@reference`, two of the three remedies are edits, and the one-click removal would land a single-cardinality field on the arbitrary-row shape.
- `FieldBuilder.deriveSplitQuerySource`: drop the null fallback to `parentTable.primaryKeyColumns()` and with it the `parentTable` parameter, which nothing else reads. Require the correlation (`Objects.requireNonNull`) and read `correlation.parentKeyColumns()`. Remove the "empty-joinPath standalone shape ... falls back to the parent PK" sentences from the javadoc and the inline comment. After the move above, every caller passes a non-null correlation: the routine-chain arm (a chain always has a step), the `TableInterfaceType` arm (`validateSingleHopFkJoin` rejects an empty path first) and the `@pivot` arm (`PivotSpec` pins one FK hop).
- `ChildField.BatchedTableField` compact constructor: `requireNonNull(parentCorrelation)` unconditionally, beside the `checkCarrierInvariant` call, with a message saying a batched child needs a parent correlation and naming the leaf. Leaf-wide rather than Table-arm-only, because the consumer that needs it (`LaunchSource.CorrelatedChain` / `CorrelatedLookupChain`, via `LauncherCommands.batchedRow` / `batchedLookupRow`) serves both arms, and every record-sourced mint already lands non-null: FK paths land `OnFkSlots`, and the accessor, `@sourceRow` leaf-PK and payload re-fetch arms land `OnLiftedSlots` (`buildPayloadCarrierBatchedTableField`, the `@sourceRow` branch, the record-parent `BatchedTableField` mints). `checkCarrierInvariant` keeps admitting null on an empty path because the inline `TableField` standalone shape needs it; the stricter rule lives on the batched leaf.
- Leave `LaunchSource.CorrelatedChain` / `CorrelatedLookupChain`'s `requireNonNull` and `BatchedRowsFragments.prelude`'s guard in place as backstops; they become unreachable.
- `docs/manual/reference/directives/splitQuery.adoc` § Constraints: one bullet stating that `@splitQuery` on a field whose return type shares the parent's table and has no `@reference` is rejected, why (the batch has no parent key to key on), and the remedies with their preconditions as in the Goal. Reader's terms, no item ids.

Not changed here: the inline standalone arm's semantics and its mislabelling comment in `ProjectionUnitRenderer`; both are R1045's.

## Tests

- Pipeline tier, a focused `@PipelineTier` class beside `BatchedTableFieldPipelineTest` (or rows in `GraphitronSchemaBuilderTest`, implementer's call), on the sakila catalog with two types over `film`:
  - single-cardinality same-table `@splitQuery`, plain `@table` parent: the field is `UnclassifiedField` carrying a structural author error whose message contains the coordinate, `@splitQuery`, both type names and the table name;
  - list cardinality, same assertion;
  - `@lookupKey` + `@splitQuery` same-table list: same rejection, not the lookup gate's;
  - the reported shape, parent declaring `@key(fields: ...)` and `@defaultTenant` (the `KEY_DIRECTIVE` + `build` setup `TenantBindingClassificationTest` uses): same rejection;
  - `@tenantFanOut` alone on a same-table field: the message names `@tenantFanOut`, not `@splitQuery` (or the earlier tenant-fold rejection, per Implementation);
  - remedy 1 classifies: the same field with `@reference(path: [{condition: ...}])` over a fixture condition method, keeping `@splitQuery`, is a table-sourced `BatchedTableField` with `ParentCorrelation.OnParentJoin`;
  - remedy 2 classifies: the return type without `@table` and the field without `@splitQuery` is a `NestingField`;
  - the inline twin is untouched: the same field without `@splitQuery` stays a `TableField` with an empty path and null correlation (this pins the `deriveSplitQuerySource` move);
  - for one rejection case, assert through `GraphitronSchemaValidator`'s error output (the surface `graphitron:generate` prints), so the test shows the build reports an author error rather than only that the classifier returned `UnclassifiedField`.
- Unit tier: constructing a `BatchedTableField` with a null correlation throws, on both the table-sourced and record-sourced arms.
- Existing fixtures that construct the now-illegal state, each repointed rather than deleted, since their assertions are about other axes:
  - `BatchedLookupValidationTest.Case.TABLE_CONNECTION_BLOCKED` (table arm, `List.of()` path, null correlation): give it an FK path and `TestFixtures.pcFor(...)` like the `BatchedTableFieldValidationTest` table cases; its assertion is about the connection wrapper.
  - `RECORD_SINGLE_NO_PATH` in both `BatchedLookupValidationTest` and `BatchedTableFieldValidationTest`, and the other null-correlation record case in `BatchedLookupValidationTest`: give them `OnLiftedSlots` over the target PK, the correlation the real record-sourced mints land on an empty path.
  - Grep the test tree for any other `BatchedTableField` construction passing a null correlation and repoint it the same way.

## Implementation notes

- The guard sits in `FieldBuilder`'s `TableBoundReturnType` child arm, ahead of the `@lookupKey` gate, and `deriveSplitQuerySource` moved inside the `hasSplitQuery` branch. The message drops the `Field '<coordinate>': ` lead from the Goal's wording because `ValidationError.forField` prefixes it; the classifier rejection carries the rest.
- `@tenantFanOut` alone: the tenant fold does not stop the child arm from running. Classification reaches the same mint and the rejection names `@tenantFanOut` (`SplitQuerySameTableRejectionPipelineTest.tenantFanOutAlone_namesTenantFanOut`).
- Tests: `SplitQuerySameTableRejectionPipelineTest` (pipeline tier, every row in § Tests, the validator-output case included) and `BatchedTableFieldCorrelationInvariantTest` (unit tier, both source arms). `BatchedLookupValidationTest` (`TABLE_CONNECTION_BLOCKED` to an FK path; `RECORD_SINGLE_NO_PATH` and `RECORD_CONNECTION_BLOCKED` to `OnLiftedSlots`) and `BatchedTableFieldValidationTest` (`RECORD_SINGLE_NO_PATH`) were repointed; the generator test tier found no other null-correlation construction.

## Other solutions we've considered

- **Support it as the parent's own row.** Land `ParentCorrelation.OnLiftedSlots(parentTable, parentTable.primaryKeyColumns())` on the empty-path split, the hop-less arm the source=target payload re-fetch already uses; `BatchedRowsFragments` renders it with no new emitter shape, and it is what Graphitron 9 generated. Not adopted: the inline twin of the same SDL is an uncorrelated read, so adding `@splitQuery` would change which rows the field returns, fusing the delivery and correlation axes (see Decision). Adopting the same-row reading coherently means changing the inline standalone semantics too, which is R1045's question. Rejecting now leaves that door open: an author error can later relax into support without breaking any schema; support cannot later narrow into an error.
- **Guard only at launch.** Turn the `requireNonNull` in `LaunchSource.CorrelatedChain` into an exception naming the coordinate. It fixes the diagnostic but still lets validation pass, so the LSP and `graphitron:validate` stay silent on a schema that cannot generate.
- **Make `checkCarrierInvariant` reject null everywhere.** It would break the inline `TableField` standalone shape, which is legal.
- **An `Uncorrelated` arm on `BuildContext.ParentCorrelationResolution`** in place of `Resolved(null)`, so a split site cannot forget the case. More structural, but only compile-forced once the call sites, which today test `instanceof AuthorError` and cast to `Resolved`, become switches; that conversion spans every caller of `buildParentCorrelation` and is not needed to close this hole once the batched leaf refuses null. Worth doing when another split site is added.
