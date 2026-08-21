---
id: R763
title: "Two test defects hold graphitron-sakila-example to one thread, and it is 23s of the critical path"
status: In Review
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

## What the implementation found

The four changes landed as planned. The Spec reviewer's ruling on the second meta-test was to abandon
it: "earns its container" is not a property of the assertion vocabulary, because a `@QuarkusTest`
legitimately pins CDI wiring, an interceptor, config or a startup effect without asserting a status
code, and this module already carries the fixtures such a test would use. Its intent is in the first
meta-test's failure message: `QuarkusTestLockEnforcementTest` now tells the author of a new
`@QuarkusTest` class to check whether it needs the container before taking the key, and names the 46
`querydb` classes that do not. The enforcer was checked by deleting one annotation and watching it
fail.

**Sixty-three assertion sites, not seven.** The seven the experiment surfaced were the ones a
particular interleaving happened to hit. Sixty-two methods in `GraphQLQueryTest` and one in
`ProjectionSqlBaselineTest` assert something that only holds while `film` or `content` holds nothing
but the seed. Finding them by interleaving would have taken many runs, so the sweep used a
deterministic detector instead: insert films and content rows shaped like the ones the module's
writers create, run the suite, and every case that asserts what the table holds fails on the spot.
The suite is green with those rows present, which is the property the item wanted and a stronger
statement than three green runs.

**A detector is only as good as the column shapes it varies, and the first one missed a shape.** Its
rows carried `rating` at its `'G'` default, `length` and `release_year` null, and content attached to
film 1, and that found sixty sites. It did not vary the *title*, so every row it inserted sorted
after the seed and no title-ordered page ever moved. Three cases in the `filmsOrderedConnection`
title cluster survived, which is what the In Review reviewer caught by adding a single row titled
`A DETECTOR FILM`. The detector's row set now spans the shapes the module's writers actually
produce, including a title that sorts ahead of the seed and one that sorts into the middle of it. The
lesson generalises past this item: the detector's coverage is the cross product of the columns it
varies against the orderings the tests use, and a column left at its seeded value is a blind spot
rather than a safe default.

Four shapes came up that the plan did not anticipate:

* **`film.rating` defaults to `'G'`.** Every film a fixture inserts without naming a rating is
  G-rated, so `films(rating: G)` and a G-filtered `totalCount` were counting other classes' rows. The
  PG films are the ones the seed can own, and the two cases now say why in place.
* **A facet count needs a bounded base, and `FilmExtraFilter.lengthIs` supplies one.** It is a
  non-facet filter field, so it joins every facet arm's base predicate and stands in for none of
  their own. Bounding on the seeded films' five lengths works because `film.length` has no default,
  so an inserted film carries null there and falls outside.
* **The approval worked example compared a whole response to an unfiltered root field.** An approval
  file over `films` is a claim about the database rather than about the query, and consumers copy
  this file. It now names its five rows, and the class javadoc says why an approval query must.
* **Two pairs of `email` cleanup bands overlapped.** Four classes write `email` and the module
  already gives each a hundred-wide `message_no` band, but their `@AfterEach` deletes were
  open-ended (`>= 100`, `>= 300`), so each reached into its neighbour's band and deleted rows that
  class had in flight. Bounded at both ends. This is the one writer-side fix in the item and it is
  the same defect as the `TutorialSmokeTest` delete: cleanup scoped wider than the rows a class owns.

Two notes for the next reader:

* **A count over an unfiltered relation cannot be made exact, and comparing two requests is not a
  fix.** `searchConnection` takes no filter, so the first attempt at
  `searchConnection_totalCount_independentOfAfterCursor` read `totalCount` on two pages and compared
  them. The full build failed it with 8 against 9: a film landed between the two requests. The case
  now walks to the last page and reads the count once, where a count that inherited the seek
  predicate would report at most a page's worth against the union's eight.
* **Moving `TutorialSmokeTest` to map assertions surfaced a false sentence in the tutorial.** Page 4
  claimed the first three customers belong to store 1; the seed puts Mary, Patricia and Barbara
  there and Linda and Elizabeth in store 2. The old string-matching assertions could not see it
  because both addresses appear in the response either way. Prose corrected.

Measured on a contended 4 vCPU sandbox, alternating arms, `surefire:test` on the module:
**59-61 s before, 44 s after** (three readings: 43.7, 44.2, 44.1), so about 15 s off the module and
therefore off the build's wall clock. Proportionally the same 25% the item projected; the absolute
figure is smaller because the baseline here is slower than the box the item was measured on. The
module's own total moved 105 s to 99 s, which is noisier than the surefire figure because
`graphitron:generate` and `quarkus:build` dominate it. No
`Discovered 2 'junit-platform.properties'` warning, confirmed by grep rather than assumed.

