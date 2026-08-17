---
id: R642
title: "graphitron-mcp reads only the store"
status: In Progress
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-12
last-updated: 2026-08-17
---

# graphitron-mcp reads only the store

`graphitron-mcp` answers thirteen tools, one resource and one prompt off four generator-side
projections it reaches through the language server's `Workspace`. This item ends that: after it the
module compiles against `graphitron-model` and nothing else in the reactor, names no type from
`graphitron-lsp` or `graphitron` in any main source, reads no classification projection, and answers
every tool from the fact store or from a value its host hands it. The generator survives at test
scope, where the fixture runs a real build to produce the store the tests read.

Fewer tools come out than went in, and that is the item's second decision rather than a consequence
of the first. Two tools are dropped instead of migrated and three collapse into one, for the reason
"The surface shrinks first" below gives: a tool whose shape was dictated by reading a projection is
not a tool worth carrying onto a substrate that would have produced a different shape. Nine tools,
one resource and one prompt come out the far side.

The item takes full ownership of the module's dependencies and finishes the job. It waits on no
other item and defers nothing to one: every read that has to move, moves here. That is a change from
earlier readings, which split the work three ways and left each part able to ship while truthfully
saying the remaining coupling was somebody else's. The module's reads do not separate cleanly
anyway. `catalog.describe` read `CatalogFacts`, `edges` read `CatalogFacts` *and* the
classification maps, `schema` reads the classification maps *and* a store relation already, the code
tools read the classpath scan, `status` and `diagnostics` read the snapshot's lifecycle arms, and the
`directives` resource reads the snapshot *and* the language server's own vocabulary registry.
Migrating a subset leaves the module holding a
`Workspace` for whatever was left, which is the state it is in today.

The LSP fact-store item (`lsp-reads-the-fact-store.md`) retires the `CatalogFacts` projection from
the language server's side and cannot delete the type while a non-LSP consumer reads it. This item
deletes it, in the slice that removes its last reader. That is a courtesy the sequencing makes
free rather than a dependency: no `graphitron-lsp` surface reads `catalogFacts()` today, so nothing
here waits on that item, and nothing there waits on this one.

One doctrine binds the two modules and outlives both items: they read one shared store-side *base*,
never a narrowing made for one of them, which the `FactCapture.capture` javadoc already states. The
base is the relation. What each consumer writes over it is its own, which is the subject of "The MCP
writes its own queries" below. `TenantScopes` cites the retired type only in javadoc and just
repoints.

## The goal

Retiring `CatalogFacts` is one mechanism among several. The goal is four properties of
`graphitron-mcp`, and all four outlive the code this item touches. Each lands here in full; none is
deferred to a successor, and the close-out slice asserts each of them as a test.

**One reactor dependency: `graphitron-model`.** This is the property, stated positively, and it is
much stronger than deleting the language-server edge. After this item `graphitron-mcp` compiles
against exactly one `no.sikt` module, `graphitron-model`, plus `org.jooq:jooq`. Not
`graphitron-lsp`, and not `graphitron` either. The module that answers questions about a generated
graph stops compiling against the generator.

That is the honest end state of "the MCP reads only the store". A module that still compiles against
`graphitron` can still reach for a projection the next time a tool wants a datum, and the whole
argument for the store as the extension point is that reaching for one should not be possible. The
store is a published schema in `graphitron-model`; the generator is the thing that fills it. A
reader needs the first and not the second.

**Test scope is different, and the difference is structural rather than a hedge.** The tests keep
`graphitron` and `graphitron-sakila-db`, because the module's test strategy is to assert against a
real capture and a capture is something only the generator can produce. `StoreBackedBuild` runs
`GraphQLRewriteGenerator.buildOutput()` into a file store, and its own javadoc says why nothing
cheaper works: "Tests over hand-built reports cannot survive the substrate: the loaders read the
walk's own streams, so the rows a test asserts on have to come from a real pipeline run." Every
slice below leans on that fixture, and the alternative, a pre-built store shipped as a test resource,
would put a frozen artifact where the item deliberately put a live one. So the allowlist is written
per scope: compile is `graphitron-model` alone, test adds `graphitron` and `graphitron-sakila-db`
by name.

The split is not a loophole, because scope is exactly the distinction that matters here.
`graphitron-mcp` is *published*, and its pom comment records that a Maven plugin resolves its
declared dependencies from the consumer's repositories at execution time. Compile scope is what
ships and what a future reader can reach for; test scope reaches nothing a consumer receives and
constrains no production read. The guard fails on a `graphitron` import in main sources, which is
the property, and permits one in tests, which is the fixture.

`org.jooq:jooq` is declared rather than inherited, and that is a correction this item owes rather
than new scope. `DiagnosticsTool` and `DiagnosticFacets` import `org.jooq` today with no declaration
in the module's pom, resolving it transitively through the language server. This item makes every
tool in the module a jOOQ query author, so the undeclared direct use goes from two files to most of
the module while the edge that supplies it is being deleted.

The allowlist shape matters as much as its contents. A rule naming `graphitron-lsp` passes the next
edge somebody adds: `graphitron-javapoet` rides in transitively today, and `graphitron-jakarta-rest`
is a plausible reach for a module rendering consumer-facing shapes. Stating the permitted set per
scope means any new edge is argued for at the guard rather than noticed later.

There are no LSP-specific facts, which is why that edge can go at all: the LSP reads the store for
its own purposes and the MCP has different data needs, so each writes the queries its own surface
wants. The current edge is a pre-store artifact, from when MCP had no way to reach generator output
except through the object the LSP already held it in, and every argument that once justified it was
an argument about reaching *data*. The store is that access now, and the same sentence retires the
`graphitron` edge one step later: once no tool reads a projection, nothing in main sources names a
generator type.

**No read of the classification projection.** `FieldClassification`, `TypeClassification` and
`TypeBackingShape` are the generator's field and type taxonomy projected for the language server.
`graphitron-mcp` switches all three today, in `EdgeProducer` and in `SchemaView`, across ninety-odd
exhaustive arms with no `default`. After this item it switches none of them. This is a separate
property from the one above, and the stronger of the two: an import can be deleted by moving a
value, while a taxonomy read has to be replaced by asking the question the taxonomy was precomputing
an answer to.

**No read of the `walk_` family.** `walk_type_backing_class`, `walk_claim_domain_type` and
`walk_claim_domain_field` transcribe the classification walk's own reach so a store-native
derivation has a differential to check itself against. Their family header says they retire with the
walk and that their relations "drain on separate clocks". A consumer reading one keeps it alive past
its purpose and acquires a dependency on a relation whose whole design is to disappear, so
`graphitron-mcp` reads none of them. Where this bites is the backing class, and the slice that
answers it says how.

**No connection to the store of its own.** `graphitron-mcp` never opens a store, mints a connection,
or knows where the store directory is. The host does: `DevMojo` in `graphitron-maven-plugin` opens
`sessionStore`, and hands the server a `StoreHandle` over its DSL context. Everything this item adds
keeps that shape, including the `StoreReader` the `catalog.describe` section calls for, which the
host mints from `sessionStore.reader()` and passes in exactly as it already does for the language
server's `StoreAccess`. The rule is worth stating out loud because a module that writes its own
queries looks like a module that might reasonably open its own connection, and it must not: the
lifetime, the isolation level and the teardown belong to the process that owns the session. Ownership
of a *query* and ownership of a *connection* are separate things, and this item moves only the first.
(`DevQueryExecutor`'s own driver loading, a `ServiceLoader` sweep that deliberately bypasses
`DriverManager`, is about the consumer's database, which the `execute` tool reaches on the user's
behalf, and is unrelated.)

Together the four properties set the price of the next tool, which is what this item is really
buying. Today a new datum on the MCP wire is a pipeline change: extend a projection in `graphitron`,
thread it through `BuildArtifacts` and `Workspace` and `DevMojo`, keep the language server
compiling, and page the result in memory. After this item it is a query and a wire shape, authored
in `graphitron-mcp`, scoped through `store.reads(...)`, pinned by the module's own fixture, over
relations the capture already writes. The store's relation surface is the extension point, so making
the MCP more capable stops requiring changes to any module that is not the MCP.

## What each tool actually asks

The migration is tractable because the tools' intentions are narrower than the projections they read.
This table is the item's spine: each row is a slice below, and the item is done when every row's
right-hand column is true.

[cols="1,3,2"]
|===
| Tool | What it is for | Where the answer comes from after this

| `catalog.tables`
| Which tables exist in this graph's source
| `sql_table`, keyset-paged

| `catalog.describe`
| One table's columns, keys, indexes and both FK directions
| the `sql_` family, one nested projection at the table grain

| `catalog.search`
| Which table holds something the author can only describe in words
| the same two census queries, composed into the embedding corpus

| `schema`
| What graphitron made of a type or field, and what it binds
| the claim, binding, backing and demand views

| `directives`
| The directive grammar this schema can use
| `graphql_directive` and its argument / location children

| `status`
| Is the dev session live, and is its answer current
| `store_graph` presence and the SDL refusal relations' emptiness; liveness is proved by answering

| `diagnostics`
| What is broken
| the `diagnostic` view already, plus the same refusal-relation read for the axes

| `code`
| Which consumer Java the schema binds to
| the `jvm_` census and the `java_` declaration family, one nested projection per class

| `docs.search`, `execute`
| The manual; the consumer's own database
| untouched, neither reads a generator projection
|===

The right-hand column is the whole claim, and every row now names a store read. What the scope
boundary still fences off is smaller than a row: the handle and reader the host mints, and the one
liveness bit no relation can carry, which a tool proves by answering at all.

Two rows are absent that a reader of the module today would expect, and one row is new. `edges` and
`diagnostics.aggregate` are dropped rather than migrated, and `services` / `conditions` / `records`
arrive as the single `code` row. The next section is why.

## The surface shrinks first

A migration that ports every tool assumes each tool's shape was a judgment about what an agent
needs. Three of them were not: they were judgments about what a projection made cheap to reach, and
the substrate that produced that constraint is the thing being removed. So the first question per
tool is whether to carry it, and the answer is no three times.

**`edges` is dropped.** Its stated job is "what else touches this, what breaks if I change it", which
is two questions wearing one costume. The forward half answers what a coordinate binds, which is
what the `schema` tool answers, and once `schema` reads the binding relations directly the forward
half is a reformatting of a `schema` response with a different field layout. The reverse half is the
genuinely valuable question and the tool's own javadoc says so, calling it "the impact-analysis
directions agents cannot cheaply walk forward". Carrying the costume to keep the reverse half means
porting five relationship labels, a six-permit node model, a direction argument and a memoised
in-memory index whose entire purpose is inverting a map, plus a label vocabulary that reads backwards
in at least two of its five cases (an edge from `Film.title` labelled `BACKS` with target
`public.film:title` says the field backs the column, and the column backs the field). Nothing is
filed to bring the reverse question back, and that is deliberate rather than an omission: if it turns
out to be needed, it is one query over the binding relations keyed at the target end, cheaper to
author fresh once `schema`'s reads exist than to keep alive here on the chance somebody asks.

**`diagnostics.aggregate` is dropped.** What it is, concretely, is a `GROUP BY` performed in Java over
a projection, carrying its own dimension enum, a two-bucket partition of that enum, elision
accounting, and a coverage meta-test whose subject is that the partition stays a partition. The
`diagnostic` view is already the relation `diagnostics` reads, so grouping over it is a `GROUP BY`
with counts whose group keys are that view's own columns. None of the enum, the partition or the
meta-test has a successor in that shape, so migrating them spends the effort on machinery the
substrate deletes. It returns under
`roadmap/diagnostics-aggregation-over-the-store.md`.

**`services`, `conditions` and `records` become one `code` tool.** The three share one argument schema
(`nameLimitCursorSchema`), read one census, and differ by a `WHERE` clause each: a class with record
components, a method whose `returns_condition` is set, a class with callable methods. `CodeTools`'
own javadoc already records the split as the tools' derivation rather than a store rule. Three tool
descriptions, three handlers and three paged wire shapes for three predicates is surface without
information, and it is worth collapsing now rather than porting three times.

Nothing else is dropped. `docs.search` and `execute` read no generator projection and are untouched
by this item at all. `catalog.tables`, `catalog.describe`, `catalog.search`, `schema`, `status`,
`diagnostics`, the `directives` resource and the `about` prompt all answer a question no other tool
answers, so they carry forward.

## One nested projection per grain

