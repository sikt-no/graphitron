---
id: R1091
title: "A list field on a payload with errors is null when the call fails, and cannot be declared non-null to answer []"
status: Spec
bucket: enhancement
priority: 3
theme: error-channel
depends-on: []
created: 2026-10-22
last-updated: 2026-10-22
---

# A list field on a payload with errors is null when the call fails, and cannot be declared non-null to answer []

## Goal

A schema author should be able to say that a list-valued data field on a payload with an errors field answers `[]`, not `null`, when the call fails into the errors list. Graphitron 9 answered `[]` in many of these cases. Today Graphitron 10 has two obstacles. Such a field is `null` on the error arm. Declaring it non-null (`[X!]!`) is a build error (`ErrorChannelWalkerError.NonNullableSuccessProjectionField`), because a non-null field that resolves `null` would bubble up and drop the sibling `errors` field. After this item a non-null list data field answers `[]` on the error arm, so the build accepts it. A nullable one keeps today's `null`. The SDL then states the contract and the client can read it from introspection. Every producer on the outcome transport (root `@service`, DML `@mutation` and `@routine` carriers) behaves the same.

Terms used below. A *payload with errors* is an object type returned by a mutation or `@service` field that has one errors-shaped field (a list of `@error` types) beside its data fields. The *outcome transport* is how graphitron hands such a payload to its child fields: the producing fetcher returns a generated `Outcome` value, `Success(value)` when the call succeeded and `ErrorList(errors)` when a handler claimed the exception. The *error arm* is that second case. A *data field* (in the code, a success-projection field) is any field of the payload other than the errors field.

The minimal pair, on the sakila bulk-insert payload the execution tests already use (`FilmsCreateCheckedPayload`, `graphitron-sakila-example` `schema.graphqls`):

```graphql
type FilmsCreateCheckedPayload {
    films: [Film]          # error arm: { films: null, errors: [...] }   (unchanged)
    errors: [FilmCreateError]
}

type FilmsCreateCheckedPayload {
    films: [Film!]!        # error arm: { films: [],   errors: [...] }   (today: build error)
    errors: [FilmCreateError]
}
```

`Film!` beside an errors field stays a build error: a single object has no empty value to answer.

## What Graphitron 9 consumers depend on

Reported by sis, whose consumer-facing developers count `null` in place of `[]` as a breaking change. Graphitron 9 was not uniform, and that shapes the plan.

- **Answered `[]`.** A list data field that was `@splitQuery` in 9 answered `[]` on every error, database constraint errors included. The error-arm payload carried no keys, and 9's split-query fetcher answered `[]` for them without a query. In sis that is 28 fields, all in the experimental contract (`AktiverStudieprogramPayload.studieprogram`, `EndreStudieprogramOkonomiPayload.studieprogram`, …).
- **Declared non-null.** Four sis payload fields were `[X!]!` in 9: `FjernStatsborgerskapPayload.ids` and `.semesterregistreringer` on `RegistrerSemesteravgiftBetaltForStudenterPayload`, `RegistrerFritakFraSemesteravgiftPayload` and `SemesterregistrerStudenterPayload`. Graphitron 10 forced them to `[X!]`, which client code generators see as a type change.
- **Answered `null`.** A list data field that was not `@splitQuery` already answered `null` in 9. That covers every one of sis's stable (32) and beta (19) payload list fields, plus 79 experimental ones (counts from sis `origin/main`).

So "always `[]`" would itself break 9 behaviour for the stable and beta contracts. The choice has to be per field, and nullability is the declaration the schema already has. With this item sis restores its four `[X!]!` declarations unchanged, and gets `[]` back on the 28 experimental fields by tightening them from `[X!]` to `[X!]!`. Tightening an output type's nullability does not break a client query.

## The rule

One rule for every data field of a payload with errors, keyed on the field's SDL return wrapper (`FieldWrapper`, the model's record of list-or-single, connection, and outer nullability):

