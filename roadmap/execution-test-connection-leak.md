---
id: R1209
title: "Execution tests hold a PostgreSQL connection per class until the test JVM exits"
status: Backlog
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

## Findings

The 10.0.0-RC59 GitLab release job (job 4393611) and the GitHub trunk build at `c514f01e0` failed in this module with `FATAL: sorry, too many clients already`. Both pipelines run `-Plocal-db` against a service container at the default limit of 100; an earlier commit raised the limit to 400 only on the Testcontainers path in `SakilaDatabase`, which the `test.db.url` path never reaches.

Measured on trunk `722a81883` against a private PostgreSQL with `log_connections` and `log_disconnections` on and each connection's `application_name` set to the class that opened it (an uncommitted instrumentation of `SakilaDatabase`):

- Peak 101 concurrent connections, 99 of them still open when the test JVM exited, all 1808 tests passing with the limit raised out of the way.
- 71 held by a `static DSLContext` field assigned from `SakilaDatabase.dsl()` in `@BeforeAll` and never closed.
- 21 held by a static field assigned from `DSL.using(url, user, password)` with a URL from `SakilaDatabase.url()`, never closed.
- 7 held by the Quarkus smoke test's datasource pool, which is a pool and not a leak.
- The live count climbs to about 99 within the first 40 seconds and stays there, so the budget is spent by fixtures that outlive their class, not by a burst.

`jOOQ`'s `DSL.using(String, String, String)` opens its JDBC connection eagerly; surefire runs the module in one JVM, so a static field keeps that connection until exit. A source grep for the pattern found 88 of the 92 classes; the log found the rest.

Not part of this item: `TenantFanOutExecutionTest`'s hanging data source connects five seconds after the scatter stopped waiting, by which time its tenant database is gone, so this run could not tell whether the runtime closes a lease that arrives after a fan-out timed out.
