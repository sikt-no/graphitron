---
id: R863
title: "Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it"
status: Ready
bucket: bug
priority: 1
theme: testing
depends-on: []
created: 2026-08-27
last-updated: 2026-10-28
---

# Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it

## Goal

Every execution-tier test in `graphitron-sakila-example` is correct on a database other tests write
to at the same moment, so a red execution test means the generated code or the test is wrong and
never that another class happened to commit a row at the same time. Concurrent clients seeing
each other's committed rows is the normal condition of a database, and it is the condition
consumers run generated code under; the fix is in the tests, not in the fixture. Today the
`local-db` profile (the Maven profile that points tests at one PostgreSQL server that is already
running, instead of starting a container per class) puts all of them in one database, four classes
at a time, and trunk CI fails about once a day on a test that is correct: on 2026-10-20 and
2026-10-21 alone, `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`
counted a film another class had inserted, and `filmsConnection_emptyBeforeCursor_returnsLastPage`
compared two reads of the table's tail across a sibling's insert and cleanup. When this lands, a
test author knows the one rule (a reader asserts over rows it can name, a writer touches only rows
it created), the exposed tests follow it, and the provisioning code that is copied 75 times lives in
one harness.

The execution tier is the tier that runs a real GraphQL request through generated code to
PostgreSQL and asserts on the rows that come back (`docs/architecture/how-to/testing.adoc`,
Execution tier). The `graphitron` module's `TestDatabase` already works this way: one database per
JVM, never reset, tests resilient rather than isolated.

## What is true today

Measured against the tree at `387bdf7`.

* **Provisioning is copied, 75 times.** Each of 74 test classes (66 under `test/querydb`, eight under `test/internal`) carries the same `@BeforeAll`: if
  `test.db.url` is set it connects there, otherwise it starts its own
  `PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql")`, then builds a
  `DriverManager`-backed `DSLContext` in autocommit. `SmokeTestPostgresResource` makes the same
  choice for the five `@QuarkusTest` classes. There is no harness, so there is nothing to change in
  one place.
* **The two paths share differently.** Off `local-db` every class has a private server. On
  `local-db` every class shares `rewrite_test`, which CI and the sandbox seed from
  `graphitron-sakila-db`'s `init.sql` and which jOOQ code generation also reads. Class
  parallelism is four (`junit-platform.properties`).
* **The eight `test/internal` classes were surveyed too.** None inserts, updates or deletes a row.
  Their reads are keyed (by id, by seeded key) or assert a statement count over their own query, so
  none joins the exposed inventory below; they migrate to the harness with the rest.
* **27 classes write to that shared server.** Eleven commit `film` rows (marker titles, sequence
  ids above 100, `language_id = 1`); `GraphQLQueryTest.updateFilmWithErrors_existingFilm_returnsTheUpdatedRow`
  commits a new title on seeded film 1 and restores it in `finally`; `rental`, `content`, `email`,
  `film_endorsement` and `film_bookmark` take committed rows; `TenantFanOutExecutionTest` holds
  `language` 901 to 903 for its whole class lifetime. Two rows leak for good:
  `upsertAddress_omittedSameTableIdentity_serviceInsertAssignsPk` leaves an `address` and
  `endorseFilm_fkReferenceLandsOnRenamedChildColumn` leaves a `film_endorsement`, so a persistent
  `rewrite_test` drifts from `init.sql` run by run.
* **Cluster-scoped names are fixed.** The tenant classes create and drop `tenant_1`/`tenant_2`
  (`TenantDivinedRoutingExecutionTest`), `fanout_t1`/`fanout_t2` (`TenantFanOutExecutionTest`),
  `scatter_t1`/`scatter_t2` (`TenantScatterExecutionTest`) and two `org_tenant_*` databases
  (`ConvertedTenantRoutingExecutionTest`), each deriving their URLs with its own private
  `tenantUrl` rewrite of the JDBC URL.
