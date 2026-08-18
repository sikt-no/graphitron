---
id: R642
title: "graphitron-mcp reads only the store"
status: In Review
bucket: architecture
priority: 2
theme: tooling
depends-on: []
created: 2026-08-12
last-updated: 2026-08-18
---

# graphitron-mcp reads only the store

`graphitron-mcp` answered thirteen tools, one resource and one prompt off four generator-side
projections it reached through the language server's `Workspace`. It now compiles against
`graphitron-model` and `org.jooq:jooq` and nothing else in the reactor, names no type from
`graphitron-lsp` or `graphitron` in any main source, reads no classification projection and no
`walk_` relation, and answers every tool from the fact store or from a value its host hands it. The
generator survives at test scope, where the fixture runs a real capture to produce the rows the
tests read.

Fewer tools came out than went in, and that was the item's second decision rather than a
consequence of the first. One tool was dropped instead of migrated and three collapsed into one,
for the reason "The surface shrinks first" gives below: a tool whose shape was dictated by reading a
projection is not a tool worth carrying onto a substrate that would have produced a different shape.
Ten tools, one resource and one prompt came out the far side.

The item took full ownership of the module's dependencies and deferred nothing to a successor: every
read that had to move, moved here. `CatalogFacts` deletes with its last reader, which the LSP
fact-store item (`lsp-reads-the-fact-store.md`) could not do from its own side while a non-LSP
consumer still read the type.

One doctrine binds the two modules and outlives both items: they read one shared store-side *base*,
never a narrowing made for one of them, which the `FactCapture.capture` javadoc states. The base is
the relation, and what each consumer writes over it is its own.

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
walk's own streams, so the rows a test asserts on have to come from a real pipeline run." The
alternative, a pre-built store shipped as a test resource,
would put a frozen artifact where the item deliberately put a live one. So the allowlist is written
per scope: compile is `graphitron-model` alone, test adds `graphitron` and `graphitron-sakila-db`
by name.

The split is not a loophole, because scope is exactly the distinction that matters here.
`graphitron-mcp` is *published*, and its pom comment records that a Maven plugin resolves its
declared dependencies from the consumer's repositories at execution time. Compile scope is what
ships and what a future reader can reach for; test scope reaches nothing a consumer receives and
constrains no production read. The guard fails on a `graphitron` import in main sources, which is
the property, and permits one in tests, which is the fixture.

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
`graphitron-mcp` reads none of them. Where this bites is the backing class, and the seven questions
below say how.

**No connection to the store of its own.** `graphitron-mcp` never opens a store, mints a connection,
or knows where the store directory is. The host does: `DevMojo` in `graphitron-maven-plugin` opens
`sessionStore`, and hands the server a `StoreHandle` over its DSL context. Everything this item adds
keeps that shape, the second host-minted value included: the `StoreReader` a multi-statement answer
goes through, which the host mints from `sessionStore.reader()` and passes in exactly as it already
does for the language server's `StoreAccess`, so a read never rides the session writer's connection.
The rule is worth stating out loud because a module that writes its own
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

## What each tool answers

The migration was tractable because the tools' intentions are narrower than the projections they read.
This table is the item's spine, one row per tool, and the item is done when every row's right-hand
column is true. Every one of them is.

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

| `diagnostics`, `diagnostics.aggregate`
| What is broken, and in what proportion
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

One row is absent that a reader of the module today would expect, and one row is new: `edges` is
dropped rather than migrated, and `services` / `conditions` / `records` arrive as the single `code`
row. The next section is why.

## The surface shrinks first

A migration that ports every tool assumes each tool's shape was a judgment about what an agent
needs. Some of them were not: they were judgments about what a projection made cheap to reach, and the
substrate that produced that constraint is the thing being removed. So the first question per tool is
whether to carry it, and the answer is not always yes.

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

