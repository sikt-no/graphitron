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
commit as its last reader's migration, and both consumers read one shared store-side *base*, never a
narrowing made for one of them (the `FactCapture.capture` javadoc already states this). The base is
the relation. What each consumer writes over it is its own, which is the subject of the next
section. `TenantScopes` and `McpWire` cite the type only in javadoc and just repoint.

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

## The MCP writes its own queries

The obvious move is to reuse the LSP's readers. `graphitron-lsp`'s `facts` package already holds
`CatalogTable`, `CatalogColumns` and `CatalogKeys` over `StoreHandle`, and `graphitron-mcp` compiles
against `graphitron-lsp` today, so the imports would resolve. This item does not do that, and the
reason is the whole point of having built a store.

The shared thing is the *relation*, not a Java class over it. `ClassMemberSlots` says so in its own
javadoc: the bean rule "used to be re-run per build to hand the same list to all four" surfaces and
"now has one home in the DDL and this is the read of it". Once the rule lives in the view, a reader
is a query plus a row shape, and which module it sits in is incidental. What is not incidental is
what crosses the module boundary when one consumer imports another's: a Java row vocabulary, which
"one model, many views" is satisfied by neither module owning. Both modules reading the base is the
arrangement the doctrine describes; one module reading the other's view of the base is not.

The two consumers also want different queries. The LSP asks whether a spelling lands anywhere, to
decide a squiggle. `catalog.describe` assembles five relations into a wire response. Those overlap
in `FROM` clause and nowhere else, and a shared reader serving both accumulates entry points that
one caller never uses. Where a rule genuinely must be shared, this model already has an escalation
and it is not a Java class: it graduates to a store view, which is where the qualifier split and the
case-insensitive match went when the sibling item stopped them acquiring a third copy.

So the catalog reads this item needs are written in `graphitron-mcp`, against
`no.sikt.graphitron.model.Tables`, scoped through `store.reads(...)` like every other read, shaped by
what the wire wants rather than by what a second consumer might one day want. The LSP keeps its
readers unchanged; nothing in `graphitron-lsp` is touched by this item.

That also removes the constraint the shared-reader arrangement needed to make itself safe (no
MCP-only component on the shared records), the deferred relocation item it was paying for, and the
argument about `graphitron-model` having no test sources. A query written in the module that runs it
is tested by that module's own fixture.

`CatalogColumns`'s Javadoc overlay through `SourceDeclarations` is a case in point rather than a
wrinkle: it is a correlated `Field<String>` the LSP wants and MCP passes nothing for. Under a shared
reader that is a component one caller reads as permanently empty. Written separately, it simply is
not in the MCP query.

## The dependency this item is paying down

`graphitron-mcp` imports exactly two things from `graphitron-lsp`: `Workspace`, and
`ClassMemberSlots`. Nothing else, and the four projections MCP reads through `Workspace`
(`snapshot`, `catalog`, `sourceIndex`, `catalogFacts`) are generator types from `graphitron`, not LSP
types. The edge is one state holder plus one misplaced reader, and it dates from before the store:
MCP had no way to reach generator output except through the object the LSP already held it in.

The target state is no edge at all. It is not reachable here, and saying which part blocks is worth
more than a deferral:

* `catalogFacts` is this item.
* `ClassMemberSlots` is this item too. It is already a store read, so nothing migrates; MCP writes
  its own query over `intent_class_member_slot` and the LSP keeps its own for the four surfaces its
  javadoc names. Leaving the one existing instance of a coupling while declaring the rule against it
  would make the rule weaker than the exception.
* `sourceIndex` and the external references are `mcp-code-tools-read-the-store.md`, whose relations
  (`jvm_`, `java_`) are captured.
