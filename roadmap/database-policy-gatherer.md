---
id: R1184
title: "Graphitron reads the database over a live connection: row-level security as facts, definitions on request"
status: Spec
bucket: architecture
priority: 4
theme: diagnostics
depends-on: []
created: 2026-11-13
last-updated: 2026-11-16
---

# Graphitron reads the database over a live connection: row-level security as facts, definitions on request

## Goal

A schema author sees what the database's row-level security says about the tables their schema
binds. Hovering a `@table` type in the editor, or asking `catalog.describe` over MCP, answers whether
the table secures its rows, whether that is forced, and each policy's command, mode, roles and
`USING` / `WITH CHECK` text. The facts come from a new optional gatherer that reads the database's own
catalog over a live connection, configured by a new `<database>` block that replaces `<devDatabase>`.
In a `dev` session the same connection also answers, when asked, the database's own definition of
one object the schema names: a table with its keys, indexes, triggers and policies, a view, a routine,
a trigger and the function it runs. Those definitions are read live and never stored (§ Definitions on
request). Without connection settings nothing changes, and generated code is the same whether the
gatherer ran or not. The warnings the policy facts make possible are follow-up items (§ Follow-ups);
this item lands the facts, their reading record, the readers named above, the definitions on request,
and the gates that keep the facts out of generation.

## Why a live connection

The jOOQ-generated classes are how graphitron knows the database today, and they carry no policies.
The facts therefore come from the database itself, through a gatherer that runs only when it is given
connection settings. Alf gave this direction when the item was filed, over the alternatives of a
sidecar written at jOOQ codegen time and a parse of migration scripts. The ruling enters
`docs/architecture/` in this item (§ Docs).

On 2026-11-16 Alf ruled that the database's own definitions, fetched one object at a time on request,
belong to this item too, since they need the same connection. They were R1050, now discarded as
absorbed; § Definitions on request carries its scope.

## Configuration

### `<database>`, the connection

A new shared parameter on `AbstractRewriteMojo`, so every goal that captures (`generate`, `validate`,
`capture`, `dev`) sees it:

```xml
<database>
  <url>jdbc:postgresql://localhost:5432/sakila</url>
  <user>dev</user>
  <password>dev</password>
  <dialect>POSTGRES</dialect>
</database>
```

* Each field has an environment override that wins over the POM, reconciled as `DevMojo` does today
  (`firstNonBlank(env, pom)`): `GRAPHITRON_DB_URL`, `GRAPHITRON_DB_USER`, `GRAPHITRON_DB_PASSWORD`,
  `GRAPHITRON_DB_DIALECT`.
* No url from either source means no database: the gatherer does not run and `execute` is absent.
* A url with a missing or unknown dialect fails the goal, as `<devDatabase>` does today.
* The block holds the connection only. `execute`'s request fields stay with `execute` (next section).

### `<execute>`, the dev tool's request fields

`<claims>`, `<session>` and `<allowClaimsOverride>` move from `<devDatabase>` to a `dev`-goal block
`<execute>`, with their environment overrides unchanged (`GRAPHITRON_DEV_CLAIMS`,
`GRAPHITRON_DEV_SESSION`, `GRAPHITRON_DEV_DB_ALLOW_CLAIMS_OVERRIDE`). `execute` connects with
`<database>`.

### The migration window

`<devDatabase>` stays accepted:

* Alone, it supplies both the connection and the execute fields, exactly as today, and the goal logs
  one warning naming `<database>` and `<execute>` as its replacements. The legacy
  `GRAPHITRON_DEV_DB_URL` / `_USER` / `_PASSWORD` / `_DIALECT` variables are read as fallbacks for
  the new ones, with the same warning.
* Together with `<database>` or `<execute>`, it fails the goal with a message saying which to keep.
  Two sources for one connection would be a silent precedence rule.
* A consumer who keeps only `<devDatabase>` now also gets the gatherer in `dev`, since that is the
  connection they configured. They get it in `generate` only once they move to `<database>`, because
  `<devDatabase>` was never visible there.

