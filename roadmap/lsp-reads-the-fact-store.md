---
id: R638
title: "The LSP is a fact-store client"
status: In Progress
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-14
---

# The LSP is a fact-store client

The language server is not fact-based. Completion, hover, go-to-definition, inlay hints, diagnostics
and code actions all read `CompletionData` and `LspSchemaSnapshot`, in-memory projections built per
pipeline round. Everything they cost follows from that shape: they answer only what someone
pre-projected, they scan lists linearly, they accrete a field per new consumer, and they flatten
distinctions the facts carry (`typeDefinitionLocations` is a `Map<String, SourceLocation>` and
cannot represent a type assembled from several files). Being rebuilt only by a codegen pass over the
whole schema, they also let one unparseable buffer invalidate the workspace, which the dev loop
works around by hand.

The store holds these facts as relations, at the grain the facts have, for every graph in the
workspace.

## The shape

Scope is all of `graphitron-lsp`; every feature moves.

**A request is a parse, a sealed switch, and a handler that queries.** The parse reads the LSP
request and the buffer and produces a value naming what is wanted, drawn from the sealed trigger
type below. An exhaustive switch dispatches it. The arm it selects runs one or more queries, shapes
the rows into the LSP response, and returns. There is no model between the switch and the store, no
per-round projection for a handler to consult, and no state a handler shares with another handler
beyond the store itself. A handler is a query and a rendering, and that is the whole of it.

That is the simplification, and it is not a hypothesis this item tests. The incumbent rescans lists
linearly, recomputes per keystroke, fans that recomputation across dependent files, and can only
answer what a codegen pass thought to pre-project. Indexed reads on the save cadence beat that on
work done, on latency and on what can be surfaced at all, since the store carries distinctions the
projections flatten. An earlier draft of this item posed all of that as a claim under test with a
paired measurement and an abandon path. That framing is dropped, and with it the reason the two
implementations would have had to coexist behind one facade.

**No compatibility facade.** Nothing shoehorns the new implementation into the incumbent's shape so
the two can answer side by side. A capability is rewritten against the store and its incumbent is
deleted in the same commit. That is a strangler fig, not a bake-off: the work still lands
incrementally, substrate first and then capability by capability, so the acceptance holds at every
intermediate commit and the language server is never broken across a series of them, but at no
point are there two live answers to one request.

The baselines stay on the record as an outcome rather than a gate: `graphitron-lsp` is **9,119**
main lines and the `rewrite/catalog` seam is **3,232** of the package's 4,008, with `SourceWalker`
and `ClasspathScanner` netted out because their lines move rather than delete: both end up
capture-side readers (`ClasspathScanner` already is one; `SourceWalker` relocates in this item, see
"What retires"). Report both at the end, plus the SQL added. If
the totals come out worse than expected that is worth knowing and saying; it is not a trigger for
anything, because there is no longer an incumbent to fall back to.

**The reuse is of facts, not of code.** The seam between the language server and the store is a
query and its rows, nothing else. The LSP states its data need, fetches exactly that, and reads the
result as jOOQ's `Result` and `RecordN` tuples off the generated model; a hand-written type appears
only where it carries something the rows do not, and it is the LSP's own. No `graphitron`
projection type crosses the seam, and none gets rebuilt store-side under a new name: an arm list
that exists because a Java projection had those arms is the shape being replaced, not a claim on
the query. The structural test is `graphitron-lsp`'s pom. It names `graphitron` today and imports
twenty-one types from it, ten from `rewrite/catalog`: `CompletionData`, `LspSchemaSnapshot`,
`CatalogFacts`, `SourceWalker` and the six classification projections named under "What retires".
At the cutover it should name `graphitron-model` for the generated store
tables, with whatever remains of the `graphitron` dependency accounted for one type at a time. A
surviving projection import is a surviving second model, whatever the line count says.

**This is not a port.** The incumbent's behaviour is not the target, so there is no shadow-parity
gate and no byte-equality on rendered output; pinning the new implementation to the old would import
the shape being replaced, and each handler should answer as well as its facts allow rather than as
well as its predecessor did. What stands in for a parity gate is an enforcer, not care: a meta-test
in the `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` mould asserting the
(trigger × surface) matrix is an exhaustive partition over answered / declared-no-answer /
unimplemented, each surface's dispatch a compile-checked exhaustive switch. The axis is trigger,
not `Behavior` arm: roughly half the inventory below keys on something else (all four inlay-hint
rows, both code-action branches, four of the five diagnostic sources, the four hover rows keyed on
no `Behavior` arm, the two SDL-keyed definition providers), and that is the half this item changes most. A
matrix over `Behavior` alone would leave it ungated, which is the position declining the
shadow-parity gate was meant to avoid. The `†` gaps below stop being silently empty arms and become
declared facts the test pins, and the inventory becomes a rendered view of the matrix rather than
prose that rots.

The sealed trigger type is the one the parse produces and the switch dispatches, so it is the
architecture rather than a scaffold the meta-test needs. It has to be sealed for two reasons that
now coincide. `everyGraphitronFieldLeafHasAKnownDispatchStatus` draws its entire universe from
`getPermittedSubclasses()`, so a hand-listed trigger vocabulary would turn the "exhaustive
partition" into an unguarded inventory relocated from prose into a test file, which is the smell and
not the cure. And a handler switch that is not exhaustive over a sealed subject is a switch with a
`default` arm, which is how a capability goes quietly unanswered. `Behavior`'s eight arms are one
family inside the hierarchy, beside the inlay-hint toggles, the directive-name and declaration-name
tokens, the fix-bearing lint finding and the detector re-scan. Building it is the first substrate
commit's output, because every capability migrated afterwards dispatches through it.

## The division of labour

**Tree-sitter extracts intent**: buffer position to schema coordinate, over the live and possibly
unparseable buffer, and the reverse when a store position must land in a buffer that has drifted
since capture. Positions in, positions out; it produces no fact, judges nothing, and writes nothing
to the store. That is its whole job, it is the only part that must tolerate broken syntax, and it is
the only part that sees unsaved content at all.

**The store answers everything else**, for the whole workspace: completion lists, hover bodies,
definition targets, hint values, diagnostic judgements. The incumbent already leans this way; a
source survey found no tree-sitter syntax diagnostics and no tree-sitter workspace scan to retire
(trees exist only for open buffers, and syntax validity ships via the `ValidationReport` replay).
But it exceeds
the line in three places this item pulls back: `IntraSchemaDefinitions` treats every open buffer's
tree as authoritative over the projection, `WorkspaceFile` re-derives a declared/referenced type
index from the tree on every keystroke to aim the diagnostic fan-out, and the recalculation queue
re-runs full-tree validation per keystroke across every dependent open file.

The same line binds the other direction: the LSP never walks. Its inputs are the live buffer and
the store, and its only signal toward the writer is that a schema file was saved. Today it walks
Java source roots itself: `Workspace.refreshSourceIndex` builds the `SourceWalker.Index` that hover
Javadoc and goto-definition join at request time, and that `graphitron-mcp` borrows through
`workspace.sourceIndex()`, a second fact source with its own cadence inside the one module this
item is emptying. That walk moves store-side; the java-source family below carries what it
produced. No new signal is needed for `.java` files, and none should route through the LSP (a
`.java` changed by a build or a rebase never touches an editor): the dev session already owns the
watcher (`DevMojo.startSourceWatcher`), and what changes is its sink, from `Workspace.setSourceIndex`
to capture.

There is no gate between the two, because there is no state in which the store cannot answer. Two-
stage capture writes on every outcome, so tree-sitter's job is the same whatever the buffer holds:
positions in, positions out. What the store answers for a file is the facts of its last saved
content, which lags the buffer by exactly one save; that is a stated division of labour, not a
currency variant a surface switches on. The first iteration keeps the shadow minimal, simple and
correct over clever: the live tree supplies the coordinate under the cursor and re-anchors positions
into text that has moved since capture, never facts. Widening it so live declarations feed answers
before capture is a later iteration, taken only if authors report the wait hurting.

A graph is many schema files, so validity is per file, not per workspace. An author typing
`extend type |` in a new file has one invalid buffer and a workspace of well-formed captured ones;
the completion wanted is what those other files declare. The invalid buffer is not an obstacle to
answering, it is the question: tree-sitter names the coordinate, the store supplies the list.

## What the store must provide

**Two-stage capture, so the store is never blind.** In scope, and the shape is two stages rather
than one validity gate. Stage one parses each schema file to its own registry and slurps it into the
store; a file that will not parse writes its syntax error as a located violation row, and its
siblings land regardless. Stage two combines the parsed files and assembles the GraphQL schema,
where graphql-java validates a great deal the parser did not. That stage either fails, and its
errors are facts, or succeeds, and the facts only the combined schema can carry land with it:
reachability, the input-occurrence rows, `merge_ordinal`. Both outcomes write. Neither leaves the
store empty.

That is the property the whole item rests on. Every state the incumbent expressed by withholding a
snapshot is a row here: a file that would not parse, a schema that would not assemble, a type
nothing declares. The LSP never asks whether it may trust an answer. It runs its query and reads
what is there, absence of a row being an answer in its own right, and which kind of absence a join
away, since `store_source` records what was captured and the stage-two outcome records whether the
last combine succeeded.

The blocker sits earlier than this item first placed it. `RewriteSchemaLoader.load` concatenates
every schema file through one `MultiSourceReader` into a single document and parses it once, so a
syntax error in any file throws `SchemaParseException` and no registry exists at all. Per-file
parsing is that split; it is not a change to `FactCapture.capture`'s signature. Capture is already
source-attributed underneath: `SdlFactCapture` derives its source names from each definition's own
`getSourceLocation()`, `graphql_type_declaration` indexes "which types does this file touch" by its
own comment, and `store_source` stamps each file.
`GraphitronSchemaBuilder`'s `makeExecutableSchema` call is where stage two already lives; what
changes is that its failure writes rows rather than propagating out of
`GraphQLRewriteGenerator.buildOutput` and taking capture with it.

**A graph-scoped handle.** `sql_` is keyed by `source_name` and a persisted store spans every module
of a workspace, so catalog reads join through `store_graph_source` and the handle is
`(DSLContext, graphName)` — the shape `StoreHandle` already is — never a bare `DSLContext`. That
makes the scoping structural rather than a rule each query site remembers. No new facts are needed:
`GraphSourceMembership.note` already records the packages the catalog walk read, which beats the
configured package. The handle type moves to `graphitron-model` beside the store; `graphitron-lsp`
does not depend on `graphitron-mcp`, and a second copy of the handle is how the scoping stops being
structural. One case decides where the graph is chosen: `store_graph_source` puts no uniqueness on
`source_name`, so a schema file can belong to two graphs, and the request boundary resolves
`source_name` to a graph once, through that relation, with a sealed outcome for the multi-graph
case rather than first-row-wins.

**Its own read connection.** `GraphitronModelStore` holds one `Connection`, shared with the MCP
server, which is safe only because MCP is turn-based. Both URL shapes admit a second connection,
but the class deliberately publishes no URL (the in-memory name carries a private UUID), so the
store mints a reader rather than the LSP reconstructing a path. Capture is a single transaction, so
a reader never sees a half-written round; each answer likewise assembles inside one read
transaction so it cannot straddle a commit, which is the whole consistency claim, made structural
instead of resting on an H2 isolation default. The store is a cache by contract, "never state of
record", and its fallback to a private in-memory store costs only warmth while every reader shares
the writer's process. That last clause is what keeps the fallback from being a degraded answer
surface: the session's own capture fills the private store as readily as the shared one, so a cold
start costs the previous run's rows and nothing else. There is no configuration under which the LSP
holds a store it cannot query.

