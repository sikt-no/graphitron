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

The item takes full ownership of the module's dependencies and finishes the job. It waits on no
other item and defers nothing to one: every read that has to move, moves here. That is a change from
earlier readings, which split the work three ways and left each part able to ship while truthfully
saying the remaining coupling was somebody else's. The module's reads do not separate cleanly
anyway. `catalog.describe` reads `CatalogFacts`, `edges` reads `CatalogFacts` *and* the
classification maps, `schema` reads the classification maps *and* a store relation already, the code
tools read the classpath scan, `status` and `diagnostics` read the snapshot's lifecycle arms, and the
`directives` resource reads the snapshot *and* the language server's own vocabulary registry.
Migrating a subset leaves the module holding a
`Workspace` for whatever was left, which is the state it is in today.

The LSP fact-store item (`lsp-reads-the-fact-store.md`) retires the `CatalogFacts` projection from
the language server's side and cannot delete the type while a non-LSP consumer reads it. This item
deletes it, in the slice that migrates its last reader. That is a courtesy the sequencing makes
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
Eleven of the thirteen tools plus the resource change here, and this table is the item's spine: each
row is a slice below, and the item is done when every row's right-hand column is true.

[cols="1,3,2"]
|===
| Tool | What it is for | Where the answer comes from after this

| `catalog.tables`
| Which tables exist in this graph's source
| `sql_table`, keyset-paged

| `catalog.describe`
| One table's columns, keys, indexes and both FK directions
| the `sql_` family, five queries in one read transaction

| `catalog.search`
| Which table holds something the author can only describe in words
| the same two census queries, composed into the embedding corpus

| `edges`
| What else touches this coordinate, and what breaks if it changes
| the binding relations, forward and reverse, no index

| `schema`
| What graphitron made of a type or field, and what it binds
| the claim, binding, backing and demand views

| `directives`
| The directive grammar this schema can use
| `graphql_directive` and its argument / location children

| `status`
| Is the dev session live, and is its answer current
| `store_graph` presence and the SDL refusal relations' emptiness; liveness is proved by answering

| `diagnostics`, `diagnostics.aggregate`
| What is broken, and in what proportion
| the `diagnostic` view already, plus the same refusal-relation read for the axes

| `services`, `conditions`, `records`
| Which consumer Java the schema binds to
| the `jvm_` census and the `java_` declaration family

| `docs.search`, `execute`
| The manual; the consumer's own database
| untouched, neither reads a generator projection
|===

The right-hand column is the whole claim, and every row now names a store read. What the scope
boundary still fences off is smaller than a row: the handle and reader the host mints, and the one
liveness bit no relation can carry, which a tool proves by answering at all.

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
`catalogFacts`' readers, so slice 4 deletes the projection. Slices 5 to 8 drain the rest, so slice 9
deletes the module edge. Within that constraint the order is by risk: the catalog tools first
because their queries are the plainest and they prove the fixture, `schema` late because it is the
largest, and the close-out last because it asserts what the others achieved.

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
concurrently mints a `StoreReader` instead", which stays true of the diagnostics tools and gains a
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

**Leaves behind.** `catalogFacts` keeps one reader, the edge tools.

## Slice 4: `edges`, and `CatalogFacts` deletes

This is the largest change by line count and the one that removes the most code. `EdgeProducer`
stops switching over classification permits and `ReverseEdgeIndex` stops existing; both directions
become queries over the binding relations, and `EdgeProducer.Context` carries a handle where it
carried two projections.

**Reads.** Seven queries, one per question the edge kinds ask:

* **What a field binds, forward**, keyed by `(type, field)` and returning the target's full key:
  `intent_column_match_claim` and `intent_field_column_table` for `BACKS`, `intent_bound_table` for
  `TARGETS`, `intent_field_reference_step_hop` for `REFERENCES` with its hops, and for `RESOLVES`
  the two reads the next bullet separates.
