---
id: R1016
title: "A tenant-routed mutation payload carries both its tenant and its errors"
status: Spec
bucket: bug
priority: 6
theme: runtime-connection
depends-on: []
created: 2026-10-12
last-updated: 2026-10-12
---

# A tenant-routed mutation payload carries both its tenant and its errors

## Goal

In a database-per-tenant build (one with `<tenantColumn>` configured), a successful `@mutation` or `@routine` write whose payload declares an `errors` field returns its payload cleanly, and a failed one still returns its typed errors. Today every successful such mutation fails with `Can't resolve value (/endreStudieprogram/errors) : type mismatch error, expected type LIST`. graphql-java gives each field one per-subtree value slot, `localContext`. The tenant-routed fetcher puts the tenant it picked there so the payload's children query the same tenant database, and the payload's `errors` field reads its error list from the same slot. On success that field gets the tenant key instead of a list. When this lands, the payload object itself carries the errors, the way `@service` payloads already do, and `localContext` carries only the tenant. The schema that fails today works unchanged:

```graphql
type Mutation {
  endreStudieprogram(input: [EndreStudieprogramInput!]!): EndreStudieprogramPayload
    @mutation(typeName: UPDATE, table: "studieprogram")
}
type EndreStudieprogramPayload {
  studieprogram: [Studieprogram]
  errors: [EndreStudieprogramError!]
}
union EndreStudieprogramError = UgyldigInput
```

Two author-visible edges move with it, both toward what `@service` payloads already do (see "Author-visible changes"): when a mapped error fires on a bulk DML write, the list data field renders `null` rather than `[]`, and a DML payload with an `errors` field must declare its data field nullable, which the build now checks.

## Provenance

sis IT run against Oracle 18c on trunk `dd940c703` (`sis/GRAPHITRON10_STATUS.md`, section "Second IT run"), about 37 of 218 ITs fail on this one shape.

## Terms

- **Error channel**: how a mutation fetcher's caught exception reaches the payload's `errors` field. Classified as `ErrorChannel` on the mutation field; the payload side is `ChildField.ErrorsField` with a `ChildField.Transport` saying where it reads from.
- **Tenant hand-down**: a fetcher that works out the tenant from its arguments (`TenantBinding.ArgumentBound`) returns `DataFetcherResult...localContext(_divinedTenant)`. graphql-java passes a parent's `localContext` to every descendant that sets none of its own, and descendants classified `TenantBinding.Inherited` read it back through the generated `TenantConnections.divinedTenant(env.<Object>getLocalContext())` and `TenantConnections.tenantLoaderName(env)`.
- **`Outcome`**: the generated wrapper (`OutcomeClassGenerator`) a fetcher returns as its payload value: `Success(value)` on success, `ErrorList(errors)` on a mapped error. The payload's children receive it as `env.getSource()`. The data fields unwrap `Success` and render `null` on `ErrorList`; the errors field reads `ErrorList.errors()`. `@service` payloads moved onto it in R244 (`ErrorChannel.Mapped`, `Transport.WrapperArm`), and R244 explicitly left DML on the older transport.

## The defect

Two writers and two readers share the slot. The tenant writers are `TenantDslEmitter.Resolution.localContextTail` and `TenantAcquisitionFragments` (ArgumentBound arm), plus per-element stamping in the generated `fanOutRows` / `fanOutBatchRows` (`ConnectionRuntimeClassGenerator`). The errors writer is the generated `ErrorRouter.dispatchToLocalContext`, which returns `data(sentinel).localContext(List.of(t))`. Here `sentinel` is an all-null key record (`RecordSentinel`) that keeps the payload non-null so graphql-java still descends into it. The errors reader is `FetcherEmitter`'s `Transport.LocalContext` arm, `return env.getLocalContext();`. The tenant readers are as listed under Terms.

