---
id: R856
title: "A consumer-schema capture spends over an hour inside the materialization refresh"
status: Backlog
bucket: bug
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-27
last-updated: 2026-08-27
---

# A consumer-schema capture spends over an hour inside the materialization refresh

On a real consumer schema, `mvn graphitron:validate` and `mvn graphitron:dev` both spend more than an
hour inside the materialization refresh, and no capture has been observed to finish. In practice
everyone kills them, and `graphitron:dev` never binds its language server or MCP ports at all,
because the bind sits behind the initial run.

Whether this terminates at all is not established. The recursive terms carry path guards, which is a
structural argument that the walk is bounded rather than infinite, and it is the only argument there
is: no completion has ever been observed on a DDL carrying the two suspect registrations. Treat it as
combinatorial blowup of unknown magnitude rather than as either a deadlock or a known-finite wait,
and be careful about the word "hang", which invites a deadlock hunt this evidence does not support.

## Vocabulary

A **registration** is a row of `meta_materialize`: a rule kept in a view under a `_live` name, and a
table of the same shape under the canonical name every reader spells. The **refresh** refills those
tables once per capture, walking them in an order derived from the stored view definitions, and it
runs inside the capture's own transaction. The **consumer schema** here is an internal one at Sikt,
about 8,400 fields and 2,300 types.

## What is established

Two JVMs, different goals, same frame, each pegged on one core with cpu time tracking elapsed time,
so spinning rather than blocked or starved:

- `graphitron:dev`, started 11:16, exited ~12:19 without binding.
- `graphitron:validate -pl <consumer module>`, started ~11:11, gone by ~12:32.

Every thread dump showed the same stack:

```
FactCapture.capture
  Materializations.refresh
    Materializations.refreshPartition        <- INSERT INTO <target> SELECT * FROM <target>_live
      org.h2.index.RecursiveIndex.find
        <- IndexCursor.find <- TableFilter.next <- Select.gatherGroup
```

So the time is in the refresh, and the shape is a recursive term re-evaluated under a grouped select.
That much is measured and is not in doubt.

The cost is the expansion rather than any child. Timed in isolation against a populated store of this
schema, the children of the two most expensive relations answer in 1 to 206 milliseconds each, four
of them totalling under 0.7 seconds, while the parents take 35 and 59 seconds. There is nothing
expensive underneath to reach for.

## The priced order, and what it does not prove

Both slow processes ran a 16-registration DDL. That is measured for the first, whose open file
descriptors named its store, and inferred for the second, which started 76 minutes before the
installed model jar changed. The order was reproduced from that store's own
`meta_materialize_dependency` rows, and every registration ahead of the last two was timed in
isolation against a populated store of the same schema:

| # | registration | one evaluation |
|---|---|---|
| 1 | `intent_argmapping_pair_live` | 0.2 s |
| 2 | `intent_errors_field_live` | 20 s |
| 3 | `intent_spelled_table_live` | 0.1 s |
| 4 | `intent_field_reference_step_hop_live` | 3 s |
| 5 | `intent_resolved_type_binding_live` | 0.1 s |
| 6 | `intent_carrier_data_field_live` | 59 s |
| 7 | `intent_field_column_scope_live` | 35 s |
| 8 | `intent_argument_scope_table_live` | 22 s |
| 9 | `intent_argument_column_scope_live` | 0.5 s |
| 10 | `intent_input_field_resolving_table_live` | 0.03 s |
| 11 | `intent_node_id_instruction_live` | 5.3 s |
| 12 | `intent_argument_column_match_live` | 0.7 s |
| 13 | `intent_input_field_filter_role_live` | 47 s |
| 14 | `intent_node_id_decode_hop_column_live` | 4.9 s |
| 15 | `intent_mutation_payload_refusal_live` | **unmeasured** |
| 16 | `intent_mutation_payload_column_live` | **unmeasured** |

Positions 1 to 14 total 199 seconds and are exactly the set of registrations the populated store
holds, so nothing ahead of the tail is unmeasured and nothing is argued cheap from its shape. The
tempting conclusion is that a run still going at 80 minutes must be at position 15 or 16.

**That inference does not carry on its own, because of a flaw in the comparison.** The 199 seconds
were measured post-commit, against a settled store, on connections that wrote nothing. The 63 and 80
minutes are in-transaction wall clock for a whole capture. Those are not the same quantity: some of
that hour is parse, catalog reflection, fact-writing, classification and compilation rather than
refresh, and an in-transaction refresh may cost more than the same relations cost measured
afterwards. So read the table as a price list. It says which relations are expensive when evaluated
against a settled store, and it is not by itself a statement about where an hour goes.

## The population fact, which does carry

The localisation rests on this instead, and it needs no cross-quantity comparison at all.

A survey of all fifteen stores this schema has left on disk finds that every store at 16 or 20
registrations holds zero graphs and zero fields. Not one has ever been captured into. The only
populated stores are at 14 registrations or fewer, and the newest of them carries
`store_graph.LAST_CAPTURED = 2026-08-26 14:30:25`, which is the most recent successful capture of
this schema on this machine.

The plugin carrying the 16-registration DDL was installed nineteen minutes later, at about 14:49 the
same day. From that install onward, no capture has completed.

So the two tail registrations appeared, and captures stopped finishing, with nineteen minutes between
the last success and the DDL that added them. That is a before-and-after in the population rather
than an argument from durations, and it is the strongest evidence this item has.

Note what it is not. It gives no duration for the successful 08-26 capture, so nothing here says what
this schema costs when it works, and no figure should be invented for it.

## What can be measured now, and what a result would license

This prices the transaction boundary, which is a separate question from where the hour goes. It was
once thought to be the thing that decided the localisation; it is not, the population fact above
having settled that without it. It still matters, because if the boundary is expensive then the fix
may be capture's transaction shape rather than the two rungs.

