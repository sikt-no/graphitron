---
id: R732
title: "Recover the build wall clock, starting from what derived reads cost"
status: In Review
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Recover the build wall clock, starting from what derived reads cost

**All four deliverables have shipped.** Trunk CI had gone from a 5 minute median to a 15 minute median in seven weeks, and the curve was steeper than the suite was growing: between 8 and 19 August the number of test methods rose 21 percent while CI wall clock rose 53 percent. Cost outrunning volume means the shape is wrong somewhere, not that we have simply written more tests. Two thirds of a full build was the test phase, and the largest single share of that was H2 evaluating the fact store's own derived relations over and over. This item named the mechanism, harvested the three slices whose wins were measured before it was written, and put the one durable finding it produced somewhere that outlives this file.

Measured on one 4 vCPU sandbox, the same machine before and after, on the tree at each end:

| Build | Before | After | Recovered |
|---|---|---|---|
| `mvn install -Plocal-db` | 9m06s (547.6s) | 6m44s (406.3s) | 141.3s, 26 percent |
| `mvn install -Plocal-db -T 1C`, as CI runs it | 8m26s (508.1s) | 5m58s (360.3s) | 147.8s, 29 percent |

5,900 tests green in the final run. The `graphitron` module, where three of the four deliverables land, went from 181.2s to 70.6s of wall clock on its own.

**Scope, and why it was this narrow.** The diagnostic pass that produced this item turned up ten candidate slices. Three of them had numbers: a spike that was measured and reverted, a duplicated-work span read off a timestamped log, and a parallelism experiment run entirely through command-line flags. The other seven were tempting and unmeasured, and one of them, the guardrail that keeps the recovered time from drifting back, is an architectural choice rather than a harvest. Bundling a measured harvest with an unmeasured program means the whole thing waits on the slowest decision in it, so the seven went to R733 with their bounds and their open questions intact, and this item shipped the three. R733 depends on this one, and the number two of its slices were waiting for now exists: what is left after slice 1 and slice 3 is a `graphitron` module at 70.6s whose slowest single class is 6.0s.

That split is deliberate about what this item does not promise. Recovering the wall clock once is not the same as holding it, and holding it is R733's spine rather than a footnote here. The reason CI tripled is that nothing measured it; this item does not fix that, and the green build it leaves behind is not evidence that the curve cannot resume.

Two terms used throughout. A **derived relation** is a fact-store relation whose rows are computed from other relations rather than written by capture, which in the current schema means one of the 56 SQL views layered over the 140 captured tables (a `CREATE VIEW` count of `graphitron-model.sql`; do not read the view count as tracking the corpus count, which is separately 55). A **corpus sweep** is a test that loops the 55 spec-by-example fixtures and asserts something about each one, which is a recurring test shape in `graphitron` though not, as measured below, a distributed cost.

## What was measured

**This section and the next are the diagnosis, and every figure in them is a "before".** They are kept because they are the argument for why the four deliverables are these four rather than others, and a reviewer checking that argument needs them; do not read the tables as current. The after-figures are at the top and in "What shipped".

Everything below came from one session's measurements on a 4 vCPU sandbox with a warm local repository, JDK 25 and Maven 3.9.11. The re-measurement recipes are at the end so any number here can be checked or refuted.

The CI figure is the one to treat as a floor rather than a reading. It was taken on 19 August; a re-measurement during review, over the 30 most recent trunk-push runs by the same recipe, put the median nearer 18.4 minutes. The curve has not flattened while this item sat in Spec, which strengthens the case for the slices rather than changing any of them, so the original figure stays and this note dates it.

| Figure | Value |
|---|---|
| Full `mvn install -Plocal-db`, single-threaded | 8m40s wall, 517s of mojo time |
| Same with `-T 1C` (4 threads) | 6m58s wall |
| Surefire, summed across modules | 340.7s, 66 percent of mojo time |
| Test inventory | 5,821 tests in 595 classes, 324s of measured in-test time |
| CI trunk-push median, 1 July to 19 August | 5.2m to 15.3m, sampled over 238 runs |

