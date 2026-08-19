---
id: R732
title: "Recover the build wall clock and hold it, starting from what derived reads cost"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-19
last-updated: 2026-08-19
---

# Recover the build wall clock and hold it, starting from what derived reads cost

Trunk CI has gone from a 5 minute median to a 15 minute median in seven weeks, and the curve is steeper than the suite is growing: between 8 and 19 August the number of test methods rose 21 percent while CI wall clock rose 53 percent. Cost outrunning volume means the shape is wrong somewhere, not that we have simply written more tests. Two thirds of a full build is now the test phase, and the largest single share of that is H2 evaluating the fact store's own derived relations over and over. This item is the holistic pass: name the mechanism, harvest the slices in order of measured win over risk, and leave a guardrail so the curve cannot quietly resume.

Two terms used throughout. A **derived relation** is a fact-store relation whose rows are computed from other relations rather than written by capture, which in the current schema means one of the 55 SQL views layered over the 140 captured tables. A **corpus sweep** is a test that loops the 55 spec-by-example fixtures and asserts something about each one, which is the dominant test shape in `graphitron`.

## What was measured

Everything below came from one session's measurements on a 4 vCPU sandbox with a warm local repository, JDK 25 and Maven 3.9.11. The re-measurement recipes are at the end so any number here can be checked or refuted.

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

`ColumnMatchShadowTest` is the extreme case and the clearest specimen: three test methods, 74.0 seconds, 14 percent of all test time in the reactor. A JDK Flight Recorder profile of it (7,058 execution samples) puts **96 percent of samples inside `org.h2` query execution**, with garbage collection at 0.6s total across 188 pauses and store boot at roughly 12ms per store. It is neither the harness nor allocation. It is the SELECTs.

The first test captures all 55 corpus examples into one store as 55 graphs, then loops the examples again and runs its masked-claims query once per graph. That query reads `intent_column_match_claim`, which carries a `ROW_NUMBER() OVER (PARTITION BY ...)` over `intent_field_column_scope`, itself a view over `DISTINCT` and `GROUP BY` subqueries. H2 re-evaluates a joined derived relation once per outer row and does not push the `graph_name` predicate through the window, so each of the 55 queries pays for all 55 graphs' rows. Cost is quadratic in corpus size, and the corpus went from 44 to 55 examples over the same weeks CI doubled.

This is not news to the schema. `intent_column_match_claim`'s own DDL comment already states the rule: *"H2 re-evaluates a joined derived relation once per outer row, so reading the scope from underneath `graphql_field` costs the whole relation per candidate field and measured seventy times this shape on a store holding a dozen graphs. Any relation joining a derivation this deep wants the derivation first in the FROM clause."* That rule is prose in a comment with no gate behind it, and the read side has no equivalent rule at all.

The corpus-sweep shape is what multiplies it. Fourteen classes sweep the corpus and together account for 96.1s of `graphitron`'s 167s, 58 percent. Eleven of them call `ClassifiedHarness.classify(example.sdl())` per example, a full parse plus schema build, with no memoisation, several of them from more than one test method. Every new corpus example and every new sweep multiplies against each other.

A synthetic reproduction of the same view stack (55 graphs, 5,280 fields, 1,200 catalog columns, same window function) isolates the read pattern from everything else:

| Read pattern | Time |
|---|---|
| One query per graph, 55 graphs | 5,852 ms |
| One query for all graphs, grouped by the caller in Java | 109 ms |

Fifty-four times, from batching alone, with identical rows and identical anti-joins.

## Why this does not use materialized views

The obvious reach is `CREATE MATERIALIZED VIEW`. H2 2.4.240 does support it, with real snapshot semantics and a `REFRESH MATERIALIZED VIEW` statement, and on the synthetic stack above it takes the per-graph sweep from 5,852 ms to 4 ms. It is nonetheless ruled out here, for reasons that are defects rather than preferences.

**It breaks the model build outright.** While a materialized view exists anywhere in the database, any read of `INFORMATION_SCHEMA.COLUMNS` throws an internal `NullPointerException`. Minimal reproduction:

```sql
CREATE TABLE t (id INT);
CREATE MATERIALIZED VIEW m AS SELECT id FROM t;
SELECT COUNT(*) FROM INFORMATION_SCHEMA.COLUMNS;
-- General error: "java.lang.NullPointerException: Cannot read the array length because "<local7>" is null"
```

