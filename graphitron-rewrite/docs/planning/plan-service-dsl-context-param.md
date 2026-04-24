# `DSLContext` parameters on `@service` methods

> **Status:** Done
>
> `ServiceCatalog.reflectServiceMethod` now recognises `org.jooq.DSLContext`
> parameters by type and emits `MethodRef.Param.Typed` carrying
> `ParamSource.DslContext`. Previously such parameters fell through every
> classification arm and the field was rejected with *"unrecognized sources
> type: 'org.jooq.DSLContext'"*. Scope was deliberately limited to
> `@service` methods; `reflectTableMethod` is unchanged.

## Shipped

- Type-based branch at the top of the per-parameter loop in
  `ServiceCatalog.reflectServiceMethod` — mirrors the `org.jooq.Table` check
  in `reflectTableMethod`. Uses the developer-declared name when
  `-parameters` is present, else falls back to `"dsl"`.
- `ServiceCatalogTest` (new) — four unit cases: DSLContext with trailing
  arg, DSLContext alone, DSLContext name colliding with a GraphQL argument
  (type wins), and a negative case asserting the "unrecognized sources
  type" message still fires for truly unknown parameters.
- `GraphitronSchemaBuilderTest.SERVICE_FIELD_DSL_CONTEXT_PARAM` — one
  pipeline case that parses SDL with an `@service` field whose Java method
  declares a `DSLContext` parameter, asserts the resulting field is not
  `UnclassifiedField` and carries a single `ParamSource.DslContext` param.
- `TestServiceStub` — four fixture methods (`getWithDsl`, `getByIdWithDsl`,
  `getFilteredWithDsl`, `getWithUnknown`) backing the new tests.

No call-argument emitter is affected: `MethodRef.callParams()` filters to
`Arg`/`Context` only, and all current service emitters are stubs. The
new variant is visible to future emitters via `params()`.

## Out of scope / follow-ups

- **`@condition` / `@tableMethod` methods accepting DSLContext** —
  `[Backlog]`. `reflectTableMethod` still rejects DSLContext with the
  existing "not a Table<?>, not a GraphQL argument, and not a context
  key" message. Lifting that gate requires
  `ArgCallEmitter.buildCallArgs` to walk `params()` rather than
  `callParams()` so it can inject DSLContext at the correct positional
  slot. File when a real schema needs it.
- **`Set<T>` parent-keys on `@service` methods** — `[Backlog]`. The
  `java.util.Set<...Record>` error from the same validator run is a
  batch-key design question (migrate the service signature to `List<T>`
  vs. grow `BatchKey` / `classifySourcesType` to accept `Set`). Track
  separately.
