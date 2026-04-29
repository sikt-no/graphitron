---
id: R49
title: "Stub: scalar/`@record`-returning `@service` child field (`ServiceRecordField`)"
status: Spec
bucket: stubs
priority: 2
theme: service
depends-on: [service-rows-method-body]
---

# Stub: scalar/`@record`-returning `@service` child field (`ServiceRecordField`)

Lift `ChildField.ServiceRecordField` out of `TypeFetcherGenerator.NOT_IMPLEMENTED_REASONS`. `ServiceRecordField` is structurally identical to `ServiceTableField` going in: same `BatchKey` machinery (List/Set container axis cross Row/Record key-shape axis), same `Sources` parameter shape on the developer's service method, same DataLoader plumbing (`newDataLoader` for positional, `newMappedDataLoader` for mapped). It diverges going out: `ServiceTableField` lifts the resulting `Record` into a Graphitron-projected query via `$fields()`, while `ServiceRecordField` returns the developer's value directly to graphql-java, which dispatches subfield resolution through normal wiring (default `PropertyDataFetcher` on backing-class accessors, or registered fetchers for further `@service` / `@table` chains).

The track ships in two phases:

- **Phase A (this spec)**: classification, `BatchKey` on the variant, DataLoader registration, lambda + key-extraction emission, rows-method stub. The fetcher compiles and the schema validates; the rows method throws `UnsupportedOperationException` at request time.
- **Phase B**: fill the rows-method body for both `ServiceTableField` and `ServiceRecordField`. Tracked under [`service-rows-method-body.md`](service-rows-method-body.md) (R32). The "call the developer's service and return the value" form for `ServiceRecordField` is the simpler of the two (no projection step), so it slots cleanly into that follow-up.

Scope guards: `@record`-typed parents (Site 1 in `FieldBuilder.classifyChildFieldOnResultType`) and non-empty `joinPath` are rejected with `DEFERRED` for now.

In passing, Phase A also corrects the element-shape carry-through note in [`set-parent-keys-on-service.md`](set-parent-keys-on-service.md): rather than re-reflecting the service method later to recover whether the user wrote `Set<TableRecord>` or `Set<RowN<...>>`, the classifier captures the element type at the same site as the `BatchKey` and surfaces it via `ChildField.ServiceRecordField#elementType()` (see "Strict return-type validation" inside the Builder section).

### Phase A trade-off

The `[deferred]` validator gate flips: schemas that today fail validation start compiling cleanly and codegen succeeds. Selecting a `ServiceRecordField` at request time throws `UnsupportedOperationException` until Phase B lands. Callers who route around the field succeed; callers who select it get a runtime error pointing at R32.

## Model: `BatchKey` on `ServiceRecordField`

`graphitron/src/main/java/no/sikt/graphitron/rewrite/model/ChildField.java`:

```java
record ServiceRecordField(
    String parentTypeName,
    String name,
    SourceLocation location,
    ReturnTypeRef returnType,
    List<JoinStep> joinPath,
    MethodRef method,
    BatchKey batchKey  // NEW
) implements ChildField, MethodBackedField, BatchKeyField {

    @Override
    public String rowsMethodName() {
        return "load" + Character.toUpperCase(name().charAt(0)) + name().substring(1);
    }
}
```