**Sealed resolution outcomes.** The store's keys are honest where the projections' lists were not:
an unqualified table name may match several rows, and `jvm_method` keys on `descriptor` because
overloads share a display name. Resolved / ambiguous / not-found needs no type: it is
how many rows the query returned, and a `Result` says that already. Nor is there a freshness arm
beside it. An earlier draft of this item carried one, `Indeterminate`, because the incumbent
withholds its whole snapshot when a build fails and every consumer had to be forced to notice. Two-
stage capture removes the state that arm described: a failed parse and a failed assembly are both
rows, so there is no moment at which the store declines to answer and nothing for a consumer to
switch on. A multi-row result at an author-written coordinate surfaces as a diagnostic, never a
silent first match.

The store updates on save, and unsaved content is seen only by tree-sitter. That is where the
division of labour sits, not a performance tradeoff to be tuned: the store's subject is the schema
as it exists, and a buffer nobody has saved is not yet part of it. The line can move later if it
proves to hurt, on reports from authors using it.

Saving on save does not bring the retired arm back, and the distinction is worth being exact about,
because it is easy to slide from one to the other. A store answering from the last saved content is
not a store declining to answer; it answers a well-posed question definitely, and the question it
answers is the right one. The lag has teeth in exactly one place: a diagnostic asserting absence,
"no such type", "no such column", against a buffer where the author has just typed the thing that
would satisfy it. The capture cadence already handles that, since diagnostics publish when capture
swaps rather than on keystroke, so a file's diagnostics describe the content they were computed from
and refresh at its next save, re-anchored through the live tree where the text has moved. The same
cadence covers the cold case for free: before a graph's first capture nothing swaps, so nothing
publishes, and no author is told their schema is undeclared by a store that has not read it yet.

**Coordinate-keyed lookups.** The parse hands over a coordinate; the store answers it, and what
comes back is rows. `DeclTarget`'s six variants are not a shape to preserve: they are the projection
era's dispatch vocabulary, and re-pointing them at the store would carry that shape across the seam
under a new payload. What a bound field binds to lives on `graphitron_service` and
`graphitron_external_field`, which carry the class and the method, and on `graphitron_routine`,
which carries the routine reference; by being three relations they say which directive bound it. Not `graphitron_field_binding`: that relation
carries `@field(name:)`'s bound name and defers backing to classification by its own comment.

Parity between hover and definition is that they read the same facts, not that they run the same
query. With no projection standing between them and the relations, neither can be right about what
a field binds to while the other is wrong; that is the whole of what the shared type was buying.
What each selects off those facts is its own business, and they should diverge, because the shared
type capped both at the intersection. The incumbent's javadoc states the cap plainly: the variants
"name the resolved declaration, not its location or Javadoc", and the per-consumer difference is
"only the final read". Definition wants a position and little else. Hover wants a signature, a
comment with the catalog description taking precedence, a column's type and nullability, the member
name and type when the backing is a record or POJO. Each fetching what it renders is the point of
re-sourcing, not a looseness to be tidied back into one query.

**A java-source family, on the source cadence.** Hover's Javadoc and definition's positions come
from parsed `.java` sources, and here they are rows like everything else: a `java_` family written
by `SourceWalker` as a capture-side reader, prefixed for the vocabulary its rows are written in
(source-form declarations keyed by arity are Java's, not the JVM's). It is its own population with
its own natural key (file, declaring FQN, member, declaration ordinal), joined by name to
`jvm_class` and `jvm_method` on one side and to the captured table-class and `Keys` FQNs on the
other; never columns on `jvm_class`, and never FK'd to it. Two facts force that shape. `jvm_class`
excludes the generated jOOQ package while the jOOQ half of goto-definition jumps into exactly those
classes, so a family hung off `jvm_class` silently drops `@table`, `@field(name:)` and
`@reference(key:)` definition; the FQN capture widening below is that join's key. And `jvm_method`
keys on `descriptor` where a source parse yields arity, so the source rows cannot take its key.
The join is outer on both sides and no view may assert agreement between the two populations: a
source row and its `jvm_` twin can legitimately disagree between cadences, the same skew that exists
today between the LSP index and the catalog, made visible instead of ambient. The family is
source-keyed, not graph-keyed, like `store_source` itself; that is the one stated exception to the
graph-scoped handle, and graph scoping happens on the `jvm_`/`sql_` side of the join through
`store_graph_source`.

Refresh is per file, one transaction per source in the `StoreRefresh` retain-or-rewrite mould, with
the content-hash bookkeeping on the family's own file relation rather than one `store_source` row
per `.java` file, so `store_source`'s taxonomy stays closed and the freshness scan stays
proportional to what changed. The cadence gets an enforcer, not a stated intent:
`SourceCadenceHoverAndDefinitionTest` and `CatalogRefreshTest` repoint rather than retire, asserting
that a `.java` edit moves the store row with no generator round; without that pin the family drifts
onto the round cadence, which is exactly the staleness the old doctrine feared. Headless LSP-only
use has no source watcher, so the family sits empty and absence is an answer, which is the status
quo: `refreshSourceIndex` was only ever called from the dev session and tests. The family registers
a new agreement-arm shape in `FactCaptureAgreementTest`, source-partitioned where the existing
oracle-lifecycle anchors are graph-partitioned, and `FactSchemaGateTest`'s transcription-twin rule
needs an answer for a family whose oracle is a source parse.

## Capture widenings

Three facts read off a live handle inside the codegen scope, so unrecoverable downstream.

* **The jOOQ binding type on `sql_column`.** `ColumnEntry` and `ColumnFacts` read the same
  `org.jooq.Field` and each keeps a different type off it; capture writes `ColumnFacts`, so the
  binding type reaches no relation. A column has both a SQL type and a binding type. Fix
  `ColumnFacts`' javadoc, which claims to be a superset of `ColumnEntry` and is not.
* **`sql_constraint.jooq_name`.** The `Keys` constant is resolved by reference identity over the
  generated class's fields, not by formula, and it is what an author types in `@reference(key:)`.
  `sql_table` and `sql_column` each already carry a `jooq_name`. Capturing it retires
  `CatalogBuilder`'s `jooqPackage() + ".Keys"` guess.
* **The generated class FQNs**, at the concept's grain: the table class FQN is per table, the `Keys`
  FQN is per `(source_name, table_schema)` and is a repeating group on `CompletionData.Reference`
  today. Under the java-source family these FQNs are the join key that lands definition in
  generated sources, load-bearing rather than a completion nicety.

`jvm_class`'s filters (public, non-synthetic, top-level, outside the generated package) are a
design input here, not a confirmation: the generated-package exclusion is why the java-source
family joins through the captured FQNs rather than through `jvm_class` for the jOOQ half.

## What retires

`CompletionData`, `CatalogFacts`, `LspSchemaSnapshot` and its freshness seal, `CatalogBuilder`'s
projection pass, and `DevMojo`'s keep-previous-and-demote workaround at all three of its
`demoteSnapshot()` sites, not only `rebuildCatalog`'s — catalog candidates are not retained across
a bad parse, they were never invalidated by it.

"Most of `rewrite/catalog`" is too vague to sweep, so the projection types the LSP imports are named
here: `FieldClassification`, `TypeClassification`, `TypeBackingShape`, `DirectiveShape`,
`InputValueShape` and `InferredDirectiveArgs`. These are the ones the inventory below hides behind
the word "classification", and they are the ones a port would keep. Each leaves the LSP as a query
over the claim stratum, not as a store-side rebuild of its arms. Whether the types
themselves also delete depends on their generator-side readers, which is a separate census; what
this item owns is that no LSP surface dispatches on them.

With them go the LSP's own tree-derived facts: `WorkspaceFile.refreshTypeIndex` and the
declared/referenced type sets it re-derives per keystroke, whose one consumer is the cross-file
diagnostic fan-out. "Which files touch this type" is a read over `graphql_type_declaration`, and
the only file that relation cannot speak for is the one stale buffer.

`CatalogFacts` has non-LSP readers that must move with it: `GraphitronMcpServer` (the
`catalog.tables` and `catalog.describe` tools), `EdgeProducer`, `EdgesTool`, `ReverseEdgeIndex`,
`CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`, plus
`GraphQLRewriteGenerator` in `graphitron`, whose output record carries the projection. Not LSP
work, not optional; the projection cannot delete while they read it. `TenantScopes`, `McpWire` and
`NodeRef` cite it only in javadoc, so they repoint rather than migrate; the `{@link}` gate keeps
them from being forgotten.

`SourceWalker` moves rather than stays. An earlier pass kept it LSP-side as shipped doctrine, and
that reading does not survive an audit of what keeping it costs: `SourceWalker.Decl` carries a
`CompletionData.SourceLocation`, so the projection this item deletes stays alive inside its own
replacement; `graphitron-mcp` reads the index through `workspace.sourceIndex()`, so `Workspace`
survives as a shim feeding another module, the cross-consumer private model "One model, many views"
names; and `SourceWalker.Index` hand-rolls the resolved/ambiguous/not-found tri-state
(`ambiguousMethods` as a side-set, first-declaration-wins merges) that "Sealed resolution outcomes"
retires everywhere else. So the walker becomes a capture-side reader beside `ClasspathScanner`,
relocating from `rewrite/catalog` to the capture package, and the LSP never calls walk:
`refreshSourceIndex` and the LSP-owned index retire, and the MCP Javadoc joins repoint to store
queries in the sibling item.

The doctrine rewrites honestly rather than quietly. The cadence rule's own closing sentence
licenses the move: "joined, not stored" is the law for positions that move on a cadence the fact
does not, never a ban on positions that share the fact's own cadence, and the java-source family's
cadence is the source's own. Two paragraphs of `fact-model.adoc` change, not one: the location
paragraph, and the co-sourced-description sentence, because Javadoc becomes the paradigm
counter-case (a different walk on a different cadence from the bytecode scan, so its own relation,
never a column on `jvm_class`). The `jvm_class` DDL comment restates the division instead of
deleting: what the classfile declares lives there; positions and Javadoc live in the java-source
family on the source cadence, joined by name. The SDL positions the store already carries were
always the same rule's sanctioned side: SDL is what capture reads, so those positions were on the
capture cadence from the start.

The rewrite frames the doctrine around the loop this item ran four times, because it is the
steady-state relationship between consumers and the store rather than a one-off event: a consumer
discovers a fact it needs and the store does not carry; the fact is modeled at its own grain, never
at the discovering consumer's convenience; every other consumer inherits it. The four instances are
the record: the binding type hover wanted and `ColumnFacts` dropped, the `Keys` constant name
completion wanted and `CatalogBuilder` guessed, the generated FQNs definition wanted and a
projection hoarded, and the java-source family itself, whose first beneficiary beyond the LSP is
the MCP Javadoc join that stops borrowing `workspace.sourceIndex()`. The grain clause is the guard
that keeps the loop from unwinding the item: a store that accretes consumer-shaped columns is
`CompletionData` with SQL syntax.