| SDL shape | Error arm answers | Build |
|---|---|---|
| nullable (`Film`, `[Film]`, `[Film!]`, a nullable connection) | `null` | accepted (today) |
| non-null plain list (`[Film]!`, `[Film!]!`, `[ID!]!`, `[String!]!`) | `[]` | accepted (new) |
| non-null single (`Film!`, `ID!`, `Int!`) | none | rejected, `NonNullableSuccessProjectionField` |
| non-null connection (`[Film!]! @asConnection`, a hand-written `FilmConnection!`) | none | rejected, `NonNullableSuccessProjectionField` |

A connection stays out of scope because no consumer has asked for one: graphitron could answer an empty page (it already builds one when a batched key cannot match), but that is a separate decision with its own test surface, and the rejection keeps the door open. Note that the classifier reads the pre-rewrite schema, where `[Film!]! @asConnection` still looks like a list; keying on `FieldWrapper` (which `BuildContext.buildWrapper` resolves to `Connection` for both the directive and the structural spelling) rather than on `GraphQLTypeUtil.isList` is what keeps that row rejected.

The same answer applies to a `Success` whose value is `null` (a producer that returned `null`): nothing was produced, and a `null` on a `[X!]!` field would bubble and null the payload. The type-level binding already escapes on that case through the same guard as on the error arm (`ParentSourceBinding.OutcomeRecord.prelude`, `if (!(subject instanceof Success<?> success) || success.value() == null)`). The leaf-level family does not: `FetcherEmitter.emitRecordSourceLocal` tests only `instanceof Success`, the two id leaves catch a null value through a trailing `if (source == null) return null;`, and `buildRecordCompositeFetcherValue` has no guard at all and returns the null. This item aligns the leaf-level guard with the binding's (see Implementation).

The success arm is untouched: a non-null list data field reads whatever the producer or the field's own query returned, exactly as a non-null list anywhere else in the schema does.

## Implementation

**One home for the answer.** Add a small model enum in `no.sikt.graphitron.rewrite.model`, `ErrorArmAnswer` (`NULL`, `EMPTY_LIST`, `NONE`), with a single static derivation `ErrorArmAnswer.of(FieldWrapper)` implementing the table above. It is a pure function of the wrapper every data-field leaf already carries (`ReturnTypeRef.wrapper()`), so nothing new is stored on a leaf and no fact is copied. The answer is only meaningful for a data field of a payload with errors; `NONE` on any other field is legal and never consulted (see the binding change below).

**Classifier.** `FieldBuilder.nonNullableSuccessProjection` stops testing `GraphQLTypeUtil.isNonNull(f.getType())` and instead rejects a sibling of the errors field whose `ErrorArmAnswer.of(ctx.buildWrapper(f))` is `NONE`. It becomes an instance method (it needs `ctx`). Both callers, `resolveServiceOutcomeChannel` (root `@service`) and `detectStructuralDmlErrorChannel` (DML and `@routine` carriers), keep calling the one helper, so the three producers stay on one rule and the rejection keeps its current home and LSP surfacing.