The boundary controls two things, and only one of them can be priced against a store that already
exists. Anyone reaching for this should know which half they are buying before they run it.

**The half that is runnable today.** On a copy of the 14-registration store, time the same
`SELECT * FROM <target>_live WHERE graph_name = ?` twice: once inside a transaction that has first
touched the fact tables the target depends on, so they count as updated in it, rolling back
afterwards; and once in autocommit. That sets the updated-in-transaction condition on the real
driving tables at full scale, which is a better instrument than toggling the session's reuse flag,
and it prices the cache and dirty-flag half of the boundary.

**The half that is not.** Absent statistics. Any store on disk here was written by a capture that
completed, so `Materializations.analyse` has run and selectivity is populated, and a copy inherits
it. Both arms above therefore run with good statistics, which is exactly the variable that differs
inside a real capture, where the rows were written moments earlier and the plans are chosen before
any analysis. Pricing that half needs either a way to reset selectivity that someone has confirmed
against the pinned H2, the syntax in circulation being from the 1.x line, or a cold capture that
completes on a DDL carrying the rungs, which is the thing nobody has obtained.

So read a result carefully. A ratio near 20 means this item is about capture's transaction shape and
the tail localisation dissolves. **A ratio near 1 does not exonerate the boundary**; it exonerates
the cache and dirty-flag half and leaves the statistics half exactly as untested as it is today. Do
not conclude the boundary is cleared from a near-1 pair.

The sibling logging item helps here in a way worth naming: it does not price anything, but it makes a
cold capture legible while it runs, so the seventy minutes nobody currently waits out would at least
report which registration it was spending them in.

## A refuted hypothesis, recorded so nobody re-runs it

H2 disables its query-expression result cache when a query's dependencies were written in the current
transaction, which is exactly what a capture does. `Query.isUpdatedInCurrentTransaction` is real in
the pinned H2 and gates caching as described, so this looked like a systemic cause with a systemic
fix: commit the facts first, refresh afterwards, and caching returns for every position at once.

Measured, it is not the driver. Same store, same relation, cache alternated within one connection:
42.3 s with reuse on against 48.3 s with it off, a ratio of 0.88, close to the band the fact model
page already records for that setting and nowhere near orders of magnitude.

The bytecode says why. `Query.query` evaluates `getNoCache()` first and short-circuits, and
`getNoCache` sets no-cache whenever the query expression fails H2's independence check, which is to
say whenever it is correlated. The terms driving the cost here are correlated, so caching was never
available to them in or out of a transaction, and `isUpdatedInCurrentTransaction` is never reached.

What this refutes is the cache half of the transaction-boundary story only. The boundary also
controls when statistics exist, since `Materializations.analyse` runs post-commit and the plans
inside the capture are chosen without it, and the pair above deliberately held statistics fixed at
their good value. That second mechanism is untested and is what the experiment above is for.

## Caveats on the figures

Every timing here was taken against copies of stores left under the per-user cache home, driven over
JDBC with the pinned H2, `OPTIMIZE_REUSE_RESULTS` off, five interleaved sweeps where the relation was
cheap enough to repeat, and H2's own query statistics, with cumulative-over-maximum ratios between
1.14 and 2.30 so no row is result-reuse corrupted.

The price list was taken on the 14-registration DDL and the slow runs used the 16-registration one, a
day apart, so a view among positions 1 to 14 could have changed text between them. The populated
store came from a capture that completed and the slow runs are on a working tree with uncommitted
schema changes, so the populations are not identical.

On the population fact: `LAST_CAPTURED` records when a capture finished, not how long it took, so the
successful run's duration is unknown and must not be reconstructed. "No capture has completed at 16
registrations or more" is a fact about one machine's cache, not about the world; a CI run or another
developer's machine could say otherwise, and checking one would strengthen or break this cheaply.

An earlier draft of this item recorded a 73-CPU-minute completion on 2026-08-27 and reasoned from it
that an hour might be ordinary cost for this schema. There was no such completion. The figure came
from a note whose basis was a run that was killed, and the 2026-08-27 file timestamp behind it is a
file open rather than a capture, `LAST_CAPTURED` on that same store reading 2026-08-26. Nothing in
this item should be read as resting on it.

A third DDL generation was briefly thought to reproduce this and does not: that process was
terminated rather than slow, and its store is empty for that reason. Two hypotheses were killed early
and should not be re-run: the `@lookupKey` recursion, whose seed relation is the retired input-field
site and is empty on this schema, and the schema's existing validation errors, which shorten the
reference walk rather than lengthening it.

## Why killing it is not free, and why that is not a workaround either

The refresh runs inside the capture's transaction, so an interrupted run commits nothing: it leaves a
full-size file holding no rows, and the next run starts as cold as the last. One measured this way
was 124 MB and held 67 rows. So every kill guarantees the next run pays from the beginning again, and
a person hitting this repeatedly is not making the situation worse only by luck.

The tempting advice that follows is "let one capture finish and the store stays warm". Do not give
it. It is true at 14 registrations, where a completed capture is on record and a warm store does open
in milliseconds afterwards, and it is unproven at 16 or more, where no capture has ever finished and
nobody knows what waiting would cost. Advising a developer to wait out an unbounded run is worse than
telling them the truth, which is that there is currently no way to capture this schema.

## Related

The sibling logging item adds per-registration output to the refresh, which turns the next real
capture into the identification this item currently lacks; it should land first for that reason.
R850 describes this failure mode at a different relation and assumes the trigger is someone editing
that arm; this is a second trigger, on store size alone with no code change. R839 is position 6.
R848 is the frame, asking whether the register's shape is right at all.