The channel is `ErrorChannel.LocalContext`, assigned by `FieldBuilder.detectStructuralDmlErrorChannel` to every directiveless DML or routine-write payload carrier with an errors-shaped field: `MutationDmlRecordField`, `MutationBulkDmlRecordField`, `MutationRoutineWriteRecordField`. A tenant-routed instance of any of the three fails on success: the generated body is `return ...data(payload).localContext(_divinedTenant).build()` inside the `try`, and the `errors` field returns the tenant key. The error path holds today only by accident. The catch arm overwrites the slot with the throwable list, so the data field's `tenantLoaderName(env)` names its loader after a list of exceptions. The data field never reaches `divinedTenant` only because the sentinel's null key short-circuits key extraction first.

No test at any tier combines a tenant scope with an errors payload on a DML or routine-write carrier; `multitenant.graphqls` has none.

## Design

Move the DML and routine-write carriers onto the `Outcome` transport `@service` payloads already use. Errors then travel on the payload object (`env.getSource()`), the tenant travels in `localContext`, and the slot has one meaning everywhere.

This extends the shape already in the tree rather than adding a second one. `ErrorChannel`'s own javadoc says `Mapped` and `LocalContext` carry the same two fields "by coincidence"; they are one author-facing concept with two transports. The payload's data field is already the shape `Outcome` handles: `FieldBuilder.buildPayloadCarrierBatchedTableField` builds the same record-sourced `BatchedTableField` for `ProducerBinding.DmlEmitted`, `ServiceEmitted` and `RoutineEmitted`. The `ServiceEmitted` instance already runs under `Outcome` with tenant hand-down (`TenantDivinedRoutingExecutionTest.service_onTheErrorChannel_routesAndHandsTheTenantDown`, `RateFilmsCheckedPayload.films`). On the error arm the data field's `Outcome` narrowing (`ParentSourceBinding.OutcomeRecord`) returns before any tenant read, and the errors subtree reads no database, so no reader sees an absent tenant.

Once nothing else writes it, `localContext` meaning "the tenant" is an invariant, and it gets an enforcer (see Implementation, "The slot's one meaning").

## Implementation

**Classification** (`FieldBuilder`)
- `detectStructuralDmlErrorChannel` produces `ErrorChannel.Mapped` (`StructuralDmlErrorChannel.Present` narrows to `Mapped`). Its callers on the DML and routine-write paths take `Mapped` wherever they took `LocalContext`.
- Apply the nullable-success-projection check to these carriers too. Today `resolveServiceOutcomeChannel` runs it inline, rejecting with `ErrorChannelWalkerError.NonNullableSuccessProjectionField`. Lift that loop into one helper both sites call, so the check is one rule, not two copies. Routine-write payloads already reject a non-null data field for their own reason (`routine.adoc`), so the change in what is accepted is DML-only.
- `transportForParent` returns `Transport.WrapperArm` for every parent with an active channel: a root-`@service`-produced payload, as today, or an `emittedCarrierBinding` parent. `selectErrorsTransport` and its rule table retire. For active-channel parents, its `PayloadAccessor` arm was reachable only through an explicit `@field(name: "<literal>")`, and there it already disagreed with the `LocalContext` catch arm. Carrier payloads have no backing class to probe, so only the `$errors` sigil and that explicit-name case ever reached the table.
- `ChildField.SingleRecordIdFieldFromReturning` (the encoded-id data field of a payload-returning DELETE) gains the `SourceEnvelope` slot `SingleRecordIdField` already carries. It is minted the same way (`OUTCOME_SUCCESS` when the payload carries an errors field). Its fetcher (`FetcherEmitter.buildSingleRecordIdFromReturningFetcherValue`) reads through `emitRecordSourceLocal` like `buildSingleRecordIdFetcherValue` does. Remove it from `ParentSourceBinding.WRAPPER_INADMISSIBLE_LEAVES`; otherwise `validateWrapperArmSiblingPosture` rejects every DELETE payload with an errors field. Correct the prose this falsifies: the `SourceEnvelope.DIRECT` gloss ("DML mutation carrier") and `ParentSourceBinding.of`'s claim that the wrapper transport is granted only to service-produced payloads.

