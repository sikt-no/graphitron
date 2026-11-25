---
id: R1209
title: "Execution tests hold a PostgreSQL connection per class until the test JVM exits"
status: Spec
bucket: bug
priority: 2
theme: testing
depends-on: []
created: 2026-11-25
last-updated: 2026-11-25
---

# Execution tests hold a PostgreSQL connection per class until the test JVM exits

## Goal

An execution test class in `graphitron-sakila-example` gives back every connection it opened when the class finishes, so the suite's connection use stays bounded by the classes running at once rather than growing by one per class. The release and trunk builds then pass against a PostgreSQL server at its default `max_connections` of 100, which is what both CI pipelines provision, and the setup a consumer copies from this module (`docs/manual/how-to/test-your-schema.adoc` points them at it) stops teaching the leak.

An *execution test* is one that runs generated code against a real PostgreSQL database. Its *fixture context* is the jOOQ `DSLContext` the class opens in `@BeforeAll` for its own setup, assertions and cleanup. On the escape-hatch path, where a caller hands graphitron its own `DSLContext` (`GraphQLQueryTest` passes its fixture context to `Graphitron.newExecutionInput`), it is also the connection each request runs on.

## Findings

The 10.0.0-RC59 GitLab release job (job 4393611) and the GitHub trunk build at `c514f01e0` failed in this module with `FATAL: sorry, too many clients already`. Both pipelines run `-Plocal-db` against a service container at the default limit of 100; an earlier commit raised the limit to 400 only on the Testcontainers path in `SakilaDatabase`, which the `test.db.url` path never reaches.

Measured on trunk `722a81883` against a private PostgreSQL with `log_connections` and `log_disconnections` on and each connection's `application_name` set to the class that opened it (an uncommitted instrumentation of `SakilaDatabase`):

- Peak 101 concurrent connections, 99 of them still open when the test JVM exited, all 1808 tests passing with the limit raised out of the way.
- 71 held by a static fixture field assigned from `SakilaDatabase.dsl()` in `@BeforeAll` and never closed.
- 21 held by a static fixture field assigned from `DSL.using(url, user, password)` with a URL from `SakilaDatabase.url()`, never closed.
- 7 held by the Quarkus smoke test's datasource pool, which is a pool and not a leak.
- The live count climbs to about 99 within the first 40 seconds and stays there, so the budget is spent by fixtures that outlive their class, not by a burst.
- With those 99 left out, the most connections live at once was 8. A fixed suite should therefore peak near 8, plus one fixture for each of the four classes running at once, plus the pool's 7: about 19.

`jOOQ`'s `DSL.using(String, String, String)` opens its JDBC connection eagerly; surefire runs the module in one JVM, so a static field keeps that connection until exit. A context nothing references is a different case: jOOQ's `DefaultCloseableConnectionProvider` closes its connection from `finalize()`, so the collector closes it eventually, which is why the inline contexts below did not show as held. Finalization is deprecated for removal and its timing is unbounded, so the plan does not lean on it.

The module's test sources declare 95 static fields of type `DSLContext` or `CloseableDSLContext`, and they reconcile with the log. 72 are assigned from `SakilaDatabase.dsl()`: the 71 above, plus `OutboxFailureCompanionTest.dsl`, which its `@AfterAll` closes by hand. 21 are the direct `DSL.using(url, user, password)` fields above. 2 (`ScatterSingleByIdxTest.DSL_CTX`, `ScatterLookupByIdxTest.DSL_CTX`) come from `DSL.using(SQLDialect.POSTGRES)`, which renders SQL and holds no connection. No class uses `@TestInstance(PER_CLASS)` and no instance field holds a context, so these fields are the whole population. A grep keyed on how a field is assigned found 88 of the 92 leaking classes; keyed on the declared type, the count is complete. The 14 static `DataSource` and `AgroalDataSource` fields hand out connections per call or are closed in their class's `@AfterAll`, and none appears among the held connections.

The consumer template in `test-your-schema.adoc` has the same leak in a different shape: its class opens `dsl` with `DSL.using(url, user, password)` and never closes it. On its container arm, `@AfterAll` stops the container and that ends the connection server-side; on its `test.db.url` arm nothing does. Its "Per-test connection leak" pitfall says holding `dsl` past class scope leaks, without saying that the class closes it.