* `snapshot` and `vocabulary` are blocked on substrate rather than on scoping. The sibling item's
  own increment states it: the `@field` renderer needs a column match at a site whose table is not
  the parent's own, which no relation answers yet, and `@reference` needs foreign-key discovery,
  which is unbuilt. `graphitron_node`, `graphitron_node_key_column` and the `graphql_directive`
  family are there for the rest, but classification is not migratable until those two land.

One preparatory step belongs here because it is cheap and makes the rest legible: `graphitron-mcp`
declares only `graphitron-lsp` today and reaches `graphitron` and `graphitron-model` transitively
through it. Declaring those two directly changes no bytecode and turns the LSP edge into what it
actually is, two imports, rather than a dependency that appears to carry the module's whole
substrate. The pom edge deletes in whichever of the items above lands last, with a test that fails
if it comes back.

## What the queries must answer

Per consumer read:

* **The table census, ordered, filtered and paged.** Every table of this graph's source, optionally
  narrowed by exact case-insensitive schema and case-insensitive substring on the SQL name, ordered
  by schema then table name, with the page bound applied in SQL. `catalog.tables` answers from it
  directly, `catalog.search` composes its corpus from it, `EdgeProducer` resolves names against it.
* **A spelling resolved to a table.** Bare (`film`) or inline-qualified (`public.film`), with a
  separate schema argument as the alternative to inline qualification (inline wins), all matching
  case-insensitive. Same query, different filter. No sealed outcome type is minted for
  it: how many rows came back is the answer, per the sibling item's "sealed resolution outcomes",
  and the wire taxonomies that already exist (`EdgesTool.Selection`, `catalog.describe`'s
  `resolution` field) keep their arms and read the row count.

  The LSP's `CatalogTables.named` answers the nearest question and is the clearest case for
  separate queries rather than a shared one. It returns a sealed `Match` whose third arm `NoCensus`
  separates "the census holds no table at all" from "no table spells this", so that a catalog nobody
  has generated yet does not turn every `@table` in a schema red. The tools want the opposite
  reading, stated in the refusal delta below: absence of rows is absence of tables. An MCP arm over
  `NoCensus` would report a database the store cannot distinguish from an empty one. Sharing the
  reader would mean either MCP ignoring an arm or the LSP losing a distinction it needs, which is
  the shape of every reader serving two consumers with different meanings for the same rows.

  The qualifier split, the case-insensitive match and the membership scope are the rule
  `intent_spelled_table` owns for spellings an author wrote in a directive, and the sibling item
  spent an increment moving it there so it would not acquire a third copy. This is deliberately not
  a fourth: that view's population is authored SDL, and an MCP tool argument is not authored SDL, so
  the view cannot answer for it. What the two owe each other is agreement, so the MCP query's
  javadoc links the view as the site that must match it. If a tool argument ever needs to resolve
  the way a directive does (a lookup by `@table` spelling, say), the answer is to read the view.
* **A table's columns**, in `ordinal` order, keyed by schema and table; plus a whole-graph form so
  the search corpus and the reverse index read columns in one query rather than one per table.
* **A table's uniqueness constraints**: the primary key, and the unique constraints other than it,
  each with its constraint name and ordered columns. One query over `sql_constraint` /
  `sql_constraint_column` / `sql_primary_key`.
* **A table's indexes** with their ordered columns, one query with its column join.
* **A table's foreign keys in both directions, with column pairs.** Both directions are predicates
  on `sql_referential_constraint`, and the pairs are the referencing constraint's own
  `sql_constraint_column` rows matched on position against the referenced constraint's. That rule is
  stated by `sql_referential_constraint`'s DDL comment, which calls it guaranteed by SQL semantics
  and never copied onto the referencing row; the query renders what the comment states rather than
  restating it. The LSP's `CatalogKeys.touching` answers the same shape for the hover, carrying the
  `Keys` constant instead of the column pairs, and the two stay separate for the reason the section
  above gives.
* **A backing class's member slots**, for `SchemaView`: one query over `intent_class_member_slot`,
  ordered by slot name, replacing the `ClassMemberSlots` import. The bean rule the read depends on
  is the view's, so there is nothing to duplicate but the projection of three columns.

