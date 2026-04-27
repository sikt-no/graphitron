---
title: Rewrite `runtime-extension-points.md` for the rewrite runtime
status: In Review
bucket: cleanup
priority: 3
---

# Rewrite `runtime-extension-points.md` for the rewrite runtime

> **Implementation:** shipped. The doc was rewritten against the actual
> rewrite-emitted `GraphitronContext` interface, with all four drift sites
> closed and `getTenantId` documented for the first time. See
> §"What landed" below for a summary of the changes.

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
ground via a new "Where each concern belongs" paragraph. The Backlog file
was deleted in the same split commit (`99b037e`).

---

## What landed

- **Interface description.** Replaced the legacy `graphitron-common` location
  with the rewrite-emitted shape: per-app generation under
  `<outputPackage>.rewrite.schema`, written by
  `GraphitronContextInterfaceGenerator`, no shared runtime jar.
- **Three actual methods.** `getDataLoaderName` was wrong; the emitted
  interface has `getTenantId`. Added a dedicated section for `getTenantId`
  documenting the contract (Graphitron concatenates `getTenantId(env) + "/"
  + path keys` to build DataLoader registry keys; only the tenant prefix is
  pluggable, the path component is Graphitron-controlled). This contract
  was not previously documented anywhere outside `TypeFetcherGenerator`'s
  Javadoc.
- **Registration example.** Replaced the legacy
  `Map.of("graphitronContext", new DefaultGraphitronContext(ctx))` example
  with the typed-key form (`b.put(GraphitronContext.class, ctx)`) the
  generator actually emits, and lifted the full hello-world wiring to a
  pointer at `getting-started.md` rather than duplicating it inline.
- **Generated helper snippet.** Updated to show
  `env.getGraphQlContext().get(GraphitronContext.class)`, matching
  `TypeFetcherGenerator.buildGraphitronContextHelper`.
- **"Where each concern belongs" paragraph.** Three-layer comparison
  (jOOQ `Configuration` for cross-cutting jOOQ behaviour; `getDslContext`
  for per-request decisions; schema directives for SDL-author-readable
  business semantics). Closes the
  `graphitroncontext-extension-point-docs.md` scope.
- **Cross-references.** "See also" no longer points at
  `graphitron-common/README.md`; redirected to the rewrite docs (Getting
  Started, Code Generation Triggers, repo-level Security Model) which all
  exist and are current.

The legacy "real code" snippets attributed to
`graphitron-example-server/GraphqlServlet.java` and
`QueryCapturingExecuteListener.java` were dropped: the Configuration
example survives as an illustrative jOOQ pattern with no module
attribution; the `ExecuteListener` example was cut, and the section now
links out to jOOQ's own docs.