Every read this item writes projects its whole answer in one query, nesting one-to-many children with
jOOQ's `MULTISET` and mapping them straight onto records with `Records.mapping`. The alternative,
which the first cut of `catalog.describe` shipped, is several queries at several grains folded back
together with `LinkedHashMap` accumulators, a synthetic grouping key record, and mutable lists rebuilt
immutable at the end. That is a relational join written in Java, and it is worth naming as a hazard
rather than a style preference because it is what the language-server item drifted into and it looks
locally reasonable every time.

Three things go wrong with the folded shape and all three are cured by nesting. The grouping key has
to be invented in Java and can be invented wrong: `catalog.describe`'s foreign-key fold needs a
four-part `FkId` because a constraint name is unique per table and not per schema, which is a fact the
relation states and the Java has to remember. Consistency has to be argued rather than held: the
current `describe` needs a second connection and one read transaction so five queries cannot straddle
a capture commit, where one statement is atomic and needs no argument. And the row count crossing the
JDBC boundary is the product rather than the sum, since a parent row repeats per child.

The mechanism is verified rather than assumed: nested `MULTISET` two levels deep, `row(...)` inside a
multiset, and `Records.mapping` onto records all work against the store's H2 through `StoreReader`,
which jOOQ serves by emulating the nesting over H2's JSON aggregation. `StoreReader` stays the door a
multi-statement answer goes through, but the reason narrows to the plain one: a read should not ride
the session writer's connection.

A tool may still fire more than one query where the answers are genuinely independent, which
`catalog.tables` does for its count beside its page. What the rule forbids is one answer assembled
from several grains in Java.

## The shape of the work: one tool per slice

The work is sliced by tool, not by projection, and each slice takes one tool from its projection to
its queries in full. That ordering is a deliberate choice over the alternative, which is to migrate
one projection at a time across every tool that reads it.

Per-tool slicing wins because the tool is the acceptance surface. A slice's wire output is either
right or wrong, its tests are that tool's own cases, and it can land on trunk with every other tool
untouched. A projection-shaped slice would leave several tools half-migrated between commits, each
holding one projection and one handle, which is the awkward state this item exists to end rather
than to pass through repeatedly.

Every slice satisfies the same conditions, and a slice that cannot is not ready to land:

* The tool answers from the store or from a value the host handed it, with no projection read left
  in its own path.
* The tool's tests run against a real capture through `StoreBackedBuild`, not a hand-built fixture.
* Any wire change is stated in "Where the answers change" and reflected in
  `mcp/instructions.txt` and the manual's tool table in the same commit.
* The reader count on each `Workspace` accessor is stated in the slice, so the close-out slice's
  precondition is arithmetic rather than a grep nobody ran.

The ledger that arithmetic runs over has five accessors, not four, and the fifth is the one an
import scan does not see. `graphitron-mcp` reads `catalogFacts`, `catalog`, `sourceIndex` and
`snapshot` as values, and reaches `vocabulary()` through a call chain
(`workspace.vocabulary().registry()`, once, at server construction) without ever naming
`LspVocabulary`. Counting only the four that appear as types is how the fifth survives a slice plan.
Its opening counts are `catalogFacts` four (`catalog.tables`, `catalog.describe`, `catalog.search`,
`edges`), `catalog` five, `sourceIndex` three, `snapshot` six and `vocabulary` one.

The slices are ordered so the deletions fall out rather than being scheduled. Slices 1 to 3 drain
`catalogFacts`' readers, so slice 4 deletes the projection. Slices 7 to 10 drain the rest, so slice 11
deletes the module edge. Within that constraint the order is by risk: the catalog tools first
because their queries are the plainest and they prove the fixture, `schema` late because it is the
largest, and the close-out last because it asserts what the others achieved.

Two slices are not migrations and come before the rest of the work for that reason. Slices 4 and 5
remove the two dropped tools, so every later slice reads a smaller module, and slice 4 in particular
turns the projection deletion from something the plan had to sequence around into the removal's own
by-product. Slice 6 then reshapes the one already-migrated tool that shipped in the folded shape, so
the nested-projection pattern exists in the tree before `schema` is written against it rather than
after.

## Slice 1: `catalog.tables`

**Reads.** One census query over `sql_table`, scoped through `store.reads(...)`, optionally narrowed
by exact case-insensitive schema and case-insensitive substring on the SQL name, ordered by schema
then table name, with the page bound applied in SQL.

**Wire.** The entry (`schema`, `name`, `comment`) is unchanged and so is the opaque-cursor
convention; what changes is what the cursor encodes. `McpWire` today base64-encodes an offset into
an in-memory list, and its own javadoc states why that is safe to change: the encoding is "opaque so
the wire contract does not promise offset semantics". So the cursor becomes the last
`(schema, name)` a page emitted, the query becomes a `>` predicate on that pair with
`ORDER BY schema, name` and the limit applied in SQL, and nothing on the wire has to move.

That is worth doing for more than the round trip it saves. An offset is only meaningful against a
result order that is stable between calls, which the projection's reflective field order never was;
under keyset the ordering *is* the cursor, so the guarantee is structural rather than a property the
census has to promise. It also keeps the paging state on the client where it already lives, and
leaves the store answering questions rather than holding position.

`McpWire.page` stays for the tools that page an in-memory list, since five of them still do. What
this slice adds is the keyset form beside it, not a replacement.

**Leaves behind.** `catalogFacts` keeps two readers (`catalog.describe`, `catalog.search`) plus the
edge tools' one.

## Slice 2: `catalog.describe`

**Reads.** Resolve the spelling, then read columns, constraints, indexes and both foreign-key
directions for the resolved table. Five small queries where there was one map lookup, so the answer
assembles inside one read transaction rather than risking the columns of one generation beside the
keys of the next.

H2 gives that directly; the only wrinkle is that it cannot come from the handle the server holds,
which carries the session writer's own connection, where a nested transaction is a savepoint rather
than a boundary. So the server *takes* a `StoreReader`, and takes it in the same sense it takes its
handle: `DevMojo` mints it from `sessionStore.reader()`, the same call that already gives
`StoreAccess` the LSP's reader, and closes it in `cleanup()` beside `lspStore`. The catalog tools
answer inside `reader.read(...)`. One constructor parameter, one field, one line of teardown, and no
`GraphitronModelStore` import in `graphitron-mcp`. This is the only place in the item where the
module's connection surface widens at all, so it is the place the ownership rule from the goal has
to be checked rather than assumed: the reader is minted by the host, and the module that uses it
still cannot open one.

Two details come with it. The refusal gate is the reader rather than the handle, so the tools check
the thing they answer through. And the full constructor's javadoc says today that sharing the
writer's connection "is safe here only because this server is turn-based; a consumer answering
concurrently mints a `StoreReader` instead", which stays true of the diagnostics tool and gains a
second reason here: not concurrency, but an answer assembled from five queries. Those tools keep
their single-query reads through the handle.

**Wire.** The unique-key and column-order deltas below.

**Leaves behind.** `catalogFacts` keeps two readers.

## Slice 3: `catalog.search`

**Reads.** The corpus composes from the census query plus a whole-graph column query, so it reads
every column in one query rather than one per table.

**Wire.** `CatalogDescriptors.descriptor` takes the census query's row shape and is otherwise
untouched, but the corpus is not: the composer folds column order into each descriptor and
`corpusHash` digests the descriptors in table order, so the two ordering deltas below change the
hash by construction. The first search after the migration pays one full re-embed and the hash gate
self-heals from there, which is a one-time cost worth stating rather than a claim of byte-identity
that the deltas contradict. The composition runs on the request thread inside `observe()`, never on
the `AsyncWarm` daemon, which keeps doing only what it does today: embedding strings it was handed.
With no store to read, the index reports the same refusal the structured catalog tools report rather
than its warming degradation, because a corpus that cannot be composed is not an index that is still
building.

**Deletes.** `CatalogSearchIndex`'s `Supplier<CatalogFacts>` and its `liveFactsRef` gate. That gate
skips composing the corpus when the projection reference is unchanged, and it goes rather than being
re-keyed: gate two, the corpus content hash, is already the honest invalidation key, it is what the
tests assert, and what gate one now saves is one census query per `catalog.search` call, on a path
that is about to embed text if it misses. Deleting a cache whose subject is two queries is the
cheaper simplification.

The seam goes with the gate, and the index reads the census itself: it takes the `StoreReader` the host
minted and the graph name, and `CatalogCorpus` beside it holds the two queries. A supplier of rows was
the first shape tried and it is the wrong one, for a reason worth recording because it recurs whenever a
consumer is given its facts instead of reading them. Under a supplier, "this index ranks what a capture
wrote" is a property of the line that constructed it rather than one anybody can read off the type, and
nothing stops a future caller handing it a corpus from somewhere else. The connection stays the host's
either way, which is the ownership rule this item states: the index reads through a reader it never
opened.

That puts the corpus query in the RAG package rather than with the structured tools' reads, which is
this item's own doctrine one level down. The two are shaped by different consumers, a wire response and
an embedder, and they overlap in `FROM` clause and nowhere else; what a reader shares with another
reader is the relation. Gate one could not have survived the move in any case: a read composes fresh
rows every time, so there is no reference for an identity check to compare.

The refusal is gated at the wire beside `catalog.describe`'s rather than inside the index, so the RAG
package needs no vocabulary for a wiring fact, and it is ordered store-first: no store refuses, and no
embedder over a store that is present is still the warming degradation.

**Leaves behind.** `catalogFacts` keeps one reader, the edge tools.

## Slice 4: `edges` deletes, and `CatalogFacts` with it

The largest slice in every earlier reading of this item, and now the smallest, because the tool it was
going to migrate is dropped instead. "The surface shrinks first" carries the argument; this slice
carries the removal, and it is the removal that licenses the projection deletion the item was built
around.

**Deletes, the tool.** `EdgesTool`, `EdgeProducer`, `ReverseEdgeIndex` including its `Cache` and the
server's `reverseEdgeIndexCache` field, `Edge`, `EdgeKind` and `NodeRef`. Six types and one field,
none of which any surviving tool names. `EdgeProducer` alone is two exhaustive switches over ninety-odd
classification permits and four permit-set constants; `ReverseEdgeIndex` is a walk over every
classified field, an inversion into a `HashMap`, and a two-reference memo whose javadoc explains how a
torn read against a non-atomic multi-field swap self-heals. All of it goes rather than being rewritten
against relations.

`McpWire` keeps `methodRef` and `columnId`, which the code tools compose their own IDs from, and loses
whatever exists only to serve `NodeRef`. Check the qualified-table splitter at removal time rather than
assuming either way, since its only caller may be the node model.

**Deletes, the tests.** `EdgeCoverageTest`, whose subject is the agreement between `EdgeProducer`'s
permit sets and the permit space, and which therefore has no successor rather than a replacement.
`ConflictedReverseEdgeTest`. The `edges` block of `GraphitronMcpServerTest`, including the two
surviving hand-built `catalogFixture()` cases, which are its ambiguity and not-found arms.

An earlier reading of this item specified an `EdgeCoverageTest` successor at some length: three
assertions over the query set and the authored directive vocabulary, guarding against a relation
gaining an arm the tool never surfaces. That specification retires with the tool it was guarding. The
property it was protecting is real, and it belongs wherever those queries next exist rather than
here, where after this slice there is no query set for it to be a statement about.

**Deletes, the projection.** `CatalogFacts` and `CatalogFactsTest`,
`CatalogBuilder.buildCatalogFacts` and its `toKey` helper,
`GraphQLRewriteGenerator.BuildArtifacts`' `catalogFacts` component and the two-argument convenience
constructor that becomes the canonical one, `Workspace.catalogFacts` (field, accessor, and its
assignment in `setBuildOutput`), and `DevMojo`'s threading of the value. `TenantScopes` cites the
retired type in javadoc only and repoints.

The convenience constructor goes because it cannot survive rather than because it stops being useful:
its signature is `(CompletionData, LspSchemaSnapshot.Built.Current)`, which is exactly what the
canonical constructor becomes once the third component drops, so leaving it is a duplicate signature
and the module does not compile. That bounds the blast radius in the useful direction too. Every
two-argument caller compiles untouched against the new canonical constructor, which is every
construction site outside this module: `WorkspaceTest` (five sites) and
`BuildTriggerPublishesDiagnosticsTest` in `graphitron-lsp`, and `CatalogRefreshTest` in
`graphitron-maven-plugin`. They are named to record that they need no edit, not to schedule one.

Two main-code sites pass three arguments and lose the third: `GraphQLRewriteGenerator`'s own
construction, whose third argument is the `buildCatalogFacts` call being deleted with it, and
`DevMojo`'s re-wrap of a prior catalog. The remaining three-argument sites are both in
`GraphitronMcpServerTest`'s `edges` block and go with the block.