Every one of these is scoped through `store.reads(...)` on the relation's `source_name`, which is
what keeps a sibling module's catalog out of the answer in a shared store. That scoping also settles
the key question: `RewriteContext.jooqPackage` is a single value, so one graph reads one generated
package and every `sql_` row it owns carries the same `source_name`. Within a scoped read
`(schema, table)` is therefore already unique, and the queries key on it. The store's own key leads
with the source because the store is shared across graphs and modules, which is a fact about the
store rather than about any one graph's answer. Should a graph ever read more than one package, the
scoped reads become ambiguous in exactly one way and the queries acquire the source; nothing about
the wire changes, since a wire ID has no slot for a source to begin with.

## The consumers

**`catalog.tables`** answers from one query, and its paging becomes keyset. The wire entry
(`schema`, `name`, `comment`) is unchanged and so is the opaque-cursor convention; what changes is
what the cursor encodes. `McpWire` today base64-encodes an offset into an in-memory list, and its
own javadoc states why that is safe to change: the encoding is "opaque so the wire contract does not
promise offset semantics". So the cursor becomes the last `(schema, name)` a page emitted, the query
becomes a `>` predicate on that pair with `ORDER BY schema, name` and the limit applied in SQL, and
nothing on the wire has to move.

That is worth doing for more than the round trip it saves. An offset is only meaningful against a
result order that is stable between calls, which the projection's reflective field order never was;
under keyset the ordering *is* the cursor, so the guarantee is structural rather than a property the
census has to promise. It also keeps the paging state on the client where it already lives, and
leaves the store answering questions rather than holding position.

`McpWire.page` stays for the tools that page an in-memory list, since five of them still do. What
this item adds is the keyset form beside it, not a replacement.

**`catalog.describe`** resolves the spelling, then reads columns, constraints, indexes and keys for
the resolved table. Five small queries where there was one map lookup, so the answer assembles
inside one read transaction rather than risking the columns of one generation beside the keys of the
next. H2 gives that directly; the only wrinkle is that it cannot come from the handle the server
holds, which carries the session writer's own connection, where a nested transaction is a savepoint
rather than a boundary. So the server takes a `StoreReader`, which is the substrate's existing
answer: `DevMojo` mints it from `sessionStore.reader()`, the same call that already gives
`StoreAccess` the LSP's reader, and closes it in `cleanup()` beside `lspStore`. The catalog tools
answer inside `reader.read(...)`. One field, one line of teardown.

Two details come with it. The refusal gate is the reader rather than the handle, so the tools check
the thing they answer through. And the full constructor's javadoc says today that sharing the
writer's connection "is safe here only because this server is turn-based; a consumer answering
concurrently mints a `StoreReader` instead", which stays true of the diagnostics tools and gains a
second reason here: not concurrency, but an answer assembled from five queries. Those tools keep
their single-query reads through the handle.

**`catalog.search`** loses its `Supplier<CatalogFacts>` and composes the corpus from the census plus
the column census. `CatalogDescriptors.descriptor` takes the census query's row shape and is
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

The rule applied to those rows is the weaker half, and it does not survive contact with where the
sibling item has gone. `EdgeProducer` matches a bare name because its input is a *classifier* table
name, and `resolveTable` degrades an ambiguous or unfound one to a `TableNode` with an empty schema.
That is the exact defect `lsp-reads-the-fact-store.md` names as removed under "the binding names a
table by its whole key": a classifier's `tableName` slot only ever held a bare name, so every reader
downstream matched it case-insensitively across every schema and hoped. Carrying it into a store
read would re-instantiate downstream what that item just retired upstream, so the two arms split
rather than sharing one transitional excuse.