Not part of this item: `TenantFanOutExecutionTest`'s hanging data source connects five seconds after the scatter stopped waiting, by which time its tenant database is gone, so this run could not tell whether the runtime closes a lease that arrives after a fan-out timed out.

## Implementation

### Each fixture context closes when its class finishes

Every static field that holds a connection-bearing context carries JUnit's `@AutoClose`:

```java
@AutoClose
static DSLContext dsl;

@BeforeAll
static void setUp() {
    dsl = SakilaDatabase.dsl();
}
```

Two properties make it fit, both checked against `junit-jupiter-engine` 6.1.3. It closes a static field in the class's after-all phase, after the class's own `@AfterAll` methods, so a class that deletes its rows in `@AfterAll` still has its connection there. And it calls `close()` on the field's runtime value, so a field declared `DSLContext` that holds the `CloseableDSLContext` `dsl()` returns closes without its declared type changing.

The 93 fields this covers, by class:

- The 72 assigned from `SakilaDatabase.dsl()`. `OutboxFailureCompanionTest` drops its hand-written `dsl.close()` for the annotation, so every fixture field closes the same way.
- The 21 direct fields, in `ConnectionLifecycleExecutionTest`, `ConvertedTenantRoutingExecutionTest`, `DevExecuteExecutionTest`, `DmlBulkMutationsExecutionTest`, `EscapeHatchSessionIdentityExecutionTest` (`superDsl`), `FetcherErrorResponseExecutionTest`, `LegacyNodeKeyExecutionTest`, `MutationScopeExecutionTest`, `MutationScopeMultiTenantExecutionTest`, `OutboxCompanionTest`, `OutboxExecutionTest`, `RoutineCarrierRlsExecutionTest` (`superDsl`), `SessionHookExecutionTest`, `SessionIdentityPoolIsolationExecutionTest`, `TenantDivinedRoutingExecutionTest`, `TenantFanOutExecutionTest`, `TenantRoutingExecutionTest`, `TenantScatterExecutionTest`, `TransactionProviderExecutionTest`, `VoucherAcceptExecutionTest` (`superDsl`) and `VoucherIssueExecutionTest`.

Where a direct site's arguments are exactly `SakilaDatabase.url()`, `user()` and `password()`, whether passed inline or through a local that holds them, the assignment becomes `SakilaDatabase.dsl()`. All 21 were of that shape when this was written; the implementer re-checks each before changing it, and a local such as `jdbcUrl` stays where other code in the class reads it. `OutboxFailureCompanionTest.mounted` opens its context the same direct way and moves to `dsl()` on the same check; its callers already close what it returns. The move is not what fixes the leak, since `@AutoClose` closes either kind, and no rule requires it: `testing.adoc` lets a test call `url()`, `user()` and `password()`. Its value is that `dsl()` becomes the one place a fixture context is opened, so the docs describe one call and a measurement like the one under Findings hooks into one method.

`FacetBucketsExecutionTest` passes `SakilaDatabase.dsl()` inline to `ConnectionHelper.facetBuckets` three times and never closes the result. Each call moves into a try-with-resources block rather than waiting on finalization.

Unchanged: the tenant-database contexts, which every class already opens in try-with-resources; the `DataSource` and `AgroalDataSource` fields; the two dialect-only `DSL_CTX` fields.

### An enforcement test holds the rule

This is the recommended answer to an open question, what fails when a class holds a connection past its run, which the architect left to Alf and Filip; Other solutions below names the alternative.

A new `FixtureCloseEnforcementTest` in `no.sikt.graphitron.rewrite.test.internal`: every static field of the module's test classes whose type is assignable to `DSLContext` carries `@AutoClose`, unless, once its class is initialized, it holds a value that is not `AutoCloseable`. It is reflective, sharing the walk over `target/test-classes` that `QuarkusTestLockEnforcementTest` and `TierAnnotationEnforcementTest` use, because "this field carries this annotation" is the question those two already answer by reflection, and because the declared type, which is what makes the rule complete over the 93 fields where the assignment grep was not, is what `Field.getType()` reads. Two differences from that walk:

- It includes nested classes, which the other two skip, since a nested class can declare a static field too. Its own planted cases are nested classes of the test, and it leaves them out the way `SakilaDatabaseGuardTest` leaves out its own file.
- The exemption reads a value, since reflection cannot see an initializer. A fixture field is `null` until `@BeforeAll` runs, so it has to carry the annotation; the two dialect-only `DSL_CTX` fields hold a plain `DSLContext` from their initializers, which is not `AutoCloseable`, and `@AutoClose` would refuse them anyway for having no `close()`.

The planted cases: an unannotated static `DSLContext` field is caught, an annotated one passes, a dialect-only one passes.

What it cannot see: a connection held in a static field of another type (`Connection`, `DataSource`, a pool), or reached through a helper class. Those are closed by hand today and stay that way; the test keeps the common shape honest, not every shape.

The plan's answer to the other question the architect left open, who owns a test connection's lifetime, is the class that opens it, stated on the field that holds it, where both a reader and a consumer copying the class see it. A ruling from Alf or Filip on either question enters `docs/architecture/how-to/testing.adoc` as its own `docs` commit the day it is given, as the workflow has every ruling do; this item's edits to that page then build on it.

### Both database paths run at the default limit

Once the measurement under Tests confirms the fixed suite's peak, `SakilaDatabase.start()` drops its `withCommand` call and the comment that justifies it. `-c fsync=off` is the container's own default command, which the call only restates once `max_connections` goes; `TestDatabase` in `graphitron` starts the same image without one. A build on the Testcontainers path then runs at the same limit CI does.

### Docs

- `docs/manual/how-to/test-your-schema.adoc`: the template's `dsl` field carries `@AutoClose`, the same idiom as the module's classes, which the page also tells consumers to copy. It closes after the template's `@AfterAll` has stopped the container, and that is silent: jOOQ closes through `JDBCUtils.safeClose`, which logs and swallows, and pgjdbc discards an `IOException` on close. The "Per-test connection leak" pitfall says what holds: a `DSLContext` built from a URL holds one open connection until it is closed, a class that never closes it holds that connection until the JVM exits, and a suite of such classes runs the server out of connections.
- `docs/architecture/how-to/testing.adoc`, under "Where an execution test gets its database": `dsl()` hands the caller a connection the caller closes, with `@AutoClose` on a static field or try-with-resources inline, and `FixtureCloseEnforcementTest` holds the field rule.
- `graphitron-sakila-example/src/test/resources/junit-platform.properties`: the comment that each class builds its own `DSLContext` in `@BeforeAll` adds that it closes it when the class finishes, so the connections held at once follow the classes running at once.
- `SakilaDatabase.dsl()`'s javadoc: the caller closes what it returns.

## Tests

`FixtureCloseEnforcementTest` carries the rule and its planted cases, and is the named test that fails when a fixture field stops closing.

The goal's numbers are shown by measurement, once by the implementer and again by the Done reviewer. The `application_name` tagging that attributed connections to classes is not needed for it; connection logging is enough.

```bash
docker run -d --name r1209-pg -p 55432:5432 -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=rewrite_test \
  postgres:18-alpine -c fsync=off -c max_connections=1000 -c log_connections=on -c log_disconnections=on
# once the log says "database system is ready to accept connections" for the second time:
psql postgresql://postgres:postgres@localhost:55432/rewrite_test -f graphitron-sakila-db/src/main/resources/init.sql
while sleep 0.2; do psql -tAc "select count(*) from pg_stat_activity where backend_type = 'client backend'" \
  postgresql://postgres:postgres@localhost:55432/rewrite_test; done > peak.txt &
mvn test -pl :graphitron-sakila-example -Plocal-db -Dtest.db.url=jdbc:postgresql://localhost:55432/rewrite_test
kill %1; sort -n peak.txt | tail -1                      # peak, less the poller's own connection
docker logs r1209-pg 2>&1 | grep 'disconnection:' | cut -c1-19 | uniq -c | tail -3   # the exit burst: the last line or two
docker rm -f r1209-pg
```

On a private server with a full install behind it, the peak should come in near the 19 estimated under Findings, against 101 before, and the exit burst should be the Quarkus pool's 7 disconnections, against 99 before. A run against a server other sessions share counts their connections too, so it proves nothing.

After landing, the trunk build on GitHub and the next release job on GitLab pass at the default limit, which is the goal's own evidence.

## Other solutions we've considered