Per module, and the per-test cost is the tell:

| Module | Tests | Test time | s / test |
|---|---|---|---|
| `graphitron` | 3,637 | 167.0s | 0.046 |
| `graphitron-lsp` | 606 | 43.0s | 0.071 |
| `graphitron-model` | 291 | 36.3s | 0.125 |
| `graphitron-sakila-example` | 799 | 31.8s | 0.040 |
| `graphitron-mcp` | 145 | 27.8s | 0.191 |
| `graphitron-maven-plugin` | 112 | 11.9s | 0.106 |
| `graphitron-roadmap-tool` | 231 | 6.1s | 0.027 |

For contrast, `GraphitronSchemaBuilderTest` runs 484 assertions in 2.8s, 0.006s per test, because they share one fixture. Anything an order of magnitude above that is paying a per-test setup cost, and in this codebase that cost is almost always a query against a derived relation.

## The mechanism

`ColumnMatchShadowTest` is the extreme case and the clearest specimen: three test methods, 74.0 seconds, which is 14 percent of the build's 517s of mojo time and 23 percent of the 324s of measured in-test time. (Two figures for this class appear below: 74.0s in the un-instrumented run summarised above, 78.4s in the spike run slice 1 quotes. Both are this class; neither is a typo. An independent re-measurement during the Spec review, on a different 4 vCPU sandbox, put it at 120.4s of a 268.6s module total, so treat the absolute as machine-dependent to roughly ±60 percent and the ranking as the durable part.) A JDK Flight Recorder profile of it (7,058 execution samples) puts **96 percent of samples inside `org.h2` query execution**, with garbage collection at 0.6s total across 188 pauses and store boot at roughly 12ms per store. It is neither the harness nor allocation. It is the SELECTs.

The first test captures all 55 corpus examples into one store as 55 graphs, then loops the examples again; before slice 1 it ran its masked-claims query once per graph inside that loop. That query reads `intent_column_match_claim`, which carries a `ROW_NUMBER() OVER (PARTITION BY ...)` over `intent_field_column_scope`, itself a view over `DISTINCT` and `GROUP BY` subqueries. H2 re-evaluates a joined derived relation once per outer row and does not push the `graph_name` predicate through the window, so each of the 55 queries pays for all 55 graphs' rows. Cost is quadratic in corpus size, and the corpus went from 44 to 55 examples over the same weeks CI doubled.

This is not news to the schema. `intent_column_match_claim`'s own DDL comment already states the rule: *"H2 re-evaluates a joined derived relation once per outer row, so reading the scope from underneath `graphql_field` costs the whole relation per candidate field and measured seventy times this shape on a store holding a dozen graphs. Any relation joining a derivation this deep wants the derivation first in the FROM clause."* That rule is prose in a comment with no gate behind it, and the read side has no equivalent rule at all.

The corpus-sweep shape is what multiplies it, but the multiplication is concentrated in one class rather than spread across the sweeps, and the Spec review's re-measurement is what establishes that. Twelve test classes reach the corpus: nine read `ClassifiedCorpus.examples()` directly (`ColumnMatchShadowTest`, `DemandShadowTest`, `InputOccurrenceShadowTest`, `ClassifiedDslTest`, `QueryViewRendererTest`, `DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`, `WrapperAlgebraTest`), two reach it through `ExemptionRegistry` (`VariantCoverageTest`, `ExemptionRegistryTest`), and `ClassifiedDocTest` reaches the `docExamples()` subset. Together they measured 137.5s of the module's 268.6s, but 120.4s of that is `ColumnMatchShadowTest` alone and the other eleven total 17.1s, six percent of the module. Five of the twelve call `ClassifiedHarness.classify(example.sdl())` per example, a full parse plus schema build, with no memoisation, several from more than one test method (`ClassifiedDslTest`, `DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`, `WrapperAlgebraTest`), as do the two shared readers those sweeps go through, `ExemptionRegistry` and `ClassifiedCorpus.coveredLeaves`. `ColumnMatchShadowTest` is not among them: it calls `TestSchemaHelper.buildSchema` directly, which is why the repetition it pays is not the repetition a `classify` memo would remove, and why memoising `classify` is R733's slice rather than a companion to slice 1 here. Every new corpus example and every new sweep still multiplies against each other; what the measurement rules out is that the multiplication is already large anywhere but the one class.

