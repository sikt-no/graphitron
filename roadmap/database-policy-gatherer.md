---
id: R1184
title: "Graphitron reads the database over a live connection: row-level security as facts, definitions on request"
status: Ready
bucket: architecture
priority: 4
theme: diagnostics
depends-on: []
created: 2026-11-13
last-updated: 2026-11-17
---

# Graphitron reads the database over a live connection: row-level security as facts, definitions on request

## Goal

A schema author sees what the database's row-level security says about the tables their schema
binds. Hovering a `@table` type in the editor, or asking `catalog.describe` over MCP, answers whether
the table secures its rows, whether that is forced, and which policies it declares, with each one's
command, mode and roles. In a `dev` session the same connection also answers, when asked, the
database's own definition of an object the schema names: a table with its keys, indexes, triggers and
policies (their `USING` and `WITH CHECK` expressions included), a view, a routine, a key.

The store records only what graphitron reasons over: that a table is secured, which policies exist,
and their command, mode and roles. Everything shown only for a person or an agent to read is fetched
live on request and never stored (§ Definitions on request). The facts come from a new optional
gatherer that reads the database's own catalog over a live connection, configured by a new
`<database>` block that replaces `<devDatabase>`. Without connection settings nothing changes, and
generated code is the same whether the gatherer ran or not. The warnings the policy facts make
possible are follow-up items (§ Follow-ups); this item lands the facts, their reading record, the
readers named above, the definitions on request, and the gates that keep the facts out of generation.

## Why a live connection

The jOOQ-generated classes are how graphitron knows the database today, and they carry no policies.
The facts therefore come from the database itself, through a gatherer that runs only when it is given
connection settings. Alf gave this direction when the item was filed, over the alternatives of a
sidecar written at jOOQ codegen time and a parse of migration scripts. The ruling enters
`docs/architecture/` in this item (§ Docs).

On 2026-11-16 Alf ruled that the database's own definitions, fetched one object at a time on request,
belong to this item too, since they need the same connection. They were R1050, now discarded as
absorbed; § Definitions on request carries its scope.

On 2026-11-17 Alf ruled the line between the two: the store records what exists and what graphitron
reasons over, and anything else is looked up when needed. Start simple and capture only what is
necessary. That moved the policies' expression text and comments out of the store, dropped a stored
row of server identity, and needs no new existence relations, because every object the schema can
name is already in the store through the jOOQ reading (§ The live interface). He also ruled that what
can only be found by connecting is a reading outcome, never a failed goal (§ Configuration).

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
* No url from either source means no database: the gatherer reads nothing (it still drops the graph's
  claims, § Who drops a claim) and `execute` is absent.
* A url with a missing or unknown dialect fails the goal, as `<devDatabase>` does today.
* The declared dialect is checked against the connection, on connecting, through jOOQ's
  `JDBCUtils.dialect(Connection)`. A disagreement is a reading outcome, not a failed goal: the rule is
  that what can be decided without connecting (no url, no dialect, an unknown dialect, no driver that
  accepts the url) fails the goal, and what can only be found by connecting does not, so the same POM
  never passes where the database is down and fails where it is up. In capture a mismatch is a failed
  reading whose cause names both dialects (§ Unreachable database); in `execute` and
  `database.definition` it is a refusal with that cause. `<dialect>` stays required while
  `<devDatabase>` is accepted, since `<devDatabase>` requires it explicitly; whether it becomes
  optional, detected from the connection, is decided when `<devDatabase>` is removed.
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
  row. Columns: command (`ALL`, `SELECT`, `INSERT`, `UPDATE`, `DELETE`) and mode (`PERMISSIVE`,
  `RESTRICTIVE`). The `USING` and `WITH CHECK` expressions and the policy's comment are display text
  nothing reasons over, so they are not stored; the editor and MCP fetch them live as part of the
  table's definition (§ Definitions on request). A table may declare policies while its row security is
  off, and the database then does not apply them: such policies are stored under their table's row,
  whose flag says they are not in force.
