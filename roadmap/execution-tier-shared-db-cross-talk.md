---
id: R863
title: "Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it"
status: Spec
bucket: bug
priority: 1
theme: testing
depends-on: []
created: 2026-08-27
last-updated: 2026-10-21
---

# Execution-tier tests cross-talk through the shared local-db instance, and trunk CI is red on it

## Goal

Every execution-tier test class in `graphitron-sakila-example` runs against a database no other
class can see, on CI, in the web sandbox and on a laptop alike, so a red execution test means the
generated code or the test is wrong and never that another class happened to commit a row at the
same moment. Today the `local-db` profile (the Maven profile that points tests at one PostgreSQL
server that is already running, instead of starting a container per class) puts all of them in
one database, four classes at a time, and trunk CI fails about once a day on a test that is
correct: on 2026-10-20 and 2026-10-21 alone, `GraphQLQueryTest.filmsByTitleBean_absentInput_buildsNoBeanAndLeavesTheFieldUnfiltered`
counted a film another class had inserted, and `filmsConnection_emptyBeforeCursor_returnsLastPage`
compared two reads of the table's tail across a sibling's insert and cleanup. When this lands, a
test author writes `hasSize(5)` over the five seeded films and it means what it says, with no
seeded-rows-only filter in front of it.

The execution tier is the tier that runs a real GraphQL request through generated code to
PostgreSQL and asserts on the rows that come back (`docs/architecture/how-to/testing.adoc`,
Execution tier).

## What is true today

Measured against the tree at `387bdf7`.

* **Provisioning is copied, 67 times.** Each of 66 test classes carries the same `@BeforeAll`: if
  `test.db.url` is set it connects there, otherwise it starts its own
  `PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql")`, then builds a
  `DriverManager`-backed `DSLContext` in autocommit. `SmokeTestPostgresResource` makes the same
  choice for the five `@QuarkusTest` classes. There is no harness, so there is nothing to change in
  one place.
* **The two paths isolate differently.** Off `local-db` every class has a private server. On
  `local-db` every class shares `rewrite_test`, which CI and the sandbox seed from
  `graphitron-sakila-db`'s `init.sql` and which jOOQ code generation also reads. Class
  parallelism is four (`junit-platform.properties`).
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

**One harness, one server per test JVM, one database per class.** A new JUnit extension,
`SakilaDatabase`, owns every connection an execution test makes. It is the only code that reads
`test.db.*`, constructs a `PostgreSQLContainer` or composes a JDBC URL.

* **Server.** With `test.db.url` set, the server is the one it names. Without it, one container per
  test JVM, started on first use, replacing today's container per class. This is the only branch
  between the two paths; everything after it is the same code, so the two paths isolate
  identically by construction rather than by agreement.
* **Pristine template.** On first use in a JVM, under a JVM-wide lock, the harness creates a
  database named for this run and applies `init.sql` from the classpath to it, the same script the
  container path applies today. Nothing connects to it afterwards. Measured on PostgreSQL 16 in
  the web sandbox: 0.4 s to apply.
* **Per-class clone.** `beforeAll` runs `CREATE DATABASE <class database> TEMPLATE <pristine>` and
  hands the class a `DSLContext` over it, plus the URL and credentials for tests that build their
  own `DataSource` (the owned-path classes, where the generated runtime acquires its own
  connections). `afterAll` drops it with `WITH (FORCE)`. Measured: 0.13 s for one clone and 0.56 s
  for eight concurrent clones of one template, so the module's 66 classes cost a few seconds of
  wall clock spread over four threads.
* **Auxiliary databases.** `auxiliary(String suffix)` mints an empty database whose name carries
  the class database's name, hands back its URL, and drops it with the class. The four tenant
  classes take their tenant databases from it and lose their `tenantUrl` copies.
* **Hygiene across runs.** Every database the harness creates carries one prefix and the run's
  token. A run drops what it created; at start it also drops prefixed databases left by a run that
  died, and never a live concurrent run's. The implementer picks the liveness test (a
  session-level advisory lock keyed on the run token, held on the server's `postgres` database for
  the JVM's lifetime, is the obvious one).
* **Quarkus.** `SmokeTestPostgresResource` asks the harness for a database per resource start. The
  five `@QuarkusTest` classes already run one at a time under `QuarkusTestLock.KEY`.

`rewrite_test` goes back to being the code generator's input only. No test reads it, so a test
run no longer depends on whether someone re-seeded it after `init.sql` moved, and nothing a test
does can leak into it.

**What this does not isolate.** Two roles are cluster-scoped and fixed-name:
`carrier_rls_probe` (`RoutineCarrierRlsExecutionTest`) and `rls_probe_user`
(`SessionHookExecutionTest`). Each belongs to one class, so no two classes in a run contend for
one, and both classes already create and drop them. They stay as they are; minting role names
would change what those RLS tests assert for no gain in isolation.

**The enforcer is structural.** Once every class owns its database there is no reader/writer
distinction to declare and nothing to check at run time; the invariant is "every execution test
gets its database from the harness". A guard in `graphitron-sakila-example`'s `test/internal`
package, in the shape of `graphitron`'s `StoreFixtureGuardTest` (a scan of test sources with a closed list of
sanctioned homes, a file-count floor and a staleness check on its own list), fails the build on
`new PostgreSQLContainer`, a read of `test.db.url`/`test.db.username`/`test.db.password`,
`DriverManager.getConnection`, or a `jdbc:postgresql:` literal anywhere in the module's test
sources outside `SakilaDatabase`. That catches the copied `@BeforeAll` and the `tenantUrl`
rewrites alike, which a guard on the system property alone would not.