That the projection deletes in the same commit as its last reader is this item's binding constraint,
and it is satisfiable without waiting on anything: no `graphitron-lsp` surface reads `catalogFacts()`,
only the `Workspace` field the MCP tools read through. Re-check with a `catalogFacts()` grep at
pickup, since a reader that reappeared upstream changes the order.

The `JooqCatalog` walk that fed the projection does not retire with it: classification reads the same
catalog and `CatalogBuilder.build` still runs. What goes is the second pass over it that produced a
consumer-shaped copy.

**Wire.** The `edges` tool is gone from the tool list, from `mcp/instructions.txt` (its own bullet, and
its mention in the currency paragraph), and from the manual's tool table. The instructions gain no
replacement bullet: no surviving tool answers "what breaks if I change this", and saying so plainly is
better than pointing an agent at a tool that answers a narrower question.

**Leaves behind.** `catalogFacts` has no reader left, which is what licenses the deletion above.
`Workspace` keeps four accessors read: `catalog` four, `sourceIndex` three, `snapshot` five,
`vocabulary` one.

## Slice 5: `diagnostics.aggregate` deletes

**Deletes.** The `diagnostics.aggregate` tool specification and handler, and from
`DiagnosticFacets`: `aggregateResult`, the private `aggregate`, `summarize` and `groupByDimensions`,
the `TRIAGE_PRESET`, the four group and example bounds, the `TYPED_KEY_DIMENSIONS` /
`LOCATION_DERIVED_DIMENSIONS` bucket lists, `dimensionGloss` and `Dimension.wireNames`. Plus
`DiagnosticsAggregateTest` and `DiagnosticDimensionCoverageTest`, the latter having the bucket
partition as its whole subject.

**Survives, and this is the part worth stating so the deletion is not overcut.** `Dimension` itself
stays, because it is also the `diagnostics` tool's filter vocabulary: `conditions(graphName, args)`
builds that tool's predicates from it and two arms are matched directly. What retires is the
grouping half, not the filtering half.

The two class-level helpers that are not about diagnostics at all move rather than dying with the
aggregate. `refusal(tool)` and `error(message)` are wire shapes three catalog tools and the
diagnostics tool all call, so they belong in `McpWire`, which is already the shared wire-helper home.
What remains under the old name is the diagnostics filter vocabulary and nothing else, so the class
renames to match; `DiagnosticFacets` was named for the facets that are being deleted.

**Wire.** The tool disappears from the tool list, from `mcp/instructions.txt` (its bullet, and the
sentence routing group keys back into `diagnostics`), and from the manual's tool table. `diagnostics`
is unchanged, including its own filters and its snapshot axes.

**Leaves behind.** `snapshot` keeps four (`schema`, `status`, `diagnostics`, the `directives`
resource). `catalog` four, `sourceIndex` three, `vocabulary` one, unchanged.

## Slice 6: `catalog.describe` becomes one nested projection

The first cut of this tool shipped store-native and folded, and this slice fixes the shape before
`schema` is written against the pattern. Nothing about the question changes and nothing about the wire
changes; what changes is that the answer comes back as one result rather than six.

**Reads.** One query at the table grain. The resolved table row carries a `MULTISET` of columns in
`ordinal` order, a `MULTISET` of primary and unique keys each carrying a nested `MULTISET` of its
columns in constraint position order, the same for indexes, and a `MULTISET` per foreign-key
direction with the positional pairing of referencing to referenced columns expressed as the join it
already is. `Records.mapping` lands each level on the record it already has.

The spelling resolution stays a separate read, and that is the rule's own boundary rather than an
exception to it. Resolving `film` against the census asks whether one table, two or none match, and
the answer decides between describing a table, naming candidate schemas and reporting nothing found.
That is a different question from "describe this table", not a grain of the same answer.

**Deletes.** Both `LinkedHashMap<String, List<String>>` accumulators (keys, indexes), the `FkId`
grouping record, the mutable-list `Fk` built-then-rebuilt-immutable pass, and the `foreignKeys`
fold. The `scopedTo` predicate helper survives as the nesting's correlation.

Two paragraphs of javadoc retire with them. The consistency argument for taking a `StoreReader` (five
queries could straddle a capture commit) is answered by the statement being one statement; the reader
stays, on the plainer ground that a read must not ride the session writer's connection, and its
javadoc says that instead. And the note explaining why the foreign-key pairing is a positional join
rather than a Java zip keeps its point but loses its contrast, the zip having no successor to be
contrasted with.

**Wire.** Unchanged in every field and every order, which is what makes this slice cheap to verify:
the existing `catalog.describe` cases pass untouched. The deltas this tool declared when it first
landed (unique keys the primary key covers are reported, the key / index / foreign-key lists ordered
by name, columns in `ordinal` order) were already delivered and are not revisited.

**Leaves behind.** No accessor count changes; this slice reads no projection either before or after.

## Slice 7: `services`, `conditions` and `records` become one `code` tool

**Reads.** One nested projection per class over the `jvm_` census: the class row carries a `MULTISET`
of its callable methods, each with a nested `MULTISET` of parameters for the arity in the
`fqcn#method/arity` ref and its `declared_return_type` for the signature an author reads, and a
`MULTISET` of record components. Source locations join the `java_` declaration family
(`java_class_declaration`, `java_method_declaration`, `java_field_declaration`) for the `location` /
`locationStatus` wire fields, at the grain each one belongs to.

The three-way split the old tools performed becomes a `kind` argument over one answer rather than
three tools over one census: a class with `jvm_record_component` rows is a record, a method whose
`jvm_method.returns_condition` is set is a condition, and a class with callable methods is a service,
condition methods included, since the same class is both. That split was always the tools' own
derivation and `CodeTools`' javadoc says so; it stays a derivation and becomes three predicates over
one read instead of three handlers over three.

**Wire.** This is a breaking change on three tools and the argument for it is in "The surface shrinks
first". The `code` tool takes the same name filter, limit and cursor the three took (they shared one
argument schema), plus the `kind` selector, and its entries carry the same class refs, method refs,
components and location fields. What an agent loses is three tool names; what it gains is one call
where a class that is both a service and a record needed two. The instructions' three bullets become
one and the manual's three rows become one.

**Why here rather than in a separate item.** An earlier reading deferred these three to a Backlog
item on the grounds that their acceptance surface is source locations and they share no query with
the catalog reads. Both facts are true and neither is a reason to defer: the goal is the module
reading only the store, and a tool left on `CompletionData` keeps a `Workspace` accessor alive, which
keeps the module edge alive, which is what the item is for. That separate item is discarded and its
scope absorbed here.

**Leaves behind.** `sourceIndex` has no reader left. `catalog` keeps one, `schema`'s `nodeMetadata`
read, which slice 8 takes. `snapshot` keeps its four (`schema`, `status`, `diagnostics`, the
directives resource) and `vocabulary` its one.

## Slice 8: `schema`

The largest slice, and the only one whose wire cannot be preserved. Today the tool renders the
classification permit's name as `kind` plus that arm's slots, for every type and every field. There
is no store relation shaped like that and there should not be: the permit name is an artifact of the
generator's internal taxonomy.

The tool's stated intent is what it migrates onto. The server instructions tell an agent to reach
for `schema` to learn "what did graphitron make of a type or field, which table backs it, which
mutations write and to what", and to read `Unresolvable` / `Unclassified` as "graphitron could not
read the intent you are asking about, go to `diagnostics`". So the entry becomes the answer to five
questions per coordinate, each a slot present when the relation has a row and absent when it does
not:

* **What claims it**, from `intent_resolved_field_claim` / `intent_authored_type_claim`: the
  classifier and, on the field grain, the tier that decided it. The classifier vocabulary is the
  store's, which `SchemaView.mapClaim` already emits today for the conflicted arm and documents as
  "the store's classifier vocabulary, deliberately not a projection permit name". That arm is the
  template; this generalises it to every coordinate.
* **What it binds**: table, column, class, method, join path, participants, all six read at the
  coordinate grain as slots of the entry rather than as separate edges. This slice writes those reads;
  no earlier slice does, the tool that would have written five of them having been dropped instead of
  migrated. The relations are `intent_column_match_claim` for the column, `intent_bound_table` for the
  table with its `candidates` arity, `intent_field_reference_step_hop` for the join path,
  `graphql_union_member` and `graphql_implements` inverted for participants, and for the method two
  populations rather than one. `intent_field_producer_method` answers `@service` and `@externalField`
  through its two `declared_via` arms; a field whose method comes from an explicit `@condition` is not
  covered by that view, its own comment scoping it to those two, so the condition population is a
  second read over `graphitron_field_condition` joined to `jvm_method` and `jvm_method_parameter` for
  the arity. Reading only the view would drop every `@condition`-carrying input field's method slot
  silently. The sixth slot is the backing class, which is the next subsection.

  The condition read is an MCP query and deliberately not a third arm on
  `intent_field_producer_method`, which is the escalation rule applied rather than dodged. A rule
  graduates to a store view where it genuinely must be shared, and this one is not:
  `DeclTarget.methodBackedTarget` switches five arms and the unbound-input arm is not among them, so no
  language-server surface resolves a condition's method pair at all. Widening a two-value
  `declared_via` to carry a population one consumer wants would put the MCP's requirement in the
  model's vocabulary. What the query does is what that view does for its own two arms and what
  `ClasspathMethods` does for arity, so nothing here is a new mechanism, and `depends-on` stays empty.

  Four bindings are deliberately not read, and they are declared as removals rather than gaps: a
  composite `@nodeId` field's key columns, an interface's `@discriminate` column, a `@pivot`'s two
  columns, and a participant's cross-table column. Each is authored in the transcription family but
  has no derived view resolving it to a catalog column, so reading it would mean re-implementing a
  model rule in this module (in the `@nodeId` case, the catalog-primary-key fallback that applies when
  `keyColumns` is omitted). The `schema` tool reports the slot absent for those coordinates. This is
  the item's one accepted loss of information, taken because the new substrate is where the rule
  belongs and a view can add it later without a consumer change; "Where the answers change" states it
  as a delta.
* **Whether a verdict was demanded**, from `intent_resolved_field_demand` / `..._type_demand`, with
  the rule name. This replaces `Unresolvable` and `Unclassified` and is strictly more informative
  than either: those two say a verdict is missing, while `DEMANDED` plus a rule says one was
  expected and names why, and `EXEMPT` says the coordinate was never in scope. The instructions
  sentence routing an agent from `Unresolvable` to `diagnostics` keeps working and gets a reason to
  carry with it.
* **What conflicts**, from `intent_authored_claim_conflict`: verdict, directives, message. The
  `Conflicted` arm's per-claim breakdown is this relation joined to the authored claim views, which
  is where the current arm's data came from before it was flattened into a projection.
* **Where it is declared**, from `graphql_type_declaration`: every site, not the one the projection
  reduced them to. `LspSchemaSnapshot`'s own javadoc says the language server already moved to this
  relation and that the projection "retires when its remaining reader does"; that reader is this
  tool.

The `@node` metadata block joins `graphitron_node` and `graphitron_node_key_column` and stops coming
from `CompletionData.nodeMetadata`. Backing members keep reading `intent_class_member_slot`, through
MCP's own query rather than the LSP's.

### The backing class

One read of `intent_type_backing`, keyed by `(graph_name, type_name)`, joined to
`intent_type_backing_conflict` where the tool wants to say a type is answered more than one way.

That view is the right read rather than `intent_type_backing_class` underneath it, and its own
comment gives the reason: it is "one relation for the question every consumer of a backing actually
asks, which is what class, not which walk found it". The `schema` tool is exactly that consumer. Its
two arms are the `@table` binding read through the table's generated record and the producer-and-hop
closure, and the closure arm is materialized at capture cadence by a writer that clears and
re-derives its graph partition, so on any settled store the rows are current for every captured
graph. `declared_via` carries which arm answered, and the wire keeps it: an agent asking why a type
is backed by a jOOQ record wants the difference between "you bound it to a table" and "a producer
returns it".

An ambiguous binding is rows, as everywhere else in the model, and this view is where the model
declines a precedence the walk applies. A type whose `@table` binding and whose closure answer
differently is two rows; the walk resolves that pair by reading the table and never looking at the
class. The comment is explicit that a consumer may still apply that precedence by filtering on
`declared_via`, and that what it may not do is mistake the precedence for agreement. The `schema`
tool does neither: it reports both rows with their `declared_via`, which is the honest rendering of
a disagreement an author probably wants to know about.

One silence is worth stating because it looks like a bug from the wire. A table whose generated model
has no record class reports `org.jooq.Record`, which is not a backing, so the view drops it and the
type is unbacked here. The `schema` entry's table slot still answers for such a type; only its class
slot is empty.