**DML fetchers** (`TypeFetcherGenerator`: `buildSingleRecordTwoStepFetcher`, `buildBulkRecordTwoStepFetcher`)
- When the field carries `ErrorChannel.Mapped`, the value type becomes `Outcome<…>` around today's record / result type. The success return wraps in `new Success<>(…)` and keeps the tenant tail, as `buildServiceFetcherCommon` does. That includes the bulk `in.isEmpty()` early return.
- The catch arm is `ChannelCatchArmEmitter.emit`. The `dispatchToLocalContextCatchArm` / `singleRecordSentinelFor` path retires, and so does the `LocalContext` arm of `asyncRouterCall`: it emits a three-argument `dispatchToLocalContext` call against the four-parameter generated method, so it would not compile if reached.

**Routine-write fetchers** (command seam: `command/ErrorDispatch`, `plan/RoutineWriteCommands.dispatchFor`, `render/RoutineWriteFetcherRenderer`, `render/ErrorDispatchFragments`)
- `ErrorDispatch` gains an `OutcomeWrapped` arm in place of `LocalContextRouted`. It carries the router, mappings and `Outcome` units as `UnitRef`s minted by `GeneratedUnits` (the `Outcome` unit is the one `ChannelCatchArmEmitter.errorListClass` already resolves), plus the mappings constant name.
- Unlike `LocalContextRouted`, this arm also governs the success return and the entry point's value type, so `RoutineWriteFetcherRenderer.closeEntryPoint` reads both off the row. Both arms of the renderer (`renderChainReread`, `renderCarrierKeys`) take the same path, which also removes the sentinel the CarrierKeys arm builds today.
- The catch body gets one spelling: a render-side primitive (`ErrorDispatchFragments`) that `ChannelCatchArmEmitter` delegates to. That is the two-level arrangement `ErrorDispatchFragments`' javadoc already describes, and it keeps `rewrite.generators` out of `render`'s imports (`PackageImportDirectionTest`).
- `catchArm` loses its nullable `localContextSentinel` parameter and the `IllegalStateException` that guarded it.

**Generated runtime** (`ErrorRouterClassGenerator`)
- `dispatchToLocalContext` and its javadoc retire, along with the class javadoc paragraph that describes it.

**Validation** (`GraphitronSchemaValidator`)
- `validateLocalContextErrorsFieldGuards` and `isLocalContextGuardedDataChannel` retire with the transport. The DML and routine payloads' siblings now fall under `validateOutcomeChildArmSwitch` and `validateWrapperArmSiblingPosture`, the rules `@service` payloads already answer to.

**The `$errors` sigil** (`FieldSourceSigil`, `FieldSourceSigilGrammar`, the LSP completion and diagnostic arms that route through them)
- The sigil's only effect was to select `Transport.LocalContext`; with every active-channel parent on `WrapperArm` nothing reads it. It appears in no user doc and no example schema.
- Keep parsing it, so an existing schema still builds, but have the classifier emit a build warning that the sigil is no longer needed and can be removed.
- The LSP stops offering it as a completion. Rename the parse arm (`FieldNameRef.LocalContext`, `LOCAL_CONTEXT_LITERAL`, `FieldSourceSigilGrammar.LOCAL_CONTEXT`) off the transport name, to `ErrorsSigil` / `ERRORS_LITERAL` / `ERRORS`. Removing the sigil outright is a later item once the warning has shipped.

**Fact store** (`graphitron-model.sql`)
- `intent_field_error_channel.transport` derives `OUTCOME_WRAPPER` for root DML and routine carriers, as for root `@service`. Narrow its column comment's domain to the two remaining values. This is a correctness edit to a dissolving family, not a precedent for its shape.

