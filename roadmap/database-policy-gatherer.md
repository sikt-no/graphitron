---
id: R1184
title: "An optional gatherer reads the database's row-level security policies over a live connection"
status: Spec
bucket: architecture
priority: 4
theme: diagnostics
depends-on: []
created: 2026-11-13
last-updated: 2026-11-14
---

# An optional gatherer reads the database's row-level security policies over a live connection

## Goal

A schema author sees what the database's row-level security says about the tables their schema
binds. Hovering a `@table` type in the editor, or asking `catalog.describe` over MCP, answers whether
the table secures its rows, whether that is forced, and each policy's command, mode, roles and
`USING` / `WITH CHECK` text. The facts come from a new optional gatherer that reads the database's own
catalog over a live connection, configured by a new `<database>` block that replaces `<devDatabase>`.
Without connection settings nothing changes, and generated code is the same whether the gatherer ran
or not. The warnings these facts make possible are follow-up items (§ Follow-ups); this item lands the
facts, their reading record, the readers named above and the gates that keep the facts out of
generation.

## Why a live connection

The jOOQ-generated classes are how graphitron knows the database today, and they carry no policies.
The facts therefore come from the database itself, through a gatherer that runs only when it is given
connection settings. Alf gave this direction when the item was filed, over the alternatives of a
sidecar written at jOOQ codegen time and a parse of migration scripts. The ruling enters
`docs/architecture/` in this item (§ Docs).

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
`COMPILE` today. They widen to `TEST` only when `<database>` is configured, if Maven allows that
conditionally; otherwise the driver must be on the compile classpath for those goals, and the manual
says so. Implementation settles which.

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
`source_name` a foreign key to `store_source` (`ON DELETE CASCADE`) and a `touched_at` mark. The rows
that hang off a policy (`database_policy_role`) also carry a foreign key to that policy's key with
`ON DELETE CASCADE`, so the sweep of a policy is what collects them.

* `database_table_security`: one row per table the database secures by row, with whether that
  security is forced. A table absent here, in a reading that happened, does not secure its rows.
* `database_policy`: one row per policy a table declares. Columns: command (`ALL`, `SELECT`,
  `INSERT`, `UPDATE`, `DELETE`), mode (`PERMISSIVE`, `RESTRICTIVE`), and the `USING` and `WITH CHECK`
  text as the database renders it, and the policy's comment. Each of those three columns is display material, never a dimension, and the DDL says so in the same words; no reader
  string-searches it. A follow-up that needs structure from an expression captures it at capture, beside the text, never by parsing the stored text.
  A table may declare policies while its row security is off, and the database then does not apply
  them. So `database_policy` has no foreign key to `database_table_security`: such policies are
  stored, and the readers show them as declared but not in force.
* `database_policy_role`: one row per role a policy applies to; `PUBLIC` is a row. It has a foreign key
  to its `database_policy` row (`ON DELETE CASCADE`), so sweeping a policy collects its roles.

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
This item stores nothing for it, because the finding has no SDL position to hang a defect on (see
Open questions).

### The reading record

* `source_name` is a credential-free rendering of the connection, `<dialect>://<host>:<port>/<database>`,
  built from the parsed url. Never the raw url, which can carry `password=` and other parameters; the
  store is readable by agents through `store.console`.
* A `store_source` row of `source_kind` `DATABASE`, with `read_at` and a `stamp` that hashes the
  captured rows. The stamp commits after the facts it vouches for, in the same transaction.
* A `store_graph_source` row claims the source for the graph, and a `store_graph_progress` row records
  the gatherer's start and completion, as for every gatherer.

Readers answer in three arms:

* no `DATABASE` source claimed by the graph: not read, and the readers say nothing about row
  security;
* a source with a stamp: read, and a table with no `database_table_security` row does not secure its
  rows;
* a source without a stamp: the last reading failed (see Unreachable database).

### Cadence

Every capture that is given a connector re-reads. The reading is three catalog queries, cheaper than
working out whether anything changed. Rows are upserted with the round's `touched_at`, and the
gatherer sweeps its own family for this source, as `JooqFactCapture.sweep` does for `sql_`.

### Unreachable database

When the connection or a query fails, the goal logs a warning naming the source and the cause, and
continues: output does not depend on these facts, so a database that is down must not stop a build.
The gatherer clears the source's stamp and keeps its rows, so the readers can say the facts are from
an earlier reading and when. Whether stale rows should be kept or swept is an open question for Alf
and Filip; keeping them is this spec's default.

### Postgres reading

One read-only transaction, three statements:

* `pg_class` joined to `pg_namespace` where `relrowsecurity`, `relkind` in `r`/`p`, outside
  `pg_catalog`, `information_schema` and `pg_toast`: schema, table, `relforcerowsecurity`.