It fails even when filtered to an unrelated table, and `DatabaseMetaData.getColumns()` fails with it. `StoreCatalog` reads `INFORMATION_SCHEMA.COLUMNS`, and `graphitron-model` runs jOOQ codegen off live H2 metadata of exactly this schema, so one materialized view would break the model build and the generated schema reference. jOOQ's own `DSLContext.meta()` dies on precisely this query. The defect is present in both 2.4.240 and 2.3.232 (August 2024), and 2.4.240 is the latest release, so there is no version to upgrade to.

**Dropping one corrupts the catalog.** `DROP MATERIALIZED VIEW m` leaves its backing table behind; `INFORMATION_SCHEMA.TABLES` then reports a `BASE TABLE` row whose `TABLE_NAME` is null, and re-creating the same view fails with `Table "M$1" already exists`. The fact store persists warm across builds under a DDL-hash path, so a corrupting drop is exactly the wrong failure mode.

**There is no catalog to derive a refresh order from.** Refresh is manual only: `REFRESH ON COMMIT`, `REFRESH FAST` and `ALTER MATERIALIZED VIEW ... REFRESH` are all syntax errors. Refresh does not cascade, so an outer materialized view stays stale until refreshed in its own right. And H2 exposes no dependency information at all: of its 35 `INFORMATION_SCHEMA` relations, the three SQL-standard usage catalogs (`VIEW_TABLE_USAGE`, `VIEW_COLUMN_USAGE`, `VIEW_ROUTINE_USAGE`) are all absent, there is no `DEPENDENCIES` relation, and jOOQ's `Meta` and `Table` have no dependency accessor either. H2 does not even track it internally: dropping the source view of a materialized view succeeded and left the materialized view readable and reporting `STATUS=VALID`. A refresh chain over our 55 layered views would therefore be hand-maintained ordering, which collides with the principle `SchemaIdentifierDriftCheck` already states, that the universe of relations comes from the booted store and never from regexing the DDL.

**Smaller cuts.** A materialized view cannot be indexed through its own name, only through the internal `$1` backing table. Those backing tables report as `BASE TABLE`, so jOOQ codegen would generate a class per snapshot unless excluded. `VIEW_DEFINITION` is null for them, so the defining SQL is not recoverable from the catalog at all, only from `SCRIPT`. And in one test a rolled-back transaction left a refreshed snapshot in place while the base rows went away.

**And it would not even win the case that motivated it.** Batching the sweep costs one full evaluation (109 ms). Materializing costs 4 ms of reads plus 102 ms of refresh. Both pay exactly one evaluation, so for a write-then-sweep workload they are equivalent. Materialized views only pay off where a relation is read many times between writes, which is the reader-side pattern, not the test pattern, and the reader-side gains are available through the alternatives below without any of the defects above.

For the record of what the alternatives are, both verified: an ordinary table populated with `INSERT INTO derived SELECT ... FROM <view>` keeps the view as the single statement of the rule while making reads a plain indexed scan, and it is a normal table for every other purpose (indexable by name, cleanly droppable, visible to codegen on our own terms). A `LOCAL TEMPORARY` table does the same per connection and disappears with it. Note that H2's bare `CREATE TEMPORARY TABLE` defaults to `GLOBAL`, and H2's global temporary tables share their **rows** across every attached session, unlike Oracle's, so anything per-reader must say `LOCAL` explicitly; the fact store runs H2 in mixed mode with concurrent module builds and the LSP and MCP attached to one file, so a global temporary table there would be shared with all of them.

## Derived data, from both sides

The read cost above is a symptom of a design choice worth stating plainly, because it is a good choice with one missing half. We derive on read: capture writes plain facts, and every classification, reduction and scope resolution is a view evaluated when someone selects from it. That is why the schema is self-documenting and why a rule lives in exactly one place. What is missing is any notion of *when* a derivation is paid for.

**Producer side, meaning graphitron computing its own derived facts.** Deriving on read is right for a relation read once per pass and wrong for one read once per graph in a loop. The lever is not to abandon views but to add a reduction step that pays a derivation once and writes the rows, populated *from* the view so the view remains the only statement of the rule. Two shapes are available and both are ordinary tables, indexable and droppable: a persistent reduction written at capture time for relations every reader wants, and a `LOCAL TEMPORARY` reduction built per connection for a long-lived reader that will query the same relation repeatedly. The ordering problem that sinks materialized views does not arise the same way here, because we would be writing the population statements ourselves in the DDL we control, in the order we write them, rather than asking H2 for a dependency graph it does not have.

Three producer-side rules deserve to become enforced rather than remain prose:

- Batch by key set, never loop by key. A caller that needs a derived relation for N partitions issues one query and groups in the caller. This is the same discipline as the DataLoader batching the generated code already does, applied to our own reads.
- Put the derivation first in the FROM clause, which is the rule `intent_column_match_claim`'s comment already states and which nothing checks.
- A derived relation that will be read per partition is a candidate for reduction, not a candidate for a cleverer query.