A synthetic reproduction of the same view stack (55 graphs, 5,280 fields, 1,200 catalog columns, same window function) isolates the read pattern from everything else:

| Read pattern | Time |
|---|---|
| One query per graph, 55 graphs | 5,852 ms |
| One query for all graphs, grouped by the caller in Java | 109 ms |

Fifty-four times, from batching alone, with identical rows and identical anti-joins.

## Why this does not use materialized views

**This section has landed in `docs/architecture/explanation/fact-model.adoc`** and is deliberately
not restated here, because two copies of one ruling drift and this file is deleted at Done. The
short form: `CREATE MATERIALIZED VIEW` exists on H2 2.4.240 and would take the synthetic per-graph
sweep from 5,852 ms to 4 ms, and four defects rule it out, of which two are fatal on their own. Any
read of `INFORMATION_SCHEMA.COLUMNS` throws while a materialized view exists anywhere in the
database, which breaks the model build outright since jOOQ codegen boots off this schema's live
metadata; and a file-backed store containing one cannot be reopened, because the replayed DDL is
`CREATE FORCE MATERIALIZED VIEW` and the parser answers `FORCE` there with an unimplemented-operation
throw. Dropping one corrupts the catalog, and there is no dependency catalog to derive a refresh
order from. All four were traced to cause and the two fatal ones fixed and verified against H2 trunk,
in twelve lines and about sixty; none of that is in a release and none is filed upstream, which is
what makes the ruling conditional on the tool rather than on the feature. The page carries the ruling
at that altitude plus what a stored reduction is instead, and `graphitron-model.sql`'s header points
at it. It also records the point that decided this item's own shape: batching pays one evaluation and
materializing pays one plus a refresh, so for a write-then-sweep workload they are equivalent and
slice 1 gave up nothing by not reaching for a snapshot.

## Why deriving on read produces this shape

The read cost above is a symptom of a design choice worth stating plainly, because it is a good choice with one missing half. We derive on read: capture writes plain facts, and every classification, reduction and scope resolution is a view evaluated when someone selects from it. That is why the schema is self-documenting and why a rule lives in exactly one place. What is missing is any notion of *when* a derivation is paid for. Deriving on read is right for a relation read once per pass and wrong for one read once per graph in a loop, and slice 1 is one caller in the second category being moved into the first.

That is as far as this item takes the idea, and the boundary is worth being explicit about, because the diagnosis reaches further than the fix does. Paying a derivation once and writing the rows, whether persistently at capture time or into a `LOCAL TEMPORARY` per reader connection, is a real lever with a reader-facing payoff in the dev loop, the LSP and the MCP server, and three producer-side rules that the fact store's own DDL comments already state have nothing enforcing them. None of that is in scope here. All of it, with the reader-side and consumer-side cases and the enforcement question, moves to R733. Slice 1 batches one read; it does not establish a policy for derived reads, and it should not be reported as having done so.

## What shipped

Four commits, taken in the plan's order with a re-measurement between each, because slice 1 and
slice 3 interact.

| # | Deliverable | Where | Measured |
|---|---|---|---|
| 1 | The column-match sweep reads its claim view once | `ColumnMatchShadowTest` | class 86.6s to 12.3s; module 181.2s to 104.8s |
| 2 | The javadoc gate stops forking | root `pom.xml` | full install 9m06s to 7m40s with slice 1 in the tree |
| 3 | Class-level test parallelism | `graphitron` `junit-platform.properties`, two `@Isolated` marks | module 98.4s to 70.6s |
| 4 | The H2 ruling gets a permanent home | `fact-model.adoc`, `graphitron-model.sql` header | not a harvest |

