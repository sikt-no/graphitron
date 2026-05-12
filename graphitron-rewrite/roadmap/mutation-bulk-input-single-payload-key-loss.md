---
id: R138
title: "Reject bulk-input + single-record-payload mutations (extend Invariant #15 to the Payload arm)"
status: In Review
bucket: validation
priority: 3
theme: mutations-errors
depends-on: []
---

# Reject bulk-input + single-record-payload mutations (extend Invariant #15 to the Payload arm)

Surfaced in R134 (`mutation-empty-input-short-circuit-newrecord`, shipped at `36122dc` + `7fadbda`). R134 fixed the empty-input arm of the same shape; this item closes the non-empty arm, where the classifier admits a field whose only emit path throws `TooManyRowsException` at runtime for any input with >1 row. The classifier/emitter incoherence is the bug: a field that classifies cleanly has no runtime path that satisfies its declared return contract.

## Symptom

For the schema field

```graphql
type FilmPayload { film: Film }
input FilmCreateInput @table(name: "film") { title: String, languageId: Int }
type Mutation {
    createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)
}
```

the classifier admits the field as `MutationField.MutationDmlRecordField` with `tableInputArg.list() == true` and `returnType.wrapper().isList() == false`. The emitter at `TypeFetcherGenerator.buildMutationDmlRecordFetcher` (`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:3290-3365`) produces:

```java
Record1<Integer> payload = dsl.transactionResult(tx -> DSL.using(tx)
    .insertInto(film, FILM_ID, TITLE, LANGUAGE_ID)
    .valuesOfRows(in.stream().map(row -> DSL.row(...)).toList())
    .returningResult(FILM.FILM_ID)
    .fetchOne());
```

jOOQ's `ResultQuery.fetchOne()` contract (3.20.x): "Execute and return at most one record; if the query returned more than one record, an exception is thrown." For any `in` with >1 row, the `RETURNING` clause yields N records and `.fetchOne()` throws `org.jooq.exception.TooManyRowsException`. The throw propagates out of the `transactionResult` lambda, jOOQ rolls back the transaction, and the catch arm at `TypeFetcherGenerator.java:3361-3363` routes the exception through the configured error channel (or surfaces it as a graphql-java field error if no channel is set). Zero rows commit; the mutation observably fails.

The original Backlog body framed this as "silently drops N-1 returned PK records". That framing is inaccurate against jOOQ 3.20's contract: there is no silent path, only a thrown-and-rolled-back path. The bug shape is identical either way (the classifier admits a shape whose emit cannot honour the declared return), but the argument for classify-time rejection is *stronger* once stated honestly: this is not "data is dropped", it is "no admitted (input, output) pair satisfies the field's contract; every call with >1 input row throws".

## Why the existing guards miss it

