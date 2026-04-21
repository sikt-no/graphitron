# `DSLContext` parameters on `@service` methods

> **Status:** Draft
>
> Teach `ServiceCatalog.reflectServiceMethod` to recognise an `org.jooq.DSLContext`
> parameter on an `@service` method and classify it as
> {@link ParamSource.DslContext}. Today such a parameter falls past every
> classification branch and the field is rejected with
> *"unrecognized sources type: 'org.jooq.DSLContext'"*.

## Current state

- `ParamSource.DslContext` is already a declared variant of the sealed
  `ParamSource` interface (`ParamSource.java:26–28`, `:55`, docstring:
  *"the jOOQ `DSLContext`; injected by the framework"*). `MethodRef.Param.Typed`
  can already carry it; `ServiceFieldValidationTest.RecordCase.WITH_LIFT_CONDITION`
  constructs one by hand (line 42).
- Nothing in the reflection path produces one. `reflectServiceMethod` classifies
  each parameter as, in order: `Arg` (name in `argNames`), `Context` (name in
  `ctxKeys`), or else hands off to `classifySourcesType`, which only accepts
  `List<?>` (line 309–310). A `DSLContext` parameter therefore fails at
  `ServiceCatalog.java:174–177` and surfaces as an `UnclassifiedField`.
- `reflectTableMethod` (the sibling for `@tableMethod` / `@condition`) already
  demonstrates the type-based-check pattern: it recognises `org.jooq.Table`
  assignability at line 228 *before* name-based dispatch. The fix mirrors that.
- No emitter currently consumes a `ParamSource.DslContext` produced from a
  service method. Every `@service` emission is either:
  - a stub (root-`Query` service leaves — `QueryServiceTableField`,
    `QueryServiceRecordField` — tracked by
    [`plan-service-root-fetchers.md`](plan-service-root-fetchers.md)), or
  - a `UnsupportedOperationException` body (child
    `ChildField.ServiceTableField.buildServiceRowsMethod`,
    `TypeFetcherGenerator.java:1042–1060`).
  So the classifier change is safe to land ahead of emission: it produces a new
  `MethodRef.Param.Typed` that no live code path reads, but downstream plans
  (service-root-fetchers, the ChildField service follow-up) can rely on it.

## Scope

**In scope.** `@service` methods only. Recognise `org.jooq.DSLContext`
parameters by type, emit `MethodRef.Param.Typed` with `ParamSource.DslContext`,
preserve their positional slot in `params()` so a future emitter can walk the
parameter list and inject the right expression.

**Out of scope.**

- `@condition` / `@tableMethod` methods (`reflectTableMethod`). These still
  reject DSLContext parameters with the existing "not a Table<?>, not a GraphQL
  argument, and not a context key" message. Lifting that gate requires
  rewriting `ArgCallEmitter.buildCallArgs` (condition-call emission —
  currently emits `(table, arg...)` by iterating only `callParams()`, which
  skips structural sources) to inject DSLContext in the correct positional
  slot. Tracked as an `[Unplanned]` follow-up below; no active consumer
  needs it.
- Emission wiring. Covered by `plan-service-root-fetchers.md` (root `@service`
  queries) and a future ChildField service plan. This plan only makes the
  classifier produce the variant; it adds nothing to the generator.
- The `Set<T>` parent-keys case (the tenth error in the validator run) is a
  separate design question — `BatchKey` / `classifySourcesType` only model
  `List<T>` today. Out of scope for this plan.

## Changes

### `ServiceCatalog.reflectServiceMethod`

Insert one type-based branch before the existing name-based dispatch
(mirrors the `org.jooq.Table` check in `reflectTableMethod`):

```java
for (var p : javaMethod.getParameters()) {
    if (org.jooq.DSLContext.class.isAssignableFrom(p.getType())) {
        String paramName = p.isNamePresent() ? p.getName() : "dsl";
        params.add(new MethodRef.Param.Typed(paramName,
            p.getParameterizedType().getTypeName(), new ParamSource.DslContext()));
        continue;
    }
    // existing Arg / Context / classifySourcesType logic unchanged
}
```

