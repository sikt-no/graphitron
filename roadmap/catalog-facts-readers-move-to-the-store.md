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
modules read. It is not, but the risk is worth naming precisely, because it is not the package name.
What crosses the module boundary is a *row vocabulary*: `CatalogColumns.Column`, `CatalogKeys.Key`,
`CatalogTable`. "One model, many views" is satisfied while both modules read the base; it is
strained when one module reads the other's Java view of it, and `CatalogDescriptors` in particular
would go from composing over one consumer's projection type to composing over another's. So the
constraint this item accepts in exchange for deferring the move is that no MCP-only component lands
on those records: a read one consumer wants and the other does not becomes its own entry point, and
a derivation a second consumer asks for becomes a store view.

The relocation itself covers four families (SDL, catalog, `jvm_`, java-source), is answered once for
all of them or not at all, and moving a package the sibling item is actively rewriting buys no
behaviour, so it goes to its own item. One constraint on that item is worth recording now, while it
is cheap: `graphitron-model` has no test sources and cannot acquire them cheaply, because a store
fixture needs `FactCapture`, which lives downstream of it. Readers homed beside `StoreHandle` are
therefore testable only from a consumer module, and whoever answers the home question has to weigh
that rather than rediscover it.

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

  The qualifier split, the case-insensitive match and the membership scope are the rule
  `intent_spelled_table` owns for spellings an author wrote in a directive, and the sibling item
  spent an increment moving it there so it would not acquire a third copy. This is deliberately not
  a fourth: that view's population is authored SDL, and an MCP tool argument is not authored SDL, so
  the view cannot answer for it. What the two owe each other is agreement, so the shared reader
  states the rule once and its javadoc links the view as the site that must match it. If a tool
  argument ever needs to resolve the way a directive does (a lookup by `@table` spelling, say), the
  answer is to read the view, not to widen this reader.
* **A table's columns**, in `ordinal` order. `CatalogColumns.of(store, CatalogTable)` today, plus a
  census overload for the whole graph so the search corpus and the reverse index read columns in
  one query rather than one per table.
* **A table's uniqueness constraints**: the primary key, and the unique constraints other than it,
  each with its constraint name and ordered columns. New reader over `sql_constraint` /
  `sql_constraint_column` / `sql_primary_key`. Worth its own reader rather than a widening of
  `CatalogKeys`, whose subject is referential constraints.