- **`MutationInputResolver.validateReturnType`** (Invariants #14 + #15, at `graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:153-219`) rejects `listInput + single-cardinality` on the `ScalarReturnType (ID)` and `TableBoundReturnType (T)` arms with an "Invariant #15 / must return a list" message. The existing message text on those two arms uses a "silent drop of all-but-last-row data" framing inherited from a pre-R134 mental model; the actual runtime failure on `[ID!]! → ID` / `[T!]! → T` is the same `TooManyRowsException` shape as the one this item closes for `[T!]! → Payload`. The message-text cleanup is folded into the validator change below. The `ResultReturnType` arm explicitly excludes #15 with a comment at lines 148-150 routing the case to "the deferred Payload+list rejection in `FieldBuilder#buildDmlField`".
- **`FieldBuilder.buildDmlField`** (`graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:2375`) rejects `listInput && returnType instanceof ResultReturnType` as `Rejection.deferred(..., "synthesize-payload-carrier")`. But R75 Phase 1 introduced a single-record-carrier classification path at `FieldBuilder.java:2664-2685` that constructs a `MutationDmlRecordField` and returns **before** the deferred-rejection check fires. When the carrier trigger resolves `SingleRecordCarrierResolution.Ok` (plain SDL Object with a `@table`-element data field — exactly `FilmPayload { film: Film }`), the guard is bypassed.

The existing `DML_INSERT_LIST_PAYLOAD_DEFERRED` test fixture in `GraphitronSchemaBuilderTest.java:5554` covers the `@record`-carrier variant (which has `fqClassName` set and does not go through R75's `NoBacking` promotion). The plain-SDL variant — the exact `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload` shape declared at `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1183` for R134's compilation-tier regression — has no rejection coverage, and its emit path throws `TooManyRowsException` on every call with >1 input row.

The comment exclusion at `MutationInputResolver.java:148-150` became a stale invariant claim when R75 Phase 1 added the bypass path. This item repairs that claim at the validator layer.

## Decision: reject at validate time (option a), not lift the emit shape (option b)

The Backlog body sketched two directions. (a) extends Invariant #15 to the `ResultReturnType` arm; (b) introduces a `MutationBulkDmlRecordField` permit with a list-shaped data field and a `.fetch()`-based emit.

**This item commits to (a).** Three reasons:

1. **Mirror principle.** Invariant #15 is sealed-root-uniform over the admitted return-type arms: when the `@table` input is list-shaped, the return must be list-shaped. The predicate is `listInput && !returnType.wrapper().isList()`. The classifier opened a new admitted arm (R75 Phase 1's `SingleRecordCarrierResolution.Ok` path); the validator should mirror across that arm by the same mechanism, not by routing to an out-of-band guard. "Validator mirrors classifier invariants" applies.
2. **No real schema wants the dropped-rows shape.** A mutation that takes N inputs and returns a single carrier with one row is a footgun by construction — the response cannot carry N rows, the DML wrote N, and there is no honest mapping. If a real schema *did* want "bulk INSERT, single domain payload" (e.g. a summary count, no row data), the right surface is a `@service` mutation; the DML emitter has nothing useful to do there.
3. **(b) is feature work, not a guardrail.** A bulk-carrier permit introduces a new sealed leaf (`MutationBulkDmlRecordField`?), a list-element data-field classifier, a `Result<...>`-keyed response-SELECT predicate (`PK in (source.stream().map(...).toList())`), and a load-bearing classifier check pairing the new data-field cardinality to the emit shape. That is a separate plan; conflating it with R138 muddles the audit trail.

A sibling Backlog item should be filed for (b) under a slug like `bulk-input-single-carrier-list-data-field` if and when a real schema needs it. R138 stays a pure validator-mirror-classifier fix.

## Implementation

### Validator change

In `MutationInputResolver.validateReturnType`:

- Lift the `listInput && !returnType.wrapper().isList()` predicate above the per-arm shape switch. The predicate is sealed-root-uniform over `ReturnTypeRef`; lifting encodes that uniformity in the type system and removes the per-arm duplication that would otherwise distribute the same check across three near-identical `if` blocks (one new on `ResultReturnType`, plus the existing two). Concrete shape: factor the per-arm shape-acceptance switch (Invariant #14 + payload-list + polymorphic-not-supported) into one helper, run it first; on its admitted outcomes, apply the lifted cardinality check.
- Update the rejection message text on all three admitted arms (`ScalarReturnType (ID)`, `TableBoundReturnType`, `ResultReturnType`) to match the actual runtime mechanism. The existing wording at lines 158-161 and 182-186 ("use [...] to avoid silent drop of all-but-last-row data") inherits a pre-R134 mental model that does not match jOOQ 3.20's `fetchOne()` contract; the failure is `TooManyRowsException` thrown inside `transactionResult`, not silent drop. Replacement template: `"@mutation(typeName: <kind>) with a listed @table input must return a list (found '<name>', single-cardinality); the emit path runs valuesOfRows(...).fetchOne(), which throws TooManyRowsException on every call with >1 input row (Invariant #15)"`. The lifted predicate produces a single message construction site, so the sweep is a one-touch edit.
- Delete the comment exclusion at `MutationInputResolver.java:148-150` ("The Payload arm is excluded from #15 ..."). The lifted predicate removes the exclusion; the doc claim is stale post-R75 and confuses any reader who reaches the line. Update the Javadoc at lines 136-152 to describe the uniform cardinality check across all three admitted arms.

### Schema fixture removal and dead-branch deletion

The lifted predicate pins `MutationDmlRecordField`'s admitted shape: `validateReturnType` accepts the `ResultReturnType` arm only when `!wrapper().isList() && !listInput` (the per-arm check already rejects list-payload at line 197; the lifted check now rejects bulk-input + single-payload). So every `MutationDmlRecordField` constructed by the carrier path at `FieldBuilder.java:2664-2685` carries `tia.list() == false && dataIsList == false`. Multiple downstream branches that existed to handle the previously-reachable bulk arm collapse to the single-cardinality side; the fixtures and stale checks that pinned the bulk arm go with them.

- Drop the `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload @mutation(typeName: INSERT)` declaration at `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls:1232` and the surrounding R134 explanatory comment block at lines 1224-1231. Post-R138 the validator rejects the shape, so the schema example cannot carry it.
- Delete the entire `if (tia.list())` block at `TypeFetcherGenerator.java:3316-3336` (the empty-list short-circuit). It existed only to handle bulk-input + single-payload, which the validator now rejects upstream; `tia.list()` is constant-false at this fetcher post-R138.
- Collapse the two surviving `dataIsList` ternaries in `buildMutationDmlRecordFetcher`: the `payloadType` ternary at lines 3305-3307 (`dataIsList ? Result<rowType> : rowType`) collapses to unconditional `rowType`; the terminator ternary at line 3357 (`dataIsList ? ".fetch())" : ".fetchOne())"`) collapses to unconditional `.fetchOne())`. Both must collapse to the single-cardinality side: `wrapper().isList() == true` (the only path that would set `dataIsList` true) is rejected by the per-arm `ResultReturnType` check at `MutationInputResolver.java:197-201` before the carrier path runs, and `wrapper().isList() == false` is the only admitted shape. The `dataIsList` local at line 3302 is then unused and is removed.
- Rewrite the fetcher's Javadoc at `TypeFetcherGenerator.java:3265-3282` to describe a single emit shape: PK-only `RETURNING` + `.fetchOne()` into a `RecordN<...>`. The existing Javadoc names both list and single arms; the list-cardinality arm is now unclassifiable.
- Delete the deferred-rejection at `FieldBuilder.java:2375` (`if (listInput && returnType instanceof ResultReturnType)`) and its Javadoc/comment block at lines 2372-2374. The lifted predicate now catches the case at the validator layer regardless of carrier kind (plain SDL Object via the R75 `NoBacking` promotion, and `@record(record:{className:...})` via direct `PojoResultType` registration), and the per-arm `wrapper().isList()` check catches the complementary case. No `ResultReturnType` shape reaches `buildDmlField` via this path post-R138; the `synthesize-payload-carrier` slug no longer marks any live code. With the deferred-rejection gone, `buildDmlField`'s `listInput` parameter is unused; remove it from the signature and the four kind-switch call sites in `buildMutationField`.
- The R134 changelog entry stays accurate as history (it describes a transitional state); no edit needed there. The R134 compilation-tier regression that the `createFilmsPayload` fixture pinned has no surviving anchor because the entire shape it pinned is now unreachable; that is the principled outcome, not a coverage gap.

Pipeline-tier and truth-table fixtures that constructed the now-unreachable bulk-input + carrier-payload schema shape need their `[FilmInput!]!` inputs flipped to `FilmInput!`. Three call sites:

- `SingleRecordCarrierPipelineTest`: switch `carrier_listDataField_classifiesAsMutationDmlRecordField`, `carrier_listDataField_dataFieldClassifiesAsSingleRecordTableField`, `carrier_atRecordWithNullClassName_classifiesAsMutationDmlRecordField`, and `carrier_withDelete_rejectsAtClassifier` from `payloadDml` to `payloadDmlSingleInput`. The data-field cardinality (`films: [Film!]`) is driven by the data field's wrapper, not the mutation input, so the list-data-field semantic the test names advertise still holds with single input. The trigger-rejection cases (`carrier_withMultipleDataFields_returnsRejected`, `carrier_withScalarField_returnsRejected`) and the carrier-promotion case (`carrier_plainSdlObject_promotesToPojoResultTypeNoBacking`) stay on bulk input: in those fixtures the per-arm rejection (trigger-rejected or type-classification) fires before the lifted #15 check, so the test signal is unchanged.
- `GraphitronSchemaBuilderTest.MUTATION_DML_RECORD_FIELD` (`GraphitronSchemaBuilderTest.java:5133`): flip `createFilms(in: [FilmCreateInput!]!)` to `createFilm(in: FilmCreateInput!)` and update the field-lookup string. Same justification as the carrier pipeline tests: the row pins `MutationDmlRecordField` classification, which the carrier path produces from single input.

### Classifier-tier rejection coverage

Add a row to the truth table in `GraphitronSchemaBuilderTest`'s parameterised mutation-classification source, mirroring `DML_INSERT_LIST_SINGLE_T_REJECTED` (`GraphitronSchemaBuilderTest.java:5524-5537`):

```java
DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED(
    "DML INSERT with listed input + plain SDL Object payload return → UnclassifiedField (Invariant #15, R75 carrier-arm)",
    """
    type Film @table(name: "film") { title: String }
    type FilmPayload { film: Film }
    input FilmInput @table(name: "film") { title: String }
    type Query { x: String }
    type Mutation { createFilmsPayload(in: [FilmInput!]!): FilmPayload @mutation(typeName: INSERT) }
    """,
    schema -> {
        var f = (UnclassifiedField) schema.field("Mutation", "createFilmsPayload");
        assertThat(f.reason())
            .contains("must return a list")
            .contains("Invariant #15");
    }),
```

Update the existing `DML_INSERT_LIST_PAYLOAD_DEFERRED` row (`GraphitronSchemaBuilderTest.java:5554`) in the same change: the lifted predicate fires uniformly across `ResultReturnType`, so the `@record(record:{className:...})` carrier + bulk input case now lands at the validator-rejection arm with the Invariant #15 message, not at the `buildDmlField` deferred-rejection. Rename the row to `DML_INSERT_LIST_PAYLOAD_REJECTED` (drop `_DEFERRED`), retarget its assertion to `.contains("must return a list").contains("Invariant #15")`, and update its description string to remove the "(deferred, R75)" suffix. The `@record`-carrier variant remains a useful coverage row because it exercises a structurally distinct schema shape (`PojoResultType` with `className` vs `PlainObjectType` `NoBacking` promotion) that reaches the same validator decision through a different classification path. No split between the two rows survives post-R138; both land at the same validator decision and carry the same message shape.

## Tests

- **L3 (classifier).** One `GraphitronSchemaBuilderTest.DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` row, as shown above. Invariant #15 is one mechanism; the existing `DML_INSERT_LIST_SINGLE_T_REJECTED` and `DML_INSERT_LIST_SINGLE_ID_REJECTED` precedents both cover INSERT only, on the same one-row-per-mechanism rule. UPDATE / UPSERT siblings are not added here.

No execution-tier test. The failure mode (`TooManyRowsException` thrown inside `transactionResult`, transaction rolled back, GraphQL error surfaced) is below the noise floor for behavioural-tier coverage: an execution test asserting "throws an exception" carries no signal beyond what the classifier-tier rejection already pins. The classifier-tier truth-table row is the load-bearing test for this fix.

### Verifying the fix against a fresh checkout

For a reviewer who wants ground-truth evidence the bug exists pre-fix: temporarily re-add the `createFilmsPayload(in: [FilmInput!]!): FilmPayload` declaration to a fixture schema, run a multi-row INSERT through the generated fetcher against a live PostgreSQL, and observe the `TooManyRowsException`. No part of that observation belongs in the repo's test suite post-fix; the classifier rejection is the durable assertion.

## Acceptance criteria

- `MutationInputResolver.validateReturnType` rejects `listInput && !returnType.wrapper().isList()` for every admitted return-type arm (`ScalarReturnType(ID)`, `TableBoundReturnType`, `ResultReturnType`) via one lifted predicate above the arm switch, not three per-arm duplications.
- The rejection message contains `"Invariant #15"` and names `TooManyRowsException` as the runtime failure (replacing the pre-R138 "silent drop of all-but-last-row data" wording on all three arms).
- The comment exclusion at `MutationInputResolver.java:148-150` is deleted. `validateReturnType`'s Javadoc (lines 136-152) is updated to describe uniform #15 coverage across the three admitted arms.
- The `if (tia.list())` block at `TypeFetcherGenerator.java:3316-3336` is deleted (unreachable post-lift). The `payloadType` ternary at lines 3305-3307 collapses to `rowType`; the terminator ternary at line 3357 collapses to `.fetchOne())`; the `dataIsList` local at line 3302 is removed. The fetcher's Javadoc at lines 3265-3282 is rewritten to describe a single emit shape (no list-cardinality arm).
- The deferred-rejection at `FieldBuilder.java:2375` and its Javadoc/comment block at lines 2372-2374 are deleted. The `synthesize-payload-carrier` slug no longer marks any live code. `buildDmlField`'s `listInput` parameter is removed (unused post-deletion) and the four kind-switch call sites updated.
- `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls` no longer declares `createFilmsPayload(in: [FilmCreateInput!]!): FilmPayload` (and its R134 comment block at lines 1224-1231 is removed).
- `GraphitronSchemaBuilderTest` carries a `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` row covering the plain-SDL payload variant; the assertion contains `"Invariant #15"`. UPDATE / UPSERT sibling rows are not added; one INSERT row covers the mechanism, consistent with the existing `_SINGLE_T_` / `_SINGLE_ID_` precedents.
- The existing `DML_INSERT_LIST_PAYLOAD_DEFERRED` row is renamed to `DML_INSERT_LIST_PAYLOAD_REJECTED`, its assertion retargeted to `"must return a list"` + `"Invariant #15"`, and its description string updated (the `(deferred, R75)` suffix removed). The `@record(record:{className:...})` carrier shape now lands at the validator-rejection arm alongside the plain-SDL variant.
- The four `SingleRecordCarrierPipelineTest` fixtures that depended on the bulk-input + carrier-payload shape (`carrier_listDataField_classifiesAsMutationDmlRecordField`, `carrier_listDataField_dataFieldClassifiesAsSingleRecordTableField`, `carrier_atRecordWithNullClassName_classifiesAsMutationDmlRecordField`, `carrier_withDelete_rejectsAtClassifier`) switch from `payloadDml` to `payloadDmlSingleInput`. `GraphitronSchemaBuilderTest.MUTATION_DML_RECORD_FIELD` flips its mutation fixture from `createFilms(in: [FilmCreateInput!]!)` to `createFilm(in: FilmCreateInput!)`. The carrier-promotion (`carrier_plainSdlObject_promotesToPojoResultTypeNoBacking`) and trigger-rejection tests (`carrier_withMultipleDataFields_returnsRejected`, `carrier_withScalarField_returnsRejected`) keep their bulk-input fixtures because the per-arm rejection fires first.
- No execution-tier test is added or retained. The classifier-tier truth-table rows are the load-bearing assertions for this fix.
- `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` passes end-to-end with the schema fixture removed, the dead emit branches deleted, the deferred-rejection block deleted, the `DML_INSERT_LIST_PAYLOAD_DEFERRED` row renamed and retargeted, and the new `DML_INSERT_LIST_PLAIN_PAYLOAD_REJECTED` classifier-tier coverage in place.

## Roadmap entries (siblings / dependencies)

- **Follow-up from** [R134 / `mutation-empty-input-short-circuit-newrecord.md`](changelog.md): R134 fixed the empty-list arm of the same shape (`newRecord(...)` vs `newResult(...)`); R138 closes the non-empty arm by rejecting the shape at classify time so the broken emit path is no longer reachable.
- **Mirrors** [`DML_INSERT_LIST_SINGLE_T_REJECTED`](../graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java) and `DML_INSERT_LIST_SINGLE_ID_REJECTED`: same Invariant #15 mechanism, extended to the third admitted return arm.
- **Defers** "bulk DML with a single carrier wrapping a list-shaped data field" (`.fetch()`-based emit, new `MutationBulkDmlRecordField`-style permit, list-element data-field classifier) to a future Backlog item. File under a slug like `bulk-input-single-carrier-list-data-field` if a real schema surfaces a need for it; the principles call here is that R75 Phase 1's surface is "single-record carrier" and bulk-carrier is the next plan, not this one.
