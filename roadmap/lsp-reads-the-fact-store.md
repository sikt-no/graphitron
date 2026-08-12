---
id: R638
title: "The LSP reads the fact store instead of the catalog projection seam"
status: Spec
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
---

# The LSP reads the fact store instead of the catalog projection seam

The language server answers hover, completion, go-to-definition, inlay hints, diagnostics and code
actions out of `CompletionData`, a pre-baked in-memory projection of four fixed collections built
per pipeline round. That shape bounds what the LSP can offer: it can only ask what someone
projected in advance, its lookups are linear scans over lists (`getTable` streams and filters),
and the projection accretes a field whenever a consumer needs something new, which its own
backwards-compatible three-argument constructor records. The fact store holds the same knowledge
as queryable relations carrying source line and column, so hover and hints could ask questions
nobody pre-projected: which columns of a table sibling fields already claim, what the foreign-key
graph reaches from here, what the claim strata resolved and why. The store is also the one place
where a new fact family becomes available to every reader at once, and the language server is
currently the reader that cannot see it: it imports no store relation at all, while the generator
module reads sixteen and the MCP server two.

Scoping this turned up the sharper version of the problem. The jOOQ catalog is projected three
times, not twice. The `sql_` family holds it as relations. `CompletionData` holds it again,
Java-name-centric, for the LSP. `CatalogFacts` holds it a third time, SQL-name-centric, and it is
not a small surface: `EdgeProducer`, `EdgesTool`, `ReverseEdgeIndex`, `NodeRef`,
`CatalogDescriptors` and `CatalogSearchIndex` read it in `graphitron-mcp`, and `TenantScopes` and
`GraphQLRewriteGenerator` read it inside `graphitron` itself. Its own javadoc names the situation:
"a sibling projection to `CompletionData`, not a widening of it ... each carries exactly what its
consumer reads." Both Java projections are built in one `CatalogBuilder` pass over a jOOQ
reflection scope that closes at pass end, which is why each is frozen into resolved immutable
values. The store has no such boundary.

So the endpoint is not "the LSP gets a second way to read the catalog." It is one relational
catalog read surface with two projections deleted. This item builds that surface and cuts the
first two readers over to it.

## The item is the read surface, not a feature port

The shape that matters is shared, and building it underneath a feature port would mean paying for
it while three LSP-specific questions (per-keystroke latency, a second connection, the freshness
seal) are live at the same time. So the item lands the surface first and cuts over in two steps.

**Step 1, `CatalogFacts`.** SQL-name-centric so it is the closer shape match to `sql_`, frozen
values only, no live handles, and its consumers are turn-based. It exercises the read path with
concurrency, freshness and rendering all held out. It ends with a projection deleted.

**Step 2, the catalog-backed arms of hover.** Inherits the surface and owns exactly the three
questions step 1 could not answer.

The numbering is a real seam: step 1 ships and is observable on its own, and step 2's risk profile
only becomes assessable once the surface exists.

**Step 2's subject is open, and the reviewer should settle it.** Hover's catalog arms are the
cleanest *measurement*, because they A/B against a seam implementation doing the identical job.
`IntraSchemaDefinitions`' snapshot fallback is the cleanest *demonstration*, for the reason the
two-scopes section below gives: it is pure SDL provenance with source positions and no
classification, it is 107 lines, and the projection it replaces is a flat map that structurally
cannot represent a type assembled from several files. Picking it makes step 2 a capability gain
rather than a re-plumbing, at the cost of a weaker comparison, since there is no equivalent
incumbent to measure against for the cases the map could not express.

## The read surface

**Graph-scoped, structurally.** The `sql_` family is keyed by `source_name`, not by graph, and a
persisted store is shared across every module of a workspace. `CompletionData` is implicitly
graph-scoped because it is built per pipeline pass for one module; a store-backed `getTable` is
not, and would silently range over a sibling module's tables. `store_graph_source`'s own comment
states the law: any derivation joining a graph-keyed fact to a source-keyed one "scopes its
catalog side through this relation, which is what keeps one graph's resolution from seeing a
sibling module's tables in a shared store."