**`services`, `conditions` and `records` become one `code` tool.** The three share one argument schema
(`nameLimitCursorSchema`), read one census, and differ by a `WHERE` clause each: a class with record
components, a method whose `returns_condition` is set, a class with callable methods. `CodeTools`'
own javadoc already records the split as the tools' derivation rather than a store rule. Three tool
descriptions, three handlers and three paged wire shapes for three predicates is surface without
information, and it is worth collapsing now rather than porting three times.

Nothing else is dropped, and one candidate was considered and kept, which is worth recording because
the reasoning that nearly removed it was wrong. `diagnostics.aggregate` looked like the same case as
`edges`: a second tool over the relation `diagnostics` already reads, whose justification is that the
first tool's page can run long, carrying its own dimension vocabulary, a two-bucket partition of it and
a coverage meta-test holding the partition together. What made it look droppable was a false claim,
that its grouping happens in Java over a projection. It does not. The aggregate is a SQL `GROUP BY`
over the `diagnostic` view with `HAVING`, ordering and the group limit pushed down, its fifteen
dimensions are columns of that view, and the item that built it deliberately never built a Java
grouping engine. So there is no migration to weigh: the tool is already where this item is trying to
get everything else, and the only projection it reads is the snapshot for the two freshness axes
`status` and `diagnostics` also read, which the lifecycle slice converts for all three at once. It
carries forward unchanged.

`docs.search` and `execute` read no generator projection and are untouched by this item at all.
`catalog.tables`, `catalog.describe`, `catalog.search`, `schema`, `status`, `diagnostics`, the
`directives` resource and the `about` prompt all answer a question no other tool answers, so they carry
forward too.

## The classification maps are questions, not a shape

This is why the migration was possible at all, and it is the reasoning the `schema` tool's new reads
rest on. There are three taxonomies, not one, and the module read all three.
`FieldClassification` has twenty-nine permits, `TypeClassification` twenty-two, and
`TypeBackingShape` five that fan out to eight leaf arms in a switch.
`EdgeProducer` switched the first two; `SchemaView` switched all three and rendered every arm
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
   `@routine` is no read at all, a routine-backed field resolving to no Java method. The landing
   section carries what went wrong here: the plan wrote all three relations as though one view spanned
   them, which is how a whole population went missing from a query list that looked complete.
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

Question 2 was the hard one for most of this item's life, and its answer moved to the store side. The
backing class had no relation stating it, only edges, with `intent_field_accessor_hop`'s own comment
inviting a reader to close over them. The closure is materialized now and the MCP reads rows, for the
measurement the landing section records. The generalisation survives the correction: a shadow relation
answers a question by transcribing what the code being replaced decided, so reading one buys an answer
at the price of keeping that code alive. `walk_type_backing_class` is still there and is still not what
this module reads. What changed is that the honest alternative stopped being "write the recursion
yourself" and became "read the relation that closure produced".

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

That also settles what the tools cost at rest, and the edge tools settled it by leaving: the first
reverse traversal after a build used to pay a whole-schema walk, and nothing pays one now because
nothing walks. Every read here is an indexed lookup on a captured or materialized relation, so no tool
pays a derivation at request time.

## Where it landed

Ten commits, one per tool, each taking that tool from its projection to its queries with every other
tool untouched. The tool was the acceptance surface throughout, so a slice's wire output was either
right or wrong and could land on trunk on its own.

[cols="1,2,4"]
|===
| Slice | Commit | What moved

| 1 | `fb5ed1b` | `catalog.tables` answers from the `sql_table` census, keyset-paged
| 2 | `1cf41a9` | `catalog.describe` answers from the census relations
| 3 | `5de78d5` | `catalog.search` composes its corpus from the census
| 4 | `119e869` | `edges` deletes, and `CatalogFacts` with it
| 5 | `b655ce3` | `catalog.describe` becomes one nested projection
| 6 | `c2a14c2` | `services` / `conditions` / `records` become one `code` tool
| 7 | `8de07e2` | `schema` answers five questions per coordinate off the store
| 8 | `df5e7ff` | the `directives` resource reads the captured vocabulary
| 9 | `bc245fe` | the lifecycle axes and the diagnostic kind read the store
| 10 | `7fcfb86` | the module's dependency edges close on the store
|===

