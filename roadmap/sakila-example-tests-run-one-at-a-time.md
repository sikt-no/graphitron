---
id: R763
title: "Two test defects hold graphitron-sakila-example to one thread, and it is 23s of the critical path"
status: In Progress
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-20
last-updated: 2026-08-21
---

# Two test defects hold graphitron-sakila-example to one thread, and it is 23s of the critical path

When this lands, a contributor's `mvn install -Plocal-db` finishes about **23 seconds** sooner and
`graphitron-sakila-example` stops being the one module in the reactor that runs 805 tests one at a
time while three of four cores sit idle. Getting there means fixing two defects that exist today and
are merely unobserved, because nothing currently runs that module's classes concurrently: one test
class asserts a property of the whole database rather than of its own query, and one uses a Quarkus
deployment it does not need.

Everything below restates the facts it needs rather than pointing at other items, because those
bodies are deleted when they reach Done.

## Why this module and not the reactor's shape

`mvnd` ends every build naming "bottleneck projects that decrease concurrency" and pointing at
`-Dsmartbuilder.profiling=true`, which prints a critical path and a list of modules that ran alone.
That reads as an invitation to break module dependencies, and the invitation is worth **one second**:
on one 4 vCPU sandbox at trunk `660aba6`, `mvnd install -Plocal-db` takes **340 s** and the same build
at `-T1` takes **339 s**, while service time rises 39% from 339 s to 470 s. Five of the six
critical-path modules already run their own test classes on four threads, so a second module beside
them takes cores rather than adding them. `graphitron-docs` went from 15.6 s to 45.6 s to save
`graphitron` nothing.

Two modules are the exception, and they are where the idle cores are. Measured as summed surefire
class spans over module wall clock, where above 1 means classes overlapped:

| Module | Class-time sum | Wall clock | Overlap |
|---|---|---|---|
| `graphitron-lsp` | 231.4 s | 35.6 s | 6.5x |
| `graphitron` | 380.2 s | 78 s | 4.9x |
| `graphitron-model` | 182.3 s | 46.8 s | 3.9x |
| `graphitron-mcp` | 82.1 s | 26.9 s | 3.1x |
| `graphitron-maven-plugin` | 15.5 s | 32.7 s | **0.5x** |
| `graphitron-sakila-example` | 38.8 s | 88 s | **0.4x** |

This item takes the second of those. R767 takes the first. R766 takes the other half of this module,
its five sequential `graphitron:generate` executions, which is a plugin API change rather than a test
fix and does not belong in the same cycle.

`graphitron-sakila-example` depends on everything and nothing depends on it, so it is the reactor's
terminal node and can never overlap another module. Whatever it spends is wall clock. Per-goal
timings, module total 93.6 s:

| Goal | Time |
|---|---|
| five `graphitron:generate` executions | 33.1 s (R766) |
| `compiler:compile` plus `testCompile` | 9.3 s |
| **`surefire:test`, 805 tests, one at a time** | **44.8 s** |
| `jar`, `quarkus:build` and the rest | 6.4 s |

## The experiment that sizes it, and the two defects it found

Dropping a four-thread `junit-platform.properties` into the module cuts it from **92.5 s to 69.2 s**
and fails **36 of 805 tests**. Two causes, and they want different fixes.

### `@QuarkusTest` keeps per-test bookkeeping in static single slots

`io.quarkus.test.junit.QuarkusTestExtension` declares, in the 3.34.5 jar this reactor builds against:

```
private static java.lang.Class<?> actualTestClass;
private static java.lang.Object   actualTestInstance;
private static final Deque<Object> outerInstances;
private static java.lang.Class<?> quarkusTestMethodContextClass;
```

One slot each, per JVM, non-volatile and unguarded. `interceptTestClassConstructor` writes them
through `initTestState` when a class starts, and every later callback reflects the currently running
JUnit method onto whatever `actualTestInstance` holds. Two classes in flight means the second
construction overwrites the first, and the first class's callbacks then look up their own method name
on the wrong class and invoke it against the wrong receiver:

```
RuntimeException: Could not find method void TutorialSmokeTest.page3_activeFilter() on test class
  at QuarkusTestExtension.createQuarkusTestMethodContextTuple(QuarkusTestExtension.java:564)
IllegalArgumentException: object of type GraphQLOverHttpConformanceTest
                          is not an instance of TutorialSmokeTest
  at QuarkusTestExtension.interceptAfterEachMethod(QuarkusTestExtension.java:913)
```

Sharing the running application across classes is deliberate and works; the augmentation cost is why
it exists. It is the bookkeeping beside it that is not thread-safe.

**Upstream will not rescue this, and it is worth having read the record before planning around it.**
Parallel `@QuarkusTest` execution has never been supported and is tracked as an open *enhancement*,
`quarkusio/quarkus#42296`, opened August 2024, labelled `area/testing` and `kind/enhancement`. The
maintainer answer on the community thread, discussion `#29218` from November 2022, is "Currently, it
will not work properly", and a committer's 2025 note there frames the goal as restoring an earlier
level of accidental success: "it's not supported, and it won't work in all cases, but it will
sometimes work". Nothing in the testing guide warns about it; its only occurrence of "parallel" is
`@QuarkusTestResource(parallel = true)`, which starts test resources and is unrelated. Two further
details matter for reading our own failure. The symptom most reporters hit is a *different* race,
`SRCFG00017: Configuration already registered for the given class loader` out of SmallRye Config,
which `#24524` shows firing in a module of plain unit tests with no `@QuarkusTest` at all, merely
because `quarkus-junit5` was on the classpath. And our exact symptom has a closed precedent reached
without threads: `#25812` is the same `IllegalArgumentException` from the same `runExtensionMethod`,
triggered by `@Nested` plus a per-class instance lifecycle, fixed by patching that path while the
shared slot stayed. So at least two independent races live in this extension, and hitting one says
nothing about the other. The documented workaround is process-level, surefire `forkCount` with
`reuseForks=false`, which trades one shared Quarkus boot for N of them on a box this item has just
measured as saturated: wrong direction here.

### Four of the five Quarkus classes need the container, and one does not

Running GraphQL operations against a generated schema needs no Quarkus, and 46 of the module's 47
`querydb` classes already prove it: `Graphitron.newGraphQL().build()` plus
`Graphitron.newExecutionInput(dsl, "{}", "test-user")`, straight to GraphQL-Java over a jOOQ
`DSLContext`. `GraphQLQueryTest` runs 363 such cases in 7 seconds.

Four classes have the container as their subject and say so in their own javadoc:
`GraphQLOverHttpConformanceTest` cites normative GraphQL-over-HTTP sentences,
`GraphqlResourceSmokeTest` checks the endpoint plus the GraphiQL page and its asset route,
`MountedEndpointTest` pins an operation-policy decision "reaching the wire with the right status and
shape", and `OverlappingMountTest` pins Jakarta REST's root-resource matching across two `@Path`
classes. They are HTTP tests. Together they are **5.8 s** of test time (4.714, 0.544, 0.489, 0.040).

`TutorialSmokeTest` is the exception. Every assertion is on GraphQL response shape; the only
HTTP-shaped ones are `statusCode(200)` and `errors == null`, both in its private `post` helper and
incidental to all six cases. Its `@AfterEach` also runs `DELETE FROM film WHERE film_id > 5`, the only
unscoped destructive cleanup in the module that lands on a table other classes use: every `querydb`
writer to `film` deletes by UUID marker, title or a specific id. The one other unscoped delete,
`RoutineCarrierRlsExecutionTest`'s three `delete from secure_note` calls, is safe because that class
is the only one in the module that touches `secure_note`. Under concurrency `TutorialSmokeTest`
silently deletes other classes' fixture rows.

### `GraphQLQueryTest` asserts a property of the whole database

Seven cases, one defect. Each is an unfiltered `films` query asserting an absolute count against the
five rows `init.sql` seeds at ids 1 to 5:

```
[omitting the nullable filter must return the unfiltered baseline of 5 films, ...]
Expected size: 5 but was: 7
```

and the extra rows name their author:

```
{"title"="ACADEMY DINOSAUR"}, ... {"title"="R144-MULTIROW-A-8a56b78d-94d2-44a0-99b1-529c218d2b2d"}
```