* **19 test methods break today when a sibling commits.** Four read an unfiltered or broadly
  filtered set (`filmsByTitleBean_absentInput...`, three `ResultKeyScopeExecutionTest` cases over
  films per language); five compare two reads taken at different instants
  (`filmsConnection_emptyBeforeCursor...`, `RootFieldExecutionTest.interfaceRootConnection_totalCountAgreesWithTheUnpagedRoot`,
  three in `ServicePolymorphicReturnExecutionTest`); ten read seeded film 1's title, which the
  one seeded-row writer above changes (`FederationEntitiesDispatchTest`, `RootFieldExecutionTest`,
  `ApprovalQueryExampleTest`, `RoutineFieldExecutionTest` and others).
* **Two more victims, observed 2026-10-26.** `PageSizeMaximumQueryTest` (added after the
  measurement above) reads the whole `film` table through a page-size-capped connection: on trunk
  CI run 7148 at `0d7ea51`, `followingTheCursorVisitsEveryRowOnce` saw `[1, 2, 3, 4, 5, 98]` and
  `aLastAboveTheMaximumIsServedAtTheMaximumFromTheEnd` saw `[5, 98]` instead of `[4, 5]`, film 98
  being a sibling's committed insert. The same tree run locally on `local-db` passed that class but
  failed `FederationEntitiesDispatchTest.entities_typeScopedSelectionSet_resolvesEachTypesOwnFields`,
  which read film 1's title as `UPD-OK-<uuid>` mid-way through
  `updateFilmWithErrors_existingFilm_returnsTheUpdatedRow`, and `filmsByTitleBean_absentInput...`
  (six films). Different classes fail on each run of one tree, so the count above is a floor.
* **About 65 more are safe only by convention.** Roughly 51 exact-cast and absence assertions over
  `film_actor` hold because `DmlBulkMutationsExecutionTest` seeds its pairs inside
  `inRolledBackTransaction`; fourteen whole-table `rental` counts in `GraphQLQueryTest` hold
  because no other class rents. The next writer that does either commits, and they fail.