**The per-test filters go.** With no other class able to touch a class's database, the 34
seeded-only filters guard nothing and would be copied by the next author as if they did. Each one
that exists only because of another class's writes is removed in this item, and its assertion
returns to the plain form (`hasSize(5)`, an exact list). A filter that guards against the class's
own earlier writes stays, and says so in a comment; the two permanent leaks are fixed at their
source instead (the leaking test deletes what it wrote), so that `seededDistricts` and its kin
have nothing left to filter.

## Implementation

* `graphitron-sakila-example/src/test/java/.../test/SakilaDatabase.java` (new): the extension above.
  `BeforeAllCallback`/`AfterAllCallback`; `dsl()`, `url()`, `user()`, `password()`,
  `auxiliary(String)`. Registered as `@RegisterExtension static final SakilaDatabase db = ...` so
  its `beforeAll` runs before the class's own `@BeforeAll`.
* The 66 execution-tier classes: the copied `@BeforeAll` and `@AfterAll` blocks become the
  extension field and a `db.dsl()` read. Mechanical; the class's own setup after the connection
  (for example `TenantFanOutExecutionTest` seeding its tenant tables) stays in its own
  `@BeforeAll`. `DmlBulkMutationsExecutionTest.seedFilmActor`'s second-connection check opens its
  second connection through the harness.
* The four tenant classes: tenant databases from `db.auxiliary(...)`; the `tenantUrl` helpers and
  the `drop database if exists ... with (force)` / `create database` pairs go.
* `SmokeTestPostgresResource`: takes its database from the harness.
* `GraphQLQueryTest`: `upsertAddress_omittedSameTableIdentity_serviceInsertAssignsPk` and
  `endorseFilm_fkReferenceLandsOnRenamedChildColumn` delete the rows they create.
* The seeded-only filters in `GraphQLQueryTest`, `OptionalNodeIdProjectionExecutionTest` and
  `MultiTableNodeIdRouteExecutionTest` (`seededByTitle`, `SEEDED_FILM_IDS`, `LanguageWindow`,
  `seededDistricts` and siblings): removed where the guarded writer is another class, kept with a
  comment where it is the class itself.
* `graphitron-sakila-example/src/test/java/.../test/internal/SakilaDatabaseGuardTest.java` (new):
  the guard above.
* `junit-platform.properties`: the long comment shrinks to the concurrency settings and the Quarkus
  lock, with a pointer to `testing.adoc` for where a test's database comes from. It also stops
  saying "the four" `@QuarkusTest` classes; there are five.
* CI workflows and `.claude/scripts/session-start-web-env.sh` keep seeding `rewrite_test`, which
  code generation still reads. The `local-db` comment in `graphitron-sakila-example/pom.xml` stops
  telling the caller to apply `init.sql` for the tests' sake.

## Tests

* `SakilaDatabaseTest` (unit-tagged where it needs no server, execution-tagged where it does): two
  classes' databases have different `current_database()` names; a row committed in one is absent in
  the other; a class database holds exactly `init.sql`'s seed (the five films, the seven
  `film_actor` pairs) whatever ran before it; `auxiliary` databases are dropped with the class; a
  database carrying the prefix and a dead run's token is dropped at start, and one held by a live
  run is not.
* `SakilaDatabaseGuardTest` fails on a planted `new PostgreSQLContainer` and a planted
  `System.getProperty("test.db.url")` outside the harness (its own negative case), and passes on
  the migrated tree.
* The 19 methods listed under "What is true today" pass with their filters removed. That they pass
  is the demonstration the item is complete, since each failed on CI with a sibling's row in it.
* Evidence at In Review: five consecutive full `mvn install -Plocal-db` runs green on one tree,
  and the module's wall clock before and after, so a reviewer can see what one container per JVM
  and 66 clones cost or save against 66 containers.

## Documentation

`docs/architecture/how-to/testing.adoc` gains "Where an execution test gets its database" beside
"Where a store-backed test gets its store": the harness, one database per class, auxiliary
databases, and the guard. The Execution tier section's "on the shared fixture" wording becomes
"on the Sakila fixture".

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

* **Keep patching per test.** The status quo: 34 filters and six items so far, and 19 exposed
  methods remain. Each patch protects one assertion from one writer, and the next writer or reader
  starts the count again.
* **A shared read-only database for readers, private databases for writers.** Cheaper by about 40
  clones, and `default_transaction_read_only` would turn an undeclared write into a deterministic
  failure. It fails correct tests: `session_connect`, which the plugin-level `<sessionState>` mount
  calls on every owned-path acquisition, takes `nextval('session_handle_seq')`, and PostgreSQL
  refuses `nextval()` in a read-only transaction (checked in the sandbox). A privilege-restricted
  role instead would stop readers connecting as `postgres` and change what the RLS tests mean. And
  the split needs every class to declare which it is, a marker nothing would bind.
* **JUnit resource locks**, `READ` for readers and `READ_WRITE` for writers. Every class has to
  declare one, a missing `READ` silently reintroduces the race, and `GraphQLQueryTest` is both, so
  the module's largest class would serialise against everything.
* **Every write inside a rolled-back transaction.** The owned-path classes commit through a
  `DataSource` per field, and some tests assert that a row persisted, so neither can roll back.
* **No class parallelism.** Removes the race at the cost of the parallelism the properties file
  measured as worth three cores, in the reactor's terminal module, where every second is build wall
  clock.

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
