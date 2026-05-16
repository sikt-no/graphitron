---
id: R161
title: "Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path"
status: Spec
bucket: structural
priority: 3
theme: mutations-errors
depends-on: [error-handling-parity]
created: 2026-05-13
last-updated: 2026-05-16
---

# Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path

Two parallel designs exist today for `@record`-returning DML mutations. R75 + R141 introduced the carrier-walk path (`MutationField.MutationDmlRecordField` / `MutationBulkDmlRecordField`); the older reflective-payload path (`DmlReturnExpression.Payload` consumed by the four `DmlTableField` permits) stayed in place, and the two compete for the same SDL shapes at classify time. The duplication is structurally unprincipled: Path 2 reflectively constructs the user's payload class with the row dropped into a constructor slot and defaults elsewhere, then hands the typed instance to graphql-java as a source value; the carrier walk hands graphql-java a PK record and lets per-field DataFetchers project. Both wire through graphql-java's field resolution, but Path 2 leans on `PropertyDataFetcher` reading from a typed Java source (the eagerly-assembled DTO), while Path 1 contributes explicit per-field DataFetchers. graphql-java's per-field wiring is the right abstraction here; reflective DTO assembly is a workaround that pre-materializes a courier whose only function is to satisfy `PropertyDataFetcher`. R161 retires Path 2 entirely.

After R161, `Mutation*TableField` permits are guaranteed to never carry a `@record` return — the `DmlReturnExpression.Payload` arm that admitted them is gone, and the type system enforces narrowness structurally rather than via classifier-acceptance shape.

## Concrete state today

- `DmlReturnExpression` is a sealed type with five arms; the `Payload` arm wraps a `PayloadAssembly` (`payloadClassName`, `rowSlotType`, `rowSlot ∈ {CtorParameterIndex, SetterMethod}`, `defaultedSlots`) — the validated wiring recipe between the DML's typed jOOQ row record and the user's payload-class construction.
- `FieldBuilder.resolveDmlPayloadAssembly` (declared at `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:2028`, body extends to `:2098`; locate by symbol) does reflection + jOOQ catalog lookup + SDL field walk to produce the assembly. Rejection cases include class-not-loadable, no row-typed parameter, multiple row-typed parameters, unresolvable construction shape, bean-arm with no row setter.
- `TypeFetcherGenerator.emitPayload` (declared at `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:3088`; locate by symbol) emits `var payload = new FilmPayload(row, null, ...)` literal DTO construction, with sibling functions `emitPayloadCtor` (`:3130`) and `emitPayloadSetters` (`:3118`) handling the two construction shapes.
- `FieldBuilder.classifyMutationField` routes to `MutationDmlRecordField` / `MutationBulkDmlRecordField` when `BuildContext.tryResolveSingleRecordCarrier(returnTypeName, kind) == Ok`; otherwise it falls through to `buildDmlField` which calls `buildDmlReturnExpression`. The `ResultReturnType` branch constructs `new DmlReturnExpression.Payload(assembly)`, and the resulting `MutationInsertTableField` (or sibling) carries a `@record` return inside its `returnExpression`.
- `BuildContext.tryResolveSingleRecordCarrier` (declared at `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:530`; the candidate predicate sits in the method body at `:534-535`) admits `PlainObjectType` or `PojoResultType.NoBacking`, not `PojoResultType.Backed`. So `@record(record: {className:})` wrappers route to Path 2 today.
- `MutationInputResolver.validateReturnType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:169-260`) admits `ResultReturnType` whenever `fqClassName != null` (Path 2) or `fqClassName == null` and `tryResolveSingleRecordCarrier == Ok` (Path 1). The two probes are disjoint today: `fqClassName != null` implies `PojoResultType.Backed`, which the carrier walk currently rejects.

## Spec direction

- **Delete `DmlReturnExpression.Payload`.** The sealed type collapses to four arms (`EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`). `buildDmlReturnExpression`'s signature loses its `Optional<PayloadAssembly>` parameter; the `ResultReturnType` branch in `buildDmlReturnExpression` becomes unreachable from `buildDmlField` and is deleted. The Javadoc claim about totality over Invariant #14's admitted set is rephrased without the `ResultReturnType` row.