* **The response so far has been per-test.** 34 hand-written filters keep a read to the seeded
  rows (`seededByTitle`, `SEEDED_FILM_IDS`, `LanguageWindow` and their siblings in
  `GraphQLQueryTest`, `OptionalNodeIdProjectionExecutionTest` and
  `MultiTableNodeIdRouteExecutionTest`), and six Backlog items each recorded one colliding pair.
  The convention the properties file states ("writers scope their cleanup to rows they can name;
  readers assert what their own query means") has no enforcer, and the count of exposed tests is
  not going down.

## Design

**The rule.** Seeded rows (what `init.sql` inserts) are read-only for every test. A test that
writes inserts rows under keys it chose (marker titles, ids it allocated) and reads back only those
rows. A reader asserts over rows it can name, either seeded keys or keys it wrote, and never over a
table's cardinality, its tail, or two reads taken at different instants over a range a sibling can
write into. Cleanup deletes what the test wrote, by key. The rule is stated once in
`docs/architecture/how-to/testing.adoc` for both modules and applied to the whole exposed
inventory in this item, so the plan is a rule applied at once, not a patch per flake.

**One harness for provisioning.** `SakilaDatabase`, a JUnit extension in
`graphitron-sakila-example`'s test sources, is the only code that reads `test.db.*`, constructs a
`PostgreSQLContainer` or composes a JDBC URL.

* **One server, one database per test JVM.** With `test.db.url` set, the database is the one it
  names. Without it, one container per test JVM, started on first use and seeded from `init.sql`,
  replacing today's container per class. This is the only branch between the two paths, so they
  behave identically by construction. Nothing is cloned, reset or dropped between classes.
* **Access.** `dsl()`, `url()`, `user()` and `password()`, for the `DSLContext` the class reads
  through and for the owned-path classes that build their own `DataSource`. Registered as
  `@RegisterExtension static final SakilaDatabase db = ...` so the connection exists before the
  class's own `@BeforeAll`.
* **Quarkus.** `SmokeTestPostgresResource` takes its database from the harness. The five
  `@QuarkusTest` classes already run one at a time under `QuarkusTestLock.KEY`.

**Cluster-scoped names are minted per run.** The tenant classes create and drop databases with
fixed names (`tenant_1`/`tenant_2`, `fanout_t1`/`fanout_t2`, `scatter_t1`/`scatter_t2`, the
`org_tenant_*` pair), which collide across two runs on one server, two sessions on one laptop for
instance. The harness hands them a name from `tenantDatabase(String stem)`: the stem plus a token
unique to the run, with its URL, dropped by the class that created it. This is the shared-database
rule applied to names; the four classes lose their private `tenantUrl` rewrites. The two fixed-name
roles, `carrier_rls_probe` (`RoutineCarrierRlsExecutionTest`) and `rls_probe_user`
(`SessionHookExecutionTest`), each belong to one class, which already creates and drops them, and
stay as they are.

**The exposed set is rewritten to the rule.**

* The four unfiltered or broadly filtered readers (`filmsByTitleBean_absentInput...`, three
  `ResultKeyScopeExecutionTest` cases over films per language) filter to named rows.
* The five two-instant comparisons (`filmsConnection_emptyBeforeCursor...`,
  `RootFieldExecutionTest.interfaceRootConnection_totalCountAgreesWithTheUnpagedRoot`, three in
  `ServicePolymorphicReturnExecutionTest`) and the page-size-capped reads in
  `PageSizeMaximumQueryTest` run over a key range or marker the test owns, so a sibling's insert
  cannot fall inside it.
* `GraphQLQueryTest.updateFilmWithErrors_existingFilm_returnsTheUpdatedRow` inserts its own film
  and updates that instead of film 1, so the ten readers of film 1's title stop being exposed.
* The roughly 65 convention-safe assertions (the `film_actor` casts that hold because
  `DmlBulkMutationsExecutionTest` seeds inside `inRolledBackTransaction`, the fourteen whole-table
  `rental` counts) are rewritten the same way now, rather than waiting for the writer that
  breaks them.
* The 34 existing filters (`seededByTitle`, `SEEDED_FILM_IDS`, `LanguageWindow` and siblings) are
  the right shape applied ad hoc after each flake. They stay, are brought under the one rule, and
  where a helper repeats across classes it moves to a shared test helper rather than being
  copied again.
* The two tests that leave rows behind (`upsertAddress_omittedSameTableIdentity_serviceInsertAssignsPk`,
  `endorseFilm_fkReferenceLandsOnRenamedChildColumn`) delete what they wrote, as hygiene. Under the
  rule a leaked row cannot break a reader, so a persistent `rewrite_test` drifting from `init.sql`
  is harmless to the tests, though still worth not doing.

**Enforcement, named honestly.** A structural guard cannot tell a whole-table count from a filtered
one, so the reader/writer rule is held by review and by `testing.adoc`. Two things are mechanical.
A guard in `graphitron-sakila-example`'s `test/internal` package, in the shape of `graphitron`'s
`StoreFixtureGuardTest` (a scan of test sources with a closed list of sanctioned homes, a
file-count floor and a staleness check on its own list), fails the build on `new
PostgreSQLContainer`, a read of `test.db.url`/`test.db.username`/`test.db.password`,
`DriverManager.getConnection` or a `jdbc:postgresql:` literal in the module's test sources outside
`SakilaDatabase`, which catches the copied `@BeforeAll` and the `tenantUrl` rewrites alike. And a
cheap detector for the rule itself: running the execution tier at a higher class parallelism, or
two runs at once against one server, surfaces a non-resilient test faster than waiting for CI to
hit the interleaving. The In Review evidence below uses it.

## Implementation

* `graphitron-sakila-example/src/test/java/.../test/SakilaDatabase.java` (new): the extension
  above.
* The 74 execution-tier classes (66 under `test/querydb`, eight under `test/internal`: `AddressOccupantsListBatchingTest`, `AccessorDerivedSourceTest`, `OutcomePolymorphicChildExecutionTest`, `AddressOccupantCarrierSingleCardinalityTest`, `MultiTablePolymorphicSelfFkOrientationExecutionTest`, `MultiTablePolymorphicJoinedCorrelationExecutionTest`, `MultiTablePolymorphicParentHoldsFkExecutionTest`, `MutationPayloadLifterTest`): the copied `@BeforeAll` and `@AfterAll` blocks become the
  extension field and a `db.dsl()` read. Mechanical; a class's own setup after the connection (for
  example `TenantFanOutExecutionTest` seeding its tenant tables) stays in its own `@BeforeAll`.
  `DmlBulkMutationsExecutionTest.seedFilmActor`'s second-connection check opens its second
  connection through the harness.