Every catalog read in this item goes through a graph-scoped view joining `sql_table` to
`store_graph_source` on `source_name`, and the handle a consumer receives is `(DSLContext,
graphName)` — the shape `GraphitronMcpServer.StoreHandle` already is — never a bare `DSLContext`
or `Connection`. Carrying the graph name in the handle is what makes the scoping structural
rather than a discipline every query site has to remember. Covering it costs a seeded fixture, not
a two-module build: `ColumnMatchClaimTest.siblingGraphsResolveThroughTheirOwnMembership` already
stands up two graphs in one store with the same unqualified table name in each graph's own source,
by inserting `store_graph` / `store_source` / `store_graph_source` / `sql_table` rows directly, and
asserts each resolution names its own catalog partition. The crawlers are tested where they are;
a read-side scoping test seeds the rows it needs.

This needs no new facts, and specifically not a transcription of the configured jOOQ package.
`GraphSourceMembership.note` already fires at the three places a run enumerates its sources, one
of them being the catalog walk's generated packages, and its javadoc names this exact job: the
relation "is what makes an SDL-to-catalog join determinate in a shared store." The membership row
records the package the walk *read*, not the one the build was configured with, which is the
stronger fact of the two and the one this scoping wants. Deriving the scope from configuration
instead would reproduce the store-first shape the schema-recipe item already rejected on the
record, where a transcription bug becomes a build that consistently reads the wrong thing past an
anchor that still passes.

**Sealed resolution outcomes.** Every seam read collapses {no match, one match, several matches}
into `Optional`-or-first-wins: `getTable` takes `findFirst` on a case-insensitive name;
`methodHover` takes `findFirst` over same-named methods; `fkHover` scans every table's references.
On a `CompletionData` list that flattening is nearly invisible. On the store it is unavoidable,
because the keys are honest: `sql_table` is keyed `(source_name, table_schema, table_name)`, so an
unqualified name genuinely may match several rows, and `jvm_method` is keyed by `descriptor`
precisely because the erased display rendering collided on overloads, which
`CompletionData.Method.descriptor`'s javadoc already documents.

Resolution returns a sealed outcome per lookup axis. `CatalogFacts.resolve` is the in-tree
exemplar and already returns `Resolved` / `Ambiguous` / `NotFound` over exactly this question;
the surface generalizes that to columns, methods and foreign keys rather than reproducing
`findFirst` on the store side to satisfy a parity gate. Re-flattening a distinction the store's
keys just handed back is the one outcome to avoid.

## Two scopes: the current file, and the universe

Tree-sitter parses buffers. The store holds every schema file of every graph the workspace ever
captured, the whole catalog and the whole classpath. Any question whose answer lives outside the
file under the cursor is a store question, and the LSP already knows this: `IntraSchemaDefinitions`
scans open buffers first, then falls back to the snapshot's `typeDefinitionLocations()`, which in
its own javadoc "covers every type in every schema file regardless of which buffers are open." The
buffer stays authoritative where it has an answer, because a type being edited should resolve to its
live span rather than its last-built position. Everywhere else the universe answers.

That fallback is also the sharpest example of what a projection costs. `typeDefinitionLocations` is
a `Map<String, SourceLocation>`: one location per type name. `graphql_type_declaration` is keyed
`(graph_name, type_name, source_name, source_line, source_column)` and carries `merge_ordinal` and
`is_extension`, because, as its comment says, "a type's effective shape may be assembled from
several files; this relation records who contributed what and indexes the incremental-refresh unit
('which types does this file touch')." A type declared once and extended twice is three rows and one
map entry. The map cannot say a type has several declaration sites, cannot say which file
contributed what, and cannot answer the reverse query at all, and `graphql_duplicate_declaration`
exists as a separate relation for the case the map collapses. This is not a fact the store holds
and the projection lacks; it is a fact the projection's *shape* cannot represent.

It is worth being explicit that this is the flexibility argument that motivated the item, now with a
mechanism under it. A projection can only carry what one build projected for one graph at the grain
someone chose in advance. The store carries every graph at the grain the facts have.

## What the store knows during a mid-edit session

The candidates a graphitron directive completes against do not come from the SDL. Table and column
names come from the jOOQ generated model; class names, method names and scalar constants come from
the classpath. Whether the schema file currently parses has no bearing on whether `film` is still a
table. Those facts are source-keyed in the store (`sql_`, `jvm_`), refreshed on the classpath
cadence, and a query against them carries no dependency on the SDL at all.

