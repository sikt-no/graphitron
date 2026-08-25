---
id: R828
title: "The store-performance skill hand-rolls timings H2 already collects"
status: Ready
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

Each of these answers a question the skill currently has a weaker answer for, or no answer for.

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

## The instrument was driven end to end before this item was written

Everything below was run against the sakila example schema over a `CapturedStore`, and then rolled
back. It is here because the item is a claim that an instrument earns its place, and the way to
support that claim is to have used it. The changes are not in the tree; the numbers are what they
produced.

**Ranking.** One probe, one capture, 8.8 seconds: query statistics on, one `SELECT count(*)` per
view, then read `INFORMATION_SCHEMA.QUERY_STATISTICS` ordered by cumulative time. That ranked all 85
relations at once, where the skill's current recipe is a hand-written probe per relation. Fourth
place was `meta_relation_reference` at about 181 ms, which is a `meta_` relation, and every one of
the twelve existing registrations is on an `intent_` one.

**The instrument lied twice, in ways worth naming.** The first control read as a fourteenfold
improvement. It was not: H2 reuses the result of a repeated identical query, so every repeat after
the first costs nothing and `AVERAGE_EXECUTION_TIME` is one real execution divided by the repeat
count. Isolated on the pinned H2: six runs of one query measured 98 ms then 0, 0, 0, 0, 0, and the
view reported an average of 15.4 ms for a query that takes about 92. `SET OPTIMIZE_REUSE_RESULTS
FALSE` restores honest repeats, after which cumulative time (293 ms) matches the summed wall clock
(298 ms). The second lie was `MIN_EXECUTION_TIME`, which read 0.0. It always does, even when every
run took at least 26 ms, so that column is not populated and cannot be used for anything.

**The discriminator is `CUMULATIVE_EXECUTION_TIME / MAX_EXECUTION_TIME`, and it is internal to the
view.** Reuse leaves exactly one execution real, so cumulative collapses onto maximum however many
repeats were asked for; honest repeats push the ratio up toward the execution count. Measured on
2.4.240, five repeats of one view query: reuse on gave max 342.67, cumulative 342.81, a ratio of
1.00; reuse off gave max 109.40, cumulative 313.84, a ratio of 2.87. So with
`EXECUTION_COUNT` above one, a ratio near 1 is a corrupted row and nothing else produces it. Stated
as a ratio rather than as "cumulative matches the summed wall clock" deliberately: the wall-clock
comparison also works and is what caught it here, but it requires the author to take a second
measurement outside the instrument, which is the labour this item exists to remove. The ratio needs
only the row in front of you. The ceiling is the execution count and the observed honest ratio sits
below it, 2.87 against 5, because a cold first run inflates the maximum; the rule is "near 1 is
corrupt", not "equal to n is honest".

That mattered, because the corrupted numbers had already carried two conclusions: the fourteenfold
figure, and a child-isolation pass whose real reading is below.

**Isolation, honestly.** Whole relation 153 to 166 ms; every child between 0.2 and 2.1 ms. That is
the skill's own first control firing: no expensive child, so the cost is the expansion.

**The floor control refuted the suspect.** Two rounds had gone into the census join. Removing the
census joins from the statement entirely still left 95 to 106 ms, so the census was never the term,
exactly as the skill's floor control is meant to establish. The term was a `SELECT DISTINCT` over
`INFORMATION_SCHEMA.KEY_COLUMN_USAGE`, named twice, once per end of a foreign key, and inlined at
both namings.

This relation is an instance of the case the skill's bisect step was added for, expensive with every
child cheap, and it needed a third bisection axis that step does not list. The two it names are CTEs
and top-level `UNION` arms; this body has neither, being one flat join, and what localised it was
dropping one join at a time and re-timing. Worth adding to that list, since a flat join of derived
tables is the shape the `meta_` family is mostly made of.

**Scan counts and wall clock disagreed, in both directions.** Storing the census without an index
visited twenty times more rows than the shipping view (87834 against 4248) and was faster (105
against 161 ms). Adding the index then removed 96% of those visits (87834 to 3484) and moved the
clock not at all (105 to 103 ms, inside a standard deviation of 13).