* `pg_policy` joined to `pg_class` and `pg_namespace`: the policy name, `polcmd` and `polpermissive`
  decoded at capture into the words the table holds, `polroles` with the `{0}` entry read as `PUBLIC`,
  `polqual` and `polwithcheck` rendered by `pg_get_expr`, and the policy's comment. The catalog is read
  directly, not through the `pg_policies` view, which derives from the same catalog and adds role names
  and a fixed layout; the reading keeps the raw codes it decodes itself. Role names come from `pg_roles`,
  which the connecting role can read.
* `pg_roles`, for the role names `polroles` points at, as the previous bullet resolves them.

With dialect `ORACLE` the gatherer does not run and logs that Oracle is not read yet. Oracle's VPD
(`ALL_POLICIES`) fits the same relations later. Grants (one row per table, grantee and privilege) fit
the same gatherer later too.

### Place in the capture

In this item the gatherer is a crawler: what it captures about the database may not vary with any
other corpus, so it declares no `meta_gatherer_dependency` rows. It writes its own `store_source` and
`store_graph_source` rows, as `jooq` does without an edge.

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
* stale (stamp cleared): the same, marked as from the reading at `read_at`.

### MCP

`catalog.describe` gains a `rowSecurity` member with the same arms, read through `CatalogQueries`. The
`docs/manual/how-to/mcp-agent-context.adoc` description of `catalog.describe` says so.

## Keeping the facts out of generation

Generated output must be identical whether the gatherer ran or not. Three gates make that structural:

* A guard in the style of `PackageImportDirectionTest`: no class in the generator's emit path refers
  to a `database_` table's generated jOOQ class.
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
* `docs/manual/reference/mojo-configuration.adoc`: `<database>` under shared parameters, `<execute>`
  under `dev`-goal parameters, the migration from `<devDatabase>`, the driver-scope rule. The sentence
  that the store holds nothing "that cannot be rebuilt from your sources" gains the database as a
  source.
* `docs/manual/how-to/mcp-agent-context.adoc`: `execute` connects with `<database>`;
  `catalog.describe` reports row security.
* `docs/security.adoc`: one paragraph saying the editor and MCP show the policies when a database is
  configured.

## Tests

* `DatabaseFactCaptureTest` (`@ExecutionTier`, local PostgreSQL): reads sakila and asserts the
  `database_` rows for `secure_note`. Two readings exercise the sweep: the second, after a policy is
  dropped inside the test's own transaction, removes its rows.
* `graphitron-sakila-db/src/main/resources/init.sql` gains what the arms need: a table with forced row
  security, a restrictive policy, a policy for one command, and a policy for a named role besides
  `PUBLIC`. `secure_note` keeps its role as the permissive `ALL` case.
* Reading record: no connector leaves no source row; a connector whose url refuses connections leaves
  a warning, a source without a stamp, and the previous rows.
* `source_name` never contains the user, the password or the url's query parameters.
* `polroles` entries resolve to role names through `pg_roles`, and `{0}` reads as `PUBLIC`.
* Enforcement fixtures act as an application role (`SET ROLE`), not as the owner: the owner bypasses
  row security unless the table is forced, so a read as the owner says nothing about enforcement.
* Mojo configuration tests over the injected environment map: `<database>` alone; env overriding POM;
  `<devDatabase>` alone (works, warns); `<devDatabase>` with `<database>` (fails); legacy variables as
  fallback (works, warns); no url (gatherer and `execute` absent); a url with no accepting driver
  (fails).
* Hover: the four arms, with the statement-count and no-view tests still green.
* MCP: `catalog.describe` on `secure_note` with and without a reading.
* The two generation gates above.

## Follow-ups, filed when this reaches Ready

Each is its own rule with its own codes, fact document and near miss. Each raises warning defects
from an anchor stage of the `database` gatherer, which is the first stage holding every fact the rule
reads, so the `graphitron` gatherer gains no edge to an optional upstream:

* a non-null field reached through `@reference` into a row-secured table, which resolves to null when
  a policy hides the target row, even where the foreign key is `NOT NULL`;
* a DML mutation whose `WITH CHECK` admits a row its read policy hides, so the payload's re-read
  returns nothing (the `secure_note` case), and a row-secured table with no policy for the mutation's
  command, which the database denies by default;
* a policy reading a session setting without treating `NULL` and `''` alike as no identity, the
  fail-closed rule in `docs/security.adoc`. This one captures each policy's setting reads at capture,
  beside the text, and the functions a policy reaches with them: the walk over function bodies and the
  edges it produces move here, since this is their first reader. Checking setting names against the
  `SessionState` mount needs a new fact, since the store does not record what the mount writes;
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

Consulted at filing and at take-up. What it raised, and what this spec did:

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