* **What method a field resolves to**, which is two populations and not one, and this is the place
  an earlier reading of this item lost half of it. `RESOLVES` is produced today from six
  classification arms. `intent_field_producer_method` answers four of them: `ServiceBacked`,
  `QueryService` and `MutationService` through its `SERVICE` arm, `Computed` through its
  `EXTERNAL_FIELD` arm. A fifth, `RoutineBacked`, needs no read at all, for the reason two
  paragraphs down. The sixth is `InputUnbound`, whose method pair is populated "when the carrier has an explicit
  `@condition`", and that view does not cover it: its own comment scopes it to "`@service` and
  `@externalField` resolved against `jvm_method`", and `declared_via` is a documented closed
  two-value vocabulary. So the condition population is a second query, over
  `graphitron_field_condition` joined to `jvm_method` and `jvm_method_parameter` for the arity.
* **What binds a target, reverse**: the same relations with the predicate on the other end, the
  columns of a given table, the methods of a given class, the types bound to a given table. This is
  the query that replaces `ReverseEdgeIndex` outright, and the reason the reverse direction stops
  needing a build step is that it was never a different question, only a different `WHERE`.
* **Which types participate in an abstract type**, for `PARTICIPATES`: `graphql_union_member` for a
  union, `graphql_implements` inverted for an interface, since it is stored in declaration
  direction. One query answering both, keyed by the abstract type's name. Its reverse direction is
  deliberately not indexed: type-to-type is cheaply walkable forward, which was true before this
  item and stays true after it.
* **Table-to-table FK edges** (`outgoingFkEdges` / `incomingFkEdges`) read the same
  `sql_referential_constraint` query slice 2 wrote, which is where the reverse FK direction was
  already a query rather than an index, and is the shape the rest of the tool converges on.

The condition read is an MCP query and deliberately not a third arm on
`intent_field_producer_method`, which is the escalation rule applied rather than dodged. A rule
graduates to a store view where it genuinely must be shared, and this one is not shared:
`DeclTarget.methodBackedTarget` switches five arms and `InputUnbound` is not among them, so no
language-server surface resolves a condition's method pair at all. Widening a two-value
`declared_via` to carry a population one consumer wants would put the MCP's requirement in the
model's vocabulary. What the query does is what that view does for its own two arms and what
`ClasspathMethods` does for arity, so nothing here is a new mechanism, and `depends-on` stays empty
because no store-model change is needed.

`RoutineBacked` is the arm that costs nothing, and it is worth stating why so an implementer does
not go looking for the routine's method in the store. Its target is a generated jOOQ
`Routines`-class method, `ClasspathScanner` excludes the generated jOOQ package by design, and
`resolvesMethod` "produces no edge rather than an invented arity" when the class was not scanned.
So the arm emits no `RESOLVES` edge today, the `jvm_` census excludes the same package for the same
reason, and `graphitron_routine` carries a `routine_ref` rather than a class and method. The
migration preserves an empty answer, which is different from a population being dropped, and
neither read has to reach for the routine.

**Wire.** Unchanged for `RESOLVES`, which is the point of the second query: both populations keep
emitting one edge per arity-distinct overload with no census row producing no edge, so the arity
fan-out and the silence both survive. Reading only `intent_field_producer_method` would have
dropped every `@condition`-carrying input field's edge silently, with no wire delta declared and
nothing in the tests to catch it.

The bare-name resolution goes, which is a fix rather than a translation. `EdgeProducer`
matches a bare name today because its input is a *classifier* table name, and `resolveTable`
degrades an ambiguous or unfound one to a `TableNode` with an empty schema. Reading the binding
relations instead means no edge endpoint is ever spelled without its schema, because none of those
relations carries a bare name to begin with. There is no resolution step left to degrade.

Ambiguity stops being a degradation too. `intent_bound_table` carries a `candidates` arity beside
the key, so an ambiguously bound type emits that many fully-keyed targets and the count is stated
rather than recounted downstream.