**1. The sweep reads its claim view once.** `maskedClaims(dsl, graphName)` became
`maskedClaimsByGraph(dsl)`: `graph_name` joins the projection, the query runs once for the whole
store, and the caller pairs the result per example through `fetchGroups`. The rows are identical
because both anti-joins already correlated on `graph_name` themselves, so the outer graph predicate
only ever chose which of those rows a caller looked at. Positional `Record4` access gave way to a
`MaskedClaim` record, since the projection grew a column. The shared store and the sibling-scoping
property the sweep's own docstring calls load-bearing are untouched, and the assertions are
unchanged, which is what proves the pairing: a grouping keyed wrong empties the compared map and
fails `containsExactlyInAnyOrderEntriesOf`.

The plan asked for a read of the other eleven sweep classes for the same shape. Done, and it
confirmed rather than harvested, as the plan predicted: none of them runs a per-graph query in a
loop over a multi-graph store, which is the shape that costs. The whole-module run bears that out,
with the slowest surviving class at 6.0s.

**2. The javadoc gate stops forking.** The `check-link-references` execution now binds
`javadoc-no-fork`. The pom comment carries why, including the interaction that made the attribution
look wrong: Maven runs a mojo's `@Execute` fork before the mojo body evaluates its skip parameter,
so `graphitron-sakila-example` paid for a whole forked `generate-sources` lifecycle and then skipped
the goal it forked for. It also names `test-javadoc-no-fork` for whoever adds a second execution
beside this one, which is R730's job.

Harvest check, both halves, on a full install: zero `>>> javadoc ... > generate-sources` spans in
the log, against 14 on the baseline tree, and each of the five named `graphitron:generate`
executions appears exactly once across the whole build.

Gate non-vacuity proven, and saying which way the plan's two options were taken: a *new public*
class carrying a dangling `{@link}`, on a clean `target/`, fails the build with "reference not found"
at that line. One trap worth leaving behind, because it produced a false green on the first attempt:
a package-private probe class does not fail, since javadoc's default visibility documents public and
protected only, so such a probe proves nothing about the gate.

**3. Class-level test parallelism.** `graphitron`'s `junit-platform.properties` sets classes
concurrent, methods `same_thread`, fixed parallelism 4, and the file carries the reasoning rather
than the values alone: a class owns a fixture here (a `@TempDir` and the H2 store captured into it)
so two classes share nothing, while two methods of one class routinely share their class's store.
Fixed rather than machine-relative, so concurrency does not vary by machine and an
ordering-sensitive failure cannot become one that reproduces only elsewhere.

`ClassificationTraceTest` and `SingleWalkClassificationOrderTest` carry `@Isolated`, the first uses
in the tree, each javadoc saying what the annotation answers. In the ordering test that means saying
which of its two isolation concerns is which, since its class comment already argued record
isolation by unique type names, which is a different question from the writer's binding.

Measured on the final tree, same commit both ways: 98.4s sequential against 70.6s at four threads,
3,677 tests green in both. The marginal gain is 27.8s rather than the 53s the plan predicted, and
slice 1 is why: removing the 83s sweep took most of what parallelism would otherwise have harvested.
The two together are 181.2s to 70.6s, which is more than either predicted alone.

**4. The H2 ruling has a permanent home.** In
`docs/architecture/explanation/fact-model.adoc`'s "Derived reads are views, not stored facts"
section, immediately after the derivation-first-in-`FROM` rule a reader meeting it would reach for
`CREATE MATERIALIZED VIEW` against. What travelled is the ruling at defect altitude, its
conditionality on no release carrying the fixes, and what a stored reduction is instead (an ordinary
table populated from the view, or an explicitly `LOCAL TEMPORARY` one, with the `GLOBAL`-default
trap). What did not travel is every transient measurement, including the twelve-class corpus census.
`graphitron-model.sql`'s header carries the one-line pointer.