* `database_policy_role`: one row per role a policy applies to; `PUBLIC` is a row.

Command, mode and roles are stored because the follow-up warnings reason over them; in this item they
are only displayed, but they are not display text in the store.

A follow-up that needs structure from an expression extracts the direct facts during the reading and
stores them as rows: a policy reads a setting, a policy calls a function, a function
reads a setting, a function calls a function. The parse happens once, at capture, over what the
database returns: the expression's node tree where the database exposes one, a function's source text
where it does not. That passes the recompute test in `docs/architecture/explanation/fact-model.adoc`
§ "Gatherers build the model in a declared order", because the store holds neither the node tree,
the function source nor the expression text. Which
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
* `store_graph_source_failure`: one row per claim whose last reading failed, saying why (the cause
  the warning logged: refused connection, timeout, version below the minimum) and when. It keys and
  foreign-keys the claim, `(graph_name, source_name)` to `store_graph_source` with `ON DELETE CASCADE`,
  and a successful reading deletes it, so absence means the last reading did not fail. It records
  the run rather than the corpus, so it is a `store_` relation, declared as its run-record siblings
  are: `store_graph_progress` and `store_graph_write` are declared with the `capture` owner while each
  gatherer writes its own rows, and this one is written by the `database` gatherer the same way. It
  is what the readers show as the cause.

### Who drops a claim

The gatherer owns the graph's `DATABASE` claims, on the shape `JooqFactCapture.dropMembership` gives
the `JOOQ_SCHEMA` kind. Every capture calls it, connector or not:

* With a connector, it claims the source the connector names and drops the graph's other `DATABASE`
  claims. A url that changes host, port or database therefore leaves one claim, never two, and the
  readers' `(table_schema, table_name)` lookup sees one reading.
* Without a connector, it reads nothing and drops every `DATABASE` claim the graph holds. A consumer
  who removes `<database>` stops seeing the facts on the next capture.

After dropping claims, the gatherer releases its own kind, as `GraphQLSourceCapture` and
`ClasspathSourceCapture` do for theirs: it deletes the `DATABASE` sources no graph claims, with
`SourceClaims.unclaimed()` in the delete's own `WHERE`, and the `database_` rows go with them through
their `ON DELETE CASCADE` from `store_source`. There is no shared release to lean on; each gatherer owns
the reclaim of its kinds. "Without connection settings nothing changes"
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

One read-only transaction, three statements:

* First, the version: `server_version_num`. The reading needs PostgreSQL 10 or later, because
  `pg_policy.polpermissive` first exists there. Below that, the reading stops before reading any
  policy and is recorded as a failed reading whose cause names the version and the minimum, so the
  readers say why (§ Reader arms), rather than failing on a missing column. The version is checked,
  not stored. Version-dependent catalog columns a follow-up needs (`security_invoker` on views needs
  15) are that follow-up's to gate the same way.
* `pg_class` joined to `pg_namespace` where `relkind` is `r` or `p`, outside `pg_catalog`,
  `information_schema` and `pg_toast`: schema, table, `relrowsecurity`, `relforcerowsecurity`.
* `pg_policy` joined to `pg_class` and `pg_namespace`: the policy name; `polcmd` and
  `polpermissive` decoded at capture into the words the table holds; `polroles` resolved to names
  through `pg_roles`, which the connecting role can read, with the `{0}` entry read as `PUBLIC`. The
  catalog is read
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
  mode, roles. The expressions are in the table's definition, which the hover on the `@table`
  argument shows live in `dev` (§ Definitions on request);
* stale (claim stamp cleared, `read_at` kept): the same, marked as from the reading at `read_at`;
* never read successfully (neither): `Row security: the database could not be read`, with the cause
  from `store_graph_source_failure`; the stale arm shows that cause too.