The **type arm** takes the keyed answer now. `intent_bound_table` resolves an `@table`-bearing type
to the full `sql_table` key with a `candidates` arity beside it, `BoundTables.of(store, typeName)`
already reads it into `CatalogTable`, and that is the same census key this item is widening the
shared row records to carry. The type name is in hand at the call site, `EdgesTool` keying
`typeClassificationsByName` by it before dispatching, so what the switch needs is that name threaded
into `typeEdges` rather than a new resolution. Ambiguity stops degrading to an empty schema and
becomes what the view already says it is: rows, with the count stated rather than recounted.

The **field arms** stay on the bare name, and here transitional is the honest word.
`intent_field_column_table` answers a field site's column table but deliberately omits the parent's
own binding, being what a reader already holds, so there is no single view a field arm can read for
the table its classification names. Those arms retire with the classification projection this item
does not touch, and the note belongs on them rather than on `EdgeProducer` as a whole.

Table-to-table FK edges
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
  offsets into it: a reordering between two calls silently skips or repeats entries. Under the
  keyset paging above the order is the cursor, so this stops being an ordering the census has to
  promise and becomes the one it is keyed by.
* **Column order becomes the table definition's.** `sql_column.ordinal` is the position
  `Table.fields()` states; the projection carried the reflective field walk's order, which is
  documented as no order in particular.
* **An ambiguously bound type stops emitting an unqualified edge target.** `resolveTable` renders a
  degraded `TableNode` with an empty schema, and `wireId()` drops the qualifier entirely, so `edges`
  answers today with a bare `film` where every other table node is `schema.film`. Reading the type
  binding through `intent_bound_table` replaces that with the candidates the view carries, each a
  full key. This is the one wire change in this item that is a fix rather than a translation: an
  unqualified table ID is not a node any other tool can be handed back.
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

Two imports also go, which is the dependency half. `SchemaView`'s `ClassMemberSlots` import is
replaced by MCP's own query, leaving `Workspace` as the sole remaining `graphitron-lsp` import in
the module. And `graphitron-mcp`'s pom gains direct declarations of `graphitron` and
`graphitron-model`, which it reaches transitively through `graphitron-lsp` today. Neither changes
what is on the classpath; together they make the remaining edge one import wide and visible as such.

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
  Not-found, filters and paging keep their cases unchanged, and the deltas above get one case each,
  so the new behaviour is pinned rather than merely permitted. Ambiguity is the exception, and the
  fixture bullet below is where it goes.
* `CatalogDescriptorsTest` stays store-free, and the property split is the reason it may: the
  store-free cases own formatting and identifier normalization, which are the composer's own
  business and need no rows. What they cannot own is that the rows a real capture yields compose the
  descriptor the index embeds, and that is where the risk moved once the corpus stopped being
  byte-identical, so one store-backed case owns corpus composition end to end.
* `CatalogSearchIndexTest` / `CatalogSearchOnnxTest` replace the facts supplier with the corpus
  seam. With gate one deleted, the hash gate is the only invalidation left and carries the cases: a
  recapture that changed nothing must not re-embed, a changed catalog must.
* The foreign-key column pairing is stated by `sql_referential_constraint`'s own DDL comment, which
  calls it guaranteed by SQL semantics and never copied onto the referencing row. The relation
  therefore needs no case: as a positional join over two captured relations it is correct exactly
  when its inputs are. What is new here is the Java that renders it, and a hand-written positional
  zip can get the order wrong where the relation cannot, so the case is aimed there:
  `public.project_note`'s two-column foreign key to `project` whose `targetColumns` come back in the
  referenced constraint's own order.
* `ConflictedReverseEdgeTest` follows `EdgeProducer.Context`'s new shape, and is the only test that
  constructs one. `EdgeCoverageTest` is not: it reads `EdgeProducer`'s four permit-set constants and
  never builds a context, so its partition over the classification permits is untouched by this item.