The census read `EdgeProducer.Context` needed for name resolution is not replaced by a smaller one.
It is not needed at all: the endpoints arrive keyed, so there is nothing to resolve them against.
`EdgesTool.selectNode` still resolves the *tool argument* against the census, which is slice 2's
query reused; what the context holds after this is the handle and the request's scope.

**Deletes.** `ReverseEdgeIndex` in full, including its `Cache` and the server's
`reverseEdgeIndexCache` field. That class iterates every coordinate in the schema, runs
`EdgeProducer.fieldEdges` per entry, inverts each result into a `HashMap<String, List<Edge>>`, and
is wrapped in a memo keyed on a two-reference pair whose javadoc explains how a torn read against a
non-atomic multi-field swap self-heals. All of it is a `GROUP BY` written in Java because the input
was a map. Against the store, "what binds this column" is a predicate on the target: the index goes,
the cache goes, the two-reference key goes, the torn-read paragraph goes, and the stated invariant
that the reverse direction must invert the same switch as the forward one so "the two directions
cannot disagree" goes with them, because forward and reverse stop being two code paths over one map
and become one relation read from either end.

The cache looked like the one memo worth keeping, its subject being a walk over every classified
field. It is not, because the walk is what this slice removes. A memo exists to avoid recomputing
something expensive, and an indexed predicate on a target key is not expensive; keeping the cache
would mean keeping the index, which would mean keeping the inversion, which is the code the
migration is for.

Also `EdgeProducer`'s two switches, its four permit-set constants and `resolveTable`; and
`EdgeCoverageTest`, whose subject is the partition those constants describe.

**And `CatalogFacts` deletes here**, this being its last reader: the type and `CatalogFactsTest`,
`CatalogBuilder.buildCatalogFacts` and its `toKey` helper, `GraphQLRewriteGenerator.BuildArtifacts`'
`catalogFacts` component and the two-argument convenience constructor that becomes the canonical one,
`Workspace.catalogFacts` (field, accessor, and its assignment in `setBuildOutput`), and `DevMojo`'s
threading of the value.

The convenience constructor goes because it cannot survive, not merely because it stops being
useful: its signature is `(CompletionData, LspSchemaSnapshot.Built.Current)`, which is exactly what
the canonical constructor becomes once the third component is dropped, so leaving it is a duplicate
signature and the module does not compile. That is worth knowing in the other direction too, because
it bounds the blast radius to less than a record-component deletion usually costs. Every existing
two-argument caller keeps compiling untouched against the new canonical constructor, which covers
every construction site outside this module: `WorkspaceTest` (five sites) and
`BuildTriggerPublishesDiagnosticsTest` in `graphitron-lsp`, and `CatalogRefreshTest` in
`graphitron-maven-plugin` are all already on that form, none of them passes the projection, and none
asserts on it. They are named here to record that they need no edit rather than to schedule one.

Five sites pass three arguments today and they split two ways. Two are main code and lose the
argument: `GraphQLRewriteGenerator`'s own construction of the record, whose third argument is the
`buildCatalogFacts` call being deleted with it, and `DevMojo`'s re-wrap of a prior catalog. The other
three are in this module's tests, `GraphitronMcpServerTest` twice and `ServerInstructionsTest` once,
and each passes a hand-built `CatalogFacts` fixture. Those are not edited either: they are the
fixtures slices 1 to 4 convert to `StoreBackedBuild`, so they are gone before this deletion lands
rather than trimmed by an argument. That ordering is the reason the deletion is a small commit at
all, and it is worth checking at pickup rather than assumed, since a fixture left behind by an
incomplete conversion turns the last slice's tidy deletion into a compile error in the middle of it.

That the deletion lands in the same commit as the last reader's
migration is this item's binding constraint, and it is satisfiable without
waiting on anything: no `graphitron-lsp` surface reads `catalogFacts()`, only the `Workspace` field
the MCP tools read through. Re-check that at pickup with a `catalogFacts()` grep, because a reader
that reappeared upstream changes the order.