- **Raise the limit in CI.** The GitLab service can take a `max_connections` command; GitHub's service containers take none. Either way it hides the leak where it is caught, and the architect pointed out that it removes the only alarm. The 400 on the Testcontainers path was this move, made on one path.
- **One shared pool for every fixture context.** It bounds connections without closing anything, but the escape-hatch path (where the caller hands graphitron its own `DSLContext`) is documented as holding one connection per request, and a pool changes what those tests exercise. Raised by the architect.
- **A harness-owned extension that closes whatever `dsl()` handed out.** It fixes every class without touching one, but the lifetime moves out of the class a consumer copies, so the template teaches nothing about it. Raised by the architect.
- **A context over a data source, the shape `graphitron`'s own `TestDatabase` uses.** It opens a connection per statement and holds none, so there is nothing to close, which is why that module never had this leak. It does not carry over: on the escape-hatch path the fixture context is the connection a request runs on, and the session-state classes (`SessionHookExecutionTest`, `EscapeHatchSessionIdentityExecutionTest`) need a request's statements on one connection.
- **A source-text rule in `SakilaDatabaseGuardTest`.** That guard reads source because the calls it bans leave no trace in a compiled class; a field's type and annotations do, and the module already checks annotations by reflection.
- **A runtime check of live connections instead of the enforcement test.** This was the author's first lean, since it would be exact and would also catch a connection the runtime fails to return. Two costs decided against it for this item. A per-class check has to run after the class's fields close, but JUnit runs the after-all callbacks of a later-registered extension first, and `AutoCloseExtension` is a default extension registered before any autodetected one, so the check would run before the close unless it did the closing itself, which is the harness-owned option above. A suite-wide count on the `test.db.url` path shares the server with other clients (the `-T 1C` reactor in CI, a developer's other builds), so it needs a key for this JVM's connections that no test can overwrite, and tests set `application_name` themselves. The runtime half of such a check belongs with the fan-out question under Findings.
- **A context per test method.** `@BeforeEach` and `@AfterEach` also bound the count, but open a connection per test, about 1800 instead of about 95, and settle no lifetime question that `@AutoClose` leaves open.

## Reviewer findings

### Round 1 (Spec → Ready review, session_1c60fba0-d82e-4fcb-a91d-786381dcef68, 2026-11-25)

Verdict: request revisions. Question 1 holds; question 2 fails on one point.

Question 1 holds. When this lands, an execution test class in `graphitron-sakila-example` closes its fixture connection when it finishes, so the suite peaks near 19 connections instead of 101, both CI pipelines pass at PostgreSQL's default limit, the Testcontainers path stops asking for 400, and the template a consumer copies from `test-your-schema.adoc` closes its `dsl` instead of holding it to JVM exit. The claims about the tree check out: the field census (95 = 72 + 21 + 2, and the 21 direct classes by name), `SakilaDatabase`'s accessors and its `withCommand`, the JUnit 6.1.3 and jOOQ 3.20.11 behaviour the plan leans on, and every class, docs section and properties comment the plan names.

**The `DSL_CTX` exemption rests on a shape the two fields do not have.** The plan says `ScatterSingleByIdxTest.DSL_CTX` and `ScatterLookupByIdxTest.DSL_CTX` "hold a plain `DSLContext` from their initializers", lists them under Unchanged, and has `FixtureCloseEnforcementTest` exempt a field that holds a non-`AutoCloseable` value once its class is initialized. On trunk both are declared `private static DSLContext DSL_CTX;` with no initializer, and are assigned `DSL.using(SQLDialect.POSTGRES)` inside `@BeforeAll`. So after class initialization they are `null`, the same as a fixture field, and:

- the rule as written requires `@AutoClose` on both;
- `@AutoClose` on either throws `ExtensionConfigurationException` in the class's after-all, because `AutoCloseExtension` looks up `close()` on the runtime value and `DefaultDSLContext` has none;
- the planted "dialect-only one passes" case, written to the shape the plan describes, passes while the real run fails on these two classes, so the planted cases would not catch the mismatch.

An implementer cannot satisfy the rule and leave these classes unchanged, and has to choose how to reconcile them. What would satisfy this: the plan says which shape the two fields end in and makes the exemption, the Unchanged list and the planted dialect-only case agree with it. Either arm is the author's to pick: move the two assignments to field initializers (and take the two classes off Unchanged), or restate the exemption so it covers a dialect-only field assigned in `@BeforeAll`.
