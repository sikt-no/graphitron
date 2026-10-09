---
id: R1007
title: "A second graphitron-model build in the same mvnd daemon fails jOOQ codegen with Table already exists"
status: Backlog
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-10-09
last-updated: 2026-10-09
---

# A second graphitron-model build in the same mvnd daemon fails jOOQ codegen with Table already exists

## Goal

Building `graphitron-model` twice in the same `mvnd` daemon works the way it does under plain `mvn`. Today the second build can fail in jOOQ code generation with `Table "STORE_GRAPH" already exists`, because the in-memory H2 database that code generation reads the fact schema into outlives the build inside the daemon. CLAUDE.md tells web sessions to prefer `mvnd` and says every `mvn` command works verbatim under it; for this module that is not yet true. Nothing changes for consumers: this is the repo's own build.

## Evidence

`graphitron-model` generates jOOQ classes for the fact store (the H2 schema in `graphitron-model.sql`) through the `generate-model-sources` execution of `jooq-codegen-maven`, pointed at this connection URL in `graphitron-model/pom.xml`:

```
jdbc:h2:mem:graphitron-model-codegen;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM '.../graphitron-model.sql'
```

`DB_CLOSE_DELAY=-1` keeps a named in-memory database alive until the JVM exits. Plain `mvn` exits after every build, so each build meets a fresh database. `mvnd` runs builds in a long-lived daemon JVM, and caches the classloaders of non-SNAPSHOT plugins between builds, so a later build can meet the database the earlier one created. `INIT=RUNSCRIPT` then runs the schema script again against tables that already exist, and the first `CREATE TABLE` fails.

Observed on 2026-10-08 in a web session, after the SessionStart warm-up build had already run through `mvnd`:

- `mvnd install -pl :graphitron-model -Plocal-db -DskipTests` failed with the error above, twice, the second time with `clean`. `clean` does not help: the stale state is in the daemon's memory, not under `target/`.
- The same command under plain `mvn` succeeded, and the session used plain `mvn` for that module from then on.

`jdbc:h2:mem` appears in no other pom in the reactor.

## Open question

What exactly triggers it. Several Done reviews in `roadmap/changelog.md` report full `mvnd install -Plocal-db` builds green, so not every `mvnd` build that reaches this execution fails. The working hypothesis is that it bites only when the build lands on a daemon that has already run this execution, since `mvnd` may start a fresh daemon when the warm one is busy. The first step of the item is to reproduce it on demand (two scoped builds of the module back to back on one daemon, `mvnd --status` to confirm which) before choosing a fix, so the fix can be shown to remove the failure rather than to coincide with a cold daemon.

## Candidate fixes

To choose between at Spec:

1. Drop `DB_CLOSE_DELAY=-1`, if the code generator holds one connection open for its whole run, so the database dies with that connection. The cleanest if it holds; whether it does is a property of `jooq-codegen-maven` to check, not to assume.
2. Make the database name unique per build (a build-unique property in the URL), so a later build never meets an earlier one's database. Leaves one dead database per build in a long-lived daemon until it exits.
3. Prefix the init script with `DROP ALL OBJECTS`, so a reused database is emptied before the schema runs. Correct, but it reads as working around state the URL asked for.

Done looks like: two consecutive `mvnd install -pl :graphitron-model -Plocal-db -DskipTests` runs on the same daemon both succeed, and the full `mvn install -Plocal-db` stays green.