Same `load<X>` naming convention as `ServiceTableField` for consistency with the existing emitter dispatch. Adding `BatchKeyField` to the `implements` list lets generators pattern-match on the shared interface (already used by `TypeFetcherGenerator`'s service / split-query arms via `GeneratorUtils.keyElementType(BatchKey)` and friends).

### Element-type derivation (resolves the `set-parent-keys-on-service.md` footnote)

The `set-parent-keys-on-service.md` spec defers element-type recovery to the Phase B body emitter, which would re-reflect the service method. That deferral is closed here without modifying `MethodRef.Param.Sourced`: `ChildField.ServiceRecordField#elementType()` (see "Strict return-type validation" below) derives the Java element type directly from the field's `ReturnTypeRef`. The accessor lives on the record so both Builder and Generator read it, mirroring `computeServiceRecordReturnType(QueryServiceRecordField)` at `TypeFetcherGenerator.java:920`.

No change to `MethodRef.Param.Sourced` is needed for Phase A. Phase B's body emitter will determine the correct container-unwrapping logic (the `Map<KeyType, V>` call shape) from `batchKey` and the schema-side return type, without needing a separate V field on `Sourced`. Phase B inherits this accessor unchanged.

## Builder: derive and attach `BatchKey`; reject deferred shapes

`FieldBuilder.classifyChildFieldOnTableType` (Site 2, around line 2009): the existing `resolveServiceField(...)` call already produces a `MethodRef` whose `Sources` parameter carries a `BatchKey`. Lift that key using `extractBatchKey(MethodRef)`, the private helper at `FieldBuilder.java:164` already used by the `ServiceTableField` arms at the same site:

```java
var batchKey = extractBatchKey(svcResult.method());
if (batchKey == null) {
    return new UnclassifiedField(parentTypeName, name, location, fieldDef,
        RejectionKind.AUTHOR_ERROR,
        "@service on a child field requires a Sources parameter (List/Set of "
            + "Row/Record keys); see roadmap/service-record-field.md");
}
return switch (returnType) {
    case ReturnTypeRef.ResultReturnType r ->
        new ServiceRecordField(parentTypeName, name, location, r, servicePath.elements(), svcResult.method(), batchKey);
    case ReturnTypeRef.ScalarReturnType s ->
        new ServiceRecordField(parentTypeName, name, location, s, servicePath.elements(), svcResult.method(), batchKey);
    // existing TableBoundReturnType arm continues to produce ServiceTableField
};
```

### Strict return-type validation

`ServiceTableField` enforces that the developer's reflected return type matches the schema's declared `ReturnTypeRef` element (via `FieldBuilder.computeExpectedServiceReturnType`). The same enforcement must run on `ServiceRecordField`, using a shared helper to derive the expected Java type from the schema. The helper lives at the classification layer (a default method on `ChildField.ServiceRecordField` itself), not on the Generator: both Builder and Generator need the same derivation, and Builder calling into the Generator package would invert the classify -> emit dependency that the rest of the codebase preserves. Suggested shape: `ChildField.ServiceRecordField#elementType()` returning a `ClassName`, mirroring the existing `qualifiedName()` accessor pattern on the same record hierarchy.

- `String` field: expected element is `String`. Method shape is `Map<KeyType, String>` (mapped) / `List<String>` (positional).
- `Boolean` field: expected element is `Boolean`. Same shape rules.
- `@record`-backed `FilmDetails` field: expected element is `no.example.FilmDetails` (from `ResultReturnType.fqClassName()`); `@record` backing class matched by FQN.

Mismatches surface as classification-time `AUTHOR_ERROR`, not compile-tier.

`FieldBuilder.classifyChildFieldOnResultType` (Site 1, lines 1857-1859): the parent is a `@record`. Deriving a batch key here would require lifting through the parent chain to the rooted `@table` whose PK provides the key columns, which is its own design problem. Reject for now until a real schema needs it:

```java
return new UnclassifiedField(parentTypeName, name, location, fieldDef,
    RejectionKind.DEFERRED,
    "@service on a @record-typed parent is not yet supported; the batch key "
        + "must be lifted through the parent chain to the rooted @table. See "
        + "roadmap/service-record-field.md.");
```

Site 1's `ScalarReturnType` and `ResultReturnType` arms become a single `DEFERRED` rejection; the `TableBoundReturnType` arm at the same site already routes to a different code path and is unaffected.

## Generator: extend the existing service emitters, don't fork

`graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java`:

`buildServiceDataFetcher` (lines 1469+) and `buildServiceRowsMethod` (lines 1533+) already encode the entire DataLoader plumbing for `ServiceTableField`. The only axis of variation between `ServiceTableField` and `ServiceRecordField` is the loader value type and the terminal return shape:

```
ServiceTableField:    DataLoader<K, Record> -> loader.load(k) ->
                      downstream $fields() projects the Record into the table scope
ServiceRecordField:   DataLoader<K, V>      -> loader.load(k) ->
                      returned directly to graphql-java; V = field.elementType()
```

Everything else is identical: BatchKey extraction, lambda shape, factory selection (`newDataLoader` / `newMappedDataLoader`), key-extraction code, the `loader.load(key, env)` return.

Parameterise the existing emitters by element type rather than forking a parallel `buildServiceRecordFetcher` / `buildServiceRecordRowsMethod`. Concretely:

- `buildServiceDataFetcher` already takes `(String fieldName, BatchKeyField field, MethodRef method, ReturnTypeRef returnType, TableRef parentTable, ...)`. Add a parameter (or derive internally from the variant type) that selects the loader value type: `Record` for `ServiceTableField`, `field.elementType()` for `ServiceRecordField`. The lambda body and key-extraction code do not change.
- `buildServiceRowsMethod`: same parameterisation. The Phase A stub body (`throw new UnsupportedOperationException()`) is identical for both variants; only the return-type construction differs.

Dispatch in the variant switch then becomes:

```java
case ChildField.ServiceTableField stf -> {
    builder.addMethod(buildServiceDataFetcher(stf.name(), stf, stf.method(), stf.returnType(), parentTable, className, jooqPackage));
    builder.addMethod(buildServiceRowsMethod(stf, stf.returnType()));
}
case ChildField.ServiceRecordField srf -> {
    builder.addMethod(buildServiceDataFetcher(srf.name(), srf, srf.method(), srf.returnType(), parentTable, className, jooqPackage));
    builder.addMethod(buildServiceRowsMethod(srf, srf.returnType()));
}
```

Reasoning: maintaining two emitters that share 90% of their body and diverge only on a type token invites drift. The `BatchKeyField` interface already lets pattern-matching across the two variants happen at the dispatch site; the emitter doesn't need to re-match.

The element-type derivation lives on `ChildField.ServiceRecordField#elementType()` (per the Strict return-type validation section above), mirroring `computeServiceRecordReturnType(QueryServiceRecordField)` at `TypeFetcherGenerator.java:920`:

- `ResultReturnType` with non-null `fqClassName`: element is `ClassName.bestGuess(fqClassName)`.
- All other cases: element is `field.method().returnType()`, the reflected return type already on `MethodRef`.

Both the Builder's strict-return-type validation and the Generator's fetcher emission read through that single accessor.

Data fetchers return Java types; graphql-java's registered scalar coercing handles the GraphQL-side coercion at output. No GraphQL-scalar-to-Java mapping table is needed in the generator.

The rows-method stub for `ServiceRecordField` reuses the parameterised `buildServiceRowsMethod` described above; the return-type construction reads through `field.elementType()` for the per-key element instead of the `Record` type used by `ServiceTableField`. No new method is introduced; Phase B fills in the body via the same shared emitter once R32 lands.

## `TypeFetcherGenerator` cleanup

1. Remove the `Map.entry(ChildField.ServiceRecordField.class, ...)` row from `NOT_IMPLEMENTED_REASONS`.
2. Add `ChildField.ServiceRecordField.class` to `IMPLEMENTED_LEAVES`.
3. Replace the stub arm in the dispatch switch with the shared-emitter call shown in the "Generator: extend the existing service emitters" section above.

The fetcher and rows method land on the same `<TypeName>Fetchers` class as the `ServiceTableField` ones; no new top-level emitter is introduced.

## Validator

`GraphitronSchemaValidator.validateServiceRecordField` (line 660) currently runs only `validateReferencePath`. Add a guard rejecting non-empty `joinPath`:

```java
private void validateServiceRecordField(ChildField.ServiceRecordField field,
        Map<String, GraphitronType> types, List<ValidationError> errors) {
    if (!field.joinPath().isEmpty()) {
        errors.add(new ValidationError(RejectionKind.DEFERRED,
            field.qualifiedName(),
            "Field '" + field.qualifiedName() + "': @service with a @reference path "
                + "(condition-join lift form) is not yet supported; see "
                + "graphitron-rewrite/roadmap/service-record-field.md",
            field.location()));
        return;
    }
    validateReferencePath(field.qualifiedName(), field.location(), field.joinPath(), errors);
}
```

The variant-implementation gate (`validateVariantIsImplemented`, `GraphitronSchemaValidator.java:147`) reads `NOT_IMPLEMENTED_REASONS.keySet()`; removing the entry per the generator-cleanup step above is what flips the `[deferred]` error off for this field class.

## Tests

Unit tier (`graphitron/src/test/`):

- `ServiceCatalogTest`: extend the existing `reflectServiceMethod_*` cases with one per `BatchKey` variant cross scalar/record return: e.g. `reflectServiceMethod_setOfRow1Sources_scalarReturn_classifiedAsServiceRecord`. The classifier itself is shared with `ServiceTableField`; what we're verifying is that the builder routes scalar/record returns to `ServiceRecordField` while preserving the `BatchKey` derivation. This needs builder-level coverage, not catalog-level (the catalog already classifies sources).
- `GraphitronSchemaBuilderTest`: extend the existing `SERVICE_FIELD_ON_RESULT_TYPE` case to assert (a) the field is `ServiceRecordField`, (b) it carries the expected `BatchKey` for the parent's PK, (c) it implements `BatchKeyField`. Add a `SERVICE_FIELD_ON_RECORD_PARENT_DEFERRED` case asserting the new `DEFERRED` rejection for the @record-typed parent shape.
- `TypeFetcherGeneratorTest`: add `serviceRecordField_*` fixtures mirroring the `serviceField_*` fixtures shipped under [`set-parent-keys-on-service.md`](set-parent-keys-on-service.md):
  - `serviceRecordField_scalar_list_dataFetcherReturnsCompletableFutureListString` (or whichever scalar shape; assert `CompletableFuture<List<...>>`).
  - `serviceRecordField_scalar_single_dataFetcherReturnsCompletableFutureString`.
  - `serviceRecordField_recordBacked_list_dataFetcherReturnsCompletableFutureListPojo`.
  - `serviceRecordField_mappedRow_list_dataFetcherCallsNewMappedDataLoaderWithSetKeys`.
  - `serviceRecordField_mappedRow_list_rowsMethodReturnsMapToListOfElement`.

The `newDataLoader` vs `newMappedDataLoader` factory selection is shared infrastructure (factory-method-string + `BuildKeyExtraction`) covered by `ServiceTableField`'s test surface; no parallel regression on `ServiceRecordField` is needed.

Add one classifier-level case in `ServiceCatalogTest` asserting V capture on `MethodRef.Param.Sourced`: e.g. for a method `Map<Row1<Integer>, String> forkortelser(Set<Row1<Integer>> ids, DSLContext dsl)`, the resulting Sourced param's `elementType` is `String`. Cover both batched containers and the backed-`@record` case.

Pipeline tier: a `SchemaToFetchersPipelineTest` case running classifier + generator on a small schema with one scalar-returning `@service` child field on a `@table` parent, asserting (a) the generated fetcher class compiles, (b) it contains the expected `newDataLoader` / `newMappedDataLoader` call.

Compilation tier (`graphitron-test`, `<release>17</release>`): the existing compile harness picks up the new fetcher + rows method automatically once the fixture below is in place.

Execution tier: deferred to Phase B (R32). The rows method throws at request time, so no end-to-end query test can assert a value yet.

## Fixture

The fixture below exists for classification + reflection-time discovery only. Phase A does not execute the rows method; the stub's `UnsupportedOperationException` mirrors the generated rows-method body. Phase B replaces the body with the real `Service.method(keys, ...)` call.

Add to `graphitron-test/src/main/resources/graphql/schema.graphqls`:

```graphql
extend type Film @table(name: "film") {
    titleUppercase: String @service(service: {
        className: "no.sikt.graphitron.rewrite.test.services.FilmService",
        method: "titleUppercase"
    })
}
```

And the corresponding stub in `graphitron-test`:

```java
public final class FilmService {
    private FilmService() {}

    /** Phase A: signature only; the body throws to mirror the rows-method stub. */
    public static java.util.Map<org.jooq.Row1<Integer>, String> titleUppercase(
            java.util.Set<org.jooq.Row1<Integer>> filmIds, org.jooq.DSLContext dsl) {
        throw new UnsupportedOperationException();
    }
}
```

The fixture compiles and is reachable from the schema; Phase B fills the body.

## Out of scope

- **Rows-method body** (Phase B): folded into [`service-rows-method-body.md`](service-rows-method-body.md) (R32). For `ServiceRecordField` the body shape is "call the developer's method, return the values directly" with no projection step, simpler than the `ServiceTableField` body. Both shapes share infrastructure (DSLContext local, arg-call emission via `ArgCallEmitter.buildMethodBackedCallArgs`, scatter-result-into-Map for positional variants), so they ship together.
- **`@record`-typed parents.** Site 1 in `FieldBuilder` returns `DEFERRED` for the scalar/record-return arms. The blocker is "what's the batch key when the parent is a `@record`?". The `@record` parent might itself be reachable via a `@table`-rooted chain whose terminal node has a derivable PK, but that's a separate design problem.
- **`MutationServiceRecordField`.** Tracked under [`mutations.md`](mutations.md), unaffected by this track.
- **Non-empty `joinPath`.** `DEFERRED` rejection on the validator surface; re-promote when a real schema needs the lift form.

## Open questions

None remaining. The element-type derivation question (how to surface V without modifying `MethodRef.Param.Sourced` or changing `classifySourcesType`'s return type) is resolved by `ChildField.ServiceRecordField#elementType()` reading the schema-side `ReturnTypeRef` directly, mirroring the existing `computeServiceRecordReturnType` at line 920.

## Roadmap entry update

After Phase A ships:

- This file: collapse the body into a one-line "Phase A shipped at `<sha>`" pointer and a brief note about what landed (DataLoader plumbing + stub body); the remaining work cross-references R32.
- `changelog.md`: standard "Done" entry for Phase A.
- Status moves to In Review pending Phase B; flips to Done only when R32 closes the rows-method body for both `ServiceTableField` and `ServiceRecordField`.
