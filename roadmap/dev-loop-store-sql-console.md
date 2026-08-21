---
id: R772
title: "The dev loop holds a live fact store no developer can query"
status: Spec
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

When this item lands, a developer sets one flag, and the dev session prints a `psql` line they can
paste into another terminal. They get read-only SQL against the rows the running session is answering
from, live as rounds land, with no change to how the session itself reads or writes the store.

## What was measured

Feasibility was measured against H2 2.4.240, psql 16 and pgjdbc 42.7.10 in a web sandbox, not
reasoned about. The first four findings framed the fork; the last five settled it.

- **The Postgres wire protocol works.** H2 ships `org.h2.server.pg.PgServer`, and psql runs real SQL
  through it: `SELECT`, aggregates, views, `information_schema`. A `-key <name> mem:<name>` mapping
  points it at a database whose name the client cannot know, which is the mechanism that fits a store
  holding a private url.
- **It requires `MODE=PostgreSQL` in the creation url.** Without it the first psql connection dies
  with `Schema "PG_CATALOG" not found`: H2's pg_catalog emulation exists only for a database created
  in PostgreSQL mode. `SET MODE PostgreSQL` on the live connection afterwards does **not** retro-fit
  it. `DATABASE_TO_LOWER` is not needed; identifiers stay uppercase and unquoted lookups resolve.
- **The real fact schema boots in that mode.** All 2047 statements of `graphitron-model.sql` execute
  clean under `MODE=PostgreSQL`, and psql then reads the seeded `meta_family` rows and lists the 215
  `PUBLIC` relations through `information_schema`.