**Consumer side, meaning two different consumers.** The first is the readers of the fact store: the dev loop, the LSP and the MCP server. They read the claim views repeatedly against a store that changes rarely between reads, which is exactly the profile where a reduction pays, and where a per-connection `LOCAL TEMPORARY` reduction is cheapest to reason about because it cannot outlive the reader or be seen by a concurrent build. Their latency is a user-facing number, so this is not only a build concern.

The second is the consumers of generated code, querying their own database. The pathology is the same shape one level out: a resolver that reads a derived relation once per parent row is the per-outer-row cost again, and PostgreSQL's planner is much stronger than H2's but the N+1 shape does not care. Graphitron already defends this with DataLoader batching, and the execution tier already has the instruments to prove it, the `QUERY_COUNT` listener for round-trip counts and the `SQL_LOG` listener for SQL shape. The consumer-facing work in this item is therefore not new machinery but coverage: assert round-trip counts on the paths that read derived relations, so a generator change that turns a batched read into a per-row read fails here rather than in a consumer's production database. Worth noting for anyone extending this to consumer schemas: PostgreSQL does have what H2 lacks, `pg_matviews` naming materialized views with `ispopulated` and `definition`, and `pg_depend` joined to `pg_rewrite` yielding the dependency graph uniformly for views and materialized views. A consumer-side story could use them. The fact store cannot.

## Tempting slices, in the order to take them

Ordered by measured win over risk. The first three are independent of each other and of everything below; take them in order and re-measure between each, because they interact (slice 1 raises the ceiling slice 3 runs into). Nothing here has been implemented: slice 1's figure comes from a spike that was measured and reverted, slice 3's from an experiment run entirely through command-line flags.

| # | Slice | Win | Confidence |
|---|---|---|---|
| 1 | Batch the per-graph sweep query | 69s | verified by spike |
| 2 | `javadoc` to `javadoc-no-fork` | 27s | measured waste, fix untested |
| 3 | Class-level test parallelism in `graphitron` | 53s now, more after 1 | measured |
| 4 | Memoise `ClassifiedHarness.classify` | unmeasured, bounded by 96.1s | high |
| 5 | Share one captured-corpus store | unmeasured | medium |
| 6 | Index the hot non-key join columns | unmeasured | measure first |
| 7 | Reduce a derived relation at write time | unmeasured, reader-facing | architectural |
| 8 | One Jetty server per class in `graphitron-mcp` | up to 15.5s | high |
| 9 | Decide whether PR builds need `-Pcoverage` | unmeasured | measure first |
| 10 | Guardrail so the curve cannot resume | none, protective | required to close |

**1. Batch the per-graph sweep query.** In `ColumnMatchShadowTest`, hoist the masked-claims query out of the example loop: select `graph_name` alongside the existing projection, run it once, group by graph in the caller, and index into that map per example. Same rows, same anti-joins, same shared store, so the sibling-scoping property the test's own docstring calls load-bearing survives. Measured 78.4s to 9.0s with assertions unchanged. Then read the other 13 sweep classes for the same shape; this one is the extreme case, not the only case.

**2. Stop the javadoc gate forking the build.** The `check-link-references` execution in the root pom binds `javadoc:javadoc`, which forks the `generate-sources` lifecycle in every module. Because the gate runs at `verify`, everything it forks has already run: 22.9s of duplicated work in `graphitron-sakila-example` (all five `graphitron:generate` executions run twice), 3.5s in `graphitron-model`, 0.5s in `graphitron-sakila-db`. Change the goal to `javadoc-no-fork`, which exists in maven-javadoc-plugin 3.12.0. Harvest check: the `>>> javadoc ... > generate-sources` lines disappear from the log and each `generate` execution appears once. Prove the gate still bites by pointing a `{@link}` at a symbol that does not exist and confirming the build fails.

**3. Turn on class-level test parallelism.** Nothing in the reactor sets `forkCount`, `parallel`, or any `junit.jupiter.execution.parallel.*` property, so every module runs one sequential fork. Running `graphitron` with classes concurrent at 4 threads took Surefire from 170.5s to 117.1s with exactly one failure out of 3,637 tests: `DeliveryFactPinTest` died with `java.io.IOException: Stream closed` inside `ClassificationTrace.write`, because the classifier trace is a process-global writer that one test closes while other threads are still building schemas through it. Fix that one piece of shared state, by scoping the trace to its run or marking its owning test `@Isolated`, then set the parallel properties in `graphitron`'s `junit-platform.properties` with classes concurrent and methods `same_thread`. Two things to check while harvesting: the leaf-coverage JSONL is appended from test forks and must stay well-formed under concurrency, and the ceiling is the slowest single class, so slice 1 comes first or this buys much less than it should. Extend to `graphitron-lsp`, `graphitron-mcp` and `graphitron-model` afterwards, one module at a time.

