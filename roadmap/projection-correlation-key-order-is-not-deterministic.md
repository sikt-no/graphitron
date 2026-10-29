---
id: R1127
title: "Generated projection output is deterministic: the order of correlation-key columns is stable from run to run"
status: Backlog
bucket: bug
priority: 6
theme: codegen-correctness
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# Generated projection output is deterministic: the order of correlation-key columns is stable from run to run

## Goal

Regenerating `graphitron-sakila-example` twice from the same sources gives the same output every time. Today it does not: in some projection units, the `fields.add(...)` lines of a multi-term `case` arm swap places between runs (for example `fields.add(table.CATEGORY_ID)` and `fields.add(table.PARENT_CATEGORY_ID)` in the generated `Category` projection), so a regeneration with no input change can rewrite a file and mark its unit as changed, which forces a needless recompile in the dev loop and churns a consumer's generated diff.

## What was seen

Found while diffing the output of the JavaPoet fork against Palantir's. Two regenerations of the same tree, on the same machine, from clean, differed from each other in a different handful of files each time (`Category`, `Store` and `DivergedRefChild` between them), and every difference was the same lines in a different order. The fork's tree matched itself on two runs, which is not evidence of stability, since the ordering varies by run. Both trees ran the same generator code, so this predates the emitter switch.

## Where to look

The multi-term arm in `ProjectionUnitRenderer.emitProjectArm` emits one `fields.add` per entry of `Contribution.Project.terms()`, in list order, so the order is fixed upstream of the renderer. `ProjectionCommands.correlationKeyArm` builds those terms from the columns its callers pass, which come from `parentRowColumns()` and `sourceKey().columns()`. The root cause is not confirmed; the first thing to establish is which of those lists is not ordered by anything stable.

The fix is a stable order at the source, such as the declaration order of the key, and a test that regenerates twice and compares, so the property is held by a check and not by luck.