After the rework round the suite is 839 tests and the three re-run readings are 48.5, 49.7 and
48.0 s, against the same 59-61 s baseline. Trunk added tests between the two rounds, so the arms are
no longer matched; the number to hold is the ratio, not either absolute.

One case renamed: `filmsFaceted_noFilter_countsMatchPlainAggregates` is now
`filmsFaceted_noFacetFilter_countsMatchPlainAggregates`, since it carries a non-facet filter.

## Reviewer findings

**In Review → Done, first pass: rework.** The four changes landed as described and the win is real
(`mvn install -Plocal-db` green, 838 tests, 0 failures, module `SUCCESS [01:36 min]`, summed
surefire class time 114.5 s against a shorter surefire wall clock, so classes overlapped; no
`Discovered 2 'junit-platform.properties'`). One finding blocks the gate, and it is narrow.

**Three assertions in `GraphQLQueryTest` still assert what the `film` table holds.** All three are
in the `filmsOrderedConnection` title-ordering cluster, and all three assert the exact contents of a
title-ordered page over a set the query does not bound:

* `filmsOrderedConnection_orderByTitle_paginatesAlphabetically`: `order: TITLE ASC, first: 3` over
  the unfiltered root field, `containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER",
  "ADAPTATION HOLES")`. That is the claim "the three alphabetically first films in the table are the
  seed's".
* `filmsOrderedConnection_orderByTitle_cursorNavigation`: the same walk at `first: 2` plus its
  `after` cursor, so both pages inherit the same dependence.
* `filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree`: `rating: G,
  order: TITLE ASC, first: 1`, `containsExactly("ACE GOLDFINGER")`. This one is the same
  `DEFAULT 'G'` hazard the item found and wrote down twice, and fixed at its two sibling sites
  (`films_filteredByRating` and
  `filmsOrderedConnection_totalCount_underFilter_appliesSamePredicate`) by moving them to `PG`.
  Filtering on `G` bounds nothing, because every film another class inserts without naming a rating
  is G-rated.

Reproduced with the item's own detector method, extended by the one shape it did not vary. The
detector inserted films with `rating` left to its default and `length`/`release_year` null, but not
a *title* that sorts before the seed's, so a title-ordered page never moved. Insert one
(`insert into film (title, language_id) values ('A DETECTOR FILM', 1)`, which lands G-rated with a
null length, exactly like the module's own writers' rows) and run the class:

```
Tests run: 377, Failures: 3
  filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree:2095
  filmsOrderedConnection_orderByTitle_cursorNavigation:2117
  filmsOrderedConnection_orderByTitle_paginatesAlphabetically:2083
```

The rest of the sweep holds up against the same detector. Nothing else in the module fails, and the
other whole-table shapes checked out: `film.text_rating` and `film.length` carry no default, so
`films(textRating: G)` and the `extra.lengthIs` facet bound are safe as documented;
`filmsOrderedConnection_defaultOrder_paginatesById` is safe because `serial` only climbs, so the
seed permanently owns the lowest ids; nothing in the module writes `customer` or `actor`, so
`TutorialSmokeTest`'s five-customer assertions and the exact actor counts are sound; the four
`email` bands are bounded at both ends and each class's `message_no` literals fall inside its own
band; and the four `@QuarkusTest` classes all carry `QuarkusTestLock.KEY`.

**What would satisfy the gate.** Make the three cases state what they mean rather than what the
table holds. The file already carries the tool for it: `seededTitlesInOrder` is what the rate-DESC
siblings use, and reading the seeded rows' relative order out of an untrimmed walk is the same move
those cases made for the same reason (a `first: n` page is not the seed's page once another class
inserts a row that sorts ahead of it). For the `rating: G` case, `PG` is the bounded filter, as at
its two siblings. Then re-run the detector with a low-sorting title as well as a default-rating one,
and say so.

Two non-blocking notes, neither a gate condition:

* The body says the abandoned "earns its container" meta-test's intent "went into the first
  meta-test's failure message instead". `QuarkusTestLockEnforcementTest`'s message explains the lock
  and points at `QuarkusTestLock`; it does not ask whether the class needs a container at all. Either
  put that sentence in the message or drop the claim.
* `QuarkusTestLockEnforcementTest` skips class files whose name contains `$`, so a nested
  `@QuarkusTest` class would not be seen. It shares that with `TierAnnotationEnforcementTest`, so it
  is a pre-existing property of the walk shape rather than something this item introduced.

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