Nor is "the SDL" one thing with one validity state. A graph is many schema files. When an author
opens a new one and types `extend type |`, that file is not valid SDL and will not be until the
line is finished, but every *other* schema file is well formed, unchanged, and already captured.
The completion wanted there is the set of type names declared in those other files, which is
`graphql_type` and `graphql_type_declaration`, sitting in the store and correct. The invalid buffer
is not an obstacle to answering; it is the question.

So the honest split is three ways, by *which file* a fact came from rather than by source kind
alone:

* Catalog and classpath facts, untouched by schema editing at all.
* SDL facts from files not being edited, captured at the last successful parse and still correct,
  because those files have not changed since.
* SDL facts from the buffer under the cursor, which are the only genuinely stale ones, and exactly
  the ones tree-sitter reads live.

There is no gap between those three. The store is authoritative for the whole workspace except the
buffers being edited, and tree-sitter covers precisely those. That is the whole division, and it is
why a broken file is a local condition rather than an outage.

The current design does not have that granularity. Capture takes a whole `TypeDefinitionRegistry`,
so one unparseable file means capture does not run, and `demoteSnapshot` marks the *entire*
snapshot `Built.Previous`. Typing `extend type ` in a new empty file therefore makes the whole
workspace's type knowledge read as stale to freshness-aware consumers, when the only thing nobody
knows about is the empty file, which was never saved and which the store was never claiming to
know. Validity is per file; the seal is per workspace.

Worth noting that the store's relations already have the granularity the capture path lacks.
`graphql_type_declaration` carries `source_name` and, in its own comment, "indexes the
incremental-refresh unit ('which types does this file touch')", and `store_source` stamps each
schema file separately. Per-file refresh is a shape the schema was designed for and the capture
path has not yet taken; it is named in the follow-on entries rather than built here. What this item
needs from it is only the part already true: capture's transaction leaves "the previous committed
state" on a failed run, so the other files' rows are intact.

`CompletionData` conflates the two. It carries `tables` and `externalReferences` (catalog and
classpath) beside `types` and `nodeMetadata` (SDL), in one record swapped as a unit by
`setBuildOutput`, and rebuilt only by a codegen pass that also parses the schema. So a schema parse
failure takes down a catalog rebuild that had nothing to do with the schema, and the LSP carries a
hand-written workaround for it: `DevMojo.rebuildCatalog` catches the failure and, in its own words,
keeps "the previous catalog so completions do not silently disappear" while demoting the snapshot
"so freshness-aware consumers silence themselves."

That pairing is the right behaviour reached by hand. Under source-keyed relations it is structural:
catalog candidates are not *retained* across a bad parse, they were never invalidated by it, because
nothing about them was derived from the SDL. The store is therefore *more* available mid-edit than
the projection it replaces, not less. An earlier draft of this item had that backwards, treating
"the store reflects the last successful capture" as a limitation to work around. It is not a
limitation of the store at all; it is a limitation of one buffer, and tree-sitter is already
pointed at it.

The consequence for the plan: the sealed resolution outcomes must not give one availability answer
for the whole workspace. A catalog lookup that finds nothing found nothing. An SDL lookup that finds
nothing found nothing *unless* the coordinate belongs to a file currently dirty, which the LSP knows
and the store cannot. Which file a fact would have come from is therefore part of the outcome, not
an afterthought, and it is the axis a whole-snapshot seal collapses.

## What tree-sitter is for

The division of labour, stated because it decides the surface's shape and an earlier draft of this
item had it wrong. Tree-sitter answers *where the cursor is*: buffer position to schema coordinate,
over the live and possibly unparseable buffer. It produces no fact about the schema and writes
nothing to the store, and it could not, since it is an LSP-only dependency that capture has no
reference to. Facts arrive on the save cadence, through graphql-java, which is what capture walks.
The parse exists so the server can run the *right query*; the store answers it.

