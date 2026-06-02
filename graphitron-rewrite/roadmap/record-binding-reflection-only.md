---
id: R276
title: Record binding is reflection-only; remove @record-directive-consulting code
status: In Progress
bucket: cleanup
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-06-02
last-updated: 2026-06-02
---

# Record binding is reflection-only; remove @record-directive-consulting code

The `@record` directive is deprecated and ignored: graphitron derives every result and input backing class from the producing field's reflected return type (R96), and `TypeBuilder.emitDirectiveIgnoredWarnings` already warns the author that the directive does nothing. Despite that, several classifier sites still *read* `@record` to drive binding and type classification. That residual coupling is a standing bug source, and it actively produces a wrong classification for the source-record-carrier shape.

The load-bearing case: `RecordBindingResolver.groundServiceField` gates the result-axis producer observation on `resultObjType.hasAppliedDirective(DIR_RECORD)` (`RecordBindingResolver.java:240`). So a `@service` field whose method returns a jOOQ `TableRecord` into a payload that carries no `@record` (the only supported authoring style) never grounds a result binding; the payload stays a `PlainObjectType` instead of binding to the producer's record type. A classification probe confirms it: for `makeFilm: MakeFilmPayload @service(...returns FilmRecord)` with `MakeFilmPayload { film: Film @splitQuery, errors: [FilmErr] }`, `MakeFilmPayload` classifies as `PlainObjectType`, the field resolves `errorChannel: Optional.empty`, and the inner `film` field gets no datafetcher. This is the direct blocker for R275: the carrier never becomes a `ResultType`, so no error channel attaches and the inner data field never classifies.

## Target

Binding is reflection-only. No classifier or binding-resolver code reads `@record` to decide a backing class or a type's classification kind. With the gate removed, the `@service` producer's reflected return element grounds the result observation unconditionally (still subject to the existing `shouldBind` predicate), so a source-record-carrier payload binds to its producer's record type and classifies as a `JooqTableRecordType` (a `ResultType`). The deprecation surface stays intact: `@record` remains a registered, parseable directive (existing schemas still load) and `emitDirectiveIgnoredWarnings` still tells authors it is ignored.

## `@record`-consulting sites

Remove (these read the directive to drive binding/classification):

* `RecordBindingResolver.groundServiceField` (`~236-248`): the `sdlHasRecord` gate on the result-axis `RootService` observation. Ground the observation from the reflected return element unconditionally. This is the load-bearing change that makes the carrier payload bind.
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
* **R222 (adjacent).** The author's planned classification-hierarchy cleanup. Keep R276 scoped to removing the `@record` binding source; leave hierarchy restructuring to R222.

## Out of scope

* The error-channel and data-projection wiring for the carrier (R275).
* Removing `@record` from the SDL grammar or unregistering the directive; it stays parseable, with the ignored-directive warning, so existing schemas keep loading.
