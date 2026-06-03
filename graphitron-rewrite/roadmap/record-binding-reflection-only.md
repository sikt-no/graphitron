---
id: R276
title: Record binding is reflection-only and sound; remove @record reads, ground all producers, eliminate PlainObjectType
status: In Progress
bucket: cleanup
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-06-02
last-updated: 2026-06-03
---

# Record binding is reflection-only; remove @record-directive-consulting code

The `@record` directive is deprecated and ignored: graphitron derives every result and input backing class from the producing field's reflected return type (R96), and `TypeBuilder.emitDirectiveIgnoredWarnings` already warns the author that the directive does nothing. Despite that, several classifier sites still *read* `@record` to drive binding and type classification. That residual coupling is a standing bug source, and it actively produces a wrong classification for the source-record-carrier shape.

The load-bearing case: `RecordBindingResolver.groundServiceField` gates the result-axis producer observation on `resultObjType.hasAppliedDirective(DIR_RECORD)` (`RecordBindingResolver.java:240`). So a `@service` field whose method returns a jOOQ `TableRecord` into a payload that carries no `@record` (the only supported authoring style) never grounds a result binding; the payload stays a `PlainObjectType` instead of binding to the producer's record type. A classification probe confirms it: for `makeFilm: MakeFilmPayload @service(...returns FilmRecord)` with `MakeFilmPayload { film: Film @splitQuery, errors: [FilmErr] }`, `MakeFilmPayload` classifies as `PlainObjectType`, the field resolves `errorChannel: Optional.empty`, and the inner `film` field gets no datafetcher. This is the direct blocker for R275: the carrier never becomes a `ResultType`, so no error channel attaches and the inner data field never classifies.