The skill now says a `scanCount` is a row count and not a cost, and says to price the shape by
timing, so the general point is already made and this item does not re-make it. What the run adds is
the specific mechanism and a measured case in both directions: a scan count weights every visited row
equally, and a row of a view over `INFORMATION_SCHEMA` does not cost what a row of a table costs, so
the instrument diverges from cost exactly when a change moves rows between a view and a table. That
is what every registration in the register does, which makes this the case where the caveat bites
rather than an abstract one.

**The lever, and why it is a conjunction.** Census stored alone: no change. Key-constraint relation
stored alone: 49.7 ms. Both stored: 0.7 ms. Neither is the fix and together they are, which is a
different lesson from the registry's existing "materialize the deepest relation the cost multiplies
through".

**The rewrite rung failed as predicted.** Hoisting the derived table into a `WITH` measured 201 ms
against the view's 211: no change, which is what the fact-model page says to expect, since H2 inlines
a non-recursive `WITH` exactly like a view. Driving the halves off
`INFORMATION_SCHEMA.TABLE_CONSTRAINTS`, which is already at constraint grain and needs no `DISTINCT`,
reached 121 ms but at 124316 scans.

**Implemented.** Two stored relations filled once per booted store beside
`MaterializeDependencies.populate`, and `meta_relation_reference` reading them. One read of the
shipping relation went from 153 to 166 ms to **1.0 ms** with a standard deviation of 0.3, scans from
4250 to 1752, and the row count unchanged at 175. It left the top eight entirely.

**Two findings the measurement did not predict.** First, the capture-cadence materializer is the
wrong mechanism here: these rows are a function of the DDL alone, and their readers include the
schema gates and the docs drift guard, which run against a store no capture has touched, so a
capture-cadence refresh would leave those readers on an empty relation. Boot-time derivation is the
right cadence and `meta_materialize_dependency` is the precedent. Second, the full build then failed
five gates, and every one was schema discipline rather than cost: new base tables must lead with
`graph_name`, the two rule views needed column comments, the new relations needed a registered
agreement source, `SchemaReferencePagesTest` refused to render blank comment entries, and
`DerivedReadCostTest`'s pinned view count needed re-pinning from 85 to 86. Worth stating plainly:
`DerivedReadCostTest` did not fail on cost direction. The measurement was the easy half.

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
- **Repeats need `SET OPTIMIZE_REUSE_RESULTS FALSE` or they are not repeats.** H2 reuses a repeated
  identical query's result, so the second and later runs cost nothing and the reported average is one
  real execution divided by the repeat count. This is a silent under-report proportional to how many
  repeats were asked for, which makes it worse the more careful the author is being. Whatever the
  skill grows here must carry the setting in the recipe itself, not as a footnote.
- **`MIN_EXECUTION_TIME` is always 0.0** and must not be read at all. It is 0.0 on an honest row and
  on a corrupted one alike, so it is not a tell either; the discriminator is the cumulative-over-
  maximum ratio described in the run above.
- Disabling result reuse changes absolute figures, so it has to be set the same way on both sides of
  any before-and-after comparison.
- **Both settings are database-wide, not session-scoped.** Set on one connection and a second
  connection's statements are recorded, and recorded honestly: measured on 2.4.240, with both set on
  connection 1 and four repeats run and read entirely on connection 2, the ratio came back 2.07
  rather than 1.00. So one `SET` pair on the store's own connection covers every `StoreReader` a
  session mints, and a probe does not have to thread the setting through the connections it wants to
  measure.
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

Two files, and the split between them follows the split the skill already declares: the page carries
rules about how the engine evaluates, the skill carries the procedure that reaches them in order.

