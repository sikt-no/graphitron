---
id: R733
title: "Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-20
---

# Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured

R732 harvests the three slices whose wins were measured before it was written, and stops there
deliberately. This item carries the rest: the slices whose wins were still unmeasured at that point,
and the guardrail without which the recovered time drifts back. It is the second pass, and it wants
its own Spec cycle rather than an amendment to the first, because the guardrail is an architectural
choice and the remaining slices need numbers before they can be ordered against each other.

Everything below restates the facts it needs rather than pointing into R732's body, because that
body is deleted when R732 reaches Done.

## A second measurement pass has run, and it reorders this item

Everything in the slice table further down was written before R732 landed. A fresh pass over the
post-R732 reactor moved the cost somewhere else entirely, so read this section first and the slice
table as the record of what the first pass expected.

All figures below were taken on one 4 vCPU, 15 GB sandbox against a warm local repository, with
sequential `mvn install -Plocal-db` unless stated otherwise. R732's numbers came from a machine
where that same build ran 9m06s; here the same command on trunk runs 11m47s, so the ratios
transfer between machines and the absolute seconds do not.

### The cost left `graphitron` and went to the generator itself

Trunk baseline: 11m47s, 5919 tests in 604 classes. Where it goes, by module wall clock:

| Module | Wall clock | Share |
|---|---|---|
| `graphitron-sakila-example` | 410.6s | 58% |
| `graphitron` | 79.4s | 11% |
| `graphitron-model` | 63.9s | 9% |
| `graphitron-lsp` | 50.3s | 7% |
| `graphitron-maven-plugin` | 37.6s | 5% |
| `graphitron-mcp` | 32.7s | 5% |
| everything else | 31.8s | 5% |

R732 did its work: `graphitron` is 79.4s against the 181.2s it started from, and
`ColumnMatchShadowTest` is 8.96s against the 74 to 120s that motivated the item. The three slices
below that target `graphitron` and `graphitron-mcp` are now aimed at 16% of the build between them.

`graphitron-sakila-example` is where the build now is, and inside it two classes are almost all of
it: `GeneratorDeterminismTest` at **240.5s across 2 test methods**, and `FixtureWarningsGateTest` at
**56.8s across 1**. Neither is slow because it is a slow test. Both run
`GraphQLRewriteGenerator` over the full fixture schema, four times and once respectively, and one
such run costs about 57 seconds. The build pays for six of them. So the subject is not the test
tier at all: it is the generator's own hot path, which means every consumer pays it on every build
of theirs, and the build is merely the place we can see it.

### What one generator run spends its time on

JFR over one run (`FixtureWarningsGateTest` in isolation, 60.49s, 5470 samples, `stackdepth=1024`
because the default 64 truncates every stack below the H2 frames and hides the caller):

**97.4% of samples are inside H2 evaluating a query.** Attributed to the deepest `no.sikt` frame,
five call sites are 95.7% of the run:

| Call site | Share | What it reads |
|---|---|---|
| `ArgmappingProjectionDefects.authorDefects` | 31.2% | `intent_argmapping_projection_defect`, once |
| `StoreNodeTables.keyColumns` | 22.3% | `intent_resolved_node_key_column`, **once per node type** |
| `ResolvedKeyProjections.read` | 15.8% | `intent_resolved_node_key_projection`, once |
| `ArgmappingProjectionDefects.unemittableProjections` | 15.5% | the same view again, joined |
| `StoreNodeTables.bindings` | 10.9% | `intent_resolved_node_type_id` joined to `intent_resolved_type_binding` |

`EXPLAIN ANALYZE` on the first of those, inside the real populated store: **one query scans about
2.57 million rows** to return a handful of defect rows, and `intent_spelled_table` is expanded at
**469 separate plan nodes** within it.

### Two changes, measured end to end

Both were applied as throwaway instruments to get numbers, not as proposed final code. A full
`mvn install -Plocal-db` was green on both, all 5919 tests, and reproduced on a second run.

| | Full-fixture generator run | Whole build (log span) | `graphitron-sakila-example` |
|---|---|---|---|
| trunk | 60.49s | 706.3s | 410.6s |
| batch the key-column read | 46.90s | 584.2s | 316.2s |
| and one index on `sql_table` | 23.49s | 515.6s / 513.6s | 232.5s |

