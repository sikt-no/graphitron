---
id: R772
title: "The dev loop holds a live fact store no developer can query"
status: Ready
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
from, live as rounds land, with no change to how the session itself reads or writes the store. An agent
on the session's MCP server reads the same coordinates as structured fields from a `store.console`
tool, so it connects without scraping them out of log text.

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
- **`READONLY` stops DML, and that is all it stops.** `INSERT`, `UPDATE` and `DELETE` through a linked
  relation all fail with `The database is read only`, which is the accidental-`UPDATE` guard worth
  having. DDL on the console database does not fail, and the connecting user has to be an H2 admin
  anyway (`PgServerThread` issues `SET DEFAULT_NULL_ORDERING HIGH` at connect, which requires admin,
  so a rights-limited user is refused before it runs anything), so a client that insists can write. For
  a dev tool that is fine; the item just does not claim otherwise.
- **H2's "only local connections" is a peer check, not a bind restriction.** With `-pgAllowOthers`
  omitted, `getStatus()` reports "only local connections" and a psql session arriving on this host's
  non-loopback address is dropped, but a plain TCP connect to that address is **accepted**: the
  listener is on all interfaces and H2 rejects the session afterwards. The port is open to the network
  even though no data is served through it.
- **`h2.bindAddress` does restrict the bind, and only if it is set before H2 loads.** With
  `h2.bindAddress=127.0.0.1` set before any H2 class initialises (or passed as `-D`), a TCP connect to
  the non-loopback address is refused outright while loopback keeps working. Set *after* H2 has loaded
  it has no effect at all and the listener stays on every interface: `SysProperties.BIND_ADDRESS` is
  read once at class initialisation. This is the finding the design below is built around, because the
  dev goal cannot assume it runs before H2 loads (`ModelCodegenDriver` opens a store during ordinary
  builds in the same JVM).
- **An ephemeral port is fully supported and self-reporting.** `-pgPort 0` binds a free port, and
  `getPort()`, `getURL()` and `getStatus()` all report the port actually bound (measured: 32973), so
  the session can name it in a log line without pre-binding a socket to guess a free number. H2's
  status string reads `PG server running at pg://localhost:<port> (only local connections)`, which
  reads like a bind claim and is not one: see the peer-check finding above.
- **A printed connect command runs verbatim.** The exact line the session would print,
  `PGPASSWORD=<password> psql -h localhost -p <port> -U graphitron -d store`, was pasted back into a
  shell unchanged and returned rows, and a wrong password or user is refused at connect. An empty
  password is not an option: libpq refuses to send one and prompts instead, which is why the console
  carries a fixed non-empty password rather than none.
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

**This is a dev tool, and it has exactly one hard requirement: the listener is on `127.0.0.1` and
nowhere else.** Everything else here is ergonomics. Credentials are fixed and printed (`graphitron` /
`graphitron`), not minted secrets: an empty password is worse than a fixed one, since libpq refuses to
send one and prompts instead, which breaks the paste-ready line for no gain. The `READONLY` links stay
because they cost nothing and catch a mistyped `UPDATE`, and the docs say plainly that they are not a
sandbox rather than implying a guarantee the mechanism cannot make.

**The bind is checked, not assumed.** Because `h2.bindAddress` is only honoured when set before H2
loads, and the dev goal can run in a JVM that already loaded H2, setting the property is necessary but
not sufficient. So the console does both:

1. Set `h2.bindAddress` to `127.0.0.1` as early as the goal can reach, at the top of `execute()`
   before the session store opens, which is enough in an ordinary dev JVM.
2. After the server starts, prove it: connect to each non-loopback IPv4 address of this host in turn
   and require every one to refuse. If any accepts, the property was too late to take effect, so stop
   the server, log that the console is refused because the bind could not be confined, and continue the
   session without a console.

A listener that cannot prove it is loopback-only does not stay up. That turns an unverifiable claim
into a checked invariant, and the failure mode is a missing debug tool rather than an open port.

**The console is a debug affordance, so it degrades rather than fails.** A store whose console cannot
open (port taken, link rejected) must not fail the dev session: `console()` throws and `DevMojo`
catches, warns with the reason, and continues without a console. This mirrors `openAt`'s posture that
cache trouble costs warmth and never correctness.

## Implementation

`graphitron-model`, `no.sikt.graphitron.model.boot`:

- New `StoreConsole`, `AutoCloseable`, the handle a caller holds: the H2 `Server`, the console
  database's own connection, and everything the connect line is made of, since a caller that had to
  assemble that line itself is a caller that can assemble it wrong. `port()` (the port bound),
  `user()`, `password()`, `database()`, `relationCount()`, and `connectCommand()`, which returns the
  paste-ready `PGPASSWORD=<secret> psql -h localhost -p <port> -U <user> -d <database>`. `close()`
  stops the server and shuts the console database down (`SHUTDOWN`, the `dropOnClose` arm of
  `GraphitronModelStore.close`, since the console holds `DB_CLOSE_DELAY=-1`), and leaves the store
  untouched.
- The console owns its own credentials rather than borrowing the store's, and they are fixed:
  user `graphitron`, password `graphitron`. Fixed rather than minted because the listener is confined
  to loopback and a dev tool gains nothing from a secret, and non-empty because libpq will not send an
  empty password (it prompts instead), which would break the paste-ready line.
- `GraphitronModelStore.console(int port)` mints it, beside `reader()`:
  1. Read the relation names off this store's own connection
     (`information_schema.tables` where `table_schema = 'PUBLIC'`), the same raw-JDBC shape
     `stampMatches` uses rather than a jOOQ query, so the bootstrap keeps touching no generated class.
  2. Open `jdbc:h2:mem:graphitron-console-<UUID>;DB_CLOSE_DELAY=-1;MODE=PostgreSQL` under the minted
     credentials, the private-UUID naming of `open()` for the same collision reason. The name psql
     passes as `-d` is the `-key` alias below, not this one, so the UUID stays out of the printed line.
  3. One `CREATE LINKED TABLE <relation>(NULL, '<this.url>', <user>, <password>, 'PUBLIC.<relation>')
     READONLY` per relation.
  4. `Server.createPgServer("-pgPort", <port>, "-key", <console name>, "mem:<console name>")`, with
     neither `-pgAllowOthers` nor `-baseDir`.
  5. Verify the bind: every non-loopback IPv4 address of this host must refuse a TCP connect to the
     port, and loopback must accept one. A host with no non-loopback address passes trivially.
  6. Any failure, the bind check included, closes what it opened and throws `IllegalStateException`
     naming the port and the reason.
- `console(0)` is the ordinary call, not a special case: `StoreConsole.port()` reports
  `Server.getPort()` (the port bound, never the port asked for), so a caller that passed 0 learns the
  real port from the handle and a caller that pinned one gets the same number back. Nothing in the
  class treats 0 as a sentinel.