**The slot's one meaning**
- `FetcherResult.success`'s free `CodeBlock builderTail` becomes a typed value only tenant code can mint: a `TenantHandDown` (render package) that `TenantDslEmitter.Resolution` and `TenantAcquisitionFragments` produce. A non-tenant writer can then no longer reach the success envelope.
- A meta-test in the `graphitron` test tier backstops the emitted-literal side. It fails if a `localContext(` or `getLocalContext` literal appears in generator main sources outside the tenant owners: `TenantDslEmitter`, `TenantAcquisitionFragments`, the tenant statics in `ConnectionRuntimeClassGenerator`, and `TenantHandDown` itself. Same style as `RoadmapReferenceGuardTest`.

**Prose**
- Correct the comments that describe the `LocalContext` catch arm as shipping `data(null)` while the code ships the sentinel. Most are deleted with their subjects; the survivors are in `ParentSourceBinding` (DirectRecord prelude), `TypeFetcherGenerator` (`buildBatchedDataFetcher`), `GeneratorUtils` (the "LocalContext sentinel short-circuit" in the key-extraction javadoc), and `ChildField.ErrorsField` / `Transport`.
- In the sakila fixture, rename `FilmCreateLocalContextPayload` to a name that is not a transport (`FilmCreateCheckedPayload`) and rewrite its schema comment.

## Author-visible changes

Neither change touches a schema in the tree; both bring DML payloads in line with what `@service` payloads already do.

- **Bulk DML error path.** When a mapped error fires on a list-input DML write, the payload's list data field renders `null` where it rendered `[]`. It took `[]` only because the sentinel was an empty `Result`. `null` is also the more honest answer, since the transaction rolled back and nothing was written.
- **Non-null data field on a DML payload with errors.** `X!` and `[X!]!` are now rejected at build time with the `NonNullableSuccessProjectionField` message. For `X!` this replaces a runtime failure: on the error path the null data bubbled up and erased the errors list. For `[X!]!` on a bulk payload, a shape that worked through the `[]` above now fails the build; the fix is dropping the outer `!`. Before implementation starts, check the sis schemas for this shape so the changelog line can say whether anyone is affected.

**User docs**
- `docs/manual/reference/directives/mutation.adoc` gains a short "Payload with an errors field" paragraph covering both points. `routine.adoc` already states its own nullability rule and needs nothing.
- `docs/manual/how-to/error-channel.adoc` keeps its producer-family list unchanged.
- `docs/architecture/reference/code-generation-triggers.adoc`: re-check any trigger row naming the `LocalContext` channel.

## Tests