**Wire.** Breaking, and the item's one deliberate breaking change. The `kind` values change from
permit names to classifier names, `backingShape.kind` goes, and the demand and conflict slots are
new. It is worth taking rather than preserving: a permit name is a fact about the generator's
internals that the wire had no business promising, the manual documents `schema` by what it answers
rather than by its permit vocabulary, and the alternative is to hold ninety exhaustive arms inside
`graphitron-mcp` forever to keep a label stable.

**Deletes.** `SchemaView.mapTypeClassification`, `mapFieldClassification`, `mapClaim` and
`mapBackingShape`, which together are the ninety-odd arms, plus the `joinPath` and `members` helpers
reshaped onto the new queries. `Edge.joinPath`'s component type stops being
`FieldClassification.FkStep` and becomes an MCP-owned hop record carrying the destination's full key,
since a bare-name record cannot hold what `intent_field_reference_step_hop` returns.

**Leaves behind.** `catalog` has no reader left. `snapshot` keeps three: `status`, the `diagnostics`
tool's axes, and the directives resource, which reads it beside the bundled grammar. `vocabulary`
keeps its one. Only the directives resource stands between here and the lifecycle arms being all
that is left, which is why it is the next slice.

## Slice 9: the `directives` resource

**Reads.** `graphql_directive` with `graphql_directive_argument` and `graphql_directive_location`:
per directive its `repeatable` flag and description, per argument its `type_sdl`, `named_type` and
description, and its applicable locations.

The bundled-plus-overlay structure goes with it, along with the `putIfAbsent` collision rule and the
degrade-to-bundled path: capture writes every defined directive of the merged schema, so one query
answers what two halves and a merge answered before. The resource's own promise, "the bundled
grammar unioned with the schema's user-declared directives", is unchanged as a description of the
content; what changes is that the store already holds the union.

One behaviour follows and is stated rather than discovered: before the first successful capture the
resource has no rows, where today it degrades to the bundled grammar. It reports that the way the
catalog tools report an unbuilt census rather than answering with a partial vocabulary, since a
directive list missing the user's own declarations reads as a grammar that forbids them.

**Deletes.** The `bundledDirectives` list the server computes at construction and the resource
lambda captures, which is the module's only `workspace.vocabulary()` call and therefore the whole
of its `LspVocabulary` coupling; the `workspace.snapshot()` read at the resource's call site; and
`DirectiveShape` as a type `graphitron-mcp` names.

`TypeShape` and the `renderType` recursion over it go with them, and that one is worth naming
because it is a `graphitron` type rather than an LSP one, so it survives the edge deletion and would
otherwise be residue. `DirectivesResource.renderType` walks `TypeShape.Named` and `TypeShape.List`
to rebuild an argument's SDL spelling (`String!`, `[Foo!]`). `graphql_directive_argument.type_sdl`
is that spelling, captured. The read replaces a recursion with a column.

There is no `LspVocabulary` import to delete, and that is the point rather than a detail.
`GraphitronMcpServer` reaches the registry as `workspace.vocabulary().registry()`, so the coupling
is spelled entirely in method calls. Slice 11's import scan would have passed a module that still
held it, had the `Workspace` parameter not gone with everything else; a coupling reached through a
call chain is invisible to exactly the guard written to catch it.

**Why this is a store read and not a third host-supplied value.** The obvious cheaper move is to
pass the bundled grammar in, the way the host already passes the handle and the reader, and it
would work: `DevMojo`
already calls `LspVocabulary.load()` at the composition root and hands the result to the `Workspace`
constructor, and `Workspace.vocabulary()`'s own javadoc calls the registry "shape, not state; there
is no setter". The host holds the value already. So the coupling was never a problem of reaching
data, which is worth saying because it is the reverse of every other slice here.

It is still the wrong move. Passing the bundled half in preserves exactly what this slice exists to
delete: two halves, a `putIfAbsent` collision rule between them, and a degrade-to-bundled path, all
to reassemble a union the store already holds. The host-hands-a-value pattern is reserved for what
the store *cannot* answer, which after the scope boundary's correction is only the connection
itself and process liveness, and which is not true here: capture writes every defined directive of
the merged schema, bundled and user-declared alike. Reaching for the pattern because it is available, rather than because the store
is silent, would leave the merge in the module and buy nothing.

**Leaves behind.** `vocabulary` has no reader left. `snapshot` keeps two, both of them the
lifecycle arms: `status` and the `diagnostics` tool's axes.

## Slice 10: `status` and the diagnostics axes

These become queries too, and an earlier reading of this slice said they could not and reached for
a host-supplied value instead. The scope boundary names that reading withdrawn and carries the
argument; what this slice carries is the derivation.

Availability is `store_graph` presence: a graph no capture has written is `Unavailable`. Freshness
is the SDL refusal relations' emptiness over the graph's partition: `graphql_syntax_error` and
`graphql_schema_error` are written by capture on every pass, on either outcome, so no rows is
`Current` and rows is `Previous`, whose meaning was always "the newest parse refused something and
the last clean facts are being held", which is what the transcription families do per source
anyway. The wire shape is unchanged: `statusResult` and `McpWire.writeSnapshotAxes` render the same
two fields from the query's answer, `toolsReady` stays the liveness bit no relation can carry (a
tool that answers has proved it), and the `LspSchemaSnapshot` import goes the way every other
projection import went, replaced by a query rather than by a value. The exhaustive switches over
the sealed permits retire with the type; the three cases in the tests section are what pins the
wire instead.

One behavioural divergence is accepted rather than ported. A first capture that met a refusal
reported `Unavailable` before, because no snapshot object had ever been built; it reports `Built` /
`Previous` now, because the store genuinely holds every fact the parseable sources yielded, and
answering as well as the facts allow is the doctrine. What stays out of the store is
in-flightness: whether a capture is running this instant is process state with a crash-shaped
failure mode (a dangling in-progress marker outlives the writer that crashed), and no tool's answer
depends on it beyond "may refresh shortly", so nobody stores it and nobody hands it in either.

**`RejectionKind` goes here too**, and it is the last generator type any main source names. It is
not a projection and no earlier reading of this item costed it, which is how it survived every
slice: `DiagnosticsTool` renders a diagnostic's kind as
`RejectionKind.valueOf(row.getKind()).displayName()`, reading a string out of the `diagnostic` view,
parsing it into a `graphitron` enum, and calling a method that lower-cases the name and swaps
underscores for hyphens. The whole of the dependency is a kebab-case transform.

What `valueOf` adds beyond the transform is validation, and the store already performs it:
`rejection_validation_error.kind` carries a closed `CHECK (kind IN ('AUTHOR_ERROR',
'INVALID_SCHEMA', 'DEFERRED'))`, which is the same three values the enum declares, enforced at write
time on the model's own closed-CHECK convention. So the enum was standing in for a constraint the
DDL states. `graphitron-mcp` renders the stored kind itself, and the `diagnostic.kind` column
comment (`RejectionKind.name()` on rejection-bearing rows, `NULL` elsewhere) is what the rendering
is written against. The wire is unchanged.

**Leaves behind.** Nothing. `Workspace` has no reader left in `graphitron-mcp`, and no main source
names a type from `graphitron`.

## Slice 11: the edge deletes

`graphitron-mcp`'s pom drops `graphitron-lsp` and declares `graphitron-model` and `org.jooq:jooq` at
compile scope, both of which it reaches transitively today. `graphitron` moves to test scope beside
`graphitron-sakila-db`, which stays. That is the one reactor dependency the goal names on the
compile surface, and the two the fixture names on the test surface.

**The classpath is not unchanged, and the difference is the payoff.** Six artifacts leave
`graphitron-mcp`'s compile and runtime classpath: `graphitron-lsp`, `org.eclipse.lsp4j`,
`io.github.tree-sitter:jtreesitter`, `graphitron-tree-sitter-natives`, and then `graphitron` and
`graphitron-javapoet` behind them. The natives jar is per-platform binaries published to Central for
the language server's parser, and this module has no parser. `graphitron-javapoet` is a Java source
emitter, and this module emits no Java.

It matters because `graphitron-mcp` is *published*: its pom comment records that a Maven plugin
resolves its declared dependencies from the consumer's repositories at execution time, so these are
artifacts a consumer fetches today and stops fetching after this item. What remains on the compile
and runtime surface is the store schema and jOOQ, which is what a store client is. Shedding the
generator and a native-binary jar is the module's stated dependency-quarantine purpose pointed the
other way for once: the quarantine was built to keep the heavy RAG stack off
`graphitron-maven-plugin`, and it turns out to have been carrying the generator and somebody else's
natives the whole time.

The runtime loses nothing it needs, because the server does not run alone. It is embedded in the
`graphitron:dev` JVM, whose plugin already holds the generator; what the server receives from that
host is a `StoreHandle` and a `StoreReader`, neither of which is a generator type.

Nothing in `graphitron-mcp` imports `org.eclipse.lsp4j`, the tree-sitter binding, or anything from
`graphitron-javapoet`, so those go without a replacement declaration. Re-check that at pickup with
an import scan rather than trusting this paragraph, since a slice landing between now and here could
reach for one.

The last `Workspace` reader is gone by slice 10, so the type is not a constructor parameter either:
`GraphitronMcpServer` takes its `StoreHandle` and its `StoreReader`, and
`DevMojo` stops passing it the workspace. That is what makes the import scan satisfiable rather than
merely the reads draining, and it is the last thing holding the `graphitron-lsp` import in the
module's main sources.

The `graphitron` side of the precondition is an audit rather than a count, and the slices above
discharge it in full. Every `no.sikt.graphitron.rewrite` import in the module's main sources today
is one of: `CatalogFacts` (slice 4), `CompletionData` and `SourceWalker` (slices 7 and 8),
`FieldClassification`, `TypeClassification` and `TypeBackingShape` (slices 4 and 8), `CatalogBuilder`,
`DirectiveShape` and `TypeShape` (slice 9), `LspSchemaSnapshot` and `RejectionKind` (slice 10). The
last two of those are the ones no earlier reading had costed, `TypeShape` because it hid inside the
directives resource's SDL rendering and `RejectionKind` because it is an enum rather than a
projection and so did not look like a read. Re-run the import scan at pickup: this list is a reading
of the module as it stands, and a slice landing in between can add a row to it.

Both guards land with it, and the tests section states them. The precondition is arithmetic from the
slices above rather than a grep nobody ran: every `Workspace` accessor has a stated reader count and
all five are zero, `vocabulary` included.

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
`CatalogTables`, `CatalogColumns` and `CatalogKeys` over `StoreHandle`, and `graphitron-mcp` compiles
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

So every read this item needs is written in `graphitron-mcp`, against
`no.sikt.graphitron.model.Tables`, scoped through `store.reads(...)` like every other read, shaped by
what the wire wants rather than by what a second consumer might one day want. The LSP keeps its
readers unchanged; nothing in `graphitron-lsp` is touched by this item.

After this item the choice is not available anyway, the module edge being gone. That is the right
order of the two arguments and not a substitute for the first: the guard enforces a rule the
doctrine already decided, and if the doctrine ever changes, the guard is the site that has to be
argued with rather than a habit nobody wrote down.

That also removes the constraint the shared-reader arrangement needed to make itself safe (no
MCP-only component on the shared records), the deferred relocation item it was paying for, and the
argument about `graphitron-model` having no test sources. A query written in the module that runs it
is tested by that module's own fixture.

`CatalogColumns`'s Javadoc overlay through `SourceDeclarations` is a case in point rather than a
wrinkle: it is a correlated `Field<String>` the LSP wants and MCP passes nothing for. Under a shared
reader that is a component one caller reads as permanently empty. Written separately, it simply is
not in the MCP query.

## How the dependency gets paid down

`graphitron-mcp` imports exactly two types from `graphitron-lsp`: `Workspace` and
`ClassMemberSlots`. That count understates the coupling, which is why the accessor ledger rather
than the import list is what this item's close-out counts. Four of the five `Workspace` accessors
MCP reads (`snapshot`, `catalog`, `sourceIndex`, `catalogFacts`) return generator types from
`graphitron`, not LSP types. The fifth, `vocabulary()`, returns an `LspVocabulary` the module never
names. So the edge is one state holder, one misplaced reader, and one vocabulary registry reached
without spelling its type.

Each is answered here:

* `catalogFacts` becomes the `sql_` census queries the catalog tools and the search corpus read.
* `snapshot` splits three ways. The classification maps become the claim and binding views, for
  `edges` and for `schema` alike. The directive list becomes `graphql_directive`. The
  availability / freshness arms become reads of `store_graph` and the SDL refusal relations, which
  capture writes on every pass; slice 10 carries the derivation and the scope boundary the argument.
