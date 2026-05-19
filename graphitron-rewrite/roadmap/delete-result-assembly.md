---
id: R179
title: Delete ResultAssembly; service success arm is universal passthrough
status: Spec
bucket: cleanup
priority: 5
theme: service
depends-on: []
created: 2026-05-19
last-updated: 2026-05-19
---

# Delete ResultAssembly; service success arm is universal passthrough

## Problem

`ResultAssembly` exists to let a `@service`-backed root field's developer method return a *domain object* (an `XRecord`, a hand-rolled domain record) while the SDL declares a *payload class* (`CreateFilmPayload { film: Film, errors: [Error] }`). The carrier classifier walks the payload class's canonical constructor for one parameter assignable from the service method's reflected return type; the emitter then synthesises a `T __row = service.method(...); T payload = new Payload(__row, List.of(), ...defaults);` block. The generator constructs the payload DTO on the *success* arm.

That violates the rewrite's architectural constraint that the generator does not construct output DTOs on the happy path. The intended layering is:

1. **Service layer** returns whatever's natural (a `TableRecord`, a `List<TableRecord>`, a hand-rolled domain record). No GraphQL types in sight.
2. **Per-field resolver layer** (graphql-java wiring + the rewrite's child-field fetchers) projects SDL fields off the parent's domain return. `CreateFilmPayload.film` reads off the source; `CreateFilmPayload.errors` defaults to `List.of()` on the happy path (or routes through `ErrorRouter` on the exception path). No payload DTO needs to exist in Java memory.
3. **Payload class** is a GraphQL-side concept that does not need a Java twin.

Under that layering, `ResultAssembly` is solving a problem the per-field wiring layer already solves. It was introduced by sessions that didn't recognise the wiring path was the proper answer; the result is a sealed slot hierarchy (`ResultSlot.CtorParameterIndex | SetterMethod`), a parallel classifier pipeline (`ResultAssemblyResult.NoAssembly | Assembly | Reject`), and a forked success-arm emitter — none of which earn their keep against the wiring path.

The catch arm's `payloadFactoryLambda` is a different concern and stays: when the service throws, the service never produced a payload, so the generator constructs `Payload(null, errors)` from the error-routed list. Generator-side DTO construction is unavoidable on the error path; that's where `PayloadConstructionShape`, `ErrorsSlot`, `NonBoundSetter`, and `DefaultedSlot` earn their keep, and they all continue to. The wiring layer cannot symmetrise this: graphql-java per-field DataFetchers project off a *value* the parent resolver returned, but on the throw path no value was returned. The error-routed list is produced inside the service-call try block, which is generator-owned, so the synthetic payload must also be constructed there. The boundary is structural, not a missing abstraction.

## Scope

Removing `ResultAssembly` is purely a generator-side cleanup. No SDL change, no runtime behaviour change in the legacy passthrough path (which is the only path real `@service`-backed fixtures exercise). The four service-backed `Field` records lose one component each; the success-arm emitter collapses to one branch.

## Implementation

### Delete

- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ResultAssembly.java` (whole file).
- `graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ResultSlot.java` (whole file; sealed hierarchy used only by `ResultAssembly`).
- In `FieldBuilder.java`: the `ResultAssemblyResult` sealed interface and its three permits, the `NO_RESULT_ASSEMBLY` constant, the `resolveServiceResultAssembly` method (~90 lines), and the `buildResultAssemblyBeanArm` helper (~40 lines). The four call sites in the service-backed builders (`buildQueryServiceTableField`, `buildQueryServiceRecordField`, `buildMutationServiceTableField`, `buildMutationServiceRecordField`) drop their `switch (resolveServiceResultAssembly(...))` block and the `Optional<ResultAssembly> assembly` local; the `UnclassifiedField` arm previously produced by `ResultAssemblyResult.Reject` (mismatched return type) is replaced with a single check: if the service method's reflected return type does not equal the expected SDL payload type (the existing legacy-equality computation), reject as `UnclassifiedField` with the existing "must return" message. The "list-cardinality service fields: per-element ResultAssembly is not supported" reject disappears entirely (the legacy-equality check already handles list shapes).
- In `TypeFetcherGenerator.java`: the `buildSuccessPayload`, `buildSuccessPayloadCtor`, and `buildSuccessPayloadSetters` methods (~70 lines), plus the `if (resultAssembly.isPresent()) { ... } else { ... }` fork inside `buildServiceFetcherCommon`.
- The `Optional<ResultAssembly> resultAssembly` component on each of `QueryField.QueryServiceTableField`, `QueryField.QueryServiceRecordField`, `MutationField.MutationServiceTableField`, `MutationField.MutationServiceRecordField`. Update the javadoc on each to drop the `resultAssembly` paragraph.
- The `resultAssembly` plumbing through `MappingsConstantNameDedup.java` (four call sites).
- The `buildServiceFetcherCommon` parameter `Optional<ResultAssembly> resultAssembly` and the four call sites in the service fetcher builders.

### Rename

- Inside `buildServiceFetcherCommon`, rename the success-arm local from `payload` to `result`. This is the only hygiene-only step in the spec; everything else under Delete is structural. Bundling it into the same commit is deliberate (the file is open anyway, the rename pins the corrected mental model in source), but flagged separately here so the asymmetry is visible to a reviewer. The variable holds a `TableRecord` for `QueryServiceTableField` / `MutationServiceTableField` (Single), the developer's declared `Result<XRecord>` / `List<XRecord>` for the List arm, and the `@record` backing class (or reflected return type) for `*ServiceRecordField`. `payload` is residue from the discarded mental model. Update the `return $T.success(result)` / `returnSyncSuccess(valueType, "result")` site and any local references. Keep the local — the catch arm dispatches the captured exception, not the local, so the rename is purely cosmetic but worth doing while the file is open.

### Fixtures

Two fixtures exist purely to exercise the deleted classifier path:

- `graphitron-rewrite/graphitron/src/test/java/no/sikt/graphitron/codereferences/dummyreferences/BothShapesSakPayload.java` — actually consumed by the catch-arm `payloadFactoryLambda` test (`FetcherPipelineTest.serviceMutation_bothShapesPresent_prefersCtorFactory`), which asserts the AllFieldsCtor-over-MutableBean precedence in `PayloadConstructionShape` resolution. **Keep**: the test still pins a real precedence rule in the catch-arm factory path.
- `MultiCtorSakPayload` (referenced by `ErrorChannelClassificationTest`) — same; **keep**.
- `TestServiceStub.runBothShapesSak`, `runMultiCtorSak`, `runSetterSak` stubs — keep; they back the catch-arm tests above.

No execution-tier sakila fixtures exercise the Assembly arm. All three `FilmReviewService` methods (`submit`, `submitWithDetails`, `submitSetterShape`) return their payload class directly (passthrough). After deletion, the entire execute-tier coverage continues to compile and pass against an identical generated body.

### Classifier reject simplification

Today's classifier produces three distinct reject messages for service-return mismatches:

1. "must return `<sdlPayload>` to match the field's declared payload type" — when the payload class loads but no ctor parameter binds.
2. "must return `<sdlPayload>` (the field's declared payload class) or a type matching one parameter on `<cls>`'s canonical constructor" — when ctor walked and nothing matched.
3. "list-cardinality service fields must return the SDL payload type directly (per-element ResultAssembly is not supported)" — list-arm mismatch.

After the deletion, all three collapse to the existing legacy-passthrough rejection: "must return `<sdlPayload>`" (single source of truth, already produced by the `method.returnType().equals(sdlPayloadTypeName)` check above the deleted block). Existing tests asserting on `.contains("must return")` continue to pass. Tests asserting on the more specific wording (e.g. "or a type matching one parameter on ...") need their assertion narrowed to "must return".

The `LoadBearingGuaranteeAuditTest` net stays green after the deletion. The two annotated consumers wired to producer keys the deleted code touches are `buildSuccessPayload` (`payload-construction.shape-resolved`, alongside catch-arm `payloadFactoryLambda` and validator pre-step `declareEarlyPayloadFromErrors`) and `buildSuccessPayloadSetters` (`payload-construction.setter-name-matches-sdl-field`, alongside catch-arm `payloadFactoryLambdaSetters` and validator pre-step `declareEarlyPayloadSetters`). Both consumers delete with their methods; both producers keep at least one surviving consumer, so no producer becomes orphaned and the audit pair balances. What the annotation-value grep missed is the producer-side *description-string* prose and the surrounding `{@link}` / comment javadoc that names the deleted symbols by name; those land in the same commit, enumerated under "Stale doc cleanup" below.

### Stale doc cleanup

Per the workflow's "Documentation names only live tests/code" rule, every javadoc, annotation description, and prose comment that names a deleted symbol (`ResultAssembly`, `ResultSlot`, `resolveServiceResultAssembly`, `buildSuccessPayload`, `buildSuccessPayloadCtor`, `buildSuccessPayloadSetters`) updates in the same commit:

- `FieldBuilder.java` `@LoadBearingClassifierCheck(key = "payload-construction.shape-resolved")` (on `resolvePayloadConstructionShape`) : description names "Every ErrorChannel and ResultAssembly carries a PayloadConstructionShape arm ..." and "The two TypeFetcherGenerator payload-factory emit sites (catch-arm payload-factory lambda, service-result buildSuccessPayload) wear @DependsOnClassifierCheck on this key." Rewrite to name ErrorChannel only and to name the two surviving consumers: the catch-arm `payloadFactoryLambda` and the validator pre-step's `declareEarlyPayloadFromErrors`.
- `FieldBuilder.java` `@LoadBearingClassifierCheck(key = "payload-construction.setter-name-matches-sdl-field")` (same producer method) : description references "the catch-arm payload-factory and analogous service-result emit sites". Drop "analogous service-result emit sites"; the surviving consumers are the catch-arm factory and the validator pre-step's `declareEarlyPayloadSetters`.
- `FieldBuilder.collectDefaultedSlots` javadoc : cites `{@link #resolveErrorChannel}` and `{@link #resolveServiceResultAssembly}` as the two callers. Drop the latter; only `resolveErrorChannel` remains.
- `DefaultedSlot` class javadoc : cross-refs `{@link ResultAssembly}` as a consumer. Drop.
- `PayloadConstructionShape` class javadoc : names "the assembly carriers ({@link ErrorChannel}, {@link ResultAssembly})". Drop the `ResultAssembly` link; only `ErrorChannel` remains.
- `NonBoundSetter` class javadoc : cites `{@link ErrorsSlot}` and `{@link ResultSlot}` as the two `SetterMethod`-arm carriers. Drop `{@link ResultSlot}`.
- `ErrorsSlot` class javadoc : names `{@link ResultSlot}` as the sealed-hierarchy sibling and `{@code ResultAssembly}` in the rationale. Both go; either trim the sibling-hierarchy paragraph or replace it with a forward-only justification (folding `ErrorsSlot` onto a broad `Slot` interface would still widen, but the comparison loses its second pole).
- `ServiceDirectiveResolver.expectedReturnFor`'s `ResultReturnType` arm comment : returns `null` and justifies that with "the ResultAssembly resolver in FieldBuilder does [the disambiguation]". After deletion the rationale is structurally different ; the legacy passthrough check in `FieldBuilder` rejects directly on `method.returnType().equals(sdlPayloadTypeName)`, with no ctor-walk side path. Rewrite the comment to describe the legacy-equality check as the sole disambiguator.
- `TestServiceStub.runSetterSak` javadoc : says "the service returns the SDL payload type directly (legacy passthrough), so no ResultAssembly is resolved". Drop the "so no ResultAssembly is resolved" clause ; the legacy passthrough is the only path now.

## Tests

- `ServiceCatalogTest`, `ServiceRootFetcherPipelineTest`, `GraphitronSchemaBuilderTest` — re-run; any assertion on the Assembly-specific reject prose narrows to "must return".
- `FetcherPipelineTest.serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged`, `FetcherPipelineTest.serviceMutation_bothShapesPresent_prefersCtorFactory`, `ErrorChannelClassificationTest` — unchanged; they exercise the catch-arm factory, not the deleted success arm.
- `TypeFetcherGeneratorTest.queryServiceRecordField_withErrorChannel_catchArmDispatchesThroughErrorRouter` and siblings — unchanged; the test's inline comment already calls out "every existing service-record test passes `Optional.empty()`", so the success-arm assertions still hold with the constructor signature minus the trailing `Optional<ResultAssembly>`.
- `MappingsConstantNameDedupTest` — re-run after the constructor-shape change.
- Validation tests (`ServiceFieldValidationTest`, `MutationServiceTableFieldValidationTest` family) — update the four-arg / five-arg literal record constructors; the trailing `Optional.empty()` for `resultAssembly` drops.
- Sakila execute-tier (`graphitron-sakila-example`): `FilmReviewService` round-trips, `submitWithDetails`, `submitSetterShape` — all pass unchanged. These are the load-bearing regression cover for the passthrough path; if they go green after the deletion, the universal-passthrough claim holds end-to-end.
- Add no new tests. The deletion has no new behaviour to cover; it removes a redundant path.

## Pipeline staging

Single commit on a single feature branch:

1. Delete the model files and the classifier / emitter blocks together.
2. Update the four service-backed `Field` records' components and javadoc in the same commit (the records' constructors are public, so the change ripples through every literal constructor in tests).
3. Re-run `mvn install -Plocal-db`. Any test surface that asserted on Assembly-specific prose tightens to "must return".

No phased rollout, no feature flag. The deleted path has no consumer outside the codebase.

## Open questions

- *Inline the success arm's local?* Out of scope. The current shape (`$T result = service.method(...); return $T.success(result);`) keeps the catch arm's `try` block uniform across service-backed fetchers and matches the DML-emitter convention. Inlining is a separate ergonomic call.
- *Variable name `result` vs alternatives?* `result` is the obvious name once the misnomer "payload" goes; matches the SDL nomenclature (`ResultReturnType` is already the modelled return-type variant for non-table fields). No other name has come up that improves on it.
- *Should `payloadFactoryLambda` use a different `PayloadConstructionShape` resolver than the deleted Assembly path?* No. The catch-arm factory already drives `PayloadConstructionShape` resolution independently in `FieldBuilder.resolveErrorChannel`; deleting `resolveServiceResultAssembly` does not touch that resolver.
