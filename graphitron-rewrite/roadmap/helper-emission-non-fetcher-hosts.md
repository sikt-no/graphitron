---
id: R85
title: "Emit graphitronContext helper into Conditions and Type classes"
status: Backlog
bucket: cleanup
depends-on: [type-fetcher-helper-emission-gate]
---

# Emit `graphitronContext` helper into Conditions and Type classes

`@condition(contextArguments: [...])` is a documented feature
(`docs/getting-started.adoc:198,226`, `runtime-extension-points.adoc:96-101`)
but its generated output does not compile. The classifier produces
`CallSiteExtraction.ContextArg` for context-bound filter parameters
(`MethodRef.java:111`, exercised at `GraphitronSchemaBuilderTest.java:2768`);
`ArgCallEmitter.buildArgExtraction`'s `ContextArg` arm emits
`graphitronContext(env).getContextArgument(env, ...)`; the call lands in
`<RootType>Conditions.<field>Condition()` (via `QueryConditionsGenerator`)
or in `<TypeName>.$fields()` (via `InlineTableFieldEmitter` /
`InlineLookupTableFieldEmitter`). Neither host class emits a
`graphitronContext` helper, so the generated source fails at
`mvn compile -pl :graphitron-sakila-example` with "cannot find symbol:
graphitronContext". The bug is currently latent because no fixture in
`graphitron-sakila-example` or `graphitron-fixtures-codegen` uses
`contextArguments` on `@condition`.

R80 closed the same hole on the `*Fetchers` side by recording helper
requests through `TypeFetcherEmissionContext`. The R80 review surfaced that
`QueryConditionsGenerator`, `InlineTableFieldEmitter`, and
`InlineLookupTableFieldEmitter` instantiate a throwaway context and discard
it (`QueryConditionsGenerator.java:107-109`), and the as-shipped notes
deferred a structural fix. R83 is that fix.

## Scope

- Generalise `TypeFetcherEmissionContext` into a class-agnostic
  `EmissionContext` (rename, drop the `TypeFetcher` prefix; the
  `HelperKind` taxonomy stays single-valued for now).
- Make `QueryConditionsGenerator` and `TypeClassGenerator` instantiate an
  `EmissionContext` per class assembly, thread it through every emitter
  that targets that class, drain at assembly, and emit
  `graphitronContext` when the context recorded a request. The throwaway
  contexts at `QueryConditionsGenerator.java:109`,
  `InlineTableFieldEmitter.java:144`, and
  `InlineLookupTableFieldEmitter.java:218` delete; their callers receive
  the live context from the class assembler instead.
- Correct the misleading comment at `QueryConditionsGenerator.java:107-108`
  (claims `ContextArg` cannot reach this site; it can).
- Add a compile-tier fixture: a `@condition(contextArguments: [...])`
  field on a `@table` type in `graphitron-sakila-example/schema.graphqls`
  whose user-written method is referenced from a `<RootType>Conditions`
  call site. The fixture exercises the
  classify → emit → `mvn compile` path end-to-end so the bug class cannot
  go latent again.

## Out of scope

The four other class generators that emit a `graphitronContext` helper
ad-hoc (`EntityFetcherDispatchClassGenerator:218`,
`ConnectionHelperClassGenerator:280`,
`QueryNodeFetcherClassGenerator:211`, plus `TypeFetcherGenerator:481`
itself) are not unified onto `EmissionContext` here. A separate cleanup
item can fold them in once R83's pattern proves out across three sites.
