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

**Freshness stays a typed workspace fact.** `LspSchemaSnapshot`'s `Built.Current` /
`Built.Previous` gets no store equivalent, and the reason is structural rather than a matter of
taste. Capture records the last *success*; `Built.Previous` is a fact about the last *attempt*.
`Workspace.demoteSnapshot` is called from three `DevMojo` paths where a parse threw and capture
therefore never ran and never wrote a row. No timestamp comparison at the read site recovers that
distinction, and inventing one would be branching on a predicate over pre-resolved inputs rather
than reading a resolved decision. If the seal is ever to move into the store, capture must first
record the attempt outcome as a fact; that round-outcome row does not exist today and is not in
this item. Until then the store answers *what* and the workspace keeps answering *how fresh*.

## Capture widenings

Widen capture where the store is genuinely missing a fact; do not widen it to keep a render
byte-equal.

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
* **A Java-type column on `sql_column`, no.** See the behaviour change below.
* **`jvm_class` filters.** The family is filtered to public, non-synthetic, top-level, outside the
  generated jOOQ package. Confirm `CompletionData.ExternalReference` agrees on all four rather
  than assuming; a disagreement is a finding either way.

## One deliberate behaviour change

`CompletionData.Column.graphqlType` is misnamed. It is `col.getType().getName()`, the Java class
name (`java.lang.Integer`), and it is rendered verbatim in three places: twice in hover and once
in `FieldCompletions`. `sql_column.sql_type` is the SQL type as jOOQ reports it.

This is why the migration unit is the read surface rather than the feature. Cut hover alone to
`sql_type` and the same column renders `integer` in a hover and `java.lang.Integer` in a
completion one keystroke later, in one editor session. Capture the Java type purely to keep the
render equal and the store has gained a fact whose only justification is preserving a mislabeled
name.

So: move both readers together, to the SQL type, and record it as a named behaviour change rather
than a parity residue. An author spelling `@field(name:)` against a column wants the SQL type. The
same reasoning applies to `columnGraphqlType`, which today resolves a `@node(keyColumns:)` entry
by scanning every table for a matching column name, keyed on nothing; the node type's resolved
table is a fact the store supplies, so the store-backed version keys on `(node table, column)`.
That is a keying correction, not a residue.

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
* Tree-sitter parsing. The store reflects the last successful capture; the server must answer on
  an unparseable mid-edit buffer.
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

Step 2: shadow green; measured latency stated and no worse than the seam at p99; the SQL-type
behaviour change landed for both readers together; any capture gap left open recorded with the arm
still on the seam.

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

Exempt for step 1. Step 2 carries one user-visible change: column type renders as the SQL type
rather than the Java class name, in hover and in field completions. That needs a line wherever
those surfaces are described.

## Roadmap entries

* The remaining LSP feature packages: completions, definition, inlay, diagnostics, code actions.
* `DeclarationHovers` and the classification-backed arms, once more of the classification
  derivation lives in the store.
* A round-outcome fact, if the freshness seal is ever to move into the store.
</content>