The section turned out to need no enforcer invented for it, which is recorded on the page beside its
other enforcers: jOOQ codegen reads `INFORMATION_SCHEMA.COLUMNS` off a live store booted from this
schema, so a `CREATE MATERIALIZED VIEW` added to `graphitron-model.sql` fails the model build before
any test runs. The `LOCAL` requirement has no enforcer and is labelled a trap rather than an
invariant, which is the honest reading of it.

### The one consequence this leaves behind

Slice 3 shifts the composition of the leaf-coverage trace, and R736 rather than this item owns the
cause. `resetForTesting(null)` leaves the writer disabled for the rest of the fork, so which classes
emit at all depends on where those two teardowns fall in the schedule. On the final tree the module
emits 17,565 records from 83 classes sequentially and 42,158 from 170 classes in parallel: 87 classes
gained, none lost. So the next regeneration of `roadmap/inference-axis-coverage.adoc` will show
leaves as newly observed that were always observed. That is scheduling rather than coverage, it is
recorded in slice 3's commit message as the plan requires when this lands before R736, and the
monotone direction means it cannot mask a real regression.

Seven further slices came out of the same diagnostic pass and are R733's, with their bounds and open
questions carried across: memoising `ClassifiedHarness.classify`, sharing one captured-corpus store,
indexing the hot non-key join columns, sharing the Jetty server in `graphitron-mcp`, reducing a
derived relation at write time, settling whether PR builds need `-Pcoverage`, and the guardrail. Two
things this item ruled out for both, and the numbers behind them still hold: module-level
parallelism is close to exhausted, since the critical path runs `javapoet` to `model` to
`graphitron` to `maven-plugin` to `sakila-example`, and the docs render already has an opt-out in
`-P'!docs'`, so it is a local-loop convenience rather than a build problem. On this sandbox
module-level parallelism is worth about the same at both ends, 39.5s on the baseline tree and 46.0s
after, so the harvest came out of the sequential work rather than out of `-T 1C`'s headroom, and
R733 inherits a build whose module-parallel and single-threaded times are still 46s apart.


## Sequencing against the in-flight items

How each collision actually resolved. Recorded because none of them earned a `depends-on` entry, so a reviewer reading an empty `depends-on:` has no other place to check the reasoning against the outcome.

* **R680 reached Done before slice 1 landed, and cost slice 1 nothing.** It was restructuring the fact-store test harnesses while this item was drafted, and the prediction was that its restructure had settled without moving the API slice 1 uses. It held: `CapturedStore` is `public final` in `no.sikt.graphitron.rewrite` with its factory set intact, and the sweep still reaches the store through `CapturedStore.ofCatalog` and `andCatalogGraph`, both re-checked after R680 landed. R733 carries the two slices whose subjects were more deeply inside R680's territory.
* **R730 was still Ready, so slice 2 landed first and R730 inherits the obligation.** Both edit `maven-javadoc-plugin` executions in the root pom. The pom comment now names `test-javadoc-no-fork` at the point where R730 will add its execution, so the obligation sits in the file R730's implementer opens rather than in an item body. If R730 adds `test-javadoc` rather than `test-javadoc-no-fork`, it reintroduces exactly the waste slice 2 removed.
* **R568 is still Backlog, and slice 2's proof took the workaround it predicted.** The up-to-date check keys on the option strings plus the source file list rather than on content, so proving the gate still bites needed a new file on a clean `target/` rather than an edit to an existing one. R568 would make that proof ordinary; nothing about slice 2 changes either way.
* **R736 reached Spec while slice 3 was being implemented, so slice 3 went first and said so.** The preference was for R736 first, since it makes slice 3's effect on the leaf-coverage record count legible; it had no plan body yet and slice 3 is correct either way, so the plan's fallback applied and slice 3's commit message records the composition shift with its numbers. Read R736's spec before reviewing slice 3's `@Isolated` marks: it establishes that the coverage report truncation is *published* on every trunk push, not merely local, and it plans a thread-scoped trace binding that would remove the race those marks guard and let them be deleted. That supersession is the intended direction rather than a conflict, and it is why slice 3's marks are the smallest thing that makes parallelism safe today rather than the shape the writer should end up with.

## How to re-measure

