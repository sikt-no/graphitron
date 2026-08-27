---
id: R855
title: "The materialization refresh emits nothing, so a hang inside it is anonymous"
status: Backlog
bucket: dx
priority: 1
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# The materialization refresh emits nothing, so a hang inside it is anonymous

`Materializations.refresh` walks the registered materializations in dependency order and issues a
`DELETE` and an `INSERT ... SELECT` per registration. It logs nothing at any point. On a schema
where one of those statements is slow, the console goes silent after whatever line happened to print
last, and stays silent for as long as the statement runs.

## Vocabulary

A **registration** is a row of `meta_materialize`: a rule that stays written in a view under a
`_live` name, and a table of the same shape under the canonical name every reader spells. The
**refresh** is the pass that refills those tables, once per capture, and it runs inside the capture's
own transaction. The **refresh order** is derived from the stored view definitions, so the pass is a
flat sequence of statements whose order is a property of the schema rather than of the caller.

## What this cost, concretely

Two Claude sessions spent an afternoon locating a refresh that did not finish on a consumer schema.
What it took, in order: three thread dumps of two live JVMs to reach
`Materializations.refreshPartition`; four copies of on-disk store files out of the per-user cache;
a per-relation timing harness over one of them; and a hand-reproduced Kahn sort over
`meta_materialize_dependency` to turn the stack frame into a position in the sequence. What came out
of all of that was a price list, a two-way suspicion, and an argument about whether the suspicion
followed from the prices at all. It never became a name, and the sibling item now carries that
question open because no instrument in the tree can close it.

One log line per registration, printed before its `INSERT` is issued, would have named the relation
outright in the first thirty seconds. Every wrong turn either session took, and there were several
recorded in the sibling item, was a substitute for a fact the pass already knows and does not say.

The narrower cost is ordinary rather than dramatic and it is the reason to do this even if no
relation is ever pathological again: a person watching a build has no way to tell a refresh that is
working from one that is stuck, and no way to tell which of twenty evaluations a slow capture is
spending its time in.

## Why the obvious shape of the fix is wrong

Timing each registration and logging the result after it returns is the shape a reader reaches for
first, and it fails at exactly the moment it is needed. A statement that does not return emits
nothing under that shape, so a healthy store would look instrumented and a stuck one would look
identical to today.

The name has to be emitted **before** the statement is issued. What may be emitted afterwards is the
duration, which is the ordinary-case value and the part that makes a slow-but-finishing pass legible.
Any plan for this item should state which of the two it is doing at each point, because the
difference is the whole value of the change on the case that motivated it.

## What is not settled

Where the output goes. `Materializations` sits in `graphitron-model` and has no logger today, while
the callers that would want the output are a Maven mojo with a `getLog()` and a language server
without one. Whether this is a logger, a callback the caller supplies, or rows written into the store
itself is a design question and not an obvious one; the store-shaped answer has the attraction that
the fact store is where this repository puts facts, and the drawback that a hang inside the capture
transaction never commits, so store-written progress is invisible in precisely the failing case.

Whether the refresh should also carry a slow-relation warning, rather than only a record, is a
separate question this item should decide rather than assume.

## Related

The sibling item on the mutation-payload refresh is the failure this one is the instrument for, and
it should be read first for the measurements. R848 asks whether the register's shape is right at all;
this item takes the register as given and only asks it to say what it is doing.
