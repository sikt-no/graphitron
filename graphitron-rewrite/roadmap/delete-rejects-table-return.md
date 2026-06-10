---
id: R287
title: Remove DELETE -> @table return path
status: Spec
bucket: bug
priority: 4
depends-on: []
created: 2026-06-09
last-updated: 2026-06-10
---

# Remove DELETE -> @table return path

DELETE cannot legitimately return an `@table`. The row is gone after the statement, and since
`RETURNING` carries only the primary key (richer columns must come from a follow-up `SELECT`, which a
deleted row cannot serve), there is no way to project a full `@table` shape. The current code admits the
shape anyway, and the generated resolver simply fills every non-PK column with `null`: the
`PkResolution.NonPkNullable` arm exists precisely so a `@table`-element DELETE carrier "emits a
constant-null fetcher" for any field that is not the PK. The Sakila example proves the wrongness by only
ever projecting the PK itself: `deleteFilmsTableCarrier` returns `deleted { filmId }`, and the execution
test passes solely because `filmId` *is* the PK. Ask that schema for `deleted { title }` and the
generated resolver hands back a `Film` with a null title off a row that no longer exists. That is a
fabricated entity, not a projection.

Two model artifacts encode this:

1. `ChildField.SingleRecordTableFieldFromReturning` projects a full `@table` shape directly off the
   PK-only `RETURNING` record (both "more than PK read out of `RETURNING`" and "a from-`RETURNING`
   `@table` projection" should not exist). It carries a `List<PkResolution>` whose only non-trivial
   work is the null-fill described above.
2. The DELETE carriers can carry a `Projected*` `DmlReturnExpression` arm, i.e. an `@table` return:
   `MutationDeleteTableField` (direct return) through `validateReturnType` accepting a
   `TableBoundReturnType` for `DmlKind.DELETE`, and the DELETE payload carriers
   (`MutationDeletePayloadField` / `MutationBulkDeletePayloadField`) by routing an `@table`-element
   data field through artifact 1.

Both contradict two invariants surfaced during R281 dimensional-model design: (a) `RETURNING` carries
only PK, anything richer is a follow-up query; (b) DELETE tops out at an encoded-ID return.

`SingleRecordIdFieldFromReturning` stays: reading the PK off `RETURNING` and encoding it to an ID needs
no follow-up and is deletion-safe by construction. The point of the cut is to make the wrong shape
*unrepresentable and unauthored*, and to stop the rewrite's own javadoc and user docs from describing it
as a deliberate sibling in the design.

## Implementation

**Reject DELETE -> `@table` at classification (the author-facing path).** Two sites in
`FieldBuilder`/`MutationInputResolver` currently admit it; both already have the rejection idiom
(`UnclassifiedField` + `Rejection.structural(...)`), so this is turning two acceptances into
rejections with a clear message:

- `MutationInputResolver.validateReturnType` (`MutationInputResolver.java:190-196`): the
  `TableBoundReturnType` arm returns `null` (accept) for every `DmlKind` bar a `Connection` wrapper.
  Add a `kind == DmlKind.DELETE` rejection here naming why ("the row is gone after the statement;
  `RETURNING` carries only the primary key, so a full `@table` projection is impossible; return `ID`").
  This closes the direct-return `MutationDeleteTableField` path: with `@table` rejected, the only
  return that reaches `buildDmlReturnExpression` for DELETE is `ID`, so the carrier only ever holds an
  `Encoded*` arm.
- `FieldBuilder.classifyDeletePayloadField` (`FieldBuilder.java:3864-3880`): the
  `case BuildContext.DmlElementKind.Table tbl` arm builds the `SingleRecordTableFieldFromReturning`
  carrier. Replace it with a rejection, joining the `RecordElement` arm immediately below it
  (`FieldBuilder.java:3881-3885`) which already rejects with a clear message. The `IdElement` arm
  (`SingleRecordIdFieldFromReturning`) is untouched.

**Backstop the invariant.** Add a compact constructor on `MutationField.MutationDeleteTableField` that
rejects a `Projected*` `returnExpression`, so "DELETE never carries a projected `@table` arm" holds even
if a future classifier path regresses. This is a runtime check, not unrepresentability; the genuinely
stronger encoding is to narrow the *component type*. Two narrowing axes, both considered and declined:

- *Sub-seal `DmlReturnExpression` on the wrong axis.* Splitting it into `Encoded`/`Projected` groups so
  every `DmlTableField` could narrow taxes three healthy verbs to fence one: `Projected*` is legitimate
  for INSERT/UPDATE/UPSERT (their rows survive the statement and a follow-up `SELECT` projects them).
- *Sub-seal on the right axis, narrow only DELETE's component.* The cost-free version is to seal
  `DmlReturnExpression` into `Encoded`/`Projected` intermediates and declare *only*
  `MutationDeleteTableField.returnExpression` as `DmlReturnExpression.Encoded`; the other three verbs
  keep the wide root, so they pay nothing, and "DELETE never projects" becomes a compile fact at the
  consumer (the `CompositeColumnField.compaction: CallSiteCompaction.NodeIdEncodeKeys` precedent at
  `ChildField.java:337`). Declined here on blast radius: it threads the new intermediate type through
  `dmlDomainReturnType` and all four `Mutation*TableField` constructors and their tests, for an
  invariant the classifier rejection already enforces at authoring time. The compact ctor is the
  proportionate backstop; if a reviewer prefers the component narrowing, the axis above is the one to
  take.

The classifier rejection above is the real guard. Both rejection sites produce `UnclassifiedField`, the
shape `GraphitronSchemaValidator` rejects by construction, so the invariant surfaces at validate time
with *no new validator arm* (the deleted no-op arm was a no-op precisely because the shape was accepted;
it is now rejected upstream). The validate-time contract is pinned by the new
`MutationDeleteTableFieldValidationTest` cases below.

**Delete the carrier and its now-dead support.** Removing
`ChildField.SingleRecordTableFieldFromReturning` (`ChildField.java:199-212`) strands a chain of code
whose only consumer is that carrier; the compiler confirms each is dead as it is removed:

- `PkResolution` (`PkResolution.java`) entirely: its javadoc states it rides only on this permit, and
  the `NonPkNullable` null-fill arm is the wrong behavior itself. Verify no other reference survives.
- `BuildContext.classifyDeleteTableProjection` and the `BuildContext.DeleteTableProjection` result type.
  `PerFieldOutcome` (`PerFieldOutcome.java`) is consumed only by `classifyDeleteTableProjection` /
  `classifyElementFieldForDeleteProjection` (no sibling production consumer); with the projection step
  removed it has no remaining consumer and is deleted.
- `FieldClassification.SingleRecordTableFromReturning` (`FieldClassification.java`) and the
  `CatalogBuilder` arm that maps to it (`CatalogBuilder.java:137,212`).
- `FetcherEmitter.buildSingleRecordTableFromReturningFetcherValue` (`FetcherEmitter.java:885-920`) and
  its dispatch (`FetcherEmitter.java:253`); the `TypeFetcherGenerator` arm/literal
  (`TypeFetcherGenerator.java:212,505,515`).
- The `GraphitronSchemaValidator` no-op switch arm and class literal
  (`GraphitronSchemaValidator.java:142,1071`).

## Javadoc and reference cleanup

The wrong concept has leaked into prose across the model, the generator, the validator, and the
user-facing docs, each describing the from-`RETURNING` `@table` projection as a deliberate design
sibling. Every site below must go or be corrected so nothing in the tree presents DELETE -> `@table` as
designed. The most important is the *user-facing* one: a reader of `code-generation-triggers.adoc`
currently learns that `@mutation(typeName: DELETE)` legitimately returns "a `@table` type".

| Site | What to do |
|---|---|
| `docs/code-generation-triggers.adoc:419` | Trigger-table row for DELETE reads "returning `ID` or a `@table` type"; change to "returning `ID`". This is user-facing and "wrong by definition". |
| `docs/typed-rejection.adoc:56` | Verify the reference; align with the new rejection if it names the removed shape. |
| `model/ChildField.java:84-106` | `SingleRecordIdFieldFromReturning` javadoc cites `SingleRecordTableFieldFromReturning` as a load-bearing sibling (lines 92-98). Drop the sibling reference; reframe the remaining invariant split. |
| `model/ChildField.java:119-148` | `SingleRecordIdField` javadoc references the removed type as a sibling (lines 131-135). Drop it. |
| `model/ChildField.java` (`SingleRecordTableField` javadoc) | Remove the removed-type sibling cross-reference if present. |
| `model/MutationField.java:102-112` | `MutationDeleteTableField` javadoc says it "returns its `@table` type directly or returns an encoded ID". Correct to ID-return only, and note the compact-ctor invariant. |
| `model/MutationField.java:24-28` | `DmlTableField` javadoc lists "projected `@table`" as a return shape; note DELETE is excluded. |
| `model/MutationField.java:322` | `MutationBulkDmlRecordField` javadoc DELETE-carve-off note referencing the removed carrier. |
| `model/PkResolution.java` | Deleted with the carrier. |
| `PerFieldOutcome.java:32`, `BuildContext.java:469,492`, `FieldRegistry.java:72`, `GraphitronSchemaBuilder.java:250`, `GraphitronSchemaValidator.java:1061`, `TypeFetcherGenerator.java:4355`, `FetcherEmitter.java:885-918`, `CatalogBuilder.java:126`, `FieldClassification.java:376` | Javadoc/comment references to the removed carrier and projection; remove or rewrite as their host code is cut. |

Roadmap cross-references that name the carrier as a live design element get a light touch, not a
rewrite: `dml-payload-positional-alignment.md:201`, `classification-test-dsl.md:525`,
`audits/classification-test-dsl-inventory.md:142`, and `audits/2026-06-10-roadmap-staleness-audit.md:49`
(the audit that flagged this contradiction) should note R287 resolved it. `changelog.md` historical
entries (R156/R266 landings) are left as-is: they record what shipped, not what the design endorses.

## Tests

- **Validation (new/extended).** `MutationDeleteTableFieldValidationTest` already exists; add a case
  asserting a direct-return `@mutation(typeName: DELETE)` over a `@table` type yields a `ValidationError`
  with the new message, and a case for a payload carrier with a `@table`-element data field. These pin
  the author-facing contract.
- **Corpus and coverage retirement.** The `SingleRecordTableFieldFromReturning` permit is asserted by
  `VariantCoverageTest:76`, `ProjectionCoverageTest:82`, `SingleRecordPayloadPipelineTest:140-146`,
  `PkResolutionEmitterReachabilityTest` (delete outright), and rows in `GraphitronSchemaBuilderTest`
  (around `:8105-8125,8254-8272`). Retire the permit's rows; `LeafTupleAdapter:135-143` already refuses
  the carrier as having "no valid (producer, mapping) verdict", so its refusal arm retires with the type
  rather than being inverted. Confirm `VariantCoverageTest` still passes its exhaustiveness check with
  one fewer permit.
- **Execution + example schema.** Remove the live proof of the wrong path:
  `DmlBulkMutationsExecutionTest.deleteFilmsTableCarrier_projectsPkColumnsFromReturningRecord`
  (`:929-955`) and its comment block (`:887-897`); the `deleteFilmsTableCarrier` field, the
  `DeletedFilmsTablePayload` type (`schema.graphqls:1584`), the `DeletedFilmInfo` element type, and the
  `@table`-element half of the R156 schema comment (`schema.graphqls:1692-1710`). Keep
  `deleteFilmsIdCarrier` / `DeletedFilmsIdPayload` and its execution test: the ID-return carrier is the
  legitimate sibling. `FilmDeleteInput` is shared; keep it. After the `@table`-element half is removed,
  confirm `graphitron-sakila-example` still compiles (the cross-module backstop) and the kept
  `deleteFilmsIdCarrier` execution test still exercises the deletion-safe ID path.

## Out of scope / notes

- `DmlReturnExpression`'s `Projected*` arms stay: they are correct for INSERT/UPDATE/UPSERT.
- The name `MutationDeleteTableField` reads oddly once the carrier can only return `ID`, but "TableField"
  names the *family axis* (direct-return DML on a `@table`, as opposed to the `*DmlRecordField` /
  `*PayloadField` carriers), not the return shape, which `returnExpression` carries. Renaming to encode
  "ID-only now" would conflate the family axis with the return-shape axis, the exact two-axes-in-one-name
  smell the sealed-hierarchy naming avoids; it also carries broad test/catalog churn. Keep it; flagged
  for the reviewer.

Discovered during R281 spec design (the `producer x mapping x source` dimensional model); the
`2026-06-10` roadmap staleness audit independently flagged the same contradiction as live.