So about 122 seconds for the batched read and a further 69 for the index, 191 seconds of a 706
second build between them, and `GeneratorDeterminismTest` goes 240.5s to 86.2s while
`FixtureWarningsGateTest` goes 56.8s to 20.6s. The index's end-to-end share is smaller than its
read-side share for the reason the write-side paragraph below gives.

**The batched read.** `StoreNodeTables.read` loops `for (var binding : bindings(...))` and calls
`keyColumns(...)` inside the loop, so it reads `intent_resolved_node_key_column` once per node type,
31 times over the fixture schema. That view is a `DENSE_RANK() OVER (PARTITION BY graph_name,
type_name)` over a three-arm union, and a window sees its whole partition whatever predicate the
reader applies outside it, so each of the 31 reads paid the entire view. This is the same defect
R732 fixed in `ColumnMatchShadowTest`, found this time in the generator rather than in a test. The
change is one query grouped with `fetchGroups`, 19 lines across the method and its two helpers.

Worth naming precisely, because it is the argument for an enforcer rather than a fix: the method's
own javadoc already says it "reads the whole population rather than a requested subset ... the query
is one pass per relation either way". The prose was right and the code did not match it, and
nothing checked.

**The index.** The fact store's DDL declares 140 tables, 56 views, 161 primary keys and zero
`CREATE INDEX`. The spelling-resolution join in `intent_spelled_table` matches
`sql_table.table_name_upper`, a generated column with nothing behind it, so it scans `sql_table`
every time, and that view is expanded hundreds of times per query. A single
`CREATE INDEX ... ON sql_table (source_name, table_name_upper)` takes the generator run from 46.90s
to 23.49s.

**The index is not free, and this is the part the first pass did not anticipate.** Capture writes
the whole catalog, so an index is maintained on every insert. Timing the five
`graphitron:generate` executions alone (`mvn generate-sources -pl :graphitron-sakila-example
-Plocal-db`): 1m06.7s without the index, 1m31.0s with it, so about +24s on the write side in that
module, and `graphitron-maven-plugin` moves 37.6s to 45.9s for the same reason. The net across the
build is strongly positive because five of the six full-fixture generator runs happen in tests,
which read and do not write. But it means each candidate index has to be measured on both sides,
and a broad "index the hot columns" sweep is not the shape of the work.

### What this does to the slice ordering

1. **Index the fact store's hot join columns.** Slice 3 below, promoted from last-but-one and
   "unmeasured, measure first" to first. The caveat it carried, that H2 may decline an index under
   an `OR`, is about `intent_column_match_claim`'s predicate and does not apply to the equality on
   `sql_table.table_name_upper` that actually dominates. The write-side cost above is the new
   constraint on it.
2. **Batch the key-column read, and give the rule an enforcer.** This is the first of the three
   unenforced rules below, with a worked violation to point at.
3. **Reduce the high-multiplicity relations. Moved to R742, which owns it now.** Slice 5 below was
   the reduction slice and has become a pointer: the subject turned out to be the whole `intent_`
   stratum rather than one relation, with a measured 24.5s to 0.72s on the hottest read, so it wants
   the spec cycle it now has in R742 rather than a bullet here. What stays this item's business is
   the enforcer question, since the statically computable multiplicity metric R742 proposes is the
   missing enforcer for the derived-read rules named below.
4. Slices 1, 2 and 4 stay on the list and drop below those three. Their bounds hold (slice 4's
   `GraphitronMcpServerTest` measured 15.85s here against the 15.5s claimed) but each is about 2%
   of the build, where the three above are 27% together and not yet exhausted.
5. Slice 6 was not measured this pass and keeps its place.

## Why a guardrail is the spine of this item

Trunk CI went from a 5.2 minute median to a 15.3 minute median over seven weeks while the test-method
count rose 21 percent. Cost outran volume, and nothing measured it. R732 buys the time back once; the
reason the curve was allowed to triple is that no build artifact ever failed or flagged when the shape
regressed, so a repeat is a matter of time rather than of vigilance.