The `JooqCatalog` walk that fed the projection does not retire with it: classification reads the
same catalog, and `CatalogBuilder.build` still runs. What goes is the second pass over it that
produced a consumer-shaped copy.

**Leaves behind.** `catalogFacts` has no reader left, which is what licenses the deletion above.
`Workspace` keeps four accessors read: `catalog` four, `sourceIndex` three, `snapshot` five,
`vocabulary` one.

## Slice 5: `services`, `conditions`, `records`

**Reads.** The `jvm_` census answers all three, and the split the tools perform stays a derivation
they perform rather than becoming a relation: `records` lists classes with `jvm_record_component`
rows, `conditions` lists methods whose `jvm_method.returns_condition` is set, and `services` lists
classes with callable `jvm_method` rows, condition methods included, since the same class is both.
That split is the tools' own and is stated in `CodeTools`' javadoc as such; nothing about it becomes
a store rule.

Method entries read `jvm_method` with `jvm_method_parameter` for the arity in the `fqcn#method/arity`
ref, and `declared_return_type` for the signature an author reads. Source locations join the `java_`
family (`java_class_declaration`, `java_method_declaration`, `java_field_declaration`) for the
`location` / `locationStatus` wire fields.

**Why here.** An earlier reading of this item deferred these three tools to a separate Backlog item
on the grounds that their acceptance surface is source locations and they share no query with the
catalog reads. Both facts are true and neither is a reason to defer: the goal is the module reading
only the store, and a tool left on `CompletionData` keeps a `Workspace` accessor alive, which keeps
the module edge alive, which is the thing the item is for. The separate item is discarded and its
scope absorbed here.

**Leaves behind.** `sourceIndex` has no reader left. `catalog` keeps one, `schema`'s `nodeMetadata`
read, which slice 6 takes. `snapshot` keeps its five (`schema`, `status`, both diagnostics tools,
the directives resource) and `vocabulary` its one.

## Slice 6: `schema`

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
* **What it binds**: table, column, class, method, join path, participants. Five of the six are the
  queries slice 4 wrote, read at the coordinate grain instead of as edges, the method slot included,
  which means it inherits both of that slice's producer reads rather than only the view: a field
  whose method comes from an explicit `@condition` reports it here for the same reason it emits an
  edge there. The sixth is the backing class, which is the next subsection.
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

**Leaves behind.** `catalog` has no reader left. `snapshot` keeps four: `status`, both diagnostics
tools' axes, and the directives resource, which reads it beside the bundled grammar. `vocabulary`
keeps its one. Only the directives resource stands between here and the lifecycle arms being all
that is left, which is why it is the next slice.

## Slice 7: the `directives` resource

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
is spelled entirely in method calls. Slice 9's import scan would have passed a module that still
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

**Leaves behind.** `vocabulary` has no reader left. `snapshot` keeps three, all of them the
lifecycle arms: `status` and the two diagnostics tools' axes.

## Slice 8: `status` and the diagnostics axes

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

## Slice 9: the edge deletes

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

The last `Workspace` reader is gone by slice 8, so the type is not a constructor parameter either:
`GraphitronMcpServer` takes its `StoreHandle` and its `StoreReader`, and
`DevMojo` stops passing it the workspace. That is what makes the import scan satisfiable rather than
merely the reads draining, and it is the last thing holding the `graphitron-lsp` import in the
module's main sources.

The `graphitron` side of the precondition is an audit rather than a count, and the slices above
discharge it in full. Every `no.sikt.graphitron.rewrite` import in the module's main sources today
is one of: `CatalogFacts` (slice 4), `CompletionData` and `SourceWalker` (slices 5 and 6),
`FieldClassification`, `TypeClassification` and `TypeBackingShape` (slices 4 and 6), `CatalogBuilder`,
`DirectiveShape` and `TypeShape` (slice 7), `LspSchemaSnapshot` and `RejectionKind` (slice 8). The
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
  capture writes on every pass; slice 8 carries the derivation and the scope boundary the argument.
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
  no edge at all and exist to be exhaustive.
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