That is a sharper boundary than "parsing stays because the store cannot answer mid-edit", and it
corrects a scoping claim. The `parsing` package is not uniformly positional. `Nodes`, `Positions`,
`Directives`, `SchemaCoordinate`, `DeclarationKind`, `TypeNames`, `NestedArgs`, `ArgMapping`,
`SdlDeclaration`, `GraphqlNodeKind`, `GraphqlLanguage` and `LspVocabulary`'s coordinate machinery
are; they stay untouched. `DeclTarget`, `TypeContext`, `ArgMappingSupport` and part of `Behavior`
join a resolved coordinate against facts, and that join is precisely what moves. They belong in the
migration surface, not outside it as the earlier scoping said.

The division also fixes the interface's keys. An earlier draft had the surface take strings
(`table(String name)`, `column(String tableName, String columnName)`), which is the projection's
shape rather than the parse's. The right shape is already in the tree: `DeclTarget` is a sealed
resolution from an SDL declaration coordinate to what it binds to, shared by hover and
goto-definition so their parity is structural rather than asserted. What it gets wrong for this
purpose is that its variants carry `CompletionData.Table` and `CompletionData.Column`, so the
projection is baked into the coordinate. Variants carrying the coordinate instead
(`CatalogTable(tableName)`, `CatalogColumn(tableName, columnName)`) leave every consumer free to
ask the store, keep the shared-resolution parity that made `DeclTarget` worth having, and give the
read surface keys the parse actually produces.

**One connection for reading, and what it does and does not buy.** `GraphitronModelStore` holds
one `Connection` and one `DSLContext` over it. `DevMojo` hands that same `dsl()` to the MCP
server, safe there because MCP is turn-based; the LSP is not, and would put concurrent request
threads on a connection a build thread writes through. Add a read-only accessor opening an
additional connection on the same URL. Both URL shapes admit one: the in-memory store is a named
database held open by `DB_CLOSE_DELAY=-1`, the file store is `AUTO_SERVER=TRUE`.