* `ClassMemberSlots` is already a store read, so nothing migrates; MCP writes its own query over
  `intent_class_member_slot` and the LSP keeps its own for the four surfaces its javadoc names.
  Leaving the one existing instance of a coupling while declaring the rule against it would make the
  rule weaker than the exception.
* `vocabulary()` goes with the directives resource, its only caller. The bundled grammar its
  registry carries is in the store
  already: capture writes every *defined* directive to `graphql_directive`, bundled and
  user-declared alike, because the schema it captures is the merged one.
* `catalog` and `sourceIndex` become the `jvm_` census and the `java_` declaration family, in the
  code-tools slice. `catalog.nodeMetadata` goes to `graphitron_node` in the `schema` slice.

So the pom edge deletes here, with the guard test, rather than being promised to whichever of three
items lands last. That promise was an earlier reading of this item and it was worth abandoning:
"the last item deletes the edge" is a plan no item owns, and every one of the three could ship while
truthfully saying the edge was somebody else's. The edge is one item's to delete, and this is it.

No supplier of a generator projection survives either, which is the difference between this reading
and the one before it. An earlier draft kept `catalog` and `sourceIndex` as values the host handed
in, on the argument that `CompletionData` is a `graphitron` type crossing a declared `graphitron`
dependency and therefore breaks no stated rule. That argument is correct and beside the point: a
tool answering from a projection handed to it is still a tool that cannot be extended without
touching the pipeline, which is the cost the goal section says this item is buying out. The only
values the host hands the server after this are its `StoreHandle` and its `StoreReader`, neither
of which is a fact about a graph.

Under the compile-scope rule that argument stops needing to be made, which is the point of moving
the rule from the language server to the generator. "`CompletionData` crosses a declared `graphitron`
dependency" was true and was the whole trouble: while that dependency exists, every projection is
one import away, and each one has to be argued down on its merits. Deleting the compile edge retires
the argument rather than winning it again. No host-supplied value survives beyond the handle and
the reader, which is what lets the compile surface close over `graphitron-model` alone.

## The classification maps are questions, not a shape

There are three taxonomies, not one, and the module reads all three.
`FieldClassification` has twenty-nine permits, `TypeClassification` twenty-two, and
`TypeBackingShape` five that fan out to eight leaf arms in a switch.
`EdgeProducer` switches the first two; `SchemaView` switches all three and renders every arm
onto the `schema` tool's wire. Together that is around ninety exhaustive arms, which reads at first
like the size of what a store-side migration would have to reproduce.

It is not, because nothing consumes any of them as a union. Every use site is a narrow projection:

* `InlayHints.columnNameOf` asks "what column name" across four arms and lets the rest fall through.
  `InlayHints.fkPathOf` asks "what FK path" across two.
* `DeclTarget.methodBackedTarget` asks "what class and method" across five. `DeclTarget.ofType` is
  the clearest case: eight `TypeBackingShape` arms collapse into `tableTarget`, `SourceClass` and
  `None`, which is a three-way question wearing an eight-way costume.
* `EdgeProducer`'s ninety-odd lines of switch produce five edge kinds. Nineteen of its arms produce
  no edge at all and exist to be exhaustive. This is the extreme case of the pattern and it is why
  its tool is dropped rather than migrated: a switch whose answer is five labels, half of whose arms
  answer nothing, is not a taxonomy read that needs a store-side counterpart.
* `SchemaView` is the apparent counterexample, since it renders every arm. What it renders per arm
  is a `kind` label plus between zero and five slots drawn from a pool of about a dozen: table name,
  column name, class name, method name, join path, participants, discriminator, element type. The
  union is wide because it is a union of *slot combinations*, not because the wire carries ninety
  distinct facts.

So the permits are not a requirement any consumer has. They are what precomputing every question at
once costs, from when there was nothing to ask at read time. The questions themselves are seven:

1. Which table backs this type. `intent_bound_table`, with `candidates` for arity.
2. Which class backs this type. `intent_type_backing`, which coalesces both populations that can
   answer: a `@table` binding read through the table's generated record, and the closure over
   producer returns and accessor hops, discriminated by `declared_via`. `intent_type_backing_conflict`
   gives `class_names` and `candidates` where a type is answered more than one way.
3. Which column does this field match. `intent_column_match_claim` for the structural case, which
   already carries the resolved table's full key; `intent_field_column_table` where a directive
   moved the match off the parent's own binding, whose `disposition` and `basis` say which.
4. Which class and method does this field resolve to. `intent_field_producer_method` over
   `graphitron_service` and `graphitron_external_field`, carrying `descriptor` so an overload is
   distinguishable and arity is derivable. Those are its only two arms, so the `@condition`
   population is a separate read of `graphitron_field_condition` against the `jvm_` census, and
   `@routine` is no read at all: slice 4 carries both, with the reasons. An earlier reading of this
   item wrote all three relations as though one view spanned them, which is how a whole population
   went missing from a query list that looked complete.
5. What join path does it traverse. `intent_field_reference_step_hop` carries `constraint_name` and
   a fully-keyed `to_schema` / `to_table` per hop, ordered by `(ordinal, position)`, which is
   `FkStep` with a real key instead of a bare name.
6. Which types participate in this abstract type. `graphql_union_member` for a union;
   `graphql_implements`, read in the inverting direction, for an interface. Both are captured. This
   is the question the `PARTICIPATES` edge kind and the `participantTypeNames` wire slot ask, and it
   is the only one of the seven that no earlier reading of this item named.
7. What claims this coordinate, and was a verdict demanded. `intent_resolved_field_claim` gives the
   classifier and its tier; `intent_authored_type_claim` the type grain;
   `intent_authored_claim_conflict` the violated coordinates with their message and directives; and
   `intent_resolved_field_demand` / `intent_resolved_type_demand` say whether the model wanted a
   verdict here at all, with the rule that decided it.

All seven are a relation read. What each consumer wants is one of them at a time, which is what a
relation per fact is for, and joining them is the caller's business rather than a shape the model
has to anticipate. This is the escalation rule read forwards: the shared thing graduated to a view,
so the union that used to carry every combination has nothing left to carry.

Question 2 is worth a note on how it got that way, because for most of this item's life it was the
hard one. The backing class had no relation stating it, only edges: producer-method returns for the
ground and `intent_field_accessor_hop` for the step, with that view's own comment inviting a reader
to close over them. An earlier reading of this item took the invitation and put the recursion in the
`schema` slice. That would have been a mistake, and the store side found out first: a recursive form
over those edges measured at 369 seconds returning nothing on an adversarial census, because H2
re-evaluates a recursive view once per outer row of whatever joins it. The closure is now
materialized on the store side, written at capture cadence by a derivation that clears and re-derives
its graph partition, and the MCP reads rows.

The generalisation survives the correction and is worth keeping. A shadow relation answers a question
by transcribing what the code being replaced decided, so reading one buys an answer at the price of
keeping that code alive; `walk_type_backing_class` is still there and is still not what this module
reads. What changed is that the honest alternative stopped being "write the recursion yourself" and
became "read the relation that closure produced", which is the better trade in the same direction.

Three populations the closure does not yet carry are stated in `intent_type_backing_class`'s own
comment, and the `schema` slice inherits all three rather than working around any: the walk's
cardinality guard is not applied, so a single-object field produced by a collection return backs its
type here where the walk reads a carrier and declines; the two-level carrier fork is not applied, so
a payload wrapper backs itself here where the walk reaches past it to the data field; and a
`@table`-bound type seeds nothing into the closure, which is why the tool reads the coalescing view
rather than the closure relation. Each is queued for adjudication against the walk's shadow on the
store side. The tool reports what the relation says, which is the same posture it takes everywhere
else in this item.

This substrate is moving under the item faster than any other part of it, so the count above is a
reading of one commit rather than a durable fact. The input axis was absent when this paragraph was
first written and is not any more; the coalescing view did not exist and does. Re-read
`intent_type_backing_class`'s comment at pickup rather than trusting this paragraph's arithmetic.

The same move has a cost side, and it is the leaf-zoo connection. The classification projections are
the LSP-facing view of the generator's field and type taxonomy (the leaf zoo whose dissolution
`coordinate-lowers-to-datafetcher-queryparts.md` owns), and the exhaustive switches in
`CatalogBuilder.projectFieldClassification` and `projectTypeClassification` mean every new generator
permit costs a mapping decision for every reader the projections still have. After this item
`graphitron-mcp` is not one of those readers: a taxonomy that grows an arm changes no MCP query,
because the relations behind the seven questions are keyed by what a coordinate binds and what
claims it, not by what it was classified as. That is one consumer struck from the list the zoo's
dissolution waits on, and it is the whole reason `SchemaView` migrates here rather than being left
for later. Leaving it behind would have kept the module on ninety exhaustive arms while the goal
section claimed the opposite.

That also settles what the tools cost at rest, and the edge tools settle it by leaving: today the
first reverse traversal after a build pays a whole-schema walk, and nothing after this item pays one
because nothing walks. Every read this item adds is an indexed lookup on a captured or materialized
relation, so no tool pays a derivation at request time. That is worth stating because it was nearly
not true: the
backing-class closure was specced here as a per-request recursion before it existed on the store
side, and the store side's own measurement of that shape is why it is materialized instead.

## Where the answers change

The tool output is the acceptance surface, so the deltas are named rather than discovered. Three of
them remove a surface rather than change one, and they lead because they are the largest:

* **The `edges` tool is gone.** No surviving tool answers "what else touches this coordinate, what
  breaks if I change it". `schema` answers the forward half of what it used to, at the coordinate
  grain and under different field names, and the reverse half has no replacement and no successor
  item. "The surface shrinks first" carries the argument.
* **The `diagnostics.aggregate` tool is gone.** `diagnostics` still pages entries with the same
  filters, so what an agent loses is proportions and the group keys that fed back into the entry
  tool. Returns under `roadmap/diagnostics-aggregation-over-the-store.md`.
* **`services`, `conditions` and `records` become one `code` tool** with a `kind` selector, carrying
  the same entry fields the three carried.
* **Four bindings the `schema` tool used to report go absent.** A composite `@nodeId` field's key
  columns, an interface's `@discriminate` column, a `@pivot`'s two columns, and a participant's
  cross-table column. Each is authored in the store's transcription family but has no derived view
  resolving it to a catalog column, so reporting it would mean this module re-implementing a model
  rule. Slice 8 states the reasoning; a store view can restore each without a consumer change.
* **Unique keys are what the database declares.** A unique constraint whose column set the primary
  key also covers was dropped by `candidateKeys` and now appears in `uniqueKeys`. This is the
  intended direction: `catalog.describe` reports the catalog, and the dedup was another consumer's
  key-matching rule leaking into a discovery tool.
* **A described table's key, index and foreign-key lists are ordered by name.** The three lists
  carried whatever order jOOQ's `getKeys()`, `getIndexes()` and `getReferences()` returned, which is
  no stated order; ordering by constraint name and index name is stateable. Unlike the table and
  column orders below, nothing pages over these, so the delta is only that repeated calls agree.
* **Table order becomes schema then table name.** Today it is the generated `Tables` class's
  reflective field order, which the JDK does not promise is stable at all, and page cursors are
  offsets into it: a reordering between two calls silently skips or repeats entries. Under the
  keyset paging above the order is the cursor, so this stops being an ordering the census has to
  promise and becomes the one it is keyed by.
* **Column order becomes the table definition's.** `sql_column.ordinal` is the position
  `Table.fields()` states; the projection carried the reflective field walk's order, which is
  documented as no order in particular.
* **No table is named unqualified any more, anywhere.** `EdgeProducer.resolveTable` degraded a bare
  classifier name it could not uniquely resolve to a `TableNode` with an empty schema, whose
  `wireId()` had no qualifier to render, so a tool could answer with a bare `film` where every other
  table id is `schema.film`. That producer deletes with its tool, and `schema`'s binding slots read
  keyed relations, so nothing is left that can emit one. An ambiguously bound type reports the
  candidates `intent_bound_table` carries, each a full key, with the arity beside them.
* **A join path's hops carry their constraint's full key.** `FieldClassification.FkStep` holds a bare
  target table name and an FK name; both come back schema-qualified from
  `intent_field_reference_step_hop`. The same argument as the bullet above, on the payload rather than
  on the endpoint.
* **The `schema` tool speaks the store's classifier vocabulary.** Its `kind` values stop being
  `FieldClassification` / `TypeClassification` permit names and become classifier names from the
  claim views; `backingShape` goes, its table and class halves being questions 1 and 2 answered
  directly; and `demand` and `conflict` slots appear where `Unresolvable` and `Unclassified` used to
  stand in for both. This is the item's one breaking wire change, argued in the consumer section
  above. The server instructions and the manual's tool table both name the old vocabulary and change
  with it.