All four goal properties hold and each is asserted as a test in `StoreClientBoundaryTest`. The
compile surface is `graphitron-model` and `org.jooq:jooq`; test scope names `graphitron` and
`graphitron-sakila-db`; nothing else in the reactor appears in any scope. Six artifacts left a
*published* module's compile and runtime classpath with the edge: `graphitron-lsp`,
`org.eclipse.lsp4j`, `jtreesitter`, `graphitron-tree-sitter-natives`, and `graphitron` with
`graphitron-javapoet` behind it. The reactor now builds `graphitron-mcp` before `graphitron-lsp`,
which is the absence of the edge rather than an argument about it.

Nine differences between what was planned and what shipped are worth a reviewer's attention. Six are
corrections the substrate forced, and they are the item's real findings.

**A `{@code}` mention needs no import, so four generator references outlived every slice that moved
one.** `TypeShape` hid inside the directives resource's SDL rendering, `RejectionKind` was an enum
rather than a projection and so did not read as a read, `McpWire.location` lost its last caller
silently, and `WarmState`'s javadoc defined its own arms by comparison to `LspSchemaSnapshot`. Each
was found by reading rather than by a guard, and the same species recurred outside the module after
the item was implemented: a self-review sweep found seven further javadoc citations of types slice 4
deleted, three of them in `graphitron`, where no module-scoped guard reaches. The lesson generalises
past this item, which is why the guards scan occurrences rather than imports.

**`intent_field_producer_method` does not span every producer population, and the plan read it as
though it did.** It carries two `declared_via` arms, `@service` and `@externalField`. A field whose
method comes from an explicit `@condition` is outside the view, so the condition population is a
second read over `graphitron_field_condition`, and `@routine` is no read at all. Reading only the
view would have dropped every `@condition`-carrying input field's method slot silently, with the
query list looking complete and the wire still emitting a kind. That failure mode does not announce
itself the way a missing relation does.

**The backing-class closure is materialized store-side, not recursed per request.** The plan specced
a recursion over `intent_field_accessor_hop` inside the `schema` read. Measured on an adversarial
census that form cost 369 seconds and returned nothing, H2 re-evaluating a recursive view once per
outer row. The closure is now written at capture cadence and the tool reads rows. Three populations
the closure does not carry are inherited rather than worked around, each stated in
`intent_type_backing_class`'s own comment: the walk's cardinality guard is not applied, the two-level
carrier fork is not applied, and a `@table`-bound type seeds nothing into the closure, which is why
the tool reads the coalescing view rather than the closure relation.

**Reading a deep derivation as a correlated `MULTISET` is a substrate trap, and the measurement is
now documented outside this item.** Two views cost twenty-four seconds correlated per field row and
under two seconds read once and paired on their key. That finding and the one-projection-per-grain
rule are in `docs/architecture/explanation/fact-model.adoc`, because they outlive this item and would
otherwise die with this file.

**A store does not cost a build.** The plan read `StoreBackedBuild` as the price of a store and
reached for a store-free seam in slice 3 on that basis. The generator is what that fixture pays for:
capturing directly through `FactCapture.capture` stands a census up in about 50 ms against the 400 ms
a build costs, so `graphitron-mcp` gained its own `StoreFixture` and the rule became by what the tool
reads rather than by which fixture exists.