> **Reopened In Review -> In Progress (2026-06-03).** The first cut (removing the four `@record`
> binding reads, D1/D2/D3, the test migration) shipped to trunk and **broke main**: removing
> `@record` binding without a complete reflection-only replacement left whole shapes unbound, and
> the classifier admits unbound types silently, so the breakage surfaced at execution time, not
> build time. "Binding is reflection-only" is not finished until the replacement is *complete*
> (every producer kind grounds a binding) and *sound* (an unbound type or field is impossible, it
> fails the build rather than degrading to a runtime null). Both are now owned here; see
> [Completion](#completion-binding-completeness-and-classification-soundness). The `@tableMethod`-
> under-`NestingField` generator gap that surfaced alongside is carved out to
> tablemethod-under-nested-type (R277). The carrier model and `NoBacking` removal, originally
> folded into R275, move here too, since they are part of making binding sound.

## Target

Binding is reflection-only. No classifier or binding-resolver code reads `@record` to decide a backing class or a type's classification kind. With the gate removed, the `@service` producer's reflected return element grounds the result observation unconditionally (still subject to the existing `shouldBind` predicate), so a source-record-carrier payload binds to its producer's record type and classifies as a `JooqTableRecordType` (a `ResultType`). The deprecation surface stays intact: `@record` remains a registered, parseable directive (existing schemas still load) and `emitDirectiveIgnoredWarnings` still tells authors it is ignored.

## `@record`-consulting sites

Remove (these read the directive to drive binding/classification):

* `RecordBindingResolver.groundServiceField` (`~236-248`): the `sdlHasRecord` gate on the result-axis `RootService` observation. Replace it with a **cardinality-match** guard, not unconditional grounding. Bind the SDL return type to the producer's reflected return element only when the SDL field's cardinality matches the Java return's cardinality (both single, or both a list/collection). This is the load-bearing change that makes a single-record carrier payload bind to its record (a `JooqTableRecordType`). Grounding unconditionally is wrong: a single-object SDL field produced by a *collection* return (e.g. `payload: FooPayload @service` whose method returns `List<BarRecord>`) is a list carrier whose collection feeds the payload's inner list field, not the wrapper; binding the wrapper to the element type breaks that R75 carrier path (regression-caught by `R96RecordBindingPipelineTest.plainCarrier_serviceReturnDoesNotBindWrapperType_R75CarrierPathPreserved`). The cardinalities always agree for `@record` payloads (single->single, list->list), so the guard preserves their binding unchanged.
* `TypeBuilder.classifyType` (`576`): the `|| objType.hasAppliedDirective(DIR_RECORD)` arm. A type classifies as a `ResultType` only when it has a reflected producer binding.
* `TypeBuilder.buildResultType` (`~893-917`): the directive-`className` fallback (the `readRecordClassName` branch and its arg-mapping inertness check). Keep only the reflected-class path.
* `TypeBuilder.buildInputType` (`~1085-1100`): the analogous input-side directive-`className` fallback.

Keep (these are the deprecation itself, not binding behavior):

* `GraphitronSchemaBuilder.java:476` `assertDirective(DIR_RECORD)`: keeps `@record` a parseable directive so existing schemas still load.
* `TypeBuilder.emitDirectiveIgnoredWarnings` (`300-358`) and its helper `readRecordClassName` (`366-373`): read the directive only to emit the "directive ignored" warning, never to bind.

## Decisions (resolved at Spec → Ready review)

* **D1, `detectTypeDirectiveConflict` `@record` arm (`TypeBuilder.java:1438`).** Today `@table` + `@record` and `@error` + `@record` are hard conflicts. Under full deprecation, rejecting a schema because `@record` is *present* is itself reading the directive to drive behavior. Recommendation: drop `@record` from `detectTypeDirectiveConflict`, leaving `@table` vs `@error` mutually exclusive; the `@table`/`@error` + `@record` combinations then fall through to the existing ignored-directive warning (the input-side `@table` + `@record` warning at `emitDirectiveIgnoredWarnings:326` already exists; the OBJECT side would warn instead of reject). Alternative: keep the conflict to forbid ambiguous combinations. **Resolved (review):** drop `@record` from the `present` count (the recommendation); rejecting on directive *presence* contradicts full deprecation, and the ignored-directive warning already carries the "remove it" signal. Implementer note: update the now-stale comment at `TypeBuilder.java:325` that claims the OBJECT-side `@table` + `@record` combination is rejected before the warning site reached.
* **D2, `ServiceEmitted` reconciliation.** R178's `RecordBindingResolver.groundServicePayloadBinding` grounds a separate `ServiceEmitted` memo (consumed as `ChildField.SingleRecordTableField` with `Wrap.TableRecord`) for a carrier's inner `@table` field, on the assumption the payload stays unbound. Once the payload binds as a `JooqTableRecordType`, its inner field classifies through `FieldBuilder.classifyChildFieldOnResultType` instead, so the `ServiceEmitted` path is redundant for this shape (and possibly entirely). Recommendation: confirm the result-axis binding subsumes it and remove the now-dead `ServiceEmitted` machinery as part of this item; if it covers a distinct surviving case, scope that down rather than carry two inner-field mechanisms. This decision determines whether R275's inner-field projection rides `classifyChildFieldOnResultType` (preferred) or the legacy `SingleRecordTableField`. **Resolved (review):** subsume-and-remove is the target, but gated on an explicit step — enumerate the consumers of the `ServiceEmitted` memo (`RecordBindingResolver.resolveServiceEmitted`, `TypeBuilder.serviceEmittedBinding`, and the `serviceEmittedMemo` writers) before deleting. If every consumer is the carrier-inner-field path now served by `classifyChildFieldOnResultType`, delete the machinery; if a distinct case survives, scope that case down rather than carry two inner-field mechanisms. R275's inner-field projection rides `classifyChildFieldOnResultType`.
* **D3, bare-`@record` with no producer.** After removing the `classifyType:576` arm, a type carrying only a bare `@record` and produced by no field becomes a `PlainObjectType` (today it enters `buildResultType` and becomes `PojoResultType.NoBacking`). Confirm acceptable; it matches "directive ignored," and such a type has no producer to read anyway. **Resolved (review):** acceptable (matches "directive ignored"), but pin the changed classification with a test (see Tests) so the `NoBacking` → `PlainObjectType` shift is intentional and regression-guarded, not silent.

## Implementation

* Apply the four removals above. After (2), `buildResultType` is only reached for types with a reflected binding, so the fallback removed in (3) is dead; delete it rather than leave it unreachable.
* Apply D1, D2, and D3 per the resolved decisions above (drop `@record` from `detectTypeDirectiveConflict`; enumerate `ServiceEmitted` consumers then subsume-and-remove or scope down; let bare-`@record`-no-producer fall to `PlainObjectType`).
* Migrate fixtures and tests that lean on `@record(record: {className: ...})` *without* a reflected producer to either declare the producing field or drop the orphan type. `ErrorChannelClassificationTest.unTypedRecordPayload_producesChannelFromReflectedProducer` already asserts the reflection path and should survive unchanged or with a comment update; any test asserting the directive-`className` fallback as the *sole* binding source is removed.

## Tests

* **Pipeline (`@PipelineTier`).** A source-record-carrier `@service` (method returns a jOOQ `TableRecord`; payload carries that table's `@table` field plus an `errors` field, no `@record`) binds the payload to the producer's record class and classifies it as a `JooqTableRecordType`. Promote the throwaway `R275ProbeTest` shape into a kept assertion here.
* **Pipeline (`@PipelineTier`).** `@record(record: {className: X})` is ignored when the reflected producer binds a different class Y: the type binds to Y, and the deprecation warning fires.
* **Unit (`@UnitTier`).** Per D1 (resolved: warn, don't reject): a type carrying `@table` + `@record` (and `@error` + `@record`) produces the ignored-directive warning rather than a `DirectiveConflict` rejection, with that behavior pinned.
* **Pipeline (`@PipelineTier`).** Per D3: a type carrying a bare `@record` with no producing field classifies as a `PlainObjectType` (not `PojoResultType.NoBacking`), pinning the deliberate classification shift.
* **Compilation (`@CompilationTier`).** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green end-to-end after the fixture migration.

## Coordination

* **R275 depends on this.** Once R276 lands, the source-record-carrier payload is a `JooqTableRecordType` / `ResultReturnType`, `resolveServiceOutcomeChannel` attaches `ErrorChannel.Mapped`, and the inner `@table @splitQuery` field classifies via `classifyChildFieldOnResultType`. R275 then owns the channel emit, the inner-field success-branch DataLoader, the bucket-C success-arm `errors: null`, and the new errors-nullable rejection rule. R275's failure-map "current state" and Implementation step 1 are corrected in tandem to drop the inaccurate "`MutationServiceTableField` over `TableBoundReturnType`" framing.
* **R277 (tablemethod-under-nested-type, Backlog).** Carved out of the completion work: `@tableMethod` under a table-bound `NestingField` is not wired in the generator (the nested-fetcher machinery keys on `BatchKeyField`, and `TableMethodField` is `MethodBackedField` but not `BatchKeyField`). R276 only defers it (removes the `languageViaTableMethod` child, disables the execution test); R277 implements the generator support and re-enables the fixture.
* **R222 (adjacent).** The author's planned classification-hierarchy cleanup. The completion work here now removes `PlainObjectType` and `PojoResultType.NoBacking`, which *is* hierarchy restructuring, so coordinate that slice with R222 rather than treating them as disjoint: R276 takes the two deletions its soundness invariant requires, R222 keeps any broader restructuring. Confirm the boundary with the R222 author at the Spec -> Ready gate.

## Completion: binding completeness and classification soundness

Added 2026-06-03 after the first cut broke main. This section owns making the reflection-only model complete and sound.

### Root cause

`TypeBuilder.classifyType` (`~578-581`) routes any reachable object type with no `@table`/`@error`/producer binding to `PlainObjectType`, the only fall-through. `GraphitronSchemaValidator` then accepts it (`:88`, "no domain directives, nothing to validate structurally"), and its fields resolve through graphql-java's default `PropertyDataFetcher`, a reflective read off `env.getSource()` that returns `null` (or an un-lifted partial record) at runtime. Two failures compound: the reflection-only binding does not ground *every* producer kind, and when a type consequently loses its binding, `PlainObjectType` swallows it silently instead of failing the build. That is why removing `@record` binding surfaced as execution-time regressions, not classification errors.

### Binding completeness: ground `@externalField` + the parent-accessor cascade

The field-walk grounding loop (`RecordBindingResolver:~204-211`) calls `groundServiceField` / `groundTableMethodField` / `groundDmlMutationField`, never a `groundExternalField`, so `@externalField` return types get no result-axis binding. Add `groundExternalField`: reflect the `@externalField` method's return element (`ServiceCatalog.reflectExternalField` already exists) and ground a result observation under the same cardinality-match guard as `groundServiceField`. Once the return type binds, the existing `ProducerBinding.ParentAccessor` walk (`~527`) cascades down its accessor-reached children, so the whole chain grounds (`FilmCardWrapper` -> `FilmCardData`; `FilmCardData.film()` -> `FilmRecord`; `FilmCardData.example()` -> `RecordExampleType` -> `RecordExample`). This single grounding is the root fix for the `FilmCardWrapper.film` AccessorKeyedSingle and `RecordExample.fieldC` `@field`-rebind execution regressions.

### Classification soundness: eliminate `PlainObjectType`

Target invariant: **no reachable object type may remain a `PlainObjectType` after classification.** Each binds to a concrete variant or becomes an `UnclassifiedType`, which the validator already rejects at build time (`:958` / `:966`). `PlainObjectType` is removed as a terminal classification. Its four current roles are each re-homed:

* **Catch-all for unbound** (`TypeBuilder:581`): route to `UnclassifiedType` with a precise reason (the type name and why no binding was found) so it fails the build instead of degrading at runtime.
* **Nested DTOs under a `@table` parent:** already resolved at the field level via the table-bound `NestingField` (`FieldBuilder.classifyObjectReturnChildField`); the type-level entry follows the same table binding, not `PlainObjectType`.
* **Transient staging for connection/PageInfo promotion** (`ConnectionPromoter:126/157`): classify these structurally up front, or keep an internal pre-promotion marker guaranteed to be promoted before classification completes, so nothing *terminal* is `PlainObjectType`.
* **LSP backing shape** (`CatalogBuilder:631` -> `TypeBackingShape.NoBacking.UnbackedResult`): re-home the LSP taxonomy entry alongside the model change. This is the path `R157PipelineTest` exercises.

### Drop `PojoResultType.NoBacking` (moved here from R275)

A DML `RETURNING` yields a jOOQ `Record` / `Result<Record>`, and an `@service` source-record carrier yields a table record; neither is `NoBacking`. There is no valid schema that classifies as `NoBacking`, so it is removed: `TypeBuilder.promoteSingleRecordPayloads` binds the carrier to its `JooqTableRecordType` instead, and `PojoResultType` collapses to `Backed`. Delete `TypeClassification.UnbackedPojoResult` and `TypeBackingShape.NoBacking.UnbackedResult`; drop the `NoBacking` arms in `FetcherEmitter`, `FieldBuilder`, `GraphitronSchemaBuilder`, `CatalogBuilder`, and the `VariantCoverageTest` / `ProjectionCoverageTest` entries for the removed variants.

### Example-schema and test reconciliation

* **Case 2** (NoBacking carrier): `SingleFilmCardCarrier` + `createFilmCard` removed from `schema.graphqls` (done). `FilmCardData` survives (it backs `FilmCardWrapper` via `@externalField`); `FilmCardFactoryService` is dead, optional cleanup.
* **Case 3** (`@tableMethod` under `NestingField`): `languageViaTableMethod` removed from `FilmDetailsForMethod`, the execution test disabled, carved to tablemethod-under-nested-type (R277) (done).
* **#2/#3** (`FilmCardWrapper.film`/`example` null): fixed by `groundExternalField` + the cascade.
* **#4** (`FilmDetails.language` round-trip count): `FilmDetails` reclassifies from `JooqTableRecordType` to a table-bound `NestingField`, so `language` is an inline to-one `TableField` (1 round trip), not a record-keyed DataLoader (2). The `RecordTableField`-batching coverage belongs on a still-record-backed fixture (`FilmCardWrapper.film`, AccessorKeyedSingle); update or relocate `recordTableField_multipleParents` / `recordLookupTableField_multipleParents` accordingly, after confirming the data is correct on the inline path.
* **LSP** (`R157PipelineTest`): the two backing-shape tests migrated to `@service` producers (done); the `CatalogBuilder` taxonomy re-home keeps them aligned.

### Implementation seams

* `RecordBindingResolver`: add `groundExternalField` to the field-walk loop; cardinality-match guard mirroring `groundServiceField`.
* `TypeBuilder.classifyType`: replace the `PlainObjectType` catch-all with `UnclassifiedType`-with-reason for genuinely-unbound reachable objects; route nested-DTO and connection/PageInfo cases to their real classifications.
* Delete `GraphitronType.PlainObjectType` and `PojoResultType.NoBacking`; update every consumer (`ObjectTypeGenerator`, `FetcherEmitter`, `FieldBuilder`, `GraphitronSchemaBuilder`, `CatalogBuilder`, `EntityResolutionBuilder`, `FetcherRegistrationsEmitter`, `ConnectionPromoter`, `GraphitronSchemaValidator`).
* `TypeBuilder.promoteSingleRecordPayloads`: bind the carrier to its `JooqTableRecordType`.

### Tests and acceptance

* **Soundness (Unit/Pipeline).** A reachable object type with no binding is rejected as `UnclassifiedType`, never silently accepted; pin the message. A meta-assertion that `GraphitronType.PlainObjectType` no longer exists as a variant.
* **Binding (Execution).** `FilmCardWrapper.film` resolves the lifted Film row (`title` non-null); `RecordExample.fieldC` resolves the `@field` rebind.
* **Acceptance.** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` green end-to-end across `graphitron`, `graphitron-lsp`, and `graphitron-sakila-example`, un-breaking trunk. This is the gate for moving back to In Review.

## Out of scope

* The error-channel and data-projection wiring for the carrier (R275).
* Removing `@record` from the SDL grammar or unregistering the directive; it stays parseable, with the ignored-directive warning, so existing schemas keep loading.