**4. Memoise the corpus classification.** Eleven sweep classes call `ClassifiedHarness.classify(example.sdl())` per example with no memoisation, several from more than one test method. The precedent is in the same class: `launcherProductions()` is memoised once per JVM and its javadoc says why. `classify` returns an immutable `Result`, so a static map keyed on the fixture SDL is the whole change. Harvest check: total corpus classification passes per build drop from hundreds to 55.

**5. Share one captured-corpus store.** The capture-side sweeps each build their own store over the whole corpus. One shared fixture, captured once per JVM, removes the repetition that slice 4 removes on the classification side. Larger than slice 4 because store lifetime becomes shared state across classes, so sequence it after slice 3 when the parallelism model is settled and the isolation requirements are known.

**6. Index the hot non-key join columns.** The DDL declares 140 tables, 55 views, 161 primary keys, 253 foreign keys and **zero** `CREATE INDEX`. The hot join inside `intent_column_match_claim` matches `sql_column` on `jooq_name_upper` OR `column_name_upper`, neither of which leads a key, so it scans the catalog per candidate field; `Value.compareToNotNullable` was 27 percent of the JFR samples. Measure first and keep the result either way: H2 may decline to use an index under an OR, in which case the finding is that the predicate wants restructuring rather than an index.

**7. Reduce one derived relation at write time.** The producer-side change described above, on one relation, with a before and after number. Deliberately last among the code slices: slices 1 and 4 may remove the test-path motivation entirely, and the honest justification for this one is reader latency in the dev loop, the LSP and the MCP server rather than build time. Populate from the view so the rule stays in one place, and write the population order explicitly rather than deriving it, since H2 offers no dependency catalog to derive it from.

**8. Share the Jetty server in `graphitron-mcp`.** `GraphitronMcpServerTest` boots a server per test method: 60 tests, 15.5s, 0.26s per test, the highest per-test cost in the reactor. A few of those tests have the server's lifecycle as their subject and must keep their own; the rest can share one per class.

**9. Decide whether PR builds need `-Pcoverage`.** CI attaches the JaCoCo agent to every run including PRs. The stated reason, keeping the wiring continuously exercised so an `argLine` regression fails on the PR that introduced it, is sound and should not be discarded casually. But the cost has never been measured. One A/B run settles it; if it is material, exercising the wiring on trunk pushes only is a defensible trade.

Two things deliberately **not** on this list. Module-level parallelism is close to exhausted: the critical path is `javapoet` to `model` to `graphitron` to `maven-plugin` to `sakila-example`, roughly 402s of the 517s, and `-T 1C` measured 6m58s against 8m40s, a 20 percent gain against a predicted ceiling of 22 percent. And the docs render, 16.3s, already has an opt-out in `-P'!docs'`; it is a local-loop convenience, not a build problem.

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

**Growth correlation.** Count test methods and corpus examples at dated commits, so a wall-clock jump can be checked against whether the suite actually grew:

```bash
git grep -c -E '^\s*@(Test|ParameterizedTest)' <rev> -- '*/src/test/**/*.java' | awk -F: '{s+=$NF} END{print s}'
git show <rev>:graphitron/src/test/java/no/sikt/graphitron/rewrite/classifieddsl/ClassifiedCorpus.java | grep -c 'new Example('
```

## Holding the gain

The slices above buy back time once. The reason CI tripled is that nothing measured it, so the item is not closable on the slices alone: it needs one guardrail that fails or flags when the shape regresses.

The cheapest honest version reuses machinery that already exists. Surefire writes `target/surefire-reports/*.txt` with a `Time elapsed` per class on every build, and `roadmap-tool` already reads per-module build artifacts through a `**/target/*.jsonl` glob for the leaf-coverage report. A sibling step could read the Surefire reports and fail on a per-class ceiling, which is the shape that catches this specific pathology: the failure mode here was never a thousand slow tests, it was one class at 74 seconds inside a suite whose median class costs a fraction of a second. A per-class budget would have caught it on the commit that introduced it.

Decide at Spec time between that and the alternatives (a total-suite budget, which is noisier and drifts with hardware; or recording the trend in CI without gating, which documents the curve but does not stop it). Whichever is chosen, the budget belongs in the repository next to the tests it governs, and raising it should be a visible commit rather than a silent drift.