The retirement also takes named exemplars out of the principle docs, and the sweep must repoint
them, not just delete: `CatalogBuilder.projectFieldClassification` is the transitional exemplar
under "One model, many views" in `docs/architecture/explanation/development-principles.adoc` and
the named enforcer in `fact-model.adoc`; `CompletionData.NodeMetadata` is the one-slot provenance
exemplar and `LspSchemaSnapshot`'s two axes carry the freshness paragraph in the same file. The
replacements are nominated here: the store-side projection seam for the first, and for freshness the
two-stage capture itself. That paragraph currently explains how a consumer carries a freshness axis;
its replacement explains why no consumer carries one, because a failed parse and a failed assembly
are facts and there is no withheld snapshot to tag. `FactCaptureAgreementTest`'s agreement arms
change meaning for every relation the LSP starts reading and are revisited at the cutover.

## Acceptance

Features are specified against fixtures, not the incumbent: given this buffer, this cursor and this
store, this answer. The store is stood up by real capture over SDL fixtures wherever capture can
produce the state, so a fixture cannot encode rows capture never writes; direct inserts are
reserved for states one capture call cannot reach, with
`ColumnMatchClaimTest.siblingGraphsResolveThroughTheirOwnMembership` the precedent for exactly that
(two graphs in one store). The crawlers are tested where they are.

Five cases the corpus must carry. The first four are things the current design cannot express; the
fifth is the property the old doctrine protected, kept and pinned at the store layer:

* A dirty buffer beside well-formed siblings: `extend type |` completes against the other files.
* A type assembled from several files, resolving to all its declaration sites.
* Two graphs in one store, neither seeing the other's tables.
* One file in two graphs: the request boundary surfaces the multi-graph membership arm, not the
  first row.
* A `.java` edit and save beside an untouched schema: hover Javadoc and the definition target move
  with no generator round. The repointed cadence tests pin the same fact at the store layer.

Latency is measured per request on the Sakila fixture, against the new implementation alone. Not a
comparison, since there is nothing left to compare against and the direction is not in doubt: the
point is to find which paths are slow as views, because that decision has a sanctioned answer and
needs the numbers to be made. A hot path materializes, with the DDL comment owning why, as the
reachability closure `intent_type_domain` does; never an ad-hoc cache. Measure early enough in the capability sequence that
the first materialization is a design choice rather than a repair.

## Capability inventory

The work list. Every capability the language server serves today, with what triggers it and where a
fact-based implementation gets its answer. `†` marks a capability that returns nothing today, so it
is a gap to close rather than a behaviour to reproduce.

Every fact source below must be a relation or a view. The rows that said "classification" are not
one: the word named `FieldClassification` and `TypeClassification`, the Java projections retired
above, and leaving it there is how a port smuggles them back in. Each now names the view that
answers it, settled in "the substrate, named view by view" below, with the ones not yet built marked
as such. Which view, not how many queries: several rows projecting different things off one view is
a fine outcome, and collapsing them because they share a source would be the same error as sharing a
type because they share a subject.

The bundled directive vocabulary is not a second source either, though the incumbent treats it as
one: capture parses the bundled `directives.graphqls` like any schema file and its definitions are
rows (`SdlFactCapture`'s own contract: an application's directive name always resolves to a row),
so the directive-name and docstring rows below read `graphql_directive` and
`graphql_directive_argument` for bundled and user-declared alike.

Five request capabilities are registered (`GraphitronLanguageServer.initialize`): hover, completion,
definition, code action, inlay hint. Diagnostics are pushed. Document sync is incremental.

**Completion.** `Completions.at` resolves the coordinate once, resolves its `Behavior`, and runs the
providers registered for that arm in order, first non-empty wins. Two providers on one arm is a
dispatch-era artifact: both read the same `CompletionData.externalReferences()`, and the split is
`@externalField`'s contract narrowing (single parameter, `Field` return) placed ahead of the generic
method list with fall-through when nothing matches. In fact terms that is one query with its
narrowing and its ordering stated in the view, which is where the simplicity claim should show
first; the fall-through is behaviour, not accident, so collapsing the two keeps it.

| Behavior arm | Provider | Fact source |
|---|---|---|
| `ClassNameBinding` | `ClassNameCompletions` | `jvm_class` |
| `MethodNameBinding` | `MethodCompletions` | `jvm_method`, `jvm_method_parameter` |
| `CatalogTableBinding` | `TableCompletions` | `sql_table` |
| `CatalogColumnBinding` | `FieldCompletions` | `sql_column`; the site's own table via `intent_field_column_table` (unbuilt) |
| `CatalogFkBinding` | `ReferenceCompletions` | `sql_constraint`, `sql_referential_constraint`; the enclosing type's binding via `intent_bound_table` |
| `ArgMappingBinding` | `ArgMappingCompletions` | `jvm_method_parameter`; the GraphQL side off the buffer's own field definition, whose arguments are the ones being edited |
| `ScalarTypeBinding` | `ScalarTypeCompletions` | `jvm_scalar_type_field` |
| `NodeTypeBinding` | `NodeTypeCompletions` | `graphitron_node` |
| no coordinate, or no value match | `ArgNameCompletions` (fallback) | `graphql_directive_argument` |

