---
id: R733
title: "Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: [build-time-recovery]
created: 2026-08-19
last-updated: 2026-08-19
---

# Hold the build wall clock with a budget, and take the derived-read slices R732 left unmeasured

R732 harvests the three slices whose wins were measured before it was written, and stops there
deliberately. This item carries the rest: the slices whose wins were still unmeasured at that point,
and the guardrail without which the recovered time drifts back. It is the second pass, and it wants
its own Spec cycle rather than an amendment to the first, because the guardrail is an architectural
choice and the remaining slices need numbers before they can be ordered against each other.

Everything below restates the facts it needs rather than pointing into R732's body, because that
body is deleted when R732 reaches Done.

## Why a guardrail is the spine of this item

Trunk CI went from a 5.2 minute median to a 15.3 minute median over seven weeks while the test-method
count rose 21 percent. Cost outran volume, and nothing measured it. R732 buys the time back once; the
reason the curve was allowed to triple is that no build artifact ever failed or flagged when the shape
regressed, so a repeat is a matter of time rather than of vigilance.

The failure mode is specific and it is what a guardrail has to catch. It was never a thousand slow
tests: it was one class, `ColumnMatchShadowTest`, at 74 to 120 seconds inside a `graphitron` suite
whose median class costs a fraction of a second. A per-class ceiling would have caught it on the
commit that introduced it. A total-suite budget would have absorbed it for weeks.

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

| # | Slice | Bound | Confidence |
|---|---|---|---|
| 1 | Memoise `ClassifiedHarness.classify` | bounded by ~17s | high |
| 2 | Share one captured-corpus store | bounded by ~9s after R732 | medium |
| 3 | Index the hot non-key join columns | unmeasured | measure first |
| 4 | Share the Jetty server in `graphitron-mcp` | up to 15.5s | high |
| 5 | Reduce a derived relation at write time | unmeasured, reader-facing | architectural |
| 6 | Decide whether PR builds need `-Pcoverage` | unmeasured | measure first |

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
rather than deriving it, since H2 offers no dependency catalog to derive it from.

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
  generated code already does, applied to our own reads.
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
use them. The fact store cannot, for the reasons R732 records.

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
  -DargLine="-XX:StartFlightRecording=filename=hot.jfr,settings=profile,dumponexit=true"
jfr print --events jdk.ExecutionSample --stack-depth 60 hot.jfr

# Wall clock, both ways; they answer different questions.
time mvn install -Plocal-db
time mvn install -Plocal-db -T 1C
```

Per-class times come from the `Tests run: ... Time elapsed: ... -- in <class>` lines, attributed to
modules by tracking the preceding `Building no.sikt:<module>` line.