**Anchoring on a real capture cost four fixture affordances, and each one closed a real gap.** A
hand-built projection can assert a comment, an ordinal, an index or an ambiguity by fiat; a capture
can only show what the source declares. So `init.sql` gained database comments on both grains and
`redundant_unique_key`, the fixture gained `describe_hub` / `describe_hub_leaf` for a table whose keys,
indexes and both foreign-key directions are non-empty at once, and `StoreBackedBuild`'s generated
package became a parameter so the two-schema census could answer an ambiguous spelling. The index
case is the one worth recording: the whole census declared one index and no test read the `indexes`
field, so an index read that forgot its correlation entirely returned that one index for every table
and passed the suite. The comments change reached further than the two wire slots it was asked for,
failing eight `graphitron-lsp` tests that had been asserting emptiness as a proxy for "nothing parsed
yet" and executing six documented comment-versus-Javadoc precedence sites that had never run with
both sources present.

Three further differences are decisions rather than discoveries. **The guards scan occurrences, not
imports**, because a fully-qualified reference and an import are the same coupling and nothing they
forbid has another reason to appear. **The dependency guard is an allowlist, and scoped**, because a
denylist naming `graphitron-lsp` would assert the history rather than the rule and would pass every
edge nobody has added yet. And **the `edges` slice was the largest in every earlier reading and
became the smallest**, the tool being dropped rather than migrated.

## Where the answers change

The tool output is the acceptance surface, so the deltas are named rather than discovered. Three of
them remove a surface rather than change one, and they lead because they are the largest:

* **The `edges` tool is gone.** No surviving tool answers "what else touches this coordinate, what
  breaks if I change it". `schema` answers the forward half of what it used to, at the coordinate
  grain and under different field names, and the reverse half has no replacement and no successor
  item. "The surface shrinks first" carries the argument.
* **`services`, `conditions` and `records` become one `code` tool** with a `kind` selector, carrying
  the same entry fields the three carried.
* **Four bindings the `schema` tool used to report go absent.** A composite `@nodeId` field's key
  columns, an interface's `@discriminate` column, a `@pivot`'s two columns, and a participant's
  cross-table column. Each is authored in the store's transcription family but has no derived view
  resolving it to a catalog column, so reporting it would mean this module re-implementing a model
  rule, and in the `@nodeId` case that rule is the catalog-primary-key fallback applying where
  `keyColumns` is omitted. The `schema` tool reports the slot absent for those coordinates. This is the
  item's one accepted loss of information, taken because the new substrate is where the rule belongs and
  a store view can restore each without a consumer change.
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
  stand in for both. This is the item's one breaking wire change, and "The classification maps are
  questions, not a shape" carries the argument: a permit name is a fact about the generator's internals
  that the wire had no business promising, and the alternative is holding ninety exhaustive arms inside
  `graphitron-mcp` forever to keep a label stable. The server instructions and the manual's tool table
  both named the old vocabulary and changed with it.
* **A type's declaration sites are plural.** `graphql_type_declaration` holds every site a type is
  declared or extended at; the projection reduced them to the canonical one, and the wire slot renames
  from `definitionLocation` to `declarations` because it is a list now. A type declared once answers
  identically, so the delta is visible only on an extended type, where it is a fix.
* **An abstract type's participants say which SDL mechanism declares them.** One
  `participantTypeNames` list becomes `unionMembers` on a union and `implementors` on an interface, the
  two being different mechanisms that no type carries both of.
* **An `@node` reports what the author wrote, not what the generator falls back to.** A `typeId`
  argument the author omitted is absent rather than the type's own name, and omitted `keyColumns` are
  absent rather than the bound table's primary key. Both fallbacks are derivations no view resolves, and
  reading them here would mean re-implementing a model rule in this module, which is the line the four
  unread bindings above sit on.
* **`schema` reports the snapshot's axes without gating on them.** The two fields rename to
  `snapshotAvailability` / `snapshotFreshness`, matching the diagnostics tools, and an `Unavailable`
  snapshot stops emptying the type list: the store holds what the parseable sources yielded, and the
  axes say how current that is rather than whether there is an answer.