* The four tenant classes: names and URLs from `db.tenantDatabase(...)`; the `tenantUrl` helpers
  go, the create and drop of the database stays in the class.
* `SmokeTestPostgresResource`: takes its database from the harness.
* The exposed set under "Design", rewritten test by test to the rule; the two leaking tests delete
  their rows.
* `graphitron-sakila-example/src/test/java/.../test/internal/SakilaDatabaseGuardTest.java` (new):
  the guard above.
* `junit-platform.properties`: the long comment shrinks to the concurrency settings, the Quarkus
  lock and a pointer to `testing.adoc`. It also stops saying "the four" `@QuarkusTest` classes;
  there are five.
* CI workflows, `.claude/scripts/session-start-web-env.sh` and the `local-db` comment in
  `graphitron-sakila-example/pom.xml` are unchanged: `rewrite_test` stays seeded, and is now the
  tests' database as well as the code generator's input.

## Tests

* `SakilaDatabaseTest` (unit-tagged where it needs no server, execution-tagged where it does): the
  harness connects to the database `test.db.url` names, else starts one container per JVM and seeds
  it from `init.sql`; `tenantDatabase` names differ between two runs and are dropped by the class
  that created them.
* `SakilaDatabaseGuardTest` fails on a planted `new PostgreSQLContainer` and a planted
  `System.getProperty("test.db.url")` outside the harness (its own negative case), and passes on
  the migrated tree.
* The 19 methods listed under "What is true today", the two observed on 2026-10-26 and the
  convention-safe assertions pass with a deliberate interleaving: each rewritten test is run while
  a sibling inserts and deletes film, `film_actor` and `rental` rows concurrently. That they pass
  is the demonstration the item is complete, since each failed on CI with a sibling's row in it.
* Evidence at In Review: five consecutive full `mvn install -Plocal-db` runs green on one tree, one
  of them at doubled class parallelism, and the module's wall clock before and after, so a reviewer
  can see what one container per JVM saves against 74 containers.

## Documentation

`docs/architecture/how-to/testing.adoc` states the reader/writer rule once for both modules, under
the Execution tier beside `TestDatabase`, and gains "Where an execution test gets its database":
the harness, the per-run tenant database names and the guard. The Execution tier section's "on the
shared fixture" wording becomes "on the Sakila fixture".

## Roadmap entries

This item absorbs the Backlog items that each recorded one colliding pair; they are discarded in
the commit that moves this item to Spec, with `Discarded:` entries in `changelog.md` naming R863:

* R823 (`dev-executor-fidelity-reads-a-mutating-table-twice`): `DevExecuteExecutionTest`'s fidelity
  comparison over an unfiltered `films` read.
* R825 (`film-actor-fixture-mutated-and-read-concurrently`): the `film_actor` and `film` writes in
  `DmlBulkMutationsExecutionTest` against the per-film cast readers.
* R843 (`parallel-execution-tier-shares-sakila-rows`): the same `film_actor` pair, with the three
  candidate fixes this item chooses between.
* R946 (`execution-tier-film-actor-isolation`): `splitTableField_conditionJoin_returnsActorsPerFilm`
  against a `film_actor` writer.
* R1028 (`execution-tests-see-a-concurrent-tests-film-rows`):
  `inputFieldCondition_customScalarDefaults_bindAsBigDecimalBounds` against
  `NestedInputDmlExecutionTest`'s renamed film.

R959, the sixth, already shipped (`f7b051e`) as one of the filters this item removes.

## Other solutions we've considered

* **A database per class, cloned from a template.** One pristine template per run, a clone per
  class, auxiliary databases, a run token and a liveness scheme to sweep dead runs' databases, and
  a guard to hold it in place. Rejected: it is a database lifecycle manager built so tests can
  avoid stating what their own query means, it adds ways for the build to fail in CI, the sandbox
  and a shared laptop server that have nothing to do with generated code, and a suite that only
  passes in a private database cannot notice a generated query that leans on the table holding
  nothing but what it expects.
* **Keep patching per test.** The status quo: 34 filters and six items so far, and 19 exposed
  methods remain. The plan here is not this, because it applies one rule to the whole inventory at
  once and makes the rule the default.