**One derivation, one enforcer, no validator mirror.** The classifier check and the emitters read the same fact. Every classified leaf's `ReturnTypeRef.wrapper()` is `BuildContext.buildWrapper(fieldDef)` on the field's own definition; the only `FieldWrapper` minted elsewhere is `NestingReach.pivotWiring`'s emit-time carrier, which is never a payload child. The classifier check calls that same function on that same definition, so a second check over the leaves would compare one derivation with itself. It would also report every mistake twice: when the producer is rejected the payload still classifies (`isOutcome` reads `isServiceProducedPayloadAnywhere` and `emittedCarrierBinding`, not whether the producer classified), so `FilmPayload { film: Film! errors: [...] }` already yields an `ErrorsField` beside a `BatchedTableField` carrying a non-null `Single` wrapper. The rule therefore has one home, the classifier. It reaches validation the way every classify-time rejection does, as the producer's `UnclassifiedField` through `GraphitronSchemaValidator.validateUnclassifiedField`, which is the house form of "validator mirrors classifier invariants" (compare the `MutationRoutineWriteRecordField` arm of the validator's leaf switch). The `OutcomeRecord` throw on `NONE` (below) is generator-time and unreachable past that rejection, like `FetcherEmitter.inlineSuccessReturn`'s nesting-field throw. A pipeline case pins one diagnostic per mistake (see Tests). The `validateOutcomeChildArmSwitch` javadoc ("returning null on `ErrorList`") still changes, to "returning the field's error-arm answer".

**Diagnostic.** `ErrorChannelWalkerError.NonNullableSuccessProjectionField` keeps its name and LSP code (`graphitron.error-channel.non-nullable-success-field`); its `message()` and javadoc change to say that a data field must be nullable or a non-null plain list, and that a non-null list answers `[]` on the error arm. `RejectionSeverityCoverageTest` constructs it by components, so it needs no change.

**Type-level binding: the binding takes the answer, not a rendered escape.** Today `ParentSourceBinding.prelude(subject, CodeBlock escape)` takes an escape every caller renders by hand, always as some spelling of `null`. The answer is per field but matters on one arm only (`OutcomeRecord`); `DirectRecord` and `TableRow` are called with fields where `NONE` is perfectly legal (`Film!` under a payload without errors). So the arm dispatch belongs inside the binding:

- `prelude(subject, ErrorArmAnswer answer, EscapeForm form)` replaces the `CodeBlock` parameter. `EscapeForm` is a small sealed type naming the envelope a fetcher returns: a plain value, a synchronous `DataFetcherResult<V>`, or an asynchronous `CompletableFuture<DataFetcherResult<V>>` (carrying `V` where the rendering needs it). No caller can spell `null` or `[]` itself any more.
- `OutcomeRecord.prelude` renders the answer into the form (`NULL` as today; `EMPTY_LIST` as `List.of()` in the form's envelope; `NONE` throws `IllegalStateException` naming the coordinate, since the classifier rejected it). `DirectRecord.prelude` keeps escaping with `null` in the form's envelope, and `TableRow` keeps its empty prelude; neither reads the answer.
- The callers are exactly the leaves in `ParentSourceBinding.BINDING_SEAM_LEAVES` that reach `prelude` (today `FetcherEmitter.armSwitchedInlineDataFetcher`, the record arm of `TypeFetcherGenerator.buildBatchedDataFetcher`, and three builders in `MultiTablePolymorphicEmitter`). Completeness rests on that partition, which `GeneratorCoverageTest` pins exhaustive and disjoint, not on this list. The leaves in `WRAPPER_INADMISSIBLE_LEAVES` (child `@service`, `ComputedField`, the pivot leaves, `TableField`, the column leaves) cannot sit beside an errors field (`validateWrapperArmSiblingPosture`), so they need nothing.
- In `buildBatchedDataFetcher`, the asynchronous `EMPTY_LIST` rendering is the same expression `emptyBatchedResult` already produces for the `RecordList` result shape (`completedFuture(DataFetcherResult.<V>newResult().data(List.of()).build())`); share it rather than writing `[]` a third way in that builder.

**Leaf-level envelope: the same seam.** The three leaves that carry `SourceEnvelope.OUTCOME_SUCCESS` themselves (`SingleRecordIdField`, `SingleRecordIdFieldFromReturning`, `RecordCompositeField`) narrow through `FetcherEmitter.emitRecordSourceLocal`, the binding's leaf-level twin. It takes the same `ErrorArmAnswer` (its escape form is always the plain value), narrows with the binding's guard (`!(outcome instanceof Success success) || success.value() == null`), and escapes with the answer. The trailing `if (source == null) return null;` guards in `buildSingleRecordIdFetcherValue` and `buildSingleRecordIdFromReturningFetcherValue` then duplicate it on the wrapped arm and go there (they stay on the `DIRECT` arm, where a null source still answers `null`). This closes the `RecordCompositeField` `Success(null)` hole above and carries sis's `FjernStatsborgerskapPayload.ids` shape (`[ID!]!` with `@nodeId`).

**What does not change.** The errors field (`ChildField.ErrorsField`, `WrapperArm` transport) is not a data field and keeps answering `null` on the success arm (`NonNullableErrorsField` is untouched). A nested non-null list inside a nullable data field (`films: [Film]` whose `Film.actors: [Actor!]!`) is out of scope: on the error arm `films` is `null`, so its children are never visited. `NestingField` is in `BINDING_SEAM_LEAVES` but only classifies on table parents (`inlineSuccessReturn` throws for it), so it never reaches the outcome arm.

**Javadoc and comments that state the old rule**, rewritten to the table above: `OutcomeClassGenerator` (the generated `Outcome` class javadoc, "Success-projection fields must be nullable"), `FieldBuilder.resolveServiceOutcomeChannel` and `nonNullableSuccessProjection`, the `@routine` carrier's non-null-single check in `FieldBuilder` (the "D7: outcome (b)" comment before its `detectStructuralDmlErrorChannel` call, which says the error arm renders a non-null list null), `ParentSourceBinding` (the escape paragraph and `OutcomeRecord`), `SourceEnvelope.OUTCOME_SUCCESS`, `FetcherEmitter.bind` / `emitRecordSourceLocal` / `buildRecordCompositeFetcherValue`, `GraphitronSchemaValidator.validateOutcomeChildArmSwitch`, and the sakila `schema.graphqls` fixture comments that say a data field "must be nullable" or "renders null" (keep them where they describe a nullable fixture; they stay true there).

## User documentation (first-client check)

Replace the first two bullets of the "Three rules follow from that" list in `docs/manual/reference/directives/mutation.adoc` with:

> * On the error path a nullable data field renders `null`. A non-null list data field renders `[]`, since nothing was written; declare `[Film!]!` when your clients should never see `null` there. Every other data field must be nullable: `Film!` beside an errors field is rejected at build time, because there is no empty value to give it and a `null` would let null propagation erase the errors list. A connection (`@asConnection`) counts as a single value here, not a list.

In `docs/manual/reference/directives/routine.adoc`, the paragraph on why the data field must be nullable ends with "with an errors field present a list data field must be nullable too (`[Rental!]`, not `[Rental!]!`)"; it becomes: "with an errors field present a list data field may be non-null, and then renders `[]` on the error path as it does on a re-read that finds no row." The comment on the `rental:` line in the payload-carrier example ("must be nullable") stays, since `rental` is a single row.

In `docs/manual/how-to/error-channel.adoc` and `docs/manual/how-to/result-types.adoc`, "every other field reads null" becomes "every other field reads null, or `[]` for a non-null list", with an xref to the `mutation.adoc` rule.

In `docs/manual/how-to/migrating-from-legacy.adoc`, add a section after "Behavior divergence: when a single object resolves to `null`" and before "Things that didn't change":

> == Behavior divergence: a list field on a payload with errors
>
> In Graphitron 9 a `@splitQuery` list data field on a payload with an errors field answered `[]` when the call failed into the errors list; a list data field without `@splitQuery` answered `null`. The rewrite decides from the field's type instead: a nullable list answers `null` and a non-null list answers `[]`. To keep `[]`, declare the field non-null (`[Film!]!`); tightening an output type does not break a client query. See xref:../reference/directives/mutation.adoc[`@mutation`] for the rule.

The page's description and intro say "the one intentional behavior divergence", which is already stale (the page has two such sections); make them say "the intentional behavior divergences".

In `docs/architecture/explanation/typed-rejection.adoc`, the `NonNullableSuccessProjectionField` sentence changes to the relaxed rule.

These read simply: the rule is "nullable answers null, non-null list answers `[]`, nothing else may be non-null", which is the table, and an author can predict the wire shape from the SDL alone.

## Tests

- **Unit, the table.** A test on `ErrorArmAnswer.of` pins each row: nullable single, list and connection answer `NULL`; `[X]!` and `[X!]!` answer `EMPTY_LIST`; `X!` and a non-null `Connection` answer `NONE`.
- **Pipeline tier, the rule.** In `CarrierPayloadErrorChannelValidationTest` (DML and `@routine`) and `ErrorChannelClassificationTest` (root `@service`): `[Film!]!` and `[Film]!` beside an errors field classify without a rejection and `validate(schema)` is empty; `Film!` still rejects with `NonNullableSuccessProjectionField`; `[Film!]! @asConnection` still rejects with the same arm (the pre-rewrite-schema trap above). Two existing tests pin the old rule and flip to the accepting form: `dmlPayloadWithErrors_nonNullListDataField_isRejected` and `routinePayloadWithErrors_nonNullListDataField_isRejected` (the `@routine` one is the case the classifier's own zero-row rule already exempted). The `Film!` rejection case also asserts that `validate(schema)` holds exactly one `NonNullableSuccessProjectionField`, at the producer's coordinate, so a later check over the payload's leaves cannot double-report it unnoticed. One more case pins that only the outcome arm reads the answer: a non-null single child (`Film!`) on a record-backed parent without an errors field still generates, through the `DirectRecord` binding.
- **Execution tier, the answer**, in `GraphQLQueryTest` against new sakila fixtures, one per emit body, each asserting `[]` beside a populated `errors` on a mapped failure and the ordinary list on success:
  - DML bulk insert: a sibling of `createFilmsWithErrors` whose payload declares `films: [Film!]!` (batched re-fetch, record arm of `buildBatchedDataFetcher`).
  - Root `@service` list carrier: a sibling of `FilmsCarrierWithErrorsPayload` with `films: [Film!]!`, plus a case whose producer returns `null`, asserting `films: []` with `errors: null` (the `Success(null)` arm).
  - `@nodeId` list from a producer: a sibling of `FilmIdsCarrierWithErrorsPayload` with `filmIds: [ID!]! @nodeId(typeName: "Film")` (`SingleRecordIdField`, sis's `ids` shape).
  - `@nodeId` list from a DML delete: a bulk DELETE carrier with `[ID!]!` beside an errors field (`SingleRecordIdFieldFromReturning`).
  - Composite list: a `RecordCompositeField` list data field declared non-null, on the error arm and on `Success(null)` (the case today's code gets wrong).
  - Inline arm-switched read: a scalar list data field (`[Int!]!` read off the producer object) on a `@service` payload.
  - Batched polymorphic list: a sibling of `OccupantsWithErrorsPayload.occupants` declared `[AddressOccupant!]!` (`MultiTablePolymorphicEmitter`, batched builder). The synchronous polymorphic builder reaches a list only on the degenerate all-unbound interface or union; if no fixture can reach it under an errors payload, the implementation commit says so rather than adding a contrived one.
  Fixtures may share one payload where the classifier admits the combination. The existing `films: null` assertions (`createFilmsWithErrors_*`, `submitFilmReviewWithFilm_*`, the occupants cases) stay as the nullable half of the minimal pair.
- **No body-string assertions** on generated fetchers; the execution round-trips are the evidence the answer reaches the wire.

## Roadmap entries

R274 (OutcomeType carries its success projection) states the invariant it would move onto the carrier as "all nullable (enforced)". Its body is amended in this item's implementation commit to name `ErrorArmAnswer` as the invariant (every data field has an answer other than `NONE`), read off the classified leaves' wrappers rather than the SDL, so that item carries the rule this one ships rather than the one it retires.

## Other solutions we've considered

- **Always answer `[]` for a list data field.** Breaks Graphitron 9 behaviour for every non-`@splitQuery` list, which is all of sis's stable and beta payload lists (see above).
- **An explicit opt-in (a directive argument or plugin setting) that answers `[]` while the SDL stays nullable.** It keeps the SDL unchanged, but the contract becomes invisible to clients reading introspection, and a nullable type that never answers `null` is a promise the schema does not make. No consumer needs `[]` behind a nullable type: sis's 28 fields can tighten to `[X!]!` without breaking a client.
- **A second, validate-time check over the payload's classified leaves.** It would read the same `buildWrapper` result the classifier check reads, so it guards against no drift, and because a payload still classifies when its producer is rejected, it would report every `Film!` twice.
- **Answer `[]` by running the field's query or DataLoader with no keys.** That is what 9's split-query fetcher did in effect. It costs a dispatch for nothing, and it does not cover the inline and `@nodeId` shapes, which have no query to run.

## Reviewer findings

### Round 1 (Spec → Ready): request revisions

Reviewer session `session_015PLkpbRTM2gMANdv3haL3C`, 2026-10-22.

Question 1 passes. Without the plan sections: an author can declare a list data field on a payload with errors as `[X!]!`, the build accepts it, and on a failed call the field answers `[]` beside the populated errors list (and also when the producer returned `null`). A nullable field still answers `null`. The contract is visible in the SDL, so sis gets its four `[X!]!` declarations and its 28 split-query `[]` fields back without a per-field switch. The code claims behind it hold: `FieldBuilder.nonNullableSuccessProjection` is the single classify-time check both producers share, `BuildContext.buildWrapper` resolves `@asConnection` to `FieldWrapper.Connection` on the pre-rewrite schema, the five `ParentSourceBinding.prelude` callers are the ones named, `emitRecordSourceLocal` tests only `instanceof Success`, `buildRecordCompositeFetcherValue` has no null guard, and `emptyBatchedResult` already renders the `RecordList` empty value. Every symbol, test class and sakila fixture the plan names exists under that name. Question 2 mostly passes: `ErrorArmAnswer` is derived from the wrapper every seam leaf already carries, and moving the escape into the binding extends the existing seam instead of adding a second one. One point fails the "hand it over as-is" test.

1. **The validator mirror does fire under correct classification, so the plan states the rule twice and reports it twice (question 2).** The Validator mirror bullet justifies a second enforcer with "under correct classification it never fires". That is false for the case the rule exists to reject. When the classifier rejects the producer, the payload's own fields still classify: `isOutcome` reads `isServiceProducedPayloadAnywhere` and `emittedCarrierBinding`, not whether the producer classified. I checked with a throwaway test on today's trunk using `CarrierPayloadErrorChannelValidationTest`'s `FilmPayload { film: Film! errors: [CarrierError] }` and an INSERT `@mutation`. `Mutation.createFilm` is `UnclassifiedField` (today's rejection), `FilmPayload.errors` is `ErrorsField`, and `FilmPayload.film` is `BatchedTableField` with a non-null `Single` wrapper. `validateOutcomeChildArmSwitch` would walk that `ErrorsField`, find `film` at `NONE`, and add a second `NonNullableSuccessProjectionField` at the payload coordinate for every `Film!` an author writes. The plan's pipeline tests assert the producer's rejection only, so they would not notice the duplicate. To satisfy this, the plan says which side owns the rule and how the other behaves on the rejected path. That could be: the mirror skips a payload whose producer did not classify; or the rule moves to the validator alone, with its coordinate and LSP surfacing restated; or there is no mirror and the emitter's `IllegalStateException` is the drift backstop; or there is a deliberate double report with a test that pins both. Whichever is chosen, a pipeline case should pin the diagnostic count for `Film!` beside an errors field. Separately, if the mirror stays: `GraphitronField` has no common `returnType()`, so "`leaf.returnType().wrapper()`" means a switch over the seam leaves. `ChildField.target()` is the existing per-leaf switch to model it on. The bullet should say so.

   *Response:* the classifier owns the rule; the validator check is gone. Every classified leaf's wrapper is `BuildContext.buildWrapper` on its own field definition (the only other `FieldWrapper` minted is `NestingReach.pivotWiring`'s, never a payload child), so the leaves and the classifier check read one derivation and there is no drift to guard. The rejection reaches validation as the producer's `UnclassifiedField` via `validateUnclassifiedField`, and the `OutcomeRecord` throw on `NONE` is the generator-time backstop. The Implementation section's "One derivation, one enforcer, no validator mirror" paragraph says this. The Tests section adds an exactly-one-diagnostic assertion at the producer's coordinate for `Film!`; a probe on trunk confirms `validate` reports exactly that today for both `Film!` and `[Film!]!`. The mirror is listed under "Other solutions we've considered". The non-blocking notes are folded in: the migration guide gets a named `== Behavior divergence` section (and its stale "the one … divergence" wording is fixed), the `@routine` D7 comment joins the sweep, and both flipping tests are named (the `@routine` one too).

Non-blocking:
- `docs/manual/how-to/migrating-from-legacy.adoc` has no "behaviour notes" list. Its divergences are `== Behavior divergence: …` sections, and its intro still says "the one intentional behavior divergence". Name the section the new bullet goes in.
- The comment on the `@routine` carrier's non-null-single check in `FieldBuilder` (the "D7: outcome (b)" block just before its `detectStructuralDmlErrorChannel` call) says the error arm "renders null" for a non-null list. Add it to the comment sweep.
- `CarrierPayloadErrorChannelValidationTest.dmlPayloadWithErrors_nonNullListDataField_isRejected` pins the old rule and flips under this item. The Tests section implies this; naming it would help.
