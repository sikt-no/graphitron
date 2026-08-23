---
id: R816
title: "Establish whether a routine-write re-read can silently drop its first hop condition"
status: Backlog
bucket: generator
priority: 3
theme: routine
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# Establish whether a routine-write re-read can silently drop its first hop condition

A `@reference` path element can carry a `condition:` sub-argument, which resolves to a
two-argument method the generator appends to the enclosing query's `WHERE`. On a mutation field
that writes through `@routine`, the emitted fetcher runs the routine inside a transaction and
then re-reads after the commit, departing from the chain's first hop and joining the rest
forward. That re-read emits the `condition:` of every hop after the first and never the first
hop's own.

The omission is forced rather than careless, and the shape says why. A hop's condition method
takes the departure table and the arrival table. The first hop of a routine chain departs from
the routine's own result, and that result appears in no statement after the one that ran it,
because re-invoking it would re-execute the write. So there is no argument to give the method,
and the re-read cannot emit the call at all. Until this item, that rule lived only in a loop
index; `RoutineWriteCommand.RereadAnchor` now states it, carrying no filter slot and saying so.

What is not established is what happens to an author who writes one. Three questions, in order,
and only the first needs answering before the item is worth planning:

1. Can such a schema be written and classified? A `condition:` on the first path element of a
   mutation-root `@routine` chain, with a resolvable two-argument method against the routine
   result and the arrival table.
2. If it can, does anything reject it? Neither the classifier's re-read-anchor verdict nor the
   validator is known to look at the first hop's filter, so the likely answer is that the
   generated fetcher compiles, runs, and returns rows the author believes are filtered.
3. If nothing rejects it, the fix is a located rejection naming the coordinate and the element,
   not an emission: the filter genuinely cannot be expressed at that position, so the author
   has to move the predicate or restructure the chain, and being told that is the whole
   deliverable.

Found while narrowing the routine-write command off the walk's chain carrier. Filed rather than
fixed there: the narrowing changed no emitted output, and answering question 1 needs a fixture
this item should own.
