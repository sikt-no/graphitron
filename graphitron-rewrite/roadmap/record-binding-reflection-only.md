---
id: R276
title: Record binding is reflection-only and sound; remove @record reads, ground all producers, eliminate PlainObjectType
status: In Progress
bucket: cleanup
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-06-02
last-updated: 2026-06-05
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

### Binding completeness: ground `@externalField` + the parent-accessor cascade — shipped (468b86e, 9f53f36)

The field-walk grounding loop (`RecordBindingResolver`) gained `groundComputedField` (the `@externalField` / `ChildField.ComputedField` grounding the spec sketched as `groundExternalField`): it reflects the method's `org.jooq.Field<X>` return element and grounds a `RootService` result observation through the shared `groundProducerResult` helper, under the same cardinality-match and `@table`-backed-SDL guards as `groundServiceField`. `propagateAccessorChains` now calls `foldAll()` first so the `ProducerBinding.ParentAccessor` walk reads concrete bindings on its first pass and cascades down accessor-reached children (`FilmCardWrapper` -> `FilmCardData`; `FilmCardData.film()` -> `FilmRecord`; `FilmCardData.example()` -> `RecordExampleType` -> `RecordExample`), with a guard that skips re-grounding an already-bound child. This is the root fix for the `FilmCardWrapper.film` AccessorKeyedSingle and `RecordExample.fieldC` `@field`-rebind execution regressions.

### Classification soundness: eliminate `PlainObjectType` — shipped (19681e5, 4ad9030)

Target invariant: **no reachable object type may remain a `PlainObjectType` after classification.** Each binds to a concrete variant or becomes an `UnclassifiedType`, which the validator already rejects at build time. `PlainObjectType` is removed as a terminal classification (it survives only as a Javadoc reference at `TypeBuilder.java:480`); the `4ad9030` "don't crash on double-classification" guard hardens the path that surfaces a heterogeneous accessor as an author-error `UnclassifiedType` rather than a crash. Its four roles were each re-homed:

* **Catch-all for unbound** (`TypeBuilder:581`): route to `UnclassifiedType` with a precise reason (the type name and why no binding was found) so it fails the build instead of degrading at runtime.
* **Nested DTOs under a `@table` parent:** already resolved at the field level via the table-bound `NestingField` (`FieldBuilder.classifyObjectReturnChildField`); the type-level entry follows the same table binding, not `PlainObjectType`.
* **Transient staging for connection/PageInfo promotion** (`ConnectionPromoter:126/157`): classify these structurally up front, or keep an internal pre-promotion marker guaranteed to be promoted before classification completes, so nothing *terminal* is `PlainObjectType`.
* **LSP backing shape** (`CatalogBuilder.projectType` -> `TypeBackingShape.NoBacking.UnbackedResult`): `R157PipelineTest`'s two backing-shape fixtures migrated from `@record(className:)` to `@service` producers, so the types they exercise now project to a *backed* shape by reflection. The `UnbackedResult` projection itself stays (it is the LSP shape for backing-less result types: unions, errors, enums, scalars, connection/edge/PageInfo, nesting, unclassified); see the correction under "Drop `PojoResultType.NoBacking`" below. Shipped.

### Drop `PojoResultType.NoBacking` (moved here from R275) — shipped (19681e5, 8498e61)

A DML `RETURNING` yields a jOOQ `Record` / `Result<Record>`, and an `@service` source-record carrier yields a table record; neither is `NoBacking`. There is no valid schema that classifies as `NoBacking`, so it is removed: `TypeBuilder.promoteSingleRecordPayloads` binds the carrier to its `JooqTableRecordType` instead, and `PojoResultType` collapses to `Backed`. Shipped: `GraphitronType.PojoResultType.NoBacking` and `TypeClassification.UnbackedPojoResult` are deleted, the `NoBacking` arms in `FetcherEmitter` / `FieldBuilder` / `GraphitronSchemaBuilder` / `CatalogBuilder` are dropped, and the `VariantCoverageTest` / `ProjectionCoverageTest` entries for the removed variant are gone.

**Correction (2026-06-05).** This subsection's original wording also said to "Delete `TypeBackingShape.NoBacking.UnbackedResult`". That instruction was wrong and is *not* carried out: it conflated two unrelated `NoBacking` types. The deleted one is the **result-type model variant** `GraphitronType.PojoResultType.NoBacking`. `TypeBackingShape.NoBacking.UnbackedResult` is a different abstraction, the **LSP catalog backing-shape projection**, and it is load-bearing: `CatalogBuilder.projectType` maps eleven still-live `GraphitronType` variants onto it (`UnionType`, `ErrorType`, `EnumType`, `ScalarType`, `ConnectionType`, `EdgeType`, `PageInfoType`, `NestingType`, `UnclassifiedType`, and the null-class `PojoInputType`), and three LSP tests construct it directly. Removing it would orphan those arms. It stays. The only residual nit on it is a stale Javadoc (it still names "an `@record`-declared type whose `className` was unset, or a plain SDL object", paths that no longer reach it post-R276); refreshed alongside this correction.