Removing `<devDatabase>`, `DevDatabaseBinding` and the legacy variables is a follow-up item at the end
of the window.

### Drivers and dependency scope

The driver is loaded from the consumer's project classpath through `ServiceLoader`, bypassing
`DriverManager`, as `DevQueryExecutor.openConnection` does. `dev` already resolves test scope, because
Quarkus applications often declare the driver there. `generate`, `validate` and `capture` resolve
`COMPILE` today, and keep that static scope. When `<database>` is configured, the mojo resolves the
test classpath on demand through Maven's `ProjectDependenciesResolver` and loads the driver from it, so
a driver declared at test scope is found by every goal. Without `<database>` nothing extra is resolved,
and a build that configures no database pays nothing.

A configured url with no driver that accepts it fails the goal: that is a misconfiguration, the same
on every machine.

### What reaches the model

The credentials never enter `SubjectConfig`, which `StoreEntries` transcribes into the store. The
mojo hands `ModelCapture.capture` a separate optional connector, an interface in `graphitron-model`
that opens a `java.sql.Connection`. The model module gains no driver dependency.

## The store

### Corpus, gatherer and family

* A `meta_corpus` row `database`: the consumer database's system catalog, read over a live
  connection. It sits beside `catalog` ("read through the generated jOOQ model"), which is unchanged.
* A `meta_gatherer` row `database`, class `no.sikt.graphitron.model.capture.database.DatabaseFactCapture`,
  with its `meta_gatherer_corpus` row.
* A new family prefix `database_` with its `meta_family` row. A family is named for the vocabulary
  its rows are in, and one gatherer answers for one corpus; `sql_` stays the jOOQ reading.

### Relations

Each is keyed like `sql_table`, on `(source_name, table_schema, table_name)` plus its own grain, with
`source_name` a foreign key to `store_source` (`ON DELETE CASCADE`) and a `touched_at` mark. Each child
relation also carries a foreign key to its parent's key with `ON DELETE CASCADE`, so sweeping a table
collects its policies and sweeping a policy collects its roles.

* `database_table`: one row per table the reading saw, with whether the table secures its rows and
  whether that is forced. Row security off is a stored fact, not a missing row; a table absent here
  is one the reading did not see. Every ordinary and partitioned table outside the system schemas is
  read, not only the secured ones, so the readers ask one row rather than evaluating absence, and a
  table one side has and the other lacks is an anti-join over stored rows (see Matching `sql_table`).
