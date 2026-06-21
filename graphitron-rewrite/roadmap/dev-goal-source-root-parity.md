---
id: R351
title: "graphitron:dev walks every scanned modules sources (compileSourceRoots / classpathRoots parity)"
status: Backlog
bucket: bug
priority: 4
theme: lsp
depends-on: []
created: 2026-06-21
last-updated: 2026-06-21
---

# graphitron:dev walks every scanned modules sources (compileSourceRoots / classpathRoots parity)

The dev goal's two root-resolution paths are not in parity. `resolveClasspathRoots`
(`AbstractRewriteMojo`) collects every reactor module's `target/classes` and feeds it to
`ClasspathScanner`, so a `@service`/`@condition` class compiled anywhere in the reactor is
*scannable* (appears in completion). But the source positions for those classes come from
`SourceWalker.walk(ctx.compileSourceRoots())`, and `compileSourceRoots` does not reliably
cover the same set of modules: a class can be on the scan path (classes present) while its
`src/main/java` is absent from the walk path (sources not walked), so it resolves in
completion but has no goto-definition / hover position.

This is the concrete, reproduced trigger behind R349's field report: completion works,
goto-def silently returns nothing. It is a small, mojo-local fix and the fastest way to
unblock affected users, so it can ship ahead of R349's deeper decoupling.

## Scope

- Make the dev goal's source-root resolution cover the same module set its classpath-root
  resolution does: any module whose compiled classes are scanned for `@service` candidates
  should have its source root walked. The likely shape is a `resolveCompileSourceRoots`
  that mirrors `resolveClasspathRoots` over `session.getAllProjects()` (a sibling already
  exists; confirm it iterates the same projects and that generated-source roots registered
  by prior plugins are included), so a scanned class is always a walkable class.
- Pin it with a test at the appropriate tier. The resolution itself is mojo-local and not
  reachable from the pipeline tier; an integration/execution-tier check that a multi-module
  reactor surfaces every scanned module's source root is the honest level. The
  catalog-level consequence (a scanned ref gets a non-absent position when its module's
  source is on the build) is already covered by the R349 work and `CatalogBuilderSourceTest`.

## Relationship to R349

This is the stopgap; R349 is the durable fix. Even with perfect root parity, positions
still ride the generator build cadence (R349's decoupling addresses that) and a
genuinely source-absent class still needs the typed-outcome handling R349 introduces. Ship
this first to stop the bleeding; R349 makes the bleeding structurally impossible.