* **The page carries the merged schema's whole declared type surface.** Built-in scalars drop, having no
  declaration site; the bundled directive grammar's own input types and enums do not, and on a small
  schema they can outnumber the author's types. The incumbent projection listed them too, so this is
  inherited rather than new. No reader-side mask is available: the recipe relation recording the graph's
  own schema inputs states in its own comment that it never joins the consumer read surface, and
  `intent_type_domain` membership would silently drop an author's orphan type, which is a type they would
  very much want to see. The fix is a captured fact about a source being bundled, not anything a reader
  can do.
* **A `schema` call with no store refuses instead of answering an empty schema**, on the same grounds as
  the catalog and diagnostics tools: an empty type list reads as a schema declaring nothing.
* **The `directives` resource is empty before the first capture** rather than degrading to the
  bundled grammar. Same posture as the catalog tools: a directive list missing the user's own
  declarations reads as a grammar that forbids them.
* **A directive entry says whether it is repeatable and what an omitted argument defaults to.**
  Neither is a new query; both are columns of the relations the resource already reads and neither
  survived the projection it replaces. An agent writing an application is the reader who needs them,
  which is the entire audience for a directive cheat-sheet.
* **Directives are listed by name, so the author's own land among graphitron's** rather than after
  them. The registry hands capture its definitions in parse order, which puts every bundled one
  first; a vocabulary in that order reads as two vocabularies, which is the distinction the resource
  stopped drawing.
* **`status` reports on the graph's captured facts, not on a build the process is holding.** Two
  answers change for a consumer. A first read that refused something reports `Built` / `Previous`
  where it reported `Unavailable`, the facts the parseable sources yielded being genuinely there. And
  a session that recorded compile diagnostics before any schema capture reports `Built` / `Current`
  rather than `Unavailable`, because the anchor is what availability reads and the freshness axis is
  the one about the schema. The four wire words are unchanged and so are the keys.
* **`status` on a server with no store handle omits the axes** and reports liveness alone, naming the
  missing handle in its text. The one store-backed surface that answers rather than refusing there,
  because the call answering is the liveness the caller asked for; `Unavailable` would state a fact
  about a store the server cannot read.
* **A missing handle refuses instead of answering empty.** The server can be built without a store
  handle; the diagnostics tools already refuse per call there, on the grounds that an empty answer
  reads as a clean schema. An empty catalog reads as a database with no tables, so the catalog
  tools take the same posture. This is not the pre-capture case: a store with no rows yet is an
  answer, and absence of rows is absence of tables.

  `catalog.search` changes arm rather than answer here, which is worth naming because it had a
  plausible-looking one already: a store-less server reported the warming degradation, telling an
  agent to retry on a server where retrying cannot help. Its corpus is the census after this item, so
  it refuses with the others. A store present and no embedder keeps the warming arm, that being an
  index with nothing to rank rather than nothing to rank over.

## How it is pinned

Every query is tested from `graphitron-mcp`'s own fixture against rows a real capture wrote, which is
the practical half of writing the queries here: a query lives in the module whose acceptance surface it
serves and is pinned by the tests that assert that surface. Two fixtures, chosen by what the tool reads
rather than by which one exists. `StoreFixture` opens an in-memory store and calls
`FactCapture.capture` directly, which is every case reading the census or another transcription family.
`StoreBackedBuild` drives `GraphQLRewriteGenerator.buildOutput()` and is for the cases whose rows come
from the walk's own streams, which is the diagnostics families and the conflict view's domain gate.
Neither can encode a state capture never produced, and that is the property that made retiring the
hand-built projections worth its fixture cost.

Four guards in `StoreClientBoundaryTest` restate the goal's four properties as assertions, written in
the close-out slice rather than up front because a guard that fails for eight slices is a guard someone
disables. The dependency guard has two halves that neither imply nor duplicate each other: a pom
assertion of set equality over the `no.sikt` declarations, which fails as loudly on a missing
declaration as on an added one, and an occurrence scan over both source trees. A guard that passes is
worth nothing until it has been seen to fail, and a scanner can pass vacuously two ways, by finding
nothing because there is nothing or because it walked the wrong tree. Both are closed: every scan
asserts a floor on the files it reached, and the walk is rooted at the repository anchor rather than the
working directory. Ten mutations then confirm each half fires, nine failing and the one planted in the
named `DevQueryExecutor` exclusion passing.

