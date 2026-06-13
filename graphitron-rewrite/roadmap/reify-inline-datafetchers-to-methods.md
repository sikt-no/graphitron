---
id: R303
title: "Reify inline datafetchers into named XFetchers methods"
status: Backlog
bucket: architecture
depends-on: []
created: 2026-06-13
last-updated: 2026-06-13
---

# Reify inline datafetchers into named XFetchers methods

Most generated `DataFetcher`s for a GraphQL object type are emitted as inline value
expressions in `<Type>Type.registerFetchers(GraphQLCodeRegistry.Builder)`: lambdas
(`(env) -> env.getSource()`, the R244/R268 arm-switch ternaries, the R75/R156/R275
record-walking blocks) and `new ColumnFetcher<>(...)` instantiations, all produced by
`FetcherEmitter.dataFetcherValue`. Only the non-trivial method-backed fields resolve to a
`<Type>Fetchers::method` reference. The split means a generated datafetcher often has no
named symbol: you cannot set a breakpoint on it, name it in a stack trace, or look it up by
field. The lambdas are anonymous synthetic methods on the `…Type` class, not on the
`fetchers` package where a consumer would look.

The goal is to reify every datafetcher into a named `public static` method on the field's
`<Type>Fetchers` class so the registration site is uniformly a `<Type>Fetchers::<field>`
method reference, never an inline lambda or `ColumnFetcher` instantiation. Triviality is not
a reason to keep a fetcher inline; a named method is debuggable and discoverable. The seam is
`FetcherEmitter.dataFetcherValueRaw` (currently returns inline `CodeBlock` value expressions)
plus `TypeFetcherGenerator.generateTypeSpec` (where the corresponding methods would be added),
with the nested-type and connection/edge wiring in `FetcherRegistrationsEmitter` to account
for. Scope and the treatment of the connection/edge helper references and the `@error`-type
field fetchers in `GraphitronSchemaClassGenerator` are open questions for the Spec.
