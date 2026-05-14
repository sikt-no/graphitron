---
id: R161
title: "Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path"
status: Spec
bucket: structural
priority: 3
theme: mutations-errors
depends-on: [error-handling-parity]
created: 2026-05-13
last-updated: 2026-05-14
---

# Retire DmlReturnExpression.Payload: unify @record-returning DML on the carrier-walk path

Two parallel designs exist today for `@record`-returning DML mutations. R75 + R141 introduced the carrier-walk path (`MutationField.MutationDmlRecordField` / `MutationBulkDmlRecordField`); the older reflective-payload path (`DmlReturnExpression.Payload` consumed by the four `DmlTableField` permits) stayed in place, and the two compete for the same SDL shapes at classify time. The duplication is structurally unprincipled: Path 2 reflectively constructs the user's payload class with the row dropped into a constructor slot and defaults elsewhere, then hands the typed instance to graphql-java as a source value; the carrier walk hands graphql-java a PK record and lets per-field DataFetchers project. Both wire through graphql-java's field resolution, but Path 2 leans on `PropertyDataFetcher` reading from a typed Java source (the eagerly-assembled DTO), while Path 1 contributes explicit per-field DataFetchers. graphql-java's per-field wiring is the right abstraction here; reflective DTO assembly is a workaround that pre-materializes a courier whose only function is to satisfy `PropertyDataFetcher`. R161 retires Path 2 entirely.

After R161, `Mutation*TableField` permits are guaranteed to never carry a `@record` return — the `DmlReturnExpression.Payload` arm that admitted them is gone, and the type system enforces narrowness structurally rather than via classifier-acceptance shape.

## Concrete state today

