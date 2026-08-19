---
id: R732
title: "Recover the build wall clock, starting from what derived reads cost"
status: In Progress
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Recover the build wall clock, starting from what derived reads cost

Trunk CI has gone from a 5 minute median to a 15 minute median in seven weeks, and the curve is steeper than the suite is growing: between 8 and 19 August the number of test methods rose 21 percent while CI wall clock rose 53 percent. Cost outrunning volume means the shape is wrong somewhere, not that we have simply written more tests. Two thirds of a full build is now the test phase, and the largest single share of that is H2 evaluating the fact store's own derived relations over and over. This item names the mechanism, harvests the three slices whose wins were measured before it was written, and puts the one durable finding it produced somewhere that outlives the item file.

**Scope, and why it is this narrow.** The diagnostic pass that produced this item turned up ten candidate slices. Three of them have numbers: a spike that was measured and reverted, a duplicated-work span read off a timestamped log, and a parallelism experiment run entirely through command-line flags. The other seven were tempting and unmeasured, and one of them, the guardrail that keeps the recovered time from drifting back, is an architectural choice rather than a harvest. Bundling a measured harvest with an unmeasured program means the whole thing waits on the slowest decision in it, so the seven move to R733 with their bounds and their open questions intact, and this item ships the three. R733 depends on this one: two of its slices are bounded by what is left after slice 1 and slice 3 land here, which is a number nobody has yet.

That split is deliberate about what this item no longer promises. Recovering the wall clock once is not the same as holding it, and holding it is R733's spine rather than a footnote here. The reason CI tripled is that nothing measured it; this item does not fix that, and its own Done gate is not evidence that the curve cannot resume.

Two terms used throughout. A **derived relation** is a fact-store relation whose rows are computed from other relations rather than written by capture, which in the current schema means one of the 56 SQL views layered over the 140 captured tables (a `CREATE VIEW` count of `graphitron-model.sql`; do not read the view count as tracking the corpus count, which is separately 55). A **corpus sweep** is a test that loops the 55 spec-by-example fixtures and asserts something about each one, which is a recurring test shape in `graphitron` though not, as measured below, a distributed cost.

## What was measured

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

The first test captures all 55 corpus examples into one store as 55 graphs, then loops the examples again and runs its masked-claims query once per graph. That query reads `intent_column_match_claim`, which carries a `ROW_NUMBER() OVER (PARTITION BY ...)` over `intent_field_column_scope`, itself a view over `DISTINCT` and `GROUP BY` subqueries. H2 re-evaluates a joined derived relation once per outer row and does not push the `graph_name` predicate through the window, so each of the 55 queries pays for all 55 graphs' rows. Cost is quadratic in corpus size, and the corpus went from 44 to 55 examples over the same weeks CI doubled.

This is not news to the schema. `intent_column_match_claim`'s own DDL comment already states the rule: *"H2 re-evaluates a joined derived relation once per outer row, so reading the scope from underneath `graphql_field` costs the whole relation per candidate field and measured seventy times this shape on a store holding a dozen graphs. Any relation joining a derivation this deep wants the derivation first in the FROM clause."* That rule is prose in a comment with no gate behind it, and the read side has no equivalent rule at all.

The corpus-sweep shape is what multiplies it, but the multiplication is concentrated in one class rather than spread across the sweeps, and the Spec review's re-measurement is what establishes that. Twelve test classes reach the corpus: nine read `ClassifiedCorpus.examples()` directly (`ColumnMatchShadowTest`, `DemandShadowTest`, `InputOccurrenceShadowTest`, `ClassifiedDslTest`, `QueryViewRendererTest`, `DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`, `WrapperAlgebraTest`), two reach it through `ExemptionRegistry` (`VariantCoverageTest`, `ExemptionRegistryTest`), and `ClassifiedDocTest` reaches the `docExamples()` subset. Together they measured 137.5s of the module's 268.6s, but 120.4s of that is `ColumnMatchShadowTest` alone and the other eleven total 17.1s, six percent of the module. Five of the twelve call `ClassifiedHarness.classify(example.sdl())` per example, a full parse plus schema build, with no memoisation, several from more than one test method (`ClassifiedDslTest`, `DeliveryFactPinTest`, `OperationMemberMintPinTest`, `SourceShapeProjectionTest`, `WrapperAlgebraTest`), as do the two shared readers those sweeps go through, `ExemptionRegistry` and `ClassifiedCorpus.coveredLeaves`. `ColumnMatchShadowTest` is not among them: it calls `TestSchemaHelper.buildSchema` directly, which is why the repetition it pays is not the repetition a `classify` memo would remove, and why memoising `classify` is R733's slice rather than a companion to slice 1 here. Every new corpus example and every new sweep still multiplies against each other; what the measurement rules out is that the multiplication is already large anywhere but the one class.