Seven further mutation checks pin the joins that can silently lie, each caught by the case written for
it and no other. Cutting the index correlation makes a described table report both indexes carrying both
indexes' columns. Dropping the classifier gate on the column witness makes a method-backed field report
a column binding; dropping the classifier equality from the authored-provenance join multiplies a
contested coordinate's claim list; dropping the null-field-name gate on the type-grain conflict makes a
field's violation surface as its parent type's. Dropping either half of the freshness `OR` fails only
its own refusal case, and dropping the graph filter on the anchor read makes an uncaptured graph name
answer `Built`.

Four arms are declared uncovered rather than faked, which is the honest end of anchoring on a real
capture. A class name declared in two files is `ambiguous` at the class grain and makes every method on
it ambiguous too, and neither is reachable from a compiled fixture, two files declaring one
fully-qualified name being malformed Java; the arm exists for the same reason the relation is keyed by
file and name. A parameter name being present would need `-parameters` on the fixture module and would
make the documented NULL arm unreachable instead, so the surprising arm is the one covered. A table
whose generated model has no record class reports `org.jooq.Record`, which the backing view drops, and
no table in the fixture catalog is recordless; manufacturing one means a codegen configuration change
for a single table, so the arm is left to the store's own tests of `sql_table.record_class_fqn`. And of
the directives read's three `ORDER BY` clauses only the directive-name one is observable, H2 returning
location rows in primary-key order and argument rows in insertion order, both agreeing with what the
clause asks for; the clauses stay, because an unordered SQL result is unordered by contract whatever
this substrate does today, and what cannot be pinned is left unpinned rather than pinned against a
coincidence.

Nothing here writes an agreement test between the classification permits and the relations. Two Java
spellings of one taxonomy is what the permit partition was, and a second one keyed on rows would be the
same mistake with a store underneath it.

`graphitron-mcp` runs 145 tests and the full reactor passes with `mvn install -Plocal-db`.

## Retired vocabulary

The three tool removals retire more names than any other change here, and a dropped tool's vocabulary
is the kind that survives in prose long after the code goes, so it leads.

The sweep has been run over this list and the tree is clean of every term below. It found seven sites
the list could not have caught, and the reason is worth carrying into the next sweep of this shape:
every one was a `{@code}` mention or a bare prose analogy, which needs no import and so is invisible to
the `{@link}` build gate, and three of the seven sat in `graphitron` rather than in `graphitron-mcp`,
outside the reach of any module-scoped guard. Two of the dead names, `EdgeCoverageTest` and
`ReverseEdgeIndex.Cache` as a thing another class is "like", were not on this list at all when the
sweep ran, which is the failure mode a vocabulary list has: it catches the names its author thought of
retiring. Repointing them is `dde4034`, and the list below now names them.

* `EdgesTool`, `Edge`, `EdgeKind` with all five of its constants, `NodeRef` and its six permits, and
  the whole vocabulary the tool taught: "the traversal layer", "a typed neighbour", "the direction
  query axis", "forward edges" and "the reverse direction" as things `graphitron-mcp` has, "the
  stable-ID grammar the edges tool walks", "an edge endpoint", and "impact analysis" as a capability
  this module currently offers. What survives is the stable-ID grammar itself, which the catalog,
  `schema` and `code` tools compose and accept; what retires is its framing as a graph the edges tool
  walks
* `EdgeCoverageTest` as the project's worked example of the no-silent-default coverage pattern, cited
  by four other classes as the shape they mirror. Its subject was the agreement between
  `EdgeProducer`'s permit-set constants and the permit space, and both sides went with the tool, so it
  has no successor. `VariantCoverageTest` is the live exemplar those citations now name. `EdgeKind` as
  the reference case for "an enum of labels, not a sealed hierarchy" goes the same way, and
  `ReverseEdgeIndex`'s cache as the thing a per-server instance is *like*
