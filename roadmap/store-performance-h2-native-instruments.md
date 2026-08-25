---
id: R828
title: "The store-performance skill hand-rolls timings H2 already collects"
status: Backlog
bucket: dx
priority: 4
theme: tooling
depends-on: []
created: 2026-08-25
last-updated: 2026-08-25
---

# The store-performance skill hand-rolls timings H2 already collects

The fact store is an H2 database, and H2 ships two instruments for answering "which statement was
expensive" that nothing in this repository uses. `TRACE_LEVEL_FILE`, `TRACE_LEVEL_SYSTEM_OUT`,
`QUERY_STATISTICS` and `ConvertTraceFile` appear in no file of any type. The `store-performance`
skill, which is the written procedure for diagnosing a slow derived relation, reaches for exactly one
H2-native instrument: `EXPLAIN ANALYZE` and the `scanCount` annotation H2 puts on each plan node.
Everything else it prescribes is hand-rolled. Its worked probe wraps `System.nanoTime()` around a
`fetchOne`, once per relation, in a test method an author writes for the occasion.

Two things are worth defining before the rest of this reads as a proposal.

**Query statistics** is a per-statement aggregation H2 keeps in memory when asked. `SET
QUERY_STATISTICS TRUE` starts it, `SET QUERY_STATISTICS_MAX_ENTRIES <n>` caps how many distinct
statements it retains, and `INFORMATION_SCHEMA.QUERY_STATISTICS` reads it back. Each row is one
distinct statement text and carries `EXECUTION_COUNT`, the minimum, maximum, cumulative, average and
standard deviation of its execution time in milliseconds, and the same five figures for the row count
it returned.

**Tracing** is a log H2 writes of what a connection did. `SET TRACE_LEVEL_FILE 3` sends it to a
`<database>.trace.db` file beside the database, `SET TRACE_LEVEL_SYSTEM_OUT 3` sends it to standard
output, and level 3 is H2's DEBUG. At that level the log carries the query planner's own cost
evaluation: for each table filter it prints a candidate cost per available index, then the index and
plan it chose. Separately, `org.h2.tools.ConvertTraceFile` reads a finished trace file and emits a
report ranking every statement the trace saw by self and accumulated share of total time, with a
count and a result count per statement.

## Why this is worth doing

Both instruments answer questions the skill currently has a weaker answer for, or no answer for.

The skill's step 3 says to time relations in isolation, one at a time, and its step 5 names "time
every child in isolation" as the cheapest control there is. Query statistics does that as a side
effect of a run that was going to happen anyway. It needs no probe per relation, and it reports a
standard deviation and an execution count. That last point is not a convenience. The skill's step 1
exists because four conclusions were taken back on one investigation, and two of them were single-run
readings believed without repeats; the instrument that reports spread for free is aimed at precisely
the failure the skill opens by warning about.

The planner cost lines in a level 3 trace are evidence the skill has no source for at all.
`EXPLAIN ANALYZE` reports what the database scanned and how many times. It does not report which
access paths the planner priced and rejected. Over a deep stack of views, where a relation is
expanded once per naming, "why did it choose this join order" is a question a reader currently cannot
ask the database.

`ConvertTraceFile`'s ranked report is a cost ranking of a whole run. The skill's step 3 currently
approximates that statically, with `report-inline-multiplicity`, and it is careful to say that the
tool ranks breadth rather than cost: a count of how many relation instantiations one read expands to
is not a measurement. A ranked trace report would be the measured counterpart, and it would let the
skill keep the static tool honestly labelled as a suspect-ranker.

## Constraints the implementer should not have to rediscover

Each of these was probed against the pinned H2 (2.4.240) rather than read off documentation.

- `TRACE_LEVEL_FILE` writes nothing at all for an in-memory database. `GraphitronModelStore.open()`
  is in-memory and so is every store a `CapturedStore` hands out, so the file trace and
  `ConvertTraceFile` are reachable only on the file-backed store `GraphitronModelStore.openAt`
  returns, which in practice means the warm dev-loop store. On a probe the option is
  `TRACE_LEVEL_SYSTEM_OUT`, whose output lands in the surefire report file the skill already warns
  gets interleaved with jOOQ's own DEBUG logging.
- Query statistics does work on an in-memory database, which is what makes it the one that fits the
  skill's existing probe recipe unchanged.
- Query statistics is database-wide rather than session-scoped, so it also covers statements issued
  through the second connections `GraphitronModelStore.reader` mints. That is a point in its favour:
  a capture and its readers show up in one table.
- Neither instrument is reachable through `StoreConsole`. The console is a second database holding
  read-only linked tables, so its `INFORMATION_SCHEMA.QUERY_STATISTICS` reports the console's own
  statements and not the store's. Any recipe the skill grows has to say so, because the console is
  otherwise the surface step 3 prefers.
- Level 3 tracing is heavy enough to distort the timings it is being used to explain, so it belongs
  behind the same environment-variable guard the skill already puts on its `EXPLAIN ANALYZE` block.
  Query statistics is cheap enough to leave on for the length of a probe run.

## Scope

The change is to `.claude/skills/store-performance/SKILL.md` and to the rules page it defers to,
`docs/architecture/explanation/fact-model.adoc` under "Derived reads are views, not stored facts".
Query statistics is the substantial addition and belongs in steps 3 and 5, as the default way to get
per-relation timings rather than as an extra. The planner cost trace belongs in step 3 as a named
second instrument for the case where the chosen plan is itself the question. `ConvertTraceFile` is
the most niche of the three, given the in-memory constraint above, and the item should be free to
conclude it is worth a mention and not a recipe.

The skill's own citation policy applies to whatever lands: it restates no measured per-relation
number, because the page and the `meta_materialize` reason rows are the gated surfaces for those and
a copy here rots unobserved. Nothing in this item asks for that to change.

An open question for Spec: whether any of this should become a standing affordance rather than a
documented recipe. A store that could be opened with query statistics already on, under a flag, would
make the instrument reachable without editing a probe. That is a change to `GraphitronModelStore`
rather than to a skill document, it is a wider blast radius than the rest of this item, and it should
be decided rather than assumed.
