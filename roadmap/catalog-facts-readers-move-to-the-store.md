---
id: R642
title: "The MCP catalog and edge tools read the store"
status: Spec
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-12
last-updated: 2026-08-16
---

# The MCP catalog and edge tools read the store

Sibling of the LSP fact-store item (`lsp-reads-the-fact-store.md`), which retires the
`CatalogFacts` projection but cannot delete it while non-LSP consumers read it:
`GraphitronMcpServer`'s `catalog.tables` and `catalog.describe` tools, `EdgeProducer`, `EdgesTool`,
`ReverseEdgeIndex`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`,
plus `GraphQLRewriteGenerator`, whose output record carries the projection. These migrate to
store-side reads here (`ReverseEdgeIndex` by deleting, for the reason the edge section gives), apart
from the LSP work, because the MCP catalog tools have their own
acceptance surface (tool output, paging) that has nothing to do with cursors and buffers, and
because folding them into the LSP item would credit their `rewrite/catalog` lines to that item's
simplification measurement. Two constraints bind the siblings: `CatalogFacts` deletes in the same
commit as its last reader's migration, and both consumers read one shared store-side *base*, never a
narrowing made for one of them (the `FactCapture.capture` javadoc already states this). The base is
the relation. What each consumer writes over it is its own, which is the subject of the next
section. `TenantScopes` and `McpWire` cite the type only in javadoc and just repoint.

The edge tools read a second projection beside the catalog one, `LspSchemaSnapshot`'s per-coordinate
classification maps, and this item takes those reads too. The two are one migration rather than two:
`EdgeProducer` resolves a classifier's table name against the census, `ReverseEdgeIndex` inverts
`EdgeProducer` over every coordinate in the schema, and a version of that code holding one
projection and one store handle would be the awkward half-state rather than a smaller step. The
argument for taking it is in "The classification maps are questions, not a shape" below, and what
that section is really about is that the reverse index stops being a data structure.

## The goal

Retiring `CatalogFacts` is the mechanism. The goal is two properties of `graphitron-mcp`, and both
outlive the code this item touches.

**No dependency on `graphitron-lsp`.** The module may depend on `graphitron` and `graphitron-model`,
and should declare both directly rather than reaching them transitively through the language server.
It may not depend on the language server. There are no LSP-specific facts: the LSP reads the store
for its own purposes and the MCP has different data needs, so each writes the queries its own
surface wants. The current edge is a pre-store artifact, from when MCP had no way to reach generator
output except through the object the LSP already held it in, and every argument that once justified
it was an argument about reaching *data*. The store is that access now.

**No connection to the store of its own.** `graphitron-mcp` never opens a store, mints a connection,
or knows where the store directory is. The host does: `DevMojo` in `graphitron-maven-plugin` opens
`sessionStore`, and hands the server a `StoreHandle` over its DSL context. Everything this item adds
keeps that shape, including the `StoreReader` the `catalog.describe` section calls for, which the
host mints from `sessionStore.reader()` and passes in exactly as it already does for the language
server's `StoreAccess`. The rule is worth stating out loud because a module that writes its own
queries looks like a module that might reasonably open its own connection, and it must not: the
lifetime, the isolation level and the teardown belong to the process that owns the session. Ownership
of a *query* and ownership of a *connection* are separate things, and this item moves only the first.
(`DevQueryExecutor`'s own `DriverManager` handling is about the consumer's database, which the
`execute` tool reaches on the user's behalf, and is unrelated.)

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

## How far the dependency gets paid down here

`graphitron-mcp` imports exactly two things from `graphitron-lsp`: `Workspace`, and
`ClassMemberSlots`. Nothing else, and the four projections MCP reads through `Workspace`
(`snapshot`, `catalog`, `sourceIndex`, `catalogFacts`) are generator types from `graphitron`, not LSP
types. The edge is one state holder plus one misplaced reader.

The goal above is no edge at all. It is not reachable in one item, and saying which part blocks is
worth more than a deferral:

* `catalogFacts` is this item.
* `ClassMemberSlots` is this item too. It is already a store read, so nothing migrates; MCP writes
  its own query over `intent_class_member_slot` and the LSP keeps its own for the four surfaces its
  javadoc names. Leaving the one existing instance of a coupling while declaring the rule against it
  would make the rule weaker than the exception.
* `sourceIndex` and the external references are `mcp-code-tools-read-the-store.md`, whose relations
  (`jvm_`, `java_`) are captured.
* `snapshot` is partly this item. The edge tools' reads of it move here, because the relations
  behind every edge kind they emit are built; `SchemaView`'s backing block and the `status` tool are
  what remains, for two reasons named in the scope boundary below. `vocabulary` and
  `DirectivesResource` are untouched.

The pom half of the goal lands here in full, because it is cheap and makes the rest legible:
`graphitron-mcp` declares only `graphitron-lsp` today and reaches `graphitron` and
`graphitron-model` transitively through it. Declaring those two directly changes no bytecode and
turns the LSP edge into what it actually is, one import, rather than a dependency that appears to
carry the module's whole substrate. The edge itself deletes in whichever of the items above lands
last, with a test that fails if it comes back.

## The classification maps are questions, not a shape

`FieldClassification` has thirty-odd permits and `TypeBackingShape` has six, which reads at first
like the size of what a store-side migration would have to reproduce. It is not, because nothing
consumes either one as a union. Every use site on both sides of the fence is a narrow projection
with a default:

* `InlayHints.columnNameOf` asks "what column name" across four arms and lets the rest fall through.
  `InlayHints.fkPathOf` asks "what FK path" across two.
* `DeclTarget.methodTarget` asks "what class and method" across four.
* `FieldCompletions`, `Hovers`, `Diagnostics` and `DeclTarget` each switch `TypeBackingShape`'s six
  arms and pull out exactly one of two values, a table name or a class name. `DeclTarget.typeTarget`
  is the clearest: six arms collapse into `tableTarget`, `SourceClass` and `None`, which is a
  two-way question wearing a six-way costume.

So the permits are not a requirement any consumer has. They are what precomputing every question at
once costs, from when there was nothing to ask at read time. The questions themselves are five:

1. Which table backs this type. `intent_bound_table`, with `candidates` for arity.
2. Which class backs this type. Unbuilt, and the one gap that matters here.
3. Which column does this field match. `intent_column_match_claim` for the name;
   `intent_field_column_table` for the table when it is not the parent's own, whose absence means
   the parent's binding answers, which is a `LEFT JOIN` against `intent_bound_table` rather than a
   second query.
4. Which class and method does this field resolve to. `graphitron_service`,
   `graphitron_external_field`, `graphitron_routine`, and `intent_field_producer_method` over them.
5. What join path does it traverse. `intent_field_reference_step_hop` carries `constraint_name` and
   a fully-keyed `to_schema` / `to_table` per hop, ordered by `(ordinal, position)`, which is
   `FkStep` with a real key instead of a bare name.

Four of the five are built. What each consumer wants is one of them at a time, which is what a
relation per fact is for, and joining them is the caller's business rather than a shape the model
has to anticipate. This is the escalation rule read forwards: the shared thing graduated to a view,
so the union that used to carry every combination has nothing left to carry.

The payoff is `ReverseEdgeIndex`, and it is larger than the migration. That class iterates every
coordinate in the schema, runs `EdgeProducer.fieldEdges` per entry, inverts each result into a
`HashMap<String, List<Edge>>`, and is wrapped in a memo keyed on a two-reference pair whose javadoc
explains how a torn read against a non-atomic multi-field swap self-heals. All of it is a `GROUP BY`
written in Java because the input was a map. Against the store, "what binds this column" is a
predicate on the target: the index goes, the cache goes, the two-reference key goes, the torn-read
paragraph goes, and the stated invariant that the reverse direction must invert the same switch as
the forward one so "the two directions cannot disagree" goes with them, because forward and reverse
stop being two code paths over one map and become one relation read from either end.

That also settles what the edge tools cost at rest. Today the first reverse traversal after a build
pays a whole-schema walk; afterwards it is an indexed lookup per query, and the build pays nothing.

## What the queries must answer

Per consumer read:

* **The table census, ordered, filtered and paged.** Every table of this graph's source, optionally
  narrowed by exact case-insensitive schema and case-insensitive substring on the SQL name, ordered
  by schema then table name, with the page bound applied in SQL. `catalog.tables` answers from it
  directly and `catalog.search` composes its corpus from it. The edge tools do not read it: their
  endpoints arrive keyed from the binding relations, which is what removes the resolution step.
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
  the search corpus reads every column in one query rather than one per table.
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
* **What a field binds, forward.** One query per edge kind over the four relations the section
  above names, keyed by `(type, field)` and returning the target's full key. `EdgesTool`'s forward
  direction reads it for the coordinate in hand.
* **What binds a target, reverse.** The same relations with the predicate on the other end: the
  columns of a given table, the methods of a given class, the types bound to a given table. This is
  the query that replaces `ReverseEdgeIndex` outright, and the reason the reverse direction stops
  needing a build step is that it was never a different question, only a different `WHERE`.
* **A field's join path**, ordered, for the `joinPath` wire field: `intent_field_reference_step_hop`
  by `(ordinal, position)`, each hop carrying `constraint_name` and the destination's full key.

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
rather than a boundary. So the server *takes* a `StoreReader`, and takes it in the same sense it
takes its handle: `DevMojo` mints it from `sessionStore.reader()`, the same call that already gives
`StoreAccess` the LSP's reader, and closes it in `cleanup()` beside `lspStore`. The catalog tools
answer inside `reader.read(...)`. One constructor parameter, one field, one line of teardown, and no
`GraphitronModelStore` import in `graphitron-mcp`. This is the only place in the item where the
module's connection surface widens at all, so it is the place the ownership rule from the goal has
to be checked rather than assumed: the reader is minted by the host, and the module that uses it
still cannot open one.

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

**`edges` and the reverse index.** This is the largest change in the item and the one that removes
the most code. `EdgeProducer` stops switching over classification permits and `ReverseEdgeIndex`
stops existing; both directions become queries over the four binding relations, and
`EdgeProducer.Context` carries a handle where it carried two projections.

The bare-name resolution goes with them, which is a fix rather than a translation. `EdgeProducer`
matches a bare name today because its input is a *classifier* table name, and `resolveTable`
degrades an ambiguous or unfound one to a `TableNode` with an empty schema. That is the exact defect
`lsp-reads-the-fact-store.md` names as removed under "the binding names a table by its whole key":
a classifier's `tableName` slot only ever held a bare name, so every reader downstream matched it
case-insensitively across every schema and hoped. Reading the binding relations instead means no
edge endpoint is ever spelled without its schema, because none of those relations carries a bare
name to begin with. There is no resolution step left to degrade.

Ambiguity stops being a degradation too. `intent_bound_table` carries a `candidates` arity beside
the key, so an ambiguously bound type emits that many fully-keyed targets and the count is stated
rather than recounted downstream.

The census read `EdgeProducer.Context` needed for name resolution is not replaced by a smaller one.
It is not needed at all: the endpoints arrive keyed, so there is nothing to resolve them against.
What the context holds after this is the handle and the request's scope.

Table-to-table FK edges (`outgoingFkEdges` / `incomingFkEdges`) read `CatalogKeys.touching` for the
queried table, which is where the reverse FK direction was already a query rather than an index, and
is the shape the rest of the tool converges on.

`EdgeCoverageTest` is the one thing that does not simply follow. It reads `EdgeProducer`'s four
permit-set constants and asserts a partition over the classification permits, which is an
agreement between two Java spellings of the same taxonomy and has no successor once one of them is
gone. What replaces it is a coverage question against the relations: every edge kind the wire
declares is produced by some query, and every binding relation feeds some edge kind. The tests
section says what that costs.

**`GraphQLRewriteGenerator`** stops building the projection: `BuildArtifacts` loses its
`catalogFacts` component and the convenience constructor that defaulted it, `buildOutput` stops
calling `CatalogBuilder.buildCatalogFacts`, and `DevMojo` stops threading the value through
`setBuildOutput` and its catalog-refresh path. `Workspace` loses the field and accessor.

## Both memos delete

Two memos key on a projection's reference identity today, and there is no reference to compare once
the facts are rows. Neither is re-keyed; both go, for the same reason arrived at from opposite
directions.

`CatalogSearchIndex.observe`'s first gate skips composing the corpus when the `CatalogFacts`
reference is unchanged. It goes, rather than being re-keyed. Gate two, the corpus content hash, is
already the honest invalidation key, it is what the tests assert, and what gate one now saves is
one census query per `catalog.search` call, on a path that is about to embed text if it misses.
Deleting a cache whose subject is two queries is the cheaper simplification.

`ReverseEdgeIndex.Cache` looked like the one worth keeping, its subject being a walk over every
classified field. It is not, because the walk is what this item removes. A memo exists to avoid
recomputing something expensive, and an indexed predicate on a target key is not expensive; keeping
the cache would mean keeping the index, which would mean keeping the inversion, which is the code
the migration is for. The cache, its two-reference key, and the torn-read reasoning its javadoc
carries all delete together with their subject.

Nothing takes their place, and in particular `store_graph.last_captured` acquires no reader here.
An earlier reading of this item had the memo re-keyed onto that column; it is worth saying plainly
that it is not, so the column's DDL comment stays as written and this item owes it no amendment.

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
* **No edge target is emitted unqualified any more.** `resolveTable` renders a degraded `TableNode`
  with an empty schema whenever a bare classifier name is ambiguous or unfound, and `wireId()` drops
  the qualifier entirely, so `edges` answers today with a bare `film` where every other table node
  is `schema.film`. Both arms read keyed relations after this, so the degraded node has no producer
  left. An ambiguously bound type emits the candidates `intent_bound_table` carries, each a full
  key. This is the one wire change in this item that is a fix rather than a translation: an
  unqualified table ID is not a node any other tool can be handed back.
* **A join path's hops carry their constraint's full key.** `FkStep` holds a bare target table name
  and an FK name, and both come back schema-qualified from `intent_field_reference_step_hop`. The
  same argument as the bullet above, on the payload rather than on the endpoint.
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

On the edge side: `ReverseEdgeIndex` in full, including its `Cache` and the server's
`reverseEdgeIndexCache` field; `EdgeProducer`'s switch over the classification permits, its four
permit-set constants and `resolveTable`; and `EdgeCoverageTest`, whose subject is the partition
those constants describe. `EdgeProducer.Context` keeps its name and loses its projection components.
No classification permit is deleted by this item: `FieldClassification` and `TypeBackingShape` stay,
with `SchemaView` and the LSP still reading them, and the sibling item retires them when the last
reader goes. What retires here is `graphitron-mcp`'s dependence on their *arm count*.

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
the MCP multi-field read (`edgesTool`: snapshot plus facts) as what raised the stakes. That reader
is gone entirely after this item rather than reduced to its snapshot half: `edgesTool` reads neither
projection, so the multi-field argument loses its example and what is left of the concern is the
LSP's own reads. Whoever picks that item up should re-derive its motivation rather than inherit this
one. `capture-load-residuals.md` frames a residual around `buildOutput` reusing the `catalogFacts`
it already holds.

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
* `ConflictedReverseEdgeTest` moves onto `StoreBackedBuild` rather than following a changed context
  shape. What it pins is that a conflicted coordinate still reaches the reverse direction, and that
  survives the migration as a claim about rows: a coordinate carrying two claims contributes an edge
  per claim, which is what the claim views already say and what the old inversion had to be careful
  to preserve by hand.
* `EdgeCoverageTest` has no direct successor and needs a replacement rather than a port, which is
  this item's to specify because deleting a coverage test silently is how a taxonomy starts leaking.
  Its subject is the agreement between `EdgeProducer`'s permit-set constants and
  `FieldClassification`'s permits, and one side of that is gone. The successor asserts the same
  property one level down, against the relations: every `EdgeKind` the wire declares is produced by
  one of the queries, and every binding relation the queries read feeds at least one `EdgeKind`. The
  second half is what catches a relation gaining an arm that the tool then never surfaces, which is
  the failure the permit partition was really guarding against. Both halves are assertable from
  `graphitron-mcp` without a store, being statements about the query set, so the case stays cheap.
* Nothing in this item writes an agreement test between the permits and the relations. Two Java
  spellings of one taxonomy is what the permit partition was; a second one keyed on rows would be
  the same mistake with a store underneath it. The per-edge-kind cases against a real capture are
  what pins the behaviour, and they are the cases already listed above.
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
  carries it. And the edge tools' loss of the resolution step gets a case of its own, being the one
  wire fix here: a type bound to that spelling emits fully-keyed candidate targets rather than one
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
* **The connection-ownership rule gets a guard, and it can land here in full** because unlike the
  pom edge it is already true and this item is the one that puts pressure on it. A source scan over
  `graphitron-mcp`'s main sources fails on a reference to `GraphitronModelStore`, to a store-opening
  entry point, or to a store directory path, the module's whole store surface being the `StoreHandle`
  and `StoreReader` it is handed. `DevQueryExecutor` is the one exclusion and it is a named one, its
  connections being to the consumer's own database. Written now, the guard is what keeps the
  `StoreReader` parameter from becoming a `StoreReader` the module mints for itself the first time
  someone finds passing it through inconvenient.
* The corresponding guard for the LSP edge is not written here, since `Workspace` still needs the
  import. It belongs with the last of the three items, and the ownership guard above is the template
  for it.

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
  content hash remains; and "reference identity" as an invalidation key anywhere in
  `graphitron-mcp`, both memos that used one being gone
* `ReverseEdgeIndex`, its `Cache`, and the vocabulary built on it: "the reverse-edge index", "index
  build", "inverting the forward producer", and "the two directions cannot disagree" as the name of
  an invariant, the two directions being one relation afterwards
* `EdgeProducer.resolveTable`, and "resolving a bare table name" as a step the edge tools have;
  with it the degraded `TableNode` and "an empty schema" as a rendering of ambiguity
* "the classification projection" as prose for what the edge tools read, and the permit-set
  constants' framing of a "partition over the classification permits" as `graphitron-mcp`'s
  coverage obligation

## Scope boundary

`graphitron-mcp` reads four generator-side projections, and this item moves one of them outright and
most of a second. What is left of `snapshot` after this is two readers, and the reason each stays is
worth stating precisely, because the imprecise version of it is what kept the edge tools on the
projection longer than the substrate required.

**`SchemaView`'s backing block** needs to know which class backs a type, and no relation answers
that. `intent_class_member_slot` says so in its own DDL comment: the question "is a reflective walk
over accessor return types", the census now carries the declared type beside the erasure so the hops
are followable, "and what is still unbuilt is the walk over those hops rather than the fact it would
read. Until that lands a reader holds the class name from elsewhere". `SchemaView` is such a reader.
The table half of the same block could move today, but splitting one wire object across a projection
and a store read to save half a blocker is worse than waiting, so the whole block waits.

**The `status` tool** is not blocked, it is out of place. Its `Unavailable` / `Built.Current` /
`Built.Previous` axes are dev-session lifecycle, whether a build has succeeded and whether the last
parse failed so the previous good one is being held. That is not a fact about a graph, and a
relation carrying it would put editor state in the fact model. It reads a live projection because a
live projection is the right thing for it to read.

Those two are why `LspSchemaSnapshot` and therefore `Workspace` outlive this item, and they are a
shorter list than the previous reading of this boundary had. Two claims that appeared here are
withdrawn: the column match at a site whose table is not the parent's own is answered by
`intent_field_column_table`, whose own comment states the omission of the parent case as deliberate
and reads absence as "the parent's own scope answers"; and foreign-key discovery is unbuilt only for
the path an author *omitted*, which serves one LSP inlay renderer, while an authored path's hops are
`intent_field_reference_step_hop`. Neither blocks anything the MCP renders. An implementer should
re-check both at pickup rather than trusting this paragraph, since the substrate moves under the
item, but the direction of the correction is that more is readable than the item first claimed.

`CompletionData.ExternalReference` and `SourceWalker.Index` stay, even
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