* `BACKS`, `TARGETS`, `REFERENCES`, `RESOLVES` and `PARTICIPATES` as names for what a coordinate does
  to a table, column or method, in prose as well as in code. Anything reading a binding after this
  item names the authored directive that made it, the store's classifier, or the relation itself
* `services`, `conditions` and `records` as three tool names, and `CodeTools`' three-result framing
  with them; "the conditions tool is the condition-filtered view" retires as a cross-reference between
  tools and returns as a sentence about one tool's `kind` argument
* `SourceJoin` with its `Resolved` / `NotIndexed` / `Ambiguous` permits, `McpWire.joinMethod`,
  `joinClass` and `writeLocation`, and "the source index" as a thing this module joins against. What
  replaces the type has four arms where it had three, so its own name would have been a lie about the
  outcome space; and the phrase "an un-rewalked `.java`" stops covering every absent location, that
  being one of two absences the family reports and the only one a re-walk changes
* "the classes the schema wires to as services" as what a `jvm_` predicate answers. Whether a class is
  wired to is a classification fact and this census holds none; the predicate answers which classes
  declare callable methods, which every record does too
* `displayType` as a wire field name, and per-field choices between a type's erased and declared forms.
  Every type the `code` tool emits is the declared form, stated once
* `ParamSource` on a method's parameter as something a discovery read can report. It is decided per
  directive application, so the relation deliberately carries no column for it, and the field it fed
  never reached the wire at all
* `SchemaView.mapTypeClassification`, `mapFieldClassification`, `mapClaim` and `mapBackingShape`, and
  with them "the classification kind" as something the `schema` wire reports. An entry names the
  classifier that claims a coordinate, which is the store's vocabulary about the author's schema; a
  permit name was the generator's vocabulary about itself. "The backing shape" retires as a wire concept
  too: its table half and its class half are two questions answered directly, and there was never a
  shape between them
* `Unresolvable` and `Unclassified` as the way a coordinate reports that graphitron read no intent from
  it. Both said a verdict was missing without saying whether one was expected, which is the distinction
  the demand vocabulary carries and neither of them could
* `participantTypeNames` as one list covering both abstract-type mechanisms, and `definitionLocation` as
  a type's single site
* `availability` and `freshness` as `schema`'s own top-level keys. They are the snapshot's two axes, the
  same two every other tool reports under `snapshot`-prefixed names, and they never described the
  answer's own availability