`DmlBulkMutationsExecutionTest` inserts that row, and it is scrupulous: UUID-suffixed markers, a
`finally` per case, an `@AfterAll` sweep. The defect is on the reading side. "The query returned five
films" and "the film table holds only the seed" are different claims, and these seven make the second
while meaning the first. 11 of the 47 `querydb` classes write to `film`.

One scoping note that decides how to fix it. Each `querydb` class opens its own `DSLContext` in
`@BeforeAll`, either against `test.db.url` when the `local-db` profile sets it or against a
per-class `PostgreSQLContainer` otherwise. On the container path every class has its own database and
the count assertions are safe; on the `local-db` path, which is what CI and the sandbox use, all 47
share one. So the fix has to hold on the shared-database path, and that is the path to verify on.

## Implementation

Four changes, in this order, because each one's verification depends on the previous. No numbered
phases: they land together or the suite is red.

**`TutorialSmokeTest` moves to the direct harness.** Drop `@QuarkusTest`,
`@QuarkusTestResource(SmokeTestPostgresResource.class)`, the `@Inject AgroalDataSource` and the REST
Assured `post` helper. Adopt the `querydb` `@BeforeAll` shape: resolve `test.db.url` or start a
container, build one `DSLContext`, build one `GraphQL` through `Graphitron.newGraphQL()`. Replace
`post(String)` with an `execute(String)` returning `result.getData()`, and rewrite the six cases'
`assertThat(body).contains("\"firstName\":\"Mary\"")` string matching as map assertions in the
`querydb` idiom. Replace the `@AfterEach` global delete: `page5_createAndUpdateFilm` is the only case
that writes, so it deletes its own `filmId` in a `finally`, which is what every `querydb` writer does.
Move the class to `no.sikt.graphitron.rewrite.test.querydb`, which is what the package names and where
its siblings are; the `@ExecutionTier` annotation stays. Its javadoc keeps the tutorial-drift purpose
and loses the "an endpoint moves" clause, which `GraphqlResourceSmokeTest` holds.

**The four remaining `@QuarkusTest` classes take a shared `@ResourceLock`.** One key, `READ_WRITE`
mode, at class level, so the four are mutually exclusive and free to overlap the other 67.
`@Execution(SAME_THREAD)` is the wrong tool and the plan names it so nobody reaches for it: it pins a
class to its parent's thread and says nothing about which other classes run beside it. The key's
constant and the reason live in one place both the annotation sites and the meta-test below can
reference.