- **Delete the `PayloadAssembly` subsystem in the model package.** `PayloadAssembly`, `RowSlot` (entire sealed type including `CtorParameterIndex` and `SetterMethod` permits) all go from the model package. `PayloadConstructionShape` (`AllFieldsCtor` / `MutableBean`) stays in the model package: still consumed by `resolveErrorChannel` and `resolveServiceResultAssembly` for the catch-arm / service-result payload shapes. `NonBoundSetter` likewise stays: still consumed by `ErrorsSlot.SetterMethod` and `ResultSlot.SetterMethod`. The `FieldBuilder`-internal `DmlPayloadAssemblyResult.{Assembly, Reject, NoAssembly}` sealed and its `NO_ASSEMBLY` constant retire with their sole caller `resolveDmlPayloadAssembly`.

- **Delete the reflection layer in `FieldBuilder`.** `resolveDmlPayloadAssembly`, `resolvePayloadConstructionShape`, `collectDefaultedSlots`, the bean-arm builder, the supporting helpers that read the user's payload class via the codegen classloader.

- **Delete the emit layer in `TypeFetcherGenerator`.** `emitPayload`, `emitPayloadCtor`, `emitPayloadSetters`. The `MutationField` switch arms keyed on the deleted `DmlReturnExpression.Payload` go with them.

- **Widen `BuildContext.tryResolveSingleRecordCarrier`.** The candidate predicate inside the method body (today at `BuildContext.java:534-535`; locate by symbol) expands to admit `PojoResultType.Backed` alongside `PlainObjectType` and `PojoResultType.NoBacking`. One-line change. The carrier walk then handles all three wrapper states uniformly; the SDL author's `@record(record: {className:})` directive on a wrapper becomes informational metadata, not routing input. Codegen no longer reads `fqClassName` to drive routing for this case. If graphitron ever needs to know the wrapper's Java class for some future purpose, reflection on the actual classpath is the source of truth ; the directive can drift, reflection cannot.

- **Update `validateReturnType.ResultReturnType`'s admission.** Both `fqClassName != null` and `fqClassName == null` arms admit via `tryResolveSingleRecordCarrier`; the existing `fqClassName == null + Rejected` rejection arm extends to cover `fqClassName != null + Rejected` with the same diagnostic shape. The two probes no longer compose; there's one probe (the widened carrier walk) keyed off the SDL shape, not off `fqClassName`'s presence.

  *Validator-rejection-message delta.* Path 2's reflection-based validate-time rejections (`payload class 'X' could not be loaded`, `payload class 'X' has no parameter typed as <RowRecord>`, `payload class 'X' has multiple parameters typed as <RowRecord>`, bean-arm no-row-setter) all retire with `resolveDmlPayloadAssembly`. The carrier walk does not inspect the wrapper's developer-supplied class today, so these specific diagnostics are not preserved; they're either:
   - Re-expressed as a carrier-walk SDL-shape rejection when the underlying defect also shows up at the SDL layer (e.g., a wrapper with no `DataChannel` field rejects via the existing `"' requires a new CarrierFieldRole permit"` arm in `BuildContext.classifyCarrierField`), or
   - Deferred to `mvn compile -pl :graphitron-sakila-example` (the load-bearing-guarantee safety net described in `rewrite-design-principles.adoc` § "Classifier guarantees shape emitter assumptions"): the generated `*Fetchers` source references the wrapper's developer-supplied class only as informational metadata after R161; a misconfigured `className` surfaces at emit-output compile rather than at classify time.

  Acceptable trade per the principle "if graphitron ever needs to know the wrapper's Java class for some future purpose, reflection on the actual classpath is the source of truth ; the directive can drift, reflection cannot". The class-not-loadable diagnostic shifts from `GraphitronSchemaValidator`'s output to `javac`'s; the message quality is comparable and the failure-mode is the same (build fails before any code reaches a consumer).

- **Document the RETURNING(PK) design decision near `buildMutationDmlRecordFetcher`** (declared at `TypeFetcherGenerator.java:3656`; locate by symbol). The carrier-walk emit uses `.returningResult(pkCols).fetchOne()` deliberately: minimize work in the write transaction, do all projection work in a read-only follow-up SELECT outside the transaction. Today the rationale is implicit; a class-level doc comment on the method captures it so future refactors don't "optimize" the follow-up SELECT away by switching to `.returning(*)` and projecting the captured row directly. The same principle applies to `buildMutationBulkDmlRecordFetcher` (`:3923`) and earns the same documentation treatment.