Every number above is reproducible with the recipes below. Two standing caveats: always pass `-Plocal-db` or the jOOQ catalog jar is silently emptied and the failures will be unrelated cascades, and measure against a warm local repository or artifact downloads will dominate (116 artifacts and 28.5s of downloads appeared in the first profiled run).

**Per-mojo attribution.** Add `fr.jcgay.maven:maven-profiler:3.3` as a build extension, run, then read the JSON report. The extension was intentionally not committed; if this becomes routine, wire it behind an opt-in profile rather than making every contributor's build load it.

```bash
mkdir -p .mvn && cat > .mvn/extensions.xml <<'XML'
<extensions>
  <extension>
    <groupId>fr.jcgay.maven</groupId>
    <artifactId>maven-profiler</artifactId>
    <version>3.3</version>
  </extension>
</extensions>
XML
mvn install -Plocal-db -Dprofile -DprofileFormat=JSON
# report lands in .profiler/profiler-report-<timestamp>.json
# remove .mvn/extensions.xml and .profiler afterwards
```

**Timestamped log, which needs no extension and cross-checks the above.** Every mojo boundary and every test class gets a wall-clock stamp, so per-mojo and per-class times fall out of a parse of the log.

```bash
mvn install -Plocal-db \
  -Dorg.slf4j.simpleLogger.showDateTime=true \
  -Dorg.slf4j.simpleLogger.dateTimeFormat=HH:mm:ss.SSS | tee build.log
```

The slowest test classes come from the `Tests run: ... Time elapsed: ... -- in <class>` lines; attribute them to modules by tracking the preceding `Building no.sikt:<module>` line. The javadoc fork cost is the span between each `>>> javadoc ... > generate-sources @ <module> >>>` and its matching `<<<`.

**Hot-path attribution inside one suspect class.** JFR on the Surefire fork, then aggregate samples by stack. This is what separated "the harness is slow" from "the SELECTs are slow".

```bash
mvn test -pl :graphitron -Plocal-db -Dleaf-coverage.skip \
  -Dtest=ColumnMatchShadowTest -Dsurefire.failIfNoSpecifiedTests=false \
  -DargLine="-XX:StartFlightRecording=filename=cms.jfr,settings=profile,dumponexit=true"
jfr summary cms.jfr
jfr print --events jdk.ExecutionSample --stack-depth 60 cms.jfr
```

**The parallelism experiment, with no source or pom change.** JUnit reads its configuration parameters from system properties, and `argLine` puts them in the fork, so the number can be had before committing to anything.

```bash
mvn test -pl :graphitron -Plocal-db -Dleaf-coverage.skip -DargLine="\
-Djunit.jupiter.execution.parallel.enabled=true \
-Djunit.jupiter.execution.parallel.mode.default=same_thread \
-Djunit.jupiter.execution.parallel.mode.classes.default=concurrent \
-Djunit.jupiter.execution.parallel.config.strategy=fixed \
-Djunit.jupiter.execution.parallel.config.fixed.parallelism=4"
```

**Wall clock, both ways.** `time mvn install -Plocal-db` for the attributable single-threaded figure, and `time mvn install -Plocal-db -T 1C` for what a contributor and CI actually experience. Report both; they answer different questions.

**The CI trend.** Trunk-push runs of `rewrite-build.yml` through the Actions API, taking the median per day of successful runs and computing duration from `run_started_at` to `updated_at`. Paging is needed: roughly 30 runs come back per page and there are 15 or more runs a day, so reaching back a month means sampling pages rather than reading them all. Per-step timings for one run come from the workflow jobs endpoint, which is how the reactor build step was separated from the coverage and docs steps.

**Growth correlation.** Count test methods and corpus examples at dated commits, so a wall-clock jump can be checked against whether the suite actually grew. Both recipes need history the working clone may not have: an agent sandbox is often a shallow clone whose whole history carries one date, where both commands run and return a figure for `HEAD` alone. Check `git rev-parse --is-shallow-repository` before trusting a trend from them. Note also that the first recipe counts test *methods* while the tables above count Surefire *tests*, which a `@ParameterizedTest` expands; on the reviewed revision that is 5,593 against 5,821, so compare each figure only against itself across revisions.