* **A table's indexes** with their ordered columns. New reader, one query with its column join.
* **A table's foreign keys in both directions, with column pairs.** `CatalogKeys.touching` already
  answers both directions in one query and already carries the `Keys` constant the LSP hovers. The
  column pairs are a second entry point on that reader, not extra components on its `Key` record:
  only the wire wants them today, and a shared row record that grows a component one consumer
  ignores is the intersection-cap the sibling item rejected in the other direction. The pairing
  rule (the referencing constraint's own `sql_constraint_column` rows, matched on position against
  the referenced constraint's) has its home in that relation's own DDL comment, and the reader
  renders what the comment states. Should a second consumer ask for the pairing, it graduates to a
  store view rather than to a second Java spelling, which is the doctrine's own escalation.

Every one of these is scoped through `store.reads(...)` on the relation's `source_name`, which is
what keeps a sibling module's catalog out of the answer in a shared store.

Two of the shared row records are keyed too loosely for what the MCP reads ask of them, and the
lift lands here. `CatalogColumns.Column` carries `schema` and `tableName` but not the source
package, so a whole-graph census cannot say which table a row belongs to when two sources carry one
coordinate; `CatalogKeys.Key` carries `referencedTable` as a bare name, while the wire's
`targetTable` field is schema-qualified. Both take `CatalogTable` (the census key the readers' own
javadoc already argues for) in place of the loose names. That is a widening of the shared
vocabulary toward the key the store actually has, not toward one consumer's rendering.

## The consumers

**`catalog.tables`** takes the handle instead of the projection: one census query with the two
filters applied in SQL, then the existing opaque-cursor paging over the result. The wire entry
(`schema`, `name`, `comment`) is unchanged.

**`catalog.describe`** resolves the spelling, then reads columns, constraints, indexes and keys for
the resolved table. Five small queries where there was one map lookup, so a capture committing
mid-call could otherwise leave the columns of one generation beside the keys of the next, and the
answer has to assemble inside one read transaction. Wrapping `dsl.transaction` around the handle
the server holds today does not give that: the handle carries the session writer's own connection,
whose isolation level is whatever the writer left it at, and a wrapper on a connection a capture
may itself be inside is a savepoint rather than a boundary. `StoreReader` is the substrate's own
answer, setting H2's snapshot isolation at mint and stating that a second reader is a mint away, so
the MCP server takes one (opened beside the handle in `DevMojo`, closed with it) and the catalog
tools answer inside `reader.read(...)`, which is the shape `StoreAccess.answering` gives the LSP.
The diagnostics tools' existing reads through the writer handle are left as they are: they are one
query each, so they have nothing to straddle, and moving them is not this item's call to make.

**`catalog.search`** loses its `Supplier<CatalogFacts>` and composes the corpus from the census plus
the column census. `CatalogDescriptors.descriptor` takes the shared reader's row shape and is
otherwise untouched, but the corpus is not: the composer folds column order into each descriptor
and `corpusHash` digests the descriptors in table order, so the two ordering deltas below change
the hash by construction. The first search after the migration pays one full re-embed and the hash
gate self-heals from there, which is a one-time cost worth stating rather than a claim of
byte-identity that the deltas contradict. The composition runs on the request thread inside
`observe()`, never on the `AsyncWarm` daemon, which keeps doing only what it does today: embedding
strings it was handed. With no store to read, the index reports the same refusal the structured
catalog tools report rather than its warming degradation, because a corpus that cannot be composed
is not an index that is still building.

**`edges` and the reverse index.** `EdgeProducer.Context` swaps its `CatalogFacts` component for
the handle plus a census read once per context. Once, not per resolution: `ReverseEdgeIndex.build`
walks every classified field and resolves a bare table name for most of them, so a query per
resolution would turn one index build into thousands of round trips. Holding those rows for a call
is not a projection revival: it is a query result with a request lifetime, shaped by the query
rather than by a builder pass, and it dies with the call.

The rule applied to those rows is the weaker half, and worth naming as transitional rather than
defending. `EdgeProducer` matches a bare name because its input is a *classifier* table name, and
the store's answer to "which table does this type's binding resolve to" is `intent_bound_table` /
`intent_field_column_table`, which the LSP already reads. The Java match survives here only as long
as the classification projection this item does not touch does, and it retires with it rather than
standing as a permanent second home for the resolution. Table-to-table FK edges
(`outgoingFkEdges` / `incomingFkEdges`) read `CatalogKeys.touching` for the queried table, which is
where the reverse FK direction was already a query rather than an index.

**`GraphQLRewriteGenerator`** stops building the projection: `BuildArtifacts` loses its
`catalogFacts` component and the convenience constructor that defaulted it, `buildOutput` stops
calling `CatalogBuilder.buildCatalogFacts`, and `DevMojo` stops threading the value through
`setBuildOutput` and its catalog-refresh path. `Workspace` loses the field and accessor.

## The capture stamp replaces reference identity

Two memos key on the projection's reference identity today, and there is no reference to compare
once the facts are rows. They deserve different answers.

`CatalogSearchIndex.observe`'s first gate skips composing the corpus when the `CatalogFacts`
reference is unchanged. It goes, rather than being re-keyed. Gate two, the corpus content hash, is
already the honest invalidation key, it is what the tests assert, and what gate one now saves is
one census query per `catalog.search` call, on a path that is about to embed text if it misses.
Deleting a cache whose subject is two queries is the cheaper simplification.

`ReverseEdgeIndex.Cache` is the one worth keeping, its subject being a walk over every classified
field, and its key becomes the snapshot reference plus `store_graph.last_captured` for the graph
the handle names. Capture upserts that column on every pass, so it answers "has anything been
captured since I last looked" at the same granularity `setBuildOutput`'s swap answered it, and a
memo can only ever be over-invalidated by it, never under. Two things come with reading it that
way. Its DDL comment currently claims it only as "the age half of the age/currency distinction, and
the bookkeeping a future eviction surface reads", so the comment gains the reading, since the DDL
is where this model's meanings live. And `CompileFacts` mints the anchor row with a write time
where no capture ever ran, which is a benign over-invalidation but a real second writer, so it is
named in the comment rather than left for the next reader to discover.

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
* **One coordinate answers once, and the tool is what collapses it.** The store's key is (source
  package, schema, table), so a graph whose sources carry one `schema.table` coordinate twice has
  two rows where the projection's map silently kept the last. The shared reader answers with the
  full key, because that is what the store holds and a reader that collapsed it would be narrowed
  for the consumer whose wire ID cannot name a source. The MCP mapping collapses, on the
  coordinate, ordered by source package so the survivor is stated rather than whichever row the
  engine returned first. It collapses *before* the resolution count is read, since
  `catalog.describe`'s `Ambiguous` arm names candidate schemas and would otherwise report one
  schema twice as an ambiguity no qualifier can resolve.
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