A synthetic reproduction of the same view stack (55 graphs, 5,280 fields, 1,200 catalog columns, same window function) isolates the read pattern from everything else:

| Read pattern | Time |
|---|---|
| One query per graph, 55 graphs | 5,852 ms |
| One query for all graphs, grouped by the caller in Java | 109 ms |

Fifty-four times, from batching alone, with identical rows and identical anti-joins.

## Why this does not use materialized views

The obvious reach is `CREATE MATERIALIZED VIEW`. H2 2.4.240 does support it, with real snapshot semantics and a `REFRESH MATERIALIZED VIEW` statement, and on the synthetic stack above it takes the per-graph sweep from 5,852 ms to 4 ms. It is nonetheless ruled out here, for reasons that are defects rather than preferences. Most of those defects are shallow, and the ones that block us were traced to their cause and fixed against H2 trunk while writing this section, which is recorded below so that the ruling can be revisited if a release ever carries the fixes. What cannot be worked around from here is that no released H2 carries them, and the last release is the newest one.

**It breaks the model build outright.** While a materialized view exists anywhere in the database, any read of `INFORMATION_SCHEMA.COLUMNS` throws an internal `NullPointerException`. Minimal reproduction:

```sql
CREATE TABLE t (id INT);
CREATE MATERIALIZED VIEW m AS SELECT id FROM t;
SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS;
-- General error: "java.lang.NullPointerException: Cannot read the array length because "<local7>" is null"
```

On 2.4.240 it fails even when filtered to an unrelated table, and `DatabaseMetaData.getColumns()` fails for every call that does not name a single non-materialized table. `StoreCatalog` reads `INFORMATION_SCHEMA.COLUMNS`, and `graphitron-model` runs jOOQ codegen off live H2 metadata of exactly this schema, so one materialized view would break the model build and the generated schema reference. jOOQ's own `DSLContext.meta()` dies on precisely this query. The defect is present in 2.4.240, in 2.3.232 (August 2024) and on current H2 trunk, and 2.4.240 is the latest release, so there is no version to upgrade to.

The cause is that `MaterializedView` never populates the `columns` array it inherits from `Table`: queries against the view name are rewritten to the backing table by `Schema.resolveTableOrView`, so nothing else in the engine notices, but `InformationSchemaTable.columns` reads `table.getColumns().length` on the shell object and dereferences null. Cloning the backing table's columns into the view fixes it, verified against H2 trunk in twelve lines. Nothing is filed for it upstream. Every materialized-view issue in the H2 tracker is about something else: reconnect failing when the view carries a comment (#3897, open since 2023), column renaming (#3896), `REFRESH ... CONCURRENTLY` (#4172), and the `DROP SCHEMA` hang (#4304).

**A persistent database that contains one cannot be reopened.** `MaterializedView.getCreateSQL` always emits `CREATE FORCE MATERIALIZED VIEW`, and H2's parser answers `FORCE` on that statement with a literal `throw new UnsupportedOperationException("not yet implemented")`. Every start replays the stored DDL, so a file-backed store gains a materialized view and is then unopenable, on every version that has the feature. The fact store is file-backed and persists warm across builds under a DDL-hash path, so this defect rules materialized views out on its own for as long as it stands.