Every arm that shows facts names where they came from, from the claim: `read from
postgres://localhost:5432/sakila at <read_at>`. What the server reports about itself is display, so it
is shown live with a definition, not stored.

### MCP

`catalog.describe` gains a `rowSecurity` member with the same arms and the same provenance, read
through `CatalogQueries`. The `docs/manual/how-to/mcp-agent-context.adoc` description of
`catalog.describe` says so.

## Definitions on request

What graphitron reasons over is a stored fact; what only a person or an agent reads is fetched when
asked. The store knows that an object exists and what the follow-ups reason over; looking at it is done
on need. Definitions are large text nothing reasons over: capturing every routine and trigger body on
every round costs in proportion to the schema for an answer wanted one object at a time. So they are
read live, through the same `<database>` connector, and never enter the store. A live read may render;
it never decodes, stores or reasons over what it reads.

### The live interface

Two interfaces in `graphitron-model`, which both `graphitron-mcp` and `graphitron-lsp` already depend
on:

* `DatabaseConnector` opens a connection. The model module declares it and gains no driver
  dependency; the plugin implements it from `<database>`, loading the driver from the consumer's
  classpath as `DevQueryExecutor` does today, with the connect timeout, read-only mode and the dialect
  check. The gatherer reads through it, and `execute` moves onto it, so there is one connection path.
* `LiveDatabase`, in a package of its own (`no.sikt.graphitron.model.live`), is the one entry point
  for anything fetched on demand. One method in this item, `definition(ObjectRef)`, answering a sealed
  `Found` (the SQL, the provenance the server reports, and whether part of it is a reconstruction) or
  `Refused` (the cause). The model module holds only the interface, `ObjectRef` and the two answers;
  it is the fact tier, and a reader of a foreign corpus is not a resident there. The PostgreSQL
  implementation, with the `pg_get_*` calls and the jOOQ `Meta` composition, lives in
  `graphitron-maven-plugin` beside the connector it reads through, and `DevMojo` builds it. The MCP
  tool and the editor hover are thin adapters over the interface and write no SQL. A later
  on-demand need is another method here, not another code path in two consumers.

`ObjectRef` is a kind, a schema and a name. A consumer never builds one from an authored string: it
takes it from what the store already holds, through the jOOQ reading, each consumer with its own
query, as every use of the store owns its own (`fact-model.adoc` § "One base, many views"), so
`LiveDatabase` holds no name-to-ref helper. Tables and views are `sql_table` rows (`table_type` tells
them apart), routines are `sql_routine` rows, keys are `sql_constraint` rows, and a policy is a
`database_policy` row.

Overloaded routines are not resolved in this item. `sql_routine` is keyed on the routine's name, and
its parameters carry Java binding types, not SQL types, so the store cannot name one member of an
overload set. A routine name that matches more than one function in the database is refused with that
cause. Keying the jOOQ reading on a signature is that reading's own change, not this item's.

`ObjectRef` assumes `sql_table.table_schema` is the database's own spelling of the schema. A jOOQ
configuration that renames schemas (`outputSchemaToDefault`, named in the `sql_table.source_name`
comment) breaks that, for the live lookup and for the `database_` join alike, and shows as a refusal
or as a table with no row-security facts.

The two corpora can disagree. An object the database has and the store lacks is never asked about,
since the schema can only name what the jOOQ classes know. An object the store knows and the database
no longer has is refused with a cause that says the jOOQ classes look stale against the database; that
is the same finding as the anti-join in § Matching `sql_table`, worded alike, with no relation or report
of its own. So the store needs no new existence relations for this item: knowing that the
object exists is already there, and an object the store does not know gets no definition and costs no
round-trip.

Wiring: `DevMojo` builds one `LiveDatabase` when `<database>` is set, passes it to
`GraphitronMcpServer`, which registers `database.definition` only when it has one, and to `DevServer`,
which hands it to `GraphitronLanguageServer` as an optional constructor argument beside
`onSchemaSaved`. The standalone `Launcher` and every goal other than `dev` pass none.