That also settles what the edge tools cost at rest. Today the first reverse traversal after a build
pays a whole-schema walk; afterwards it is an indexed lookup per query, and the build pays nothing.
Every read this item adds is an indexed lookup on a captured or materialized relation, so no tool
pays a derivation at request time. That is worth stating because it was nearly not true: the
backing-class closure was specced here as a per-request recursion before it existed on the store
side, and the store side's own measurement of that shape is why it is materialized instead.

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
  with an empty schema whenever a bare classifier name is ambiguous or unfound, and the degraded
  node's `wireId()` has no qualifier to render, so `edges` answers today with a bare `film` where
  every other table node is `schema.film`. Both arms read keyed relations after this, so the degraded node has no producer
  left. An ambiguously bound type emits the candidates `intent_bound_table` carries, each a full
  key. This is the one wire change in this item that is a fix rather than a translation: an
  unqualified table ID is not a node any other tool can be handed back.
* **A join path's hops carry their constraint's full key.** `FkStep` holds a bare target table name
  and an FK name, and both come back schema-qualified from `intent_field_reference_step_hop`. The
  same argument as the bullet above, on the payload rather than on the endpoint.
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
  handle; the diagnostics tools already refuse per call there, on the grounds that an empty answer
  reads as a clean schema. An empty catalog reads as a database with no tables, so the catalog
  tools take the same posture. This is not the pre-capture case: a store with no rows yet is an
  answer, and absence of rows is absence of tables.

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

Two prose comments outside Java state the edge as a fact and go with it in slice 9, neither of them
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
your agent) off one warm workspace" stays true of the process and the sharing, and after slice 9 the
server holds no `Workspace` at all, taking a `StoreHandle` and a `StoreReader` instead. One warm
store is what the two surfaces then share, and the sentence says so with one word changed.

"Three kinds of context, all served off the same warm workspace the dev loop keeps current" is the
one to be careful with, because its *same* is not the LSP and the MCP but the `about` prompt, the
`directives` resource and the tools: the claim is that everything an agent gets comes from one live
source rather than from three of varying age. That claim survives and gets stronger. Today it is at
its least true, the tools reading a mix of frozen projections, the `directives` resource composing a
bundled half with a snapshot overlay, and the diagnostics tools already on the store. After this item
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
`ClassMemberSlots` seam closure, which slice 6 performs; that edge is unaffected by the widened
scope, since nothing here reintroduces a cross-consumer reader import.

A fourth is discarded rather than repointed: the Backlog item that planned the code tools' migration
to `jvm_` / `java_`, which slice 5 now performs. Its whole content was the substrate census that
slice cites, so nothing is lost by absorbing it, and a Backlog item describing work another item
owns is a plan nobody will pick up. Its file was already deleted while this spec was being drafted,
with the decision recorded here rather than left as a redirect, which is the workflow's `Discarded`
rather than its tombstone: the supersession is total and this spec captures it by reference. An
implementer has nothing to remove for it.

## Tests

The MCP module already has the fixture this needs: `StoreBackedBuild` runs a real
`GraphQLRewriteGenerator.buildOutput()` into a bootstrapped store and hands the server a handle,
which is how the diagnostics tools are tested. Every slice moves its tool's cases onto it and stops
hand-building projection fixtures, so the fixture work is front-loaded into slice 1 and amortised
across the rest.

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