- Javadoc on both members carries the three measured constraints a future reader will otherwise
  rediscover: PG mode is a creation-time property (hence a second database rather than a flag on this
  one), pgjdbc cannot speak to H2's PG server (hence psql, not a driver), and H2's `allowOthers=false`
  is a peer check rather than a bind restriction (hence `h2.bindAddress` plus the verification, and
  hence never reading H2's "only local connections" status as proof).

`graphitron-maven-plugin`, `no.sikt.graphitron.rewrite.maven`:

- New `StoreConsoleBinding` for a `<storeConsole>` POM block, in the shape of `DevDatabaseBinding`:
  `<enabled>`, `<port>`, with `GRAPHITRON_DEV_STORE_CONSOLE` and `GRAPHITRON_DEV_STORE_CONSOLE_PORT`
  overriding the POM on each field.
- **The port defaults to ephemeral, and that is the shape to encourage.** An unset `<port>` means 0,
  the session binds whatever is free, and the log line carries the port H2 reports back. This is the
  right default rather than a convenience: several dev sessions in one workspace is the ordinary case
  in this reactor (one per module under test), a fixed default would make the second session's console
  fail to open on a port the first one holds, and a well-known port on a developer machine is exactly
  the kind of listener that gets found by something other than its owner. A pinned `<port>` stays
  available for a developer who wants a stable connect line, and it is a deliberate choice rather than
  what they get by not thinking about it.
- `DevMojo`: a `@Parameter StoreConsoleBinding storeConsole` field, a package-private
  `resolveStoreConsole()` reconciler mirroring `resolveDevDatabase()` (env wins per field, absent
  means off, no console and no port bound), and a `StoreConsole storeConsoleHandle` field left
  package-private for the same reason `sessionStore` is.
- Set `h2.bindAddress` to `127.0.0.1` at the top of `execute()`, before the session store opens, since
  the property is only honoured ahead of H2's class initialisation. A comment states that the console's
  own bind check is what covers the case where this was already too late, so nobody later reads the
  property as sufficient on its own and drops the check.
- Start the console in `execute()` immediately after `Materializations.refreshAll(sessionStore.dsl())`
  and before the watchers, so the linked relations include the refreshed materializations and the
  console is up before the first round lands.

**The session's output carries both commands, and each is complete.** With the port ephemeral, a log
line carrying a `<port>` placeholder would be useless: the log is the only place that value exists. So
the goal prints, on the enabled path, the command that connects, straight from
`StoreConsole.connectCommand()` rather than reassembled at the log site:

```
graphitron:dev: fact-store console up, read-only, 216 relations linked, 127.0.0.1 only.
graphitron:dev:   PGPASSWORD=graphitron psql -h localhost -p 32973 -U graphitron -d store
```

and on the disabled path, which is the default and therefore the line most developers will actually
meet, the command that starts one. This mirrors `resolveDevDatabase()`, which already answers the
"why is this tool missing" question in the log rather than leaving it to the docs:

```
graphitron:dev: no fact-store console (<storeConsole> or GRAPHITRON_DEV_STORE_CONSOLE). To query
graphitron:dev: this session's facts with psql, restart with:
graphitron:dev:   GRAPHITRON_DEV_STORE_CONSOLE=true mvn graphitron:dev
```

A developer who never reads the manual gets from "I wish I could see the rows" to a psql prompt using
only what the session already told them.

`graphitron-mcp`, the agent-facing surface:

- New tool `store.console`, taking no arguments and returning the coordinates as **fields**, so an
  agent never scrapes them out of log text or out of the connect string. Up:
  `{status: "up", host, port, user, password, database, relations, connectCommand}`. Disabled:
  `{status: "disabled", enableWith: "GRAPHITRON_DEV_STORE_CONSOLE=true mvn graphitron:dev"}`.
  `connectCommand` rides along for an agent that shells out, so the human's line and the agent's line
  are the same string from the same place rather than two spellings that can drift.
- Advertised on every boot, in the `catalog.search` shape (present but reporting why it cannot serve)
  rather than the `execute` shape (registered only when configured). An absent tool teaches an agent
  nothing; a tool that answers `disabled` with the enabling command lets the agent tell its human what
  to restart with.
- Deliberately not folded into the existing `status` tool. That one reports the graph's fact
  availability and freshness; connection coordinates for a different protocol are an unrelated answer,
  and bundling them would widen a routinely-called tool for no reason.
- Wiring: `StoreConsole.coordinates()` returns a record (`no.sikt.graphitron.model.boot`, beside the
  handle), null when no console is up. `DevMojo` threads it through `bindServer` next to
  `ExecuteTool.Config` and into the `GraphitronMcpServer` constructor, with a back-compat overload
  defaulting it to null, the growable-record convention `RagConfig` already set. Ordering already
  works: the console starts right after `Materializations.refreshAll` and `bindServer` runs later in
  `execute()`, so the coordinates exist by the time the server is built.
- **The routing sentence goes in the base `/mcp/instructions.txt`, unconditionally, and not in a
  conditional fragment beside `instructions-execute.txt`.** A fragment appended only when the
  coordinates are non-null looks like the careful choice and the existing pin rejects it:
  `ServerInstructionsTest.ambientInstructionsRouteToEveryAdvertisedNameAndViceVersa` asserts the
  partition *per boot* in both directions, and `EXEMPT_FROM_AMBIENT_ROUTING` is empty on purpose,
  its javadoc naming the empty slot as the point. A tool registered on every boot whose sentence
  appears only on the console-up boot is advertised-and-unrouted on the default boot, which is
  exactly what that assertion exists to catch. `execute` escapes it because registration and fragment
  move together; `catalog.search` escapes it because registration and base prose are both
  unconditional. In the tree those are the only two shapes, and this item registers unconditionally,
  so its prose is unconditional too and has to carry the disabled arm itself: one bullet naming
  `store.console`, what it hands back, and that it answers `disabled` with the command that enables
  it.
- **The ambient character ceiling is the binding constraint on that bullet, and it has to be paid
  for.** `AMBIENT_CHARACTER_BUDGET` is 3,600 and the composed worst case (the base string plus the
  `execute` fragment) measures 3,537, so the headroom is 63 characters: less than one routing bullet.
  That test's javadoc says an append should displace something rather than accrete, and that raising
  the ceiling is a decision to argue rather than a constant to bump. The argument here is that a newly
  advertised tool is new surface rather than looser wording, so the ceiling moves to a round 3,700 and
  the reason joins the javadoc beside the existing one. A bullet terse enough to fit the 63 characters
  leaves the ceiling alone and is the better outcome; either way the budget is what to measure before
  writing the sentence rather than after the test goes red.
- A backticked lowercase token is a tool claim to that test (`TOOL_SHAPED` matches any dot-separated
  lowercase identifier), so the bullet must not backtick `psql`, `host`, `port`, `user`, `password` or
  `disabled`. Each would read as a tool the server does not advertise and fail the staleness
  direction of the same partition.
- Close it in `cleanup()` **before** `lspStore`, `mcpStore` and `sessionStore`: the link connection
  points at the store, so the console goes first.

## User documentation (first-client check)

`docs/manual/reference/mojo-configuration.adoc`, a new row beside `devDatabase`:

> `storeConsole` / `StoreConsoleBinding` / (none): Read-only SQL console onto the dev session's own
> fact store (`dev` goal only). `<enabled>` opens it, and the session logs the whole `psql` command,
> including the port it bound and the password it minted for this session. The port is ephemeral unless
> you pin one with `<port>`; pin one only for a stable connect line, and expect a pinned port to
> collide when you run more than one dev session. `GRAPHITRON_DEV_STORE_CONSOLE` and
> `GRAPHITRON_DEV_STORE_CONSOLE_PORT` override the POM. The listener is confined to `127.0.0.1`, and the
> console refuses to start if it cannot verify that, so it is never reachable from another machine.
> Writes to the store's relations are refused, which catches a slip rather than sandboxing the client.
> Absent or disabled, no port is bound and the session is unchanged, and the log says how to turn it on.

`docs/manual/how-to/dev-loop.adoc`, a new section "Query the fact store while the session runs":

> A session without a console tells you how to start one, and a session with a console tells you how
> to connect to it. You never have to compose either command yourself:
>
> ```bash
> $ mvn graphitron:dev
> graphitron:dev: no fact-store console (<storeConsole> or GRAPHITRON_DEV_STORE_CONSOLE). To query
> graphitron:dev: this session's facts with psql, restart with:
> graphitron:dev:   GRAPHITRON_DEV_STORE_CONSOLE=true mvn graphitron:dev
>
> $ GRAPHITRON_DEV_STORE_CONSOLE=true mvn graphitron:dev
> graphitron:dev: fact-store console up, read-only, 216 relations linked, 127.0.0.1 only.
> graphitron:dev:   PGPASSWORD=graphitron psql -h localhost -p 32973 -U graphitron -d store
> ```
>
> Paste the second line into another terminal and you are in. The port is a fresh free one each start,
> so two dev sessions never collide; copy it from the log. Pin `<port>` in the POM if you would rather
> have a stable port, and note that a pinned port collides when you run two sessions.
>
> The listener is on `127.0.0.1` and the console refuses to start if it cannot confirm that, so it is
> not reachable from anywhere else.
>
> What you get is the session's live rows, read-only: a query after a save sees that round's facts.
> "Read-only" means writes to the store's relations are refused, which stops a mistyped `UPDATE` while
> you are poking around. It is not a sandbox, so nothing you type at this prompt should be
> load-bearing.
> Use `information_schema.tables` and `information_schema.columns` to find your way around rather than
> psql's `\d` commands, which the server does not implement. Writes are refused by design; the
> session owns those rows.

`docs/manual/how-to/mcp-agent-context.adoc`, beside the execute-tool section:

> **Query the fact store from an agent.** With the console enabled, `store.console` returns the
> connection as fields (`host`, `port`, `user`, `password`, `database`) plus a ready `connectCommand`.
> Read the fields; there is nothing to parse. The tool is always present: without a console it answers
> `{"status": "disabled"}` and names the command that starts one, so an agent can tell you what to
> restart with rather than guessing why psql refuses. The port is fresh per session, so re-read the
> coordinates after a restart instead of caching them.

The tool table earlier in that same document gains a `store.console` row, like every other advertised
name: it is the list `ServerInstructionsTest` reads for the direction that catches a row surviving a
rename, and a tool documented only in prose is the one an agent's human will not find.

If any draft does not read simply at implementation time, the design is wrong and changes first.

## Tests

`graphitron-model` unit tier, `StoreConsoleTest`, over a booted in-memory store:

- Every `PUBLIC` relation of the store is linked and queryable through the console (count parity
  against the store's own `information_schema.tables`), and a view relation returns rows, since most
  of the fact schema is views.
- Liveness: a row written to the store after the console opened is visible through the console.
- Read-only: `INSERT`, `UPDATE` and `DELETE` through the console fail, and the store's row count is
  unchanged. The test's javadoc records what this does *not* cover (DDL on the console database, and an
  admin client's writable link) so a later reader does not mistake the assertion for a sandbox claim.
  It asserts the refusals rather than asserting the hole stays open, since H2 closing that hole would
  be welcome rather than a regression.
- Connection sharing: store-side `information_schema.sessions` stays at 2 after linking, the claim
  that N links cost one connection and the one that would regress silently.
- The store's own mode is untouched (read `MODE` back off the store's settings; confirm the exact
  relation name at implementation time).
- `close()` shuts the console database down and frees the port: a second console opens on the same
  port afterwards.
- `console(0)` binds a free port and `port()` reports the bound one, never 0. Two consoles opened at
  0 against one store get different ports and both answer, which is the multi-session case the default
  exists for.
- `connectCommand()` names the bound port and the console's user and password. The point of the
  assertion is that the string is complete: no placeholder token survives into it.
- **The bind is loopback-only**: with the console up, a TCP connect to each non-loopback IPv4 address
  of the host is refused while loopback is accepted. This is the item's one hard requirement, so it is
  asserted directly rather than inferred from H2's status string, which reports "only local
  connections" even when the listener is on every interface. The test skips its non-loopback half on a
  host that has no such address.
- A console whose bind cannot be confined does not stay up: with the verification forced to fail, the
  server is stopped and no port is left listening.

`graphitron-model`, `StoreConsolePsqlTest`, the protocol pin, guarded by an assumption on the `psql`
binary so a contributor without it skips and CI (which already uses `psql` in `rewrite-build.yml`)
runs it: open the console at port 0 and execute **the command the session would print**, taking
`connectCommand()` and appending `-c "select ..."`, then assert the returned row text. Pinning the
printed line by running it is the whole value of this test: a reconstruction of the command in the test
would pass while the line a developer copies is wrong. A wrong password is refused, so the secret is
covered too. The ephemeral port is what makes this test safe under a parallel reactor at all, since a
pinned test port would collide with a developer's own session. Its
javadoc records that pgjdbc cannot replace the shell-out, with the `SET extra_float_digits` reason, so
nobody swaps it for a driver connection and concludes the console is broken.

`graphitron-maven-plugin`, `DevMojoTest`, which already injects an in-memory store:

- Default: no console and no port bound, and the log names the command that would start one. The
  disabled path is the default, so its line is as much a shipped surface as the enabled one.
- Enabled with no `<port>`: the console opens against the injected store on an ephemeral port, and the
  logged line is exactly `StoreConsole.connectCommand()`, carrying the bound port rather than a
  placeholder or a 0.
- Enabled with a pinned `<port>`: the console binds exactly that port, and the logged command names it.
- The coordinates reach `bindServer` when the console is up, and are null when it is off.

`graphitron-mcp`, MCP-handler tier, structured-content assertions only per this module's convention:

- `GraphitronMcpServerTest`: `store.console` is advertised in `tools/list` on both arms; the up arm
  returns the bound port, the user, the password, the relation count and a `connectCommand`, each as its
  own field; the disabled arm returns `status: disabled` plus the enabling command and no connection
  fields at all.
- `ServerInstructionsTest`: no new test method, three existing ones to satisfy. `store.console` is
  routed by the base ambient string on **both** boots, since the partition is asserted per boot with
  nothing exempted; it joins `EXPECTED_SURFACE` in `theAdvertisedSurfaceIsExactlyTheNamedSet`, which is
  where adding a tool is meant to be a decision somebody writes down; and the composed string stays
  inside the ceiling the design section settles. The manual pin is satisfied by the doc changes above,
  the presence direction reading the whole document and the staleness direction the tool table's first
  column.
- `cleanup()` closes the console, and closes it before the store.
- A console that fails to open degrades to a warning naming the reason, and the session continues.

## Out of scope

- Putting the session's store in `MODE=PostgreSQL`. Rejected above with the measurement behind it.
- A door for JDBC clients. pgjdbc cannot reach this console, so DBeaver and IntelliJ would need a
  separate H2 TCP server on the store itself, which is a read-write door and its own bind question.
  File it separately if the demand shows up.
- Write access, and any binding other than `127.0.0.1`.
- Arbitrary SQL as an MCP tool. `store.console` hands out coordinates; running SQL over the fact store
  and returning rows is its own item.

## Open questions

The port question is settled: ephemeral is the default and the encouraged shape, a pinned port is
available for a developer who wants a stable connect line.

The `store.query` question is settled: both surfaces ship. `store.console` stays as specified here
(coordinates as fields, for an agent that wants a real psql session or a result set larger than a tool
response should carry), and arbitrary read-only SQL as an MCP tool is tracked as its own item, since it
needs nothing from this one: it rides a rights-limited connection straight onto the store, with no
console, no port and no linked database involved.