* `database_policy`: one row per policy a table declares, with a foreign key to its `database_table`
  row. Columns: command (`ALL`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`), mode (`PERMISSIVE`,
  `RESTRICTIVE`), the `USING` and `WITH CHECK` text as the database renders it, and the policy's
  comment. Each of those three columns is display material, never a dimension, and the DDL says so in
  the same words; no reader string-searches it. A table may declare policies while its row security is
  off, and the database then does not apply them: such policies are stored under their table's row,
  whose flag says they are not in force.
* `database_policy_role`: one row per role a policy applies to; `PUBLIC` is a row.

A follow-up that needs structure from an expression extracts the direct facts during the reading and
stores them as rows beside the text: a policy reads a setting, a policy calls a function, a function
reads a setting, a function calls a function. The parse happens once, at capture, over what the
database returns: the expression's node tree where the database exposes one, a function's source text
where it does not. That passes the recompute test in `docs/architecture/explanation/fact-model.adoc`
§ "Gatherers build the model in a declared order", because the store holds neither the node tree nor
the function source; the stored `pg_get_expr` text is display only and no reader parses it. Which
functions a policy reaches transitively is recomputable from those rows, so that closure is anchor-phase
work, not capture.

Names are stored as the database spells them (`pg_namespace.nspname`, `pg_class.relname`), which is
how `sql_table` stores jOOQ's `getSchema().getName()` and `getName()`. That makes a name match exact.

Each relation gets its `meta_relation` row (owner `database`, grain sentence, example) and a
`meta_grain` row whose corpus is `database`, and the table comment equals grain text plus example, as
the existing gates require.

### Matching `sql_table`

There is no foreign key between `database_` and `sql_`: the two corpora refresh separately, and a
cascade from one into the other would delete facts the other still vouches for. Readers join on
`(table_schema, table_name)`. `sql_table` is partitioned by jOOQ package, so the reader starts from
the `sql_table` row the `@table` type already resolved to (`graphitron_tabletype`'s triple) and looks
up the `database_` rows by its schema and name. Two jOOQ packages carrying the same schema and table
then read the same security, which is correct: it is one table in one database.

A table one side has and the other lacks would show the jOOQ classes are stale against the database.
Because `database_table` holds every table the reading saw, that finding is an anti-join between two
sets of stored rows. This item reports it nowhere, because it has no SDL position to hang a defect on
(see Open questions); the rows make it ready for whoever decides where it goes.

### The reading record

* `source_name` is a credential-free rendering of the connection, `<dialect>://<host>:<port>/<database>`,
  built from the parsed url. Never the raw url, which can carry `password=` and other parameters; the
  store is readable by agents through `store.console`.
* A `store_source` row of `source_kind` `DATABASE`, its stamp left null as `jooq` leaves it: no
  reading is ever skipped on a stamp (§ Cadence), so a hash there would vouch for nothing. The
  `source_kind` column's `CHECK (source_kind IN (...))` and its comment both gain `DATABASE`.
* A `store_graph_source` row claims the source for the graph. Its `stamp` and `read_at` carry the
  reading's outcome in the meanings those columns already have. On a successful reading the stamp is
  a hash over the rows the reading captured, computed in the reading's transaction and written last:
  that is "what this graph's rows of this source were derived from", in the column comment's words,
  and it commits after the facts it vouches for. `read_at` is the reading's instant, so it always
  names the last successful reading.
* A `store_graph_progress` row records the gatherer's start and completion, as for every gatherer.

### Who drops a claim

The gatherer owns the graph's `DATABASE` claims, on the shape `JooqFactCapture.dropMembership` gives
the `JOOQ_SCHEMA` kind. Every capture calls it, connector or not:

* With a connector, it claims the source the connector names and drops the graph's other `DATABASE`
  claims. A url that changes host, port or database therefore leaves one claim, never two, and the
  readers' `(table_schema, table_name)` lookup sees one reading.
* Without a connector, it reads nothing and drops every `DATABASE` claim the graph holds. A consumer
  who removes `<database>` stops seeing the facts on the next capture.

Dropping a claim leaves the source and its rows to the existing release: once no graph claims the
source, the delete scoped by `SourceClaims.unclaimed()` takes it, and the `database_` rows go with it
through their `ON DELETE CASCADE` from `store_source`. "Without connection settings nothing changes"
in § Goal is about what an author sees and what is generated; in the store, a graph that never had a
connector holds no claim, so the call is a no-op for it.

### Reader arms

The readers decide from the graph's `DATABASE` claim, scoped to that kind. The arms do not carry over
to other kinds: `jooq` writes its own claims with a null stamp and null `read_at` after a successful
walk.

* no claim: not read, and the readers say nothing about row security;
* a claim with a stamp: read, and each table's `database_table` row says whether it is secured;
* a claim without a stamp but with a `read_at`: the last reading failed, and the rows are from the
  reading at `read_at` (see Unreachable database);
* a claim with neither: no reading has succeeded yet, so there are no rows, and the readers say the
  database could not be read.

### Cadence

Every capture that is given a connector re-reads. The reading is two catalog queries, cheaper than
working out whether anything changed. Rows are upserted with the round's `touched_at`, and the
gatherer sweeps its own family for this source, as `JooqFactCapture.sweep` does for `sql_`.

### Unreachable database

When the connection or a query fails, the goal logs a warning naming the source and the cause, and
continues: output does not depend on these facts, so a database that is down must not stop a build.

* With a prior successful reading of this source, the gatherer clears the claim's stamp and keeps the
  rows and the claim's `read_at`, so the readers can say the facts are from an earlier reading and
  when.
* With none (a first reading, or the url just changed), it writes the claim with neither stamp nor
  `read_at`, and there are no rows to keep.

Whether stale rows should be kept or swept is an open question for Alf and Filip; keeping them is this
spec's default.

`dev` re-captures every round, so a database that is down would cost the driver's connect timeout each
round. The connector therefore sets a bounded connect timeout of five seconds through the driver's
own property (`connectTimeout` for PostgreSQL), so an unreachable database delays a round by at most
that.

### Postgres reading

One read-only transaction, two statements:

* `pg_class` joined to `pg_namespace` where `relkind` is `r` or `p`, outside `pg_catalog`,
  `information_schema` and `pg_toast`: schema, table, `relrowsecurity`, `relforcerowsecurity`.
* `pg_policy` joined to `pg_class` and `pg_namespace`: the policy name; `polcmd` and
  `polpermissive` decoded at capture into the words the table holds; `polroles` resolved to names
  through `pg_roles`, which the connecting role can read, with the `{0}` entry read as `PUBLIC`;
  `polqual` and `polwithcheck` rendered by `pg_get_expr`; and the policy's comment. The catalog is read
  directly rather than through the `pg_policies` view, which derives from the same catalog with a fixed
  layout, so the reading keeps the raw codes and decodes them itself.

With dialect `ORACLE` the gatherer does not run and logs that Oracle is not read yet. Oracle's VPD
(`ALL_POLICIES`) fits the same relations later. Grants (one row per table, grantee and privilege) fit
the same gatherer later too.

### Place in the capture

In this item the gatherer is a crawler. The `meta_gatherer_dependency` table comment states the rule:
"A crawler's capture carries no edges: its rows about its own corpus may not vary with any other
corpus's contents." Its `meta_gatherer_corpus` row (`database`, `database`) is what makes it a crawler.
It writes its own `store_source` and `store_graph_source` rows, as `jooq` does without an edge.

Its edges arrive with its anchor stages. A follow-up's defect rule reads the policy facts together with
the catalog and the schema, so that follow-up adds `jooq` and `graphitron` edges, and the stage runs
after both (§ Follow-ups). `ModelCapture.capture` therefore calls `DatabaseFactCapture.capture` after the
`graphitron` gatherer: a crawler's position is free, and placing it there now means the first anchor
stage lands without moving the call.

## Readers

### Editor hover

`TypeInformation` gains a row-security line for a `@table` type, read in the same single statement
(`DeclarationHoverStatementCountTest`) and over stored relations only (`EditorReadsNoViewTest`):

* not read: nothing is shown;
* read, not secured: `Row security: off`, followed by any policies the table declares, marked as not
  in force;
* read, secured: `Row security: on` (`forced` when it is), then one line per policy: name, command,
  mode, roles, `USING`, `WITH CHECK`;
* stale (claim stamp cleared, `read_at` kept): the same, marked as from the reading at `read_at`;
* never read successfully (neither): `Row security: the database could not be read`.

### MCP

`catalog.describe` gains a `rowSecurity` member with the same arms, read through `CatalogQueries`. The
`docs/manual/how-to/mcp-agent-context.adoc` description of `catalog.describe` says so.

## Definitions on request

What graphitron reasons over is a stored fact; what only a person or an agent reads is fetched when
asked. Policies are small and the follow-ups reason over them, so they are captured (§ The store).
Definitions are large text nothing reasons over: capturing every routine and trigger body on every
round costs in proportion to the schema for an answer wanted one object at a time. So they are read
live, through the same `<database>` connector, and never enter the store. The database stays the
authority for what it holds.

### Where it is available

In `dev`, where the MCP server and the language server run. `DevMojo` already hands `execute` its
connection and constructs the language server in `DevServer`; it hands both the `<database>` connector.
With no `<database>` (or `<devDatabase>` during the window), the MCP tool is not registered and the
editor shows no definition, as `execute` is absent today. The standalone language server (`Launcher`)
has no connector and shows none. `generate`, `validate`, `capture` and CI never fetch a definition.

### MCP

A read-only tool `database.definition` taking an object kind and a qualified name, and answering that
object's definition as the database renders it. The split the prefixes state is stored versus fetched
live: `catalog.` tools answer from the store, which includes the `database_` facts that
`catalog.describe` reports, and `database.` answers from the live database. It refuses with its
cause where the session has no database, where the object does not exist there, and where the dialect
is not read yet. Each call opens a connection, reads in a read-only transaction under the bounded
connect timeout, and closes it, as `execute` does.

### Editor

On hover of the argument that names a database object: a `@table`'s table, a `@reference` key, a
`@routine`'s routine. The hover starts from the object the store already resolved that argument to
(`graphitron_tabletype`'s table triple, the resolved key, the resolved routine), never by resolving the
authored string again; an argument the store did not resolve shows no definition. The answer is fetched when that hover is asked for, shown as a SQL block, and
bounded by the connect timeout; a failure shows its cause in the hover instead. The type and field
hovers that read the store stay as they are, one statement and no view (`DeclarationHoverStatementCountTest`,
`EditorReadsNoViewTest`), so a live read never slows an ordinary hover.

### What the database offers

PostgreSQL first, because sakila tests it:

* routine: `pg_get_functiondef`;
* view: `pg_get_viewdef`, wrapped as `CREATE VIEW`;
* trigger: `pg_get_triggerdef`, followed by its function's `pg_get_functiondef`;
* key (what a `@reference` names): `pg_get_constraintdef`, wrapped as `ALTER TABLE ... ADD CONSTRAINT`;
* table: PostgreSQL has no single statement for a table, so its definition is composed: the
  `CREATE TABLE` with columns from the catalog, `pg_get_constraintdef` for each constraint,
  `pg_get_indexdef` for each index not backing a constraint, `pg_get_triggerdef` for each trigger, and
  the table's row-security statements and policies, read live in the same transaction. Not from the
  stored `database_policy` rows, which would put a possibly stale reading beside live DDL in one
  answer, and not through the gatherer's code, whose job is to decode facts into the store's words.

Oracle answers every kind through `DBMS_METADATA.GET_DDL`; until Oracle is read, the tool refuses with
that cause, as the gatherer logs it.

## Keeping the facts out of generation

Generated output must be identical whether the gatherer ran or not. Three gates make that structural:

* A guard in the style of `PackageImportDirectionTest`: no class in the generator's emit path refers
  to a `database_` table's generated jOOQ class, and the definition reader is referenced only from the
  `dev` goal and the MCP and language-server packages, so `generate`, `validate`, `capture` and CI
  cannot fetch a definition.
* A differential capture test modelled on `CaptureCorpusIsolationTest`: capture sakila with and
  without a connector, and require identical rows in every relation outside the `database_` family,
  the reading-record rows, and `store_graph_progress`. It needs PostgreSQL, so it is `@ExecutionTier`.
* For the follow-ups, a gate that every defect code owned by a `database` stage is warning severity.
  It lands with the first such stage.

## Docs

* The ruling, as `docs` text in this item's commits:
  `docs/architecture/explanation/modeling-discipline.adoc` § "Provenance is the only question that
  matters first" admits a live database as a corpus, only through an optional gatherer that runs when
  connection settings are given; `docs/architecture/explanation/fact-model.adoc` § "One base, many
  views" states that its facts never change generated output.
* `docs/architecture/explanation/fact-model.adoc` § "One base, many views" also gains the limit of the
  live read beside it: display text from a live corpus may be fetched on request, is never stored,
  joined or reasoned over, and is fetched only in `dev`. Without it, the next consumer wanting a live
  answer has a precedent and no limit.
* `docs/manual/reference/mojo-configuration.adoc`: `<database>` under shared parameters, `<execute>`
  under `dev`-goal parameters, the migration from `<devDatabase>`, the driver-scope rule. The sentence
  that the store holds nothing "that cannot be rebuilt from your sources" gains the database as a
  source.
* `docs/manual/how-to/mcp-agent-context.adoc`: `execute` connects with `<database>`;
  `catalog.describe` reports row security; `database.definition` and when it is registered; the
  `catalog.` / `database.` split stated as stored versus fetched live.
* The editor's hover documentation: the definition shown on a `@table`, `@reference` key or
  `@routine` argument in `dev`.
* `docs/security.adoc`: one paragraph saying the editor and MCP show the policies when a database is
  configured.

## Tests

* `DatabaseFactCaptureTest` (`@ExecutionTier`, local PostgreSQL): reads sakila and asserts the
  `database_` rows for `secure_note`, and a `database_table` row with row security off for a table
  without it. Two readings exercise the sweep: the second, after a policy is
  dropped inside the test's own transaction, removes its rows.
* `graphitron-sakila-db/src/main/resources/init.sql` gains what the arms need: a table with forced row
  security, a restrictive policy, a policy for one command, and a policy for a named role besides
  `PUBLIC`. `secure_note` keeps its role as the permissive `ALL` case.
* Reading record, one capture after another against the same store:
  * no connector, never configured: no claim and no source row;
  * a successful reading: a claim whose stamp is the hash of the rows and whose `read_at` is the
    reading's instant;
  * the url then refuses connections: a warning, the claim's stamp cleared, `read_at` unchanged, the
    previous rows kept;
  * a first reading that fails: a claim with neither stamp nor `read_at`, and no rows;
  * de-configured (no connector after a reading): the claim dropped, and once no graph claims the
    source, the source and its `database_` rows gone;
  * the url changed to another database: one `DATABASE` claim for the graph, the new one, and the
    old source released the same way.
* The connect timeout bounds an unreachable reading: a url pointing at a port nothing answers on
  fails within the timeout.
* `source_name` never contains the user, the password or the url's query parameters.
* `polroles` entries resolve to role names through `pg_roles`, and `{0}` reads as `PUBLIC`.
* Mojo configuration tests over the injected environment map: `<database>` alone; env overriding POM;
  `<devDatabase>` alone (works, warns); `<devDatabase>` with `<database>` (fails); legacy variables as
  fallback (works, warns); no url (gatherer and `execute` absent); a url with no accepting driver
  (fails).
* Hover: the five arms, with the statement-count and no-view tests still green.
* MCP: `catalog.describe` on `secure_note` with and without a reading.
* The two generation gates above.
* `database.definition` (`@ExecutionTier`): each kind against sakila, including the composed table
  definition of `secure_note` with its policy; the three refusals (no database, no such object, an
  unread dialect).
* Editor: the definition hover on a `@table`, a `@reference` key and a `@routine` argument with a
  connector; no definition without one; an unreachable database shows its cause; an argument the store
  did not resolve shows none. The new hover target gets its row in `TriggerDispatchMatrixTest`.
* `graphitron-sakila-db/src/main/resources/init.sql` also gains a view and a trigger, so every kind
  has something to show.

## Follow-ups, filed when this reaches Ready

Each is its own rule with its own codes, fact document and near miss. Each raises warning defects
from an anchor stage of the `database` gatherer, which is the first stage holding every fact the rule
reads, so the `graphitron` gatherer gains no edge to an optional upstream. The candidates:

* a non-null field reached through `@reference` into a row-secured table, which resolves to null when
  a policy hides the target row, even where the foreign key is `NOT NULL`;
* a DML mutation whose `WITH CHECK` admits a row its read policy hides, so the payload's re-read
  returns nothing (the `secure_note` case), and a row-secured table with no policy for the mutation's
  command, which the database denies by default;
* a policy reading a session setting without treating `NULL` and `''` alike as no identity, the
  fail-closed rule in `docs/security.adoc`. This one captures the direct facts § Relations names
  (setting reads and function calls, of policies and of functions) and derives which functions a policy
  reaches in its anchor stage; the capture and that closure move here, since this is their first
  reader. Checking setting names against the `SessionState` mount needs a new fact, since the store
  does not record what the mount writes;
* a `@table` on an unsecured table whose neighbours are secured;
* the database-side rules an internal pgTAP suite already runs over row security: a forced table, more
  than one policy for one command, a view without `security_invoker`, a policy that is `true` throughout,
  a policy reading an `app.` setting through a function, and a policy calling a function that takes no
  argument. Each is its own item and its own warning; the list is a candidate set from practice, not a
  ruling.
* removing `<devDatabase>` at the end of the migration window.

## Open questions for Alf and Filip

* An unreachable database: keep the last reading's rows marked stale (this spec's default), or sweep
  them?
* What configuration may enter the store. Nothing under `docs/architecture/` says; this spec stores
  only a credential-free rendering of the connection.
* Where a table the database has and the jOOQ classes lack, or the reverse, should be reported, given
  that it has no SDL position.
* The application role. Policies apply `TO` roles, and without knowing which role the runtime
  connects as, the follow-up diagnostics must assume any policy may apply. A `<database>` field
  naming the runtime role(s) is the obvious home; it is left to the follow-ups.
* Views and their options. Some of the candidate rules (a view without `security_invoker`, a view
  reading an `app.` setting) reach past policies, and this item stores no view facts. Whether they join
  this family or a later one is for the follow-up that needs them.

## Principles-architect consultation

Consulted at filing, at take-up, before the first Spec -> Ready review, and when R1050 was absorbed.
What it raised, and what this spec did:

* A live database is a new corpus, needing its own `meta_corpus` row, family prefix and refresh unit:
  adopted (`database`, `database_`, a `store_source` row per connection).
* Keep `execute`'s request fields out of the connection block: adopted (`<execute>`).
* Invert the dependency, so the diagnostics are stages of the new gatherer and the `graphitron`
  gatherer never reads an optional upstream: adopted for the follow-ups. This reverses the Backlog
  body, which said the `graphitron` gatherer would own them.
* Make output independence structural, with a guard and a differential test: adopted.
* Split the diagnostics into their own items: adopted.
* The ruling splits across `modeling-discipline.adoc` and `fact-model.adoc`, and
  `mojo-configuration.adoc`'s "rebuilt from your sources" sentence must change: adopted.
* `source_name` must never be the raw url: adopted.
* The stale-versus-sweep choice, what configuration may enter the store, and the home of the
  "tables differ" finding have no ruling on record: carried as open questions.
* Before review: extracting structure at capture passes the recompute test, because the store holds
  neither the node tree nor the function source; the transitive closure over the direct facts is
  anchor work: adopted, in § Relations and the fail-closed follow-up.
* The zero-edge crawler rests on the `meta_gatherer_dependency` comment, not only on the `jooq`
  precedent: adopted, cited in § Place in the capture. (Alf ruled separately that the question is what
  this gatherer depends on, not who depends on it.)
* A null `store_source.stamp` already has meanings, and reusing it as "the last reading failed" would
  add a third; `store_graph_source.stamp` and `read_at` already mean an untrusted claim and the last
  transcription: adopted. The `store_source` stamp stays null as `jooq` leaves it, and the readers'
  arms read the graph's claim row.
* Row security off as a missing row makes every reader evaluate absence, and leaves policies unable to
  key into their table: adopted. `database_table_security` became `database_table`, one row per table
  read with both flags, and `database_policy` foreign-keys it with `ON DELETE CASCADE`. The "tables
  differ" finding is now an anti-join over stored rows.
* Adding `DATABASE` means updating the `source_kind` column comment: adopted.
* On absorbing R1050: the live read sits beside "no consumer owns a private model" and needs its own
  written limit: adopted, as a sentence for `fact-model.adoc` in § Docs. The hover starts from the
  store's resolution of the argument, never re-resolving the authored string: adopted. The prefix split
  is stored versus live, not jOOQ versus database: adopted. The composed table definition reads policies
  live in its own transaction, neither from stored rows nor through the gatherer's code: adopted.
  "Never in `generate` or CI" gets an enforcer, by widening the package guard: adopted. The new hover
  target needs a `TriggerDispatchMatrixTest` row: adopted. It found no written "one goal per item"
  rule, and read the goal as one outcome with two delivery tiers.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01WkW2JGNxszh8SZspUvi8xh, 2026-11-15)