```bash
git grep -c -E '^\s*@(Test|ParameterizedTest)' <rev> -- '*/src/test/**/*.java' | awk -F: '{s+=$NF} END{print s}'
git show <rev>:graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/ClassifiedCorpus.java | grep -c 'new Example('
```

## What this item does not do

The three slices bought back time once. They do not hold it, and nothing here is evidence that the
curve cannot resume: the guardrail that would make it evidence is R733's, along with the argument for
which shape it should take and the mechanics its host has to answer. Read the 5m58s as one recovery,
not as a floor. The 26 to 29 percent recovered is also not a claim about CI, which runs on different
hardware under different contention; the figures above are one sandbox measured at both ends, which is
what makes them comparable to each other and to nothing else.

Slice 1 also does not establish a policy for derived reads. It moved one caller from the
read-once-per-graph category into the read-once category, and the general lever, paying a derivation
once and storing the rows, is R733's with the reader-side and consumer-side cases and the enforcement
question attached.

## What a reviewer should check

The four deliverables are four commits, each carrying its own measurement, so the cheap pass is to
read them in order against this section. Beyond that, three things are worth an independent look, and
the recipes below reproduce all of them.

The one behavioural risk is slice 1: whether the batched read still compares what the per-graph read
compared. The argument is that the anti-joins were already `graph_name`-correlated so the rows are
identical, and the check is that the sweep's own assertions are unchanged, so a mis-keyed grouping
fails rather than passes quietly. Worth confirming by reading the query rather than trusting the
green run.

The one thing a green build does not prove is slice 3's `@Isolated` marks, because the race they
prevent does not reproduce reliably: two review passes disagreed about whether it fires. Read them
against the writer's lifecycle rather than against a test result.

And the numbers are all re-measurable on any 4 vCPU box with the recipes below. Measure both ends on
one machine; absolutes moved by up to 60 percent between the two sandboxes this item was measured on,
while the ranking held every time.

## What the Spec review settled (2026-08-19)

Recorded so the next pass does not re-derive it. Every class, view, goal, property and pom execution
named above exists as named; the `intent_column_match_claim` DDL comment is quoted verbatim;
`javadoc-no-fork` and `test-javadoc-no-fork` both exist in maven-javadoc-plugin 3.12.0 with the
fork/no-fork split slice 2 relies on, confirmed against the plugin descriptor.

The review's re-measurement, on a second 4 vCPU sandbox: `mvn test -pl :graphitron`, 3,651 tests
green, 268.6s of in-test time across 368 classes, `ColumnMatchShadowTest` at 120.4s and every other
class at or under 10.1s. That reproduces the diagnosis and refutes one figure the first draft carried,
the 96.1s attributed to fourteen corpus-sweep classes: it is one class, and the other eleven sweeps
total 17.1s. The corrections that followed are folded in above, and the two slices whose ordering
rested on that figure moved to R733 where their real bounds are recorded.

Three things the review raised needed a decision rather than a correction. Two of them, the
guardrail's shape and its host's mechanics, left this item's scope with the guardrail. The third,
sequencing against the in-flight items, is answered in its own section above.

A second review pass, after the scope narrowed, raised three more and all three are folded in above.
It could not reproduce slice 3's crash (3,653 tests, zero failures) and traced the mechanism to a race
between `emit` and an `@AfterEach` teardown, which is why that slice now says a green run is not
evidence. It measured the leaf-coverage record count shifting under parallelism, 17,476 sequentially
against 16,933 at four threads, with classes both losing and gaining records, and root-caused it to a
pre-existing latch in `resetForTesting(null)`, now filed as R736. And it observed that the H2 ruling
had no destination outside this file, which is what the fourth deliverable answers. It also confirmed
the `graphitron-sakila-example` share of slice 2's waste is real despite that module skipping the
gate, since the fork runs before the skip is evaluated.