* **Two cases need a second fixture package, and providing the seam is this item's work.** A bare
  table name is ambiguous only across schemas, and `StoreBackedBuild` captures from
  `no.sikt.graphitron.rewrite.test.jooq`, which `graphitron-sakila-db` generates with
  `inputSchema=public` and nothing else. Every name in that census is unique, so a capture from it
  cannot produce an ambiguous resolution at all. The hand-built projections could assert one by
  fiat; a real capture can only show what the source declares. That is the standing cost of
  anchoring on the source rather than on the projection being replaced, and it is the right cost,
  but it has to be paid rather than assumed.

  The phenomenon exists one package over. The same module generates
  `no.sikt.graphitron.rewrite.multischemafixture` from `multischema_a` and `multischema_b`, which
  declare `event` in both precisely so that a bare spelling reaches two tables, and `graphitron-mcp`
  already depends on `graphitron-sakila-db` at test scope. What is missing is only the seam:
  `StoreBackedBuild.JOOQ_PACKAGE` is a `static final` constant threaded into `RewriteContext`, so
  both `run` overloads capture the single-schema package and no caller can ask for another. Making
  it a parameter defaulted to today's value is small, and it is the precondition for both cases
  below.

* The two cases that seam unlocks. `catalog.describe` resolving a spelling two schemas declare
  answers with both rows rather than one, which is the ambiguity case relocated onto a fixture that
  carries it. And the type arm's move off the bare name gets a case of its own, being the one wire
  fix here: a type bound to that spelling emits fully-keyed candidate targets rather than one
  unqualified `TableNode`. Both want what the sibling item wants from capturing the binding, a
  fixture that cannot lie about which table a spelling reaches, and neither is writable against a
  census with one schema in it.
* `ServerInstructionsTest` is a seventh site and needs its own answer. Its `pagedWorkspace` fixture
  hand-builds a two-table `CatalogFacts` purely so a `limit=1` call on `catalog.tables` pages, beside
  hand-built projections giving five other tools two entries each, and the test boots a real server
  and asserts every tool's leading `N item(s)` line against what came back. That makes it an
  acceptance surface for a migrating tool, and its constraint is a per-tool minimum count, which a
  real capture does not promise. So it is not simply a move onto `StoreBackedBuild`: either the
  captured store is chosen to carry at least two tables (the test jOOQ package does) while the five
  hand-built projections stay, or `catalog.tables`' paging agreement moves to a store-backed case of
  its own. Which of the two is the implementer's call; that the case exists is this item's to say.
* The member-slot query gets `SchemaView`'s existing case pointed at it, unchanged. The read is the
  same relation with the same ordering, so what the case pins is that the resource still renders the
  slots, not that a new rule was introduced.
* Every query this item writes is tested from `graphitron-mcp`'s own fixture, which is the practical
  half of writing them here: a query lives in the module whose acceptance surface it serves, and is
  pinned by the tests that assert that surface.

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

`graphitron-mcp` reads four generator-side projections in all, and this item moves one of them. The
other three are what keeps `Workspace` alive in the module, and the section above says which item
each belongs to. Two are blocked on substrate rather than on scoping, which is the part worth
repeating here so nobody re-litigates it: `lsp-reads-the-fact-store.md` has named the classification
substrate view by view and built most of it, but its own increment records that the `@field`
renderer needs a column match at a site whose table is not the parent's own, which no relation
answers yet, and that `@reference` needs foreign-key discovery, which is unbuilt. Until those land,
`LspSchemaSnapshot` cannot leave, and neither can `Workspace`.

What this item does about that is refuse to add to it. The type arm above moves off the projection
onto `intent_bound_table`; the field arms stay because there is nowhere for them to go. What
`graphitron-mcp` does not do is acquire six new `graphitron-lsp` imports on the way past.
`CompletionData.ExternalReference` and `SourceWalker.Index` stay too, even
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

The deletion of the pom edge itself is not here, since `Workspace` outlives this item. It belongs to
whichever of the three named items lands last, along with the test that keeps it deleted.