- **Document the `*TableField` ⇒ no-`@record`-return rule.** The structural narrowing falls out of the `DmlReturnExpression` collapse above: with the `Payload` arm gone, `DmlTableField`'s `returnExpression` component is sealed-narrowed to the four non-`@record` arms (ID-encoded single, ID-encoded list, table-projected single, table-projected list). The rule lifts into prose in two places:
  - `graphitron-rewrite/docs/rewrite-design-principles.adoc` § "Narrow component types over broad interfaces": add `*TableField` as a worked example alongside the existing `ServiceTableField` / `PolymorphicReturnType` ones, noting that the sealed-narrowing of `DmlReturnExpression` (not a record-component change) carries the invariant.
  - `graphitron-rewrite/docs/code-generation-triggers.adoc` § "Mutation Fields" (table rows for `MutationInsertTableField` / `MutationUpdateTableField` / `MutationDeleteTableField` / `MutationUpsertTableField`): annotate each row that the `*TableField` permit handles ID-encoded or table-projected returns only; `@record` returns route through `MutationDmlRecordField` / `MutationBulkDmlRecordField` (R75 / R141).

  No further component-type narrowing is on the table for R161: `DmlTableField`'s record component stays typed as `DmlReturnExpression`. R162 (verb-on-permit-identity consolidation) is where additional structural splits land.

- **No rename, no permit split.** `MutationDmlRecordField` and `MutationBulkDmlRecordField` keep their names. The verb-on-permit-identity consolidation (`MutationInsertResultField` / `MutationUpdateResultField` / etc., with the four `DmlTableField` permits and the service-backed pair folding under the same scheme) is tracked separately at **R162** (`mutation-result-field-sealed-on-kind`). R161 is bare retirement.

## Status of the R12 dependency

The carrier walk rejects any wrapper field that doesn't classify into a `CarrierFieldRole` permit. R161 needs the `errors:` carrier field to classify into `CarrierFieldRole.ErrorChannelRole` so wrappers shaped `{ film: Film, errors: [DeleteFilmError] }` route end-to-end after the `BuildContext` widening (test fixture `DML_RECORD_PAYLOAD_RETURN_HAPPY` at `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:5982` is the canonical case).