The failure mode is specific and it is what a guardrail has to catch. It was never a thousand slow
tests: it was one class, `ColumnMatchShadowTest`, at 74 to 120 seconds inside a `graphitron` suite
whose median class costs a fraction of a second. A per-class ceiling would have caught it on the
commit that introduced it. A total-suite budget would have absorbed it for weeks.

**The second measurement pass makes this argument concrete rather than hypothetical, and widens its
scope.** The same shape was sitting in the reactor the whole time, in a module R732 never looked at:
`GeneratorDeterminismTest` at 240.5 seconds across two test methods, in a reactor whose median class
costs a fraction of a second, which is 34% of the entire build in one class. Any per-class ceiling in
the 10 to 30 second range would have flagged it. Two consequences for the guardrail's design. The
ceiling has to be reactor-wide rather than scoped to `graphitron`, because the recurrence was not in
`graphitron`. And it needs a way for a genuinely long cross-cutting test to carry an explicit,
committed exemption, because raising the ceiling to accommodate one class is how a ceiling stops
working: `GeneratorDeterminismTest` at 86.2 seconds after the two changes above is still an order of
magnitude over any healthy class, and it is doing four full generator runs on purpose.

## The guardrail decision, and the argument that settles it

Three candidate shapes. The Spec pass has to pick one and keep the other two as rationale.

. **A per-class ceiling read from the Surefire reports.** Surefire already writes
  `target/surefire-reports/*.txt` with a `Time elapsed` per class on every build, and `roadmap-tool`
  already reads per-module build artifacts through a `**/target/leaf-coverage.jsonl` glob, so the
  precedent for a sibling step that reads build output is in place.
. **A total-suite budget.** Simpler to state, but noisier, and it drifts with hardware.
. **Recording the trend in CI without gating.** Documents the curve; does not stop it.

The objection that sinks option 2, hardware drift, looks like it should sink option 1 too, and it does
not. That is the argument the Spec pass should make explicitly, because without it the choice reads as
a coin flip. The signal here is two orders of magnitude, not a few percent: the same class measured
74.0s on one 4 vCPU sandbox and 120.4s on another while the module's median class stayed far under a
second on both, so any absolute ceiling in the 10 to 30 second range separates the pathology from
every healthy class on either machine. A per-class ceiling is *more* hardware-tolerant than a suite
total, not less.

Whichever shape is chosen, the budget belongs in the repository next to the tests it governs, and
raising it should be a visible commit rather than a silent drift.

## What the chosen host has to answer

Assuming the Spec pass lands on the Surefire reader, three mechanics are load-bearing and none of them
is obvious.