- `DmlReturnExpression` is a sealed type with five arms; the `Payload` arm wraps a `PayloadAssembly` (`payloadClassName`, `rowSlotType`, `rowSlot ∈ {CtorParameterIndex, SetterMethod}`, `defaultedSlots`) — the validated wiring recipe between the DML's typed jOOQ row record and the user's payload-class construction.
- `FieldBuilder.resolveDmlPayloadAssembly` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/FieldBuilder.java:1991-2061`) does reflection + jOOQ catalog lookup + SDL field walk to produce the assembly. Rejection cases include class-not-loadable, no row-typed parameter, multiple row-typed parameters, unresolvable construction shape, bean-arm with no row setter.
- `TypeFetcherGenerator.emitPayload` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:3075-3098`) emits `var payload = new FilmPayload(row, null, ...)` literal DTO construction, with sibling functions `emitPayloadCtor` and `emitPayloadSetters` handling the two construction shapes.
- `FieldBuilder.classifyMutationField` routes to `MutationDmlRecordField` / `MutationBulkDmlRecordField` when `BuildContext.tryResolveSingleRecordCarrier(returnTypeName) == Ok`; otherwise it falls through to `buildDmlField` which calls `buildDmlReturnExpression`. The `ResultReturnType` branch constructs `new DmlReturnExpression.Payload(assembly)`, and the resulting `MutationInsertTableField` (or sibling) carries a `@record` return inside its `returnExpression`.
- `BuildContext.tryResolveSingleRecordCarrier` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/BuildContext.java:516-517`) admits `PlainObjectType` or `PojoResultType.NoBacking`, not `PojoResultType.Backed`. So `@record(record: {className:})` wrappers route to Path 2 today.
- `MutationInputResolver.validateReturnType` (`graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/MutationInputResolver.java:169-260`) admits `ResultReturnType` whenever `fqClassName != null` (Path 2) or `fqClassName == null` and `tryResolveSingleRecordCarrier == Ok` (Path 1). The two probes are disjoint today: `fqClassName != null` implies `PojoResultType.Backed`, which the carrier walk currently rejects.

## Spec direction

- **Delete `DmlReturnExpression.Payload`.** The sealed type collapses to four arms (`EncodedSingle`, `EncodedList`, `ProjectedSingle`, `ProjectedList`). `buildDmlReturnExpression`'s signature loses its `Optional<PayloadAssembly>` parameter; the `ResultReturnType` branch in `buildDmlReturnExpression` becomes unreachable from `buildDmlField` and is deleted. The Javadoc claim about totality over Invariant #14's admitted set is rephrased without the `ResultReturnType` row.

- **Delete the `PayloadAssembly` subsystem in the model package.** `PayloadAssembly`, `RowSlot.CtorParameterIndex`, `RowSlot.SetterMethod`, `BoundSetter`, `NonBoundSetter`, `PayloadConstructionShape.AllFieldsCtor`, `PayloadConstructionShape.MutableBean`, `DmlPayloadAssemblyResult.Assembly`, `DmlPayloadAssemblyResult.Reject`, `DmlPayloadAssemblyResult.NO_ASSEMBLY` all go. No consumers remain.

- **Delete the reflection layer in `FieldBuilder`.** `resolveDmlPayloadAssembly`, `resolvePayloadConstructionShape`, `collectDefaultedSlots`, the bean-arm builder, the supporting helpers that read the user's payload class via the codegen classloader.

- **Delete the emit layer in `TypeFetcherGenerator`.** `emitPayload`, `emitPayloadCtor`, `emitPayloadSetters`. The `MutationField` switch arms keyed on the deleted `DmlReturnExpression.Payload` go with them.

- **Widen `BuildContext.tryResolveSingleRecordCarrier`.** The candidate predicate at `:516-517` expands to admit `PojoResultType.Backed` alongside `PlainObjectType` and `PojoResultType.NoBacking`. One-line change. The carrier walk then handles all three wrapper states uniformly; the SDL author's `@record(record: {className:})` directive on a wrapper becomes informational metadata, not routing input. Codegen no longer reads `fqClassName` to drive routing for this case. If graphitron ever needs to know the wrapper's Java class for some future purpose, reflection on the actual classpath is the source of truth — the directive can drift, reflection cannot.

- **Update `validateReturnType.ResultReturnType`'s admission.** Both `fqClassName != null` and `fqClassName == null` arms admit via `tryResolveSingleRecordCarrier`. The two probes no longer compose; there's one probe (the widened carrier walk) keyed off the SDL shape, not off `fqClassName`'s presence. Rejection diagnostics come from the carrier walk itself when the SDL shape is malformed.

- **Document the RETURNING(PK) design decision near `buildMutationDmlRecordFetcher`** (`TypeFetcherGenerator.java:3643`). The carrier-walk emit uses `.returningResult(pkCols).fetchOne()` deliberately: minimize work in the write transaction, do all projection work in a read-only follow-up SELECT outside the transaction. Today the rationale is implicit; a class-level doc comment on the method captures it so future refactors don't "optimize" the follow-up SELECT away by switching to `.returning(*)` and projecting the captured row directly. The same principle applies to `buildMutationBulkDmlRecordFetcher` and earns the same documentation treatment.

- **No rename, no permit split.** `MutationDmlRecordField` and `MutationBulkDmlRecordField` keep their names. The verb-on-permit-identity consolidation (`MutationInsertResultField` / `MutationUpdateResultField` / etc., with the four `DmlTableField` permits and the service-backed pair folding under the same scheme) is tracked separately at **R162** (`mutation-result-field-sealed-on-kind`). R161 is bare retirement.

## Why R12 is a hard dependency

The carrier walk rejects any wrapper field that doesn't classify into a `CarrierFieldRole` permit. Today the walk produces `DataChannel` permits only; `ErrorChannelRole`'s classifier producer is R12 (`error-handling-parity`). Test fixture `DML_RECORD_PAYLOAD_RETURN_HAPPY` (`graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/rewrite/GraphitronSchemaBuilderTest.java:5973`) uses the canonical wrapper shape `{ film: Film, errors: [DeleteFilmError] }`. After Path 2 retirement and the `BuildContext` widening, this shape routes through carrier walk — and the walk rejects on the `errors:` field today, because nothing classifies it. R12 lands the classifier producer that admits it.

R12 must land first. The alternative (R161 ships with a temporary admission carved out for plain wrappers only, R12 lifts the restriction afterward) introduces a regression window for every wrapper with an errors channel, and the migration story for Path 2 tests becomes incoherent during the window. Sequencing R12 → R161 is the clean path.

## Test migration

After R12 + R161 land, Path 2's test fixtures route through the new admissions or get deleted:

- `DML_RECORD_PAYLOAD_RETURN_HAPPY` (`GraphitronSchemaBuilderTest.java:5973`) — wrapper with `film: Film` + `errors: [...]`. Admits via carrier walk + the R12 `ErrorChannelRole` producer. Re-targeted as a carrier-walk-with-errors test, or folded into R12's coverage.
- `DML_RECORD_PAYLOAD_ROW_ONLY_HAPPY` (`:6003`) — wrapper with `film: Film` only. Admits via the widened carrier walk directly (no errors channel needed). Re-targeted as a carrier-walk-with-Backed-wrapper test, or folded into R75's coverage.
- `DML_RECORD_PAYLOAD_LIST_REJECTED` (`:6022`) — list-of-payload return. Rejection reason changes (Invariant #14 from `validateReturnType` instead of from `resolveDmlPayloadAssembly`); update the assertion's expected message.
- `DML_RECORD_PAYLOAD_NO_ROW_SLOT_REJECTED` (`:6039`) — payload class with no row-typed ctor parameter. *Deleted*; the rejection is no longer reachable (the user's class is no longer inspected). The fixture's SDL shape (wrapper with `data: String` + `errors:`, where `data` isn't a `@record`-returning data field) gets rejected by the carrier walk for a different reason: no DataChannel field. Optionally re-targeted as a carrier-walk-rejection test.
- `dmlDeleteField_recordPayloadReturn_successArmConstructsPayloadAndCatchArmDispatches` (`FetcherPipelineTest.java:412`) and `dmlDeleteField_recordPayloadReturnNoErrorsField_successArmConstructsPayloadCatchArmRedacts` (`:433`) — emit-side assertions for Path 2's `new Payload(row, null, ...)` body. *Deleted*; the body no longer exists. DELETE-with-record-wrapper coverage lives in R156's carrier-walk DELETE fixtures.
- `dmlMutation_setterShapePayload_emitsSetterFactory` (`:367`) — emit-side assertion for Path 2's setter-shape body. *Deleted* for the same reason.

After migration: zero references to `DmlReturnExpression.Payload`, `PayloadAssembly`, `emitPayload*`, or `resolveDmlPayloadAssembly` anywhere in the codebase.

## Out of scope

- **Consolidating `MutationField` permits under verb-on-permit-identity.** Tracked at R162 (`mutation-result-field-sealed-on-kind`). That item lands the seven-permit landscape (`MutationInsertResultField`, `MutationUpdateResultField`, `MutationMultiRowUpdateResultField`, `MutationDeleteResultField`, `MutationMultiRowDeleteResultField`, `MutationUpsertResultField`, `MutationServiceResultField`) with the four `DmlTableField` permits, the post-R161 carrier-walk permits, and the service-backed pair all folding under the verb-named umbrella via a sealed `Result` discriminator. R161 leaves the carrier-walk permits' names alone; the consolidation does the rename.
- **Documentation lift of the `*TableField` ⇒ `TableBoundReturnType` rule.** Once `DmlReturnExpression.Payload` is gone, `*TableField` permits are guaranteed to never carry a `@record` return. The documentation update (`graphitron-rewrite/docs/rewrite-design-principles.adoc` "Narrow component types" + `code-generation-triggers.adoc`) follows the structural lift; tracked as a follow-up.

## Relationship to R12 / R158 / R159

R12 (`error-handling-parity`) is a hard `depends-on` per the section above. The R161 lift can't admit the canonical wrapper-with-errors shape until R12's `ErrorChannelRole` classifier producer lands in the carrier walk.

R158 (`SourceKey.Reader` sub-taxonomy for `@service`-backed producers) and R159 (the semantic admission question for payload carriers) sit on the consumer-side data-field axis (`ChildField.SingleRecordTableField`'s producer). R161 sits on the producer-side classifier axis. R158 inherits the cleaner invariant downstream — once R161 lands, `MutationInsertTableField` etc. never carry `@record`, so any future reasoning about the `MutationField` ↔ `ChildField.SingleRecordTableField` producer-consumer pair lives entirely inside the carrier-walk permits. But R158 doesn't *require* R161: `TableTargetField`'s sealed-interface contract already enforces `TableBoundReturnType returnType` on `SingleRecordTableField`, which is the narrower invariant R158 actually leans on.

R12, R158, R159, R161, R162 can land in any order modulo the hard dependencies: R161 → R12 (R12 before R161), R162 → R161.
