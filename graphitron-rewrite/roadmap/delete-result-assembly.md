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

The catch arm's `payloadFactoryLambda` is a different concern and stays: when the service throws, the service never produced a payload, so the generator constructs `Payload(null, errors)` from the error-routed list. Generator-side DTO construction is unavoidable on the error path; that's where `PayloadConstructionShape`, `ErrorsSlot`, `NonBoundSetter`, and `DefaultedSlot` earn their keep, and they all continue to.

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

- Inside `buildServiceFetcherCommon`, rename the success-arm local from `payload` to `result`. The variable holds a `TableRecord` for `QueryServiceTableField` / `MutationServiceTableField` (Single), the developer's declared `Result<XRecord>` / `List<XRecord>` for the List arm, and the `@record` backing class (or reflected return type) for `*ServiceRecordField`. `payload` is residue from the discarded mental model. Update the `return $T.success(result)` / `returnSyncSuccess(valueType, "result")` site and any local references. Keep the local — the catch arm dispatches the captured exception, not the local, so the rename is purely cosmetic but worth doing while the file is open.

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

## Tests

- `ServiceCatalogTest`, `ServiceRootFetcherPipelineTest`, `GraphitronSchemaBuilderTest` — re-run; any assertion on the Assembly-specific reject prose narrows to "must return".
- `FetcherPipelineTest.serviceMutation_allFieldsCtorPayload_emitsCtorFactory_unchanged`, `FetcherPipelineTest.serviceMutation_bothShapesPresent_prefersCtorFactory`, `ErrorChannelClassificationTest` — unchanged; they exercise the catch-arm factory, not the deleted success arm.
- `TypeFetcherGeneratorTest.queryServiceRecordField_withErrorChannel_catchArmDispatchesThroughErrorRouter` and siblings — unchanged; the comment at L824 already calls out "every existing service-record test passes `Optional.empty()`", so the success-arm assertions still hold with the constructor signature minus the trailing `Optional<ResultAssembly>`.
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