- **psql's backslash commands do not work.** `\dt`, `\dv` and `\d <table>` fail against H2's partial
  pg_catalog (`Column "c.relowner" not found`, and psql 16's `OPERATOR(pg_catalog.~)` is a syntax
  error to H2's parser). Introspection goes through `information_schema`, which works.
- **A linked console database gets psql onto the store without moving the store's mode.** A second
  in-memory H2 database created `MODE=PostgreSQL`, holding one `CREATE LINKED TABLE ... READONLY` per
  store relation, links all 216 relations in 0.65 to 1.8 s and serves psql from them. Views link and
  read like tables (`meta_family`, itself a view, reads through fine).
- **Linked reads are live.** A row inserted into the store *after* the links were created is visible
  to psql on the next query, so the console shows the session's current rows rather than a snapshot
  taken when it opened.
- **Linked reads cost the store one connection, not 216.** Store-side
  `information_schema.sessions` reports 2 after linking 216 relations (the store's own connection plus
  one shared link connection): H2 pools link connections per url and user through
  `org.h2.table.TableLinkConnection`.
- **`READONLY` is enforced at the console.** An `INSERT` through psql fails with `The database is
  read only`, so the debug door cannot corrupt the rows the session is reasoning from.
- **pgjdbc cannot connect at all.** The driver's startup queries include `SET extra_float_digits = 2`,
  which H2 rejects as a syntax error, and no combination of `assumeMinServerVersion`,
  `preferQueryMode=simple` or `options=-c ...` gets past it. psql (libpq) works; JDBC clients that go
  through pgjdbc (DBeaver, IntelliJ's Postgres driver) do not. This is why the protocol pin below
  shells out to `psql` instead of opening a driver connection.

## The design

**A read-only PostgreSQL-mode console database, linked to the live store, exposed on loopback.** The
store keeps its own mode, so nothing about how the generator queries it or how jOOQ's live-metadata
codegen reads it changes: the console is a second database that reads through to the first.

The alternative was creating the session's store itself with `MODE=PostgreSQL`. It is rejected here
rather than left open: that mode change lands under every generator query and under the codegen
bootstrap that reads its model off exactly this boot, so an opt-in debug flag would silently give the
session different SQL semantics from the build. Paying a second in-memory database and one shared
link connection to keep that blast radius at zero is the better trade, and the measurements above show
the cost is a fraction of a second at console start.

**The store mints the console, for the same reason it mints readers.** The link statements need the
store's own url, which is private by design, so a console assembled outside the class would have to
reconstruct a stamped path or a UUID name and would fail exactly the way `reader()`'s javadoc
describes: opening a different database, looking fine, answering from nothing. `console()` is a
sibling of `reader()` on `GraphitronModelStore`, and the url never leaves the class.

**The console is a debug affordance, so it degrades rather than fails.** A store whose console cannot
open (port taken, link rejected) must not fail the dev session: `console()` throws and `DevMojo`
catches, warns with the reason, and continues without a console. This mirrors `openAt`'s posture that
cache trouble costs warmth and never correctness.

## Implementation

`graphitron-model`, `no.sikt.graphitron.model.boot`:

- New `StoreConsole`, `AutoCloseable`, the handle a caller holds: the H2 `Server`, the console
  database's own connection, the port, and the relation count for the log line. `close()` stops the
  server and shuts the console database down (`SHUTDOWN`, the `dropOnClose` arm of
  `GraphitronModelStore.close`, since the console holds `DB_CLOSE_DELAY=-1`), and leaves the store
  untouched.
- `GraphitronModelStore.console(int port)` mints it, beside `reader()`:
  1. Read the relation names off this store's own connection
     (`information_schema.tables` where `table_schema = 'PUBLIC'`), the same raw-JDBC shape
     `stampMatches` uses rather than a jOOQ query, so the bootstrap keeps touching no generated class.
  2. Open `jdbc:h2:mem:graphitron-console-<UUID>;DB_CLOSE_DELAY=-1;MODE=PostgreSQL`, the private-UUID
     naming of `open()` for the same collision reason.
  3. One `CREATE LINKED TABLE <relation>(NULL, '<this.url>', <user>, <password>, 'PUBLIC.<relation>')
     READONLY` per relation.
  4. `Server.createPgServer("-pgPort", <port>, "-key", <console name>, "mem:<console name>")`, with
     neither `-pgAllowOthers` nor `-baseDir`: H2 binds loopback only when `allowOthers` is off, and
     that is the whole access control this door gets.
  5. Any failure closes what it opened and throws `IllegalStateException` naming the port and the
     reason.
- Javadoc on both members carries the two measured constraints a future reader will otherwise
  rediscover: PG mode is a creation-time property (hence a second database rather than a flag on this
  one), and pgjdbc cannot speak to H2's PG server (hence psql, not a driver).

`graphitron-maven-plugin`, `no.sikt.graphitron.rewrite.maven`:

- New `StoreConsoleBinding` for a `<storeConsole>` POM block, in the shape of `DevDatabaseBinding`:
  `<enabled>`, `<port>`, with `GRAPHITRON_DEV_STORE_CONSOLE` and `GRAPHITRON_DEV_STORE_CONSOLE_PORT`
  overriding the POM on each field. Default port 5435, clear of a real local PostgreSQL on 5432.
- `DevMojo`: a `@Parameter StoreConsoleBinding storeConsole` field, a package-private
  `resolveStoreConsole()` reconciler mirroring `resolveDevDatabase()` (env wins per field, absent
  means off, no console and no port bound), and a `StoreConsole storeConsoleHandle` field left
  package-private for the same reason `sessionStore` is.
- Start it in `execute()` immediately after `Materializations.refreshAll(sessionStore.dsl())` and
  before the watchers, so the linked relations include the refreshed materializations and the console
  is up before the first round lands. Log the paste-ready line, for example
  `graphitron:dev: fact-store console on 127.0.0.1:5435 (216 relations, read-only). psql -h localhost
  -p 5435 -U <user> -d <db>`.
- Close it in `cleanup()` **before** `lspStore`, `mcpStore` and `sessionStore`: the link connection
  points at the store, so the console goes first.

## User documentation (first-client check)

`docs/manual/reference/mojo-configuration.adoc`, a new row beside `devDatabase`:

> `storeConsole` / `StoreConsoleBinding` / (none): Read-only SQL console onto the dev session's own
> fact store (`dev` goal only). `<enabled>` opens it, `<port>` moves it off the default 5435;
> `GRAPHITRON_DEV_STORE_CONSOLE` and `GRAPHITRON_DEV_STORE_CONSOLE_PORT` override the POM. The
> console binds `127.0.0.1` only, serves reads, and refuses writes. Absent or disabled, no port is
> bound and the session is unchanged.

`docs/manual/how-to/dev-loop.adoc`, a new section "Query the fact store while the session runs":

> Turn the console on, and `graphitron:dev` prints a connect line at start-up:
>
> ```bash
> GRAPHITRON_DEV_STORE_CONSOLE=true mvn graphitron:dev
> psql -h localhost -p 5435 -U <user> -d <db>
> ```
>
> What you get is the session's live rows, read-only: a query after a save sees that round's facts.
> Use `information_schema.tables` and `information_schema.columns` to find your way around rather than
> psql's `\d` commands, which the server does not implement. Writes are refused by design; the
> session owns those rows.

If either draft does not read simply at implementation time, the design is wrong and changes first.

## Tests

`graphitron-model` unit tier, `StoreConsoleTest`, over a booted in-memory store:

- Every `PUBLIC` relation of the store is linked and queryable through the console (count parity
  against the store's own `information_schema.tables`), and a view relation returns rows, since most
  of the fact schema is views.
- Liveness: a row written to the store after the console opened is visible through the console.
- Read-only: an `INSERT` through the console fails, and the store's row count is unchanged.
- Connection sharing: store-side `information_schema.sessions` stays at 2 after linking, the claim
  that N links cost one connection and the one that would regress silently.
- The store's own mode is untouched (read `MODE` back off the store's settings; confirm the exact
  relation name at implementation time).
- `close()` shuts the console database down and frees the port: a second console opens on the same
  port afterwards.

`graphitron-model`, `StoreConsolePsqlTest`, the protocol pin, guarded by an assumption on the `psql`
binary so a contributor without it skips and CI (which already uses `psql` in `rewrite-build.yml`)
runs it: shell `psql -c "select ..."` at an ephemeral port and assert the returned row text. Its
javadoc records that pgjdbc cannot replace the shell-out, with the `SET extra_float_digits` reason, so
nobody swaps it for a driver connection and concludes the console is broken.

`graphitron-maven-plugin`, `DevMojoTest`, which already injects an in-memory store:

- Default: no console, no port bound, no log line.
- Enabled: the console opens against the injected store and the log line carries the psql command.
- `cleanup()` closes the console, and closes it before the store.
- A console that fails to open degrades to a warning naming the reason, and the session continues.

## Out of scope

- Putting the session's store in `MODE=PostgreSQL`. Rejected above with the measurement behind it.
- A door for JDBC clients. pgjdbc cannot reach this console, so DBeaver and IntelliJ need a separate
  H2 TCP server on the store itself, which is read-write and a different security posture. File it
  separately if the demand shows up.
- Write access, non-loopback binding, and any authentication beyond the H2 user the store already
  carries.
- Anything MCP-facing. The agent-side surface is the existing tools; this item is for a human at a
  terminal.

## Open questions

- Should `<port>` accept `0` for an ephemeral port named in the log line? It removes the collision
  question for developers running several sessions, at the cost of a port that changes each start.