Connection handling: `LiveDatabase` holds one connection, opened on first use, checked with `isValid`
before each read and reopened after a failure, used by one request at a time, each read in a read-only
transaction with a statement timeout, and closed when `dev` stops. No definition is cached: definitions
change while an author works, and a cache would mostly show stale text. A cache is added only if hover
latency shows a need.

### Where it is available

In `dev`, where the MCP server and the language server run (§ The live interface). With no
`<database>` (or `<devDatabase>` during the window), the MCP tool is not registered and the
editor shows no definition, as `execute` is absent today. The standalone language server (`Launcher`)
has no connector and shows none. `generate`, `validate`, `capture` and CI never fetch a definition.
A policy's definition is available only after a successful reading, because its `ObjectRef` comes
from `database_policy`; a table's definition, which includes its policies, needs no reading.

### MCP

A read-only tool `database.definition` taking an object kind (table, view, routine, key, policy) and a
qualified name, and answering that object's definition through `LiveDatabase`. The tool resolves the
name against the store first, which turns an unknown name into a cheap refusal. The split the prefixes state is stored versus fetched live: `catalog.` tools
answer from the store, which includes the `database_` facts that `catalog.describe` reports, and
`database.` answers from the live database. Its answer opens with a provenance line read live from the
connection it answers on (product, version, database name, connected role). It refuses with its cause
where the session has no database, where the store does not know the object, where the database no
longer has it (the jOOQ classes look stale), where a routine name is overloaded, where the dialect is
not read yet, and where the declared dialect disagrees with the
connection.

### Editor

On hover of the argument that names a database object: a `@table`'s table, a `@reference` key, a
`@routine`'s routine. The hover starts from the object the store already resolved that argument to
(`graphitron_tabletype`'s table triple, the resolved key, the resolved routine), never by resolving the
authored string again; an argument the store did not resolve shows no definition. The answer is
fetched through `LiveDatabase` when that hover is asked for, shown as a SQL block, and bounded by the
statement timeout; a failure shows its cause in the hover instead. The type and field
hovers that read the store stay as they are, one statement and no view (`DeclarationHoverStatementCountTest`,
`EditorReadsNoViewTest`), so a live read never slows an ordinary hover.

### What the database offers

PostgreSQL first, because sakila tests it:

* routine: `pg_get_functiondef`;
* view: `pg_get_viewdef`, wrapped as `CREATE VIEW`;
* policy: rendered as `CREATE POLICY` from `pg_policy`, with `pg_get_expr` for its `USING` and
  `WITH CHECK` expressions and its comment;
* key (what a `@reference` names): `pg_get_constraintdef`, wrapped as `ALTER TABLE ... ADD CONSTRAINT`;
* table: PostgreSQL has no single statement for a table, so any table definition is a reconstruction,
  and the answer labels that part as one: it can leave out what jOOQ's `Meta` does not model (identity
  and generated columns, partitioning, exclusion constraints), beside the database's own text.
  The table part comes from jOOQ's runtime metadata over the live connection,
  `DSLContext.meta().filterTables(...).ddl()` filtered to the table the store resolved: columns,
  primary key, unique, foreign key and check constraints, indexes and comments, in jOOQ's normalized
  rendering. jOOQ's `Meta` has no triggers, routines or policies, so the definition then appends
  `pg_get_triggerdef` for each trigger and its function's `pg_get_functiondef`, and the table's
  row-security statements and policies rendered as the policy kind is, read live in the same
  transaction. Triggers have no kind of their own: the schema names none, so the store knows none, and
  they are shown with their table.

Every kind other than the table uses the database's own rendering. jOOQ's `meta().ddl()` is also
dialect-neutral, so the table part works on Oracle as written; Oracle's other kinds would come from
`DBMS_METADATA.GET_DDL`. Until Oracle is read, the tool refuses every Oracle request with that cause,
as the gatherer logs it, so Oracle lands as one change.