The gap is narrow rather than structural, and it is worth stating precisely, because it decides whether this is ever revisitable. The snapshot is an ordinary persisted table sitting beside the view: a `Recover` dump of such a file holds `CREATE CACHED TABLE "PUBLIC"."M$1"` with its rows in their own store map, plus a `CREATE FORCE MATERIALIZED VIEW "PUBLIC"."M"` that should bind to it. Everything a restore needs is already on disk and already replays. What is missing is that the forced statement binds to the existing backing table instead of running its query again, and that the two objects replay in the order table before view. Both were implemented and verified against H2 trunk in about 60 lines: the store then survives restart with the snapshot intact and correctly stale, and refresh, `CREATE OR REPLACE`, drop, `DROP SCHEMA CASCADE`, `SCRIPT` and `RUNSCRIPT` all round trip, including for views in a named schema, views over views, and a file written by released 2.4.240. `CREATE OR REPLACE MATERIALIZED VIEW` turns out to be broken today for the same reason and is fixed by the same change, as is the open reconnect issue #3897, where the comment clause is written before `AS` and parsed after it. None of that is filed upstream, and none of it is in a release.

**Dropping one used to corrupt the catalog.** On 2.4.240, `DROP MATERIALIZED VIEW m` leaves its backing table behind; `INFORMATION_SCHEMA.TABLES` then reports a `BASE TABLE` row whose `TABLE_NAME` is null, and re-creating the same view fails with `Table "M$1" already exists`. This one is fixed on H2 trunk by a one-line community patch from November 2025 (issue #4304), which is not in any release.

**There is no catalog to derive a refresh order from.** Refresh is manual only: `REFRESH ON COMMIT`, `REFRESH FAST` and `ALTER MATERIALIZED VIEW ... REFRESH` are all syntax errors. Refresh does not cascade, so an outer materialized view stays stale until refreshed in its own right. And H2 exposes no dependency information at all: of its 35 `INFORMATION_SCHEMA` relations, the three SQL-standard usage catalogs (`VIEW_TABLE_USAGE`, `VIEW_COLUMN_USAGE`, `VIEW_ROUTINE_USAGE`) are all absent, there is no `DEPENDENCIES` relation, and jOOQ's `Meta` and `Table` have no dependency accessor either. H2 does not even track it internally: dropping the source view of a materialized view succeeded and left the materialized view readable and reporting `STATUS=VALID`. A refresh chain over our 56 layered views would therefore be hand-maintained ordering, which collides with the principle `SchemaIdentifierDriftCheck` already states, that the universe of relations comes from the booted store and never from regexing the DDL.

**Smaller cuts.** A materialized view cannot be indexed through its own name, only through the internal `$1` backing table. Those backing tables report as `BASE TABLE`, so jOOQ codegen would generate a class per snapshot unless excluded. `VIEW_DEFINITION` is null for them, so the defining SQL is not recoverable from the catalog at all, only from `SCRIPT`. And in one test a rolled-back transaction left a refreshed snapshot in place while the base rows went away.

**And it would not even win the case that motivated it.** Batching the sweep costs one full evaluation (109 ms). Materializing costs 4 ms of reads plus 102 ms of refresh. Both pay exactly one evaluation, so for a write-then-sweep workload they are equivalent. Materialized views only pay off where a relation is read many times between writes, which is the reader-side pattern, not the test pattern, and the reader-side gains are available through the alternatives below without any of the defects above.

For the record of what the alternatives are, both verified: an ordinary table populated with `INSERT INTO derived SELECT ... FROM <view>` keeps the view as the single statement of the rule while making reads a plain indexed scan, and it is a normal table for every other purpose (indexable by name, cleanly droppable, visible to codegen on our own terms). A `LOCAL TEMPORARY` table does the same per connection and disappears with it. Note that H2's bare `CREATE TEMPORARY TABLE` defaults to `GLOBAL`, and H2's global temporary tables share their **rows** across every attached session, unlike Oracle's, so anything per-reader must say `LOCAL` explicitly; the fact store runs H2 in mixed mode with concurrent module builds and the LSP and MCP attached to one file, so a global temporary table there would be shared with all of them.

## Why deriving on read produces this shape

The read cost above is a symptom of a design choice worth stating plainly, because it is a good choice with one missing half. We derive on read: capture writes plain facts, and every classification, reduction and scope resolution is a view evaluated when someone selects from it. That is why the schema is self-documenting and why a rule lives in exactly one place. What is missing is any notion of *when* a derivation is paid for. Deriving on read is right for a relation read once per pass and wrong for one read once per graph in a loop, and slice 1 is one caller in the second category being moved into the first.

That is as far as this item takes the idea, and the boundary is worth being explicit about, because the diagnosis reaches further than the fix does. Paying a derivation once and writing the rows, whether persistently at capture time or into a `LOCAL TEMPORARY` per reader connection, is a real lever with a reader-facing payoff in the dev loop, the LSP and the MCP server, and three producer-side rules that the fact store's own DDL comments already state have nothing enforcing them. None of that is in scope here. All of it, with the reader-side and consumer-side cases and the enforcement question, moves to R733. Slice 1 batches one read; it does not establish a policy for derived reads, and it should not be reported as having done so.

## The three slices

Ordered by measured win over risk. They are independent of each other, but take them in order and re-measure between each, because they interact: slice 1 raises the ceiling slice 3 runs into. Nothing here has been implemented. Slice 1's figure comes from a spike that was measured and reverted, slice 2's from a duplicated-work span read off a timestamped log, and slice 3's from an experiment run entirely through command-line flags.

| # | Slice | Win | Confidence |
|---|---|---|---|
| 1 | Batch the per-graph sweep query | 69s | verified by spike |
| 2 | `javadoc` to `javadoc-no-fork` | 27s | measured waste, fix untested |
| 3 | Class-level test parallelism in `graphitron` | 53s now, more after 1 | measured |

Seven further slices came out of the same diagnostic pass and are R733's, with their bounds and open questions carried across: memoising `ClassifiedHarness.classify`, sharing one captured-corpus store, indexing the hot non-key join columns, sharing the Jetty server in `graphitron-mcp`, reducing a derived relation at write time, settling whether PR builds need `-Pcoverage`, and the guardrail. Two things this item deliberately rules out for both: module-level parallelism is close to exhausted, since the critical path is `javapoet` to `model` to `graphitron` to `maven-plugin` to `sakila-example`, roughly 402s of the 517s, and `-T 1C` measured 6m58s against 8m40s, a 20 percent gain against a predicted ceiling of 22 percent; and the docs render, 16.3s, already has an opt-out in `-P'!docs'`, so it is a local-loop convenience rather than a build problem.

**1. Batch the per-graph sweep query.** In `ColumnMatchShadowTest`, hoist the masked-claims query out of the example loop: select `graph_name` alongside the existing projection, run it once, group by graph in the caller, and index into that map per example. Same rows, same anti-joins, same shared store, so the sibling-scoping property the test's own docstring calls load-bearing survives. Measured 78.4s to 9.0s with assertions unchanged. Then read the other eleven sweep classes for the same shape; this one is the extreme case, and on the review's re-measurement it is very nearly the only case, so expect the read to confirm rather than to harvest.

**2. Stop the javadoc gate forking the build.** The `check-link-references` execution in the root pom binds `javadoc:javadoc`, which forks the `generate-sources` lifecycle in every module. Because the gate runs at `verify`, everything it forks has already run: 22.9s of duplicated work in `graphitron-sakila-example` (all five `graphitron:generate` executions run twice), 3.5s in `graphitron-model`, 0.5s in `graphitron-sakila-db`. Change the goal to `javadoc-no-fork`, which exists in maven-javadoc-plugin 3.12.0 (the plugin descriptor shows `javadoc` carrying `<executePhase>generate-sources</executePhase>` and `javadoc-no-fork` carrying none, so this is the goal pair the fork distinction is made of). Harvest check: the `>>> javadoc ... > generate-sources` lines disappear from the log and each `generate` execution appears once.

The attribution looks wrong until you know the interaction, so here it is rather than left to be doubted. The largest share of the waste, 22.9s, lands in `graphitron-sakila-example`, which is one of only two modules that set `maven.javadoc.skip=true` in their own pom, so the gate does not document it at all. Maven runs a mojo's `@Execute` fork *before* the mojo body evaluates its skip parameter, so that module pays for the whole forked `generate-sources` lifecycle and then skips the goal the fork was for. Removing the fork is therefore worth more in the modules that opt out of the gate than in the ones it actually checks.

Two facts about this execution the implementer should not have to rediscover. R730 is Ready and adds a *second* `maven-javadoc-plugin` execution beside `check-link-references`, running the `test-javadoc` goal at `verify`; that goal forks `generate-test-sources` exactly as `javadoc` forks `generate-sources`, so whichever of the two items lands second has to cover both executions or R730 reintroduces the waste this slice harvests. `test-javadoc-no-fork` exists in 3.12.0 for that. And R568 records that this execution's up-to-date check keys on the option strings plus the source file *list*, not on source content, so the obvious way to prove the gate still bites (add a dangling `{@link}` to a file that already exists and confirm the build fails) is precisely the case R568 says gets skipped. Prove non-vacuity on a clean `target/`, or by adding a new file, and say which.

**3. Turn on class-level test parallelism.** Nothing in the reactor sets `forkCount`, `parallel`, or any `junit.jupiter.execution.parallel.*` property, so every module runs one sequential fork. Running `graphitron` with classes concurrent at 4 threads took Surefire from 170.5s to 117.1s, with one failure out of 3,637 tests: `DeliveryFactPinTest` died with `java.io.IOException: Stream closed` inside `ClassificationTrace.write`, because the classifier trace is a process-global writer that one test closes while other threads are still building schemas through it.

**That failure is a race, and a green run is not evidence against it.** A second review pass ran the same recipe and got 3,653 tests with zero failures. The window is narrow and real: `ClassificationTrace.emit` reads the writer through `getOrInitWriter()` and then calls `write(w, line)`, while `ClassificationTraceTest.disableTracing`, an `@AfterEach`, calls `resetForTesting(null)` and closes that same writer. A thread that captured `w` before the close and writes after it gets `Stream closed`, and whether any thread lands in the window depends on scheduling. So the shared-state fix below is required by the shape of the code, not by having observed the crash; do not read a green trial run as permission to skip it, because what ships then is a suite that fails for someone else.

Fix that one piece of shared state, then set the parallel properties in `graphitron`'s `junit-platform.properties` with classes concurrent and methods `same_thread` (the file exists, carrying only extension autodetection today). Three facts narrow the fix. `ClassificationTrace.resetForTesting` is what closes and rebinds the writer, and it has two callers, `ClassificationTraceTest` and `SingleWalkClassificationOrderTest`, so an `@Isolated` fix marks both; nothing in the tree uses `@Isolated` yet, so this is the first. `ClassificationTrace.write` is already `synchronized`, so interleaved lines are not the hazard and the close/rebind is. And the leaf-coverage JSONL's *well-formedness* is not a second thing to check: the `leaf-coverage` profile points `-Dgraphitron.classification.trace` at `${project.build.directory}/leaf-coverage.jsonl`, so `ClassificationTrace` *is* the JSONL writer, and no line can interleave with another through a `synchronized` write.

Its **composition** is a second thing, and this slice will change it. `resetForTesting(null)` sets `writerInitialised = true` while leaving `writer = null`, which its own javadoc describes as disabling tracing "for the rest of the JVM", so in a reused Surefire fork every class scheduled after that teardown emits nothing at all. Reordering the suite reorders which classes those are: the review pass measured 17,476 records sequentially against 16,933 at 4-way parallelism, with records both vanishing (`DeliveryFactPinTest` 1,187 to 482) and appearing for classes that emit nothing sequentially (`SchemaReachabilityTest` 0 to 116). The bug is pre-existing and is not this slice's to fix, and `@Isolated` stops the concurrent misdirection without restoring completeness. It is filed as R736. What this slice owes is the warning: it shifts the input to `LeafCoverageReport`, so the next person to regenerate `roadmap/inference-axis-coverage.adoc` reads a diff that is reordering rather than coverage drift. R736 and this slice want a known landing order, and R736 first is the cheaper one, since it makes this slice's harvest check meaningful instead of merely different.

The one genuinely separate thing to check while harvesting: the ceiling is the slowest single class, so slice 1 comes first or this buys much less than it should. This slice is `graphitron` only, which is where the number was taken; extending the same properties to `graphitron-lsp`, `graphitron-mcp` and `graphitron-model` is unmeasured and belongs to R733, one module at a time.

## Fourth deliverable: the H2 ruling gets a permanent home

The three slices are harvests. This fourth deliverable is not, and it is a condition of the Done gate rather than an optional tidy, because without it the most durable thing this item produced is destroyed by its own success: item files are deleted at Done, and the materialized-view ruling lives only in this file. What would go with it is four H2 defects traced to cause with minimal reproductions, three fixes implemented and verified against H2 trunk, the finding that none of them is filed upstream, and the ruling about what a write-time reduction uses instead. R733's write-time-reduction slice is the direct consumer of that ruling, so binning it means the next item re-derives it or, worse, reaches for `CREATE MATERIALIZED VIEW` and rediscovers the metadata defect the hard way.

**The destination is `docs/architecture/explanation/fact-model.adoc`.** Not a new note: that page already owns this discipline and already states half of this rule. Its "Derived reads are views, not stored facts" section carries the derivation-first-in-`FROM` rule with the MCP schema-read measurement behind it, and its stratum section already says materialization "is sanctioned above where a view cannot serve". What it does not say is what a materialization may be built out of on H2, which is exactly the gap this item filled. Putting the ruling anywhere else splits one rule across two pages.

What travels: why `CREATE MATERIALIZED VIEW` is unavailable, at the altitude of the defect rather than the reproduction (the metadata read that breaks the model build, the drop that corrupts the catalog, the absent dependency catalog, the file-backed store that cannot reopen); the upstream state, so a future reader knows the ruling is conditional on no release carrying the fixes rather than on the feature being wrong; and the write-time reduction ruling, that a reduction is an ordinary table populated `INSERT INTO derived SELECT ... FROM <view>` or a `LOCAL TEMPORARY` one per connection, with the H2-specific trap that a bare `CREATE TEMPORARY TABLE` defaults to `GLOBAL` and H2's global temporary tables share their rows across every attached session, which for a store the LSP, the MCP server and concurrent module builds all attach to is the wrong default to inherit silently. The `graphitron-model.sql` header gets a one-line pointer to the page, since that is where a schema author meets the rule.

What must not travel: the transient measurements. The per-module tables, the twelve-class corpus census and the per-class second counts are all true today and all rot, and the census in particular is the hand-maintained caller census that this project's own documentation axiom names as a smell. They earn their place in an item body that gets deleted; they would be a liability on a permanent page. If a number is needed there, it is the one measurement that already survives on that page, made against a named relation.

## Sequencing against the in-flight items

Three items hold code these slices touch. None of them earns a `depends-on` entry, and the reasoning is recorded here rather than left to be rediscovered, because an implementer reading an empty `depends-on:` will not go looking.

* **R680 has landed.** It is In Review with all eight slices shipped, and it was actively restructuring the fact-store test harnesses while this item was drafted. What matters for slice 1 is that the restructure settled without moving the API the slice uses: `CapturedStore` is now `public final` in `no.sikt.graphitron.rewrite` with its factory set intact, and `ColumnMatchShadowTest` still reaches the store through `CapturedStore.ofCatalog` and `andCatalogGraph`. So slice 1 works against a settled fixture rather than a moving one, and nothing here waits on R680 reaching Done. R733 carries the two slices whose subjects were more deeply inside R680's territory.
* **R730 is Ready and collides with slice 2 in the editor**, not in the design: both edit `maven-javadoc-plugin` executions in the root pom, and slice 2 already states what whoever lands second must do. That is a merge concern with a clear resolution, which is the category that does not want a dependency edge, since declaring one would park a measured 27s harvest behind an unrelated item's implementation.
* **R568 is Backlog and bears on slice 2's proof rather than its change.** Slice 2 states the workaround. If R568 ships first, slice 2's non-vacuity proof gets simpler, and nothing about slice 2 has to change either way.
* **R736 is Backlog and wants to land before slice 3.** It fixes the trace writer going silent for the rest of a fork, which is what makes slice 3's effect on the leaf-coverage record count legible. This is a preference with a reason rather than a blocker: slice 3 is correct either way, and taking it first only means the first regenerated coverage report after it carries a reordering diff nobody can distinguish from drift. If slice 3 goes first anyway, say so in its commit message so the next regeneration is not misread.

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

The three slices buy back time once. They do not hold it, and the Done gate here is not evidence that
the curve cannot resume: the guardrail that would make it evidence is R733's, along with the argument
for which shape it should take and the mechanics its host has to answer. Anyone reading a green build
after this item ships should read it as one recovery, not as a floor.

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
