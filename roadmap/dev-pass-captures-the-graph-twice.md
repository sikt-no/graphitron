---
id: R859
title: "A dev pass captures the same graph twice, so every save evaluates the register twice"
status: Backlog
bucket: dx
priority: 2
theme: dev-loop
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A dev pass captures the same graph twice, so every save evaluates the register twice

A dev pass runs the generator twice and each run captures. At startup `DevMojo.execute` calls
`runGeneratorPass` and then `buildOutputQuietly`; on every schema save `DevMojo.regenerate` calls
`runGeneratorPass` and then `buildOutput` inside one codegen scope. Both generator entry points reach
`GraphQLRewriteGenerator.captureAndRead`, so both write the graph's whole partition and both end with
`Materializations.refresh` for that graph inside their own transaction. Two captures of one graph,
milliseconds apart, from one `RewriteContext`, and the second writes the rows the first just wrote.

The visible cost is the materialization register: one evaluation of every registration per capture,
which on a consumer schema measured for R856 is around 200 seconds per pass. So a developer pays it
twice at startup and twice on every save, and the second one buys nothing. The register is only the
dearest part; the whole capture, the fact writing and the classification are paid twice too.

Why the second pass exists is the question to answer before removing it. `buildOutput` produces the
LSP catalog and the diagnostics lists the dev session publishes, `runGeneratorPass` produces the
emitted tree and the compile graph, and `buildOutput` is written as a separate lifecycle step over
the same context (`GraphQLRewriteGenerator` splits the two). Candidate shapes: one pass producing both
products, the catalog read off the generation that already ran, or capture becoming idempotent enough
to recognise that its inputs have not moved since the pass before it. The last is where R857's fill
record may reach, and this item should be specced after it, not before.

R620 is the same doubling seen through the classpath scan and states measurement discipline this item
should follow: measure the second pass before designing anything, since the fix for a pass that costs
milliseconds is different from the fix for one that costs minutes.