## Keeping the facts out of generation

Generated output must be identical whether the gatherer ran or not. Three gates make that structural:

* A guard in the style of `PackageImportDirectionTest`: no class in the generator's emit path refers
  to a `database_` table's generated jOOQ class, and `no.sikt.graphitron.model.live` is referenced
  only from the `dev` goal and the MCP and language-server packages, so `generate`, `validate`, `capture` and CI
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
  connection settings are given, records only what graphitron reasons over, and turns anything found
  only by connecting into a reading outcome rather than a failed goal;
  `docs/architecture/explanation/fact-model.adoc` § "One base, many views" states that its facts never
  change generated output.
* `docs/architecture/explanation/fact-model.adoc` § "One base, many views" also gains the limit of the
  live read beside it: display text from a live corpus may be fetched and rendered on request, is
  never decoded, stored, joined or reasoned over, and is fetched only in `dev`, through
  `LiveDatabase`. Without it, the next consumer wanting a live answer has a precedent and no limit.
* `docs/architecture/reference/modules.adoc`: `graphitron-model` holds the `LiveDatabase` interface
  and `DatabaseConnector`, and `graphitron-maven-plugin` holds their implementations.
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
* A failed reading after a successful one writes a `store_graph_source_failure` row with the cause,
  which a later successful reading deletes.
* A declared dialect the connection disagrees with is a failed reading in capture, naming both, and a
  refusal from `database.definition`; the goal does not fail.
* `database_policy` holds no expression text or comment.
* The version gate: a server version below 10, stubbed at the version check, records a failed reading
  whose cause names the version and the minimum, and reads no policy.
* `polroles` entries resolve to role names through `pg_roles`, and `{0}` reads as `PUBLIC`.
* Mojo configuration tests over the injected environment map: `<database>` alone; env overriding POM;
  `<devDatabase>` alone (works, warns); `<devDatabase>` with `<database>` (fails); legacy variables as
  fallback (works, warns); no url (gatherer and `execute` absent); a url with no accepting driver
  (fails).
* Hover: the five arms, with the statement-count and no-view tests still green.
* MCP: `catalog.describe` on `secure_note` with and without a reading.
* The two generation gates above.
* `database.definition` (`@ExecutionTier`): each kind against sakila, including the composed table
  definition of `secure_note` with its policy and expressions; the refusals (no database, a name the
  store does not know, an object the database no longer has, with its stale-classes cause, an
  overloaded routine name, an unread dialect, a dialect mismatch).
* `LiveDatabase` holds one connection across calls and reopens it after the database restarts.
* Editor: the definition hover on a `@table`, a `@reference` key and a `@routine` argument with a
  connector; no definition without one; an unreachable database shows its cause; an argument the store
  did not resolve shows none. The new hover target gets its row in `TriggerDispatchMatrixTest`.
* `graphitron-sakila-db/src/main/resources/init.sql` also gains a view and a trigger, so every kind,
  and a table's triggers, have something to show.

## Follow-ups, filed when this reaches Ready

Each is its own rule with its own codes, fact document and near miss. Each raises warning defects
from an anchor stage of the `database` gatherer, which is the first stage holding every fact the rule
reads, so the `graphitron` gatherer gains no edge to an optional upstream. The candidates:

* a non-null field reached through `@reference` into a row-secured table, which resolves to null when
  a policy hides the target row, even where the foreign key is `NOT NULL`;
