---
id: R1007
title: "A second graphitron-model build in the same mvnd daemon fails jOOQ codegen with Table already exists"
status: Spec
bucket: dx
priority: 3
theme: tooling
depends-on: []
created: 2026-10-09
last-updated: 2026-10-09
---

# A second graphitron-model build in the same mvnd daemon fails jOOQ codegen with Table already exists

## Goal

Building `graphitron-model` twice in the same `mvnd` daemon works the way it does under plain `mvn`. Today the second build of the module on one daemon fails in jOOQ code generation with `Table "STORE_GRAPH" already exists`, because the in-memory H2 database that code generation reads the fact schema into outlives the build inside the daemon. CLAUDE.md tells web sessions to prefer `mvnd` and says every `mvn` command works verbatim under it; for this module that is not yet true. Nothing changes for consumers: this is the repo's own build.

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

## Reproduction

Reproduced on demand on 2026-10-09. The failure is deterministic: it fires on the second build of the module that lands on one daemon, never on the first.

- On a fresh daemon, `mvnd install -pl :graphitron-model -Plocal-db -DskipTests` succeeds; the same command again, confirmed by `mvnd --status` to have run on the same daemon (same id, its last-activity time advanced), fails with `Table "STORE_GRAPH" already exists`.
- `jooq-codegen-maven`'s `generate` runs once per build. The javadoc reference gate in the parent pom binds `javadoc-no-fork`, so no build re-enters `generate-sources`, and plain `mvn` (one JVM per build) never meets an earlier build's database.
- The green `mvnd` builds reported in `roadmap/changelog.md` are explained by daemon selection: `mvnd` starts a fresh daemon whenever it finds no compatible idle one, and a fresh daemon has no database to collide with. In the reproducing session the first scoped build started a new daemon even though the SessionStart warm-up daemon sat idle, so which builds share a daemon is not something a session controls by default.

## Implementation

One file, `graphitron-model/pom.xml`, the `generate-model-sources` execution of `jooq-codegen-maven`. Change the connection URL from

```
jdbc:h2:mem:graphitron-model-codegen;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM '...'
```

to an unnamed in-memory database:

```
jdbc:h2:mem:;INIT=RUNSCRIPT FROM '...'
```

An unnamed H2 in-memory database is private to the one connection that opened it and is dropped when that connection closes. That is exactly the lifetime the execution needs: jOOQ's `GenerationTool` opens one connection from the `<jdbc>` block, reads all metadata through it, and closes it at the end of the run, so the database is born and dies inside one `generate` invocation whatever JVM it runs in. Nothing else connects to it, so nothing needs the name.

Update the XML comment above the plugin, which already explains why the DDL runs on the generating connection (view column comments survive only that way), with one sentence on the lifetime: the database is unnamed so it lives exactly as long as the generating connection, which is what keeps a long-lived JVM such as an `mvnd` daemon from handing a later build an earlier build's tables. That sentence is the guard against someone reintroducing a name plus `DB_CLOSE_DELAY=-1`; no test can observe daemon reuse, so the comment is where the constraint lives.

If a future jOOQ version ever read metadata through a second connection, the unnamed database would be gone by then, codegen would emit an empty `PUBLIC` schema and the module's own sources would fail to compile against the missing `Tables` constants. The failure mode of the fix is loud, not silent.

No CLAUDE.md change: its claim that every `mvn` command works verbatim as `mvnd` becomes true for this module rather than needing a carve-out.

## Tests

No automated test: what fails is Maven daemon reuse across builds, which no test tier runs. Acceptance is the reproduction run on the fixed tree, recorded in the In Review commit message:

1. On a daemon that has already built the module from the **pre-fix** tree (so it still holds the old named database), run `mvnd install -pl :graphitron-model -Plocal-db -DskipTests` on the fixed tree twice, checking with `mvnd --status` that both runs used that daemon. Both succeed. Starting from the pre-fix state shows the fix needs no `mvnd --stop` from anyone pulling it, since the unnamed database never looks up the old name.
2. `diff -r` the generated `target/generated-sources/jooq` tree against the one the pre-fix URL produces: identical. This is the check that the view column comments the pom comment cares about all survive.
3. The verification build `mvn install -Plocal-db` stays green.

The spec author ran 1 and 2 against an uncommitted edit on 2026-10-09: both runs green on the pre-fix daemon, 1187 generated files identical to the pre-fix output.

## Other solutions we've considered

- **Keep the name, drop `DB_CLOSE_DELAY=-1`.** Also works on a clean daemon (three consecutive builds green, output identical), because the named database then closes with the last connection. It is worse on two counts: a daemon that already holds the old database (created with `DB_CLOSE_DELAY=-1`, so still alive) fails once more after the fix lands until someone runs `mvnd --stop`, and the name keeps implying that something else is meant to connect to it.
- **A build-unique name**, through a timestamp or similar property in the URL. Leaves one dead database per build alive in the daemon until it exits, and keeps `DB_CLOSE_DELAY=-1` for no reason.
- **`DROP ALL OBJECTS` before the init script.** Correct, but it works around the lifetime the URL asks for rather than asking for the right one.
