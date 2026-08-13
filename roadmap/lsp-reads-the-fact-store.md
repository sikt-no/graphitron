---
id: R638
title: "The LSP is a fact-store client"
status: Spec
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-13
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

Every fact source below must be a relation or a view. Four rows say "classification", which is not
one: it names `FieldClassification` and `TypeClassification`, the Java projections retired above,
and leaving the word there is how a port smuggles them back in. They resolve against the
claim stratum (`intent_resolved_field_claim` and its siblings; see
`docs/architecture/explanation/fact-model.adoc`), and pinning down which view answers which row is
the first thing the substrate work settles. Which view, not how many queries: four rows projecting
four different things off one view is a fine outcome, and collapsing them because they share a
source would be the same error as sharing a type because they share a subject.

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
| `MethodNameBinding` | `ExternalFieldCompletions`, then `MethodCompletions` | `jvm_method`, `jvm_method_parameter` |
| `CatalogTableBinding` | `TableCompletions` | `sql_table` |
| `CatalogColumnBinding` | `FieldCompletions` | `sql_column`; enclosing table via classification |
| `CatalogFkBinding` | `ReferenceCompletions` | `sql_constraint`, `sql_referential_constraint` (needs `jooq_name`) |
| `ArgMappingBinding` | `ArgMappingCompletions` | `jvm_method_parameter` × `graphql_argument` |
| `ScalarTypeBinding` | `ScalarTypeCompletions` | `jvm_scalar_type_field` |
| `NodeTypeBinding` | `NodeTypeCompletions` | `graphitron_node` |
| no coordinate, or no value match | `ArgNameCompletions` (fallback) | `graphql_directive_argument` |

**Hover.** `Hovers` dispatches on the same `Behavior` taxonomy, with three non-coordinate arms
around it.

| Trigger | Answers | Fact source |
|---|---|---|
| Directive name token | Directive description | `graphql_directive` |
| `ClassNameBinding` | Class FQN + Javadoc | `jvm_class`; Javadoc via the `java_` source family |
| `MethodNameBinding` | Signature + Javadoc | `jvm_method`, `jvm_method_parameter`; Javadoc via the `java_` source family |
| `CatalogTableBinding` | Comment, column and reference counts | `sql_table`, `sql_column`, `sql_constraint` |
| `CatalogColumnBinding` | Column type, nullability, comment; member name and type when the backing is a record or POJO | `sql_column` (needs binding type); `jvm_record_component`, `jvm_method` for the member arms |
| `CatalogFkBinding` | FK direction and endpoints | `sql_referential_constraint` |
| `NodeTypeBinding` | `typeId`, key columns and their types | `graphitron_node`, `graphitron_node_key_column` |
| `ArgMappingBinding`, `ScalarTypeBinding` † | nothing | — |
| Any coordinate, no richer arm | SDL docstring | `graphql_directive`, `graphql_directive_argument` |
| User-declared directive arg | Arg docstring | `graphql_directive_argument` |
| SDL declaration name (`hoverClassification` toggle) | `DeclarationHovers`: classification block + Javadoc | classification + the `java_` source family |

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
| `classification` | `collectClassificationHints` | classification |
| `inferredDirectives` | `collectInferredDirectiveHints`, renderers for `@table`, `@field`, `@reference` | `graphitron_table`, `graphitron_field_binding`, `graphitron_field_reference*` |
| `inferredDirectives` | `collectAbsentDirectiveHints`, a second pass inside the inferred-directive collector | same, absence arm |
| `hoverClassification` | gates `DeclarationHovers` (see hover) | classification |

**Code actions.** Two branches, deliberately not sharing a path.

| Branch | Trigger | Fact source |
|---|---|---|
| `LintQuickFixes` | A fix-bearing lint finding in the report, `Built.Current` only | `lint_finding` + the rule's own `LintFix` |
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

## Retired vocabulary

Provisional until the cutover lands; the Done-gate sweep greps for these. `CompletionData`,
`CatalogFacts`, `LspSchemaSnapshot`, the `Built.Current` / `Built.Previous` freshness seal,
`typeDefinitionLocations`, `CatalogBuilder`'s projection pass, `DevMojo`'s keep-previous-and-demote
path (`demoteSnapshot`, `markAllForRecalculation`), `refreshTypeIndex`, `declaredTypes` and
`dependsOnDeclarations`, `SourceWalker.Index` with its `ambiguousMethods` and `methodsByName`, and
`Workspace`'s `sourceIndex` / `setSourceIndex` / `refreshSourceIndex`.
