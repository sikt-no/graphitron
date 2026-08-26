---
id: R845
title: "The reference tables still state live generating patterns as hand-maintained rows"
status: Backlog
bucket: architecture
priority: 4
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---

# The reference tables still state live generating patterns as hand-maintained rows

`docs/architecture/reference/code-generation-triggers.adoc` teaches each classification through a
worked example: SDL rendered from the test corpus, an outcome block stating what the pipeline made of
it, and prose. Both halves are held verbatim by build guards, so an example cannot drift from what
the generator does. Around those examples sit reference tables, the transitional remainder, whose rows
state a verdict as hand-maintained prose with no gate at all.

Most of those rows are refusals, and R842 takes them: a refusal generates nothing, so no worked
example can carry it and gathering them in one place is the right move. This item takes what R842
excludes, the rows that describe patterns the generator really does emit code for and that simply have
no example yet:

- the two root `@service` rows in Query Fields (a `@table` return re-queried through the catalog, and
  a non-table return materialized from the service's own value),
- the encoded-`ID` row in Mutation Fields,
- the `@sourceRow` row under Child Fields on a class-backed parent,
- the six Input Fields rows, which are the input-side table entire.

Each is a live pattern stated in ungated prose beside examples that are gated, which is the exact
asymmetry the page was rebuilt to remove. A reader cannot tell from the page which claims are checked.

**Sequencing.** This work is deliberately not urgent, and doing it now would cost more than waiting.
Promoting a row today means the manual loop: extend the corpus fixture, run the harness to capture the
rendered SDL, run it again to capture the outcome block, paste both under new prose, delete the row.
R840 exists to abolish that loop by regenerating those artifacts from the store. Promoting five or six
rows by hand first buys drained rows on artifacts R840 plans to regenerate, and pays the paste cost
twice. R682 sharpens the same point from the other side: these rows are written in the sealed leaf
vocabulary (`ColumnBackedField`, `BatchedTableField`, `InputField.*`) that R682's terminal step
deletes, so an example authored before the re-key states its verdict in a vocabulary that has to be
rewritten anyway.

So: after R840, and with R682's re-key either landed or close enough that the new prose can be written
in the surviving vocabulary. Filed now because the alternative is that the asymmetry has no owner and
each session that reads the page re-derives the same finding.

**The input-side table is the one part that needs its own answer first.** The corpus asserts output
field and type verdicts; input-field leaves are deliberately kept on the enum truth table
(`VariantCoverageTest` partitions the two and does not union them). So there is no vehicle to promote
an input row into, and the first question for the Spec is whether the corpus grows an input-side half,
whether the input table gets a differently-shaped gate, or whether the page stops trying to enumerate
the input side at all.