The `JooqCatalog` walk that fed the projection does not retire with it: classification reads the
same catalog, and `CatalogBuilder.build` still runs. What goes is the second pass over it that
produced a consumer-shaped copy.

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

Two live roadmap items name the projection as a current surface and are repointed at the same time,
since both of their arguments are about the thing this item removes. `lsp-structural-consolidation.md`
lists `catalogFacts` among the fields its torn-read slice must bundle behind one reference, and cites
the MCP multi-field read (`edgesTool`: snapshot plus facts) as what raised the stakes; that reader is
gone, and what remains of the concern is the snapshot alone. `capture-load-residuals.md` frames a
residual around `buildOutput` reusing the `catalogFacts` it already holds.

## Tests

The MCP module already has the fixture this needs: `StoreBackedBuild` runs a real
`GraphQLRewriteGenerator.buildOutput()` into a bootstrapped store and hands the server a handle,
which is how the diagnostics tools are tested. The catalog tool tests move onto it, and stop
hand-building projection fixtures.

* `GraphitronMcpServerTest`'s catalog cases (the largest block, currently building `CatalogFacts`
  values directly) assert the same wire fields against a store captured from the test jOOQ package.
  Ambiguity, not-found, filters, and paging all keep their cases; the deltas above get one case
  each, so the new behaviour is pinned rather than merely permitted.
* `CatalogDescriptorsTest` stays store-free, and the property split is the reason it may: the
  store-free cases own formatting and identifier normalization, which are the composer's own
  business and need no rows. What they cannot own is that the rows a real capture yields compose the
  descriptor the index embeds, and that is where the risk moved once the corpus stopped being
  byte-identical, so one store-backed case owns corpus composition end to end.
* `CatalogSearchIndexTest` / `CatalogSearchOnnxTest` replace the facts supplier with the corpus
  seam. With gate one deleted, the hash gate is the only invalidation left and carries the cases: a
  recapture that changed nothing must not re-embed, a changed catalog must.
* The foreign-key column pairing is an invariant asserted by a DDL comment and rendered for the
  first time here, so it gets a named case rather than a bullet: a multi-column foreign key (Sakila
  carries one) whose `targetColumns` come back in the referenced constraint's own order, which is
  the only thing position-matching can get wrong.
* `ConflictedReverseEdgeTest` follows `EdgeProducer.Context`'s new shape, and is the only test that
  constructs one. `EdgeCoverageTest` is not: it reads `EdgeProducer`'s four permit-set constants and
  never builds a context, so its partition over the classification permits is untouched by this item.
* `ServerInstructionsTest` is a seventh site and needs its own answer. Its `pagedWorkspace` fixture
  hand-builds a two-table `CatalogFacts` purely so a `limit=1` call on `catalog.tables` pages, beside
  hand-built projections giving five other tools two entries each, and the test boots a real server
  and asserts every tool's leading `N item(s)` line against what came back. That makes it an
  acceptance surface for a migrating tool, and its constraint is a per-tool minimum count, which a
  real capture does not promise. So it is not simply a move onto `StoreBackedBuild`: either the
  captured store is chosen to carry at least two tables (the test jOOQ package does) while the five
  hand-built projections stay, or `catalog.tables`' paging agreement moves to a store-backed case of
  its own. Which of the two is the implementer's call; that the case exists is this item's to say.
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
* "the live projections one edge computation reads" (`EdgeProducer.Context`'s javadoc)
* `CatalogSearchIndex`'s "two gates" and "gate 1" vocabulary, with `liveFactsRef`, since only the
  content hash remains; and "reference identity" as the name of the catalog half of
  `ReverseEdgeIndex.Cache`'s key, which becomes the capture stamp (the snapshot half keeps it)

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

That leaves the sibling item's retirement sweep owing two entries to items other than itself:
`CatalogFacts` to this one, `SourceWalker.Index` to the Backlog one. Whoever takes the sibling to
Done should expect both terms to survive its own diff, which is a sequencing fact rather than a
failed sweep. The coupling runs that way and only that way: this item needs nothing pending from
either sibling, so its `depends-on` stays empty, and the machine-visible edge belongs on the sibling
whose Done gate is the one that waits.

The relocation of the shared reader family out of `graphitron-lsp` is likewise not here; see "One
reader family" above.