**The seven `GraphQLQueryTest` cases stop counting the whole table.** The named cases are
`allContentConnection_forwardWalk_pagesThroughEveryRowInPkOrder`,
`films_isEnglish_resolvesViaExternalFieldExpression`,
`filmsFaceted_nullableFacet_preservesTheNullBucket`,
`filmsByEffectiveNullability_omittedFilter_returnsUnfilteredBaseline`,
`implicitInputCondition_nullField_omitsPredicate_returnsAll`,
`filmsConnectionDesc_executesDescendingPrimaryKeyOrder` and
`films_titleTitlecase_resolvesViaServiceRecordFieldDataLoader_tableRecordSource`. Each states what it
means rather than what the table holds: assert the five seeded titles are present and in the expected
order or bucket, not that the result has size five. Where a case is genuinely about a count
(`totalCount` agreeing with a walk's length), the query gains a filter that bounds it to the seed so
the count is about the query. The seven are the observed set, not necessarily the whole set: the
implementation greps the class for size and count assertions on unfiltered root fields and fixes what
it finds, and says how many it found.

**The module gains `src/test/resources/junit-platform.properties`** with the same four-thread
class-concurrent shape `graphitron` and `graphitron-model` use. Two facts make this safe to add here:
`graphitron-sakila-example` consumes `graphitron`'s test-jar, which excludes its own properties file
for exactly this reason, and it does *not* consume `graphitron-model`'s test-jar, which currently
carries one. So this file will be the only one on the classpath. Confirm that with
`Discovered 2 'junit-platform.properties'` absent from the module's surefire output rather than
assuming it.

## Tests

The suite is the test: 805 cases that currently pass sequentially must pass concurrently, and that is
the acceptance. Three additions make it stay true.

**A meta-test that a `@QuarkusTest` class carries the lock.** Reflectively or by source scan over the
module's test sources, every class annotated `@QuarkusTest` must also carry the shared
`@ResourceLock` key. Without it the fifth Quarkus class somebody adds re-breaks the suite with a
failure whose cause is three files away. The reactor's habit for this is a source-scanning meta-test
in the module that owns the rule.

**A meta-test that a `@QuarkusTest` earns its container.** A `@QuarkusTest` whose assertions never
touch a status code, a header, or a non-GraphQL route probably should not be one, and that is the rule
`TutorialSmokeTest` would have failed. Worth attempting because it is the rule that keeps this fix from
being undone by drift, and worth abandoning if it cannot be stated without false positives; the Spec
reviewer should say which they expect.

**Repeat the acceptance run.** Three concurrent runs, not one. A pass that depends on interleaving is
what this whole item is about, and one green run is compatible with a race that fires one time in
five.

Verification of the win is the matched pair from the measurement section re-taken: full
`mvnd install -Plocal-db` before and after, with the module's own wall clock read from the reactor
summary. Expect the module near 69 s against 92.5 s and the build near 317 s against 340 s. Absolute
seconds will differ per machine; the module figure is the one to hold.

## Roadmap entries

* R766 takes this module's five sequential `graphitron:generate` executions, 33.1 s, which is a plugin
  API change rather than a test fix.
* R767 takes `graphitron-maven-plugin`'s duplicated `plugin:descriptor` and its three sequential
  integration projects, 18.6 s.
* R764 takes `graphitron-model`'s test-jar carrying its `junit-platform.properties` onto three
  consumers. Independent of this item, and the reason the "only one properties file" check above is
  worth performing rather than assuming.
* R733's guardrail decision carries the durable half of this item's measurement: the build's wall
  clock and its critical path are currently the same number, which is what a total-suite budget cannot
  distinguish.

## What this item deliberately does not do

**It does not break reactor dependencies.** Two edges on the critical path are test-scope only,
`graphitron` to `graphitron-lsp` and `graphitron` to `graphitron-mcp`: both downstream modules compile
against `graphitron-model` alone and wait for `graphitron` only because Maven schedules whole module
lifecycles. Removing `graphitron-lsp` from the path would take it from 283 s to about 248 s, lifting
the unlimited-core ceiling from 1.20x to 1.37x. It would cost a module split, tests moved away from the
code they cover, and it would buy nothing until the build runs somewhere with more cores than the
modules already claim. The remaining edges are compile-scope and load-bearing. The mvnd notice will
keep suggesting otherwise on every build; this section is the recorded verdict.

**It does not serialize anything to hide a race.** The CI comment on `-T 1C` sets the rule: "The fix
for such a failure is the test, never serializing this build." Excluding four classes that a
dependency makes thread-hostile is scheduling around a documented, upstream-tracked limitation.
Narrowing seven over-broad assertions is fixing the test. Reaching for a lock on the `film` table
instead would be the thing that comment forbids, and it is the shortcut to refuse.

## How to re-measure

```bash
# The matched pair. Same tree, warm repository; alternate the arms for a delta under ten seconds.
mvnd install -Plocal-db -Dsmartbuilder.profiling=true
mvnd install -Plocal-db -Dsmartbuilder.profiling=true -T1

# This module alone, per goal. Use mvn: mvnd reformats the output and the timestamps are lost.
mvn install -pl :graphitron-sakila-example -Plocal-db \
    -Dorg.slf4j.simpleLogger.showDateTime=true \
    -Dorg.slf4j.simpleLogger.dateTimeFormat="HH:mm:ss.SSS"

# Whether a module saturates the box: sum the class spans in its surefire XML, divide by
# the module's wall clock. Above 1 means classes overlapped.
```

One reading note on the profiler's own summary line. `effective/maximum degree of concurrency 1.38/3`
does not describe the graph: the second figure is the thread count, and `-T4` reports `/4` while `-T8`
reports `/8`. Only the effective figure, service time over wall clock, says anything.