* a DML mutation whose `WITH CHECK` admits a row its read policy hides, so the payload's re-read
  returns nothing (the `secure_note` case), and a row-secured table with no policy for the mutation's
  command, which the database denies by default. Comparing the two expressions needs structure this
  item does not store, so this follow-up captures what it reasons over at capture, as the
  setting-read follow-up does;
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
  naming the runtime role(s) is the obvious home; it is left to the follow-ups. In `dev`, the role a
  live definition reports it connected as is a first answer, when the dev database is reached as the
  application's role.
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
* Before the second review: `database_server` written only with a successful reading and covered by
  the stamp, so a failure never puts one server's provenance above another reading's rows: adopted.
  The stored role named in the configuration open question: adopted. jOOQ's `Meta` is a renderer, not
  a second reading, but its table answer is a reconstruction and is labelled one; the limit sentence
  says a live read may render but never decode: adopted. The failure cause had no home: adopted, as
  `store_graph_source_failure` on the `store_` side. The reclaim through `SourceClaims.unclaimed()`
  matches the existing gatherers. Its point on a dialect mismatch went to Alf, who adopted it: what
  needs a connection is a reading outcome (§ Configuration).
* After Alf's ruling that the store records only what is reasoned over: `database_server` was removed
  rather than kept (its contents are display, now reported live with a definition), the expression
  text and comment left `database_policy`, and the live interface (`DatabaseConnector`,
  `LiveDatabase`, `ObjectRef` taken from the store's existing rows) was specified. Consulted on it:
  the overload claim was false against `sql_routine`'s name-only key: corrected, overloads are refused.
  A disagreement between the jOOQ classes and the database is refused with a stale-classes cause,
  worded like the anti-join: adopted. The schema-spelling assumption is stated: adopted. The
  implementation moves to the plugin and the model module keeps only the interfaces, with
  `modules.adoc` updated: adopted. Each consumer writes its own store query for its `ObjectRef`:
  adopted. Why command, mode and roles are stored is stated, and a policy's definition needs a
  reading: adopted. It found no remaining reader of stored policy text.

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

### Round 3: Spec → Ready, signed off (session_01WkW2JGfej6rwMoQpa9r7hy, 2026-11-17)

Question 1 passes. When this lands, a consumer who configures `<database>` sees, in the editor's
hover on a `@table` type and in `catalog.describe`, whether the bound table enforces row security
(forced or not) and which policies it declares, with each one's command, mode and roles. In `dev`,
they can also ask for the database's own definition of a table, view, routine, key or policy, through
`database.definition` or by hovering the argument that names the object. Generated code does not
change, and nothing is stored that only a person reads. The claims the spec makes about code hold:
every class, test, relation, column comment, doc section and fixture it names exists as named.
`sql_routine`'s name-only key and its overload hole are as described, and the stored resolutions the
hover starts from exist (`graphitron_tabletype`, `graphitron_path_element_key`,
`graphitron_path_element_routine`).

Question 2 passes. The gatherer reuses the shape `JooqFactCapture` already has (`dropMembership`,
`sweep`, `SourceClaims.unclaimed()`), the reading record fits the existing comments on
`store_graph_source`, and the live interface is a single entry point rather than a second code path
in the MCP server and the language server.

Non-blocking, for the implementer:

* `FactCapture.derive` is the derivation gatherer, and the `meta_gatherer_dependency` comment says it
  depends on every crawler. So "after the graphitron gatherer" should mean after
  `GraphitronAstCapture` (and the assembly) but before `FactCapture.derive`, with a
  `('derivation', 'database')` edge beside the others.
* `ModelCapture.capture` returns early when a document will not parse, before the graphitron
  gatherer. A capture that stops there does not reach the database gatherer, so it neither reads nor
  drops claims, and the store keeps its last state. Read § Who drops a claim's "every capture calls
  it" with that exception.
* In `DatabaseFactCaptureTest`, the gatherer reads over the connector's own connection, so a policy
  dropped inside the test's uncommitted transaction is invisible to it unless the test's connector
  hands out that same connection.
* The guard on `no.sikt.graphitron.model.live` has to see the plugin, `graphitron-mcp` and
  `graphitron-lsp` sources. `PackageImportDirectionTest` scans only its own module, so the guard
  needs a home that reads across modules, as `RoadmapReferenceGuardTest` does.
* The claim's `source_name` has to come from the connector, so `DatabaseConnector` names its
  credential-free source as well as opening a connection.
