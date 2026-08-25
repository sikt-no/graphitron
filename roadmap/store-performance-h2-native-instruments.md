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

The fact store is an H2 database, and H2 ships three instruments for answering "which statement was
expensive" that nothing in this repository uses. `TRACE_LEVEL_FILE`, `TRACE_LEVEL_SYSTEM_OUT`,
`QUERY_STATISTICS`, `ConvertTraceFile` and `org.h2.util.Profiler` appear in no file of any type. The `store-performance`
skill, which is the written procedure for diagnosing a slow derived relation, reaches for exactly one
H2-native instrument: `EXPLAIN ANALYZE` and the `scanCount` annotation H2 puts on each plan node.
Everything else it prescribes is hand-rolled. Its worked probe wraps `System.nanoTime()` around a
`fetchOne`, once per relation, in a test method an author writes for the occasion.

Three things are worth defining before the rest of this reads as a proposal.

**Query statistics** is a per-statement aggregation H2 keeps in memory when asked. `SET
QUERY_STATISTICS TRUE` starts it, `SET QUERY_STATISTICS_MAX_ENTRIES <n>` caps how many distinct
statements it retains, and `INFORMATION_SCHEMA.QUERY_STATISTICS` reads it back. Each row is one
distinct statement text and carries `EXECUTION_COUNT`, the minimum, maximum, cumulative, average and
standard deviation of its execution time in milliseconds, and the same five figures for the row count
it returned.

**Tracing** is a log H2 writes of what a connection did. `SET TRACE_LEVEL_FILE <n>` sends it to a
`<database>.trace.db` file beside the database and `SET TRACE_LEVEL_SYSTEM_OUT <n>` sends it to
standard output; either can also be set in the JDBC URL, as `;TRACE_LEVEL_FILE=2`. The level matters
and buys two different things, so this item treats them as two instruments rather than one:

- **Level 2 (INFO) is what `org.h2.tools.ConvertTraceFile` needs.** That tool reads a finished trace
  file and appends to the emitted script a report ranking every statement the trace saw by self and
  accumulated share of total time, with a count and a result count each. The format is deliberately
  the shape `java -Xrunhprof` produces.
- **Level 3 (DEBUG) additionally carries the query planner's own cost evaluation:** per table filter,
  a candidate cost per available index, then the index and plan it chose.

**The built-in profiler** is `org.h2.util.Profiler`, a sampling profiler in the H2 jar, driven as
`new Profiler().startCollecting()`, then the work, then `stopCollecting()` and `getTop(n)`. It has
public `interval` and `depth` fields, and it does not ignore `org.h2` frames.

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

The profiler is the one that speaks to a warning the skill already carries. Step 1 tells a reader
that a profiler names the call site rather than the plan, and adds that JFR's default 64-frame depth
is shallower than the H2 view stack, so every sample truncates inside `org.h2` and the profile reads
as a dead end that is really a truncation. That warning is correct and this item does not propose
reordering the evidence hierarchy: a sampled stack still cannot report a scan count. What it can do
is stop being a dead end. H2's own profiler has the truncation as a public field, so the failure step
1 describes is one assignment away from not happening, and the skill currently does not say so.
There is a second thing the stacks give for free, visible in the probe below: the frames repeat one
self-similar block per view expansion, so the depth of a sampled stack reads as the nesting depth of
the derivation that produced it.

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
- The trace level is a real dial and the two uses want different settings. Level 2 produces a
  statement statistics table byte-identical in structure to level 3's, from a trace file about a
  fifth the size (1433 against 7772 bytes on the same workload), so `ConvertTraceFile` should be
  driven at 2. Only the planner cost lines need 3, and those are heavy enough to distort the timings
  they are being used to explain, so level 3 belongs behind the same environment-variable guard the
  skill already puts on its `EXPLAIN ANALYZE` block. Query statistics is cheap enough to leave on for
  the length of a probe run.
- Level 1 writes no trace file at all when nothing failed, and `ConvertTraceFile` then exits on a
  `NoSuchFileException` naming a file the reader never asked for. Worth stating, because it looks
  like a broken tool rather than a level that was set too low.
- The profiler's own default `depth` is 48, which is *shallower* than the JFR default the skill
  warns about, so out of the box it truncates worse. Measured on a five-level stack of views: every
  sampled stack came back cut at exactly 48 frames, and the same stacks at `depth = 256` came back
  complete at 88 and 90 frames. Whatever the skill says here has to say that the field must be
  raised, because the default silently reproduces the exact failure step 1 already describes.
- The profiler samples its own collector thread and does not exclude it. In the probe it was 50% of
  all samples and the top entry, so a `getTop(3)` spends one of its three slots on
  `Profiler.getRunnableStackTraces`. Ask for more entries than you want to read.

## Scope

The change is to `.claude/skills/store-performance/SKILL.md` and to the rules page it defers to,
`docs/architecture/explanation/fact-model.adoc` under "Derived reads are views, not stored facts".
Query statistics is the substantial addition and belongs in steps 3 and 5, as the default way to get
per-relation timings rather than as an extra. The planner cost trace belongs in step 3 as a named
second instrument for the case where the chosen plan is itself the question. `ConvertTraceFile` at
level 2 is a cheap whole-run ranking and belongs beside `report-inline-multiplicity` in step 3, as
the measured counterpart to that tool's static one; its real limit is the file-backed store, not
cost.

The profiler belongs in step 1 rather than step 3, because its subject is that step's existing
warning rather than a new measurement. The edit there is small and specific: keep the ranking (a
sampled stack is not a plan and does not report scan counts), and replace the JFR dead end with the
fact that H2 ships a profiler whose depth is a field, plus the measured number of frames a real view
stack needs. Resist promoting it. An item that ends with the skill recommending a profiler before a
timing has been taken has inverted the order step 1 exists to defend.

The skill's own citation policy applies to whatever lands: it restates no measured per-relation
number, because the page and the `meta_materialize` reason rows are the gated surfaces for those and
a copy here rots unobserved. Nothing in this item asks for that to change.

An open question for Spec: whether any of this should become a standing affordance rather than a
documented recipe. A store that could be opened with query statistics already on, under a flag, would
make the instrument reachable without editing a probe. That is a change to `GraphitronModelStore`
rather than to a skill document, it is a wider blast radius than the rest of this item, and it should
be decided rather than assumed.