Capture runs as a single transaction (`FactCapture`'s `dsl.transaction`), so a reader on its own
connection sees the previous committed state until that transaction commits, never a half-written
round. That is read-consistency, and it is the invariant `Workspace` hand-rolls today by swapping
`catalog`, `catalogFacts` and `snapshot` together so "a single set of volatile reads observes one"
build. It is worth having and it is only that. An H2 isolation default is not an enforcer: nothing
in this build fails if it changes, so the Spec claims read-consistency from it and nothing else.

**Freshness is provenance the store does not capture yet, and it governs one half only.**
`LspSchemaSnapshot`'s `Built.Current` / `Built.Previous` gets no store equivalent in this item, and
the reason is a missing fact rather than a boundary worth keeping. Capture records the last
*success*; `Built.Previous` is a claim about the last *attempt*, and `Workspace.demoteSnapshot`
fires from three `DevMojo` paths where a parse threw, so capture never ran and wrote nothing. No
timestamp comparison at the read site recovers that, and inventing one would be branching on a
predicate over pre-resolved inputs rather than reading a resolved decision.

Note what the seal is a fact *about*: the SDL half. A parse failure says nothing about the catalog
or the classpath, so demoting a catalog answer on one would be reporting staleness that does not
exist. The seal governs SDL-sourced facts and only those, which the source-keying makes structural
rather than a rule a query site has to remember.

The honest reading is that "which round produced these rows, and did the latest attempt succeed" is
provenance, and it is hand-maintained workspace state today only because nothing captures it. A
round-outcome fact would retire `demoteSnapshot` and the volatile field behind it. That fact is not
in this item because this item's readers do not need it, but it is the shape the seal should
eventually take, not a thing the workspace is right to keep owning. Until then the workspace keeps
answering *how fresh* and the store answers *what*, and the split is a gap on the record rather
than a design.

## Capture widenings

Widen capture wherever the store is missing a fact a reader needs. A render that would otherwise
have to change is evidence of exactly that, never a reason to change the render.

* **`sql_constraint.jooq_name`, yes.** `JooqCatalog.fkJavaConstantName` resolves the `Keys`
  constant by reference identity over the generated class's fields. That is a reflective decode of
  an external artifact, not a formula, so re-deriving it downstream would mean reflection past the
  decode boundary. It also matches the existing precedent exactly: `sql_table.jooq_name` and
  `sql_column.jooq_name` each already sit in a SQL-named family with a comment owning why. The
  foreign key is the odd one out, not a new precedent. Bonus worth stating: `CatalogBuilder`
  currently mints the `Keys` FQN as the formula `ctx.jooqPackage() + ".Keys"` while `JooqCatalog`
  derives the schema-correct one from the matching constant's declaring class. Capture retires the
  formula.
* **The generated class FQNs, yes, but decide the grain from the concept.** `Table.classFqn` is
  per table and can be a column on `sql_table`. The `Keys` class FQN is per
  `(source_name, table_schema)`; today it is copied onto every `CompletionData.Reference`, which
  is a repeating group. Follow the grain, not `CompletionData`'s shape, or the store inherits the
  projection's denormalization. `classFqn` is load-bearing either way: it is the join key into
  `SourceWalker.Index`.
* **The jOOQ binding type on `sql_column`, yes.** `ColumnFacts` calls itself "the
  resolved-immutable superset of `ColumnEntry`" and is not one: both read the same live
  `org.jooq.Field` inside the codegen scope, `ColumnEntry` taking `col.getType().getName()` (the
  Java type jOOQ binds the column to) and `ColumnFacts` taking `col.getDataType().getTypeName()`
  (the SQL type) and dropping the other. Capture writes `ColumnFacts`, so the binding type reaches
  no relation. A column has both a SQL type and a binding type; they are orthogonal axes, not two
  renderings of one fact, and the binding type is what determines the Java type flowing through
  generated code, which is the more useful of the two to an author writing a binding. It is also
  unrecoverable downstream: after the codegen loader closes there is no handle left to ask. Capture
  it, and correct `ColumnFacts`'s javadoc, which currently claims a containment that does not hold.
* **`jvm_class` filters.** The family is filtered to public, non-synthetic, top-level, outside the
  generated jOOQ package. Confirm `CompletionData.ExternalReference` agrees on all four rather
  than assuming; a disagreement is a finding either way.

## No behaviour changes

Nothing an author sees changes in either step. Where a render looks like it must change, the cause
is a fact capture is missing, and the fix is to capture it. An earlier draft of this item proposed
moving hover and `FieldCompletions` from the jOOQ binding type to the SQL type and calling it a
named behaviour change; that was proposing to lose a fact rather than capture one, and the widening
above retires it.

`CompletionData.Column.graphqlType` is misnamed all the same. It is neither a GraphQL type nor the
SQL type, and it is rendered verbatim in three places, twice in hover and once in
`FieldCompletions`. The port renames it to what it holds; the value is unchanged, so no reader
moves.

The mislabeling is worth one more sentence, because it is the mechanism by which a fact goes
missing. A name that does not say which axis a value is on is what lets a second projection take
the other axis from the same handle and call itself a superset. Both `CompletionData` and
`CatalogFacts` did exactly that, in opposite directions, and neither is wrong on its own; what was
missing was a relation carrying both.

One genuine correction is not a behaviour change but a keying fix. `columnGraphqlType` resolves a
`@node(keyColumns:)` entry by scanning every table for a matching column name, keyed on nothing, so
its answer depends on iteration order. The node type's resolved table is a fact the store supplies,
so the store-backed version keys on `(node table, column)`. Where that changes an answer, today's
answer was arbitrary.

## Tests

**Shadow the resolution, not the render.** Split each ported feature into *resolve* (coordinate to
typed fact tuple) and *render* (tuple to output), and shadow the resolve half only. The renderer is
untouched, so its output stays byte-equal by construction and needs no test of its own. This keeps
the gate off rendered markdown, which would otherwise conflate which fact was resolved, what it
says, and how it is spelled, and would pin two things worth un-pinning: `methodHover`'s arbitrary
overload pick, and `columnGraphqlType`'s order-dependent first-match, whose answer depends on a row
order the store does not share.

**Population diffs with named residues.** `DemandShadowTest` is the shipped shape: diff populations
of rows against the incumbent Java derivation, with each residue pinned as a store-derived
population rather than a Java-side list. The shadow test asserts the `Resolved` arm equals the
incumbent and names the `Ambiguous` population as a residue rather than pinning today's pick.

**Tier.** With the resolve half living next to `CompletionData` in `graphitron`, the shadow tests
are pipeline-tier alongside `DemandShadowTest` and never need the LSP in the loop. Step 2 adds
LSP-tier coverage only for what is genuinely LSP-shaped. `RejectionSeverityCoverageTest` already
opens a `GraphitronModelStore` in that module, so the tier can boot one; the capture-side helper
`CapturedStore` is package-private in `graphitron`'s test sources, so either widen it deliberately
or write the local equivalent, leaving one helper rather than two.

**Latency, step 2 only.** Measure per-request hover latency for the ported arms, store-backed
against seam-backed, on the Sakila fixture, and state the number before cutover. The expectation
that a map lookup beats SQL is not obviously true here: the incumbent is a linear scan and two of
the arms are nested linear scans. That is a reason to measure, not a reason to assume the result.

## Constraint while both models are live

For the duration of the two-model window, new catalog facts land only in the store. A field added
to `CompletionData` or `CatalogFacts` during this item extends the surface being retired and makes
the window grow rather than shrink. `CompletionData`'s accreting back-compat constructor chain is
what that looks like when it is not stated.

## Scope boundaries

Out of scope, each for its own reason:

* `DeclarationHovers` (459 lines) reads `LspSchemaSnapshot.Built`'s classification maps, not the
  catalog. Classification is derived and only partly in the store; that is a different item.
* `Hovers`' snapshot-backed arms, `slotHover` over `TypeBackingShape` and the `lspColumnDispatch`
  switch, for the same reason. `columnHover` keeps its classification dispatch and changes only
  where its leaf resolves a table column.
* The tree-sitter parse itself, for the reason in "What tree-sitter is for" above: it resolves
  where the cursor is, and produces no fact anything could store.
* The `SourceWalker` Javadoc overlay. Not a concession but the store's stated design: the
  `jvm_class` comment says Javadoc and source positions "deliberately stay out; those live on the
  LSP's `SourceWalker` cadence and are joined at request time." Hover already joins at request
  time through `Descriptions`, and that stays.
* Protocol lifecycle and tracing.
* The other five LSP feature packages.

`CompletionData` does not delete in this item. Step 2 removes hover's direct reads of it; the
record survives for the out-of-scope features and the LSP tests that hand-build it in 87 places.
Recording that plainly matters, because the measurement below is only worth taking if the counts
are honest.

## Gate criteria

Step 1: shadow population diff green with residues named; graph scoping covered by a two-graph
seeded store; `CatalogFacts` deleted, not merely bypassed.

Step 2: shadow green; measured latency stated and no worse than the seam at p99; every rendered
surface unchanged; any capture gap left open recorded with the arm still on the seam.

## What this measures

The relational core has exactly one shell besides the generator, so the claim that it lowers the
cost of an additional consumer has one data point to divide by;
`docs/history/road-to-the-relational-core.adoc` records the measurement. The hover cutover in step
2 makes it two, under conditions that make the comparison honest: same feature, behaviour held
fixed by a shadow test except where a change is named, both sides measured.

Splitting the surface out into step 1 is what makes the number mean anything. A consumer that pays
for the substrate as well as its own view measures both at once; step 2 measures the marginal cost
of a view over a surface that already exists, which is what the claim is actually about. Record
line and branch counts on each side at the cutover commit rather than off the working tree, and
record them whichever way they come out. The null is that they stay flat, because the shared
*Java* model already made new facts nearly free for existing shells a month before the store
existed.

## User documentation

Exempt. Neither step changes a rendered surface, adds a goal or directive, or moves a wire format.
Should implementation turn up a render that cannot be preserved, that is the signal a fact is
missing, and the plan is reopened rather than the render changed.

## Roadmap entries

* The remaining LSP feature packages: completions, definition, inlay, diagnostics, code actions.
* `DeclarationHovers` and the classification-backed arms, once more of the classification
  derivation lives in the store.
* A round-outcome fact, retiring `demoteSnapshot` and the hand-maintained freshness state behind
  it.
* Per-file SDL capture, so one unparseable buffer stops invalidating a whole graph's round.
  `graphql_type_declaration` already indexes the refresh unit; the capture path takes a whole
  registry.