* **A type's declaration sites are plural.** `graphql_type_declaration` holds every site a type is
  declared or extended at; the projection reduced them to the canonical one. A type declared once
  answers identically, so the delta is visible only on an extended type, where it is a fix.
* **The `directives` resource is empty before the first capture** rather than degrading to the
  bundled grammar. Same posture as the catalog tools, for the reason the consumer section gives.
* **A missing handle refuses instead of answering empty.** The server can be built without a store
  handle; the diagnostics tool already refuses per call there, on the grounds that an empty answer
  reads as a clean schema. An empty catalog reads as a database with no tables, so the catalog
  tools take the same posture. This is not the pre-capture case: a store with no rows yet is an
  answer, and absence of rows is absence of tables.

  `catalog.search` changes arm rather than answer here, which is worth naming because it had a
  plausible-looking one already: a store-less server reported the warming degradation, telling an
  agent to retry on a server where retrying cannot help. Its corpus is the census after this item, so
  it refuses with the others. A store present and no embedder keeps the warming arm, that being an
  index with nothing to rank rather than nothing to rank over.

## What deletes, and when

Each slice above states its own deletions and they are not repeated here. What this section carries
is the cross-cutting half: what does *not* delete, and what has to move in the same commit as
something else or the build fails.

No classification permit is deleted by this item. `FieldClassification`, `TypeClassification` and
`TypeBackingShape` stay in `graphitron` with the LSP still reading them, and the sibling item retires
them when its last reader goes. What retires here is every `graphitron-mcp` read of them.

The `JooqCatalog` walk that fed `CatalogFacts` does not retire with the projection: classification
reads the same catalog, and `CatalogBuilder.build` still runs. What goes is the second pass over it
that produced a consumer-shaped copy.

The `walk_` family does not retire here either, and this item does not touch it. It drains on the
classification walk's own clock; what changes is that `graphitron-mcp` never becomes one of the
readers holding it open.

Javadoc citations repoint in the same commit as the code they cite, since the `{@link}` gate fails
the build otherwise:
`TenantScopes`, `McpWire`, `NodeRef`, `JooqCatalog`, `CatalogFactCapture` (whose reason for reading
`JooqCatalog` rather than the projection survives as prose about the projection's narrowings, with
the type named as history rather than linked), `FactCapture.capture`'s `@param jooq`,
`FactCaptureAgreementTest`'s constraint-census comment, and `LspSchemaSnapshot`'s
`typeDefinitionLocations` javadoc, whose "the MCP schema view is what still reads this" sentence
becomes false here and whose stated retirement condition is met by this item.

Two prose comments outside Java state the edge as a fact and go with it in slice 11, neither of them
reached by the `{@link}` gate. `graphitron-mcp/pom.xml` carries a block explaining that the compile
edge on `graphitron-lsp` exists because "the server now holds the live Workspace and reads
`LspSchemaSnapshot` off it", along with the acyclicity argument that justified it; the dependency it
explains is the one being deleted, so the block is rewritten to state the allowlist and why the
module needs only the two. And `GraphitronMcpServer`'s class javadoc describes its tools and
resources as reading "the live generator model", which after this item they do not: they read the
store and three values the host hands in.

Three doc surfaces state where the tools read from and change with them.
`docs/architecture/how-to/dev-loop-internals.adoc` says the MCP tools are backed by the warm
`Workspace`, which stops being true of all of them. `docs/manual/how-to/mcp-agent-context.adoc`
carries the tool table, where the `catalog.describe` row is the place the unique-key delta becomes a
user-facing sentence and the `schema` row carries the classifier-vocabulary change.

That page states the warm-`Workspace` framing twice outside its table, and both sentences keep their
shape and change one noun: the shared live thing is the warm *store*. What they claim is worth
preserving exactly, because it is the promise the page exists to make.

"The same `mvn graphitron:dev` process serves both the LSP (for your editor) and the MCP server (for
your agent) off one warm workspace" stays true of the process and the sharing, and after slice 11 the
server holds no `Workspace` at all, taking a `StoreHandle` and a `StoreReader` instead. One warm
store is what the two surfaces then share, and the sentence says so with one word changed.

"Three kinds of context, all served off the same warm workspace the dev loop keeps current" is the
one to be careful with, because its *same* is not the LSP and the MCP but the `about` prompt, the
`directives` resource and the tools: the claim is that everything an agent gets comes from one live
source rather than from three of varying age. That claim survives and gets stronger. Today it is at
its least true, the tools reading a mix of frozen projections, the `directives` resource composing a
bundled half with a snapshot overlay, and the diagnostics tool already on the store. After this item
every project-specific answer on that list is one store's rows, so *the same warm store* is a
tighter statement than the sentence makes now, not a weaker one. Only the currency clause needs a
second look: *the dev loop keeps current* attributed freshness to the build's swap of an in-memory
value, and freshness is capture cadence afterwards, which the page can say more plainly since
`status` reports `Current` / `Previous` off the SDL refusal relations rather than leaving an agent to
assume. The `about` prompt is bundled text rather than a store read, which is a looseness the
sentence already carries today and which the swap neither introduces nor has to fix.

The third surface is
`graphitron-mcp/src/main/resources/mcp/instructions.txt`, the agent-facing routing text: its
`schema` bullet names `Unresolvable`, `Unclassified` and the snapshot's `Unavailable` / `Previous`
as the readings that send an agent to `diagnostics`, and gives `Conflicted` its own sentence
carrying the rival claims, so both change with the vocabulary. That file is an acceptance surface
`ServerInstructionsTest` boots a real server against, so it changes in the same commit or the test
fails.

Three live roadmap items name something this item removes and are repointed as its slices land.
`lsp-structural-consolidation.md` lists `catalogFacts` among the fields its torn-read slice must
bundle behind one reference, and cites the MCP multi-field read (`edgesTool`: snapshot plus facts) as
what raised the stakes. That reader is gone entirely after slice 4 rather than reduced to its
snapshot half, so the multi-field argument loses its example and what is left of the concern is the
LSP's own reads; whoever picks that item up should re-derive its motivation rather than inherit this
one. `capture-load-residuals.md` frames a residual around `buildOutput` reusing the `catalogFacts` it
already holds. And `consumers-share-relations-not-queries.md` depends on this item for the
`ClassMemberSlots` seam closure, which slice 8 performs; that edge is unaffected by the widened
scope, since nothing here reintroduces a cross-consumer reader import.

A fourth is discarded rather than repointed: the Backlog item that planned the code tools' migration
to `jvm_` / `java_`, which slice 7 now performs. Its whole content was the substrate census that
slice cites, so nothing is lost by absorbing it, and a Backlog item describing work another item
owns is a plan nobody will pick up. Its file was already deleted while this spec was being drafted,
with the decision recorded here rather than left as a redirect, which is the workflow's `Discarded`
rather than its tombstone: the supersession is total and this spec captures it by reference. An
implementer has nothing to remove for it.

## Tests

The MCP module already has the fixture this needs: `StoreBackedBuild` runs a real
`GraphQLRewriteGenerator.buildOutput()` into a bootstrapped store and hands the server a handle,
which is how the diagnostics tool is tested. Every slice moves its tool's cases onto it and stops
hand-building projection fixtures, so the fixture work is front-loaded into slice 1 and amortised
across the rest.

**A store does not cost a build, and slice 3 corrects this section on that point.** Reading
`StoreBackedBuild` as the price of a store is what led slice 3 to reach for a store-free seam in the
first place; the generator is what that fixture pays for, not the store. `graphitron-lsp`'s own
`StoreFixture` opens an in-memory store and calls `FactCapture.capture` directly with a `JooqCatalog`,
which stands a census up in about 50 ms against the 400 ms a build costs, and writes rows only through
the real capture writer, so the property this section actually wants is intact: a fixture still cannot
encode a state capture never produces. `graphitron-mcp` gains the same affordance as
`no.sikt.graphitron.mcp.StoreFixture`, and the rule for the slices after this one is by what the tool
reads rather than by which fixture exists: a case reading the census or another transcription family
captures it directly, and `StoreBackedBuild` is for the cases whose rows come from the walk's own
streams, which is the diagnostics families and nothing else so far. Slice 3 moved the nine
`catalog.tables` / `catalog.describe` cases and both `catalog.search` server cases across, which is also
what makes three distinct censuses affordable in one case: two generated models plus the pre-codegen
state, where a build-shaped fixture would have made that three builds.

The fixture is the reason `graphitron` stays at test scope, and it is the only reason. It keeps its
generator imports (`GraphQLRewriteGenerator`, `RewriteContext`, `FactCapture`, the `SchemaInput`
family, the diagnostics fact writers), because producing a capture is running the generator and
there is no cheaper substitute that survives the substrate. What the *cases* lose as they convert is
different and is the item's actual subject: `GraphitronMcpServerTest`, `ServerInstructionsTest`,
`ConflictedReverseEdgeTest`, `EdgeCoverageTest`, `DiagnosticsAggregateTest`,
`DiagnosticsToolCompileSourceTest` and the three RAG tests import `CatalogFacts`,
`CompletionData`, `SourceWalker`, `LspSchemaSnapshot` and the three permits today, nearly all of it
to hand-build inputs (`EdgeCoverageTest` reflects over the permits instead, and retires for the
successor below rather than converting), and every one of those imports goes with the fixture it
served. An implementer
can use that as a progress signal: when the only `graphitron` imports left in the module's test
sources are the ones that drive a build or write facts, the conversion is done.

The cases below are grouped by the slice that lands them. Two of them, the fixture seam and the
close-out guards, are the item's own rather than any one tool's.

**Slices 1 to 3, the catalog tools.**

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
* `CatalogSearchIndexTest` / `CatalogSearchOnnxTest` read a captured census rather than a facts
  supplier. With gate one deleted, the hash gate is the only invalidation left and carries the cases,
  and against a store they state the thing itself: a recapture of the same generated model must not
  re-embed, and a recapture against a different one must. The store-backed case the bullet above calls
  for landed as `CatalogCorpusTest`, in the package whose code it exercises, and it pins both orderings
  (tables by the pair the id is spelled from, columns by `ordinal` inside the composed string), the
  descriptor's comment arms off the fixture's own DDL, the two-schema case where a corpus keyed by
  anything less than the qualified pair would silently lose a table, one graph's corpus excluding
  another graph's catalog, and the empty corpus a pre-codegen store answers with.
* **The ONNX retrieval case got sharper by moving, which was not the expected direction.** Its
  hand-built corpus was four richly commented tables and it asserted the intended table was among three
  hits returned, so three quarters of the corpus satisfied it. Over the real census it ranks among 58
  tables of which the fixture DDL comments exactly one, and the assertions became per-signal: a query
  naming only a table's *columns* ranks it first (`postal code and district` -> `public.address`), a
  query quoting a captured *column comment* ranks its table first (`free-text synopsis shown to
  renters` -> `public.film`), and an inflected name with no comment to help still ranks
  (`spoken languages` -> `public.language`). The original query survives as the recorded limit: with
  58 tables to rank among, `where are customer addresses stored?` puts `customer`, `rental` and
  `store` above `address`, so the intended table is in the page rather than at its head. That is the
  consumer's real case and worth a test saying so.
* `CatalogDescriptorsTest` keeps only what takes a string rather than a row, `splitWords` and
  `corpusHash`. Its descriptor cases moved to the captured census, on the grounds this section already
  states: a hand-built row can spell a comment or an ordinal the capture never spells.
* The foreign-key column pairing is stated by `sql_referential_constraint`'s own DDL comment, which
  calls it guaranteed by SQL semantics and never copied onto the referencing row. The relation
  therefore needs no case: as a positional join over two captured relations it is correct exactly
  when its inputs are. What needs one is the pairing arriving on the wire in the constraint's order,
  which the case pins through `public.project_note`'s two-column foreign key to `project` whose
  `targetColumns` come back in the referenced constraint's own order. It keeps that job through slice
  6, where the fold it was first written against is replaced by a nested projection.
**Slices 4 and 5, the two removals.** Nothing is added and four test classes go:
`ConflictedReverseEdgeTest`, `EdgeCoverageTest`, `DiagnosticsAggregateTest` and
`DiagnosticDimensionCoverageTest`, plus the `edges` block of `GraphitronMcpServerTest` and the
aggregate cases in the diagnostics blocks. Two of those are coverage meta-tests and deleting one
silently is how a taxonomy starts leaking, so each states its own reason for having no successor.
`EdgeCoverageTest`'s subject is the agreement between `EdgeProducer`'s permit-set constants and the
permit space, and both sides go with the tool; the property it was really guarding, that a relation
gaining an arm the tool never surfaces gets noticed, has nothing left in this module to be asserted
over once the queries go. `DiagnosticDimensionCoverageTest`'s subject is that the aggregate's
dimension enum partitions into two declared buckets, and the buckets go with the grouping half; the
filtering half of the enum survives and is already pinned by the `diagnostics` cases that filter on
it.