### Example-schema and test reconciliation

* **Case 2** (NoBacking carrier): `SingleFilmCardCarrier` + `createFilmCard` removed from `schema.graphqls` (done). `FilmCardData` survives (it backs `FilmCardWrapper` via `@externalField`); `FilmCardFactoryService` is dead, optional cleanup.
* **Case 3** (`@tableMethod` under `NestingField`): `languageViaTableMethod` removed from `FilmDetailsForMethod`, the execution test disabled, carved to tablemethod-under-nested-type (R277) (done).
* **#2/#3** (`FilmCardWrapper.film`/`example` null): fixed by `groundExternalField` + the cascade.
* **#4** (`FilmDetails.language` round-trip count): `FilmDetails` reclassifies from `JooqTableRecordType` to a table-bound `NestingField`, so `language` is an inline to-one `TableField` (1 round trip), not a record-keyed DataLoader (2). The `RecordTableField`-batching coverage belongs on a still-record-backed fixture (`FilmCardWrapper.film`, AccessorKeyedSingle); update or relocate `recordTableField_multipleParents` / `recordLookupTableField_multipleParents` accordingly, after confirming the data is correct on the inline path.
* **LSP** (`R157PipelineTest`): the two backing-shape tests migrated to `@service` producers (done); the `CatalogBuilder` taxonomy re-home keeps them aligned.

### Implementation seams — shipped

All landed across 8498e61 / 4ad9030 / 19681e5: `RecordBindingResolver` gained `groundComputedField` + the shared `groundProducerResult` (cardinality-match + `@table`-backed-SDL guards); `TypeBuilder.classifyType` routes genuinely-unbound reachable objects to `UnclassifiedType`-with-reason and nested-DTO / connection / PageInfo cases to their real classifications; `GraphitronType.PlainObjectType` and `PojoResultType.NoBacking` are deleted with every consumer updated (`ObjectTypeGenerator`, `FetcherEmitter`, `FieldBuilder`, `GraphitronSchemaBuilder`, `CatalogBuilder`, `EntityResolutionBuilder`, `FetcherRegistrationsEmitter`, `ConnectionPromoter`, `GraphitronSchemaValidator`); `TypeBuilder.promoteSingleRecordPayloads` binds the carrier to its `JooqTableRecordType`.

### Tests and acceptance

* **Soundness (Pipeline).** Shipped: `R96RecordBindingPipelineTest.unreachable_recordTypeIsIgnored_leftUnclassified` and `SingleRecordPayloadPipelineTest` (the orphan-carrier case, `:463`) pin that a reachable type with no producer binding is left `UnclassifiedType`, never silently accepted. The "meta-assertion that `PlainObjectType` no longer exists" is moot: the variant is deleted, so the sealed `permits` clause and the exhaustive switches are the compile-time guarantee (a test naming the class would not compile).
* **Binding (Execution).** Shipped via the `groundComputedField` + cascade fix; covered by the `graphitron-sakila-example` execution suite (green).
* **Acceptance.** `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` is green end-to-end across `graphitron`, `graphitron-lsp`, and `graphitron-sakila-example` (verified 2026-06-05, BUILD SUCCESS), un-breaking trunk.

### Remaining before In Review -> Done

The code work above is complete and trunk is green. What is left is process, not implementation:

* **Re-gate the widened scope.** The reopened scope (eliminate `PlainObjectType`, drop `PojoResultType.NoBacking`, the no-silent-unbound soundness invariant) materially exceeds the narrow `@record`-read removal that got the original Spec -> Ready sign-off (97d454e). It was rewritten while In Progress and never re-gated. Reopen Ready -> Spec (or re-confirm at the next gate) and confirm the `R222` hierarchy-cleanup boundary with that author, as flagged under Coordination.
* **Flip In Progress -> In Review** once the re-gate is settled; the front-matter still reads In Progress.
* **In Review -> Done review** by a session that is not an implementer (the implementation commits are trailer-less / `alf.lervag`; the spec sign-off and this markup are `session_012rr9PQEFUPH6moKK43xDTV`, which is therefore also disqualified as it has now touched the spec body).

## Out of scope

* The error-channel and data-projection wiring for the carrier (R275).
* Removing `@record` from the SDL grammar or unregistering the directive; it stays parseable, with the ignored-directive warning, so existing schemas keep loading.
