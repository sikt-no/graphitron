---
title: Rewrite `runtime-extension-points.md` for the rewrite runtime
status: Backlog
bucket: cleanup
priority: 3
---

# Rewrite `runtime-extension-points.md` for the rewrite runtime

`graphitron-rewrite/docs/runtime-extension-points.md` describes the legacy
`graphitron-common` runtime, not the rewrite. It is the single most misleading
doc in the rewrite tree today: a new contributor reading it to understand
how to wire `GraphitronContext` will write code that does not match what the
generator actually emits.

## Concrete drift

Verified against `TypeFetcherGenerator.buildGraphitronContextHelper`,
`GraphitronContextInterfaceGenerator`, and `GraphitronFacadeGenerator`:

- Line 19 says the interface lives at
  `graphitron-common/src/main/java/no/sikt/graphql/GraphitronContext.java`.
  The rewrite emits its own interface per app under
  `<outputPackage>.rewrite.schema.GraphitronContext`. The generator never
  references `graphitron-common`.
- Lines 29-39 illustrate registration with
  `Map.of("graphitronContext", new DefaultGraphitronContext(ctx))` and
  retrieval with `env.getGraphQlContext().get("graphitronContext")`. The
  emitted helper uses the typed key:
  `env.getGraphQlContext().get(GraphitronContext.class)`. `getting-started.md`
  already documents the correct form; this file contradicts it.
- Lines 25 and 108-112 list `getDataLoaderName(env)` as the third method on
  the interface. The actual emitted interface has `getTenantId(env)`;
  Graphitron concatenates the tenant id with the field path internally to
  build DataLoader names.
- Line 215 cross-links to `graphitron-common/README.md` as the
  `GraphitronContext` API reference. There is no rewrite-relevant API
  reference there.

## Scope

Rewrite the file around the rewrite-emitted interface. Specifically:

- Open with the three actual methods (`getDslContext`, `getContextArgument`,
  `getTenantId`) and the typed-key registration shape.
- Lift the wiring example to a one-liner pointer to `getting-started.md`
  (which already covers Hello World, JWT-claim context arguments, and
  tenant-scoped `DSLContext`); keep this file focused on what it adds beyond
  Getting Started.
- Preserve the existing "Complementary Technologies" coverage (jOOQ
  Configuration, jOOQ `ExecuteListener`, PostgreSQL RLS) that distinguishes
  Graphitron-specific extension points from general jOOQ capabilities.
  This is the part that is still useful and not covered elsewhere.
- Delete or rewrite the `DefaultGraphitronContext` example. There is no
  default implementation in the rewrite tree.

## Coordinates with

Supersedes `graphitroncontext-extension-point-docs.md` (Backlog, priority 8).
That item asked for "what belongs in `GraphitronContext` vs jOOQ
`ExecuteListener` vs schema directive"; the rewritten doc covers the same
ground. Delete `graphitroncontext-extension-point-docs.md` when this lands.