- **Execution, the reported shape.** Add to `multitenant.graphqls` a bulk UPDATE on `film` (the fixture's tenant column is `film_id`) returning a payload with a nullable `[Film]` data field and an `errors` field over an `@error(handlers: [{handler: DATABASE}])` type. This mirrors `endreStudieprogram`. In `TenantDivinedRoutingExecutionTest`:
  - On success the payload returns its rows from the tenant the input names, with `errors: null`, and the other tenant's database is never opened. This is the test that fails today.
  - A write that violates a constraint returns `{ films: null, errors: [{ __typename }] }` with no top-level error, on the tenant the input names.
- **Execution, single-tenant regression.** The existing `GraphQLQueryTest` pairs (`createFilmWithErrors_*`, `rentFilmPayload_*`) keep their wire assertions. Rename their `...LocalContextErrorChannel` test names. Add one bulk DML errors case asserting the `null` list data field on the error arm.
- **Pipeline.**
  - `SingleRecordPayloadPipelineTest`'s errors-channel cases and `RoutineMutationWritePipelineTest.routineCarrierFetcher_catchArmCarriesSentinel` assert the `Outcome` shape: `Success` wrap, `ErrorList` catch, no sentinel. Rename the latter.
  - `TenantRoutedFetcherPipelineTest` gains a tenant-routed DML payload with an errors field, and `RoutineWriteTenancyPipelineTest` gains the routine-write counterpart. Each asserts that the success return carries both `new Success<>(` and `.localContext(_divinedTenant)`, and that the errors field reads `ErrorList.errors()` rather than `getLocalContext`. Routine writes stay at the pipeline tier unless the multi-tenant fixture database carries a writable routine.
- **Validation.**
  - A DML payload with an errors field and a non-null data field rejects with `NonNullableSuccessProjectionField`, in single and bulk forms.
  - A DELETE payload with an errors field and an `[ID!]` data field is accepted, pinning the `SourceEnvelope` change.
  - `@field(name: "$errors")` on a carrier's errors field builds with the deprecation warning.
  - `LocalContextErrorsFieldValidationTest` retires; its positive cases move under the `Outcome` sibling rules.
- **Unit.**
  - `ErrorRouterClassGeneratorTest`'s two `dispatchToLocalContext` cases retire.
  - `ErrorsTransportSelectionTest` retires with `selectErrorsTransport`.
  - `FieldSourceSigilParseTest` follows the rename.
  - `ErrorChannelRelationTest` expects `OUTCOME_WRAPPER` for `Mutation.deleteFilm`.
- **Meta.** The `localContext` ownership guard described above.

## Out of scope

- An unmatched throw on any channel-carrying fetcher, `@service` included, falls through to `ErrorRouter.redact`, not `surfaceClientErrorOrRedact`. So a `GraphitronClientException` raised inside the `try` reaches the client as a correlation id instead of its message. One example is a tenant disagreement from `divinedTenant`. The fix is in the channel's fall-through, not in the transport, and deserves its own item.
- `buildBulkRecordTwoStepFetcher` divines the tenant before its `in.isEmpty()` short-circuit, so an empty batch whose only tenant slot is inside the list fails as "absent" instead of returning empty. Its own item; it is independent of the transport.

## Roadmap entries

- R533 (single-source the null-source-guard predicate) loses its validator half when `isLocalContextGuardedDataChannel` retires. The emitter's null-source prelude has no second consumer left to single-source with. Discard R533 in this item's Done commit, naming R1016 as the absorber.
- R333's coordinate model lists `LocalContext` among channel and locator values. Note the narrowed domain in its body at Done.
- R777 and R122 cite `dispatchToLocalContext` and the localContext slot; the retirement sweep updates their prose.

## Retired vocabulary

`ErrorChannel.LocalContext`, `ChildField.Transport.LocalContext`, `ErrorRouter.dispatchToLocalContext` (`buildDispatchToLocalContextMethod`), `ErrorDispatch.LocalContextRouted`, `ErrorDispatchFragments.localContextArm`, `localContextSentinel`, `RecordSentinel`, `TypeFetcherGenerator.dispatchToLocalContextCatchArm`, `singleRecordSentinelFor`, `GraphitronSchemaValidator.validateLocalContextErrorsFieldGuards`, `isLocalContextGuardedDataChannel`, `FieldBuilder.selectErrorsTransport`, `FieldSourceSigil.FieldNameRef.LocalContext`, `LOCAL_CONTEXT_LITERAL`, `FieldSourceSigilGrammar.LOCAL_CONTEXT`, `LocalContextErrorsFieldValidationTest`, `ErrorsTransportSelectionTest`, `FilmCreateLocalContextPayload`, the `LOCAL_CONTEXT` value of `intent_field_error_channel.transport`, and prose phrases "localContext error channel", "LocalContext transport", "LocalContext-routed", "sentinel" (in the error-channel sense).

## Reviewer findings

### Spec → Ready, request revisions (session_013xG7dHBdVeDjXrCSRH6917, 2026-10-12)

Question 1 mostly holds. When this lands, a database-per-tenant build can put an `errors` field on a `@mutation` or `@routine` write payload and get its rows back on success, which today fails with a `type mismatch error, expected type LIST`. The defect is real: on success the tenant fetcher's `localContext(_divinedTenant)` tail and the `Transport.LocalContext` errors read share the one slot. Question 2 holds in direction too. The plan extends the `Outcome` transport `@service` payloads already ride; it does not stand a parallel one. The validator rules it relies on key on `Transport.WrapperArm`, so they pick DML payloads up as claimed, and the data field's narrowing runs ahead of the loader name. Every symbol the spec names exists as named, apart from `TenantHandDown`, which the spec introduces. Two things send it back. Both are places where the plan as written changes consumer-visible behaviour that the spec doesn't account for, or says is unchanged.

1. **A single-row DML write that matches no row stops rendering a null payload (question 2, and question 1's account of what changes).** Today `buildSingleRecordTwoStepFetcher` returns `data(payload)` with whatever `.fetchOne()` gave it. An UPDATE whose key matches nothing therefore renders the whole payload `null`. `RoutineWriteFetcherRenderer.nullKeysGuard` documents this as "the same contract the DML single-row shape carries". The plan wraps every success return in `new Success<>(…)`, so that write becomes `Success(null)`. The payload is then non-null, graphql-java descends into it, and the `@table` data field narrows `Success` and reads its key straight off `success.value()`. The read goes through `GeneratorUtils.buildKeyExtractionWithNullCheck`, i.e. `((Record) success.value()).get(…)`, and it throws `NullPointerException`. That turns a quiet `null` into a redacted top-level error with `{ film: null, errors: null }`, on every errors-bearing single DML payload, tenant-routed or not. (DELETE escapes this, because `buildSingleRecordIdFromReturningFetcherValue` guards `source == null` after the narrowing. The routine CarrierKeys arm also escapes, because its `nullKeysGuard` returns before the wrap.) The fix is the author's decision, because the two options differ on the wire. One keeps the null-payload contract: return null data before the wrap, the way the routine arm's guard does. The other adopts `Success(null)` as "no row" and gives the record-arm read a null-value escape after narrowing. Satisfied when the DML fetchers bullet says which, Author-visible changes records the outcome if the wire moves, and Tests carries a no-row single UPDATE on an errors payload.

2. **Routine-write list carriers change too, and the spec says they don't (question 1).** The classification bullet says routine-write payloads "already reject a non-null data field", so "the change in what is accepted is DML-only". `FieldBuilder`'s routine-carrier classification rejects only a non-null *single* data field. It exempts list data fields in so many words ("A list data field renders the zero-row read as an empty list and is exempt"). A routine carrier with `[Rental!]!` builds today, and on the error path it gets `[]` from `RecordSentinel.bulk`. Under the plan its error arm resolves `null`, so `[X!]!` must now be rejected for the same reason as the DML bulk case. Both author-visible edges then apply to routine list carriers as well as bulk DML. Satisfied when the classification bullet, the Author-visible changes section and its "before implementation starts, check the sis schemas" note cover routine list carriers. The `routine.adoc` line ("needs nothing") also needs re-checking against that, and the validation tests need a routine list-carrier rejection case.

Not blocking, for the author's next pass:

- The model's slot types move with the classification, and the plan doesn't say so. `MutationDmlRecordField` and `MutationBulkDmlRecordField` carry `Optional<ErrorChannel.RouterDispatched>`, and `MutationRoutineWriteRecordField` carries `Optional<ErrorChannel.LocalContext>`. All three become `Optional<ErrorChannel.Mapped>`, matching how `WithErrorChannel`'s javadoc describes the partition. With `LocalContext` gone, `ErrorChannel.RouterDispatched` permits only `PayloadClass`. Saying whether that interface survives settles a question the implementer will otherwise answer alone.
- `SingleRecordIdFieldFromReturning` leaving `ParentSourceBinding.WRAPPER_INADMISSIBLE_LEAVES` has to land in `BINDING_SEAM_LEAVES`. `GeneratorCoverageTest` pins the partition exhaustive and disjoint.
- The typed `TenantHandDown` has to thread through `returnSyncSuccess`, `returnSyncSuccessWrapped` and `asyncWrapTail` as well as `FetcherResult.success`, or the free `CodeBlock` survives one level up.
- The same `Success(null)` hole already exists for a root `@service` whose method returns null under an errors payload. That is worth a Backlog item of its own, whichever way finding 1 is settled.