* **A shared read-only database for readers, private databases for writers.** Fails correct tests:
  `session_connect`, which the plugin-level `<sessionState>` mount calls on every owned-path
  acquisition, takes `nextval('session_handle_seq')`, and PostgreSQL refuses `nextval()` in a
  read-only transaction.
* **JUnit resource locks**, `READ` for readers and `READ_WRITE` for writers. Every class has to
  declare one, a missing `READ` silently reintroduces the race, and `GraphQLQueryTest` is both.
* **Every write inside a rolled-back transaction.** The owned-path classes commit through a
  `DataSource` per field, and some tests assert that a row persisted, so neither can roll back.
* **No class parallelism.** Removes the race at the cost of the parallelism the properties file
  measured as worth three cores, in the reactor's terminal module.

## Reviewer findings

### Round 1 (2026-10-21, Spec -> Ready, reviewer session 01Xo2fY4oBEyeUJ55YiU28NH)

Verdict: withhold. The survey under "What is true today" is careful and I have no quarrel with its
facts; the 19 failing methods and the 65 convention-safe ones are the right inventory. The finding
is with the goal the inventory is put to. The item reads the cross-talk as a database that is
shared too widely; it is tests that are not written for a shared database. The fix belongs in the
tests, and the design should be inverted accordingly.

**1. The goal targets the wrong invariant.** A test should assume other tests are running against
the same database at the same time, because that is what a database is for: concurrent clients
seeing each other's committed rows is the normal condition, not a fault in the fixture. The goal
as written makes `hasSize(5)` over the shared `film` table mean "exactly five" by removing
everyone else from the table. The assertion is what is wrong: over a table other clients may
write, a whole-table count says nothing about the generated code. The goal should instead read
along the lines of "every execution test is correct on a database other tests write to
concurrently, so a red execution test means the generated code or the test is wrong", which keeps
the item's outcome (no red build from a sibling's row) and states it as a property of the tests.

**2. The cost is infrastructure spent on the wrong problem.** A pristine template per run, a clone
per class, auxiliary databases, a run token, an advisory-lock liveness scheme to sweep dead runs'
databases, and a guard to hold it all in place: that is a database lifecycle manager built so tests
can avoid stating what their own query means. It also has to be kept working in three places (CI,
the web sandbox, a laptop server shared by several sessions), each a new way for the build to fail
that has nothing to do with generated code. Private databases per class are the wrong use of a
database; they trade a test-writing rule for machinery, and the machinery grows with the class
count rather than with the number of tests that are wrong.

**3. Isolation hides behaviour the generated code meets in production.** Consumers run the
generated code against databases other clients write to. A suite that can only pass in a private
database cannot notice a generated query that leans on the table holding nothing but what it
expects. Tests that pass among concurrent writers are the closer model.

**4. The per-test filters are the start of the right answer, not debris.** The design removes the
34 seeded-only filters. Under the rule above they are what every reader should do, generalised:
a reader asserts over rows it can name (seeded keys, or the keys and marker values it wrote
itself), never over a table's cardinality or tail. What is wrong with today's filters is that each
was added ad hoc after a flake; the item should make the rule the default and rewrite the exposed
set to it. Concretely, for the inventory already gathered:

* the four unfiltered or broadly filtered readers filter to named rows;
* the five two-instant comparisons (cursor tail, `totalCount` against an unpaged read) run over a
  key range or marker the test owns, so a sibling's insert cannot fall inside it;
* the one seeded-row writer (`updateFilmWithErrors_existingFilm_returnsTheUpdatedRow` on film 1)
  inserts its own film and updates that, so the ten readers of film 1's title stop being exposed;
  seeded rows become read-only by rule;
* the roughly 65 convention-safe assertions (`film_actor` casts, whole-table `rental` counts) are
  rewritten the same way now, rather than waiting for the writer that breaks them;
* the two leaking tests still delete what they wrote, as hygiene, but a leak stops being able to
  break anyone, so a persistent `rewrite_test` drifting run by run is harmless.

