---
id: R730
title: "Dead NodeDeclaration imports and a stale link survive the capture-API narrowing"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Dead NodeDeclaration imports and a stale link survive the capture-API narrowing

Capture's entry points used to take a nodehood predicate beside the jOOQ catalog. R711 removed that
parameter from every one of them, because deciding nodehood needs the catalog and a crawler answering
for the SDL may not read it. The removal is complete in the code that runs. What it left behind is
eight lines of prose and imports that still name the parameter.

Seven files import `no.sikt.graphitron.rewrite.NodeDeclaration` and no longer mention it anywhere
else, so the import is dead: `SdlFactCapture` in `graphitron`'s main sources, the `StoreFixture`
helpers in `graphitron-lsp` and `graphitron-mcp`, and four tests
(`FactCaptureAgreementTest`, `PersistentStoreTest`, `TypeBackingClassTest`, `DiagnosticFactsTest`).

The eighth is the one worth the item. `WarmStartRefreshTest`'s
`aWarmRefreshOverAMultiPackageCatalogCompletes` carries a `{@link}` naming a `FactCapture.capture`
overload whose parameter list ends `..., List, NodeDeclaration)`. No such overload exists, so the
link resolves to nothing. This is exactly the rot the reactor's javadoc reference gate exists to
catch: the parent pom binds doclint's `reference` group to `verify` so a dangling `{@link}` fails the
build. The gate runs javadoc's `javadoc` goal, which reads main sources only, so a broken link in a
test source is invisible to it and shipped clean here.

Two things to settle when this reaches Spec. The narrow fix is eight one-line edits and needs no
plan. The question worth a plan is whether the reference gate should see test sources at all, since
test javadoc is where this project puts a good deal of its reasoning, and a link that cannot rot is
the stated reason `{@link}` is preferred over prose in the first place. Extending the gate costs
wall-clock on every build and would likely surface pre-existing dangling links beyond this one, so
the size of that backlog wants measuring before the gate moves.