Precedence decision: **type before name.** A parameter whose Java type is
`DSLContext` is a framework-injected handle by construction; we never want a
schema author's `contextArguments: ["ctx"]` directive to shadow it. This
matches `reflectTableMethod`'s ordering for `Table<?>`.

`-parameters` independence: type-based detection does not need the parameter
name. When `-parameters` is present we use the developer-declared name (for
error messages, symmetry with other `Param.Typed` entries); otherwise we fall
back to `"dsl"`. No new warnings.

### No `classifySourcesType` change

`DSLContext` is not a batch-key type. The fix bypasses `classifySourcesType`
entirely — the sources classifier remains `List<?>`-only.

### `reflectTableMethod` — unchanged

Intentional. See "Out of scope."

## Tests

### New: `ServiceCatalogTest` (unit, reflection-level)

No `ServiceCatalog*Test` exists today (grep confirms). Create one with at
minimum:

- `reflectServiceMethod_dslContextParam_classifiedAsDslContextSource` — method
  signature `(DSLContext, String id)` resolves with the first param a
  `Param.Typed(..., ParamSource.DslContext)` and the second a
  `Param.Typed(..., ParamSource.Arg)`. Exercises positional ordering.
- `reflectServiceMethod_dslContextOnly_noArgs` — method signature `(DSLContext)`
  resolves with a single `DslContext` param and no `Sources` param. The
  schema-validator separately rejects table-bound `@service` without a
  `Sources` param; that's unchanged by this plan.
- `reflectServiceMethod_unrecognisedParam_stillErrors` — negative regression:
  a non-DSLContext, non-List param still produces the "unrecognized sources
  type" message.

Test-fixture methods live on a new `TestServiceStub`-adjacent class (e.g.
`TestDslContextServiceStub`) so `TestServiceStub`'s no-arg methods stay intact
for the 20+ existing `GraphitronSchemaBuilderTest` cases that reference them.

### Extended: `GraphitronSchemaBuilderTest`

Add one SDL case that parses an `@service` field whose referenced Java method
declares a `DSLContext` parameter, asserting the resulting `MethodRef` has
`ParamSource.DslContext` in the expected slot and the field is *not*
`UnclassifiedField`.

### Validator behaviour — unchanged

`ServiceFieldValidationTest` already covers the `WITH_LIFT_CONDITION` case
with a hand-built `ParamSource.DslContext` param. No change needed; the
existing stubbed-variant error message is orthogonal to this plan.

## Migration

None. Production schemas currently reporting the validation error will start
classifying successfully. They still land on a stubbed `@service` emitter
(`UnsupportedOperationException` at request time for child service fields,
stub body for root service queries) until the respective emission plans ship.
The classifier fix alone is not user-visible at runtime — but it is visible
at build time: ten fields that currently fail `graphitron:validate` with
*"unrecognized sources type"* stop failing for that reason.

## Follow-ups (not this plan)

- **`@condition` / `@tableMethod` methods accepting DSLContext** —
  `[Unplanned]`. Lift the `reflectTableMethod` rejection once
  `ArgCallEmitter.buildCallArgs` can walk `params()` rather than just
  `callParams()`. File when a real schema needs it.
- **`Set<T>` parent-keys on `@service` methods** — `[Unplanned]`. The
  `java.util.Set` error from the same validator run is a batch-key design
  question (migrate the service signature to `List<T>` vs. grow
  `BatchKey` / `classifySourcesType` to accept `Set`). Out of scope here;
  track separately.

## Open questions

- **Name for the test-fixture class.** `TestDslContextServiceStub` reads
  clumsily but clearly conveys the role. Alternative: fold the new methods
  into `TestServiceStub` under distinct method names (`getWithDsl`,
  `getByIdWithDsl`) so existing references are untouched. Defer to
  implementation.
- **Pipeline-test depth.** One SDL case is the minimum; the three-case unit
  test already pins the classifier behaviour. Adding a pipeline case guards
  against regressions in `FieldBuilder`'s threading of `argNames` / `ctxKeys`
  into `reflectServiceMethod`. Include it.