**Ordering, which is the one that can make the gate lie.** `roadmap-tool` declares a dependency only
on `graphitron-model`, so under the `-T 1C` that CI uses, Maven is free to schedule its `verify`
alongside or ahead of the modules whose reports it would read. `mvn test` never reaches `verify` at
all. And a `-pl`-scoped inner-loop build leaves reports behind that a later full build's reader would
happily treat as current. A reader that silently passes on absent or stale reports is worse than no
gate, because it reports safety it is not providing. Either fail closed (reports must postdate the
build's start, else fail loudly), or host the check in the module whose tests it governs instead of in
`roadmap-tool`.

**Its own test.** All nine existing `roadmap-tool` checks and reports have a paired `*Test`
(`AdocMarkdownTableCheck`, `AdocXrefAnchorCheck`, `CoverageAgentWiringCheck`, `DirectiveSupportReport`,
`LeafCoverageReport`, `ModuleEnumerationCheck`, `SchemaIdentifierDriftCheck`, `SourceCoverageReport`,
`TransientCitationCheck`). A new check inherits that convention.

**Where the rule is written down.** Every build gate in this repo has a prose home: the javadoc
reference gate has a paragraph in `CLAUDE.md`, and the `roadmap-tool` steps are described there and
under `docs/architecture/`. A new gate needs its paragraph in the same commit, or contributors meet it
first as a build failure.

## The unmeasured slices

R732 measured its three. These were listed as tempting and left unmeasured, and the numbers below are
the honest bounds established during R732's Spec review rather than the optimistic ones its first
draft carried. Measure before ordering: two of them may have no motivation left once R732's slice 1
lands.

| # | Slice | Bound | Confidence | Second pass |
|---|---|---|---|---|
| 1 | Memoise `ClassifiedHarness.classify` | bounded by ~17s | high | unchanged, now ~2% of the build |
| 2 | Share one captured-corpus store | bounded by ~9s after R732 | medium | unchanged, now ~1% of the build |
| 3 | Index the hot non-key join columns | unmeasured | measure first | **measured, promoted to first** |
| 4 | Share the Jetty server in `graphitron-mcp` | up to 15.5s | high | bound confirmed at 15.85s, ~2% of the build |
| 5 | Reduce a derived relation at write time | unmeasured, reader-facing | architectural | **subject named, and it is build time after all** |
| 6 | Decide whether PR builds need `-Pcoverage` | unmeasured | measure first | still unmeasured |

**1. Memoise the corpus classification.** Five sweep classes (`ClassifiedDslTest`,
`DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`,
`WrapperAlgebraTest`) plus the two shared readers they go through, `ExemptionRegistry` and
`ClassifiedCorpus.coveredLeaves`, call `ClassifiedHarness.classify(example.sdl())` per example with no
memoisation, several from more than one test method. The precedent is in the same class:
`launcherProductions()` is memoised once per JVM and its javadoc says why. A static map keyed on the
fixture SDL is nearly the whole change, with one caveat. `ClassifiedHarness.Result` is a record but
not deeply immutable, since `classify` hands its four bare `ArrayList`s straight into the constructor.
No caller mutates them today, so nothing is broken now, but a shared memo plus R732's class-level
parallelism turns that into a cross-test flake of exactly the kind that slice already tripped over.
Wrap the four lists at construction.

The bound is the eleven non-`ColumnMatchShadowTest` corpus sweeps' 17.1s, not the 96.1s an early draft
quoted: the class that dominated that figure calls `TestSchemaHelper.buildSchema` directly and never
touches `classify`, so this slice stands on removing repetition and holding the shape rather than on a
large recovery. Harvest check: total corpus classification passes per build drop from hundreds to 55.

**2. Share one captured-corpus store.** The capture-side sweeps each build their own store over the
whole corpus. One shared fixture, captured once per JVM, removes on the capture side the repetition
slice 1 removes on the classification side. Larger than slice 1 because store lifetime becomes shared
state across classes, so it wants R732's parallelism model settled first and the isolation
requirements known. Bound the expectation before starting: once R732 has taken `ColumnMatchShadowTest`
down, the remaining capture-side sweeps are `InputOccurrenceShadowTest` at 4.9s and
`DemandShadowTest` at 4.1s, and that is the whole pool.

**3. Index the hot non-key join columns.** The fact-store DDL declares 140 tables, 56 views, 161
primary keys and **zero** `CREATE INDEX`. The hot join inside `intent_column_match_claim` matches
`sql_column` on `jooq_name_upper` OR `column_name_upper`, neither of which leads a key, so it scans
the catalog per candidate field; `Value.compareToNotNullable` was 27 percent of the JFR samples on the
profiled class. Measure first and keep the result either way: H2 may decline to use an index under an
OR, in which case the finding is that the predicate wants restructuring rather than an index.

*Measured on the second pass, and the result is larger than anything else in this table.* The
predicate that dominates is not the `OR` this paragraph anticipated; it is the plain equality on
`sql_table.table_name_upper` inside `intent_spelled_table`, which resolves an authored table
spelling against the catalog and is expanded at 469 plan nodes inside a single generator query. One
index takes a full-fixture generator run from 46.90s to 23.49s. The `OR` caveat above stands
unrefuted for `intent_column_match_claim` specifically, and is simply not where the time was. What
does need carrying into the Spec pass, and what this paragraph did not anticipate, is the write
side: capture inserts the whole catalog, so the same index costs about 24 seconds across
`graphitron-sakila-example`'s five `graphitron:generate` executions. Each candidate index is a
separate two-sided measurement, and `Value.compareToNotNullable` remaining the top JFR leaf frame is
a symptom shared by every unindexed comparison rather than a pointer to one.

**4. Share the Jetty server in `graphitron-mcp`.** `GraphitronMcpServerTest` costs 15.5s across 60
test methods, 0.26s per test, the highest per-test cost in the reactor. Read that as a class average
rather than as 60 boots: the class constructs `new GraphitronMcpServer(...)` at 19 sites, while the
remaining tests call statics such as `GraphitronMcpServer.statusResult` and boot nothing. So the slice
is to share one server across the methods that need one, and a few of the 19 have the server's
lifecycle as their subject (port-in-use, close semantics) and must keep their own.

**5. Reduce one derived relation at write time.** The producer-side change described below, on one
relation, with a before and after number. Deliberately last among the code slices, and the honest
justification is reader latency in the dev loop, the LSP and the MCP server rather than build time.
Populate from the view so the rule stays in one place, and write the population order explicitly
rather than deriving it, since H2 offers no dependency catalog to derive it from. Read
`docs/architecture/explanation/fact-model.adoc` first: R732 lands the ruling there on what a reduction
may be built out of on H2, which is an ordinary table or a `LOCAL TEMPORARY` one and never a
materialized view, along with the trap that H2's bare `CREATE TEMPORARY TABLE` defaults to `GLOBAL`
and its global temporary tables share rows across every attached session. This slice is that ruling's
first consumer, so if the page does not yet carry it, R732 did not finish.

*Measured on the second pass, then moved out of this item.* The subject turned out to be an
architectural property of the whole `intent_` stratum rather than one relation's tuning: H2 inlines
every view reference with no common-subexpression elimination, so one read of
`intent_argmapping_projection_defect` expands to 2149 relation instantiations and takes 24.5
seconds. Reducing two relations takes it to 0.72s. R742 carries that work, with the measurements,
the statically computable selection rule proposed as this item's missing derived-read enforcer, and
what the migration costs. This slice stays here as the pointer; do not spec it twice.

One correction worth keeping even so, since the paragraph above states the opposite: it says the
honest justification is reader latency in the dev loop rather than build time. On the measured
numbers it is build time, and specifically *consumer* build time, the generator being what reads
these relations. The dev-loop, LSP and MCP latency case still holds and is now the smaller half.

**Also carried across, smaller than a slice.** R732 turns on class-level test parallelism in
`graphitron` only, because that is the module the 170.5s to 117.1s number was taken in. Extending the
same `junit-platform.properties` settings to `graphitron-lsp`, `graphitron-mcp` and `graphitron-model`
is unmeasured, and each module has its own shared-state question to answer first, so take them one at
a time and only after R732's parallelism model has settled in the module it was measured in.

**6. Decide whether PR builds need `-Pcoverage`.** CI attaches the JaCoCo agent to every run
including PRs (`mvn install -Plocal-db -Pcoverage --batch-mode -T 1C`). The stated reason, keeping the
wiring continuously exercised so an `argLine` regression fails on the PR that introduced it, is sound
and should not be discarded casually; `CoverageAgentWiringCheck` already guards part of it. But the
cost has never been measured. One A/B run settles it; if it is material, exercising the wiring on
trunk pushes only is a defensible trade.

## Derived data has a producer side, and three of its rules have no enforcer

We derive on read: capture writes plain facts, and every classification, reduction and scope
resolution is a view evaluated when someone selects from it. That is why the schema is
self-documenting and why a rule lives in exactly one place. What is missing is any notion of *when* a
derivation is paid for. Deriving on read is right for a relation read once per pass and wrong for one
read once per graph in a loop.

Under this repo's own "every invariant has an enforcer" axiom, three rules here are prose with nothing
behind them, and turning them into enforcers is the most principled work in this item.

* Batch by key set, never loop by key. A caller that needs a derived relation for N partitions issues
  one query and groups in the caller. This is the same discipline as the DataLoader batching the
  generated code already does, applied to our own reads. **This rule has a measured violation in the
  tree**, and it is the strongest argument in this item for turning the three into enforcers:
  `StoreNodeTables.read` loops a window view once per node type, its own javadoc says it does not,
  and correcting it took 13.6 seconds off every full-fixture generator run. A rule that a method's
  own documentation asserts and its body contradicts is not a rule anyone is going to catch by
  reading.
* Put the derivation first in the FROM clause. `intent_column_match_claim`'s DDL comment already
  states this, and `intent_field_column_scope`'s comment restates it, and nothing checks either.
* A derived relation that will be read per partition is a candidate for reduction, not a candidate for
  a cleverer query.

An enforcer for the second is plausible mechanically, since the DDL is parseable and the rule is
structural. The first and third are about call sites rather than about the schema, so their enforcer is
likelier a review rule with a named test than a build gate. Decide per rule at Spec time; a rule
declared enforceable and then left as prose is the drift smell the axiom names.

## The consumer side, for whoever picks this up

The same pathology exists one level out, in generated code: a resolver reading a derived relation once
per parent row is the per-outer-row cost again, and PostgreSQL's planner is much stronger than H2's but
the N+1 shape does not care. Graphitron already defends this with DataLoader batching, and the
execution tier already has the instruments to prove it, the `QUERY_COUNT` and `SQL_LOG` recording
idioms in `graphitron-sakila-example`'s execution tests. So the consumer-facing work is coverage, not
new machinery: assert round-trip counts on the paths that read derived relations, so a generator change
that turns a batched read into a per-row read fails here rather than in a consumer's production
database.

Worth knowing for anyone extending this to consumer schemas: PostgreSQL has what H2 lacks, `pg_matviews`
naming materialized views with `ispopulated` and `definition`, and `pg_depend` joined to `pg_rewrite`
yielding the dependency graph uniformly for views and materialized views. A consumer-side story could
use them. The fact store cannot, for reasons that live in
`docs/architecture/explanation/fact-model.adoc`: R732's fourth deliverable moves the H2
materialized-view ruling there precisely so this slice has something permanent to read, since R732's
own file is deleted when it reaches Done. Read that page before designing the reduction; it also
carries what a reduction may be built out of, which is not a materialized view.

## How to re-measure

The recipes R732 carries apply unchanged, and any number above can be checked or refuted with them.
Two standing caveats: always pass `-Plocal-db` or the jOOQ catalog jar is silently emptied and the
failures will be unrelated cascades, and measure against a warm local repository or artifact downloads
will dominate. The three that matter here:

```bash
# Per-class times, from a timestamped log; no extension needed.
mvn install -Plocal-db \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS | tee build.log

# Hot-path attribution inside one suspect class.
mvn test -pl :graphitron -Plocal-db -Dleaf-coverage.skip \
  -Dtest=<Class> -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="-XX:FlightRecorderOptions=stackdepth=1024 -XX:StartFlightRecording=filename=hot.jfr,settings=profile,dumponexit=true"
jfr print --events jdk.ExecutionSample --stack-depth 2000 hot.jfr

# Wall clock, both ways; they answer different questions.
time mvn install -Plocal-db
time mvn install -Plocal-db -T 1C
```

Per-class times come from the `Tests run: ... Time elapsed: ... -- in <class>` lines, attributed to
modules by tracking the preceding `Building no.sikt:<module>` line.

Three corrections to these recipes, learned by running them on the second pass. **`stackdepth=1024`
is load-bearing and is why the flag above changed.** JFR's default depth is 64 frames, the H2 view
stack is deeper than that on its own, and every sample truncates below the H2 frames, so the profile
attributes 97% of the run to `org.h2` with no caller and looks like a dead end. It is not a dead
end; it is a truncated stack. **Do not read per-goal timings off the log by measuring the gap to the
next goal line.** Untimed work between goals lands in the preceding goal's bucket, which is how the
generate goal appeared to grow by 18 seconds when the change under test only removed reads. Time a
phase directly instead (`time mvn generate-sources -pl :graphitron-sakila-example -Plocal-db`).
**For "which query, and why", `EXPLAIN ANALYZE` beats the profiler**, because a JFR frame names the
call site and not the plan. Run it against the real populated store rather than a seeded one, by
temporarily printing `dsl.resultQuery("EXPLAIN ANALYZE " + dsl.renderInlined(query))` beside the
read under test behind an environment-variable guard; H2's `scanCount` per plan node is what turns
"this query is slow" into "this view is expanded 469 times".