A named debt rides `jvm_method_parameter`: its relation comment has the per-application
`ParamSource` decision landing as a derived relation with its first consumer, and the
`<sessionState>` mount's payload parameters are a candidate first consumer. If that relation
lands, the census follow-up is admitting the named mount and unmount classes (today excluded
with the rest of the consumer's jOOQ output package) so their signatures are visible to the
same readers. Named here because the session-identity spec that first recorded the debt
deletes at Done.

**Hover.** `Hovers` dispatches on the same `Behavior` taxonomy, with three non-coordinate arms
around it.

| Trigger | Answers | Fact source |
|---|---|---|
| Directive name token | Directive description | `graphql_directive` |
| `ClassNameBinding` | Class FQN + Javadoc | `jvm_class`; Javadoc via the `java_` source family |
| `MethodNameBinding` | A signature per overload + Javadoc | `jvm_method`, `jvm_method_parameter`; Javadoc via the `java_` source family, joined on arity |
| `CatalogTableBinding` | Description, column and key counts | `sql_table`, `sql_column`, `sql_referential_constraint`; Javadoc via the `java_` source family |
| `CatalogColumnBinding` | Both column types, nullability, description; member name and type when the backing is a record or POJO | `sql_column`, `sql_table`; Javadoc via the `java_` source family. The site's table via `intent_field_column_table` and the member arms via `intent_type_backing_class` joined to the `jvm_` census, both unbuilt |
| `CatalogFkBinding` | FK direction and endpoints, under any spelling the resolver accepts | `sql_referential_constraint`, `sql_constraint` |
| `NodeTypeBinding` | `typeId`, key columns and their types | `graphitron_node`, `graphitron_node_key_column`, `graphitron_table` + `sql_column` for the types |
| `ArgMappingBinding`, `ScalarTypeBinding` † | nothing | — |
| Any coordinate, no richer arm | SDL docstring | `graphql_directive_argument` for a directive argument, `graphql_field` for a nested input field |
| Directive argument name | Arg docstring | `graphql_directive_argument`, bundled and author-declared alike |
| SDL declaration name (`hoverClassification` toggle) | `DeclarationHovers`: classification block + the bound declaration's description | the verdict views for the block, their classifier vocabularies grown to the whole taxonomy; `sql_table`, `sql_column` and the `java_` source family for the description |

**Definition.** Three providers chained with `.or()` in this order, keyed on disjoint syntax.

| Provider | Trigger | Fact source |
|---|---|---|
| `Definitions` | Directive arg: `ClassName`, `MethodName`, `CatalogTable`, `CatalogColumn`, `CatalogFk` | `jvm_`/`sql_` joined to the `java_` source family's positions |
| `Definitions` † | `ArgMapping`, `ScalarType`, `NodeType` return empty | — |
| `IntraSchemaDefinitions` | Type reference to its declaring SDL site | `graphql_type_declaration`; a declaring file that has moved since capture re-anchors through its live tree |
| `DeclarationDefinitions` | SDL declaration name to its bound Java | `jvm_class`, `jvm_record_component` + the `java_` source family |

**Inlay hints.** Three independent toggles, all default off (`InlayHintConfig`); two collectors.

| Toggle | Collector | Fact source |
|---|---|---|
| `classification` | `collectClassificationHints` | the verdict views, both grains, their classifier vocabularies grown to the whole taxonomy |
| `inferredDirectives` | `collectInferredDirectiveHints`, renderers for `@table`, `@field`, `@reference` | `intent_bound_table` for the `@table` renderer; `intent_column_match_claim` for the `@field` renderer, plus `intent_type_backing_class` (unbuilt) where the backing is a record or POJO; the `@reference` renderer fires only on an *omitted* path, so its source is the foreign-key discovery between the two types' bindings (unbuilt), not the authored chain |
| `inferredDirectives` | `collectAbsentDirectiveHints`, a second pass inside the inferred-directive collector | same, absence arm |
| `hoverClassification` | gates `DeclarationHovers` (see hover) | the verdict views, as the `classification` toggle above |

**Code actions.** Two branches, deliberately not sharing a path.

| Branch | Trigger | Fact source |
|---|---|---|
| `LintQuickFixes` | A fix-bearing lint finding for the document, while the buffer still holds the captured text | `lint_finding`, `lint_finding_fix`, `lint_finding_fix_edit`; the buffer gate against `store_source.stamp` |
| `SdlActions` † | Detector re-scan per document; registry is empty today | — |

Each `SdlActions` fix offers three scopes: per site, whole file, whole workspace.

**Diagnostics.** Pushed from five sources. The publish funnel fires on open, change and close, and
again whenever a build swaps the snapshot; save reaches it through the rebuild, not directly.

| Source | What it reports |
|---|---|
| Coordinate validation | Dispatches all eight `Behavior` arms; unresolved values against the catalog and classpath |
| Unknown args | Directive args not declared, bundled and user-declared paths separately |
| Required args | Declared-required args absent |
| Unknown directive | Skipping the GraphQL spec built-ins |
| `ValidationReport` replay | Build errors and warnings for URIs the report covers |

First-iteration cadence: diagnostics ride the capture cadence, not the keystroke. Every source in
the table reads as rows from the store, published per file when capture swaps; the per-keystroke
recomputation and its cross-file fan-out retire with the type index that aimed them. A stale
buffer shows the diagnostics of its last captured content, re-anchored through its live tree where
the text has moved, refreshed at its next capture. That trades keystroke-live feedback in the one
buffer being typed in for a single shape everywhere, and against the incumbent it is not a close
comparison: the incumbent silences the whole replay while the snapshot is demoted, so a newly broken
schema shows nothing at all, where here a file that will not parse and a schema that will not
assemble each report exactly why, as rows, per file. Keystroke-live validation of a lagging buffer
is the same later iteration as the wider shadow, and the capture-cadence question above may retire
it outright.

Compile diagnostics (javac output against generated sources) are already store-side: `DevMojo`
writes them through `CompileFacts` and the MCP diagnostics tool reads the store's `diagnostic`
view, not the LSP push. The `Workspace.compileDiagnostics` slot they once rode has no production
reader left and retires with the rest of the workspace bookkeeping.

**Lifecycle and state.** `didOpen` / `didChange` (incremental) / `didClose` / `didSave`;
`didChangeConfiguration` plus a `workspace/configuration` pull after `initialize` for the three inlay
toggles; `didChangeWatchedFiles` is a no-op today. The open-buffer set stays in `Workspace`; the
tree-derived type index (`refreshTypeIndex`'s declared/referenced sets) and the per-file
recalculation bookkeeping it aims retire with the keystroke cadence (see the diagnostics
paragraph above), and the source index (`refreshSourceIndex`, `sourceIndex`) retires with the
java-source family: the LSP walks nothing.

## Resolved questions

Three questions an earlier draft left to the reviewer, since answered against the workflow and the
fact model; the reviewer confirms rather than decides.

* **Sequencing** is substrate first, then capability by capability, each rewrite deleting its
  incumbent in the same commit. Settled as one item, and the question is now much smaller than the
  drafts that debated it. Two earlier passes argued over splitting substrate from experiment,
  because an abandon path made it ambiguous which half would revert. With no experiment there is no
  abandon path and nothing to revert, so the split has lost the problem it was proposed to solve.
  What remains is ordinary strangler sequencing inside one item: the sealed trigger type and the
  store-side substrate land first because every capability dispatches through them, and the
  capability order after that is the implementer's, subject only to the acceptance holding at each
  commit.
* **`SourceWalker`'s boundary** reversed on review. An earlier pass resolved it as shipped
  doctrine and kept the walk LSP-side; the standing line is now that the new LSP never calls walk,
  its inputs being the live buffer and the store and its only outbound signal a save. The
  doctrine's own text licenses the move (see "What retires"): the walker becomes a capture-side
  reader, the java-source family carries its output on the source cadence, and the doctrine
  paragraphs rewrite with the preserved principle named, facts refresh on the cadence of their
  source.
* **`CatalogFacts`' non-LSP readers** move alongside, in the sibling item
  `catalog-facts-readers-move-to-the-store.md`, for two reasons: the MCP
  catalog tools have their own acceptance surface (tool output, paging) that has nothing to do with
  cursors and buffers, and folding them in credits their `rewrite/catalog` lines to the LSP
  measurement. The constraint binding the two items: `CatalogFacts` deletes in the same commit as
  its last reader's migration, and both consumers read one shared store-side catalog view, never a
  narrowing made for one of them.

## Settled while building: the reading stages

The spec called the capture two-stage, "parse each schema file alone" then "combine and assemble".
Reading a schema is three stages, and the middle one was folded invisibly into the first. Settled in
implementation, recorded here because the DDL now depends on it:

* **The stages are parse, combine, assemble**, and each judges the document at its own grain. The
  parser judges one file at a time. The registry judges the combined declarations and refuses
  duplicate base declarations (`TypeRedefinitionError`, `DirectiveRedefinitionError`,
  `SchemaRedefinitionError`), which in a multi-file workspace is precisely the class of error no
  single file's parse can see. Assembly judges the registry against the GraphQL specification's
  structural rules, and is the only place they are checked at all.
* **Only parsing produces facts.** The two later stages contribute nothing to the store but their
  verdicts, which is why one relation holds both and why the store's `stage` column, not two
  relations, carries the difference. Their real product is the registry that parsing's facts flow
  through.
* **No stage's refusal aborts the next.** Parse keeps the sources that parsed; the registry admits
  definitions one at a time (`TypeDefinitionRegistry.add` reports rather than throws, so this is
  `buildRegistry`'s own loop without its terminal throw) and keeps what it admitted; assembly runs
  over whatever survived both. This is what stops one freshly broken file from blanking every fact
  about every file beside it. The build's own verdict is unchanged and still fatal, each stage still
  throwing exactly the exception it always threw; what changed is what is true of the store by the
  time it does.
* **Assembly runs unconditionally**, whether or not the pass has any use for the assembled schema,
  because its verdict is a fact about the consumer's schema worth as much as the declarations it
  judges.
* **Two relations, in `graphql_`.** `graphql_syntax_error` is keyed on the source it refused, since
  parsing stops at a file's first error, which makes "does this file parse" a primary-key lookup and
  satisfies the header's source-partitionability requirement. `graphql_schema_error` is keyed on an
  emit-order ordinal like the stratum's other arms, because a whole-document stage reports as many
  verdicts as it found. They are siblings rather than one relation because they refresh at different
  grains: a syntax refusal belongs to one source, a document-wide verdict to no source at all. The
  family is `graphql_` rather than a new one named for the SDL toolchain, which would have aliased
  this family's subject; the cost, accepted and written into the family's charter, is that
  `graphql_` now holds both a transcription and a judgement of the same artifact.

Picked up along the way, since the increment was already in this code: the dev loop's parse-failure
line was contentless. `SchemaParseException`'s reason clause took the first sentence of the parser's
message, and on graphql-java's explained shapes the first sentence is the fixed lead `Invalid syntax
encountered.`, with the explanation in the clause after it. So an author whose buffer the loop refused
got four words that say nothing they can act on. The trim is subtractive now, removing the coordinates
the message's own prefix states and that contentless lead, and keeping everything else including the
offending token. It has to be subtractive rather than selective, because the shapes disagree about
what the offending-token clause is: a trailing sentence on the explained shapes, the grammatical
object of the only sentence on the bare ones. An unrecognised shape passes through whole, so guessing
wrong costs a redundant clause rather than the explanation.

One consequence for an existing relation: `graphql_duplicate_declaration`'s element-level kinds were
unreachable only because capture was conditional on the document assembling. They are reachable now,
and the relation's comment says so instead of claiming emptiness it no longer has.

A file that does not exist writes no `store_source` row, and that is settled as correct rather than as
a gap the census owes. `store_source` is the read-set population, one row per source the store
actually read, and a file nobody could open was not read and has nothing to hash, so a row for it
would assert membership in the population that means "met". A configured-but-absent file belongs to
the recipe instead, and `store_graph_schema_input` already carries it: its comment says outright that
it records how to find the graph's schema files, "including ones that do not exist yet". The two
populations differing is the fact, not a row missing from one of them.

What that leaves is a reading-stage question rather than a census one, and it is the same contract the
next paragraph defers. `openSource` throws a bare `RuntimeException` from inside the per-source loop,
so a round meeting a missing or unreadable file still writes nothing at all, its readable siblings'
facts included, and never reaches the recipe rows either, since capture runs after the read. For a
wrong path in configuration that loudness is right. For a file deleted or briefly unreadable under a
watch loop it is the failure this item exists to remove, one source blanking the workspace, so the
stage that survives a refusal of content should eventually survive a refusal to open. Not a new hole
and not this increment's: the throw contract migrates with the reader.

Deliberately not in this increment: the LSP still throws out of `buildOutput` on a refused document.
The facts landing is the prerequisite; migrating the reader is the capability work, and changing that
contract before a store-reading consumer exists would be a half-migration with nothing on the other
side.

## Settled while building: the read substrate

The handle and the read connection landed together, since neither is usable without the other, and
three details were decided in implementation:

* **The handle's package is `read`, not `boot`.** `graphitron-model` compiles
  `boot/**` and `build/**` at `generate-sources`, before jOOQ codegen has produced a single table
  class, so nothing in `boot` may name a generated relation. Resolving a source to its graph reads
  `store_graph_source`, so `StoreHandle` and `SourceGraph` sit in `no.sikt.graphitron.model.read`
  and only the connection-minting half stays beside the store. The nested copy in
  `GraphitronMcpServer` is deleted rather than aliased, which is the whole point of moving it: two
  handles would be two conventions.
* **The multi-graph arm is named for the fact, not for the trouble.** `SourceGraph.Shared` carries
  every graph that read the file, ordered by name, because a schema file two modules both read is a
  member of both and both rows are true; calling it ambiguous would frame a correct store state as a
  defect. `Uncaptured` is the ordinary state of a file written since the last capture, and absence is
  its answer. `Scoped` carries the handle rather than the graph name, so the success arm is already
  the thing every query site needs and no site mints its own.
* **One read is one transaction, at H2's snapshot level, set at mint.** `read` is the reader's only
  door and it hands over a `DSLContext` for the duration of one call; no bare query surface is
  exposed beside it, because a handler holding one past the return is exactly the straddle the
  transaction removes. The level is stated rather than inherited, and the test that pins it is
  written to fail at read-committed: a whole capture committing between a read's first and second
  query is invisible to that read and visible to the next. The transaction ends in a rollback, since
  a reader has nothing to commit. Reads serialize on the single connection, which is the honest cost
  of one connection and not a pool waiting to be built; a second reader per thread is a mint away.

The in-memory fallback needed no special case, which is what makes it a full answer surface rather
than a degraded one: a named in-memory database takes a second connection like a file-backed one, so
a session whose cache directory was unusable still reads every row it captured.

Deliberately not in this increment: the LSP holds no reader yet. The mint and the scoping are the
substrate every capability query needs, and wiring a reader into `Workspace` before a capability
queries it would add a field with no reader.

## Settled while building: completion's first tranche

The four arms whose fact source is the classpath census moved together, and the unit is deliberate:
`ClassNameBinding`, `MethodNameBinding` and `ScalarTypeBinding` all read
`CompletionData.externalReferences()`, so migrating one and leaving the others would have had a class
name coming from the store and its own methods coming from the projection, two snapshots of one census
inside one popup. `NodeTypeBinding` joined them because it is a single graph-keyed read with nothing
in its way. What stays on the projection is the pair that renders Javadoc, `CatalogTableBinding` and
`CatalogColumnBinding`, which wait for the java-source family rather than shipping with a description
they cannot fill.

* **The two method providers collapsed into one.** The spec called for the narrowing and the ordering
  "stated in the view"; they are stated in the arm instead, and the view is not written. Two
  providers chained by dispatch order were expressing, in a provider list, a rule that belongs to the
  arm: under `@externalField` the offered set narrows to the lifter shape and falls back to the whole
  list when the class exposes none. One query either way, since the shape is a predicate over rows
  already fetched. No view, because hover's method arm has not migrated yet and the shared shape is
  not visible from one consumer; guessing it now is how a store accretes consumer-shaped columns. It
  becomes a view when the second reader lands and can say what it needs.
* **Provenance is a join, not a flag.** Class-name candidates rank reactor-resident ahead of
  jar-resident by joining `store_source.source_kind`, where the projection carried a `fromJar`
  boolean. The same census can now also answer which jar a class came from, which the boolean had
  flattened away. Two more differences fell out and both are improvements: an FQN reachable from both
  a jar and the reactor is one candidate at the better rank rather than two entries, and the order
  within a rank is by name rather than the walk's own sequence, which nothing could have explained to
  an author.
* **Absence has one shape.** No store, a URI naming no file on disk, and a document no graph of this
  session's has read all reach a handler as an empty handle. Three different absences, and the arm's
  answer to each is the same empty popup, so distinguishing them at the query site would be inventing
  a difference the author cannot see. A bare `Launcher` outside a build is the first of those, and it
  answered nothing catalog-driven before this item too.
* **The shared-file case is decided by the session, not by the store.** `SourceGraph` hands back both
  memberships; `StoreAccess` picks the session's own graph when it is one of them, and answers absent
  when it is not. That is the layer with something to go on: the request came from an editor with one
  project open. The acceptance case is met by a decision rather than by a row order.

Latency, measured against the new implementation alone as the item asks, and not on the Sakila fixture:
Sakila's own census is smaller than a real consumer's, so timing it would have measured the wrong
thing. At 5,000 classes with 40,000 methods and 80,000 parameters, one order of magnitude past Sakila,
p50/p95 per request came out at 4.6/9.5 ms for the class census (5,040 items), 2.2/6.8 ms for one
class's methods, 1.6/2.6 ms for the scalar constants and 1.2/2.0 ms for the node types. Nothing
materializes and no index is added: these are plain queries over base relations, and the class-census
figure is dominated by building 5,040 completion items rather than by the query, so the lever if it
ever matters is what the surface offers, not how the store is shaped. The catalog-shaped arms
(`sql_table`, `sql_column`, `sql_constraint`) are unmeasured because they have not migrated; they get
their own numbers when they do, which is also when a Sakila-scaled catalog is the right fixture.

## Settled while building: the java-source family

Four relations, keyed on the file: `java_file` carries the content stamp and the walked root, and
`java_class_declaration`, `java_method_declaration` and `java_field_declaration` hang off it. The
prefix sits beside `javac_` without either prefixing the other, so the census's exact match still
resolves; the roster's charter says which is which, since "what a parse read from authored sources"
and "what the compiler concluded about generated ones" are close enough in name to be worth
separating in prose.

* **The dimension is spelled `file`, not `source_name`.** Every other source-partitioned family
  leads with `source_name` and means a `store_source` row. A `.java` file is not one: `store_source`
  is a capture round's read set, and this file was read by neither the SDL walk nor the classpath
  scan. Giving the column the same name would have invited a join that can never match, `jvm_class`'s
  `source_name` being a classpath entry. The family's own file relation is also what keeps
  `store_source`'s kind taxonomy closed and its currency scan proportional to what capture reads.
* **`source_root` is the ownership scope, and that is the only reason it is a column.** A walk owns
  the files under the roots it walked: rows under those roots that the walk did not see are deletions
  and are pruned, rows under any other root belong to a walk this one knows nothing about. Without
  the column the prune would have been a path-prefix predicate, which is the same rule expressed as
  string manipulation.
* **Every overload is a row, and the tri-state is gone.** The declaration ordinal keys the overload
  where the classfile side uses a descriptor, so a consumer asking for a name gets as many rows as
  the class declares and the count is the resolution outcome. `SourceWalker.Index` needed
  `ambiguousMethods` as a side-set plus a never-dropped name-level view precisely because its keys
  could not hold the pair; the rows need neither. What the parse cannot know it does not claim:
  parameter types are unresolved names in an unattributed parse, so arity is the column and the types
  are `jvm_method_parameter`'s.
* **The two populations are allowed to disagree, and one place they do is constructors.** A
  constructor is a declaration and earns a row; `jvm_method` excludes them. The join is by name and
  outer on both sides, and no view asserts agreement, which is the same skew that exists today
  between the LSP index and the catalog, made visible instead of ambient.
* **Capture does not write this family, so `StoreRefresh` had to be told.** The writer runs on the
  source cadence and owns both halves of its own lifecycle, so a generator round has nothing to
  retain or rewrite here; the exemption is argued in beside the two source-partitioned families, and
  the reason is not symmetry with them but that a round which cleared it would blank every module's
  positions and Javadoc on a cadence that has nothing to do with sources changing.

What moved and what did not is worth being exact about, because the item calls for the walker to
relocate. The **walk's ownership** moved: `Workspace.refreshSourceIndex` is retired, the dev session
holds the walker, and the language server walks nothing. The **class** did not move packages, and
its `Index` did not retire, because the surfaces reading that projection have not migrated yet;
relocating the file while the LSP still reads what it returns would be a rename dressed as a
boundary. What did change inside it is the shape of its product: the parse's own output is now one
`ParsedFile` per source carrying a sealed `Declaration` per class, method and field, and `Index` is a
projection over that, which is why the walker no longer needs the projection type it was carrying
(`SourceWalker.Decl` still holds a `CompletionData.SourceLocation`, but only on the `Index` side of
that line, and it dies with it).

One walk feeds both sinks, deliberately. Walking twice would parse twice and, worse, let the store
and the index answer from two different reads of one file mid-edit, which is the tear the session owns
the walk to prevent. Positions are stored 1-based, the parse's own convention, and the editor-facing
0-based pair is the projection's conversion.

The doctrine rewrote as the item said it would, in the direction the item said: the location paragraph
now says the rule is cadence rather than storage, so a position may be stored as long as it is stored
on its own cadence, and the co-sourced-description sentence lost Javadoc as an example and gained it as
the counter-case, since Javadoc and the classfile census are two walks on two cadences. The
`jvm_class` header restates the division instead of pointing at a walker.

Deliberately not in this increment: nothing reads the family. It is substrate, on the same terms as
the two verdict relations, and the arms it unblocks (`CatalogTableBinding` and `CatalogColumnBinding`
in completion, every Javadoc-bearing hover row, and definition's positions) need the generated-class
FQN widening beside it for the jOOQ half, whose classes `jvm_class` excludes by design.

## Settled while building: the catalog-shaped completion arms

`CatalogTableBinding` and `CatalogColumnBinding` now read the store, which makes them the first
consumers of both the java-source family and the FQN capture. Each arm's query is its own: the table
arm selects a name and a description, the column arm a jOOQ field name, a binding type, nullability
and a description, and neither goes through a shared shape. That is the parity rule applied at the
grain it was stated for. What they share is the relations, not the query. (The column arm later
joined hover's on a shared reader, once a second consumer could say what it wanted from the same
rows; the table arms stayed apart, because hover's asks a different question of them.)

* **The Javadoc overlay is a join, and it is the join the FQN capture exists for.** `sql_table`'s
  captured `class_fqn` meets a `java_class_declaration` row for the table arm and a
  `java_field_declaration` row keyed by the generated field name for the column arm. Nothing else in
  the store could have reached those declarations: the generated package is outside `jvm_class` by
  design, which is exactly the argument the widening was landed on, now load-bearing rather than
  anticipated.
* **A correlated scalar select, not a left join.** `java_class_declaration` is keyed on
  `(file, class_name)`, so one FQN declared in two files is two rows and a left join would multiply a
  candidate into two popup entries. The subselect takes the file-order-first declaration: arbitrary,
  but stated, deterministic, and a property of a malformed source tree rather than of a catalog.
* **Description precedence is per relation and stays inverted between the two arms.** A table's
  database comment beats its generated class Javadoc, because that Javadoc is boilerplate naming the
  table back at the reader. A column's Javadoc beats its comment, because the generated field's
  Javadoc carries the qualified column name and, where the database has a comment, the comment too.
  The rule stayed in each provider rather than becoming a view: which text an editor renders is a
  presentation choice, and hover may legitimately want a different one, so a view asserting one
  precedence for both would have capped them at the same intersection the shared projection did.
* **The column census now answers with two facts the projection dropped.** The detail line is the
  type jOOQ binds the column to, which the projection carried under the name `graphqlType` and hover
  could not get at, and a column's database comment reaches a reader at all, which under
  `CompletionData` was always the empty string.
* **An ambiguous unqualified table name is answered, not resolved.** The census records every table
  every schema declares, and `sql_table`'s own comment says resolving an unqualified `@table(name:)`
  is a derivation. So the table arm offers a duplicated name twice and the column arm returns both
  tables' columns, where the projection answered from whichever table its list happened to hold
  first: the generated `Tables` class's field order, which is an accident rather than a rule. The
  candidate order is the census's own (name, then schema; definition ordinal within a table), which
  the projection could not state.
* **Which table a site's columns come from is still classification's answer.** The column arm reads
  the snapshot for the enclosing type's backing and the field's own classification, and only the
  column list is a store read. That split is the item's table, not a compromise: the resolution is a
  classifier decision, and pretending it were a fact would be the keying-axis confusion.

`CompletionRequest` lost its `sourceIndex` arm, which no provider reads any more. `Descriptions`
survived this pass for hover, which had not moved yet; it went with hover's last arm.

The tests moved to the real generated model rather than keeping their hand-built column lists. Both
arms are captured from the fixture module's jOOQ catalog through `StoreFixture.ofCatalog`, so a
fixture can no longer state a jOOQ field name the generator would not produce, and
`FixtureCatalogTest`'s two completion cases retire because the real-catalog coverage they existed for
is now what the per-arm tests do. `StoreFixture.withJavaSource` parses a declaration into the
`java_` family the way a dev session's watcher would, which is what lets a test pin the cross-cadence
join without the fixture database growing comments it does not have.

## Settled while building: the foreign-key completion arm

`CatalogFkBinding` reads `sql_referential_constraint` now, joined to `sql_constraint` for the
generated constant name. The table it reads around stays the snapshot's answer, same split as the
column arm.

* **The label switched namespace, from the generated constant to the SQL constraint name.** `key:`
  resolves two namespaces, the SQL name first and the `Keys` constant only if that finds nothing. The
  SQL name is what the manual teaches and every tutorial and directive-reference example spells, what
  a `NotInCatalog` rejection's candidate hint echoes, and what every constraint has: `jooq_name` is
  nullable by design, so the projection's `orElse(constraintName)` fallback meant the popup silently
  changed vocabulary mid-list depending on whether a `Keys` class resolved. One vocabulary now, the
  one the diagnostics already speak, with the constant in the item's documentation because it
  resolves too and an author reading generated code will recognise it.
* **Both directions come out of one query.** A self-referencing key satisfies both halves of the
  predicate and is a single row of the relation, so a union would have offered it twice. The
  projection needed an explicit skip of the table's own name in its inbound pass to get the same
  answer; here it falls out of the relation being the subject.
* **A colliding name is offered under a spelling that resolves.** A constraint name two schemas both
  declare is offered once per schema, qualified, because the `schema.` qualifier is grammar `key:`
  accepts and the resolver treats as stated intent; a name only one schema declares stays bare. Two
  keys of one name inside one schema (legal, constraint names are table-scoped) share every spelling
  an author has, so they collapse to one candidate whose detail names both joins. That is the same
  discipline as the table arm's duplicated name: state what the census holds, leave the resolution to
  the resolver, and never let census order decide silently.
* **Ordering is stateable.** Declaring schema, then constraint name. The projection's order was
  outbound-then-inbound within the generated `Tables` class's field order, which no reader could
  predict and no test could pin without encoding codegen's field layout.

`CompletionData.Reference` stays: hover, definition and diagnostics still read it, and each retires
its own reader when it moves. `StoreFixture` gained `ofMultiSchemaCatalog`, the multi-schema generated
model being the only fixture that can produce a name ambiguous across schemas and a name declared
twice within one.

## Settled while building: completion is done

The last two arms moved together, and with them `CompletionRequest` lost its `CompletionData` field:
every completion provider reads facts now. What is left beside the store on that record is the
snapshot, for the two arms whose table is a classifier decision.

* **`argMapping`'s left side offers every overload's parameter names.** A method is named by name
  alone in SDL, so which overload an author meant is not a question the census can answer; the
  projection resolved to whichever `CompletionData.Method` came first, which silently hid the other
  one's names. The union, deduplicated and in descriptor-then-position order, is the honest answer, and
  the shape is the same one `MethodCompletions` reads.
* **The class/method pair stopped being a resolved object.** `ArgMappingSupport` keeps the sibling
  coordinate derivation, which is syntax and shared, and now hands back the pair of names; what they
  refer to is the consumer's query. Diagnostics keeps the projection-resolving form until it moves,
  which is the strangler shape rather than a duplicated resolver.
* **The bundled-versus-user split in the argument-name fallback collapsed.** Capture parses
  graphitron's bundled `directives.graphqls` like any schema file, so one query over
  `graphql_directive_argument` answers for both, and `DirectiveResolution`'s three arms leave this
  surface (hover and diagnostics still use it). An asymmetry went with it: only the bundled arm
  descended into nested object literals, because the user-directive projection carried argument names
  and no input-object shapes. Nesting is now the same descent from `graphql_directive_argument`'s
  `named_type` down `graphql_field`, so an author's own input type nests exactly like
  `ReferenceElement` does. The kind join is what keeps the descent inside input objects, since
  `graphql_field` holds output fields in the same shape.
* **A session with no facts now completes nothing at all, the fallback included.** This is the one
  place the migration costs behaviour rather than recovering it: arg-name completion used to work
  before any capture, because it read the definitions the language server ships. Making the bundled
  vocabulary rows is the item's own rule, and the alternative is a second reader of one question, so
  the read went to the store. If pre-capture arg-name completion turns out to matter, the fix is to
  give the bundled vocabulary rows that no graph's capture owns, which is a modelling question for a
  sibling item and not a fallback inside this arm.
* **Two tests changed vehicle rather than subject.** `CompletionStoreWiringTest`'s
  "the absence costs exactly the arms whose subject is the store" case becomes "the absence costs
  every arm", and the dev server's round trip moved from a completion to a hover on a directive name,
  the arm that still renders the bundled docstring without reading facts. The bundled-shadows-snapshot
  precedence case retires from this arm: a redeclaration of a bundled directive loses at registry
  admission, where the loader's refusal is already covered, so there is nothing for the arm to decide.

## Settled while building: hover's Java-side arms

Hover's class-name and method arms read the store, which makes hover the first capability to be
served by two sources at once: these two answer from facts and the other nine still answer from the
projection. That is what the strangler window looks like inside one surface, and it is why the store
arrived as a parameter on `Hovers.compute` beside the catalog rather than replacing it.

* **The shared shape the completion tranche deferred is a reader, not a view.** That increment left
  the question open until a second consumer could say what it needed, and the answer is that the two
  consumers need the same *rows* and different everything else: completion offers every method a
  class has, hover names one and overlays a doc comment on it. What they genuinely share is the join
  between `jvm_method` and `jvm_method_parameter`, folding a one-to-many into a list, and spelling a
  signature the way a Java author reads one. The first is relational and the other two are not, and
  the third is presentation the store must never inherit, so the shape landed as an LSP-side reader
  (`ClasspathMethods`) that both arms call. A view would have had to carry the rendered signature to
  be worth anything to either of them.
* **Hover shows every overload, where the projection showed the first one it held.** The same
  recovery the arg-mapping arm made, for the same reason: SDL names a method by name alone, so which
  overload an author meant is not a question a census can answer, and picking one silently was the
  projection's list order rather than a rule. Each signature carries the doc comment of the
  declaration with its arity.
* **Arity is the only join the two populations admit.** `java_method_declaration` counts parameters
  because an unattributed parse reads types as written, and `jvm_method` spells an erased descriptor,
  so the count is their entire common ground; the family's own charter says as much. Two same-arity
  overloads therefore share one comment, the first in file-then-declaration order. The projection's
  index keyed the same way and had the same collapse, so this is parity rather than a new limit, but
  it is now a stated tiebreak rather than a map that silently kept one entry.
* **Presence and description are one query on the class arm.** A `jvm_class` row inside the graph's
  read set is what makes the FQN resolvable, and the doc comment hangs off it as a correlated select
  into `java_class_declaration`, which is the same subselect-not-join argument the table completion
  arm landed on: one FQN declared in two files is two rows, and a join would multiply the answer.
* **A class the graph never walked hovers as unknown, and that is now testable.** The census is
  per-graph, so the fall-through to the coordinate's SDL docstring is what a sibling module's class
  gets, not just an unresolvable one. The projection had one workspace-wide list and could not tell
  the two apart.
* **The source-cadence test asserts what it used to get for free.** Its property was that hover and
  goto-definition read the *same* index, so they could not disagree. Hover reads the store now and
  definition still reads the index, so the case writes one file, refreshes both readers, and asserts
  the doc comment and the position move together off one edit. That is the property that actually
  matters, and pinning it during the window is worth more than it was when one index made it trivial.
* **`Descriptions` lost its class and method overlays**, having no caller left; the table and column
  pair and the class-Javadoc lookup the declaration-name hover shares stay until those arms move.

## Settled while building: hover's catalog arms

The table, column, key and node arms read the store, which finishes hover's coordinate dispatch:
every arm keyed on a `Behavior` now answers from facts. What is left on the projection is the
classification snapshot, which answers *which* table a column site belongs to, and the
declaration-name arm around the dispatch.

* **Two more shared readers, on the same argument the method reader landed on.** `CatalogColumns`
  and `CatalogKeys` are the relations plus the order rows come back in, and nothing else: each
  surface keeps its own filter, its own description precedence, and its own rendering. The key
  reader is the clearest case for a reader over a view, because the two surfaces come at the same
  rows from opposite ends: completion has a table and no name, hover has a name and no table.
* **Hover answers the spellings the resolver answers.** A key's SQL constraint name, that name
  qualified by its schema, and the generated `Keys` constant all resolve in the build, and the
  qualifier binds hard rather than widening the set. The projection matched the constant alone and
  case-sensitively, so hovering the spelling the manual teaches and the completion arm offers
  produced nothing. A column is the same story with two names instead of three: the census carries
  the SQL name and the jOOQ name, and either one now resolves it.
* **A column renders both its types.** The census carries the SQL type the database declares and the
  Java type jOOQ binds it to, and the DDL says outright that hover is why a column needs both. The
  projection carried only the second, under a name that called it a GraphQL type.
* **An ambiguous name is answered, not resolved, on every arm that can hit one.** A table name two
  schemas declare hovers as both, a constraint name two schemas declare hovers as both with the
  schema that tells them apart, and a column of an ambiguous table does the same. Consistent with
  the completion arms and with `sql_table`'s own charter, which calls resolving an unqualified name
  a derivation.
* **A node's key columns are typed from the node's own table.** The projection looked a key column up
  across every table in the catalog and took the first hit, which for a name as common as `id`
  answered from whichever table came first. The table is `@table`'s argument as written, with the
  type-name fallback the generator applies, and a key column is a column of that table or of nothing.
* **The counts are correlated subselects, not fetched lists.** A table hover says how many columns
  and how many keys touch it, which is a count and not a listing, so the query counts. The key count
  is not scoped to the table's own generated package: a key declared in another package against this
  table is still one that touches it.
* **The column arm takes the store as an option, not behind a `flatMap`.** Its record- and POJO-backed
  sites answer from the classification snapshot's member slots, so putting the whole arm behind a
  store would have silenced an arm that reads no census. What each arm requires is now visible in how
  it takes the handle.
* **Noted, not fixed: the FK diagnostic accepts a narrower set than the build.**
  `Diagnostics.validateCatalogFk` checks the value against `CompletionData.Reference.keyName()`,
  which holds the generated constant whenever a `Keys` class resolves, so a plain SQL constraint name
  is flagged even though the generator resolves it first and the completion arm offers it. It is the
  diagnostics capability's to fix when that arm reads `CatalogKeys`, and the fix falls out of the
  migration rather than needing a separate decision.

## Settled while building: hover's docstring arms

The three arms that render prose rather than a binding, the directive's own name token, any coordinate
no richer arm answered, and an argument's name, read the captured SDL now. With them the bundled
directive vocabulary stops being a source: `LspVocabulary` still resolves a cursor to a coordinate
from its parsed registry, and no longer answers what a coordinate means.

* **Three rows, one read, because the coordinate is the key.** `SdlDescriptions.of(store, coord)`
  switches over the sealed `SchemaCoordinate` family and each arm's key is exactly its relation's
  primary key, so every lookup is one row and absence is the answer. The three hover arms differ only
  in which coordinate they hand over and which node they highlight. A fifth coordinate arm would fail
  to compile until it named the relation that describes it, which is the same enforcement the
  behaviour dispatch gets.
* **`DirectiveResolution` leaves hover, and its bundled-versus-user fork with it.** The incumbent
  resolved a directive name to a bundled definition, a projected user shape, or unknown, and hover
  had three arms behind that. One relation holds both populations, so the fork had nothing left to
  decide: an author's own directive hovers through the same query graphitron's do. Diagnostics is the
  last reader of that type. `Workspace.resolveDirective`, a convenience wrapper with no caller left,
  went at the same time.
* **A bundled argument's name now answers.** The incumbent's arg-name arm was gated on the user-shaped
  projection, so hovering `typeName:` said nothing while hovering the value beside it resolved the
  node. The gate was there to stop a snapshot's shadow `@table` describing an argument the bundled
  definition has none of, and the store has no shadow to prefer against: a redeclaration of a bundled
  directive loses at registry admission before capture sees it. So the precedence rule retires and the
  arm widens, which is one behaviour recovered by deleting a rule rather than by writing one.
* **Nested argument names stay unanswered, as they were.** Hovering `className:` inside
  `@service(service: {...})` needs the enclosing input type, which the coordinate walk derives for
  value positions only. The relation is there (`graphql_field` describes an input field, and the
  docstring arm already reads it when the cursor is in the value), so this is a walk to extend rather
  than a fact to model, and it belongs with whatever else moves the coordinate walk.
* **The input-type coordinate is answered though nothing triggers it.** `SchemaCoordinate.InputType` is
  in the sealed family and no cursor position produces it, the walk keying a cursor as a directive
  argument or an input field. The arm reads `graphql_type.description` rather than returning empty,
  because what a named type's description is has an answer whether or not a trigger asks; the reader's
  own test covers it, since no hover case can.
* **A named type is found by name, without a kind check.** `ArgNameCompletions` joins `graphql_type`
  for `kind = 'INPUT_OBJECT'` because its descent must stop at a type with no input fields. A
  description lookup has no such stop: one GraphQL name is one type per graph whatever kind it is, so
  the kind join would only be able to suppress a description that is correct.
* **The cost is stated: no capture, no docstring.** This is the same charge the argument-name
  completion arm paid, and now hover pays it too. The bundled definitions are rows, so a session that
  has captured nothing renders no prose either; a case pins both directions so the loss is a decision
  on the record rather than a surprise. What still answers without a store is the member-slot hover,
  whose subject is the classification snapshot.
* **The freshness case moved rather than retired.** "A stale snapshot still hovers" was pinned on the
  user-directive arm, which no longer reads the snapshot. The property is real and now sits on the
  column arm, the one arm still asking classification which table a site belongs to, where a
  `Built.Previous` snapshot resolves the table exactly as a current one does.
* **The dev server's round trip changed vehicle again.** It moved to a directive-name hover when
  completion went store-side, on the grounds that the arm read no facts; it now reads facts too. The
  request is goto-definition on an intra-schema type reference, which resolves inside the open buffers
  themselves, so the case stays about the socket and the handler. That provider is on the list, so the
  vehicle will have to move once more, and by then the test should stand up a store rather than keep
  hunting for an arm that needs none.

## Settled while building: hover's last arm, the declaration name

Hover's last unmigrated arm fires when the cursor sits on an SDL declaration's own name, a type name or
a field name, rather than inside a directive. It renders two things: a **classification block**, which
is what the classifier decided about that declaration, and beneath it an **overlay**, which is the
prose written about whatever Java or database object the declaration binds to. The classification block
was already the snapshot's answer and stays there. The overlay is what moved, so every hover arm now
reads the fact store and `Hovers.compute` takes no source index at all. What the arm still reads off the
projection is the classification snapshot and the catalog, and the catalog only to work out which
declaration the cursor's coordinate binds to, a resolution it shares with goto-definition.

Two readers of Java sources coexist during the migration, and the rest of this section turns on the
difference. The **source index** is the LSP's own in-memory parse of the workspace's `.java` files, and
goto-definition still uses it to find a declaration's position. The store's **java-source family** is
the same material as relations, and hover now uses it to find a declaration's doc comment. One walk
feeds both.

* **Parity between hover and goto is a narrower claim now, and it is stated where it is asserted.**
  Both arms resolve through one shared `DeclTarget`, and while both then read the index, "they cannot
  disagree" was structural. It cannot mean that once the reads differ. What survives is worth keeping:
  both switch exhaustively over the same resolved target, so a new backing permit breaks both at
  compile time, and one walk feeds the index and the java-source family alike, so the two cannot
  disagree about a declaration's text either. What differs is the question each asks of it, a position
  or a doc comment.
* **`DeclTarget`'s catalog arms carry names now, not projection rows.** `CatalogTable` held a whole
  `CompletionData.Table` and `CatalogColumn` held a table and a column, so a description was sitting
  right there for the overlay to read instead of querying. Narrowing them to `(tableName, classFqn)`
  and `(tableName, classFqn, columnName)` makes the store read the only route to description text, and
  it takes a projection type out of a sealed type in the parsing layer. The type's own javadoc already
  claimed its variants name a declaration and nothing else; now they do.
* **A record component jumps but has no doc comment to overlay.** Found by rewriting the overlay's
  fixture against a real parse instead of a hand-built index. The walk reads a record's component as a
  field declaration and positions it, so goto jumps, but a doc comment written in the record's header
  is not retained for that declaration, so there is nothing to overlay. The incumbent had the same gap;
  a fixture asserting a component Javadoc no parse produces was hiding it. Pinned as a case rather than
  fixed, because it is a property of the parse and not of either surface.
* **`Descriptions` retires.** Its whole job was layering an index Javadoc onto a catalog fallback, and
  each of its three methods had exactly one caller left, all in this arm. The precedence it centralised
  stays per surface, which is where the coordinate hover and the completion popup already keep it: a
  table's database comment beats its generated class Javadoc, a column's Javadoc beats its comment, and
  which text wins is a rendering choice rather than a fact.
* **The java-source family gets one reader, `SourceDeclarations`.** `Hovers` held a class-Javadoc
  lookup and an arity-keyed method lookup, `TableCompletions` held a copy of the first, and
  `CatalogColumns` held the field equivalent. Two shapes, because two kinds of caller need it: a
  correlated subselect for a query that already carries a class name on its own side, a direct lookup
  for a caller holding plain names. The family is keyed on a file rather than on a source membership, so
  it takes no graph scope, and the file-order tie-break for a name two files declare is stated once
  instead of three times.
* **The method overlay resolves in two tiers, the same two goto's does.** SDL names a method by name
  alone, so the arity a consumer holds is itself a resolution rather than a fact. `methodJavadoc`
  prefers the overload declaring that arity and falls back to any declaration of the name; declining on
  a guessed arity would lose a comment the source plainly carries. The index has always resolved in
  those two tiers, and the query mirrors them rather than sharing them.
* **A table's overlay answers for one schema, where the coordinate hover reports every match.** The
  block above the overlay has already named one table, and an overlay is a paragraph rather than a
  list, so a name two schemas both declare resolves in schema order here. The coordinate hover, whose
  whole subject is the table, still lists both. Same relation, two renderings, per surface again.
* **The two readers are asserted together end-to-end, not simulated.**
  `SourceCadenceHoverAndDefinition` gained a declaration-name case: one parse of one file, hover
  overlaying out of the store while goto jumps off the index, on a type name that is the only handle
  either surface has. That is where the property belongs while both readers exist, and the case keeps
  passing unchanged once definition reads the store too.

## Settled while building: goto-definition's positions

Goto-definition answers "where is this declared". Three providers share the request, keyed on
disjoint syntax: one for a cursor inside a directive argument (`@table(name:)`, `@service(method:)`
and the rest), one for a cursor on an SDL declaration name, and one for a cursor on a reference to
another SDL type. The first two jump into the consumer's Java tree, and this increment is theirs. The
third jumps within the schema and moves with the SDL families.

Every such jump answers two questions from two populations on two cadences. Whether a name is a
reference at all is the classpath census's answer, which the catalog projection still carries and
each provider guards on; that guard is what keeps an unknown name an empty answer rather than a
"declared nowhere" one. Where its declaration sits is a `.java` parse's answer, and that half is what
moved. It used to come from the source index, an in-memory map of positions the language server kept
beside the store; it now comes from the store's java-source family, the same rows hover reads doc
comments out of.

* **Both providers moved in one commit, because they share the join.** The directive-argument arm and
  the declaration-name arm do not merely resemble each other, they call the same three helpers
  (`classTarget`, `fieldTarget`, and a method resolution). Moving one would have meant either
  parameterising those helpers over two substrates or duplicating them, so the substrate changes once
  underneath and both arms follow. What is left in the `definition` package that still reads a
  projection is the census guard, on the same footing as everywhere else.
* **A relation needs no ambiguity set.** The index could not hold two same-arity overloads under one
  key, so it dropped the pair into `ambiguousMethods` and kept a never-dropped name-level view to fall
  back to. The family holds both declarations under their own ordinals, so the arity tier resolves and
  the first of the pair wins the slot. That whole tri-state, and the second map that existed to
  recover from it, is not a thing the store's reader has to model.
* **The two tiers that remain are the census disagreeing with the source, and they are tried in that
  order.** An arity a consumer holds is itself a resolution, so the source may declare the name at
  some other arity; jumping to the declaration beats declining. The census arities are therefore all
  tried against the declared ones before any fallback runs, not one at a time through it: a
  per-arity fallback answers the first census overload with some other overload's position while a
  later census arity matches exactly. The fixture now has a method overloaded across two arities and
  a census that names one of them, so that ordering is falsifiable rather than asserted in a comment.
* **`DefinitionTarget.Located` carries the editor's own coordinates.** It used to carry a
  `CompletionData.SourceLocation`, so the projection this item deletes lived on inside its own
  replacement, and the 1-based-to-0-based conversion sat on the projection side. The store keeps the
  parse's convention, as its schema says it does, and the LSP's own reader converts at its edge. One
  consequence worth stating: a declaration the parse could not position now reads as absent, so it
  lands on the logged no-jump arm instead of silently producing an empty location.
* **Hover and goto are structurally parallel again, and the parity test says so in both halves.** They
  switch exhaustively over one resolved target, so a new backing permit breaks both at compile time,
  and they now read one row of one family, so neither can answer about a state of the source the other
  has not seen. The one asymmetry left is the one the family's shape implies: goto jumps for every
  declaration the parse positioned, hover overlays only those it read a doc comment for. A record
  component is the case that separates them, and it is pinned.
* **Every position asserted in a definition test is a parse's now.** Both provider tests were built on
  hand-written index maps, which is how they came to assert a class declaration at line 0 (no file
  that opens with a package declaration has one) and a record component's doc comment (no parse
  retains one). They write real `.java` files and read the positions back, which is the same
  correction the hover overlay's fixture took an increment earlier, for the same reason: a fabricated
  substrate can assert a declaration the parse would never produce.
* **The index survives for one consumer, and it is not the language server.** `graphitron-mcp`'s code
  tools read it through `Workspace.sourceIndex()`, so the projection and its setter stay until the
  sibling item repoints them. Nothing in the LSP calls it, and the dev goal's watcher already writes
  the family and the index from one walk, so the two cannot disagree while both exist.
* **A definition request now needs the buffer to name a captured source, exactly as hover does.** The
  request opens one read transaction around the whole chain, so a fall-through cannot decline on a
  declaration an earlier read positioned; and a session nobody handed store access to jumps nowhere,
  declining once at the top rather than per arm. Both are the shape hover took, and the round-trip
  test now opens its document under the captured file's URI for the same reason hover's does.

## Settled while building: goto-definition's last arm, the intra-schema jump

The third provider is the one whose cursor sits on a reference to another SDL type, and it jumps
within the schema rather than into the Java tree. It is also the only migration in this item where the
projection was never the authoritative reader. A type being edited resolves against the open buffer's
own tree-sitter parse, which is live in a way no capture can be; the projection served the case where
the declaring file is on disk and in no buffer. So this increment retires a fallback rather than a
reader, and what replaces it is `SdlDeclarations`, the SDL sibling of the java-source family's
`SourceDeclarations`, asking the same question of a schema file.

* **The reduction the projection did becomes an ordering.** The projection held one entry per type
  name, reduced out of the registry before the language server saw it. The relation holds every
  declaration site a type has, base and all five live extension kinds, each with the `merge_ordinal`
  capture assigned it. So "jump to the definition, not to an extension of it" stops being a property
  of which registry map the reducer read last and becomes the query's own `order by`, matching what
  the buffer scan has always done. The fixture writes the extension above the base, so document order
  and merge order disagree and the ordering is falsifiable.
* **Whether a declaration can be opened is a property of its source name, not a list of names.** The
  projection dropped two populations before emitting: definitions whose source name was null, and
  definitions from graphitron's bundled directive file, the latter by comparing against a constant.
  The store's answer needs no list. Capture writes a schema file's `source_name` as the absolute
  normalized path it read, which is the convention `StoreAccess.sourceNameOf` reads in the other
  direction, so a source name that is not an absolute path names something no editor can open. One
  predicate covers the bundled directive definitions, a programmatic caller's SDL label, and the
  source name the `@link` tag synthesiser stamps. The middle case is one the projection got wrong: a
  label has a non-null source name, so it would have handed the editor a `file://` URI built from a
  bare word.
* **A site nothing can open is skipped, not a refusal of the type.** Because the sites are ordered,
  the fallback takes the first one that yields a location rather than the first one at all. A type
  whose base definition arrived under a label and whose extension sits in a file still jumps, to the
  extension. The unopenable sites stay perfectly readable facts for every other question; they are
  simply nowhere to jump.
* **The fallback's position is coarser than the buffer's, and that is the grain rather than a
  placeholder.** A site is where the declaration starts, at its `type` keyword; the name span is a
  parse's answer and the arm that has a parse returns one. The incumbent's javadoc explained the same
  coarseness by contrast with "the `0:0` placeholder the jOOQ path returns", which stopped being true
  an increment ago, so the comparison went with it.
* **Two behaviours changed at the edges, and both are the graph scope arriving.** The fallback answers
  from the graph the cursor's own document belongs to, where the projection answered from whatever the
  last build produced for the session. And a buffer whose file no capture has seen keeps its
  workspace-wide buffer scan but loses the on-disk fallback, which is the same trade the Java-side
  arms took.
* **The store is optional in this provider for a different reason than in its siblings.** They have
  nothing to say without one and decline at the top; this one's authoritative arm is the buffer, so a
  session started outside a build still resolves every reference the workspace declares. That
  asymmetry is now a named case rather than a thing a reader has to infer from where the `flatMap`
  sits.

## Settled while building: code actions, and what a fix cannot borrow from a diagnostic

Code actions have two branches that deliberately share no path. `SdlActions` re-scans each open
document through a detector and needs nothing but buffers, so it was already store-free and is
untouched. `LintQuickFixes` is the other one: it offers the correction a lint rule worked out
build-side, and it read that correction off the in-memory `ValidationReport`. It reads rows now, and
with `SdlActions`' registry empty that finishes the capability.

This is the first capability whose migration needed the store to hold something new. A finding was
already a row; the fix it carries was not, so `lint_finding_fix` and its ordered
`lint_finding_fix_edit` child landed with it. The interesting part is not the two relations, which are
the DDL's standing parent-plus-ordered-child shape, but what asking the question store-side exposed
about the incumbent's safety gate.

* **A fix is not a diagnostic, and the difference is re-anchoring.** The item's diagnostics plan
  promises a stale buffer its last captured findings, re-anchored through its live tree where the text
  has moved. That works because a diagnostic points at a declaration, which a tree can be asked about
  again. An edit points at a span of text. Nothing can re-anchor `[5:16, 5:22)`, so the two arms need
  different answers to the same staleness question, and the fix arm's answer has to be a refusal.
* **The incumbent's gate passed exactly when it should have refused.** It offered fixes only under
  `Built.Current`, the snapshot's freshness. But a snapshot stays `Current` through every keystroke
  after the build that produced it, and goes `Previous` only when a later parse fails. So the gate
  fired on a schema the author had just broken, which is when the ranges are still the ones the last
  successful build computed, and passed on a buffer edited without breaking anything, which is exactly
  when the ranges have moved. It was answering a question about the build where the risk is a question
  about one document's text.
* **The store already held the right question's answer.** `store_source.stamp` is the content hash
  capture recorded for each schema file, and comparing it against the bytes the workspace holds asks
  precisely whether this buffer is the text the rule read. It is per file, which is the grain that
  matters, since an edit is addressed by its own file's text whatever the other buffers say. It
  compares content and not save state, so an unsaved buffer identical to the captured file is served.
  And its failure directions are safe: an unstamped source, an uncaptured file and a session with no
  store all decline.
* **The stamp's algorithm needed one home once a second party computed it.** Capture hashed files
  through a private helper in its classpath-sources walk. A reader comparing text against a recorded
  stamp has to produce the same hash, and two spellings of one hash agree until one of them changes,
  with nothing to notice. `SourceStamp` is now the column's home: the hash of a file, the hash of
  bytes in hand, and the comparison against what the store recorded.
* **The two file spellings meet in one place.** The diagnostics families carry the canonical URI their
  union view renders, while `store_source` is keyed by the path capture read. The reader takes the
  editor's URI and renders both, rather than a caller holding two spellings and picking one per query,
  which is how they drift.
* **The fix is deliberately not on the `diagnostic` view.** That surface is single-valued at one row
  per diagnostic, and a fix is a list of edits. A reader wanting one joins the two relations on the
  finding it is offered for, which also keeps the MCP diagnostics tools' output unchanged by this
  increment.

What this leaves is a clean line. Every capability that does not need a classification substrate now
reads the store: completion, hover, goto-definition, code actions. What remains is that substrate and
the four arms the inventory marks as reading "classification", plus diagnostics, whose own migration
carries the cadence change and `DirectiveResolution`'s deletion.

## Settled while building: the substrate, named view by view

The substrate is what the inventory hid behind the word "classification", and the first thing it owed
was a name per row. Here they are, so the remaining arms are a build order rather than an open
question. None of these is a new family: the resolutions live in `intent_`, beside the claim views
that already ask them.

| What an arm needs | The view that answers it | State |
|---|---|---|
| How a written table name meets the census | `intent_spelled_table`, keyed on the spelling, under every binding | built |
| Which catalog table a type's `@table` binds to | `intent_bound_table`, a keying of the spelling view on a type | built |
| What an authored `@reference` element lands on | `intent_field_reference_step_hop` for the element's local resolution, `intent_field_reference_step_target` for the chain over it | built |
| Which table a *field site's* columns come from | `intent_field_column_table`: the parent's binding for a plain column, the chain's terminal arrival for a `@reference` field, the navigated element table for the order-by sites | unbuilt |
| What an *omitted* `@reference` path infers | foreign-key discovery between the parent's binding and the field's named type's binding; both bindings are built, the discovery is not | unbuilt |
| Which Java class a type is backed by, and its member slots | `intent_type_backing_class`, joined to `jvm_record_component` for components and `jvm_method` for bean accessors | unbuilt |
| The verdict label for a declaration | `intent_resolved_field_claim` and a type-grain sibling, their `classifier` vocabularies grown from today's seven and two to the whole taxonomy | partly built |

Two things fall out of writing that down. The label rows do not want a new view at all: they want the
reduction that already exists to answer for every classifier, so what looked like the substrate's
biggest unknown is arms in an existing view rather than a relation nobody has designed. And the column
rows are not one question but three, which is why the column arm could not move with the key arm
despite the inventory giving them the same words: the key arm needs the enclosing type's binding and
nothing else, and it moved.

* **A CTE with a second reader is a relation.** The binding resolution was written once already, inside
  `intent_column_match_claim`, because the column classifier asks it on the way to a claim. The
  language server asks the same question with no claim in view. Extracting it is not tidying: the
  alternative was the reader re-spelling a resolution with a qualifier split, a case-insensitive
  fallback and a membership scope in it, and two spellings agree exactly until one changes. The claim
  view reads the view now, so there is one.
* **The binding names a table by its whole key.** Three columns, the `sql_table` key, not a name. The
  incumbent's answer was a bare table name, which is all a classifier's `tableName` slot ever held, so
  every reader downstream had to match it case-insensitively across every schema and hope. With the key
  in hand the key census is filtered on all three columns, and a same-named table in another schema
  stops being a looser match on this one.
* **Ambiguity is rows plus an arity.** Two candidate tables are two rows, each saying there were two.
  That single decision is what lets the classifier keep transcribing the walk's `Ambiguous` verdict
  (join at `candidates = 1`) while the editor offers both, off the same rows, with neither re-deriving
  the resolution. An arity computed by whoever counted first is the thing a column exists to prevent.
* **The incumbent's own test asserted an answer the build cannot produce.** The FK arm's multi-schema
  case paired a real census with a hand-built `TypeClassification.Table("event")`, and `event` is
  declared in two schemas, which is exactly the classifier's `Ambiguous` verdict and therefore no
  binding at all. So the case that documented the arm offering both schemas' keys described behaviour
  the arm never had: a real snapshot would have carried nothing for the type and the popup would have
  been empty. Capturing the binding is what makes the fixture unable to lie, and the behaviour the test
  claimed is now the behaviour it gets.
* **The root mask has one home.** `@table` on `Query` binds nothing, the walk classifying a root before
  it reads a table reference. The mask sat on the column classifier's field grain; it belongs on the
  resolution, where every reader inherits it, and the classifier's own copy went with the move.
* **What is left on the projection here is one arm, and deliberately.** `TypeContext.tableNameOf`
  survives with a single caller, goto-definition's column arm, whose next hop is the same projection
  for the table's generated class. Pointing its first hop at the store would leave one arm reading both
  models, so it moves when the definition capability's catalog arms do, which is its own inventory row
  and not this one.

## Settled while building: the reference chain, and an increment with no reader

The second substrate increment builds the spelling resolution and the `@reference` chain over it,
three views, and retires nothing. That is a deviation from this item's own rule that each increment
deletes an incumbent reader in the same commit, so it is stated rather than glossed: no single
substrate view retires an arm, because the arms that would read these views read a *dispatch* that
collapses seven questions onto one switch, and the switch cannot half-move without leaving one arm
reading both models. The pin is a derive-tier anchor instead, which is the same shape
`intent_authored_claim_conflict` shipped under. What makes that acceptable here and not in general is
that the views are provably equal to the walk on the case set that matters, against the real catalog;
what would make it unacceptable is a third increment in a row with no reader.

* **A resolution keyed on a string sits under the ones keyed on a coordinate.** The binding view knew
  how a qualifier splits, how an unqualified name matches, and which catalog sources are in scope. None
  of that varies by coordinate: a `@reference` element's `table:`, its argument-site and `@referenceFor`
  siblings, and `@mutation`'s delete target all name a table by the same rule. So the rule moved down
  into `intent_spelled_table`, keyed on the spelling itself, and the binding view became a keying over
  it. This is the previous increment's argument applied one level deeper, and it is what stopped the
  chain's table arm from becoming the third copy of a qualifier split.
* **The population of a relation keyed on a string is the strings someone wrote.** Not every table the
  census holds, and not the subset one site happens to spell. Five relations carry a table name today
  and all five are in the union, so a spelling reaching resolution never depends on which site wrote it.
* **Only the chain needs recursion, so only the chain is recursive.** An element's own resolution is
  local; the chain is sequential because an element departs from where the previous one arrived. Two
  views: the hop view enumerates every table-to-table hop an element could express, both orientations
  of its key included, and the chain walks them from the type's binding. Mixing them would have put a
  copy of every element arm inside the recursive term, which is where a four-branch recursive CTE and
  the H2 support question came from before the split. After it, the recursive term is one join.
* **Two arities, because the case that separates them is real.** `film` declares two foreign keys to
  `language`, so a `{table: "language"}` element reaches one destination by two routes. `targets` and
  `candidates` are therefore separate columns: a reader that needs the table can trust the answer, a
  reader that has to render the join cannot, and the walk's own "which foreign key did you mean"
  rejection is the second count. One arity column would have made every such element look ambiguous
  to both readers.
* **A self-referential key is one hop, not two.** Both orientations land on the same table, so the
  cardinality hint the walk uses there chooses join columns rather than a destination. Emitting both
  rows would have made an unambiguous destination fail every `targets = 1` gate, which is the sort of
  defect an arity column invites if the rows feeding it are not thought through. The probe caught it
  before the DDL landed; the fixture pins it now.
* **Two silences, and the view says which it owns.** An element that resolves to nothing ends the
  chain, so a path whose second element is fine and whose first names an unknown key contributes no
  rows at all. An element carrying neither key nor table is a different silence: its destination comes
  from a condition method's Java return type. Absence here means "not reached", never "resolves to
  nothing in particular", and a view whose absence is load-bearing owes that sentence in its comment.
* **The next increment is the one that retires.** `intent_field_column_table` is now one view away from
  answerable across six of the column dispatch's seven `Resolve` arms: the parent's binding and the
  chain's terminal arrival are built, the navigated element table is the same binding view on the
  field's named type, and only the multi-table participant arm is missing. Its `Silent` versus
  `FallThrough` split is the verdict vocabulary, which is the label rows' dependency, so those two
  pieces of the substrate finish together and retire three readers at once: `FieldCompletions`,
  hover's column arm and the field-member diagnostic.

## Retired vocabulary

Provisional until the cutover lands; the Done-gate sweep greps for these. `CompletionData`,
`CatalogFacts`, `LspSchemaSnapshot`, the `Built.Current` / `Built.Previous` freshness seal,
`typeDefinitionLocations`, `CatalogBuilder`'s projection pass, `DevMojo`'s keep-previous-and-demote
path (`demoteSnapshot`, `markAllForRecalculation`), `refreshTypeIndex`, `declaredTypes` and
`dependsOnDeclarations`, `SourceWalker.Index` with its `ambiguousMethods` and `methodsByName`, and
`Workspace`'s `sourceIndex` / `setSourceIndex` / `refreshSourceIndex`. Gone already:
`LspVocabulary.descriptionOf`, `Workspace.resolveDirective`, the whole of `Descriptions`
(`ofTable`, `ofColumn`, `classJavadoc`) and `Definitions.methodLocation`; `DirectiveResolution`
follows when diagnostics moves, and the source index when the MCP code tools stop reading it, no
language-server surface having asked it anything since goto-definition's positions moved.
`typeDefinitionLocations` is in the same position, its last reader being the MCP schema view: goto's
intra-schema arm was the language server's only one, and it reads the declaration sites now. The
`Built.Current` / `Built.Previous` seal has one language-server reader left, the diagnostics replay,
the code-action branch having stopped asking the snapshot about freshness and started asking the store
about this document's text.
