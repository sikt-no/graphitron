---
id: R772
title: "The dev loop holds a live fact store no developer can query"
status: Backlog
bucket: dx
priority: 2
theme: tooling
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# The dev loop holds a live fact store no developer can query

A `graphitron:dev` session opens one fact store and keeps it open for the whole session: `DevMojo`
holds a `GraphitronModelStore` handle, refreshes it on every round, and mints readers off it for the
language server and the MCP tools. That store is where every answer the session gives comes from, and
a developer debugging a wrong answer cannot look at it. The store's JDBC url is private on purpose
(`GraphitronModelStore.reader()` explains why: an in-memory name carries a UUID nothing outside the
class can reproduce, and a caller recomputing the stamped file path would be one edit away from
booting an empty store and reporting it as a schema with no facts), so there is no url to hand a SQL
client either. The whole debugging surface today is the MCP tools' fixed queries plus whatever a test
can be written to assert; "why does `graphql_directive` have no row for this application" is a
question with 215 relations behind it and no way to ask it.

What this item wants is an opt-in, loopback-only SQL console onto the session's own live store, so a
developer can attach a client mid-session, run ad-hoc SQL against the rows the session is actually
answering from, and watch them change as rounds land.

## What was measured

Feasibility was checked directly against H2 2.4.240 and psql 16 in a web sandbox, not reasoned about.

- **The Postgres wire protocol works.** H2 ships `org.h2.server.pg.PgServer`, and psql runs real SQL
  through it: `SELECT`, aggregates, views, `information_schema`. A `-key store mem:<name>` mapping is
  what points it at a database whose name the client cannot know, which is the mechanism that fits a
  store holding a private url.
- **It requires `MODE=PostgreSQL` in the creation url.** Without it, the first psql connection dies
  with `Schema "PG_CATALOG" not found`: H2's pg_catalog emulation exists only for a database created
  in PostgreSQL mode. `SET MODE PostgreSQL` on the live connection afterwards does **not** retro-fit
  it (verified: same failure). `DATABASE_TO_LOWER` is not needed; identifiers stay uppercase and
  unquoted lookups still resolve.
- **The real fact schema boots in that mode.** All 2047 statements of `graphitron-model.sql` execute
  clean under `MODE=PostgreSQL`, and psql then reads the seeded `meta_family` rows and lists the 215
  `PUBLIC` relations through `information_schema`.
- **psql's backslash commands do not work.** `\dt`, `\dv` and `\d <table>` fail against H2's partial
  pg_catalog (`Column "c.relowner" not found`, and psql 16's `OPERATOR(pg_catalog.~)` is a syntax
  error to H2's parser). Introspection has to go through `information_schema`, which works fine.

## The fork this leaves

The mode requirement is the whole design question, and it is worth settling in Spec rather than here.

Requiring `MODE=PostgreSQL` of the store the session actually uses changes H2 semantics under every
generator query and under jOOQ's live-metadata codegen, which reads its model off exactly this
bootstrap. That is a reactor-wide blast radius bought for a debug affordance. Confining the mode to a
debug store instead (the mode joining the stamp segment, so a PG-mode store is a different cache
directory) keeps the build's store untouched at the price of a cold boot when the flag flips, and of
a console attached to a store that is not byte-for-byte the one the build warmed.

The cheaper shape gives up psql specifically: `Server.createTcpServer` over the session's existing
store needs no mode change at all, works on the warm file store, speaks the same H2 SQL the generator
runs, and is reachable from the H2 Shell, DBeaver and IntelliJ. Same-process is a hard constraint
either way, since H2 hands a second connection off the database this process holds and would refuse a
separate one.

Whatever shape wins is opt-in and bound to loopback, with neither `-pgAllowOthers` nor
`-tcpAllowOthers`: this is an unrestricted read/write SQL surface onto a developer's workspace.