**5. Two parts of the design stand and should be kept.** Provisioning copied 67 times is a real
defect: one harness that alone reads `test.db.*` and starts the container, one server per test
JVM, is right, and the guard against `new PostgreSQLContainer` and `test.db.url` outside it still
earns its place. And the fixed cluster-scoped names (`tenant_1`, `fanout_t1`, `scatter_t1`, the
`org_tenant_*` pair) do collide across concurrent runs on one server, two sessions on one laptop
for instance, so minting those names from a run token is the shared-database rule applied to
names, not isolation. The `graphitron` module's `TestDatabase` (test sources,
`no.sikt.graphitron.rewrite.test.db`) is the precedent for the harness: one database per JVM,
never reset, resilient tests, written up in `docs/architecture/how-to/testing.adoc` under the
Execution tier.

**6. Name the enforcer honestly.** A structural guard cannot tell a whole-table count from a
filtered one, so the rule for readers and writers is held by review and by
`docs/architecture/how-to/testing.adoc`, which should state it once for both modules. The item
should say so, and may consider a cheap detector: running the execution tier at a higher class
parallelism, or two runs at once against one server, surfaces a non-resilient test faster than
waiting for CI to hit the interleaving. "Keep patching per test" under "Other solutions" then
stops describing the plan, since the plan is a rule applied to the whole inventory at once, not
a patch per flake.

### Round 2 (2026-10-28, Spec -> Ready, reviewer session 01WkW2JGRvjf1KLUB4vdRJMT)

Verdict: withhold, narrowly. The round 1 inversion landed: the goal is now a property of the tests, the
design is one harness plus a rule applied to the whole inventory, and "Other solutions" states why the
template-clone design was dropped. I can say what changes for a consumer: a red execution test stops
meaning "a sibling committed a row", provisioning lives in one extension, and the rule is written down
once. The architecture fit is sound (it extends `TestDatabase`'s one-database-per-JVM shape and
`StoreFixtureGuardTest`'s guard shape rather than standing a parallel mechanism). Every test, class and
symbol the spec names exists as named. Two checkable claims do not hold, and one of them leaves the
guard and the migration list disagreeing.

**1. (Question 1, claims about code that exists) The provisioning inventory is short by eight classes.**
The spec counts "67 times" (66 classes plus `SmokeTestPostgresResource`) and scopes the migration to
"the 66 execution-tier classes". `grep -rl 'new PostgreSQLContainer' graphitron-sakila-example/src/test`
finds 75 files: 66 under `test/querydb`, `SmokeTestPostgresResource`, and eight under `test/internal`
(`AddressOccupantsListBatchingTest`, `AccessorDerivedSourceTest`, `OutcomePolymorphicChildExecutionTest`,
`AddressOccupantCarrierSingleCardinalityTest`, `MultiTablePolymorphicSelfFkOrientationExecutionTest`,
`MultiTablePolymorphicJoinedCorrelationExecutionTest`, `MultiTablePolymorphicParentHoldsFkExecutionTest`,
`MutationPayloadLifterTest`). Each carries the same `test.db.url` branch. As written, the guard forbids
`new PostgreSQLContainer` and `test.db.*` reads in the module's test sources outside `SakilaDatabase`,
while the Implementation list migrates only the 66, so the guard would fail on the eight at the first
build. Restate the count, bring the eight into the Implementation list (or name why they are exempt and
add them to the guard's closed list), and say whether the survey under "What is true today" (the 27
writing classes, the 19 exposed methods, the 65 convention-safe assertions) covered them. It reads as
if it did not, and any of the eight that writes `film`, `film_actor` or `rental` rows, or reads a whole
table, belongs in the exposed inventory, since "applied to the whole exposed inventory" is the item's
claim. Satisfied by a corrected count, a scope statement for those eight, and a survey that has looked
at them.

**2. (Question 1, a stated claim that is false) The five absorbed items' changelog entries describe the
rejected design.** "Roadmap entries" says the `Discarded:` entries in `changelog.md` name R863. They do,
but each says R863 "gives every execution-tier class its own database cloned from `init.sql`, so the
colliding pair this item recorded cannot meet", which is the round 1 design, not this one. Anyone
following R823, R825, R843, R946 or R1028 to R863 is told the opposite of what it does (a shared database
with resilient tests). Reword those five entries to match the plan; the five items' own collisions should
now be named as tests rewritten to the rule.

Nothing else blocks. The guard's name-the-limits paragraph, the `nextval` argument against a read-only
database, and the per-run tenant names (including leaving the two single-class roles alone) all check
out against the tree.
