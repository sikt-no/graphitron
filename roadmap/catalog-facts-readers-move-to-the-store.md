---
id: R642
title: "CatalogFacts' non-LSP readers move to the store"
status: Spec
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-12
last-updated: 2026-08-14
---

# CatalogFacts' non-LSP readers move to the store

Sibling of the LSP fact-store item (`lsp-reads-the-fact-store.md`), which retires the
`CatalogFacts` projection but cannot delete it while non-LSP consumers read it:
`GraphitronMcpServer`'s `catalog.tables` and `catalog.describe` tools, `EdgeProducer`, `EdgesTool`,
`ReverseEdgeIndex`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`,
plus `GraphQLRewriteGenerator`, whose output record carries the projection. These migrate to
store-side reads here, apart from the LSP work, because the MCP catalog tools have their own
acceptance surface (tool output, paging) that has nothing to do with cursors and buffers, and
because folding them into the LSP item would credit their `rewrite/catalog` lines to that item's
simplification measurement. Two constraints bind the siblings: `CatalogFacts` deletes in the same
commit as its last reader's migration, and both consumers read one shared store-side catalog view,
never a narrowing made for one of them (the `FactCapture.capture` javadoc already states this).
`TenantScopes` and `McpWire` cite the type only in javadoc and just repoint.

## The census is already captured

Nothing new is captured here. `CatalogFactCapture.captureCatalog` walks the same `JooqCatalog` the
projection was built from and writes the `sql_` family: `sql_schema`, `sql_table` (with
`jooq_name`, `class_fqn`, `description`), `sql_column` (with `ordinal`, `jooq_name`, `sql_type`,
`binding_type`, `nullable`, `description`), `sql_constraint` discriminated by `constraint_type`
with its ordered `sql_constraint_column` rows, `sql_primary_key` naming which constraint is the
primary key, `sql_index` / `sql_index_column`, and `sql_referential_constraint` naming the
referenced constraint. Every field the two catalog tools put on the wire is in there.

Three shape differences are the whole substance of the migration, and all three are the store being
more honest than the projection:

* **Foreign keys are stored once, on the declaring side.** The projection denormalised each key
  into an `outgoing` list on one table and an `incoming` list on the other. Here the incoming
  direction is a predicate on the same relation, which is most of the point of having a store.
* **Referenced columns are not copied onto the referencing row.** They are the referenced
  constraint's own `sql_constraint_column` rows, matched on position. A reader that wants the pairs
  joins for them.
* **Constraints are not deduplicated and the primary key is not split out.** The projection read
  `JooqCatalog.candidateKeys`, which drops a unique constraint whose column set the primary key
  already covers; that is a choice serving the UPDATE key match, and a foreign key referencing a
  dropped constraint would point at nothing. The store keeps what the database declares, so
  "the unique keys other than the primary key" is a query.

## One reader family, read by both consumers

The LSP's migration landed its store reads as small static readers over `StoreHandle` in
`graphitron-lsp`'s `facts` package: `CatalogTable` (the census key: source package, schema, table),
`CatalogColumns`, `CatalogKeys`. The catalog reads this item needs join that family rather than
starting a second one, and `graphitron-mcp` already compiles against `graphitron-lsp`, so the
direction works: MCP reads the same readers, unchanged, and the readers it needs beyond them are
added there.

That is deliberately not a claim that `graphitron-lsp` is the right long-term home for facts two
modules read. It is not, and a package named for one of two consumers is the kind of private model
the architecture docs argue against. But the home question covers four families (SDL, catalog,
`jvm_`, java-source), not the catalog alone, it is answered once for all of them or not at all, and
moving a package the sibling item is actively rewriting buys no behaviour. So this item extends the
family where it stands and leaves the relocation to its own item; what it does owe is that nothing
here is shaped for the MCP consumer alone. Where an MCP read wants more than an LSP read, the
shared reader widens (a column list grows, an overload is added) and never forks.

The one wrinkle is `CatalogColumns`, which overlays the generated field's Javadoc through
`SourceDeclarations`. That overlay is a correlated `Field<String>` and stays; MCP passes none and
reads the Javadoc component as empty, which is the same "absence is a fact" reading the LSP gets on
a source tree nobody has parsed.

## What the readers must answer

Per consumer read, with the reader that answers it:

* **The table census, ordered and filtered.** Every table of this graph's sources, optionally
  narrowed by exact case-insensitive schema and case-insensitive substring on the SQL name, ordered
  by schema then table name. New entry point on a `CatalogTables` reader; `catalog.tables` pages
  over the result, `catalog.search` composes its corpus from it, `EdgeProducer` resolves bare names
  against it.
* **A spelling resolved to a table.** Bare (`film`) or inline-qualified (`public.film`), with a
  separate schema argument as the alternative to inline qualification (inline wins), all matching
  case-insensitive. Same reader, same query, different filter. No sealed outcome type is minted for
  it: how many rows came back is the answer, per the sibling item's "sealed resolution outcomes",
  and the wire taxonomies that already exist (`EdgesTool.Selection`, `catalog.describe`'s
  `resolution` field) keep their arms and read the row count.
* **A table's columns**, in `ordinal` order. `CatalogColumns.of(store, CatalogTable)` today, plus a
  census overload for the whole graph so the search corpus and the reverse index read columns in
  one query rather than one per table.
* **A table's uniqueness constraints**: the primary key, and the unique constraints other than it,
  each with its constraint name and ordered columns. New reader over `sql_constraint` /
  `sql_constraint_column` / `sql_primary_key`. Worth its own reader rather than a widening of
  `CatalogKeys`, whose subject is referential constraints.
* **A table's indexes** with their ordered columns. New reader, one query with its column join.
* **A table's foreign keys in both directions, with column pairs.** `CatalogKeys.touching` already
  answers both directions in one query and already carries the `Keys` constant the LSP hovers; it
  grows the two ordered column lists (the constraint's own columns, and the referenced constraint's
  columns matched on position). A widening the LSP ignores, not a fork.

Every one of these is scoped through `store.reads(...)` on the relation's `source_name`, which is
what keeps a sibling module's catalog out of the answer in a shared store.

## The consumers

**`catalog.tables`** takes the handle instead of the projection: one census query with the two
filters applied in SQL, then the existing opaque-cursor paging over the result. The wire entry
(`schema`, `name`, `comment`) is unchanged.

**`catalog.describe`** resolves the spelling, then reads columns, constraints, indexes and keys for
the resolved table. Five small queries where there was one map lookup, and they assemble inside one
read transaction so a capture committing mid-call cannot leave the columns of one generation beside
the keys of the next. That is the same discipline `StoreAccess.answering` gives the LSP; the MCP
handle is the session writer's own connection, so the tool wraps its own transaction.

**`catalog.search`** loses its `Supplier<CatalogFacts>` and composes the corpus from the census plus
the column census. `CatalogDescriptors.descriptor` takes the shared reader's row shape and is
otherwise untouched, so the descriptor text, and therefore the corpus hash and every persisted
index directory, stay byte-identical for an unchanged catalog. The composition runs on the request
thread inside `observe()`, never on the `AsyncWarm` daemon: the handle carries the writer's
connection and turn-based access is what makes sharing it safe. The background warm keeps doing
only what it does today, which is embedding strings it was handed.

**`edges` and the reverse index.** `EdgeProducer.Context` swaps its `CatalogFacts` component for
the handle plus a census read once per context. Once, not per resolution: `ReverseEdgeIndex.build`
walks every classified field and resolves a bare table name for most of them, so a query per
resolution would turn one index build into thousands of round trips. A census read inside one
context is not a projection revival; it is request-scoped, it is shaped by the query rather than by
a builder pass, and it dies with the call. Table-to-table FK edges (`outgoingFkEdges` /
`incomingFkEdges`) read `CatalogKeys.touching` for the queried table, which is where the reverse
FK direction was already a query rather than an index.

**`GraphQLRewriteGenerator`** stops building the projection: `BuildArtifacts` loses its
`catalogFacts` component and the convenience constructor that defaulted it, `buildOutput` stops
calling `CatalogBuilder.buildCatalogFacts`, and `DevMojo` stops threading the value through
`setBuildOutput` and its catalog-refresh path. `Workspace` loses the field and accessor.

## The capture stamp replaces reference identity

Two memos key on the projection's reference identity today, and both need a store-side key.
`CatalogSearchIndex.observe`'s first gate skips a re-read when the `CatalogFacts` reference is
unchanged; `ReverseEdgeIndex.Cache` rebuilds when either the snapshot or the facts reference swaps.
There is no reference to compare once the facts are rows.

`store_graph.last_captured` is the key: capture upserts it on every pass, for the graph the handle
names, so reading one timestamp answers "has anything been captured since I last looked" at the
same granularity `setBuildOutput`'s swap answered it. A small reader beside the others returns it,
and both memos use it: the search index as gate one (gate two, the corpus content hash, is
unchanged and still what prevents a re-embed when the pass changed nothing), the reverse index as
half of its `(snapshot, capture stamp)` key.

## Where the answers change

The tool output is the acceptance surface, so the deltas are named rather than discovered:

* **Unique keys are what the database declares.** A unique constraint whose column set the primary
  key also covers was dropped by `candidateKeys` and now appears in `uniqueKeys`. This is the
  intended direction: `catalog.describe` reports the catalog, and the dedup was another consumer's
  key-matching rule leaking into a discovery tool.
* **Table order becomes schema then table name.** Today it is the generated `Tables` class's
  reflective field order, which the JDK does not promise is stable at all, and page cursors are
  offsets into it. Alphabetic order is both stateable and stable, which is what a cursor needs.
* **Column order becomes the table definition's.** `sql_column.ordinal` is the position
  `Table.fields()` states; the projection carried the reflective field walk's order, which is
  documented as no order in particular.
* **One coordinate answers once.** The store's key is (source package, schema, table), so a graph
  whose sources carry one `schema.table` coordinate twice has two rows where the projection's map
  silently kept the last. The wire ID is `schema.table` and cannot name the source, so the census
  read collapses on the coordinate. Stated rather than left to the reader to discover from a
  duplicate entry.
* **A missing handle refuses instead of answering empty.** The server can be built without a store
  handle; the diagnostics tools already refuse per call there, on the grounds that an empty answer
  reads as a clean schema. An empty catalog reads as a database with no tables, so the catalog
  tools take the same posture. This is not the pre-capture case: a store with no rows yet is an
  answer, and absence of rows is absence of tables.

## What deletes, and when

`CatalogFacts` and `CatalogFactsTest`, `CatalogBuilder.buildCatalogFacts` and its `toKey` helper,
`BuildArtifacts.catalogFacts` and the convenience constructor, `Workspace.catalogFacts` (field,
accessor, and its assignment in `setBuildOutput`), `DevMojo`'s threading of the value, and
`CatalogSearchIndex`'s facts supplier and `liveFactsRef` gate.

The deletion lands in the same commit as the last reader's migration, which is this item's binding
constraint with the sibling. That is satisfiable today: no `graphitron-lsp` surface reads
`catalogFacts()` any more, only the `Workspace` field the MCP tools read through. Re-check that at
pickup with a `catalogFacts()` grep before planning the commit sequence, because a reader that
reappeared upstream changes the order.

Javadoc citations repoint in the same commit, since the `{@link}` gate fails the build otherwise:
`TenantScopes`, `McpWire`, `NodeRef`, `JooqCatalog`, `CatalogFactCapture` (whose reason for reading
`JooqCatalog` rather than the projection survives as prose about the projection's narrowings, with
the type named as history rather than linked), `FactCapture.capture`'s `@param jooq`, and
`FactCaptureAgreementTest`'s constraint-census comment.

Two doc surfaces state where the tools read from and change with them:
`docs/architecture/how-to/dev-loop-internals.adoc` says the MCP tools are backed by the warm
`Workspace`, which stops being true of the catalog tools; `docs/manual/how-to/mcp-agent-context.adoc`
carries the tool table, whose `catalog.describe` row is the place the unique-key delta becomes a
user-facing sentence if it needs one at all.

## Tests

The MCP module already has the fixture this needs: `StoreBackedBuild` runs a real
`GraphQLRewriteGenerator.buildOutput()` into a bootstrapped store and hands the server a handle,
which is how the diagnostics tools are tested. The catalog tool tests move onto it, and stop
hand-building projection fixtures.

* `GraphitronMcpServerTest`'s catalog cases (the largest block, currently building `CatalogFacts`
  values directly) assert the same wire fields against a store captured from the test jOOQ package.
  Ambiguity, not-found, filters, and paging all keep their cases; the deltas above get one case
  each, so the new behaviour is pinned rather than merely permitted.
* `CatalogDescriptorsTest` and the descriptor half of the RAG tests stay store-free by building the
  shared reader's row records directly: the composer is pure and its tests should not need a
  database. One store-backed case covers corpus composition from real rows.
* `CatalogSearchIndexTest` / `CatalogSearchOnnxTest` replace the facts supplier with the corpus
  seam, and gain a case for the capture-stamp gate: a second capture with an unchanged catalog
  must not re-embed (the hash gate holds), a changed catalog must.
* `ConflictedReverseEdgeTest` and `EdgeCoverageTest` follow `EdgeProducer.Context`'s new shape;
  the coverage test's partition over the classification permits is untouched by this item.
* A store-backed case per shared reader lands with the reader, in whichever module's fixture can
  capture (the LSP's `StoreFixture` for the readers the LSP also reads).

No agreement test between projection and store is worth writing: it dies with the projection in the
same commit, and what it would have asserted is exactly what the per-tool cases assert against a
real capture.

## Retired vocabulary

* `CatalogFacts`, and its nested `Table`, `Column`, `Key`, `Index`, `ForeignKeys`,
  `OutgoingForeignKey`, `IncomingForeignKey`, `TableResolution` (with `Resolved` / `Ambiguous` /
  `NotFound` arms specific to it)
* `CatalogBuilder.buildCatalogFacts`, `BuildArtifacts.catalogFacts`, `Workspace.catalogFacts`
* "the frozen catalog-data projection", "the frozen projection", "catalog facts" as prose for the
  MCP catalog tools' input, and the "frozen, SQL-name-centric" phrasing that described it
* "the live projections one edge computation reads" (`EdgeProducer.Context`'s javadoc), and
  "reference identity" as the name of a memo gate in `CatalogSearchIndex` and `ReverseEdgeIndex`

## Scope boundary

`graphitron-mcp` reads four generator-side projections in all, and this item moves one of them.
`LspSchemaSnapshot` and the classification types stay, being the substrate the sibling item
explicitly defers. `CompletionData.ExternalReference` and `SourceWalker.Index` stay too, even
though the sibling item's "What retires" hands their MCP repoint to "the sibling item" and means
this one: the code tools' Javadoc and location joins are a different family (`jvm_` and the
java-source relations), a different acceptance surface (the `location` / `locationStatus` wire
fields), and share not one query with the catalog reads. Folding them in would double this item and
blur the measurement the split exists to keep clean, so they are filed as their own Backlog item
(`mcp-code-tools-read-the-store.md`), which is where `Workspace.sourceIndex` and its setter retire.

The relocation of the shared reader family out of `graphitron-lsp` is likewise not here; see "One
reader family" above.
