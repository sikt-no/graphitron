---
id: R857
title: "A dev start evaluates the whole materialization register twice, the second pass producing identical rows"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A dev start evaluates the whole materialization register twice, the second pass producing identical rows

`DevMojo.execute` runs the initial generator pass, whose capture already refills every registered
materialization for the graph it captured, and then calls `Materializations.refreshAll` on the
session store. `refreshAll` refills every registration for every graph the store holds,
unconditionally. On the ordinary case, one graph and an initial run that was not skipped, that is the
entire register evaluated a second time to produce the rows the first pass just wrote.

## Why nothing flags it

`refreshAll` is correct, and its javadoc says why it exists: it is "the entry point for a reader that
opens a store it did not capture into", correct whether or not a capture ever ran, and idempotent.
Every word of that is true. The redundancy is not a property of `refreshAll` but of the one caller
that reaches it immediately after a capture in the same JVM, where the precondition it is defensive
about cannot hold.

Idempotence is what hides it. The second pass is invisible in the output because it changes nothing,
and it is invisible in the log because the refresh emits nothing at all, which is a sibling item.

## What it costs

One full evaluation of the register, on the cadence of every `graphitron:dev` start. On a consumer
schema measured for the sibling hang item, one pass over sixteen registrations is about 200 seconds,
so the doubling is not a rounding error; on a small schema it is small. The cost scales with the
store rather than with the session: `refreshAll` loops graphs in its inner loop, so a store holding
several graphs pays one evaluation per graph per registration, where the capture paid one for the
session's own graph.

There is a second-order effect worth stating because it bears on the fix. `refreshAll` calls
`analyse` inline and the capture path calls it after its transaction closes, so both passes also
re-gather statistics.

## Shape of the work

The narrow fix is a condition: skip the refresh for a graph a capture in this session has already
refreshed. The question a Spec should answer first is where that knowledge belongs. The mojo knows
whether it ran an initial pass and which graph it captured, so the caller can decide; but a
`refreshAll` that is safe to call and expensive to call twice is a shape that invites this bug at the
next caller, and pushing the decision into the store, so that a refresh knows whether its inputs have
changed since the target was last filled, is a different and larger change.

Note that skipping is only sound when the capture actually ran and covered that graph. Both
disqualifying cases are live and neither is exotic: `skipInitial` leaves the register unfilled, and a
store holding a graph this session did not capture still needs the whole pass for that graph.

## Related

The sibling logging item would have made this visible without reading the source, which is how both
sessions that found it found it instead. R848 asks whether the register needs to be this large at
all, which is upstream of how many times it is evaluated.