The producer-side R12 wiring R161 leans on has already landed on trunk (even though R12's overall status remains "In Progress" pending execute-tier coverage). Specifically:

- `CarrierFieldRole.ErrorChannelRole` producer in `BuildContext.classifyCarrierField` (locate by symbol; runs ahead of the `DataChannel` resolution, emits `ErrorChannel.LocalContext`).
- `ChildField.Transport` sealed type (`PayloadAccessor` / `LocalContext`) on `ChildField.ErrorsField`, with `FetcherEmitter.dataFetcherValue`'s `ErrorsField` arm switching on `field.transport()` exhaustively.
- `TypeFetcherGenerator.catchArm`'s sealed switch on `ErrorChannel` with `PayloadClass` ⇒ `ErrorRouter.dispatch` and `LocalContext` ⇒ `dispatchToLocalContextCatchArm` arms; the latter emits `DataFetcherResult.newResult().data(null).localContext(List.of(t)).build()`.
- `MappingsConstantNameDedup`'s grouping switch is sealed-aware over `ErrorChannel`, and its `withResolvedChannel` rewrite switch carries arms for `MutationDmlRecordField` and `MutationBulkDmlRecordField`.
- `LoadBearingClassifierCheck(key = "error-channel.local-context-transport")` producer / matching `DependsOnClassifierCheck` consumers wired and audited by `LoadBearingGuaranteeAuditTest`.

What's still pending on R12 (per its In Progress plan) does not block R161: it's primarily new execute-tier coverage (`ResultAssembly.Assembly` end-to-end for service-method-returns-domain-object, and `ValidationHandler` end-to-end gated on R94's input-record emission). R161 can begin once Ready; sequencing remains R12 → R161 for orderly handoff, but the structural blocker is gone.

## Test migration

After R12 + R161 land, Path 2's test fixtures route through the new admissions or get deleted (line numbers as of trunk; locate by name if drifted):

- `DML_RECORD_PAYLOAD_RETURN_HAPPY` (`GraphitronSchemaBuilderTest.java:5982`) — wrapper with `film: Film` + `errors: [...]`. Admits via carrier walk + the R12 `ErrorChannelRole` producer. Re-targeted as a carrier-walk-with-errors test, or folded into R12's coverage.
- `DML_RECORD_PAYLOAD_ROW_ONLY_HAPPY` (`:6013`) — wrapper with `film: Film` only. Admits via the widened carrier walk directly (no errors channel needed). Re-targeted as a carrier-walk-with-Backed-wrapper test, or folded into R75's coverage.
- `DML_RECORD_PAYLOAD_LIST_REJECTED` (`:6032`) — list-of-payload return. Rejection reason changes (Invariant #14 from `validateReturnType` instead of from `resolveDmlPayloadAssembly`); update the assertion's expected message.
- `DML_RECORD_PAYLOAD_NO_ROW_SLOT_REJECTED` (`:6049`) — payload class with no row-typed ctor parameter. *Deleted*; the rejection is no longer reachable (the user's class is no longer inspected). The fixture's SDL shape (wrapper with `data: String` + `errors:`, where `data` isn't a `@record`-returning data field) gets rejected by the carrier walk for a different reason: no DataChannel field. Optionally re-targeted as a carrier-walk-rejection test.
- `dmlDeleteField_recordPayloadReturn_successArmConstructsPayloadAndCatchArmDispatches` (`FetcherPipelineTest.java:412`) and `dmlDeleteField_recordPayloadReturnNoErrorsField_successArmConstructsPayloadCatchArmRedacts` (`:433`) — emit-side assertions for Path 2's `new Payload(row, null, ...)` body. *Deleted*; the body no longer exists. DELETE-with-record-wrapper coverage lives in R156's carrier-walk DELETE fixtures.
- `dmlMutation_setterShapePayload_emitsSetterFactory` (`:367`) — emit-side assertion for Path 2's setter-shape body. *Deleted* for the same reason.

After migration: zero references to `DmlReturnExpression.Payload`, `PayloadAssembly`, `RowSlot`, `emitPayload*`, or `resolveDmlPayloadAssembly` anywhere in the codebase.

*Execution-tier coverage.* Not added by R161. The carrier walk's execution behavior is already pinned by sakila's `FilmPayload` mutations (`createFilmPayload` / `updateFilmPayload`), which route a `PlainObjectType`-promoted-to-`PojoResultType.NoBacking` wrapper through the same per-field DataFetcher emission R161 widens to admit `PojoResultType.Backed`. Post-R161 the Backed path runs the same emitter code; the only difference is that `BuildContext.tryResolveSingleRecordCarrier` admits a new candidate type. Pipeline-tier re-targeting of `DML_RECORD_PAYLOAD_ROW_ONLY_HAPPY` and `DML_RECORD_PAYLOAD_RETURN_HAPPY` pins the admission; consumer-compile of the existing sakila wrappers pins the emit-shape. A bespoke sakila Backed-wrapper-DML fixture would assert "the wrapper carries `@record` metadata but execution is unchanged", which is structural not behavioral coverage. If a future audit surfaces a genuine emit-time divergence between the NoBacking and Backed cases, that gap warrants its own execute-tier fixture, but R161 does not introduce one.

## Out of scope

- **Consolidating `MutationField` permits under verb-on-permit-identity.** Tracked at R162 (`mutation-result-field-sealed-on-kind`). That item lands the seven-permit landscape (`MutationInsertResultField`, `MutationUpdateResultField`, `MutationMultiRowUpdateResultField`, `MutationDeleteResultField`, `MutationMultiRowDeleteResultField`, `MutationUpsertResultField`, `MutationServiceResultField`) with the four `DmlTableField` permits, the post-R161 carrier-walk permits, and the service-backed pair all folding under the verb-named umbrella via a sealed `Result` discriminator. R161 leaves the carrier-walk permits' names alone; the consolidation does the rename.

## Relationship to R12 / R158 / R159

R12 (`error-handling-parity`) is the structural dependency per the section above. R12's classifier-producer and consumer-side wiring R161 leans on have already landed on trunk; R161 is unblocked. R12 remains "In Progress" pending execute-tier coverage that R161 does not touch.

R158 (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) and R159 (the semantic admission question for payload carriers) sit on the consumer-side data-field axis (`ChildField.SingleRecordTableField`'s producer). R161 sits on the producer-side classifier axis. R158 inherits the cleaner invariant downstream — once R161 lands, `MutationInsertTableField` etc. never carry `@record`, so any future reasoning about the `MutationField` ↔ `ChildField.SingleRecordTableField` producer-consumer pair lives entirely inside the carrier-walk permits. But R158 doesn't *require* R161: `TableTargetField`'s sealed-interface contract already enforces `TableBoundReturnType returnType` on `SingleRecordTableField`, which is the narrower invariant R158 actually leans on.

R12, R158, R159, R161, R162 can land in any order modulo the structural dependencies: R161 → R12 (R12's carrier-walk producer + LocalContext wiring before R161, now satisfied on trunk), R162 → R161.