* `CodeQueries.Position` as a type the `code` reader owns. A store-read source position is one wire
  shape reached from one kind of triple, so it sits in `McpWire`, which is where every reader composes
  it. Written first as sitting "beside the projection-fed spelling", and that spelling
  (`McpWire.location`, over the generator's own location type) is retired vocabulary too: its last
  caller left with the projection readers and the position conversion is one, not a pair
* "several queries in one read transaction" as the shape of an answer, with the multi-query tearing
  argument that justified it, the `FkId` grouping key, the `Keys` pair record, and "folding the rows"
  as a step a reader has. What replaces all of it is one nested projection. The reader's justification
  narrows rather than retiring: a read must not ride the writer's connection, and two statements are
  still two statements
* two parallel column lists as a foreign key's shape, and "paired by position" as something a record's
  javadoc has to promise about two of its own fields. A `List<ColumnPair>` holds the pairing the query
  guarantees, and the wire's two arrays are its transposition rather than its source
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
* `DirectiveShape.locations` and `CatalogBuilder.projectDirectiveLocations`, with "the widened
  `DirectiveShape`" as prose for how a directive's applicable locations reach a reader, and the
  three-argument `DirectiveShape` constructor as a "back-compat" form; three arguments is the whole
  record now, and locations are read from `graphql_directive_location`
* "a `graphitron` type on a dependency the module legitimately keeps" as a reason anything is
  reachable from `graphitron-mcp`'s main sources. Main sources keep no such dependency; the phrase
  survives only for `graphitron-model`'s relations, which is where the `walk_` guard uses it
* the pom comment's justification of the compile edge on `graphitron-lsp`, with "the server now
  holds the live Workspace" as the reason for it and the acyclicity argument that made it safe;
  there is no edge left for either to be about
* "the snapshot" as the subject of the two axes, in every spelling that made them a value's arms
  rather than a partition's state: "the live snapshot's availability", "reflects the live `Workspace`
  snapshot state", and "no successful build yet" as what `Unavailable` reports. The keys keep their
  `snapshot`-prefixed names, the wire being unchanged, and what they report is a graph's anchor and
  its refusal rows
* `WarmState`'s framing of its own arms as "mirroring the exhaustive `LspSchemaSnapshot` switch
  posture". A cross-module comparison in a `{@code}` mention, which is how it outlived every slice
  that moved an import
* "Dev-loop status (ports, warm state)" as the manual's description of the `status` tool. It named
  two things the result never carried, and the two it does carry are now the whole of the entry
* `Workspace` as a `GraphitronMcpServer` constructor parameter, in all five of its overloads, and
  with it "the live workspace the tools read off" as prose about what the host passes. The server
  takes an address, the RAG wiring, an `execute` configuration, a `StoreHandle` and a `StoreReader`,
  and nothing on that list is a fact about a graph
* `RagConfig`'s definition of itself as "the glue the RAG indices need but the `Workspace` does not
  carry", and `DocsRag`'s account of warm ownership as "mirroring how the live `Workspace` is
  threaded in". Both defined a live thing by contrast or analogy with a type the module no longer
  has, which is how they outlived every slice that moved a read
* `pagedWorkspace`, `StoreBackedBuild.workspace`, and a hand-built projection as a fixture shape
  anywhere in this module's tests. What a case needs is a capture, so the fixture runs the pipeline
  and reads rows back; a projection assembled in a test could assert a state no build produces

## Scope boundary

Nothing generator-side is read through the language server, and no generator projection is read at all.
Nothing is read as a live value rather than as rows either: the host hands the server its `StoreHandle`
and its `StoreReader`, and everything past the connection is a query. What remains process state is
process liveness, which a tool proves by answering, and capture in-flightness, which has a crash-shaped
failure mode, which no tool's answer depends on beyond "may refresh shortly", and which therefore
nobody stores and nobody hands in.

Earlier readings left four things outside that boundary and none survived contact with the goal: the
code tools, on the argument that their acceptance surface differs; the pom edge, on the argument that a
successor would delete it; the backing class, on the argument that its relation was unbuilt; and the
dev-session lifecycle, on the argument that a graph whose newest parse just failed and a graph nobody
has edited are the same rows. That last one is the instructive failure, because both its premises
describe the projection pipeline, where a snapshot object is minted only on success, and neither
survives the verdict stratum: `graphql_syntax_error` and `graphql_schema_error` are written by capture
on every pass on either outcome, so the freshly broken graph holds refusal rows the untouched one does
not, and "the last good facts are being held" is not a possession but what the transcription families do
per source.

`depends-on` stays empty and should remain so. Three items lean on this one, which is the coupling
running the right way: `consumers-share-relations-not-queries.md` for the reader-import seam,
`fact-store-test-harness-consolidation.md` for the settled `StoreBackedBuild` it lifts onto the shared
harness home, and the LSP fact-store item's retirement sweep for `CatalogFacts`, whose term survives
that item's own diff until the fourth slice landed here. Whoever takes the LSP item to Done should read
that as a sequencing fact rather than a failed sweep.

Two findings from this item are durable and deliberately do not live here, having been written into
`docs/architecture/explanation/fact-model.adoc` instead: one projection per grain as the shape of a
consumer's answer, and the read-once rule for a derived view carrying a window function or a recursive
term. Both are properties of reading this store rather than facts about this module, and this file is
deleted at the Done gate.