What both slices do need is a negative case each, because a deleted tool that is still registered is
a live tool: one assertion that the server's advertised tool list is exactly the expected names, which
also catches a later slice adding one by accident. That belongs in `ServerInstructionsTest`, whose
subject is already what the server tells an agent it can do.

**Slice 6, the nested projection.** No new cases and this is the point: the existing
`catalog.describe` cases are the verification, and a reshape that changes no wire field passes them
untouched. One case is added rather than moved, aimed at what nesting newly makes possible to get
wrong: a table whose keys, indexes and both foreign-key directions are all non-empty at once, so a
mis-correlated `MULTISET` shows up as a child list attached to the wrong parent rather than as an
empty one. The existing foreign-key pairing case covers the ordering half.

**Slice 7, the `code` tool.** The three old blocks' assertions survive and become three `kind` cases
on one tool: the same class refs, method refs, components and `location` / `locationStatus` fields,
against a store captured from the test sources rather than against a hand-built scan. One case is new
because the merge makes it expressible: a class that is both a service and a record answers once with
both, where two tools each answered half. Two more are new because the store distinguishes what the
scan did not, a class the census never reached and a class it reached that declares no such method
being separate answers on `intent_field_producer_method`'s stated terms, with `locationStatus` the
field where that surfaces.

**Slice 8, `schema`.**

* **The cases are rewritten against a real capture, not ported.** The existing
  `GraphitronMcpServerTest` block asserts permit names and slot bags off hand-built projections;
  under the new wire there is nothing to port, because the vocabulary changed. What replaces it is
  one case per question: a table-bound type reports its binding, a class-backed type reports its
  class and members, a field reports its column with the table's full key, a service-backed field
  reports its method, a referencing field reports its hops, an abstract type reports its
  participants, a conflicted coordinate reports its directives and message, and an exempt coordinate
  reports `EXEMPT` with its rule. That last one has no predecessor at all and is the case the demand
  views buy: today an unclassified coordinate and an out-of-scope one are the same answer.
* **The backing-class cases assert the tool's rendering, not the closure.** The closure is
  `intent_type_backing_class`'s, derived and shadow-tested on the store side, so re-asserting its
  reachability from here would be a second opinion on somebody else's relation. Four cases, one per
  thing the rendering can get wrong: a closure-backed type reports its class, its members and
  `declared_via`; a `@table`-bound type reports its generated record class through the same slot,
  since the coalescing view is what makes those one answer; a type both arms answer differently
  reports both rows rather than applying the walk's table-wins precedence; and a table whose model
  has no record class reports its table with an empty class slot, that arm being dropped by the view.
* **The classification-arm coverage question does not come back in a new spelling.** The old
  `SchemaView` guard was its own exhaustive switch; the new reads have no arm count to cover. What
  replaces it is the same shape as the edge-kind successor above: every wire slot the `schema` entry
  declares is produced by one of the queries, and every relation the queries read feeds at least one
  slot. Asserting a mapping between the permits and the classifier vocabulary is explicitly not
  wanted, for the reason the last bullet in this section gives.

**Slice 9, the directives resource.** One case: the resource renders a bundled directive and a
user-declared one from one captured schema, with arguments and locations, and reports the
pre-capture case rather than degrading. The bundled / user-declared distinction is not asserted,
because after this item the resource does not draw one.

**Slice 10, `status` and the diagnostics axes.** Three cases, one per arm, driven through the
store: a pre-capture store answers `Unavailable`, a clean capture answers `Built` / `Current`, and
a capture whose source set includes one refused file answers `Built` / `Previous` off the refusal
rows. The third case is the one the fixture has to earn, since `StoreBackedBuild`'s default
sources all parse; whether the fixture can run a capture over a deliberately broken source is a
substrate check at pickup (the verdict stratum is written on every pass, but the fixture drives
`buildOutput`, whose refusal behaviour is the sibling item's ground). The divergence this case
pins, `Built` / `Previous` where the incumbent said `Unavailable` on a first failed parse, is the
slice's stated behaviour rather than a regression.

**The item's own cases**, which belong to no single tool.

* **The fixture declares database comments, so the `comment` slot is asserted rather than mocked.**
  This is the same class of fixture cost as the ambiguity case below and it surfaced the same
  way: a hand-built projection can assert a comment by fiat, and a real capture can only show what
  the DDL declares. `graphitron-sakila-db`'s `init.sql` carried no `COMMENT ON` statement at all, so
  every one of its 57 generated table classes carried an empty jOOQ comment, `CatalogFactCapture`
  wrote `NULL` on its own blank-is-absent rule, and both catalog tools' `comment` slots were absent on
  every row a capture could produce. Nothing was wrong with the crawler: it reads the comment off the
  live `Table` and normalises blank to `NULL` precisely so a reader can tell `''` from absent. The
  source said nothing.

  So `init.sql` declares comments, on both grains, and that is the fixture affordance rather than
  decoration. What it buys is larger than the two wire slots: the description columns are captured
  from the database through the crawler to the wire with no test anywhere exercising the path today,
  and a slot no capture can populate is a slot whose only coverage was a mock. The set is chosen for
  what it discriminates rather than for breadth. `film` carries a table comment and `actor`
  deliberately carries none, which is the arm pairing the retired projection fixture asserted by
  fiat. Within `film` some columns carry one and some do not, so the mixed case lives inside one
  table rather than needing two. One comment carries an apostrophe, that being the character a naive
  pipeline breaks on and a real consumer's catalog certainly holds. And `film.description` carries
  one, so a column *named* description carrying a description cannot be confused with the column's
  own comment by any reader downstream.

  Bumping `jooq.codegen.schema.version` is what makes the regeneration happen on an incremental
  build, and the pom comment already says so.

  Declaring the comments answers a question nobody had been able to ask, and the answer is worth
  recording because it bounds what the fixture can pin. Only two of the three consumers of a jOOQ
  comment carry it. `JooqCatalog.columnFactsOf` reads it off the live field, so the catalog-discovery
  projection has it and `CatalogFactCapture` writes it to `sql_table.description` and
  `sql_column.description` at both grains, which is what makes slice 2's column-comment case real.
  The larger find is in `graphitron-lsp`, and it is why declaring the comments was worth more than the
  two wire slots it was asked for. Three surfaces there (`Hovers`, `DeclarationHovers`,
  `TableCompletions` / `FieldCompletions`) each carry a deliberate, documented, *mutually inverted*
  precedence between a database comment and a generated Javadoc: at the table grain the comment wins,
  because a generated table class's Javadoc names the table back at the reader, and at the column grain
  the Javadoc wins, because a generated field's Javadoc carries the qualified column name *and* the
  comment where one exists and is therefore the richer of the two. Not one of those six sites had ever
  been executed with both sources present, because the fixture could only ever supply one. The column
  arm's stated reason is now checked and holds: jOOQ writes `film_id`'s field Javadoc as "The column
  public.film.film_id. Surrogate key, stable across catalogue imports." Eight tests failed on the
  fixture change and every one of them had been asserting emptiness as a proxy for "nothing parsed
  yet", two of them saying "the fixture database carries no comments" in the assertion description.
  They are repaired by asserting the precedence where the precedence is the subject and by moving to a
  commentless table (`actor`, `film.release_year`, left bare for exactly this) where absence is. One
  drift guard turned out to be stating something no longer true, its overlay-present-exactly-when-goto-
  jumps biconditional being false for a commented catalog target, whose overlay needs no parse at all;
  it is split into the source-derived arms, where the biconditional is exact, and the catalog arms,
  where the asymmetry is the design and the commentless case pins that the parse is the only other
  origin. That work is in `graphitron-lsp`'s tests, which this item otherwise does not touch; the
  scope-boundary sentence about not touching that module is about migrating reads, not about a shared
  fixture's consequences.

  The completion projection does not: `CatalogBuilder.buildColumn` hardcodes the empty string,
  because hover joins the LSP's source index for a column's Javadoc at request time and that shape
  never asked the database for anything. So `CatalogBuilderSourceTest`'s emptiness assertion is
  correct and stays, and the one thing that changes in `graphitron` is a stale parenthetical in
  `buildColumn`'s javadoc claiming a column comment is "not recoverable from the runtime catalog",
  which the sibling method in the same class recovers. An expectation of a comment there would have
  been a misreading of which projection carries what, and it was worth being wrong about once to
  find that out.

* **The unique-key delta needs a fixture table, for the same reason the comments did.** The delta
  above is that a unique constraint whose columns the primary key already covers stops being
  filtered out, and no table in `init.sql` had one: every fixture there pairs a primary key with a
  unique constraint over *different* columns, so the covered case could not be observed from a
  capture at all. A hand-built projection could assert it by fiat; this is the third time in this
  item that anchoring on the source turns a mocked slot into a fixture affordance, and it is the
  right cost each time.

  So `init.sql` declares `redundant_unique_key`, a two-column table whose whole content is its
  constraint pair, with no seed rows because nothing selects from it. It carries the delta's case and
  nothing else, which is why it is a new table rather than a constraint bolted onto an existing
  fixture: changing one of those would move a generated `Keys` constant that an execution-tier test
  may name.

  The constraint has to be declared by `ALTER TABLE`, and the discovery is worth recording because
  the obvious spelling silently does nothing. PostgreSQL's `CREATE TABLE` analysis discards a
  `UNIQUE` whose column list matches a `PRIMARY KEY` declared in the same statement, so
  `entry_id serial PRIMARY KEY, UNIQUE (entry_id)` yields one constraint while reading like two, and
  a fixture written that way would have made the delta look untestable rather than undeclared. Split
  across two statements both constraints exist, jOOQ's generated model carries the primary key on
  `getPrimaryKey()` and the redundant one on `getUniqueKeys()`, and `candidateKeys` drops the second
  on its column-set dedup, which is exactly the behaviour the slice changes.

* **Two cases need a second fixture package, and providing the seam is slice 1's work**, since it is
  the slice that first needs a capture the default package cannot produce. A bare
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
  it a parameter defaulted to today's value is small, and it is the precondition for the two cases
  below.

* The two cases that seam unlocks, landing with slices 2 and 4 respectively. `catalog.describe`
  resolving a spelling two schemas declare answers with both rows rather than one, which is the
  ambiguity case relocated onto a fixture that carries it. And the edge tools' loss of the
  resolution step gets a case of its own, being the one wire fix here: a type bound to that spelling
  emits fully-keyed candidate targets rather than one unqualified `TableNode`. Both want a fixture
  that cannot lie about which table a spelling reaches, and neither is writable against a census
  with one schema in it.
* `ServerInstructionsTest` is the one test whose premise changes rather than its subject, and it is
  touched by every slice, so it is the item's rather than any one slice's. Its `pagedWorkspace`
  fixture hand-builds a two-table `CatalogFacts` purely so a `limit=1` call on `catalog.tables`
  pages, beside hand-built projections giving five other tools two entries each, and the test boots
  a real server and asserts every tool's leading count line (`catalog.tables: 2 table(s)` and its
  per-tool siblings) against what came back. As slices
  land, those hand-built projections lose their readers one by one, so the fixture converts to
  `StoreBackedBuild` in slice 1 and each later slice drops the projection it stopped needing. The
  constraint is a per-tool minimum count, which a real capture does not promise by construction, so
  the SDL the fixture captures is chosen to yield at least two entries per paged tool. That is a
  fixture-authoring job rather than a design decision, but it has to be done once at the front
  rather than discovered when an assertion fails mid-slice.
* The member-slot query gets a case rather than a repoint, because there is none to repoint:
  `SchemaView.members` is production code with no test exercising it, the module's one `backingShape`
  assertion pinning `TableBacking`, the arm without members. Slice 8's backing-class cases are where
  the slots get asserted; the read is the same relation with the same ordering, so what they pin is
  that the entry still renders the slots, not that a new rule was introduced.
* Nothing in this item writes an agreement test between the permits and the relations. Two Java
  spellings of one taxonomy is what the permit partition was; a second one keyed on rows would be
  the same mistake with a store underneath it. The per-tool cases against a real capture are what
  pins the behaviour.
* Every query this item writes is tested from `graphitron-mcp`'s own fixture, which is the practical
  half of writing them here: a query lives in the module whose acceptance surface it serves, and is
  pinned by the tests that assert that surface.

**Slice 11, the guards.** Four, one per goal property, and each is the property restated as an
assertion. Writing them in the close-out slice rather than up front is deliberate: a guard that
fails for eight slices is a guard someone disables.