**Slice 1 to 4, the catalog and edge tools.**

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
  Its subject is the agreement between `EdgeProducer`'s permit-set constants and the
  `FieldClassification` / `TypeClassification` permits, and one side of that is gone. The successor
  asserts the same
  property one level down, against the relations: every `EdgeKind` the wire declares is produced by
  one of the queries, and every binding relation the queries read feeds at least one `EdgeKind`. The
  second half is what catches a relation gaining an arm that the tool then never surfaces, which is
  the failure the permit partition was really guarding against. Both halves are assertable from
  `graphitron-mcp` without a store, being statements about the query set, so the case stays cheap.

  Those two halves are not enough on their own, and the reason is this item's own near-miss. Both
  are counted per `EdgeKind`, so a kind that keeps one of its producing populations and loses
  another passes both: `RESOLVES` stayed produced by the service arm while the `@condition`
  population was missing from the query list, and nothing in either half notices. So the successor
  carries a third assertion, one grain finer and written in the authored vocabulary rather than the
  permits': every directive that can name a method (`@service`, `@externalField`, `@condition`,
  `@routine`) maps to a named read or to a stated reason there is none, and the map is exhaustive
  over that list. Being a statement about SDL directives and the relations that capture them, it is
  not a second spelling of the classification taxonomy, which is what the last bullet in this
  section rules out and what the leaf-zoo guard would fail a test source for reintroducing. Two
  entries to check it against while writing it: `@condition`, which the kind-grain halves cannot see
  at all, and `@routine`, whose correct entry is the stated reason rather than a query.

  `PARTICIPATES` is the arm to check the first two halves against, being a declared `EdgeKind` whose
  relations an earlier reading of this item omitted from the query list entirely, so a successor that
  passes without covering it has been written to the queries rather than to the wire.

**Slice 5, the code tools.** Their existing cases keep their assertions and change their fixture:
the `services` / `conditions` / `records` blocks assert the same class refs, method refs and
`location` / `locationStatus` fields against a store captured from the test sources rather than
against a hand-built scan. Two cases are new because the store distinguishes what the scan did not:
a class the census never reached and a class it reached that declares no such method are separate
answers on `intent_field_producer_method`'s stated terms, and the `locationStatus` field is where
that distinction surfaces.

**Slice 6, `schema`.**

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

**Slice 7, the directives resource.** One case: the resource renders a bundled directive and a
user-declared one from one captured schema, with arguments and locations, and reports the
pre-capture case rather than degrading. The bundled / user-declared distinction is not asserted,
because after this item the resource does not draw one.

**Slice 8, `status` and the diagnostics axes.** Three cases, one per arm, driven through the
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
  The completion projection does not: `CatalogBuilder.buildColumn` hardcodes the empty string,
  because hover joins the LSP's source index for a column's Javadoc at request time and that shape
  never asked the database for anything. So `CatalogBuilderSourceTest`'s emptiness assertion is
  correct and stays, and the one thing that changes in `graphitron` is a stale parenthetical in
  `buildColumn`'s javadoc claiming a column comment is "not recoverable from the runtime catalog",
  which the sibling method in the same class recovers. An expectation of a comment there would have
  been a misreading of which projection carries what, and it was worth being wrong about once to
  find that out.

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
  assertion pinning `TableBacking`, the arm without members. Slice 6's backing-class cases are where
  the slots get asserted; the read is the same relation with the same ordering, so what they pin is
  that the entry still renders the slots, not that a new rule was introduced.
* Nothing in this item writes an agreement test between the permits and the relations. Two Java
  spellings of one taxonomy is what the permit partition was; a second one keyed on rows would be
  the same mistake with a store underneath it. The per-tool cases against a real capture are what
  pins the behaviour.
* Every query this item writes is tested from `graphitron-mcp`'s own fixture, which is the practical
  half of writing them here: a query lives in the module whose acceptance surface it serves, and is
  pinned by the tests that assert that surface.

**Slice 9, the guards.** Four, one per goal property, and each is the property restated as an
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
Slice 8 carries the derivation. What is genuinely process state after the correction is process
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

The pom edges are deleted in slice 9 with both halves of the guard, and that is a change from an
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