Question 1 passes: the goal reads on its own (an author sees a bound table's row security and
policies in hover and `catalog.describe` when a database is configured, generation unchanged), and
every symbol, relation, doc section and fixture the spec names exists as named. Question 2 fails on
one point, the lifecycle of the reading record, which the readers' three arms rest on.

1. **Nothing removes a `DATABASE` claim, so the "not read" arm cannot be reached once a reading has
   happened (question 2).** The arms are decided by the graph's claim row, but the spec only ever
   writes or updates that row. Two cases follow. A consumer who removes `<database>` gets no
   gatherer run ("the gatherer does not run"), so the claim, its stamp and the rows stay, and hover
   keeps saying `Row security: on` about a database they no longer configure. A consumer whose url
   changes host, port or database name gets a second `source_name`, so the graph holds two
   `DATABASE` claims and the readers' `(table_schema, table_name)` lookup returns both readings'
   rows. The tree already has the shape for this: `JooqFactCapture.dropMembership` drops the
   graph's claims of its own `source_kind` that the reading no longer holds, all of them when no
   package is configured, and `SourceClaims.unclaimed` then lets the unclaimed source and its rows
   go. Satisfied when the spec says who drops the graph's `DATABASE` claims it no longer holds,
   including on a capture given no connector (and squares that with "Without connection settings
   nothing changes"), and the Tests list carries the de-configure and url-change cases.

2. **The value the claim's `stamp` carries on success is unstated (question 2).** The trusted arm is
   "a claim with a stamp", and § The reading record leaves `store_source.stamp` null because "a hash
   would vouch for nothing". The same argument reaches `store_graph_source.stamp`, whose column
   comment defines it as "what this graph's rows of this source were derived from", and the spec
   names no value for it. Note too that `JooqFactCapture` writes its own claim with a null stamp
   and null `read_at` after a successful walk, so a null claim stamp does not mean "failed" across
   kinds today; the arm is sound only scoped to `DATABASE`, which the readers do. Satisfied when the
   spec names what is written to the stamp on a successful reading, in terms consistent with that
   column comment, and what a first reading that fails writes (no prior claim, so no `read_at` and no
   rows for the stale arm to show).

Non-blocking:

* `store_source.source_kind` is enforced by a `CHECK (source_kind IN (...))`, not only described by
  its comment; the constraint gains `DATABASE` too.
* `dev` re-captures on each round, so an unreachable database costs the driver's connect timeout on
  every round, not once. Worth a bounded connect timeout on the gatherer's connection, or a sentence
  saying the default is acceptable.

### Round 2: Spec → Ready, signed off (session_01WkW2JGNxszh8SZspUvi8xh, 2026-11-15)

Both round-1 findings are met: § Who drops a claim gives the gatherer the `DATABASE` claims on
`dropMembership`'s shape, connector or not, and the reading record names the stamp (a hash over the
captured rows, written last) and the first-failure arm, with tests for each step of the sequence.
The `CHECK` constraint and the connect timeout are taken up too.

Non-blocking, for the implementer:

* § Who drops a claim says the unclaimed source goes through "the existing release". No shared
  release exists: per `SourceClaims`' javadoc each gatherer owns the reclaim of its own kinds
  (`GraphQLSourceCapture` and `ClasspathSourceCapture` each issue their own delete with
  `SourceClaims.unclaimed()` in its `WHERE`, and `jooq` issues none). The database gatherer writes
  that delete for `DATABASE` sources itself; the test list already demands the outcome.
* § Configuration still says that with no url "the gatherer does not run", while § Who drops a claim
  has every capture call it. Read the former as "reads nothing".