* **The connection-ownership rule.** A source scan over
  `graphitron-mcp`'s main sources fails on a reference to `GraphitronModelStore`, to a store-opening
  entry point, or to a store directory path, the module's whole store surface being the `StoreHandle`
  and `StoreReader` it is handed. `DevQueryExecutor` is the one exclusion and it is a named one, its
  connections being to the consumer's own database. Written now, the guard is what keeps the
  `StoreReader` parameter from becoming a `StoreReader` the module mints for itself the first time
  someone finds passing it through inconvenient.
* **The reactor dependency set**, which is the goal property restated, is deliberately an allowlist,
  and is deliberately scoped. Two halves, and both are needed.

  A pom assertion reads `graphitron-mcp`'s declared dependencies, takes the ones in the `no.sikt`
  group, and fails unless that set is exactly `graphitron-model` at compile scope and
  `graphitron` plus `graphitron-sakila-db` at test scope. Anything else in the reactor fails in any
  scope, and `graphitron` at compile scope fails too, which is the case the whole item is for.

  A source scan fails on any `no.sikt.graphitron.lsp` import in main or test sources, and on any
  `no.sikt.graphitron.rewrite` import in *main* sources only. The asymmetry is the scope split
  restated at the source level: the fixture may drive the generator, and no tool may name it.

  Each half covers what the other cannot. The import scan alone would pass a pom that still declares
  `graphitron` at compile scope, which is the state that lets the next reader reach for a projection
  without noticing they are widening a dependency. The pom assertion alone would pass a module that
  reaches the generator transitively, which is how the language-server edge arrived in the first
  place.

  The allowlist shape is the part worth insisting on. A denylist naming `graphitron-lsp` asserts the
  history rather than the rule, and it passes every reactor edge nobody has added yet;
  `graphitron-javapoet` sits on this module's compile classpath transitively today and would sail
  through one. Stating the permitted set means a new edge has to be argued for at the guard rather
  than noticed later, and the guard's message says the rule rather than the symptom:
  `graphitron-mcp` answers from the store and from what its host hands it, so the store's schema is
  the whole of what it compiles against. Naming `graphitron` and `graphitron-sakila-db` in the
  test-scope allowlist rather than exempting test scope wholesale is the same instinct: the capture
  fixture is a named affordance, not a hole.
* **The leaf zoo**, failing on a reference to `FieldClassification`, `TypeClassification` or
  `TypeBackingShape` from `graphitron-mcp`. Its subject is now the *test* sources, and that narrowing
  is a consequence of the compile-scope rule rather than a weakening. In main sources the
  `no.sikt.graphitron.rewrite` scan already catches all three, these being `graphitron` types on a
  dependency main sources no longer have; a dedicated guard there would assert the same thing twice.
  Tests keep `graphitron`, so tests are where a permit switch can still be written, and
  `EdgeCoverageTest` is the precedent: its whole subject is a partition over the permits, and its
  successor is specified in the tests section as a statement about the query set precisely so the
  taxonomy does not come back through the fixture.
* **The `walk_` family**, failing on a reference to `WALK_TYPE_BACKING_CLASS`,
  `WALK_CLAIM_DOMAIN_TYPE` or `WALK_CLAIM_DOMAIN_FIELD` from `graphitron-mcp`. Same argument as the
  leaf-zoo guard and the same failure mode: these are ordinary relations on `graphitron-model`, a
  dependency the module keeps. `walk_type_backing_class` is the live temptation, since it is keyed
  the same way as `intent_type_backing` and answers the populations the closure is still being built
  out to cover. The guard's message says why not: the family drains, and a consumer of it does not.

The last three guards are what make the goal's properties enforceable rather than aspirational, and
they are the reason those properties are worth stating separately at all. No agreement test between
projection and store is worth writing alongside them: it dies with the projection in the same
commit, and what it would have asserted is exactly what the per-tool cases assert against a real
capture.

## Retired vocabulary

The three tool removals retire more names than any other change here, and a dropped tool's vocabulary
is the kind that survives in prose long after the code goes, so it leads.

* `EdgesTool`, `Edge`, `EdgeKind` with all five of its constants, `NodeRef` and its six permits, and
  the whole vocabulary the tool taught: "the traversal layer", "a typed neighbour", "the direction
  query axis", "forward edges" and "the reverse direction" as things `graphitron-mcp` has, "the
  stable-ID grammar the edges tool walks", "an edge endpoint", and "impact analysis" as a capability
  this module currently offers. What survives is the stable-ID grammar itself, which the catalog,
  `schema` and `code` tools compose and accept; what retires is its framing as a graph the edges tool
  walks
* `BACKS`, `TARGETS`, `REFERENCES`, `RESOLVES` and `PARTICIPATES` as names for what a coordinate does
  to a table, column or method, in prose as well as in code. Anything reading a binding after this
  item names the authored directive that made it, the store's classifier, or the relation itself
* `DiagnosticFacets` as a class name and "facet" as the word for a diagnostics grouping key, with
  `aggregateResult`, the triage preset, "the typed-key / location-derived buckets" as a declared
  partition, "an elided group" and "the dimension gloss"; `Dimension` survives as the `diagnostics`
  tool's filter vocabulary and nothing else does
* `services`, `conditions` and `records` as three tool names, and `CodeTools`' three-result framing
  with them; "the conditions tool is the condition-filtered view" retires as a cross-reference between
  tools and returns as a sentence about one tool's `kind` argument
* "several queries in one read transaction" as the shape of an answer, with the multi-query tearing
  argument that justified it, the `FkId` grouping key, and "folding the rows" as a step a reader has.
  What replaces all of it is one nested projection, and the reader's remaining justification is that a
  read must not ride the writer's connection
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
* "the classification projection" as prose for what any MCP tool reads, and the permit-set
  constants' framing of a "partition over the classification permits" as `graphitron-mcp`'s
  coverage obligation
* `intent_field_producer_method` as the whole of what answers "which method does this field resolve
  to", and any framing of the producer relations as one view spanning `@service`, `@externalField`
  and `@routine`; the view has two arms, `@condition` is its own read and `@routine` is none
* `SchemaView.mapFieldClassification`, `mapTypeClassification`, `mapBackingShape`, and "backing
  shape" as a wire concept; with them "the classification / backing-shape / snapshot mappings are
  exhaustive switches over the sealed permits" as a statement about this module
* `Unresolvable` and `Unclassified` as the `schema` tool's answer for a coordinate with no verdict,
  the demand views distinguishing "no verdict where one was owed" from "never in scope"
* "the bundled grammar unioned with the live snapshot's user-declared directives" as a description
  of how the `directives` resource is *composed*, with "the bundled half", "the live overlay" and
  the collision rule between them; the union is the store's, and the resource reads it
* "the warm `Workspace`" as prose for what backs the MCP tools, in javadoc, in
  `dev-loop-internals.adoc` and in `mcp-agent-context.adoc` alike; with "one warm workspace" and
  "the same warm workspace the dev loop keeps current" as the manual's two spellings of it. The
  retirement sweep has to reach the manual and not only the contributor docs, this being the surface
  a consumer reads. What retires is *workspace* as the shared thing, not the sharing: "the same warm
  store" is the replacement in both sentences, and a sweep that deleted the unity claim along with
  the noun would have thrown away the promise the page is for
* "the live snapshot" as the thing `status` and the diagnostics axes read, and "the lifecycle
  value" / "the lifecycle supplier" as a thing the host hands in; the axes are row-derived and the
  host hands only the handle and the reader
* "the flat `ExternalReference` scan joined with the source index" as prose for what the code tools
  read, with `SourceWalker.Index` and `CompletionData.ExternalReference` as `graphitron-mcp` names
* "the last item deletes the edge", and any framing of the `graphitron-lsp` dependency as work
  shared across items or owed by a successor
* "delete the `graphitron-lsp` edge" as a statement of what this module's dependency rule *is*. The
  rule is the scoped allowlist, and deleting that edge is one consequence of it; a denylist naming
  the one artifact that happened to be there records the history instead of the rule
* `RejectionKind` and `TypeShape` as types `graphitron-mcp` names, with `renderType` as a step the
  directives resource has; the stored kind is rendered here and an argument's SDL spelling is a
  captured column
* "a `graphitron` type on a dependency the module legitimately keeps" as a reason anything is
  reachable from `graphitron-mcp`'s main sources. Main sources keep no such dependency; the phrase
  survives only for `graphitron-model`'s relations, which is where the `walk_` guard uses it
* the pom comment's justification of the compile edge on `graphitron-lsp`, with "the server now
  holds the live Workspace" as the reason for it and the acyclicity argument that made it safe;
  there is no edge left for either to be about

## Scope boundary

Nothing generator-side is read through the language server after this item, and no generator
projection is read at all. Nothing is read as a live value rather than as rows either: the host
hands the server its `StoreHandle` and its `StoreReader`, and everything past the connection is a
query.

**The dev-session lifecycle is rows, and an earlier reading of this section argued it could not
be.** The withdrawn argument, kept because its failure mode is instructive: the store holds what
the last successful capture wrote, so a graph whose newest parse just failed and a graph nobody has
edited are the same rows, and the distinction `Built.Previous` draws is a fact about the session's
*possession* of a build, which exists only in the process holding it. Both premises describe the
projection pipeline, where a snapshot object is minted only on success, and neither survives the
verdict stratum: `graphql_syntax_error` and `graphql_schema_error` are written by capture on every
pass, on either outcome (`SdlVerdictCapture`'s javadoc and both relations' comments state the
cadence), so the freshly broken graph holds refusal rows the untouched one does not, and "the last
good facts are being held" is not a possession but what the transcription families do per source.
Slice 10 carries the derivation. What is genuinely process state after the correction is process
liveness, which a tool proves by answering, and capture in-flightness, which no relation should
carry and no tool needs.

That is the whole of the boundary, and stating it that way is the point of taking full ownership.
Earlier readings of this item left four things outside it: the code tools, on the argument that
their acceptance surface differs; the pom edge, on the argument that a successor would delete it;
the backing class, on the argument that its relation was unbuilt; and the lifecycle, on the
argument withdrawn above. None survived contact with the goal. The first two are answered by the
slices; the last two were simply wrong about the substrate, since `intent_field_accessor_hop` and
the verdict stratum exist precisely so a reader can close over them.

One claim from an earlier reading is withdrawn and worth naming, since it points the same way both
times it has been corrected: the column match at a site whose table is not the parent's own is
answered by `intent_field_column_table`, whose own comment states the omission of the parent case as
deliberate and reads absence as "the parent's own scope answers". An implementer should re-check the
substrate at pickup rather than trusting this document, since the store moves under the item.

The corrections no longer all point one way, and the exception is the more instructive of the two
directions. Most have found more readable than the item claimed, which is the store growing under a
document written against an earlier commit. The `RESOLVES` correction in slice 4 found less: a view
whose name reads like the whole answer answers every producer population but one, and the item had
written it as the whole answer. That failure mode does not announce itself the way a missing relation does,
because the query list looked complete and the wire kept emitting the kind. So the re-check at
pickup is two questions, not one. Whether a relation this document names still exists and says what
it is quoted as saying, and whether it covers every population the tool it replaces answered for,
which is a question about the view's arms rather than its existence and is answered by reading its
`declared_via` vocabulary or its `FROM` list rather than by finding the name.

`depends-on` stays empty and should remain so. Three items lean on this one, which is the coupling
running the right way: `consumers-share-relations-not-queries.md` for the reader-import seam,
`fact-store-test-harness-consolidation.md` for the settled `StoreBackedBuild` it lifts onto the
shared harness home, and the LSP fact-store item's retirement sweep for `CatalogFacts`, whose term
survives that item's own diff until slice 4 lands here. Whoever takes the LSP item to Done should expect that and read it as
a sequencing fact rather than a failed sweep.

The pom edges are deleted in slice 11 with both halves of the guard, and that is a change from an
earlier reading which deferred the language-server one to whichever of three items landed last. The
deferral was wrong on its own terms: the code-tools item moved reads that crossed a `graphitron`
dependency, so it never had a reason to touch the LSP edge, and the LSP item does not touch
`graphitron-mcp` at all. No successor was going to inherit the deletion, which is how a promised
cleanup becomes a permanent one.

The `graphitron` compile edge goes the same way and for a sharper version of the same reason. It is
the edge every deferral above was implicitly leaning on: "the code tools read a `graphitron` type
across a declared `graphitron` dependency" was the argument that made three separate reads look free.
Once the compile surface is `graphitron-model` alone, that argument has no premise, and the reads
this item moves cannot be re-created by a later one without the guard saying so first.
