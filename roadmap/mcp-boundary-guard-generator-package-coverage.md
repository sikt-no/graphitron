---
id: R716
title: "The MCP boundary guard names one generator package of five"
status: Backlog
bucket: architecture
priority: 3
theme: tooling
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# The MCP boundary guard names one generator package of five

`StoreClientBoundaryTest` asserts that `graphitron-mcp`'s main sources name no generator type, and
it scans for one package prefix, `no.sikt.graphitron.rewrite.`. The generator module publishes four
more: `no.sikt.graphitron.command`, `.facts`, `.plan` and `.render`, together about 84 classes. A
fully-qualified mention of any of them in a main source passes the guard, verified by planting
`{@code no.sikt.graphitron.plan.SelectionPlan}` in a class javadoc and watching all six cases stay
green where the same probe under `rewrite.` fails the build.

The property itself is not at risk. The pom half of the guard pins `graphitron` to test scope, so an
*import* of any generator package cannot compile in main sources whatever the scan says. What slips
through is the case the scan exists for and the compiler cannot see: a `{@code}` or prose reference,
which is precisely the species that left four citations standing through every slice of the item
that wrote this guard, and seven more outside the module. Widening the needle to the module's package
root and excluding the store's own `no.sikt.graphitron.model.` would close it; whether the simple-name
case (a bare `{@code CatalogBuilder}`, which no prefix scan can catch) is worth a second mechanism is
the question the spec should answer.