**`docs/architecture/explanation/fact-model.adoc`, under "Derived reads are views, not stored facts",
takes exactly one addition:** that H2 reuses the result of a repeated identical query, so a repeat is
not a repeat unless `OPTIMIZE_REUSE_RESULTS` is off. That is an engine-evaluation rule of the same
kind and the same shape as the page's existing "H2 inlines a non-recursive `WITH` exactly as it
inlines a view", which sits in the paragraph on expression-shaped joins, and it belongs beside it
rather than in a procedure document. It also bears directly on the page's own measured claims: every
figure on that page is a timing, and this is the rule that says when a timing is real.

**Everything else in this item is skill procedure and none of it touches the page.** The instrument
mechanics (which `SET` commands, which columns of `INFORMATION_SCHEMA.QUERY_STATISTICS` to read,
the cumulative-over-maximum discriminator, the trace levels, the profiler's depth field) are how an
author operates a tool, not facts about how the engine evaluates a relation, and the page carries no
scan-count rule for the boundary sentence below to attach to. So the page is touched once and the
rest lands in `.claude/skills/store-performance/SKILL.md`.

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

On the scan-count boundary, most of the work is already done and this item should not redo it. The
skill's plan step now states that a `scanCount` is a row count rather than a cost and tells the
reader to price the shape by timing. What is left is one sentence of mechanism beside that rule,
naming when the divergence happens rather than only that it can: a scan count weights every visited
row equally, so it stops tracking cost when a change moves rows between a view and a table. That
is worth adding because it is what every registration in the register does, and because the timing
this item's instrument makes cheap is exactly what the existing rule tells the reader to reach for
next. `DerivedReadCostTest` is untouched by any of this; it rests its no-number design on scan
counts being comparable across shapes, which is a different claim and a sound one.

## What this item is not, decided rather than left open

Both of these were open questions in the first draft and the Spec gate is where they get answered,
so they are answered.

**No standing affordance on `GraphitronModelStore`. This item is prose only and adds no build
surface.** The affordance considered was a store openable with query statistics already on and
result reuse off. It is declined, and the measurement above is what decides it rather than a
preference for the smaller change: both settings are database-wide, so two `SET` statements issued
on the store's own connection cover every reader connection a session mints. The affordance would
therefore save an author two lines, at the cost of a second construction path on the class every
store in the reactor comes from, with the store-fixture guard and the boot-path gates that implies.
Two lines is not a construction path. The two statements go in the skill's probe recipe, where an
author reading the recipe cannot miss them, which is the same place the recipe already puts
`STORE_EXPLAIN`. If a later item finds authors omitting them in practice, that is evidence this
answer was wrong and is the moment to revisit; nothing here is load-bearing against it.

**`meta_relation_reference` is a separate item and not this one's work.** The 153x is real, measured
and reproducible, and it is a finding rather than an illustration, which is exactly why it should not
ride inside a skill edit: the implementation tripped five schema-discipline gates (graph-key
leading column, column comments on the two rule views, a registered agreement source, the pinned
view count, and blank reference entries), none of which has anything to do with tracing, and every
one of which would block a documentation change from landing. It is filed separately as
`roadmap/meta-relation-reference-inlined-key-projection.md`, carrying the measurements above plus
that gate list, so it starts from them rather than re-taking them. Nothing in this item depends on
it, and it does not depend on this one.

One correction that item inherits: the figures in the run above are a record of what the run saw and
are deliberately not updated, but `DerivedReadCostTest`'s pinned `READERS_IN_SCHEMA` has moved since,
so the follow-on re-pins from current rather than from this run's 86.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested

Reviewer session `session_01KFRygu4Y3D1BDh8Td39Z7e`, 2026-08-25. Status stays Spec.

Question 1 is answered. Stated without the phase list: the next person chasing a slow derived relation
stops hand-writing a `System.nanoTime()` probe per relation and instead turns on H2's own per-statement
statistics, getting the whole ranking, an execution count and a standard deviation out of a run that was
going to happen anyway; gains two evidence sources the skill has none for today, the planner's own cost
evaluation at trace level 3 and a measured whole-run cost ranking to sit beside
`report-inline-multiplicity`'s static one; and finds step 1's profiler warning naming a usable
instrument instead of a dead end. The instruments are real on the pinned H2 and behave as the item
says. Verification narrative is in this commit's message.

Question 2 is where the item is not handoff-ready. Three things an implementer would have to decide
before writing a line.

**Finding 1 (question 2, and question 1's viability). The result-reuse tell contradicts itself, so the
item's central caveat ships without a working discriminator.**

The item says twice that `MIN_EXECUTION_TIME` is always 0.0 and, in the same breath, that "a zero
minimum beside a non-zero maximum is what a reuse-corrupted row looks like", naming that as the
column's one use. Both halves cannot hold. A column that is always 0.0 reads identically on an honest
row and a corrupted one, so it discriminates nothing. Reproduced on 2.4.240: four runs of one view
query with reuse left on reported `count=4 min=0.0 max=407.06 cum=407.33 avg=101.83`, and the same four
runs under `SET OPTIMIZE_REUSE_RESULTS FALSE` reported `count=4 min=0.0 max=764.84 cum=2076.47
avg=519.12`. The minimum is 0.0 on both, and the honest row carries a non-zero maximum beside it.

This is worth blocking on rather than noting, because the item tells the implementer that whatever the
skill grows here "must carry the setting in the recipe itself, not as a footnote". A document whose
whole subject is not believing a bad reading would ship a tell that fires on every reading, honest ones
included. What would satisfy: name a discriminator that works. The run recorded above already used one,
cumulative execution time against the summed wall clock (293 against 298 in the narrative; 2076 against
2077 summed in the reproduction, versus 407 against roughly 1700 with reuse on). Which discriminator
the skill carries changes what the implementer writes, so the choice is the author's rather than mine.

**Finding 2 (question 2). `docs/architecture/explanation/fact-model.adoc` is named as a change target
and no described edit lands on it.**

Scope's first sentence names two files. Every paragraph after it assigns its edit to a numbered step of
the skill: query statistics to steps 3 and 5, the planner cost trace to step 3, `ConvertTraceFile` to
step 3, the profiler to step 1, and the scan-count mechanism sentence to the plan step, which is the
skill's step 3 and not the page (the page carries no scan-count rule; I checked). So the page is named
and never written to, and the implementer decides whether it is touched at all.

The split is not self-evident, which is why leaving it open costs something. The skill's own opening
says the page is the source of the rules and the skill is the procedure that reaches them in order, and
several of this item's constraints are engine-evaluation rules of exactly the kind the page already
carries: "H2 inlines a non-recursive `WITH` exactly as it inlines a view" is on that page today, and
"H2 reuses the result of a repeated identical query, so a repeat is not a repeat" is the same kind of
fact about the same engine. What would satisfy: say for each instrument fact whether it is a page rule
or a skill procedure, or say plainly that the page is untouched and drop it from the Scope sentence.

**Finding 3 (question 2). Open question 1 leaves the item's blast radius undecided, and Spec → Ready is
the transition that decides it.**

Open question 1 asks whether the instrument becomes a standing affordance, a store openable with query
statistics already on and result reuse off, and says it "should be decided rather than assumed". That is
a change to `GraphitronModelStore`, `graphitron-model` main source, inside an item whose Scope otherwise
describes two prose files. The two answers are not variants of one item: one is a documentation edit
with no build surface, the other adds a construction path to the class every store in the reactor comes
from, with the gates and the store-fixture guard that implies. An implementer resolves this on their
first read, which is the design decision this gate exists to take. What would satisfy: pick one.
Declining the affordance and recording why is a complete answer, and so is taking it with the
constructor surface named.

Non-blocking, no response needed.

- Open question 2 reads as open, but the prose directly beneath it answers it: this item is about the
  instrument, and the schema change would make the skill edit hostage to five gates that have nothing
  to do with tracing. Stating it as decided, with the follow-on item filed, removes the only other
  thing an implementer has to resolve.
- The run's numbers are dated. The pinned count in `DerivedReadCostTest` is `READERS_IN_SCHEMA` and
  stood at 88 at the commit that moved this item to Spec, not 85; it read 85 two commits earlier, so
  the run predates two landings. I left the "85 to 86" figures alone, because editing them would
  falsify the record of what the run saw, but a follow-on item starting from these measurements should
  re-pin from current rather than from 86. I did correct "eleven existing registrations" to twelve in
  this commit; the point it carries, that every registration is on an `intent_` relation, still holds.
- The item has no `## Implementation` or `## Tests` section and Scope carries the implementation. For a
  prose-only change that reads fine. Raising it only because whichever way finding 3 goes may give the
  item a build surface that wants one.

### Round 2: Spec → Ready, signed off

Reviewer session `session_01KFRygu4Y3D1BDh8Td39Z7e`, 2026-08-25. Status flips to Ready.

All three findings are answered, and two of them are answered with a measurement rather than a
preference. I reproduced both on the pinned 2.4.240 rather than reading them.

Finding 1 is closed and the replacement is better than what I proposed. Five repeats of one view
query with reuse on gave max 164.46 and cumulative 164.65, a ratio of 1.00; with
`OPTIMIZE_REUSE_RESULTS FALSE` the same five gave max 163.65 and cumulative 318.69, a ratio of 1.95.
The mechanism is exactly as stated, cumulative collapsing onto maximum because one execution is all
that is real, and the direction that matters is safe: a corrupted row cannot present a high ratio,
so the discriminator has no false negatives. Preferring it to my cumulative-against-wall-clock is
right for the reason given, that mine needs a second measurement taken outside the instrument.

Finding 2 is closed. The page now takes one named addition and the item says why that fact and not
the others: it is an engine-evaluation rule rather than instrument mechanics, and it sits beside a
rule of identical shape that the page already carries. The reasoning follows the split the skill
itself declares, so the implementer inherits a decision rather than making one.

Finding 3 is closed, and the measurement it rests on reproduces. With both settings issued on one
connection and four repeats run and read entirely on a second, the ratio came back 1.81, honest;
the same cross-connection shape with reuse left on came back 1.00. So the settings really are
database-wide, the second reading is genuinely sensitive to them rather than accidentally honest,
and the affordance would have bought two lines. Declining it on that basis is the right call and it
is now recorded as one.

Non-blocking, and none of it needs a response.

- The honest ratio's floor is more fixture-dependent than the item's single figure suggests. Against
  the item's 2.87 out of 5, mine came in at 1.95 out of 5 and 1.81 out of 4, because a cold first
  run dominates the maximum more in my fixture than in the item's. The item's own framing already
  covers this, "near 1 is corrupt" rather than "equal to n is honest", and it is why that framing
  should survive into the skill unqualified: an implementer who turns it into a numeric threshold
  would reject an honest 1.81 as corrupt.
- The new "both settings are database-wide" bullet subsumes the existing "Query statistics is
  database-wide rather than session-scoped" bullet two lines below it. Same fact stated twice in one
  list; the implementer writes it once either way.
- The findings responses landed in the revision commit's message rather than as notes beneath each
  finding, which is what the item file conventions ask for. It cost nothing here, because the commit
  message maps one-to-one onto the three findings and the diff reads cleanly against them, so the
  delta was auditable. Noting it only so the convention does not quietly lapse on the next item.

### Round 3: In Review → Done, rework requested

Reviewer session `session_01KFRygu4Y3D1BDh8Td39Z7e`, 2026-08-25. Status flips to Ready.

Question 3 is answered, with one exception, and question 4 is where that exception bites. What landed
is the change the Spec gate approved: the page took exactly the one addition Scope named, in the
section Scope named, beside the rule of identical shape; the four instruments went into the steps
Scope assigned them; the profiler edit stayed inside step 1 and did not promote the instrument; and
both declined items, an affordance on `GraphitronModelStore` and any `meta_relation_reference`
change, are absent from the diff. Every factual claim the two documents now make about H2 I re-took
against the pinned 2.4.240 rather than reading, including the whole of `ConvertTraceFile`, which the
item had asserted and nothing here had yet exercised. All of them hold. That narrative is in this
commit's message.

**Finding 1 (question 4, and question 3). Step 2's recipe executes each relation exactly once, so the
spread the item made its whole case on is not delivered, the setting the recipe calls non-optional
does nothing in it, and one sentence in step 3 is false as written.**

The recipe loops over relations, not over executions. Run exactly as written against a three-level
view stack: `count=1`, `stddev=0.0000`, `cum/max=1.00` on every row, for all three relations. So a
reader who follows the recipe gets precisely the single-run reading with no spread that step 1 opens
by warning about, which is the failure the item chose this instrument to fix.

Three consequences, in order of how load-bearing they are.

Step 3 says the instrument "reports a standard deviation and an execution count without being asked
for them". You do have to ask, by executing more than once, and the recipe never does; from the
recipe the standard deviation column is 0.0000 by construction. That sentence carries the argument
for preferring this instrument to a hand-rolled timing, so it is the one that has to become true
rather than be softened.

`SET OPTIMIZE_REUSE_RESULTS FALSE` is inert in the recipe as written. Measured, because it was worth
ruling out that it changes a single execution: one execution of a view naming its child twice cost
3400 against 3199 ms, 3090 against 3034, and 3222 against 3176, reuse on against off, which is 0.94
to 0.99 and inside the noise. Reuse only ever costs you a repeat, so a recipe with no repeat teaches
the statement as ritual. The item was explicit that the setting "must carry the setting in the recipe
itself, not as a footnote"; it is in the recipe, and the repeat that gives it meaning is not.

Every row the recipe produces sits at `cum/max = 1.00`, which step 3 calls "a corrupted row and
nothing else produces it". The `EXECUTION_COUNT` above one qualifier is present and saves a careful
reader, so this is not a false alarm, but the recipe's own output is the one shape where the tell has
to be suppressed and the recipe does not say so.

For contrast, from the same fixture: five repeats with reuse off gave `count=5 cum=15359.05
max=3129.84 stddev=52.4493`, a ratio of 4.91, and five with reuse left on gave `count=5 cum=1055.54
max=1055.44 avg=211.11`, a ratio of 1.00. The instrument does everything the item claims once
something repeats.

What would satisfy: the recipe produces more than one execution of whatever it wants a spread for.
How many, whether the repeat lives in step 2's block or is named as a rule in step 3, and whether
step 5's deliberate "name each child once" stays as it is, are all choices between arms and so the
implementer's rather than mine.

Non-blocking, no response needed.

- The profiler's self-sampling caveat is the one constraint from the item's list that landed nowhere:
  the collector thread is sampled and not excluded, it was half of all samples in the item's own
  probe, and so a `getTop(3)` spends a slot on `Profiler.getRunnableStackTraces`. Step 1 now tells a
  reader the instrument is usable and to raise `depth` before reading a profile, which is exactly the
  reader who will meet that. It is small, and the item file dies at Done, so if it is not worth a
  clause it should be dropped by decision rather than by omission.
- Recorded as checked rather than as a finding: the citation policy's new third category is not a
  scope deviation, though the spec did say nothing asks that policy to change. Without it the policy
  as written forbids the profiler frame counts and the trace-file size ratio the spec did ask to
  land, so the paragraph is a consequence of the approved change rather than an addition to it. The
  transition commit flags it for the reviewer, which is where it belonged.
- `relations` in step 2's block is undefined, as `tmp` above it already was, and the prose directly
  under the block tells the reader to name their own list, so it reads as a placeholder rather than a
  defect. Raising it only because whatever answers finding 1 lands in that same block.
- The honest ratio's floor is now four independent fixtures wide, 1.81 and 1.95 in round 2, 1.97 in
  the implementation, and 4.91 here. That spans nearly the whole range from the corrupt reading to
  the execution count, which is a stronger case for the skill's refusal to name a cutoff than the
  skill's own text needs to make.
- `f216109`'s message cites `4872bfa` for the reproduction; the commit carrying it on this branch is
  `8c03600`, the pre-rebase name having gone with the rebase. Nothing to fix in published history,
  noted so the next reader of the trail is not looking for a commit that is not on the branch.
