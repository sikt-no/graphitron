---
id: R638
title: "The LSP is a fact-store client"
status: In Review
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-19
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


## Reading this at the gate

This item is large, and most of its length is a record rather than a request. The judgement being
asked for is one thing: whether where this landed is the delivery the intention describes.

Read in this order.

* **The intention**, everything from "The shape" down to "Resolved questions". Written before and
  during the work: what the language server was, what it was meant to become, and the capability
  list it was measured against. Some of it speaks in the present tense about an incumbent that is
  gone; it is preserved as written, because what the gate compares against is what was promised, not
  a retelling of it.
* **"Where it landed"**, the delivery stated against that intention, including the places the two
  diverge. Every divergence is named there rather than left for the gate to discover.
* **"Open at the gate"**, the calls that are the reviewer's rather than the implementer's.
* **"Retired vocabulary"**, the grep list the retirement sweep runs against.

**The build log below is not required reading.** It is a chronological record, one section per design
decision, appended as each was settled. Its job is to let any single claim in "Where it landed" be
challenged without that claim having to carry its own reasoning: a decision you want to push on has a
section there stating what the alternatives were and why the tree looks the way it does. Read it by
search, not front to back.

**Verification state.** The full reactor build is green: `mvn install -Plocal-db`, fourteen modules.
`graphitron-lsp` carries 606 tests across 68 files, 3 skipped and none failing. A self review ran
before this handoff; what it found and fixed is the last section of the build log.

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
(trees exist only for open buffers, and syntax validity ships as rows: the parser's own refusal is a
verdict the store carries, which the diagnostics replay reads).
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

With them went the LSP's own tree-derived facts: `WorkspaceFile.refreshTypeIndex` and the
declared/referenced type sets it re-derived per keystroke, whose one consumer was the cross-file
diagnostic fan-out. "Which files touch this type" is a read over `graphql_type_declaration`, and
the only file that relation cannot speak for is the one stale buffer. Settled below, with the
cadence change that removed the fan-out those sets aimed.

`CatalogFacts` had non-LSP readers that had to move with it, and they have: the sibling item
`catalog-facts-readers-move-to-the-store.md` migrated every `graphitron-mcp` reader off the
projection and deleted the type with the last of them, `GraphQLRewriteGenerator`'s output record
included. Nothing outside the LSP reads it now, so this item inherits no non-LSP work here. The
term survives in the paragraphs below as the projection this item is written against; a reader
meeting it should take it as the shape being replaced rather than as a type still standing.

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

All five are pinned. Two are pinned as the property they illustrate rather than as literally worded,
the first because its example names a completion trigger the inventory does not carry and the second
because it asks for an answer shape a jump should not have; settled in the closing section of this
file, with the tests that hold them.

Latency is measured per request on the Sakila fixture, against the new implementation alone. Not a
comparison, since there is nothing left to compare against and the direction is not in doubt: the
point is to find which paths are slow as views, because that decision has a sanctioned answer and
needs the numbers to be made. A hot path materializes, with the DDL comment owning why, as the
reachability closure `intent_type_domain` does; never an ad-hoc cache.

Measurement is not sequenced, and not a gate. An earlier reading of this clause asked for numbers
early enough that the first materialization would be a design choice rather than a repair; the
sanctioned answer is what makes that unnecessary, since the choice is already decided and only its
trigger is open. Numbers are taken when a surface is reported slow or when a materialization is
proposed, and no path materializes without them.

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
| `CatalogColumnBinding` | `FieldCompletions` | `sql_column`; the site's own table via `intent_field_column_table`; a class-backed parent's members via `intent_class_member_slot`. Which class or table backs the parent is `intent_type_backing` and `intent_bound_table`, read as one fork through `TypeMemberScope` |
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
| `CatalogColumnBinding` | Both column types, nullability, description; member name and type when the backing is a record or POJO | `sql_column`, `sql_table`; Javadoc via the `java_` source family. The site's table via `intent_field_column_table`, a class-backed parent's members via `intent_class_member_slot`; which class or table backs the parent through `TypeMemberScope` over `intent_type_backing` and `intent_bound_table` |
| `CatalogFkBinding` | FK direction and endpoints, under any spelling the resolver accepts | `sql_referential_constraint`, `sql_constraint` |
| `NodeTypeBinding` | `typeId`, key columns and their types | `graphitron_node`, `graphitron_node_key_column`, `graphitron_table` + `sql_column` for the types |
| `ArgMappingBinding`, `ScalarTypeBinding` † | nothing | — |
| Any coordinate, no richer arm | SDL docstring | `graphql_directive_argument` for a directive argument, `graphql_field` for a nested input field |
| Directive argument name | Arg docstring | `graphql_directive_argument`, bundled and author-declared alike |
| SDL declaration name (`hoverClassification` toggle) | `DeclarationHovers`: classification block + the bound declaration's description | the claim views for the classifier, then one relation per fact the block shows (built: `ClaimFacts` over `intent_column_match_claim`, `graphitron_service`, `graphitron_external_field`, `graphitron_field_node_id`, `graphitron_routine`, `graphitron_mutation`, `intent_bound_table`, `graphitron_error_handler`, `intent_field_reference_step_target`, `intent_field_separate_fetch`); `sql_table`, `sql_column` and the `java_` source family for the description |

**Definition.** Three providers chained with `.or()` in this order, keyed on disjoint syntax.

| Provider | Trigger | Fact source |
|---|---|---|
| `Definitions` | Directive arg: `ClassName`, `MethodName`, `CatalogTable`, `CatalogColumn`, `CatalogFk` | `jvm_class`, `jvm_method`, `jvm_method_parameter`, `sql_table`, `sql_column`, `sql_constraint`, `sql_referential_constraint`, `sql_schema`, joined to the `java_` source family's positions. The column arm's own table via `intent_field_column_table`, its parent's via `intent_bound_table` |
| `Definitions` † | `ArgMapping`, `ScalarType`, `NodeType` return empty | — |
| `IntraSchemaDefinitions` | Type reference to its declaring SDL site | `graphql_type_declaration`; a declaring file that has moved since capture re-anchors through its live tree |
| `DeclarationDefinitions` | SDL declaration name to its bound Java | `intent_class_member_slot` for which declaration a member name binds to, a field or a method; `sql_table` and `sql_column` for a table-backed one, `jvm_method` for the arity a method-backed field resolves at; the `java_` source family for where it is written |

**Inlay hints.** Three independent toggles, all default off (`InlayHintConfig`); two collectors.

| Toggle | Collector | Fact source |
|---|---|---|
| `classification` | `collectClassificationLabels` | the claim views, both grains, at the vocabulary they carry (built: `InlayFacts`) |
| `inferredDirectives` | `collectInferredDirectiveHints`, collectors for `@table`, `@field`, `@reference` | `intent_bound_table` for the `@table` collector; `intent_resolved_field_claim` over `intent_column_match_claim` for the `@field` collector's column arm and `intent_class_member_slot` for its class-member arm, the second reached through `TypeMemberScope` (both built: `InlayFacts`); the `@reference` collector fires only on an *omitted* path, so its source is `intent_field_reference_discovery`, the foreign-key discovery between the two types' bindings, and not the authored chain (built) |
| `inferredDirectives` | `collectAbsentTableHints`, a second pass inside the inferred-directive collector | `intent_bound_table` (built: `InlayFacts`) |
| `separateFetch` | `collectFetchMarkers` | `intent_field_separate_fetch`, the marked rules only (built: `InlayFacts`, `SeparateFetchRule`) |
| `hoverClassification` | gates `DeclarationHovers` (see hover) | the claim views, as the `classification` toggle above, plus the per-fact relations (built) |

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
| Coordinate validation | Dispatches all eight `Behavior` arms; unresolved values against the catalog, classpath and SDL censuses, all eight reading the store |
| Unknown args | Directive args not declared, one path over `graphql_directive_argument` for bundled and user-declared alike, descending `graphql_field` into object literals |
| Required args | Declared-required args absent |
| Unknown directive | `graphql_directive`, skipping the GraphQL spec built-ins |
| The build's own findings | Every schema-side arm of the `diagnostic` view for the file: rejections, claim conflicts, lint findings, advisory warnings, and the parser's and assembler's refusals |

First-iteration cadence: diagnostics ride the capture cadence, not the keystroke. Landed, settled
below. Every source in
the table reads as rows from the store, published per file when capture swaps; the per-keystroke
recomputation and its cross-file fan-out retired with the type index that aimed them. A stale
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
view, not the LSP push. The `Workspace.compileDiagnostics` slot they once rode had no production
reader left and retired with the rest of the workspace bookkeeping, settled below.

**Lifecycle and state.** `didOpen` / `didChange` (incremental) / `didClose` / `didSave`;
`didChangeConfiguration` plus a `workspace/configuration` pull after `initialize` for the three inlay
toggles; `didChangeWatchedFiles` is a no-op today. The open-buffer set stays in `Workspace`; the
tree-derived type index (`refreshTypeIndex`'s declared/referenced sets) and the per-file
recalculation bookkeeping it aimed retired with the keystroke cadence (see the diagnostics
paragraph above), and the source index (`refreshSourceIndex`, `sourceIndex`) retired with the
java-source family, settled below: the LSP walks nothing.

**What read the projection, and what closed the list.** Two sites, the residue of the inventory
rather than separate concerns. Named here because "every feature moves" is checkable only against a
list of what has not, and that list is empty now.

* `DeclTarget` asked for the field classification at a coordinate, and asked it last only about
  `@routine`: the generated call surface a routine read or write binds to. That surface is a relation
  now, and the arm is a subquery in the statement it used to arrive beside; settled below.

The second site was `FieldCompletions`'s source-sigil predicate, and it is settled below: the
completion and the diagnostic beside it read `intent_carrier_data_field`, and the carrier projection
retired with them. The projections nothing read at all went next, also settled below: the
`CompletionData` catalog the workspace still held, the snapshot's type-backing and
declaration-location maps, and the source-position index the workspace held beside them. The
directive surface followed, reader first: diagnostics judges a directive against
`graphql_directive` and `graphql_directive_argument` now, and the projection retired in the same
session. Then the replay of a build's own errors moved, reading the `diagnostic` view rather than
the report object the workspace held, and the freshness axis it gated on retired with it, in the same
session. The routine arm went last, and took the projection out of the language server with it: no
surface takes a snapshot, `Workspace` no longer holds one, and the type is produced and read
generator-side, its availability arm alone reaching the dev goal as the signal that a round
classified.

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
* **`CatalogFacts`' non-LSP readers** moved alongside, in the sibling item
  `catalog-facts-readers-move-to-the-store.md`, for two reasons: the MCP
  catalog tools have their own acceptance surface (tool output, paging) that has nothing to do with
  cursors and buffers, and folding them in credits their `rewrite/catalog` lines to the LSP
  measurement. That item has shipped, taking `CatalogFacts` with it. The doctrine binding the two
  outlives both: they read one shared store-side *base*, never a narrowing made for one of them.


## Where it landed

The delivery, stated against the intention above. Everything here is checkable on the tree; where the
delivery differs from what the intention said, the difference is named rather than smoothed over.

### The seam, which is the item's own structural test

The intention nominated one test that could not be argued with: `graphitron-lsp`'s pom. It named
`graphitron` and imported twenty-one types from it, ten of them out of `rewrite/catalog`, and the
standard set was that it should name `graphitron-model` instead, with whatever remained accounted for
one type at a time.

It names `graphitron-model`. **No main source in `graphitron-lsp` imports any generator type, and the
`graphitron` dependency is test-scope.** The module's compile classpath is `graphitron-model`, lsp4j,
jtreesitter, the tree-sitter natives and slf4j. Nothing the language server ships can reach a
generator type, and the pom says so in a way a future commit cannot quietly undo: widening the scope
back to compile is a visible edit with a comment beside it explaining what it would cost.

Accounting for the last six, the ones a late audit found still crossing (five imported, one reached
by fully-qualified name and therefore missed by the import count):

* **Four were rules or constants rather than models**, and moved *down* to `graphitron-model` rather
  than being copied, joining the grammar package that already stated the case: a rule about a column
  belongs in the module that declares the column, not in either module that reads it, because a
  private copy on either side is a second opinion about a spelling neither owns.
  * `SourceUri`, both directions of the source-name/URI trip. That was the worst of the six: the
    forward trip was the generator's `canonicalUri` and the reverse was the language server's
    `sourceNameOf`, so one round trip was defined in two modules with nothing holding the halves to
    each other.
  * `ConstantReferenceGrammar`, the split-at-last-period rule behind
    `ScalarTypeResolver.parseDirectiveValue`, with the sealed shape verdict over it.
  * `FieldSourceSigilGrammar`, the two sigil literals and the message for one written where it is not
    admitted. This was the edge the import count missed, reached by fully-qualified name. The
    generator rejects such a site and the language server marks it while the author is still typing,
    so a literal spelled twice is the two surfaces disagreeing about one value.
  * `InferredDirectiveArgs`, as it is.
* **Two were the directive vocabulary's**, `RewriteSchemaLoader.directivesSdl` for the bundled
  definitions and `DeprecationRecognizer` for whether a coordinate is deprecated. Neither moved. The
  vocabulary was **replaced**, and that is the change worth the reviewer's attention below.

The test-scope edge is 29 distinct generator types, all of them fixture-side: capture, schema
loading, lint configuration, the validation report. The fixtures stand a store up by running real
capture rather than by inserting rows, which is what the Acceptance section asked for, and it is why
they need the build tier. Consolidating that surface is
`fact-store-test-harness-consolidation.md`'s, not this item's.

### The vocabulary, which was the last thing holding the edge open

The language server used to parse graphitron's bundled `directives.graphqls` at startup into a
graphql-java `TypeDefinitionRegistry` and resolve every cursor against it. That was the single
remaining reason the module needed graphql-java and the generator at runtime, and it was also a
second model: a directive an author declared themselves was known only by argument name, because the
registry held graphitron's definitions and the projection held everyone else's.

It is gone. `DirectiveSurface` reads the same four relations an author's own declarations land in
(`graphql_directive`, `graphql_directive_argument`, `graphql_type`, `graphql_field`), because capture
parses the bundled file like any other schema file. Three consequences, in descending order of how
much they change:

* **The bundled-versus-user split is unrepresentable rather than fixed.** An author's own directive
  now gets the same nested descent through its input types that `@reference` gets. That asymmetry was
  never a decision; it was what having two sources looked like.
* **The vocabulary became state.** It is loaded per graph and reloaded when capture swaps, where it
  used to be a constant compiled into the session. `Workspace` holds it; `LspVocabulary.empty()` is
  what a session before its first build has, and it resolves nothing rather than guessing.
* **The startup drift check became a test.** The vocabulary used to refuse to be constructed when an
  overlay coordinate failed to resolve, which was a sound guard while it was reading a file shipped in
  the jar. Read against a captured graph it conflates two different failures, and only one of them is
  drift: a graph nobody has captured yet would take the whole editor down. `DriftDetectionTest`
  asserts exactly the old invariant, against a capture of the shipped file, with none of the
  collateral. This is a deliberate weakening of a runtime guard and the single change here most worth
  a second opinion.

The surface is loaded whole and held rather than queried per question. That is forced rather than
chosen: the diagnostics walk reads nothing by construction, so that a recalculation costs one
statement per graph rather than one per value an author wrote, and a store round trip inside
coordinate resolution would have broken the invariant four tests pin. The one place that still
queries these relations directly is `ArgNameCompletions`, and only for the half the surface cannot
answer: it needs an input object's field names *in declaration order*, which is a listing rather than
a lookup, while every nesting step it walks resolves through the held surface.

Deprecation left with the same commit and for a plainer reason: nothing on any request path read it.
`deprecationOf` and `deprecatedCoordinates` had exactly one consumer, a test asserting that the
shipped SDL and the shipped quick-fix registry agree, so the reading moved to test support beside
that assertion. One honest gap came out of it: no relation carries `@deprecated` applied to a
*directive definition's formal argument*, so `SdlDeprecations` parses the shipped file rather than
reading a graph, and its javadoc says why instead of a store-shaped reader quietly answering for two
of the three markers.

### The six named projections, and one miscount

"What retires" named six projection types the language server imported, on the reasoning that a port
would keep them.

* `DirectiveShape`, `InputValueShape`, `FieldClassification` and `TypeClassification` are **deleted
  outright**, along with `LspSchemaSnapshot` and the projection half of `CatalogBuilder`.
* `TypeBackingShape` **stays**, and not as residue: its producer is `CatalogBuilder.projectTypesByName`
  and its reader is the walk shadow, a capture-time transcription rather than anything shipped to a
  consumer.
* `InferredDirectiveArgs` was a **miscount at drafting**. It is a three-entry constant table naming
  which argument each inference rule fills in, rebuilt by nothing and read by both tiers. A query over
  the claim stratum cannot replace it, because what it states is which arguments inference fills in,
  not what any of them resolves to. It moved to `graphitron-model` with the other rules.

`CompletionData` also **stays**, which an earlier draft of the retired-vocabulary list got wrong. It
is capture's input type for the classpath census now, not a projection any consumer reads.

The item had deferred one question rather than answering it: whether the classification projections
themselves delete depends on their generator-side readers, "which is a separate census". The census
ran at the gate and the answer was **nobody**. `CatalogBuilder.buildSnapshot` ran on every generator
pass and reached exactly one production statement in the reactor, an `instanceof` in the dev goal
asking whether a round had classified at all. The generator does not classify through the projection;
it classifies into `GraphitronSchema` and reads that. So the projection was a model with no consumer
being rebuilt on every build, which is precisely the second model this item exists to remove, and it
deleted here rather than in a follow-up.

### The numbers, said plainly

Acceptance asked for the line counts at the end, as an outcome rather than a gate.

| Measure | At filing | Now |
|---|---|---|
| `graphitron-lsp` main | 9,119 lines | 14,203 lines across 81 files |
| `graphitron-lsp` tests | not recorded | 16,063 lines, 606 tests in 68 files |
| `rewrite/catalog` | 4,008 lines | 1,536 lines |
| the seam inside it (the package less `ClasspathScanner`, with `SourceWalker` moved to `rewrite/capture`) | 3,232 lines | 997 lines |
| the store's DDL | n/a | 5,603 lines, with 45 `intent_` views as the derived stratum |

**The two directions are opposite and both were expected.** The item said in advance that saying so
was the point. A query answering a capability from relations is more code than a switch reading a
value someone else pre-projected, and the `intent_` stratum is new code that did not exist before.
The largest package in the module is `facts` at 3,231 lines, and that package **is** the seam: queries
and the row types they return, with nothing behind them. What it replaced went the other way and
further than the baseline anticipated, because once the language server stopped reading the
classification projection the census found nobody else reading it either.

Attribution is coarser than the figures look. The module took other items' work over the same period,
and the clone these were measured in does not reach back to the baseline commit, so these are the
totals now against the totals then rather than this item's own diff.

### The acceptance corpus

All five cases are pinned. Two are pinned as the property they illustrate rather than as literally
worded, and the difference is stated here rather than left for the gate to find.

| Acceptance case | Pinned by |
|---|---|
| A dirty buffer beside well-formed siblings | `CompletionStoreWiringTest.aBufferThatWillNotParseCompletesAgainstWhatTheOtherFileDeclares` (see the divergence below) |
| A type assembled from several files, resolving to its declaration sites | `IntraSchemaDefinitionTest` (see the divergence below) |
| Two graphs in one store, neither seeing the other's tables | `CompletionStoreWiringTest.oneGraphsPopupDoesNotOfferAnothersClasses`, `ReferenceCompletionsTest.aSiblingGraphReadsNoneOfTheBinding`, `SdlDescriptionsTest.anotherGraphsDeclarationsAreInvisible` |
| One file in two graphs, surfacing the multi-graph membership arm | `StoreAccessTest.aDocumentTwoGraphsReadResolvesToTheSessionsOwn`, with the neighbouring absent-arm cases |
| A `.java` edit and save beside an untouched schema | `SourceCadenceHoverAndDefinitionTest.aSourceEditMovesHoverAndGotoTogetherWithoutAGeneratorPass` |

**Divergence one: the first case names a trigger that does not exist.** "`extend type |` completes
against the other files" asks for SDL type-name completion, which the language server does not have
and never had; there is no such arm anywhere in the capability inventory. What the example
illustrates is the division of labour: a buffer the parser refuses is the question, not a reason to
decline it. That property is pinned on a trigger the inventory does carry. The test opens a captured
document, replaces its content with SDL carrying a syntax error, completes `@nodeId(typeName:)`
against a `@node` type only the graph's *other* schema file declares, and asserts the buffer's tree
carries an error so the case cannot quietly decay into a well-formed-buffer one.

One boundary the example blurred is worth naming beside it: the store answers for a document some
graph has captured, so a file nobody has saved yet resolves to no graph and gets no answer, which
`StoreAccess` states and `StoreAccessTest.aDocumentNoGraphHasReadAnswersAbsent` pins.

**Divergence two: the second case overshoots the other way.** It asks a jump to resolve to *all* a
type's declaration sites. `IntraSchemaDefinitions` returns one, and one is the right answer for a
jump. What the flattened `typeDefinitionLocations` map could not do was **choose**: one entry per type
name meant an extension overwrote a definition by whichever the projection pass reached last. The
store carries every site and the resolution is a rule over them, an open buffer's live span beating a
captured one and a base declaration beating its extension. The test pins both preferences and the
across-files jump.

Neither divergence changes a shipped behaviour. Both are the Acceptance text catching up with what
the capability inventory actually contains.

Latency was not measured, and the item said in advance that it would not be sequenced or gated: the
sanctioned answer to a slow path is a materialized view with the DDL comment owning why, so the
decision is already made and only its trigger is open. No path has been reported slow and none has
materialized.

### The cadence change, which is the one behaviour an author will notice

Diagnostics ride the capture cadence rather than the keystroke. Every source reads as rows from the
store, published per file when capture swaps; the per-keystroke recomputation and its cross-file
fan-out retired with the tree-derived type index that aimed them.

That trades keystroke-live feedback in the one buffer being typed in for a single shape everywhere.
Against the incumbent it is not a close comparison, because the incumbent silenced the whole replay
while the snapshot was demoted: a newly broken schema showed nothing at all, where here a file that
will not parse and a schema that will not assemble each report exactly why, as rows, per file. A
stale buffer shows the diagnostics of its last captured content, re-anchored through its live tree
where the text has moved.

### The doctrine the retirement owed a repointing

Three named exemplars in `docs/architecture/explanation/`, repointed rather than deleted.

* The one-slot provenance exemplar in `fact-model.adoc` was `CompletionData.NodeMetadata` and is
  `graphitron_node` now, whose `type_id` holds the authored value, stays null where nothing was
  authored, and whose own comment names the type-name fallback as a derivation.
* The freshness paragraph beside it described a consumer carrying an availability axis and a
  current-versus-previous one. It explains why no consumer carries either now: two-stage capture
  turned both refusals into rows, and what is left is a lag of stated size rather than a state a
  surface switches on.
* The projection-seam enforcer, named in both `fact-model.adoc` and `development-principles.adoc`,
  was `CatalogBuilder.projectFieldClassification`. **No projection seam survives**, so both documents
  now say that each consumer's coverage gate is over its own sealed vocabulary instead:
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` for code generation and
  `TriggerDispatchMatrixTest` for the language server. (An earlier gate section in the build log says
  the exemplar was kept and only restyled; the census that ran afterwards deleted the method, and the
  documents had not caught up until the self review.)

`DocSizeBudgetTest` shaped how that last one was worded, and was right to. `development-principles.adoc`
has no headroom left against its own 3,500-word cap; the next addition to it displaces something.

### What stands in for a parity gate

The intention declined a shadow-parity gate and nominated an enforcer instead: an exhaustive
partition over (trigger × surface), with the trigger universe derived from
`getPermittedSubclasses()` rather than hand-listed, so a new trigger cannot sit unaccounted for while
the test stays green.

That is `TriggerDispatchMatrixTest`, over 13 sealed `Trigger` leaves and 6 `LspSurface` arms. Every
cell is `answered`, `declared-no-answer` or `unimplemented`, and each surface's dispatch is a
compile-checked exhaustive switch. The `†` gaps in the capability inventory are declared facts the
matrix pins rather than silently empty arms.

## Open at the gate

Four things left deliberately for the reviewer rather than decided at the end of the item.

**1. Two author-visible changes are undocumented, and whether that is a gap is a call.** The item is
an internal re-platforming and carries no `User documentation (first-client check)` section, which
the workflow exempts an internal refactor from. Two shipped changes are nonetheless visible to a
schema author, and neither is written down in the manual: diagnostics now appear when capture swaps
rather than as the author types, and a session that has never captured completes nothing at all,
argument names included. No manual page claims otherwise today, so nothing there is *wrong*. The
question is whether either deserves a sentence on a reference page. Adding one at the end of the item
without asking would be a silent scope widening, so it was not done.

**2. Three design calls worth a second opinion.** Each is stated where it landed, with its reasoning
in the build log; they are collected here so the review does not have to find them.

* The drift check stopped being fatal (see "The vocabulary"). A guard that used to take the session
  down is now a build-time test. The argument is that an unresolved coordinate has a second cause,
  an uncaptured graph, that must not kill an editor. The counter-argument, if there is one, is that
  a genuinely drifted overlay now degrades silently in a running session.
* `DirectiveSurface` is loaded whole and held rather than queried per question. The alternative costs
  the one-statement-per-graph invariant that four tests pin, so this is the load-bearing shape
  decision in the vocabulary cut.
* The `graphitron` dependency is test-scope rather than removed. The argument is that the fixtures
  stand a store up by running real capture, which is what Acceptance asked for and which needs the
  build tier. It does mean the structural test is "no main source imports one" rather than "the
  module cannot see it at all".

**3. This item's deletions falsified premises in other roadmap items.** Found by the self review's
retirement sweep, listed rather than acted on, because retiring another item is that item's decision
and not this one's. Each names a type this item deleted.

* `lsp-structural-consolidation.md` (In Progress) is the strongest case for `Discarded`. It is
  an umbrella over duplication in a module that has since been rewritten wholesale, and the "sound
  spine" it consolidates *toward* is the vocabulary's startup invariant (now a test) and the
  freshness-aware snapshot matching (deleted).
* `mcp-snapshot-axis-key-naming.md` (Backlog) is entirely about how four MCP tools spell the
  availability and freshness axes of `LspSchemaSnapshot`. The snapshot is deleted and no consumer
  carries a freshness axis, so the item has no subject left.
* `lsp-compile-diagnostics-publish.md` (Backlog) is premised on diagnostics landing on
  `Workspace.compileDiagnostics()`, which retired. The feature it wants, javac errors published
  against generated-file URIs, may still be wanted; the mechanism it names is gone.
* Eleven more name `CompletionData`, `SourceWalker`, `LspSchemaSnapshot` or a retired method in
  passing rather than as a premise, so each needs a line edited rather than a decision:
  `lsp-reference-path-authoring.md`, `lsp-nodetype-hover-column-scoping.md`,
  `validator-reference-candidate-hint-terminal-table.md`, `consumers-share-relations-not-queries.md`,
  `assembled-schema-owns-the-sdl-census.md`, `split-query-marker-sweep.md`,
  `coordinate-lowers-to-datafetcher-queryparts.md`, `fact-store-test-harness-consolidation.md`,
  `model-free-of-emit-vocabulary.md`, `deprecate-externalfield-fold-into-service.md` and
  `relevance-ranked-search.md`. The two files under `roadmap/audits/` that also match are historical
  records and should be left as written.

**4. The test-scope edge is real and is somebody else's.** 29 distinct generator types across
`graphitron-lsp`'s tests. `fact-store-test-harness-consolidation.md` owns consolidating that surface;
this item deliberately stopped at the runtime edge.

## Retired vocabulary

The Done-gate sweep greps prose surfaces for these: javadoc, implementation comments, `.adoc` files,
the user manual, fixture prose and SDL descriptions, test names, and roadmap bodies. Grouped by what
they belonged to rather than by when they went, because the sweep is a search and not a history. Read
"**Collisions**" and "**Survives elsewhere**" first: they are the names that will match something
live and correctly so.

### Collisions the sweep will hit and should not act on

* `DirectiveShapeSmokeTest` names the SDL shape of `@service` and its siblings, the nested input under
  an outer argument. Not the retired projection type.
* `graphitron-sakila-example`'s federation tests say "directive shape" about emitted SDL. Same word,
  different subject.
* The retired `compileDiagnostics` is `Workspace`'s slot. The concept is alive and the MCP tool
  answers about it from the store's own relation.
* `TypeContext.tableNameOf` is retired; `CatalogBuilder.tableNameOf` is a different method and is
  alive.
* `LspVocabulary.findInputValue` is retired; `DeprecationRecognizer.findInputValue` is generator-side
  and alive.
* `ClaimFacts.separateFetchRules` is retired; `InlayFacts.Rows.separateFetchRules` is the relation's
  new home.

### Survives elsewhere, retired only from the language server

* `DeprecationRecognizer` still backs the `no-deprecated-directive-usage` lint rule.
* `RewriteSchemaLoader` is still how every build finds its schema.
* The language server calls neither; its tests still do.

### Not retired, despite an earlier draft of this list saying so

* `CompletionData` stays, as capture's input type for the classpath census.
* `InferredDirectiveArgs` stays, and moved to `graphitron-model`'s grammar package. Listing it among
  the projections was a miscount.
* `TypeBackingShape` and `CatalogBuilder.projectTypesByName` stay; their reader is the walk shadow.
* `SourceWalker` stays, one product lighter, moved to `rewrite/capture` beside `ClasspathScanner`.
* `TypeBackingClass.resolve` stays; only `TypeBackingClass.contested` and `ofTypes` went.

### The classification projections, and the coverage apparatus that gated them

`FieldClassification` with every arm it declared, and `FieldClassification.lspColumnDispatch` with the
sealed `LspColumnDispatch` its three column readers switched on. `TypeClassification` with every arm.
`CatalogBuilder.buildSnapshot` in both overloads, `projectFieldClassifications`,
`projectFieldClassification`, `projectTypeClassifications`, `projectTypeClassification`,
`inputConsumerTables`. `GraphQLRewriteGenerator.BuildArtifacts` with `BuildOutput.artifacts`.
`DevMojo.InitialOutput.snapshot`. `LspClassificationLabels` with `projectionLabel` and
`projectionTypeLabel`. The `@ProjectionFor` annotation, `ProjectionCoverageTest`,
`ExemptionRegistry.LSP_PROJECTION` with `NO_PROJECTION_REQUIRED`, `PROJECTION_WALKER`,
`projectionForCoveredLeaves` and `allModelLeaves`, and `ExemptionRegistry.corpusObligations` now that
it and `obligations` agree.

### The snapshot, and everything that took one

`LspSchemaSnapshot` with `Built`, `Unavailable` and `unavailable()`, and the `Built.Current` /
`Built.Previous` seal with `Workspace.demoteSnapshot` and the dev goal's three calls to it.
`Workspace.snapshot` and `Workspace.setBuildOutput`. `CompletionRequest.snapshot`. The snapshot
parameter on `DeclarationDefinitions.compute`, `DeclarationHovers.compute` and `Hovers.compute`.
`DeclTarget.projectedMethod`, `DeclTarget.of`'s and `DeclarationFacts`'s `ProjectedMethod` parameter
with the record itself, and `DeclarationFacts.Rows.projectedArity` with the `jvm_method_parameter` arm
behind it. `typeDefinitionLocations` with `CatalogBuilder.projectTypeDefinitionLocations` and
`putTypeLocation`. The snapshot's `typesByName` with its `typeBacking` lookup.

### The directive vocabulary's registry

`LspVocabulary.registry` with the `load()` and `load(overlay, sdl)` factories that built one,
`LspVocabulary.unwrapToInputTypeName`, `LspVocabulary.findInputValue`,
`LspVocabulary.deprecatedCoordinates`, `LspVocabulary.deprecationOf`,
`LspVocabulary.descriptionOf` and `LspVocabulary.LspStartupException`. `Workspace.resolveDirective`.

### The directive projection and its readers

`DirectiveResolution` with its `Bundled` / `User` / `Unknown` permits. `Diagnostics.validateUnknownArgs`,
`descendUnknownArgs`, `validateRequiredArgs`, `validateUnknownArgsAgainstSnapshot` and
`validateRequiredArgsAgainstSnapshot`. `LspSchemaSnapshot.Built.directives` with its `directive(name)`
lookup, `DirectiveShape`, `InputValueShape`, the sealed `TypeShape` with its `Named` / `List` permits,
`CatalogBuilder.projectInputValues`, `projectType(Type)` and `descriptionOf`, and the registry-only
`buildSnapshot` overload.

### The keystroke cadence

`WorkspaceFile.refreshTypeIndex` with the `declaredTypes` and `dependsOnDeclarations` sets it
maintained. `Workspace.enqueueTouched` with its `intersects`. The `TypeNames` class that fed them, with
`extract`, the `Extracted` record and the tree-sitter query behind both; its `BUILTIN_SCALARS` is a
private constant on `IntraSchemaDefinitions` now, the one surface that reads it.

### The workspace's other slots

`Workspace.compileDiagnostics` and `setCompileDiagnostics`, with `reportCompile`'s and
`maybeStartIncrementalCompiler`'s `Workspace` parameters. `Workspace.validationReport` with
`setBuildOutput`'s report parameter. `Workspace`'s `catalog` field with its `catalog()` accessor and
both `CompletionData`-taking constructors, and the `CompletionData` parameters on `Hovers.compute`,
`SdlActions.all` and `buildSnapshot`. `Workspace.sourceIndex` and `setSourceIndex`.

### Diagnostics' per-value readers

`Diagnostics.validateCatalogTable`, `validateCatalogFk`, `validateClassName`,
`validateScalarTypeClasspath`, `validateNodeType`, `validateMethod`, `validateFieldMember`,
`validateColumnOnResolvedTable`, `validateColumnOnTables`, `validateMemberSlot`, `validateArgMapping`
and `resolveParameterNames`, all replaced by a walk that collects and a judgement that reads one
answer. `Diagnostics.collectAllFkNames`. `Diagnostics` no longer reads `CatalogTables`, `CatalogKeys`,
`CatalogColumns`, `ClasspathClasses`, `ClasspathMethods`, `FieldColumnTable` or `TypeMemberScope`
directly at all; those relations are arms of `DiagnosticFacts`, and the two rules that had to survive
the move are `TypeMemberScope.resolve` and the `spelledBy` conditions on `CatalogTables` and
`CatalogKeys`.

### The per-grain bulk readers, recomposed into `InlayFacts`

The whole of `ClaimClassifiers` (`ofTypes`, `ofFields`) and of `FieldMemberName` (`of`,
`matchedColumn`). `BoundTables.unambiguous` and `unambiguousByType`. `TypeBackingClass.ofTypes` with
its `candidatesByType`. `ClaimFacts.separateFetchRules`. `InlayHints` names no reader class at all now
and its per-directive registry holds collectors rather than renderers, so
`renderInferredTableNameHint`, `renderInferredFieldNameHint` and `renderInferredReferencePathHint` are
gone with the `InferenceSources` record and the `InlayHintKind` parameter on `makeHint`.

### The carrier projection and the source sigil

`payloadDataFieldByType` off both `Built` arms, `LspSchemaSnapshot.siteContext`,
`CatalogBuilder.projectPayloadDataFields`, and the sealed `FieldSourceSigil.SiteContext` with its
`PayloadDataField` / `Other` permits and `sourceSigilDefinedAt`.

### The source-position index, and its resolution policy

`SourceWalker.Index` with `ambiguousMethods`, `methodsByName`, `resolveMethod`, `methodByName` and the
`Decl` / `MethodKey` / `MethodNameKey` / `FieldKey` shapes it was keyed by. `SourceWalker.walk` and
`indexOf` that built it. `Definitions.methodLocation`. The whole of `Descriptions` (`ofTable`,
`ofColumn`, `classJavadoc`).

### The backing shapes and the type context

`TypeBackingShape.MemberSlot` with the `RecordBacking.components` / `PojoBacking.accessors` payloads,
and `CatalogBuilder`'s `projectRecord`, `projectPojo`, `beanAccessorSlot` and `lowercaseFirst`.
`TypeBackingClass.contested` with `ClaimFacts.ofType`'s classifier parameter. `TypeContext.tableNameOf`
with `ArgMappingSupport.resolveMethod`. `TypeContext.tableNameFromClassification` with the sealed
`InferredDirectiveArgs.AbsentArm` and its `TableName` permit. The relation
`intent_class_member_type_ref`, whose union the owner-keyed `intent_declared_type_ref` states one key
lower.

### Gone with the sibling item that took them

`CompletionData`'s non-LSP readers went to `catalog-facts-readers-move-to-the-store.md`, which deleted
`CatalogFacts` with the last of them, `GraphQLRewriteGenerator`'s output record included.
`CatalogBuilder`'s projection pass went here.

## Build log

Everything below this line is the record of design decisions taken while implementing, one section
per decision, appended as each was settled and left in the order it happened. **It is not required
reading at the gate.** Its job is to let a claim in "Where it landed" be challenged without that
claim having to carry its own reasoning: search it for the subject you want to push on, and the
section that settled it states the alternatives that were considered and why the tree looks the way
it does. The last section is the self review that ran before this handoff.

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
| Which table a *field site's* columns come from | `intent_field_column_table`: the chain's terminal arrival for a `@reference` field, the navigated element table for the order-by sites. The parent's own binding is deliberately absent, being what a reader already holds | built |
| What an *omitted* `@reference` path infers | `intent_field_reference_discovery`: the foreign keys connecting the parent's binding to the field's named type's binding, with the arity that says whether the walk would accept one | built |
| What member names a backing class offers | `intent_class_member_slot`: a record's components, or the bean accessors of anything else, the rule chosen by the class's declared form. Keyed by the census, not by a graph | built |
| Which Java class a type is backed by | `intent_type_backing_class`, the reflective binding walk's own answer. The census blocker is gone: it now carries the declared return type beside the erasure, so an accessor hop names its element type. What is unbuilt is the walk over those hops, its grounding and its gates | unbuilt |
| The main classifier at a declaration | `intent_resolved_field_claim` at the field grain, `intent_authored_type_claim` at the type grain, both at the vocabulary they already carry | built |
| The rest of what a declaration's incumbent label encoded | nothing: the other facts, each from the relation that owns it. See "the label is not a fact" below | built, unbuilt per fact |

Two things fall out of writing that down. The label rows do not want a new view at all, and they do
not want a grown vocabulary either: what an incumbent label encoded was several facts folded into one
name, so the surfaces that render it stop rendering one name rather than the store learning every name
they could have rendered. And the column rows are not one question but three, which is why the column arm could not move with the key arm
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

## Settled while building: the column dispatch retires, and an override is not an answer

`intent_field_column_table` landed and `FieldClassification.lspColumnDispatch()` is gone, with the
sealed `LspColumnDispatch` and its projection meta-test. All three of its readers, completion, hover
and the field-member diagnostic, resolve a site's scope from the store now. The projection's whole
content was a mapping from twenty-nine classification permits onto three audience-specific arms, and
the mapping was the part worth keeping: as a resolution it is two rules and two silences, so what
looked like the substrate's hardest piece was a switch standing in for a rule nobody had written down.

The estimate above was wrong in a useful direction. The seven `Resolve` arms are not seven questions,
and the participant arm that looked missing was never a separate one: a `@table`-interface participant
reaching a column across a single-hop path is the path rule, the participant being a table like any
other. What the increment did need that the estimate missed was the verdict vocabulary's absence being
survivable, which it was, because the two silences worth keeping are structural.

* **The relation overrides a default; it does not answer the question.** A field whose columns come
  from its own parent's table contributes no row, because every reader already holds that binding and
  stating it here would make the relation a copy of `intent_bound_table` keyed one grain down. So
  absence means "the parent's scope stands", which is exactly the fall-through each reader already had.
  Half the anchor test's cases pin coordinates that produce no row, and they are the cases that say
  what the relation is: a root's field, a scalar field, a named type that is an interface, a field an
  authored claim diverted.
* **Two rules, not seven arms.** An authored `@reference` path resolves to its terminal element's
  table; a field with no path resolves to the table its named type is itself bound to. That second one
  needs guards to stay a reading of navigation rather than a guess, and the guard that matters is an
  anti-join against `intent_authored_field_claim`: a field the author claimed does not navigate to its
  named type at all. `@pivot` is named directly because the claim vocabulary has no arm for it yet, and
  the explicit guard folds into the anti-join the day that arm lands. Stating the gap in the DDL
  comment is the alternative to it becoming a silent divergence.
* **The silences are structural, and one candidate was refused.** A contested coordinate is silent, and
  an authored path reaching no single table is silent. A third silence was available and rejected: the
  incumbent went quiet wherever the classifier had declined, whose real content was "a report already
  covers this coordinate", and the relation that holds those reports is the rejection residue, which is
  scheduled to drain. A derivation reading it would go quiet the day that family leaves, with no
  compile error and no failing pin. So the case the incumbent pinned, an empty `@field(name:)` beside a
  path that resolves, now offers the terminal table's columns, which is what the author needs; the
  three tests pinning the old silence pinned a projection disagreeing with itself about one schema, and
  they now pin a path that genuinely reaches nothing.
* **A macro's rewrite is read at the authored end.** A connection field's own named type is the wrapper
  `@asConnection` synthesized, so the named-type rule reads `graphitron_field_synthesis`'s authored type
  expression instead and takes its named type, three `REPLACE`s removing brackets and bangs. The
  alternative was walking the expansion's `nodes` field, which couples the view to the shape this one
  macro expands into. The pinned connection case is what forced the choice, and it is the better rule
  for any macro that rewrites a type expression.
* **A resolved table is a key, and the readers now say so.** The projection's `Resolve` carried a bare
  table name, so both column readers matched it case-insensitively across the census and an ambiguous
  name quietly contributed two schemas' columns. The view answers with the whole `sql_table` key, so
  the resolved arm reads one table and the spelling-keyed read stays where a spelling is genuinely what
  the reader holds. Both overloads live on `CatalogColumns`, the difference stated in its javadoc.
* **What is still the projection's, and why it did not block this.** All three readers still fall
  through to `typesByName()` for the parent's own scope, because a class-backed parent's member slots
  need `intent_type_backing_class` joined to the `jvm_` census. That fall-through is one dispatch, not
  three, and the store arm now runs ahead of the snapshot's freshness gate: a site's scope is a fact
  about the schema, so a document whose snapshot is unavailable still resolves it. The next increment
  is the member slots, and it retires `typesByName()` and `TypeBackingShape` from the language server
  outright, those three arms being their only readers.
* **Diagnostics reads the store now, which it never did before.** The arm needed a handle threaded
  through `Diagnostics.compute`, so the entry point gained a store-bearing overload beside the
  store-free one, and the document service wraps the whole file's diagnostics in one read transaction.
  The store-free form answers as if the store were unavailable, which is the same posture the class
  already takes on a snapshot it cannot trust, and it is what keeps the thirty-odd existing call sites
  unchanged rather than churned.

## Settled while building: a class's members are a relation, and the binding is not

`intent_class_member_slot` landed: what member names a backing class offers an SDL author, a record's
components or the bean accessors of anything else. Five readers, which is every surface that asked the
question: completion offers the slots, hover names one, the field-member diagnostic reports a name that
matches none, goto-definition jumps to the declaration behind one, and the MCP schema resource lists
them. The projection's member lists are gone with `TypeBackingShape.MemberSlot`, and the two class
permits now name a class and nothing else.

* **The bean rule was a rule, not a projection.** `get`/`is` plus an upper-case letter, first letter
  lowered, no parameters: that ran per build in `CatalogBuilder` to hand the same list to five
  readers, and it is a function of the classpath census with no graph in it. In the DDL it is one
  relation with the two prefixes joined as data. The projection decided which population a type had by
  its permit, one list per type; a relation over the census has both populations available at once, so
  it has to say which one a class answers with, and it says the class's declared form does: a record
  offers its components, and a record that also declares `getTitle()` still offers `title` once. That
  is the one place the relation had to decide something the projection got for free from its own shape,
  and it is pinned by a fixture record carrying exactly that accessor.
* **Keying follows the question, not the family.** Every other `intent_` resident leads with
  `graph_name`, and this one leads with the census's `source_name`, because the question is about a
  class. Keying it by graph would have stored one copy of the answer per graph that reads the class,
  which is a claim about the graph the rule never makes. The stratum is chosen by what produces a
  row, a rule rather than a transcription; the family header now says so.
* **An origin column that is a function of the class, carried anyway.** Every slot of one class
  shares its origin, so the column is redundant per row. It is carried because the two readers that
  fork on it hold a slot and not a class: the diagnostic picks the word it calls the member, and the
  jump lands on a field for a component and on a method for an accessor. That second one is an
  improvement rather than a transcription, the target now following the slot the store answered with
  instead of the permit that routed the arm.
* **The MCP schema resource moved too, because the alternative was two spellings of the rule.**
  Leaving its member lists on the projection would have kept `beanAccessorSlot` alive beside the
  view, and two spellings of a rule agree exactly until one changes. It is a reader of the relation
  now, on the same terms as the language server's four; the resource's own vocabulary is unchanged.
* **What did not move, and why the estimate was wrong.** The naming table had one row for the class
  binding and its member slots together. They are two questions, and only the second is derivable:
  which class backs a type is a reflective walk that grounds at `@service` returns and `@table`
  resolutions and extends through parent-accessor return types, and the census records those types
  *erased*, so a list-valued accessor hop has no element type to follow. Deriving the binding needs
  the census widened with un-erased element types first, and then the walk's own folds and gates
  besides. The table now carries the two rows separately with that blocker named, and the four arms
  keep reading the binding off the projection.

## Settled while building: an empty census is a fact, and the deferral has one home

The definition provider and the diagnostics coordinate dispatch read the store on every arm. Two
whole inventory rows close with it, and `CompletionData` leaves both classes plus `DeclTarget`, which
the same rules ran through for the declaration-name half.

* **An empty census is an answer, not a missing one.** Every arm that resolved a name against the
  projection carried a guard: if the population is empty, say nothing. The projection needed it
  because it could be half-populated, a snapshot existing before the consumer had run `mvn compile`,
  and a schema full of red class names is the wrong thing to show someone waiting for a build. The
  guard was a separate `isEmpty()` test per surface, ahead of the lookup, correct only in that
  order. The readers answer it in the same read now: `ClasspathClasses.presenceOf` and
  `CatalogTables.named` return *known*, *unknown* and *no census* as three arms, so no reader can
  hold the two questions in the wrong order and each states which of them it defers on.
* **Three answers, two shapes, and the difference is data.** The class reader's three arms carry
  nothing beyond which arm they are, so they are an enum; the table reader's *known* arm carries the
  rows, so it is a sealed interface. Same question, different shapes, decided by what the arm holds
  rather than by consistency for its own sake.
* **The pre-build silence moved up one level.** With every value arm reading the store, "no store"
  and "nothing captured yet" stopped being per-arm cases: the dispatch takes the handle as an option
  and the arms that wholly need it run only when there is one. Two arms take the option instead,
  because each has something to say without a census: the scalar reference's malformed-value check,
  and `argMapping`'s structural checks. How each arm takes the handle is what its requirement now
  looks like.
* **The `@node` deferral retires rather than moving.** It existed because a graph declaring no
  `@node` type looked exactly like a projection nobody had built. A store answers only after a
  capture and a capture writes every `@node` in the graph, so no rows means the schema declares none
  and the build will reject the reference; the arm flags it, and silence before the first build is
  the absent store. One guard fewer, from the substrate rather than from a decision.
* **The FK diagnostic's spelling set was fixed by the migration, as predicted.** It accepted only the
  generated constant, so a plain SQL constraint name red-squiggled even though the generator resolves
  it and the completion arm on the same coordinate offers it. Asking `CatalogKeys.named` is the fix:
  both namespaces, case-insensitive, qualifier scoping rather than stripping. That last part closed a
  hole the note said was untestable, a real name under a schema that does not declare it, which the
  projection had nowhere to record and therefore waved through.
* **The column arm's first hop changed, and both surfaces now agree about where a name lives.**
  Goto-definition validated `@field(name:)` against the enclosing type's own `@table`; the diagnostic
  had already moved to the site's resolved scope, which differs at a `@reference` path's terminal
  table. Two surfaces disagreeing about which table a name belongs to is one bug wearing two faces,
  so the jump reads `intent_field_column_table` and falls back to `intent_bound_table` exactly as the
  squiggle does. `TypeContext.tableNameOf`, whose last caller this was, retires.
* **A jOOQ name is what the generated class declares.** The projection's column list held the
  generated field name under a component called `name`, and the position join keyed on it. Reading
  `sql_column` gives both spellings, and the reader that answers whether an author's spelling matches
  is not the one that answers what the class declares, so the two are separate now: matched under
  either name, keyed by the jOOQ one.
* **What is left, and where.** `Diagnostics` keeps one projection read and `Definitions` none: which
  class or table backs an SDL type, the piece with no relation. The `@table` projection itself is not
  dead and cannot be: `CatalogFacts` takes it as capture's *input*, the same relationship the class
  census has with `ClasspathScanner`'s output, which is why `FixtureCatalogTest` now asserts on both
  sides of that capture rather than dropping the projection half.

## Settled while building: the label is not a fact, and the census carried one form too few

This increment started as the verdict-label arms and turned into a correction plus a census widening.
The plan was to grow the claim views' `classifier` vocabulary until it could name every variant
`FieldClassification` and `TypeClassification` permit, so the two label-rendering surfaces could read
the store. That is a store-side rebuild of the projections' arms, which this item's own "What retires"
section rules out in as many words, and the inventory rows that asked for it have been repointed.

* **The label was never one fact, which is why no relation should carry it.** The incumbent's
  twenty-nine field permits and twenty-two type permits are combinations: `Column` against
  `CompositeColumn` is an arity, `ColumnReference` against `Column` is the presence of a join path,
  `QueryTable` against `TableTarget` is whether the parent is a root, and `TableTarget`'s
  `splitBatched` and `hasLookupKey` are two more facts folded into the same name. A vocabulary naming
  every combination is the monolith this item exists to take apart, rebuilt in SQL and keyed the same
  way. The main classifier is the fact: which of `SERVICE`, `EXTERNAL_FIELD`, `NODE_ID`, `LOOKUP_KEY`,
  `ROUTINE`, `MUTATION`, `TABLE_COLUMN` claims the coordinate, which the claim views already answer.
  Everything else the label used to imply is a separate fact, already relational or already named as
  an unbuilt row above.
* **So the surfaces change what they show, and that is the point rather than a cost.** The inlay
  toggle renders the classifier; the hover block renders the classifier and then whatever facts the
  coordinate actually has, each read from the relation that owns it. A coordinate whose facts are not
  all modelled yet renders the ones that are, instead of waiting on a name that could describe all of
  them at once. `LspClassificationLabels`' pedagogical claim, that the label is the projection record's
  simple name so a reader can grep the taxonomy, retires with the taxonomy it teaches.
* **The type grain probably needs no reduction at all.** The field grain has a resolved view because
  it has an inferred arm to mask. The type grain's authored claims are `TABLE` and `ERROR`, with no
  structural claimant to mask them against, and the rest of what its labels encoded is type kind
  (`graphql_type.kind`), root operation (`graphql_root_operation`) or a Relay wrapper
  (`graphitron_connection`), all captured. A resolved type view would be a copy of the authored one,
  which the discipline forbids, so the reader reads the authored view until an inferred type claim
  exists.
* **What the census widening was actually worth.** It shipped in this increment under the wrong
  headline: it unblocks the reflective backing-class walk, which is real and still wanted, but the
  backing class is not what stood between here and the label arms, because the label arms were never
  going to be built. It stands on the two readers it fixed instead, both of which were showing the
  wrong form of a type. That is the whole case for it and it is enough of one.

* **The blocker was one column, and reading it is not a new relation.** The census recorded only the
  erasure the JVM descriptor carries, so an accessor returning `List<Film>` said `List` and a walk
  following the hop had nothing to follow. The classfile `Signature` attribute is what was missing.
* **The erasure stays, and the reason is a type variable.** The tempting shape is one column holding
  the declared form with the erasure derived from it, and it is wrong: `T` erases to its bound, which
  the declared form does not name. Neither form is a function of the other, so both are base columns.
  The direction each reader wants is a property of its question: a signature spelled for an author
  wants the declared form, and `@externalField`'s "does this return a jOOQ `Field`" check wants the
  erasure, because every `Field<X>` answers that the same way.
* **A pair of parameter lists is not a pair of positions.** The signature attribute's argument list and
  the descriptor's differ in length where the compiler synthesised a parameter, and there is no
  position-wise correction for that. So a length mismatch falls back to the erasure for the whole
  method rather than pairing a declared form with the wrong parameter, which is the failure that would
  have shipped silently and shown an author a type their source does not contain.
* **The reader arrived with the fact, and it was a defect.** `intent_class_member_slot.display_type`
  and the method-signature hover were both showing the erasure, so a hover on a `List<Film>` accessor
  said `List` where the editor line beside it said otherwise. The member slot's column is display-only
  and now carries the declared form; its own DDL comment used to argue that being erased was why the
  binding walk could not be derived from it, and that sentence is gone with the reason for it.
* **The fixtures compile rather than declare.** The member-slot cases read a real classfile scan of
  this package's fixture types, so a generic component and a generic accessor are the compiler's own
  `Signature` attributes rather than strings a fixture wrote. A fixture that declared the attribute
  itself could assert a census no compiler produces, which is the whole reason that test scans.

## Settled while building: the inlay's label is the classifier, and silence is an answer

The first of the two label surfaces moved. `collectClassificationHints` reads `ClaimClassifiers`, a
new pair of readers over `intent_resolved_field_claim` and `intent_authored_type_claim`, and renders
the classifier as the label. `LspSchemaSnapshot`'s two classification maps have one reader left in
this arm's place: the hover, which is the next increment and the one where the fact list has to be
chosen.

* **The surface got smaller, and that was the finding rather than the cost.** The incumbent arm
  labelled every field and every type, because every field and every type had a projection variant.
  The claim stratum answers about fewer of them: a plain SDL object, an enum, a Relay wrapper and a
  field nothing bound have no claim, and now get no hint. What is left is a hint at exactly the
  declarations graphitron has an opinion about, which is a better surface than one whose label at a
  plain object was a word for having nothing to say. The structural readings the old labels also
  carried (that a type is a connection, that a field nests) are each a relation away if a later arm
  wants them, and each would be its own arm rather than a widened vocabulary.
* **A conflict renders as its claims.** More than one classifier at a coordinate is what a conflict
  *is*, so the reader answers with a list and the label is the classifiers comma-joined. `Conflicted`
  named the fact that there were two; `NODE_ID, SERVICE` names which two. The reader is where the
  distinct lives, so a claim arm's collapse rule is that arm's business rather than something every
  consumer has to know held.
* **The two arms now run off different sources, and the gate had been shared.** `compute` used to
  return nothing at all unless a classification snapshot existed, which after the move would have
  hidden store-backed labels from a session that had captured but not generated. Each arm is now
  gated on what it reads, and the freshness section of the manual page says so: the classification
  arm rides the capture cadence, the inferred-directive arm the generator's.
* **The region is bounded before the query, not after it.** The arm collects the visible declaration
  sites first and asks the store once per grain for exactly those type names. The alternative, a
  whole-graph fetch filtered while rendering, pays per keystroke for declarations nobody is looking
  at, and the walk was already visiting the sites it needed.
* **Where the tests live follows what they read.** `InlayHintsTest` keeps the snapshot-reading arm
  and passes an empty handle at every call, which is also its assertion that the arms are
  independent; the new `ClassificationHintsTest` captures a real store and asserts under an
  unavailable snapshot, which is the same assertion from the other side. Its fixture schema is one
  coordinate per shape the arm answers: claimed type, structurally claimed field, authored claim
  masking a structural one at a coordinate whose name *does* match a column, two claims at one
  coordinate, and declarations nothing claims.

## Settled while building: the hover's fact list, and the round-trip fact the store did not have

The second label surface moved, and with it the two classification maps left the language server's
declaration arms entirely. `DeclarationHovers` renders a heading of the classifiers claiming the
declaration and a list of labelled values behind them, read through the new `ClaimFacts` from the
relation that owns each. `LspClassificationLabels` is gone; the 29-arm and 22-arm switches over the
projection permits are gone with it.

* **The block is a heading and a list, not a variant with a payload.** The reader answers with
  `DeclarationFact(label, value)` pairs. A typed per-classifier payload would have been the sealed
  taxonomy again in a second home, and it would have gone mostly null at every coordinate for the
  same reason the projection records did. A claim growing a fact is now a query in `ClaimFacts`, not
  an arm in a switch that every other claim's reader has to keep compiling against.
* **Which facts made the cut, and the rule that decided.** A fact is in when the relation that owns
  it is one join from the coordinate and the value answers a question an author asks at the
  declaration: the matched column and its table, which naming tier matched, the `@field` binding's
  name where it differs from the field's own, the class and method behind a service or external
  field, the node target, the routines in application order, the DML verb and table, the bound
  table, the error handlers, the resolved join path. Out: `splitBatched`, `batched`, `isList`,
  `isLookup`, `override`, `tableBound`, the participant lists and the discriminator column. Each of
  those is real; none of them is asked for at a declaration name today, and each is one arm away the
  day someone asks.
* **The round-trip fact is the one the store did not have, and it is now a relation.**
  "Does this field launch a query of its own" is the question the trimmed list would otherwise have
  dropped, and it is a derivation over four independent transcriptions, so it is a view:
  `intent_field_separate_fetch`, one row per (coordinate, rule), rules `SPLIT_QUERY`,
  `TENANT_FAN_OUT`, `SERVICE` and `ROOT_OPERATION`. Rows rather than a precedence, on the claim
  views' own discipline: a coordinate several rules reach is several rows and the arity is the
  answer. The root arm keys on the root operation binding rather than the three conventional names,
  which is `intent_field_demand_rule`'s precedent and the same known difference from today's walk.
* **The view owns what its absence does not mean.** The implicit split on a `@table`-typed child of
  a class-backed parent needs the backing-class resolution the census does not carry yet, so the
  relation is incomplete in a way a reader could misread as "this field inlines". The DDL comment
  states the prohibition directly (report a rule you find; do not report the absence of one) and the
  reader's javadoc and the manual page repeat it, because an unstated hole in a relation whose whole
  point is a cost estimate is the kind of thing a consumer assumes away.
* **A claim-independent fact has to hold the block open.** Gating the block on there being a claim
  hid the round-trip line on exactly the field that most needs it: a `@splitQuery` child returning a
  table type is claimed by nothing, no directive naming what it is and the structural classifier
  reaching only leaf fields. The block now opens when the classifiers are empty but a join path or a
  fetch rule is not, and renders with no heading in that case, the coordinate standing on its own.
* **The gate split again, the same way the inlay's did.** The classification block needs the store
  only, the description overlay beneath it still needs the snapshot for its binding resolution, and
  `compute` no longer refuses the whole hover when the snapshot is unavailable. The one-argument
  convenience entry, which meant "classification only, no store", is gone: with the block reading the
  store, that combination renders nothing at all.
* **The test moved to where its substrate is.** `DeclarationHoversTest` is replaced by
  `ClassificationHoverTest`, beside the other store-backed LSP tests, asserting under an unavailable
  snapshot exactly as `ClassificationHintsTest` does. Its fixture is one coordinate per shape:
  column match, a binding that matched under another name, a service, a conflict, a DML mutation, a
  split child claimed by nothing, a root field, a claimed type, an error type, and declarations
  nothing reaches.

## Settled while building: the round-trip fact earns a surface, and a vocabulary in two artifacts needs a seal

A second pass over the increment above, on three things it got wrong or left loose.

* **The heading was imprecise, and the relation's own name said so.** The hover said "Launches its
  own query" over a list whose service arm reads "the service fetches independently of the parent's
  SELECT". A service call is not a query, and the relation is called `intent_field_separate_fetch`
  for that reason. The heading is now "Fetched separately", which is what every arm actually
  claims, and the manual's section leads with the round-trip framing so the plainer wording does not
  cost the reader the point.
* **The fact needed a surface that highlights, not one that answers.** A hover answers a question
  the author already thought to ask; the question "which of these fields cost a round trip" is one
  an author scans a whole type for. So the marker got its own inlay toggle,
  `graphitron.inlayHints.separateFetch`, rendering one word at each marked field. Its own key rather
  than a second label on the classification arm, whose contract is that its label is the classifier
  and only the classifier: a delivery fact is not a classifier, and an author auditing cost should
  not have to accept a label on every declaration to see it. The two store-backed arms now share one
  walk of the visible region and one bulk query per grain, so both toggles on costs a query per
  grain rather than per declaration.
* **A universal rule is not worth marking.** Every field of a root type carries `ROOT_OPERATION`, so
  an inline marker there repeats down the whole type and distinguishes nothing. The marker is silent
  at any coordinate a universal rule reaches, the hover still states every rule, and the distinction
  lives on the vocabulary rather than in either surface's rendering code. A `@splitQuery` on a root
  field falls out correctly: the generator ignores the directive there, and marking the field would
  advertise a split that never happens.
* **A vocabulary in two artifacts needs a mechanism, not a convention.** The rule literals live in
  SQL and their words live in Java, and the first cut had a `default -> rule` arm that would have
  rendered a new rule to a user as a raw `SCREAMING_SNAKE` token with nothing failing.
  `SeparateFetchRule` is now the single rendering home for both surfaces, and
  `SeparateFetchVocabularyTest` reads the view's own stored definition back out of
  `information_schema` and requires a constant per literal in it. The extraction asserts itself
  non-empty first, so a definition it stops matching fails loudly instead of passing on an empty
  vocabulary. This is the same shape as the inferred-directive arm's renderer-coverage test, and for
  the same reason: the arm that reads a vocabulary and the artifact that declares it are in
  different languages, so only a test can hold them together.
* **A test comment claimed more than its fixture proved.** A case asserting the marker's silence was
  written as though its fixture had a class-backed parent, which it did not: it was a plain object,
  and whether the generator splits there is not something the case established. It is gone. The
  known gap is documented where it binds every reader, in the relation's comment, and the test class
  says plainly that pinning it needs a fixture nobody has built yet. An overstated test comment is
  worse than a missing test, because the next reader believes it.

## Settled while building: the inferred `@table` ghost, and an arm whose absence case was fiction

The inferred-directive arm splits by grain rather than moving whole, and one of its two passes turned
out to have been covering a case no capture can produce.

* **`@table` moves, `@field` and `@reference` stay, and the reason is per relation.** The `@table`
  renderer wanted one thing: the table a type is bound to. That is `intent_bound_table`, the same
  derivation the column-match classifier stands on, so the renderer now reads it through
  `BoundTables` and the classification snapshot loses a reader. The other two cannot follow yet and
  not for the same reason. `@field` fires at sites whose column resolves against a table that is not
  the parent's own binding, and while `intent_field_column_table` names that table, no relation
  answers which column an effective name matches *at* it; that derivation is the next slice, and it
  generalises `intent_column_match_claim`'s matching tail over a site-resolved table.
  `@reference` fires only on an omitted path, so its source is the foreign-key discovery between two
  bindings, which is unbuilt. *(Superseded for `@field`, twice: the relation named here as the next
  slice turned out not to be needed, and the renderer has since moved. The two later sections on the
  missing relation and on the `@field` ghost carry what actually happened. `@reference` has since
  moved too, the discovery being built; the section on it carries that.)*
* **A partial move is worth shipping because the cadence change is the user-visible half.** The
  `@table` ghosts now render off a capture alone, so an author who has never had a successful
  generator pass sees them, where before the arm was silent under `Unavailable`. The `inferredDirectives`
  toggle therefore spans two cadences today. That is documented in the manual's freshness section
  rather than hidden, and each of the three renderers is silent when its own source is missing rather
  than holding the others back.
* **The absent-directive arm's unit test pinned an unreachable state, and the case behind it is real
  and unserved.** The arm renders a whole `@table(name: "...")` ghost at a declaration carrying no
  `@table`, and its test pinned that on a plain `type Customer { ... }` against a hand-built snapshot
  saying `Customer` was table-bound. The projector produces `PlainObject` for a directiveless object,
  never `Table`, so that snapshot was a state no build ships and the ghost never appeared in a real
  session. The test is deleted for that reason and not for the one first written here, which said
  nothing binds a directiveless type. Something does: a directiveless object reached from a field of
  a `@table` type is a nesting type, and its fields resolve against the parent's own table row, so
  `type Inner { title: String }` under `Film.inner` is bound to `film` as surely as `Film` is. That
  is the ghost's most useful case and neither surface has ever rendered it.
* **What a type's fields resolve against is a family, and the store carries one arm of it.** The
  question is the type grain of what `intent_field_column_table` answers at the field grain, and it
  has at least three answers. A `@table` type resolves against its catalog table, which is
  `intent_bound_table` and the only arm built. A directiveless object reached from a field of a
  scoped type is a nesting type and resolves against the parent's own row, recursively, since nesting
  nests. A type produced by a class-returning field, a `@service` return or an `@externalField` lift's
  element type, resolves against that Java class's members, and the class threads down its child
  types along the accessor chain, so that arm is recursive too. A jOOQ table record is both arms at
  once, carrying a class and a table. Every arm but the first is consumer-derived, which is why
  `intent_bound_table` cannot be widened to hold them: it is keyed on `@table` applications, and
  these are keyed on the producing field. Rows and not a decline, as every binding is: one nesting
  type reached from two parents on two tables resolves against both, and a surface that must pick one
  reads the arity rather than guessing.
* **The class arm is the keystone, and four separately-tracked gaps are waiting on it.** Nothing
  authored declares a class backing any more: `@record` is deprecated and ignored, and the binding is
  reflection on the producing field's Java signature. So the class arm is not a view over facts the
  store holds, it is the unbuilt `intent_type_backing_class` walk, the one
  `intent_class_member_slot`'s comment already defers to. That same walk is what
  `Diagnostics.validateFieldMember` needs, what lets `DeclTarget` retire `TypeBackingShape`, and what
  the class-backed-parent arm of `intent_field_separate_fetch` is missing. The nesting arm is
  buildable on facts the store already has; the class arm is not, and the two together are what makes
  the absent `@table` ghost and the site-resolved column match the `@field` renderer wants land as
  one piece rather than four.
* **A class-bound type is silent on all three surfaces this item has shipped, for one reason.** The
  type-grain claim vocabulary is `TABLE` and `ERROR`, so a `@service` payload type gets no classifier
  label, no hover facts beyond its description, and no ghost. That is the same missing walk showing
  up as three silences rather than a hole in any one surface, and it is the strongest argument for
  taking the walk before another surface arm.
* **What the arm is for today is the `extend type` site**, whose base declaration carries the binding
  in another file, and that case is now pinned against a real capture. The arm's javadoc and the
  manual say that plainly, and both name the nesting case as absent rather than as impossible.
* **A strategy interface with one implementation and no input left is not a mechanism.** The absent
  pass dispatched through a sealed `AbsentArm` permit per registry entry, whose whole content was a
  switch over the classification variants carrying a table name. With the value coming from a
  relation, that switch has nothing to switch on, and what remained was a null-checked loop over
  three entries of which one was non-null. The pass is now the `@table` pass, named as such, with the
  reasons `@field` and `@reference` have no absence arm stated where a reader will look for them.
  Adding a third is writing a pass and arguing for it, which is what it should have been: the permit
  made a judgement call about what is worth showing look like a type-safety property.

## Decided before building: the backing class is a join over facts, not a fact

The keystone named above was `intent_type_backing_class`, and the first plan for it was wrong in a
way worth recording, because the mistake is reachable from any consumer-driven item and it does not
announce itself while you are inside it.

**The relation was named for a question, and the rest followed from that.** "What class backs this
type" is one caller's need, not something anyone observed. A relation named for a question takes the
question's shape. Its grain becomes whatever the caller's return value was. Its derivation absorbs
every special case the caller had. And because no independent source states the answer, the only
oracle available is the caller's predecessor. The first draft of this section had all three symptoms
and read as though they were separate concerns. One relation was to carry the root producer
grounding, the accessor hop, the cardinality agreement, the two-level carrier fork, the `@table`
shadow and the root mask, which is six clauses: a procedure with a return value rather than a fact
with a grain. Its anchor was to be total agreement with `RecordBindingResolver`, which makes the leaf
zoo normative and pins whatever bugs it has as invariants. That is transcription, not normalization,
and it would have landed the substrate as a copy of the thing this item exists to dissolve.

**The check that catches it costs one sentence.** Before the DDL, say what one row asserts, without
naming a consumer, a generator pass, or an existing class. `jvm_class_supertype` passes: this
classfile declares this supertype through this clause. `intent_class_member_slot` passes: this class
offers this slot under this name. "The class the resolver would bind to this type" fails out loud,
before any code is written. The census commits already shipped pass it, which is why they survive
this rewrite untouched; the plan for what came after them did not.

**Decomposed on grain, the six clauses are five facts and a filter.** Each stands alone, each is
anchored against its own source, and the keystone survives as the thin one:

- *The producer binding.* One row per field coordinate to the method producing its value, resolved
  from the `@service` and `@externalField` references against the census. A use-keyed derived join,
  which is the shape an authored coordinate resolving into a class-side one always takes here. The
  "unique method of this name" rule is an arity test over `jvm_method`, and a name that fails it is a
  validation rejection rather than a walk that silently picks one.
- *The type reference.* One row per position in a declared type, naming the class at that position.
  A census fact, and the finding below is that it does not exist yet.
- *The accessor hop.* One row per field coordinate and standing class to the class the hop lands on:
  a local join over `intent_class_member_slot` and the type reference, with no recursion in it.
- *The backing itself.* Reachability from the producer-grounded seeds over the hop relation. One rule
  rather than six, and its row now asserts something observable: this graph's type is backed by this
  class.
- *The cardinality reading.* Not a step in a walk. `graphql_field.is_list` and the producer's
  declared type either agree or they do not, and the disagreement is a detection the store does not
  have today. Burying it as a clause inside the walk is what hid it.
- *The `@table` shadow* is `intent_bound_table`, which exists. Two independently walked populations
  coalesced by a view is the provenance rule, not an arm of somebody's resolution.
- *The root mask* is a filter one consumer applies. It has no business inside a relation.

**The keystone still materializes, and now for a reason that fits in a sentence.** The SDL type graph
is cyclic, so the closure has the same no-safe-view-form problem `intent_type_domain`'s comment
states: a recursive UNION does not terminate, and the path-guarded form enumerates simple paths. H2
accepts a path-guarded recursive CTE inside a view, so the constraint is cost rather than syntax, and
the existing judgement stands. What changed is that with the clauses lifted out, the writer is the
`ReachabilityRows` template unmodified, a capture-cadence writer clearing its graph partition and
re-deriving under a monotonicity bound, rather than a walk transcribed into SQL wearing that
template's clothes. It registers `DERIVED` with an anchor under `no.sikt.graphitron.rewrite.derive`.

**A conflict becomes visible instead of resolved in passing.** A type reachable from two seeds
carrying different classes is two rows, and a view detects the contradiction the way
`intent_authored_claim_conflict` already does for claims. The walk's first-wins arm made that case
unobservable. So the decomposition does not merely preserve behaviour; it surfaces a population
nobody could previously ask about, which is the usual dividend and the reason grain is worth the
argument.

**Where a fact is missing the census grows, and none of the three rules needs reflection.** Overload
resolution is a count, per the producer binding above: the census keys a method on its descriptor
precisely so overloads are distinguishable. The two-level carrier's accessor probe is a join, not a
reflection call, `intent_class_member_slot` answering what slots a class offers and
`jvm_method_parameter` carrying the shapes the probe gates on; intricate to transcribe is not the
same as impossible, which is what the first reading conflated. Container detection is
`isAssignableFrom`, and it was a genuinely missing fact rather than a missing capability: a classfile
declares its own superclass and interfaces, and the scanner held the `ClassModel` exposing both and
recorded neither. That gap is what the first step closed.

**Why not run the real reflection inside capture, which would be less work.** Because it would write
a row the store cannot account for. The binding would be readable and not re-derivable: no consumer
could check it, extend it, or ask a variant of the question without re-running reflection, and the
substrate would carry a value whose derivation lives outside it. That is the private-model failure one
layer below where that failure usually gets caught. The store is the model, so a rule the generator
applies is a rule the store can state, and where it cannot, the census is short a fact and the answer
is to capture the fact.

**The assignability closure is a view, and it is smaller than the first plan made it look.** A class
hierarchy is acyclic, so the closure terminates without a path guard and states itself as a recursive
view, where the backing reachability is over the cyclic SDL type graph and takes the materialized
form above. That difference is the whole of why one is a table and the other is not. Worth stating
too that the closure is not load-bearing: the container question is closed over a handful of named
classes, so a general transitive relation is capability nobody asked for. It stays because it is a
cheap view over a census relation that had to exist anyway, not because anything waits behind it, and
it is sequenced accordingly rather than second.

**Found while building the first step: a type reference in the census is a display name, and the
walk needs a resolvable one.** Every type the census records outside `jvm_class.class_name` is
package-less. `jvm_method.return_type` reads `List`, `declared_return_type` reads `List<Film>`, and
`intent_class_member_slot.display_type` carries the same form because it is read off those columns.
That is correct for what those columns were added for, which is rendering a signature to an author,
and their comments say so plainly. It is also the reason the walk cannot start. Grounding a root
producer means asking whether a method's return is a container, and the container test compares
against `java.util.List`, `org.jooq.Result` and four others by qualified name; a simple-name compare
is precisely the collision `jvm_method.descriptor` exists to avoid, one package's `Result` being
another's. So the closure landing first is necessary and not sufficient: assignability becomes
answerable, and what a hop would feed it still does not identify a class.

The fix had two candidate shapes and the decomposition above settles which, so what was deferred to
the walk's own commit is decided here. A qualified twin beside each display column is the smaller
change and
answers the outer question only: an erasure's binary name is one field off the descriptor, and it
says nothing about the `Film` inside `List<Film>`. A type-reference relation is the larger one and
answers both: a declared type is a tree, `Map<String, List<Film>>` names three classes at three
positions, and a relation keyed by the owning coordinate and a position path records what the
Signature attribute already spells. The peel rule then descends by position instead of re-parsing a
rendered string, and the same relation serves parameters and record components, which peel by the
same rule. It is the second, because the first leaves a reader holding an unresolvable name at
exactly the depth the element type lives at, which is the whole of what the hop is after, and because
the type reference is a named member of the decomposition rather than a means to one relation's end:
a position in a declared type is a thing the classfile states, so it passes the row-assertion check
on its own and is worth having whatever reads it.

**The closure ends where the scan does, and that is a disclosed limit rather than a bug.** Nothing
puts the JDK on a classpath entry, so a chain reaching `java.util.ArrayList` has a row naming it and
no row under it. `org.jooq.Result` reaching `java.util.List` is one hop inside the census and
resolves, which covers the container the generator actually meets; a service declared to return
`ArrayList<Film>` rather than `List<Film>` would read as a non-container where reflection peels it.
The relation's comment states this so a derivation reads a missing hop as not-known-to-be-assignable
rather than as not-assignable, and the corpus anchor is where it would surface if a consumer writes
one. Closing it would mean capturing supertypes for names the scan never reached, which is a
capture-side question (a fact the store is short) and not a reason to hold the closure.

**The differential is a fact, not a test.** The `walk_` family is the precedent the first plan
ignored. Instrumenting the legacy resolver to write what it binds makes the comparison a shadow
between two relations in one store: runnable over any corpus rather than only the examples an
agreement test enumerates, checkable while the derivation is half built rather than only once it is
finished, and self-deleting, because the family header carries a removal criterion and the rows drain
when the resolver retires. A total agreement test does none of that and leaves the predecessor
normative with no expiry. Where the two disagree, each case is adjudicated on its own and the outcome
is either a fix to the derivation or a recorded behaviour change with its reason. Fidelity to the
walk is evidence here, not the specification.

**The sequence, in order, each commit green and none changing generator behaviour on faith.**

1. The census carries declared supertypes. Done: the scanner reads `superclass()` and `interfaces()`
   off the `ClassModel` it already holds and `jvm_class_supertype` lands on the `EQUALITY` arm with
   its census siblings. A supertype name the scan never reached is still a row, the JDK interface at
   the end of a chain being exactly the name nobody scans.
2. The census names types resolvably. Done: three type-reference relations on the `EQUALITY` arm,
   one beside each census relation that carries a declared type, per the section below.
3. The legacy walk writes what it binds. Done: `walk_type_backing_class` on the `ORACLE` arm, one
   row per type the walk bound to a class, written at capture cadence beside the claim-domain rows.
   Before the derivations rather than after them, so every step below has a differential to check
   itself against as it lands. Its three deliberate absences are the section below.
4. `intent_class_assignable`, the closure over step 1's declarations, as a recursive view. Done:
   one row per class and reachable supertype, on the `DERIVED` arm, with a path guard the plan did
   not expect and the section below argues for.
5. The producer binding: the field coordinate to its producing method, with the non-unique name a
   rejection rather than a silent pick. Done: `intent_field_producer_method`, a view on the
   `DERIVED` arm, one row per census method an `@service` or `@externalField` reference matches,
   the two directives coalesced by the view and told apart by a column. The section below carries
   what resolving it settled.
6. The accessor hop: the field coordinate and standing class to the class the hop lands on. Done:
   three views on the `DERIVED` arm rather than one, because the peel between the slot and the
   landing class is a rule two readers want. `intent_class_member_element` is the class a slot
   delivers once the containers come off and `intent_field_accessor_hop` is the edge itself, total
   over standing classes so that nothing in it presumes the closure; the union under them was
   keyed on the slot at first and moved a key lower in step 7, when the second reader turned up.
   The section below carries the two directions in which the hop differs from the walk.
7. `intent_type_backing_class` as reachability from step 5's seeds over step 6's edges, materialized
   on the `ReachabilityRows` pattern, beside the conflict view over types two seeds answer
   differently. Done, and the shadow is run rather than left available: on a fixture both sides can
   see, the derivation reproduces the walk exactly, and the one place they part is the disagreement
   the decomposition set out to surface. The peel moved down a level on the way, the second reader
   having shown its grain was one step too specific. The section below carries both.
8. The two facts that were clauses: the cardinality disagreement as a detection, and the `@table`
   population coalesced with the derived one by a view. Half done: the cardinality reading landed as
   `intent_producer_cardinality_conflict`, with `delivers_many` on the peel underneath it and
   `multiplies` on the container vocabulary. The coalescing view then wanted a payload the two arms
   did not share, which turned out to be a fact the store was short rather than a shape to choose
   between: `sql_table.record_class_fqn`. Captured, along with the decode the input axis needed for
   the same reason, after which `intent_type_backing` is the plain two-arm union the plan described,
   and `intent_type_backing_conflict` moved over it and gained a second disagreement. Done; the
   three sections below carry the question, the capture, and the view.
8b. The input axis, which the plan had named only as an absence in the backing relation's own
   comment: a producer's parameter backs the type of the argument it is fed from. Done as a second
   seed into the same closure rather than a second closure, which needed the parameter arm of
   `intent_declared_type_ref` and the path decode. The shadow runs it against the walk over a real
   classfile scan and the two agree. The section below carries it.
9. Make the fact answerable by a consumer. Done, and not the way this entry first read: it said the
   generator reads the fact and the resolver's copy of the walk retires there, which is the
   store-reading classifier the emit-path programme explicitly drops in favour of draining the walk
   from the consumer end. Attempting it produced a capture-ahead-of-classification reorder, reverted;
   the section below carries what that settled. What the attempt did turn up is a third difference
   from the walk that was not an adjudication but a defect, a hop contesting a grounding, and the
   fact that closes it, `intent_type_backing_seed`. The resolver's copy now retires when its last
   leaf consumer leaves, which is step 10's business and other items'.
10. The consumers follow, each its own commit: the class arm of the type-scope question, then
    `Diagnostics.validateFieldMember`, `DeclTarget` retiring `TypeBackingShape`, the
    class-backed-parent arm of `intent_field_separate_fetch`, and the three silent LSP surfaces.
    `walk_type_backing_class` and its writer were to drain with the last of them rather than at step 9,
    the differential being what keeps the duplication honest until then. That criterion did not survive
    the increment that met it: the walk's backing still has a reader outside this item, and the
    differential becomes load-bearing rather than redundant once the editor and the generator read
    different answers. The drain moves to whichever item makes the generator read the backing; the
    reasoning is in the section below. The class arm is done:
    completion, hover and the field-member diagnostic read `TypeBackingClass` over
    `intent_type_backing`, and the precedence between a grounding and a hop is the reader's rule,
    stated where a second reader can lift it into a view. The section below carries it. The table
    arm is done with it: `TypeMemberScope` answers the whole fork, the three surfaces read it, and
    `Diagnostics.validateFieldMember` is off the projection with them. The second reader turned out
    to be the one that could not lift the precedence, for a reason the section below carries, so
    `TypeBackingShape` leaves the language server with `DeclTarget` rather than before it. It has:
    `DeclTarget` reads the scope, no main source in `graphitron-lsp` names the permit hierarchy, and
    the sixteen switch cases the two resolver cores carried became two arms. One question stayed with
    the projection there and it is not a backing, which the section below states along with the fact
    that would close it. The class-backed-parent arm of `intent_field_separate_fetch` is done: the
    relation now names the split no author writes a marker for, the editor marks it and says why, and
    the arm's own prose owns two departures from the walk. What did not close is the prohibition the
    arm was blamed for, because a second population turned out to be missing and nobody had written
    it down; the section below names it and hands it on. The three silent surfaces are done with it: a
    payload type now carries the class backing it as its inlay label and as a hover line, a type two
    producers answer differently says so where nothing said anything at all before, and the third
    silence turned out to be the right answer rather than a gap, which the section below argues.

## Settled while building: a declared type is a tree, so resolving it is a relation per position

The census now names classes resolvably, and the shape it took answers three questions the plan left
open.

**Three relations rather than one, because the owners are three keys.** A method return is keyed by
a descriptor, a parameter adds a position, and a record component is named on its own. A single
relation over all three would carry a column that is NULL by kind, which is the reading the walk
reach relations already rejected for the same reason, and it could carry no foreign key at all,
where each of the three can point structurally at the census row it decomposes. A reader whose
question is uniform across the owners (the accessor hop, which stands on a member slot and does not
care which arm produced it) unions them in a view, which is the layer a reader's question belongs
in. That is the base-relations-follow-the-source, views-follow-the-reader split, applied where it
was easy to get backwards.

**A position, not a qualified twin.** The fork the previous section recorded resolves to the second
shape once you write the rows out. `Map<String, List<Film>>` names four classes at four positions,
and a twin column beside each display column answers for the outermost only, which is not the
position anything is after. The path is read outside in: the empty string is the type itself, a
digit is a 0-based type-argument index, `[]` is an array's component, joined by dots. So `List<Film>`
names its element at `0`, the map above names `Film` at `1.0`, and `Film[]` names `Film` at `[]`.

**A position naming no class has no row, rather than a row with a placeholder.** That covers a
primitive, an array (whose component is the next step down), a type variable, and a bare `?`. The
type-variable case is the one that earns its own test: a method returning `T` has an erased
`return_type` of `Object`, so the census reports a class at a position where the declaration named
none, and the relation follows the declaration. The bare wildcard is the same instinct as the
supertype relation's absent `java.lang.Object`, an implicit bound being a thing the source did not
write.

**Variance is carried.** `Film`, `? extends Film` and `? super Film` name one class and mean three
things, nothing else in the census tells them apart, and a consumer peeling an element type out of
the third would read it as the first and be silently wrong about which direction values flow. Same
argument that earned `declared_via` a column on the supertype relation: not recoverable, and wrong
in silence if dropped.

**Found while building: the descriptor reading is the common case, not a fallback.** A non-generic
method carries no `Signature` attribute at all, so most rows in these relations come off the
descriptor rather than off a signature. That is not a degraded reading. Absence of the attribute
means the erasure is the declared form, so the two paths are both the declaration and agree wherever
both exist, which is what lets one rule cover them. The parameter relation inherits its owner's
length-mismatch fallback for free, decomposing whatever the parameter row itself reports.

The anchor mirrors all three against the scan over the reactor's own classes, and pins what the
decomposition decides rather than copies: every name is qualified, some row sits at a non-root path,
and a root row's class agrees with the erased display column once the package is dropped, which is
what says the qualification names the same class the census already reported rather than another one
of that name.

## Settled while building: the shadow is thin because three of its columns belong to other relations

The walk's differential landed as `walk_type_backing_class`, and what it took to write was mostly
deciding what to leave out.

**The shadow reads the walk's own answer, not the resolver's internals.** `RecordBindingResolver`
holds observations, two axis memos, a fold and a rejection map, and none of that is the walk's
answer. The answer is what the classified model says backs each type, which the LSP-facing
projection already computes as an exhaustive switch over the sealed type hierarchy. The shadow
reduces that same projection to the class each shape names, so there is one switch rather than two
and the two readers cannot come to differ about what the walk decided. It also means the shadow and
the consumers retire together, since both stand on the projection the class arm of step 10 dissolves.

**Three populations are absent, each because another relation owns it.** A `@table`-bound type is
absent: the walk answers it with a table rather than a class, and that population is
`intent_bound_table`'s, so a second transcription would be a duplicate with worse provenance. A type
two producers bound differently is absent, because the walk resolves that by refusing to bind at
all; that is precisely the population the derivation surfaces as two rows plus a conflict view, so
the silence is a recorded behaviour difference rather than a defect on either side, and it is a
fixture rather than a footnote. And the kind of backing (record, plain class, jOOQ record) is absent
as a column, because it is a property of the class the census already states, so carrying it would
import the leaf taxonomy this item exists to dissolve into the relation that replaces it. What is
left is three columns, which is the shape a differential should have.

**The axis is not a dimension.** The resolver keeps a result memo and an input memo, so the obvious
reading is a row per type and axis. An SDL name is an output type or an input type and never both,
so the two memos are the walk's internal bookkeeping and the answer is keyed on the type alone. The
axis would have been a column that never discriminates, which is the same failure as a column that
is NULL by kind, one step further along.

**The containment is real and is still not a foreign key.** Every bound type is a registered type,
and both relations are written from one walked model in one pass, so the constraint would hold. It
is declined because the family's relations drain on separate clocks: the claim-domain rows go when
the conflict detection's gate flips to the demand relation, the backing rows when the generator
reads the derived relation, and a foreign key across two clocks makes whichever drains first
impossible while its sibling still writes. The containment is asserted by the projection's own test
instead, where it can be checked against the walked model both values come from.

**The seam got a name rather than an eleventh parameter.** The capture entry point already took ten
arguments, and the family will grow another grain before it drains. `WalkReach` carries what the
pass transcribes from the walked model, projected at one site, so a new grain is a component rather
than a signature change at three call depths.

## Settled while building: a hierarchy is acyclic in a program and not in a store

`intent_class_assignable` landed as the plan's recursive view, and one line of the plan's reasoning
turned out to be about the wrong subject.

**The guard is not about class hierarchies, it is about the store holding many of them.** The
argument above was that a class hierarchy is acyclic, so the recursion terminates without a path
guard. That is true of one program's classpath and the relation is not one program's:
`jvm_class.source_name`'s own comment already states that two runs' entries coexist as two
partitions, and that one class name may legitimately appear under several of them. The hop has to
join on the supertype's name across entries, because the ordinary chain crosses entries (a
consumer's class implements an interface a jar declares, and that interface's own supertypes are the
jar's rows), so a pair of names declared into each other by two entries is a cycle the census can
hold with both classfiles valid. Measured rather than assumed: H2's recursive `UNION` does not
deduplicate against rows earlier iterations produced, so such a cycle is not a wrong answer, it is a
build that hangs with no diagnostic.

**The guard is free on the shape a census actually has.** Over a synthetic census of 12,600 edges
shaped like a real one (5,000 classes at depth four under a 300-interface lattice) the two forms
produce the same 110,845 pairs, the unguarded one in about 300ms and the guarded one in about 330ms.
The guard enumerates simple paths, which is the form the plan rejected for `intent_type_domain` on
cost, and the reason it costs nothing here is the same acyclicity the plan was arguing from. So the
conclusion stands unchanged and only its reason narrows: acyclicity is what makes the guard cheap,
not what makes it unnecessary, and the view-versus-table split against `intent_type_domain` is
still exactly where the plan put it.

**Reflexivity is not omitted, it is unstateable.** A row saying `java.util.List` stands in for
`java.util.List` needs a `source_name` for `java.util.List`, and the names this relation exists to
reach are precisely the ones no classpath entry declares. Carrying the reflexive pair for scanned
classes only would be worse than carrying none: a consumer's container test would then answer one
way for a class on a scanned entry and another for a JDK interface, with nothing in the relation
saying which case it was in. So identity is the reader's own name comparison, and the relation
carries what a declaration asserts.

**Two columns that look like they belong are facts about one edge.** `declared_via` and a distance
both describe a hop, and the closure's row is a chain. A clause column would have to pick one hop's
clause arbitrarily or report a set nobody asked for, and a pair reachable by two chains has no
single distance. Both stay on `jvm_class_supertype`, which is where a reader wanting the
declarations rather than what they reach already goes.

**The anchor's census is hand-built, which is the opposite of the sibling's choice and the same
test.** `ClassMemberSlotTest` scans compiled fixtures because its rule reads a class's declared
form, and a fixture stating its own kind or descriptors could assert a census no compiler produces.
This rule reads nothing but the supertype edges, and an edge is a name and a clause, which is all a
hand-built reference states. What compiled fixtures cannot arrange is exactly what a closure has to
get right: a chain continuing into another entry's declarations, a chain ending at a name no entry
declares, and a type two chains reach.

## Settled while building: the producer binding resolves a reference, and refuses to choose

`intent_field_producer_method` is a join and lands as a view, which was never in doubt. What the
plan left open was what the relation does at the coordinates where the walk it replaces makes a
decision, and the answer in every case was to state the facts and leave the decision to a reader
that can be held to it.

* **The plan's "rejection rather than a silent pick" is stronger than it reads, because the pick is
  not stable.** The walk resolves an overloaded name by taking the first method the reflection API
  hands back, mirroring the projection's own first-match rule. The JVM specifies no order for that
  list, so the walk's answer for an overloaded name is not merely an arbitrary one of two, it is a
  value that may differ between runs of the same build on the same classes. The relation states
  every match and counts them, on `intent_bound_table`'s terms, and the arity is what a rejection
  needs. Nothing rejects yet, because nothing reads this relation yet; what changed is that the
  fact a rejection would stand on now exists and is not a pick.
* **Two directives, one view, and a column that says which.** `@service` and `@externalField` are
  separate relations precisely so a reader can tell which directive bound a field, and a union view
  that dropped that would be the provenance rule broken at the layer meant to preserve it. So
  `declared_via` is carried, on `jvm_class_supertype.declared_via`'s terms: it is not recoverable
  from the pair of names, and the two are not interchangeable, a service method being invoked for
  the field's value where an external field's is invoked once for the jOOQ `Field` the generator
  then selects. A coordinate carrying both directives is two references resolved independently,
  neither winning; the conflict is already `intent_authored_claim_conflict`'s to report.
* **The omitted-method fallback landed where its base relation said it would.**
  `graphitron_external_field`'s comment defers the fallback to a derivation, and this is the
  derivation: a reference with no method argument names the SDL field's own name. `@service` has no
  such fallback, so an application missing either name resolves to nothing. The asymmetry is
  authored rather than incidental, and stating it in one place means no reader has to know which
  directive forgives an omission.
* **Absence has two causes, and the relation is shaped so one join separates them.** A reference
  can fail to resolve because the census never reached the class (the scan's filters, an entry
  nothing read, the generated jOOQ package) or because the class declares no method of that name.
  Those are different messages to an author and the same empty result, so the boundary is pinned by
  half the anchor's cases: a `jvm_class` lookup under the graph's own sources is what tells them
  apart, and the relation's comment says so rather than leaving each reader to discover it.
* **A row is a match, not a verdict, and one census gap is why that had to be said out loud.**
  `@externalField` requires a static method returning a jOOQ `Field`, and the census carries
  neither a static flag nor anything a parameter-shape test could stand on. Filtering the arm on
  what the census does hold would have produced a relation that looks like it validates the
  reference and does not, which is worse than one that plainly resolves it. The static flag is a
  fact the store is short; it is named here rather than captured, because the rule that would read
  it is the external field's validation and that is not this step's work.
* **`@routine` is out, and not for the reason `@table` is.** The three binding relations are
  siblings, but a routine reference names a jOOQ routine rather than a class and a method, so it
  does not resolve against `jvm_method` at all. That is a different population rather than the same
  one under another arm, which is the test for whether a view should union something.

## Settled while building: the hop is three relations, and the peel is the one worth naming

The plan called the accessor hop one relation and a local join. It is three, and the reason is the
same grain argument the keystone lost: the peel from a declared type to the class it delivers is a
rule with two readers, and a rule with two readers that lives inside one of them is a rule that
drifts.

* **The peel is the fact, the hop is the join.** A slot declared as a `List` of `Film` delivers
  `Film`; so does a `CompletableFuture` of a `List` of `Film`, and so does a `Map` from a key to
  `Film`. That rule is wanted twice, once for the slot a hop stands on and once for a producer
  method's declared return, and stating it at each reader would be two spellings of one contract.
  So it is stated once, source-keyed, and both readers join it. The hop relation on top is then
  what the plan said it was: a local join, no rule inside it. (Where "once" is stated turned out
  to be one key lower than this increment put it; step 7's section carries the correction.)
* **The container vocabulary is named data, and the assignability closure cannot replace it.** Seven
  rows join into the peel, on the terms `intent_class_member_slot` joins its two bean prefixes.
  Recognising a container through `intent_class_assignable` instead looks tempting and does not
  work: nothing ships the JDK as a classpath entry, so `java.util.List` declares nothing the census
  holds and standing in for it is unreachable from below. That relation's own comment already said
  the container question is closed over a handful of named classes; this is the reader that proves
  it meant it.
* **The peel recurses, which the plan said it would not, and the difference matters.** A declared
  type is a finite tree, so the descent terminates on its own with no path guard and no bound the
  rule has to pick. That is not the recursion the plan was ruling out, which was the closure over a
  cyclic SDL type graph, and the depth stops being a magic number: the walk peels four levels
  because someone chose four. (Overturned by measurement in step 7, and the bound is back. The
  argument above was about termination, and the thing that made the recursion unusable was cost:
  H2 re-evaluates a recursive view once per outer row of whatever joins it. Step 7's section
  carries the numbers.)
* **Two populations fall away with no filter, because the census already omits them.** A
  primitive-typed slot and an array-typed slot name no class at their root position, so neither has
  a spine and neither delivers anything. The walk reaches the same answer through an explicit
  reject list. Here it is a consequence of the type-reference relation's omission rule, which is
  the better kind of agreement: nothing had to be written down twice for the two to match.
* **The edge is total over standing classes, and that is the property that makes it an edge.**
  Nothing in the hop says which class a parent actually stands on, so a coordinate pairs with every
  class in the graph's sources offering a slot of that name. A relation that narrowed to the
  standing class would need the closure that is step 7's job, and would be that answer wearing an
  edge's name. Totality is also why it is a view and must stay one: the product is large wherever a
  slot name is common, and small at every join a reader actually writes, because a reader binds the
  standing class first.
* **The hop differs from the walk in both directions, and the difference is one omission.** An SDL
  field's arguments are not read. The walk probes for an accessor whose parameters match them,
  where a slot is a no-argument member by definition, so the relation hops where the walk would not
  (an argument-taking field standing on a no-argument accessor of the same name) and stays silent
  where the walk would hop (a field whose accessor takes those arguments). Closing it would mean a
  slot relation that holds parameterised members, which is a different question from the one that
  relation answers and would drag `@field(name:)` completion along with it. Both directions are
  pinned as pins rather than expectations, and the adjudication belongs to step 7's shadow, which
  is the mechanism this item built for exactly this.

## Settled while building: the second reader moves the fact, and the shadow is run rather than kept

The keystone landed, and the increment that landed it spent most of its argument one level below the
keystone. Two things settled.

**A second reader showed the peel was keyed at a consumer, not at its own grain.** The peel from a
declared type to the class it delivers shipped one step earlier as `intent_class_member_element`,
keyed on a member slot, with a comment saying it was stated once because two readers wanted it. The
second reader arrived immediately and could not use it: a producer method's return is the same
declared form under a key no slot relation can hold, a service method being neither a record
component nor a bean accessor. So the rule moved down to the thing it is actually about.
`intent_declared_type_ref` names the census's declared types under one owner key and
`intent_declared_type_element` peels them; `intent_class_member_element` survives as the join that
reads that peel at a slot's own owner, and `intent_class_member_type_ref` retires, its work being the
same union one key too high. The loop the item names as its steady state ran here in miniature, and
the guard clause is the part worth keeping: the fact is modeled at its own grain, never at the
discovering consumer's convenience. A first consumer is a consumer too.

**The owner key carries a NULL and that is the union's shape, not a withheld fact.** A record
component has no descriptor, so the record arm's `owner_descriptor` is NULL, determined entirely by
`owner_kind`, on `intent_authored_claim_conflict.field_name`'s terms. The recursion joins it with
`IS NOT DISTINCT FROM` rather than coalescing to a placeholder, because a placeholder would be a
value the census never wrote. The parameter arm is the third relation of exactly this shape and is
deliberately not unioned in: it would need a position column NULL on both arms present, and no
reader peels a parameter yet. It joins when the input axis does.

**The closure is one rule, and the one condition on it is not a hop's property.** A field with a
producer of its own is not read off its parent, its value coming from the method rather than from
the member, so the hop over it is no edge of this closure. Everything else the walk carried at this
point (the first-wins child suppression, the cardinality guard, the two-level carrier fork, the
`@table` grounding, the root mask) is either another relation's or deliberately absent, and the
relation's comment names each with its reason. Objects and input objects only, on both ends, which
is what a class can stand for; that one restriction replaces the walk's reject list over Java
classes (`String`, `Boolean`, the `java` packages, arrays, enums) without importing it, because a
scalar-typed field names an SDL type nothing can back and falls away on the SDL side.

**Ambiguity is rows, and the conflict view is the dividend.** A type two producers answer
differently is two rows and `intent_type_backing_conflict` names it. The walk resolves that case by
declining the second observation to protect the first, then folding, so the contradiction is either
invisible or arrives as a rejection with the losing side already discarded. This is the population
the decomposition predicted it would surface, and it is now observable.

**The shadow was run, not merely made available.** The differential is the item's stated mechanism
and it would have been easy to ship the derivation with the mechanism unexercised, so
`TypeBackingShadowTest` runs both sides over one schema and one set of classes: capture writes the
derivation from a real classpath scan, and the walk writes its own row from a bundle built over the
same text. On the agreement fixture the two sets are equal, producer seed and three hop levels
included, with both counts asserted non-empty first so an empty answer on either side cannot pass
vacuously. On the disagreement fixture the walk is silent and the derivation carries two rows, which
is the difference stated as a pin. Getting both sides visible cost a small fixture population: the
existing service stubs are package-private, and the census keeps public top-level classes only, so
the walk could see them and the derivation could not. That asymmetry is a census filter rather than
anything about the backing rule, and the fixture classes are public so the comparison is about the
rule.

**The recursion had to go, and only a measurement could say so.** Moving the peel down a key made
its base the whole census rather than the bean slots, and that turned a tolerable view into one that
hung the build. The diagnosis took two wrong guesses first, both worth recording because both were
plausible: a stale H2 lock file left by a killed build, and a combinatorial blowup in the closure's
own loop. Neither was it. A thread dump put the build inside the seed query, and a probe over a
synthetic census of 800 classes and 16,000 methods put numbers on it: reading the peel by itself
took 363 ms, and the accessor hop, which joins it, took **369 seconds to return nothing**. H2
re-evaluates a recursive view once per outer row of whatever joins it, and the hop joins it with no
class predicate on purpose, being total over standing classes. So the cost is the product of the two
design choices, each defensible alone.

The fix is a bounded descent, four outer joins deep, and it costs the argument the previous section
made. That argument was about termination, which was never in question; a finite tree terminates
whatever form you write. What the measurement showed is that the form a reader can *join* matters
more than the form that reads elegantly on its own, and a recursive view in the middle of a read
path is a landmine for every consumer downstream of it. Four is also what the walk descends, so the
bound costs no agreement with the shadow, and a deeper nesting delivers the last container reached
rather than the wrong class, which `element_path` and a container-named `element_class` make
detectable rather than silent. The container vocabulary became `intent_delivery_container` on the
way, a relation rather than a list inlined in its reader, since the descent now reads it once per
level. After the change the hop is 10 s on the same adversarial synthetic census and the sakila
example captures and generates in three minutes with no fallback, where before it did not finish.

The general lesson is the one the item keeps relearning at a new layer: a shape argued from the
model alone is a hypothesis until something measures it. The grain decision above survived the
measurement unchanged; the implementation of it did not.

**Two shapes of absence are recorded rather than closed.** The `@table` population seeds nothing
here, and the reason goes one step past the decomposition's: the classes it would seed are the
generated jOOQ records the census excludes by design, so the subtree below a `@table` type is
unreachable from the store rather than merely unwritten. That is a capture-side gap and it is
`intent_bound_table`'s population besides. The input axis is absent too: a producer's parameters
back the argument types they map to, which needs the argument-mapping resolution and the parameter
arm of the type-reference union, and it is the next thing this chain owes.

## Settled while building: a clause becomes a detection, and the coalescing view wants a fact nobody captured

The cardinality half of step 8 landed and the `@table` half is held on a question, which is worth
stating rather than answering by picking whichever shape the SQL made easiest.

**The comparison already existed; what it lacked was a name.** The walk reads the SDL field's
cardinality and the producer's declared return, and uses the comparison as a clause: where they
disagree it declines to bind and reads the field as a carrier whose collection feeds an inner list
field. So the reading was there and its whole output was a silence, which is the shape a defect
hides in. `intent_producer_cardinality_conflict` is the same comparison stated as rows, and the
difference is that a reader can now ask whether a given coordinate is a carrier or an author error,
where before the walk answered by moving on.

**It needed one fact underneath it, and the fact belonged to the declared type.** How many a
declared type delivers is a property of the type, not of the comparison, so it is `delivers_many` on
`intent_declared_type_element` rather than a clause inside the detection. That in turn needed the
container vocabulary to say which containers multiply, so `intent_delivery_container` grew a
`multiplies` column. The map is the case worth having a column for: a map from a key to one value
delivers one and a map from a key to a list delivers many, so the map itself decides nothing and
only its value position does, which is exactly what the walk's own peel does and exactly what a
reader would get wrong if the vocabulary were a flat list of container names.

**A raw container delivers one, and that agrees with the walk for a different reason.** The walk
requires a parameterised type before it looks at cardinality at all, so a bare `List` is not
multi-valued to it. Here the descent simply never happens, so there is nothing to multiply. Same
answer, and neither side had to know about the other's reasoning.

**The held question: the two backing populations have no payload in common.** The plan says the
`@table` population is `intent_bound_table` and the two are coalesced by a view, provenance kept by
separate relations rather than a tag column on a merged base. The obstacle is what such a view would
carry. A class-backed type's backing is one column, a binary class name. A table-backed type's is
three, the `sql_table` key. A view carrying both is four columns NULL by kind, which is the shape
this stratum has rejected repeatedly, and a view carrying only the type and which kind backs it
makes every reader join the arm anyway.

There is a shape that dissolves it, and it is a capture widening rather than a modelling choice: a
`@table` type's backing *is* a class, the generated jOOQ record class for the table, which is what
the walk binds it to. If `sql_table` carried that FQN, both arms would carry one uniform
`class_name` and the view would be a clean coalesce with a provenance column, on
`intent_field_producer_method.declared_via`'s terms. `sql_table.class_fqn` is not it: that column is
the generated *table* class, captured for goto-definition, and its comment says so. The record class
is a fact the store is short.

The fork was whether step 8's second half waits for that widening or ships in the kind-plus-type
shape and is rewritten later. It was put up and settled the same way it should have been asked:
if the fact is missing, capture it. The section below carries what that turned out to mean, because
by then it was two facts and not one.

## Settled while building: the missing fact is captured, and capture is not shaped by who asked

Two pieces of the backing chain turned out to be blocked the same way, each on a fact the store
did not hold, and each with a cheap alternative that would have put the missing structure inside a
consumer instead. Both were captured. What the pair is worth recording for is the second-order
question: *at what grain*, given that only one reader had asked.

**The record class, and why it is a column rather than a relation.** `sql_table.record_class_fqn`
is `Table.getRecordType()`, one call on the `Table` the catalog walk already holds. It is a fact
about the table at the table's own grain, single-valued and always present, so it is a column on
`sql_table` and not a relation beside it. Two details are load-bearing. It is not derivable from
`class_fqn`: the relation between a table class's name and its record class's name is jOOQ codegen
configuration, so a store that computed one from the other would be guessing, and the anchor pins
the two as separate maps for exactly that reason. And a table jOOQ generated no record for reports
`org.jooq.Record`, which is recorded as written rather than nulled, because that is the catalog's
own answer and deciding it means "no backing" is a reader's judgement, not capture's.

**The argument path, and why the grain is the path and not the site.** The input axis needs the
head of an argMapping's right-hand side, and the store held the path only as a string. The
consumer-shaped capture was sitting right there: the walk uses `segments.get(0)`, so a
`head_segment` column would have satisfied the one reader that asked and been wrong the first time
anybody wanted the tail. The fact is the decomposition, so
`graphitron_argument_path_segment` records the whole of it, one row per position.

The grain question underneath that one is sharper, and it is where the family's own precedent
decided it. Seven relations in `graphitron_` carry an `argument_path`, so the obvious shape is a
decode child under each, following the `arg_mapping` to `_arg_mapping_pair` pattern the family
already uses. But a path's decomposition is a property of the string, not of the site that spells
it, and seven site-keyed children would copy one decode down every row that shares it. That is the
repeating group `sql_schema`'s comment already names, in the same schema, about the same mistake.
So the relation is keyed by the value: one decode per distinct path per graph, and every pair row
joins it on the column it already has.

**The decode was in capture's hand and being thrown away.** This is the part that settles whether
it was a capture concern at all. `GraphQLSelectionParser.parseEntries` already returns
`ParsedEntry.segments()`, and all seven writers were calling `String.join(".", ...)` on it to fill
the column. Capture was computing the fact and discarding it. Nothing was recovered here that a
parse had to be invented for; a `String.join` became a helper that writes the rows on the way past.

**Reach is pinned mechanically, because a list of seven is a list that goes stale.**
`ArgumentPathDecodeTest` enumerates every relation in the generated catalog carrying an
`argument_path` column and asserts each path it holds rejoins exactly from its own segment rows.
An eighth pair relation whose writer forgets the decode fails that without anyone remembering to
extend anything, and the rejoin pins the order and the density of the positions at the same time.

## Settled while building: the coalesce is trivial once both arms carry a class, and it finds a second disagreement

With the record class captured, `intent_type_backing` is what the plan said it would be: a two-arm
`UNION ALL` over `intent_bound_table` joined to its table's record, and the closure, with a
`declared_via` column saying which population answered. No NULLs by kind, no kind-plus-type stub, no
reader joining past it. The whole difficulty had been the missing fact.

**A table that reports `org.jooq.Record` is not backed.** jOOQ answers `getRecordType()` for every
table, and for one with no generated record the answer is the untyped `org.jooq.Record`. That is a
truthful catalog answer and a useless backing, so the fact is captured as reported and the view
drops it. Capture follows the source; the view follows the reader. Splitting it that way means no
consumer has to know the sentinel exists.

**The coalesce found a disagreement the arm-local view had been calling agreement.** A type can be
`@table`-bound and also reached by the closure, and the two can name different classes. The walk
resolves that by precedence, reading the table and never consulting the class. That is a defensible
reading, and it is a reading rather than a fact, so `intent_type_backing_conflict` moved to stand
over the coalesce instead of over the closure alone. A consumer that wants the walk's precedence
filters on `declared_via` and owns having chosen; what it can no longer do is mistake precedence for
agreement. The count is over distinct class names, so one class both arms happen to name is one
answer, not a contest.

Worth noting what this did to the view's cost: nothing. It is a grouping over a union of two
relations, both of which a reader was going to touch anyway, and the earlier lesson about recursive
views being re-evaluated per outer row does not apply to either arm.

## Settled while building: the input axis is a second seed, not a second closure

With the path decode in place the input axis is short. A producer's parameter backs the type of the
argument it is fed from, which is the argument sharing the parameter's name unless an `argMapping`
entry names that parameter on its left, in which case it is the head of the path on the right. That
last clause is one left join to the pair relation and one to `graphitron_argument_path_segment` at
position zero. Without the decode as a relation it would have been string surgery on a key, which is
the thing this schema does not do.

**One closure, not two.** The input seed writes into the same relation the result seed does, and the
existing frontier expands it without knowing which seed grounded a row: an input object backed from
a parameter has its own fields read off that class exactly as an output type does, so the whole
nested input surface follows from one statement. Two axes and one reachability was the shape all
along; what was missing was the ability to state the second set of seeds.

**The peel's third arm cost the key one column and nothing else.** `intent_declared_type_ref` had
forecast this in its own comment: the parameter relation is the third of exactly the same shape, and
it was left out because it needs an ordinal that is NULL on the other two arms. It joined now that a
reader arrived, `owner_position` came with it, and the four-level descent needed one more
`IS NOT DISTINCT FROM` per level and no new thinking. That is the grain decision from step 7 paying
for itself a second time: had the peel still been keyed at a member slot, this arm would have been a
second view.

Two arm-determined NULLs on one key is worth naming rather than glossing. `owner_descriptor` is NULL
exactly on the record arm and `owner_position` exactly on the two arms whose owner needs no ordinal,
and in both cases `owner_kind` determines which. That is the union's key shape, not a fact withheld,
and the readers that join the owner key blind already use `IS NOT DISTINCT FROM`.

**The differential covers it, and agrees.** `TypeBackingShadowTest` now runs the input axis against
the walk over a real classfile scan of public fixtures, and the two answer identically. That matters
more than the unit cases: the rule has three clauses that could each be plausibly wrong (which
argument feeds a parameter, what the parameter delivers, and whether the surface below it expands),
and agreement with an independent implementation over real classfiles is evidence none of them is.

**A nameless parameter feeds nothing, and that is a rule.** A consumer compiled without
`-parameters` leaves `jvm_method_parameter.parameter_name` NULL, and the walk skips such a parameter
for want of a name to match. The derivation states the same skip explicitly rather than letting it
fall out of a NULL failing a join, because the two look identical in a query plan and only one of
them survives someone reorganising the joins.

## Settled while building: the walk drains from the consumer end, so it never reads the store

Step 9 said the generator reads the fact and the resolver's copy of the walk retires there. Building
it produced a reorder, and the reorder was wrong, so what it cost is worth writing down.

**The mechanical blocker was real.** The generator classified at
`GraphitronSchemaBuilder.buildBundle` and captured afterwards, so no classifier could read a captured
row. Capture never needed the classified model, only the detection did, and the two were fused into
one call, which imposed the detection's ordering on the capture. Separating them by closing and
reopening the store fails on the demotion arms, where the store is a private in-memory database that
dies with its handle, so the capture had to hand its filled store back open. That all worked, and the
full reactor was green on it.

**It was scaffolding for an approach this programme dropped.**
`roadmap/planners-read-facts-emitters-read-commands.md` puts both halves out of scope in as many
words: the classification walk keeps producing the leaf model for its remaining consumers, and
reordering capture ahead of the walk is "scaffolding for a walk being drained from the consumer end
instead", dropped rather than deferred. So the walk is emptied by its consumers leaving, never
converted into a store client, and a store-reading classifier is the shape that programme declined.

**Which settles what this item's step 9 can be.** `RecordBindingResolver`'s answer reaches nobody
directly; it is threaded into the leaf model, and the leaf model's consumers are the plan and the
emitters (that item), the validator, and the LSP projection (this one). A backing class the store
holds is therefore consumed by those consumers reading it, and the resolver's copy dies with the last
leaf reader rather than by being re-pointed. The duplication in the meantime is what the shadow
differential is for, and it is doing its job.

The cost of finding this out was one commit, reverted. What survives it is the relation below, which
is a fact rather than scaffolding and would have been needed by whichever consumer arrived first.

## Settled while building: a third difference, and this one was the derivation being wrong

Two behaviour differences between the closure and the walk were on record and queued for
adjudication, the cardinality guard and the two-level carrier fork. Reading the walk closely enough
to retire it turned up a third that was not on any list, and it is not an adjudication.

**The walk refuses to read an already-grounded type off a parent's member.** It settles the root
producers first, folds them, and only then propagates through accessor edges, skipping any child
type that already has a binding. The comment at that line says why, and it is not about ordering: a
`film: Film` field on a parent whose accessor returns `LanguageRecord` would otherwise ground
`Film <- LanguageRecord`, collide with `@table`'s `FilmRecord`, and knock `Film` out of its `@table`
classification. A hop reads the parent's member type without checking it against the child's own
grounding, so the class it lands on can be simply wrong rather than merely second.

**The closure does not apply that rule, and the shadow could not have caught it.** Its one closure
condition is the field-coordinate one, and the frontier's anti-join skips only a type-and-class pair
it already holds, never a type that already has a different class. No fixture had a type both
grounded and hop-reached with different classes, so both sides answered the same thing for the wrong
reason. Adding one produces two rows here and one in the walk, with the conflict view now standing
over the coalesce and calling it a contest.

**So the store was short a fact, again, and again the consumer-shaped fix was the tempting one.** The
reading a consumer needs is "a grounding beats a hop", and the closure cannot express it: it
deliberately carries no route column, correctly, because a class reached by two routes is one
backing and a route column multiplies every reader's rows. What it can carry is the groundings
themselves. `intent_type_backing_seed` states them, one row per type and class a producer grounds,
both axes as arms. Its row asserts something observable without naming a consumer: this graph's type
is backed by this class by a producer of its own, rather than by being read off some other type's
class.

**It removes a duplicate rather than adding one.** The seed criteria already existed, twice, as two
jOOQ statements inside `TypeBackingRows`. The view is now their one home and the writer seeds from
it, so what a producer grounds is said once, in the place a reader can also see it. That is the test
a new relation should pass and this one does: it made the writer smaller.

**The precedence stays the reader's.** The relation says where a backing came from and nothing about
which to believe, on the same terms as the `@table`-beats-closure reading. When the LSP surfaces
want the same answer in the next step, that is the second reader, and it can lift into a view then.
`TypeBackingShadowTest.aGroundingBeatsAHopAndTheSeedRelationSaysWhichIsWhich` is the evidence the
reading is the walk's: the walk answers with the grounding, and the seed rows alone reproduce that
answer while the closure carries both.

## Settled while building: the first consumer reads the backing, and it takes the precedence with it

The class arm of the type-scope question is off the walk. Completion, hover and the field-member
diagnostic each resolved a class-backed parent by reading the class name off the permit the
classification projection handed them, which is the reflective walk's answer carried across the
seam; all three read `TypeBackingClass` now, an LSP fact reader over `intent_type_backing`. This is
the first consumer of the backing fact, and therefore the first real test of whether the closure and
its seeds are sufficient. They are.

**The reader owns the precedence, and that was the whole design question.** The relation states
every class it can reach for a type and prefers none of them, deliberately: a route column would key
it by path and multiply every reader's rows. So a consumer arriving with rows in hand has to say
what it makes of them, and this one says two things. A grounding beats a hop, because a hop reads
the parent's member type without checking it against the child's grounding and can land on a class
that is wrong rather than merely second; `intent_type_backing_seed` is what tells the two apart. A
type still answered two ways after that is answered not at all, because there is nothing to prefer
between two producers and a surface that guessed would offer one class's members while the generator
bound the other. Both rules are stated in the reader's javadoc rather than assumed of the store,
which is the point of leaving them here: the second reader is where they lift into a view, and
knowing whether they are one rule or two per consumer is what that decision needs.

**Silence is one answer with two causes, and that is deliberate.** A type nothing reaches and a type
two producers contest both render empty, because a surface asking what a class offers has nothing to
say in either case. A reader that needs them apart has `intent_type_backing_conflict` and its arity,
which is the shape a rejection would stand on; none of the three surfaces needs one.

**The permit no longer names anything, and a test says so out loud.** Both class arms now ignore the
`fqClassName` they were routing on, and
`DiagnosticsTest.theCheckRunsAgainstTheClassTheStoreNamesRatherThanThePermitsOwn` hands the arm a
permit naming a class the census never held: the projection-era dispatch went silent on it, and the
store's own binding reports the typo. That is the evidence the class binding moved rather than being
copied, and it is cheaper than any structural check over the two.

**What it cost was fixtures, and the cost is the honest kind.** A class-backed case can no longer
capture a placeholder schema and hand-build a projection over some unrelated type name, because the
class is now the store's answer for a type the document declares. Each such case captures its own
SDL with a producer grounding the type, which is what a consumer's schema looks like anyway. The
fixtures were also one class short: the census had a record and a POJO and no member typed by
either, so no hop could exist and the grounding rule had nothing to be tested against.
`FilmCardRecord` is that member, and `TypeBackingClassTest.aGroundingBeatsAHopOntoAnotherClass` is
the rule pinned at the reader that owns it.

**What still routes off the projection, and why this is an arm rather than the question.** The two
table permits and the three silences remain the snapshot's, so `typesByName()` and
`TypeBackingShape` stay alive for them. Both table arms are answerable from the store already, the
`@table` binding through `intent_bound_table` and a jOOQ record's table through
`sql_table.record_class_fqn`, which is the join `intent_type_backing`'s own table arm runs in
reverse; what they are waiting on is the remaining consumers of the projection rather than a fact.
`Diagnostics.validateFieldMember` is the next of them.

## Settled while building: the table arm finishes the question, and answers where the precedence lives

`Diagnostics.validateFieldMember` is off the projection, and so are completion's and hover's column
arms, because the three share the question and moving one alone would have left them disagreeing
about what a type is backed by. What replaces the permit switch is `TypeMemberScope`, one read
answering the whole fork: a type's members resolve against the columns of the tables it is bound to,
or against the member slots of the class that stands for it, or against nothing. `TypeBackingShape`
now has exactly one reader left in the language server, `DeclTarget`, which is the next entry.

**The precedence wanted lifting into a view, and trying to lift it is what showed why it cannot be.**
The class arm left two reader's rules in `TypeBackingClass` and said the second reader was where they
would become a view. This is that reader, and it arrives wanting a third rule on top: a `@table`
binding beats a class a producer grounds, which is the walk's own precedence and the one
`intent_type_backing` records as a choice rather than folding in. So the view looked due. What it ran
into is that the two arms share no payload. The backing relation coalesces them by carrying a class
name for both, reaching the table arm's through the table's generated record, and a table jOOQ
generated no record class for reports `org.jooq.Record`. Routing the table arm through the class
would therefore leave every table-bound type unscoped in any workspace whose catalog was generated
without record classes, which is a configuration and not a mistake. A view carrying both payloads
instead would be four columns NULL by kind, which is the reading `intent_declared_type_ref` refused
for the same reason. So the arms stay two relations, the table one reads `intent_bound_table`
directly, and the ordering between them is stated in the reader that needs it.

**A class that is a table's record is a table, and that is the join the coalesce runs backwards.**
`intent_type_backing`'s own table arm reads `sql_table.record_class_fqn` to turn a binding into a
class; this reads it the other way to turn a class into a binding, which is what the
`JooqRecordBacking` permit used to carry pre-resolved. It also disposes of that permit's second arm
for free: a jOOQ record no table claims is left on the class arm, where the census holds nothing for
it, because the classpath scan excludes the generated package by design. Silence, which is what the
standalone permit produced too, and now for a stated reason rather than by a case in a switch.

**Ambiguity became candidates instead of a spelling.** The permit carried a table *name*, so the
arms read columns by name and an ambiguous `@table` pulled in every schema's table that spelled it,
including ones the binding never resolved to. The scope carries resolved keys, so an ambiguous
binding contributes exactly its own candidates: still every one of them, ambiguity being rows, but
no longer a name match standing in for a resolution.

**Two cases died because the thing they pinned no longer exists.** Completion had a case for a
projection with no entry for the type and one for no projection at all, both asserting silence. The
column arm reads no projection now, so what replaced them asserts the opposite and is the more
useful fact: a session between builds still completes and still validates, the capture being on the
save cadence rather than the pipeline's. Hover's stale-snapshot case went the same way, and the
stale-prefers-over-silence rule it stood on survives at the declaration-name arm, hover's last
reader of the projection. `Hovers.richerHover` lost its snapshot parameter entirely: every coordinate
arm is a store read now.

**One behaviour changed rather than moving, and it is worth naming.** A buffer whose `@table` is a
typo used to silence the member check by accident: the permit carried the typo'd name, the census
had no columns under it, and the arm returned. The scope is keyed on the type name instead, so on a
saved graph where the type is really bound, the check now runs against the real binding while the
author is mid-typo. The case that pinned the old behaviour now captures its own document, where the
binding resolves to nothing and the type is scoped to nothing, so one mistake still yields one
diagnostic; but the general shape, a buffer ahead of the capture it is checked against, is inherent
to reading a save-cadence store and was equally true of the per-build projection.

**What it cost was again fixtures, and again the honest kind.** A table-scoped case can no longer
declare `type Foo @table(name: "film")` in a buffer while the captured graph knows nothing of `Foo`;
each such case captures its own document or names a type the shared capture binds. The census was
also short of a producer returning a generated jOOQ record, so the class-is-a-table arm had nothing
to be tested against, and `R157Service.makeFilmRow` is that producer.

## Settled while building: the projection's backing leaves the language server, and what stayed is not one

`DeclTarget` reads `TypeMemberScope`, so goto-definition and the declaration-name hover overlay
resolve a coordinate the same way completion, hover and the field-member diagnostic already do. No
main source in `graphitron-lsp` names `TypeBackingShape` any more. That was the milestone the naming
table set for this step, and it landed with one shape less than the plan expected and one question
more.

**The two switches collapsed into two arms, and the count is the argument.** `ofType` and `ofField`
each switched over eight permits, sixteen cases naming five payload shapes between them, to reach
four outcomes: a table's generated class, a column on it, a class, a member of one. The scope has two
arms and the outcomes are unchanged, because most of those cases differed only in which permit
carried the name. A jOOQ record with a table and a `@table` binding were two cases reaching one arm; a
record backing and a POJO backing were two more, and which of a component or an accessor a member
name lands on was already the store's answer rather than the permit's. Three silences were three
cases for one absence. What the sixteen encoded was where a value was parked, not a decision anyone
made.

**The degrade for a class with no members was standing in for facts, so it went.** A field cursor
inside a type backed by a jOOQ record no table claims used to jump to the class, and the reason was
stated in the code: the projection held no member keys for a record. The class it jumped to had never
been asked whether it declares the member, so the jump was a guess wearing a resolution's clothes.
The store answers that question for any class it holds and holds nothing for a generated one by
design, so the same coordinate now resolves to nothing, and the type name still names the class. Both
halves are pinned, and against the real population rather than a stand-in: a workspace whose jOOQ
model was generated but whose catalog this graph never captured, which is a configuration and not a
mistake.

**A candidate list resolves a column an early pick cannot.** `columnTarget` tries the binding's
candidates in order and answers with the first that declares the column, where the incumbent picked
one table up front and looked the column up only there. So an ambiguous `@table` whose column exists
on the second candidate jumps now instead of declining. The column named at the site is evidence
about which table the author meant, and that only became available to use once ambiguity arrived as
rows rather than as a spelling.

**One question is left with the projection and it is not a backing.** Which Java method a
method-backed field binds to is still `FieldClassification`'s, so `DeclTarget` still takes a `Built`
and the two declaration-name providers still gate on having one. The fact that would close it exists,
`intent_field_producer_method`, which is what `intent_type_backing_seed` already grounds itself on;
what makes it its own increment rather than a clause of this one is that the classification variants
carry more than a method between them, and the arm's arity read would meet the relation's own
`candidates` column. The remaining `typesByName()` read in the language server is a different thing
again: the `$source` sigil arm asks it whether the projection has seen a type at all, so that a site
whose classification is merely stale is left alone. That guard belongs with the question it gates, and
it should stay on the same substrate that answers it rather than being moved to the store while the
sigil admission stays behind.

**Inert fixtures are drift, so they went with the readers.** Five test classes were still building
permit maps and handing them to providers that no longer read one, which reads as though the backing
still mattered; they hand an empty projection now, and the cases that need a scope get it from a
capture. Two of them turned out to be one case: completion had a case for a projection with no entry
for the type beside a case for a non-carrier site, and with the column arm off the projection those
are the same input, the sigil arm reading the carrier map alone and having no membership guard of its
own. The diagnostic's guard keeps its own case, where the guard actually lives. The census also gained
a producer whose return type names a qualified class, `StoreFixture.producing`: a synthetic method
carries an erased display name, and a package-less name cannot ground anything, which is exactly why
the census records the qualified names a declared type mentions as rows of their own.

## Settled while building: three silences were two reads and one correct answer

The plan counted three surfaces a class-bound type was silent on and read that as three holes. Two of
them are one read of the backing the store now holds. The third is not a hole at all, and saying so is
the useful part.

**The label is a class name because the vocabulary it would otherwise need does not exist.** The
type-grain classifier vocabulary is `TABLE` and `ERROR`, and both are authored: a claim is what a
directive said. A backing is derived, so widening that vocabulary to admit it would have put a
reflection result into a relation keyed on directive applications, which is the shape this whole
stratum exists to avoid. Minting a category word for the inlay instead (`CLASS`, say) fails a simpler
test: an author looking at a payload type already knows it is not a table, and what they do not know is
which class. So the label is the class's simple name, and the CamelCase does real work, reading as a
class where a classifier reads as a category.

**A claim beats a backing, and not because the label has room for one.** A claimed type's classifier
already answers what it is, and its backing follows from that answer: a `@table` type's class is its
table's generated record, which the table facts name one join away. Suppressing the backing there is
therefore not a truncation, it is declining to restate a derivation. The population that gains a label
is exactly the population that had none.

**The contested type had no surface anywhere, and that is the finding worth keeping.** Two producers
naming different classes for one type is a schema the generator refuses to bind, and every reader that
needs one class is silent there by design, the resolving reader included. So an author whose two
`@service` methods disagree saw a payload type rendering like a plain object, with nothing anywhere
saying why. The hover now names the classes and says nothing binds. This is the first surface for a
population the walk does not merely fail to show but actively hides, by refusing the second
observation and keeping the first.

**The third silence is correct, and recording that is what closes it.** A ghost renders a directive an
author could have written with the argument filled in. No directive carries a backing class any more:
`@record` is deprecated and ignored, so ghosting it would advertise a directive that does nothing. The
absent-`@table` pass's own comment had lumped the class arm together with the nesting arm as "a missing
relation"; only the nesting half is, and the class half will never be a ghost. Both halves are now
stated where a reader will look, so the next person to find this silence does not build a renderer for
it.

**One bulk read, and the two rules stay in one place.** The inlay annotates a region, so the backing
read is bulk like the claim reads beside it: the seeds for every type asked about, then the coalesced
relation for the names nothing grounded. The single-type entry now delegates to the bulk one, which
matters because the grounding-beats-hop rule and the contested-is-empty rule are the reader's own and a
second copy of them would drift.

**The walk shadow does not drain here, and the criterion that said it would was wrong.** The sequence
entry had `walk_type_backing_class` and its writer leaving with the last of the language server's
consumers, on the ground that the differential was what kept the duplication honest until then. That
reads the duplication as being between the language server and the derivation. It is not. The walk's
backing has another reader that this item does not touch: `RecordBindingResolver` still binds record
types for the leaf model, which is what the plan, the emitters and the validator consume, and this
item's own step-9 section says as much when it concludes the resolver's copy dies with the last leaf
reader. So the number of live answers to "what class backs this type" does not drop to one when the
editor migrates; it stays at two, and only now do the two feed different surfaces. Before this
increment the editor and the generator both read the walk, so a divergence was impossible. After it,
the editor reads the derivation and the generated code reads the walk, and the derivation departs from
the walk in stated ways: a collection return backs a single-object field where the walk reads a carrier
and declines, and the two-level carrier fork is not applied. Those departures are pinned as pins
precisely because nobody has adjudicated them, which means an editor can now name a class the generator
does not bind. The differential is the only artifact that keeps that list honest, so it becomes
load-bearing at exactly the moment the old criterion would have deleted it. The drain belongs to
whichever item makes the generator read the backing, and the sequence entry now says so.

**Cleanup the previous increment owed.** Two javadoc paragraphs still described the class-backed-parent
split as missing from the separate-fetch relation, one increment after it landed. Both now name the two
populations that really are missing. The lesson is mechanical: when an increment closes a hole, grep the
prose that named the hole, because the artifacts that disclose an absence are exactly the ones no test
covers.

## Settled while building: the implicit split is three joins, and the hole it was blamed for was two

The round-trip relation deferred one arm on the ground that the census could not resolve a backing
class. That ground was gone, closed by this item's own earlier step, and the arm turned out to be
small. What the arm did not do is what its own comment promised it would.

**The arm is a join, an anti-join and no new fact.** A field whose parent the backing closure grounds
on a class, naming a type of its own that is bound to a table, is fetched separately. There is no
enclosing statement for such a field to be projected out of: the parent's value is a Java object a
producer handed back, so the child's table is a trip of its own. Everything that predicate needs was
already in the store the day `intent_type_backing_class` landed, which is the pattern this whole item
keeps producing. A consumer's gap reads as a missing arm and is a missing fact; once the fact is
captured the arm is three lines of SQL.

**The anti-join is the walk's precedence transcribed, and it reads the binding rather than the
coalesce.** A type both populations answer is one the walk reads as a table row and never as a handed
object, so the arm excludes a parent carrying its own `@table` binding. Two things about that. It is
a transcription and not a new opinion, which is why the disagreement stays visible on
`intent_type_backing_conflict` instead of being folded in. And it reads `intent_bound_table`, not the
coalesced view's `BOUND_TABLE` arm, because that arm drops a table jOOQ generated no record class for,
and what makes a parent a table row is its binding rather than whether a record exists for it. The
two relations would agree on every realistic schema and disagree on the one that matters.

**Two readings depart from the walk, and both are the intended rule.** An ambiguously bound child
splits here where the walk mints no table-backed verdict for it at all: an ambiguous binding is
contested, not projected into the parent's row, and an editor working on a half-written schema is
better served by the split than by silence. A `@table` interface child splits at either cardinality
where the walk inlines the single-valued one, the walk's discriminated-interface arm running before its
record-handed one. Both are recorded in the view's comment, following the root arm's precedent: state
the intended rule, name the difference, leave the adjudication to whoever owns the walk. The
alternative, transcribing the arm order, would have required a kind guard on the child and bought
nothing an author can use.

**The parent's kind is guarded and the child's is not, for a reason worth stating once.** The closure
holds input objects beside objects, and an input coordinate is not a fetch, so the parent is guarded to
`OBJECT`. The child needs no guard at all: `@table` on an input object is captured and ignored, and an
object's field cannot name an input type, so the child join is its own kind guard. Reaching for the
symmetric guard would have read as thoroughness and been noise.

**The seal over the rule vocabulary was accidentally correct.** It read every quoted upper-case word
in the view's stored definition and required a rendering for each, which held only for as long as no
arm compared a column against a literal. The parent-kind guard is exactly that, so `OBJECT` would have
arrived at the assertion as a rule the editor cannot render. The pattern now anchors on the rule
position, which is a structural property rather than a convention: `rule` is the view's last column, so
the literal standing for it is the select item immediately before its arm's `FROM`. A seal that passes
because nothing has yet violated an unstated assumption is worth tightening the first time the
assumption is tested.

**The prohibition survives the arm it was written for, because the comment had named one hole and there
were two.** The relation told its readers not to treat absence as "this field inlines", and blamed the
class-backed parent for it. With that arm in, a second population is still missing and no artifact had
recorded it: the polymorphic fan-in, where a list-valued interface or union child with a table-bound
participant batches through a DataLoader. The connection wrapper is a third, smaller one, a child
reached through `@asConnection` whose element type no relation names. So the DDL comment, the reader's
javadoc and the manual now name both rather than dropping the warning, and the fan-in arm is left to
whoever states the delivery rules; it is the same population `intent_field_delivery_rule` will carry,
and adding a fourth arm here first would have duplicated that work in the relation that asks the
neighbouring question. The lesson is about the shape of the disclosure rather than about this arm: an
incomplete relation should enumerate what is missing, because a comment naming one absence reads as a
census of them.

**The view moved down its own file, and landed next to its sibling.** H2 requires a view's referents to
exist when it is created, so reading the backing closure meant placing the definition after it. The
new neighbour is `intent_field_demand_rule`, the other coordinate-grained rule view, the one this
relation shares two literals with and whose comments already cross-reference each other. The
constraint was mechanical and the outcome is the ordering a reader wants, which is worth noting only
because the reverse happens often enough to be worth checking for.


## Settled while building: the missing relation was two rules already written, one grain apart

The `@field` inferred-directive renderer's blocker read "a column match at a site whose table is not
the parent's own, which no relation answers yet". No relation answered it, and the reason was not
that the resolution had never been derived. It had been derived twice, by two consumers who did not
know they shared a question.

`intent_field_column_table` answers the narrow question an editor asks at a `@field(name:)` site:
which table, *when that table is not the parent's own*. It carries the two navigation rules that can
move a site, an authored `@reference` path's terminal and a field's own named type's binding.
`intent_column_match_claim`, the structural column-match classifier, wanted the same navigation and
did not read it: it joined the parent's binding directly, so every name resolved against the parent
whatever the author had written about where the value comes from.

That is a defect and it was already recorded as something else. `ColumnMatchClaimTest` pinned a
`@reference` field whose terminal table has no such column: the walk rejects, the derivation claimed
a coincidental column on the parent instead, and the disagreement was filed as a transitional residue
waiting for `@reference` to become an authored claim. It was not waiting for anything. The
derivation was reading the wrong table, and an authored-claim migration would have masked the symptom
without fixing the read.

So the fact is `intent_field_column_scope`: which table the column names written at a field's site
resolve against, at every site where any name resolves. Three rules, the two the override view already
had plus a leaf field's own parent binding as the third, and absence meaning no name resolves here
rather than "ask the parent". `intent_column_match_claim` joins it. The override view is re-expressed
over it, keeping its own contract exactly: its rows are the scope's non-parent bases, which is what
"not the parent's own" means as a projection rather than as a restated rule.

Then it got slow, by two orders of magnitude, and finding out why took a measurement rather than a
guess. The corpus sweep reads the classifier over a store holding every corpus example, and it went
from seventy seconds to past thirteen minutes. My first guess was the window function the view's first
draft used to rank its three rules, so I removed it. That changed nothing, which is the useful part: a
guess about cost that survives being wrong is a guess that was never evidence.

A probe over twelve captured graphs, timing each relation on its own, found it in one pass. The scope
view costs 43 ms. The classifier joining it costs 3500 ms, and adding a graph predicate does not move
that number at all. H2 re-evaluates a joined derived relation once per outer row, so a classifier that
reads `graphql_field` and joins the scope pays the whole scope per candidate field, and the scope's own
depth (a path terminal over a hop view over a spelling resolution) is what gets multiplied. The repair
is the join order: with the scope first in the `FROM` clause it is evaluated once and the base tables
are probed per row, which is 47 ms for the same twelve graphs, and 24 ms for one graph. Seventy times,
for the order of two lines.

That constraint is now stated in both relations' comments, because it is invisible in the SQL and
reversible by anyone tidying a `FROM` clause. It generalises past this pair: any relation joining a
derivation this deep wants the derivation driving. The classifier was already paying a smaller version
of the same cost before this increment, reading its table binding from underneath `graphql_field` the
same way, so fixing the order left it faster than it started: the sweep runs in 14 seconds against a
pre-change measurement of 71, and those two numbers were taken under different load, so read the
direction rather than the ratio.

The window came out anyway and should stay out, on the grounds it was mistaken for. Disjoint rules are
a stronger statement than ranked ones: the authored-path rule and the two pathless rules are separated
by whether the field carries reference steps, and the two pathless rules by whether the named type is an
object or a leaf. One-row-per-site is then a property of the rules rather than of a collapse over them,
and the anchor test asserts it directly, so a schema where two rules overlapped would fail there instead
of being silently picked between. The one place a collapse was doing real work is the path arm, where an
element resolving to several rows that all reach one table is several rows; `DISTINCT` over a projection
that keeps only the table says that exactly, and demanding a single target is what makes it safe.

This is the measurement the item's acceptance asks for, arriving as a shape constraint rather than as a
latency budget. The sanctioned repair for a slow derivation is materialization with its argument in the
DDL comment, and it would have been the wrong repair here: nothing about the derivation was expensive,
one consumer was asking for it the expensive way. Measure before materializing, and measure the
relations separately, or you will materialize a relation to hide a join order.

This is not the shape the earlier section predicted. It read that no relation answers which column an
effective name matches at a site-resolved table, and that the next slice would be a derivation
generalising `intent_column_match_claim`'s matching tail over one. There was no need for a second
relation: the claim view already carries the matching tail, two name tiers and the collapse to a first
match, and what it had wrong was the table it matched against. Generalising it would have produced two
relations that match names, agreeing where they overlap by construction and by nobody's rule. The
whole change to the classifier is which relation its table comes from.

Two things stayed where they were, and both are about which relation owns a silence. The conflict
silence stays on the override view: a coordinate whose claims contradict each other has no settled
scope for an *editor* to offer columns in, but the classifier's raw reading there is what lets a
diagnostic say "would classify as a table column; `@service` overrides it", so folding that silence
one relation down would take the sentence away. And the three rules do not carry the same guards.
Navigating to another type's binding is diverted by an authored claim, a `@pivot` and a root parent;
resolving in your own parent's scope is diverted by none of them, because a field's parent scope
exists whatever claims the field. Different guards on rules of one relation looked like a smell and
is the opposite: it is the two questions being different, stated once each instead of averaged.

What the corpus sweep now says is worth reading precisely. Its anti-join still names `reference`, and
no longer because those coordinates disagree. A path the walk resolves produces a column carrier
whose compaction is not `Direct`, so the coordinate sits outside the arm the sweep compares. The
residue that was a disagreement is gone; what remains at that directive is a domain boundary.

The renderer this started for did not move in the same commit, and one arm is why. A `@field` at a
field of a class-backed parent resolves a member name rather than a column, which
`intent_class_member_slot` answers at its own grain, so the renderer's move is a two-arm read and only
one arm existed before this. The other arm exists now, and the move followed; see below.

One rename came with it. The language server's reader of the override view was called
`FieldColumnScope`, which is the name the new relation deserves, so the class is `FieldColumnTable`
now, named for the relation it reads as its siblings are. A reader wanting the total answer reads the
scope; a surface wanting the conflict silence reads the override; the names say which is which.

The lesson generalises, and it is the third time this item has hit it from a different side. A
consumer reporting "no relation answers this" is evidence about the consumer, not about the store. It
may mean the fact is missing. It may mean the fact exists at another grain. Here it meant the fact
existed, keyed correctly, with one of its rules living in the consumer that happened to need it
first.

## Settled while building: the `@field` ghost is two relations, and the third population is a gap

With the column arm's fact in place the `@field` inferred-directive renderer moved off the
classification projection. It reads `FieldMemberName`, one reader over two relations, in the shape
`TypeMemberScope` already set for the type grain: the arms share no payload, so the order between them
is the reader's and is stated there rather than assumed of the store.

**A column answers first, and it is read through the reduction.** The renderer asks
`intent_column_match_claim` for the column its site resolved, joined to `intent_resolved_field_claim`
so a coordinate an authored claim covers answers with nothing. That join is the whole difference
between a ghost that describes the generator and one that describes the classifier. `rating` on a
`@table`-bound `Film` is a real column and `@service` claims the field: the raw structural reading
survives in the classifier view on purpose, and a ghost naming that column would tell the author
graphitron resolved something it never reads. The incumbent projection got this right for a different
reason, the walk having produced `ServiceBacked` at that coordinate and no column at all, so reading
the reduction is what keeps the behaviour rather than what changes it.

**A class member answers where no column does.** `TypeMemberScope` says whether the parent's names
resolve against tables or against a class, and where it says a class, `ClassMemberSlots.named` is the
exact-spelling read of the slot rule. Nothing about the bean-versus-component fork enters the LSP: the
class's declared form decides it in the DDL, and the reader takes the slot's `name`, which is by
definition the spelling an author writes into `@field(name:)`.

**One population is now a stated gap rather than a silent one.** A type nothing binds, grounded on the
row type jOOQ generated for a table, resolves its member names against that table's columns.
`TypeMemberScope` knows this and offers them; the column arm cannot reach it, because
`intent_field_column_scope` derives a site's table from a `@table` binding or an authored path and
never from the parent's backing class. So the ghost is silent there, where the projection rendered a
column name for part of that population. That is a narrowing, and it is recorded in three places
rather than absorbed: `FieldMemberName`'s javadoc, an asserted test case that says why the silence is a
gap, and here.

Closing it is a fourth basis on the scope relation, not a lookup in the consumer, and it is a bigger
change than it looks. Resolving a parent's backing class to a table means the grounding rule and the
contested rule that `TypeBackingClass` owns today, plus the record-class-to-table mapping
`TypeMemberScope` owns, would have to be facts rather than reader code. That is its own increment, and
a good one for a reason beyond this ghost: a resolution rule living in the consumer that needed it
first is the exact shape this item has now hit four times. Writing the match against the table from the
LSP instead would have been the fast repair and the wrong one, putting a second copy of the match rule
beside the store's.

The ghost's text is worth one note, because it makes most of the test cases turn on presence rather
than content. Both arms resolve *by* the field's own name, so the value they fill in almost always
spells that name back. What the author actually reads is whether a ghost is there: a bare `@field` with
one beside it resolved, one without it did not. Two cases carry more than presence, and they are the
two worth having. The authored-path case renders a column the parent's table does not have, which is
evidence of where the resolution ran rather than that it ran. The masked case has a column under it and
shows nothing.

## Settled in review: the reads are too small, and "ghost" was cryptic slang

Two corrections from the same review pass, one to the vocabulary and one to the shape of every read
this item has shipped. The second changes the remaining plan, so it is recorded here rather than
absorbed.

**"Ghost" retires; a hint is a resolution overlay.** The word came in with the arm's first
implementation and spread through this item's later sections and into the new tests' names. It means
"missing" nowhere, which is the objection: it is cryptic where the thing itself is plain. A hint that
renders text the author did not write is a *resolution overlay*, in two arms, the argument overlay at
a bare directive and the directive overlay at a site carrying none. The word also collided head-on
with the LSP test suite's own convention, where `GHOST` and `Ghost` name a table, column, type or
method that resolves to nothing: within one package the same word carried opposite polarity, one
being a name with nothing behind it and the other a rendering of something that resolved. The
fixtures become `MISSING` / `Missing`, prose and test names take "resolution overlay", and the
published config key `graphitron.inlayHints.inferredDirectives` and its field stay as they are, being
both clear and in users' editor settings. Sections above this one keep the old word; they are settled
history, not a cleanup backlog.

**Every read is one relation, and the answers are assembled in Java.** There is no use of `MULTISET`,
`row()` or `Records.mapping` anywhere in `graphitron-lsp`. One hover on a field declaration costs the
classifier read, the join-path read, the separate-fetch read, and then one query *per classifier at
the coordinate* inside a loop, plus the description reads: four to seven statements with an N+1 in
the middle. The `@field` overlay costs up to three statements per site, called per directive node, so
a file with forty bare sites is up to a hundred and twenty statements, while the directive-overlay
pass over that same file is one. Both disciplines therefore live inside one collector, and the bulk
one is already stated in this item ("a query per grain rather than per declaration"). The readers
shipped most recently, this item's own, ignore it. `FieldMemberName`'s javadoc saying the arm order
"is the reader's" is that defect written down and called a design.

The cause is worth naming because it will recur otherwise: each reader was built to own exactly one
relation, which is right about *facts*, and nothing said where *composition* lives, so it fell to
Java one `Optional` chain at a time, each looking like two lines rather than like a rule.

**The fix is a projection, not a new view, and the distinction is the whole correction.** A view is
model: it merges same-grain sources, re-grains, carries keys plus its own products, and never embeds
a denormalized payload, because a consumer joins for payloads. A projection is one consumer's
`SELECT`: it joins the views it needs to the relations holding the payloads it wants, and it may
produce nested denormalized structure, because that is the shape it is about to render. `MULTISET`
belongs there, in the `SELECT`, never in the DDL. So "one statement per capability" is reachable
without the model growing shapes it should not have, and the doctrine is
`views-carry-keys-not-payloads.md` (R698), filed from this review.

What that rewrites in the plan:

* **A union view over the six per-classifier relations is withdrawn.** It was this item's proposal
  for answering a hover in one query, and it would have flattened six kinds' decoded components into
  a `(kind, value)` pair, nullable by kind, justified by one consumer's rendering.
  `intent_resolved_field_claim`'s comment already refuses exactly that shape in as many words.
* **`ClaimFacts.ofField` becomes one statement with no new DDL:** the claim reduction at the
  coordinate, left-joined to the six per-classifier relations, projecting typed columns, with
  `MULTISET` for the genuinely multi-row arms (routine refs, bound tables, error handlers) so their
  fan-out cannot corrupt the single-row arms. The six-way switch goes: the classifier column already
  says which join answered. The kind-to-label mapping stays in Java, where this item settled it.
* **`intent_field_member_name` is the one new view, and it earns it:** two sources at different
  grains, the column match at the field coordinate and the member slot at (class, member), the second
  re-grained to the field coordinate through the backing relation. It carries the member name and
  which basis answered, with `intent_resolved_field_claim.tier` the precedent for why a basis is a
  column rather than a hidden pick, and nothing else: the member's type and position stay one join
  away. The arm order stops being the reader's.
* **The overlay renderers recompose to one statement per site**, and the per-node loop stops issuing
  three.
* **Latency measurement does not move to the front, and is not a gate on the recomposition.**
  Proposing it as the recomposition's design input inverts the order the work has. There is no
  reported performance problem, and the defect being corrected is a statement count rather than a
  measured time: one statement per capability is the right shape whether or not the current shape is
  slow enough for an author to notice. Make it work, then make it fast. Numbers become worth taking
  when a surface is reported slow, or when a materialization is actually proposed, and until then
  measuring first would let a benchmark decide a question the architecture already answers.

**The diagnostics surface composes its own text.** The conflict message is a projection, so each
surface writes its own sentence: the build report prefixes the coordinate because a console has no
cursor, and this surface has a range and should not repeat it. `Diagnostics` hands `error.message()`
to the editor verbatim today, so an author sees `Field 'Film.title': ...` squiggled on the line that
*is* `Film.title`, and the surface can be richer where the console cannot, with
related-information at the other claiming directive's position. That composition is this item's; the
`message` column's removal from the intent view is `conflict-message-leaves-the-intent-view.md`
(R696), and the two are independent because this surface reads through `Diagnostics` rather than off
that column.

Two siblings also came out of the pass and are not this item's work:
`name-matching-stratum.md` (R697) and R698 above.

R697 is **not** sequenced ahead of the recomposition, though the pass that filed it proposed that it
should be. The matching-stratum views make the recomposed joins cleaner to read; they do not change
what those joins return, so a statement written against `UPPER(...)` today takes the stratum's
relations mechanically when R697 lands. Sequencing it first would have parked this item behind work
it does not depend on (R697 awaiting a third-party sign-off), for a cleanup whose value is in where
the matching rules live. (R697 has since been respecced from folded base-relation columns to derived
match views, which removes the capture-rewrite coupling this paragraph originally also cited; the
sequencing decision stands either way.)

So the remaining order is the recomposition and then diagnostics, in that order and for one reason:
diagnostics is the largest unmigrated surface left, so writing it before the recomposition would
add its reads in exactly the shape this pass condemned. Recomposing first means diagnostics is
written once, and its being the last capability makes it the honest test of whether one statement per
capability holds for a surface built that way from the start rather than retrofitted.

## Settled while building: the field hover is one statement, and the ungated facts chose its shape

The first read recomposed is the declaration hover's field block, which cost three statements plus one
per claim standing at the coordinate. It is one now, and the shape is not the one the review pass
proposed.

* **Nine subqueries over no driving table.** The review called for the claim reduction left-joined to
  the six per-classifier relations, one row per claim, the classifier column saying which join
  answered. That shape does not survive its own first case. Every claim-independent fact would hang
  off a claim row, so a coordinate no claim reaches would lose them, and the field that most needs
  them is exactly that: a split child returning a table type is claimed by nothing, and the round-trip
  answer is what an author hovers it for. Driving the statement from nothing puts the claims and the
  two ungated facts side by side, each a multiset, none conditional on another.
* **Nothing is joined, because there is nothing to join on.** All nine relations key on the same
  coordinate, which arrives as a bind value rather than from a driving row, so a join would have
  bought only the driver that broke the ungated facts. Independent subqueries also mean no arm's
  fan-out can multiply another's, which the review wanted the multisets for, without having to sort
  the multi-row arms from the single-row ones to get it.
* **Every arm is read as a list, including the six that hold at most one row.** So absence is an
  empty list in all nine rather than a null in some and an empty list in others, and each arm keeps
  the small record its own relation earns instead of contributing nullable columns to a shared one.
  The mostly-null projection this reader was written to avoid does not come back as the price of one
  statement.
* **The classifier switch survives, with no query in it.** The review read the switch itself as the
  defect; it was the queries inside it. What is left is the mapping from a classifier to which arm
  answers it and what an author sees that arm called, over rows already in hand. That is a rendering
  decision, it is the one this layer owns, and the item had already settled that the kind-to-label
  mapping stays in Java.

**The durable half is a counted test, not a faster hover.** `DeclarationHoverStatementCountTest`
attaches an execute listener and asserts one statement for a plain column match, for a coordinate two
directives both claim, for a field no claim reaches, and for a declaration the store knows nothing
about. It has no timing and no fixture scale, so it cannot fail for being slow; it exists because the
defect was invisible from every behavioural assertion. Sixteen hover cases passed throughout, since a
fan-out into separate round trips returns exactly the text one statement does. The next reader adding
a fact to the block will reach for another query, which is the natural move, and this is what refuses
it. The no-claim case doubles as the test's own proof: it was three statements before and asserts one
now, so a passing count is evidence the listener sees real executions rather than nothing at all.

`graphitron-lsp` takes `jakarta.xml.bind-api` at test scope for it, the fix `graphitron` and
`graphitron-sakila-example` already carry with the same reason recorded in their poms: jOOQ declares
it optional, deriving a `Configuration` reaches JAXB-annotated `Settings`, and the annotation methods
javac cannot then resolve are warnings `-Werror` rejects.

The type block was untouched at this point and still read a statement per claim, plus two more for an
unclaimed type's backing. Its number was deliberately left unpinned rather than asserted at the value
a defect had, and the section below is the recomposition that let the class state it.

## Settled while building: the type block is one statement, and the backing rule outlived its query

The type block cost one statement for the claim classifiers, one per claim for that claim's facts, and
for a type no claim named, two more to resolve the backing plus a third to read the conflict behind a
backing it declined to name. Worst case, an unclaimed type two producers disagree about, was five
statements to render three lines. It is one now, on the same shape as the field block: six multisets
over no driving table, the two claim arms beside the three backing arms.

* **Reading the backing beside the claims is what made it one statement, and it is also the correct
  gating.** The block used to decide it had no claims and only then ask for a backing, which is a
  second round trip conditioned on the first one's answer. The two are independent questions about the
  same type, so both are arms of the same statement and the choice of which to *show* is the renderer's
  alone. That is the field block's ungated-facts finding at the type grain, and it arrived by the same
  route: the case that broke the conditional shape was the payload type a `@service` return hands back,
  which no claim names and which the store nonetheless knows a class for.
* **The rule that chooses between the backing populations moved out of the query and stayed one
  copy.** `TypeBackingClass` holds two rules the relation deliberately does not: a grounding beats a
  hop, and a type still contested has no answer. Its bulk reader applied them while assembling rows,
  so a second reader fetching the same populations its own way would have had to restate them.
  `TypeBackingClass.resolve` now takes the two candidate populations and returns the answer or
  declines; the bulk reader calls it per type, and the type block calls it on two of its arms. The rule
  lives with the question rather than with either reading of it.
* **`TypeBackingClass.contested` is gone, and the arity it reported is an arm.** It was the surface
  that told the two absences apart, an unbacked type from a contested one, and it did so by resolving
  the backing again to confirm the decline before reading the conflict relation. The block holds all
  three populations already, so the decline is a local test and the conflict row is just another
  multiset. `ClaimFacts.ofType` also lost its classifier parameter: it answers about a type now rather
  than about one classifier of a type, which is what let the per-claim loop stop being a per-claim
  query.
* **The bound-table arm reads a relation `BoundTables` also reads.** Deliberately, and it takes only
  the table name a hover line renders rather than the three columns that reader assembles. Two readers
  of one relation is what composing a statement costs; the alternative is a round trip for a column
  keyed on the coordinate already in hand.

`DeclarationHoverStatementCountTest` gained four type cases: a claimed type, a type no claim names
that a producer's return backs, a type two producers back differently, and a type the store knows
nothing about. The third is the one worth having, being the five-statement case, and the class now
states one statement per declaration hover at either grain rather than at one of them.

## Settled while building: diagnostics is one statement per document, and the new view was not this consumer's

Diagnostics was sequenced last because it is the largest surface and would otherwise have been written
in the shape the review condemned. It was also the honest test of whether "one statement per capability"
survives contact with a surface whose unit of work is a whole file rather than a coordinate, and it
does, but not by analogy: the grain had to be restated before the shape transferred.

**The first pass got the grain wrong, and the correction is recorded below rather than hidden.** This
section originally claimed "a document's grain is the document". It is not: see the next section, which
moves the read to the unit of work. What stands from this pass is the three-stage shape and the
measurement, and both survive the move unchanged.

**The claim is that the count does not track the file.** A hover asks about one coordinate and answers
in one statement. Diagnostics asks about every value an author wrote, so the same discipline rules out
not a second query but a *per-site* one. The old cost, measured rather than argued: one `@table` name
cost one statement, one `@field(name:)` cost three, and a ten-field type cost thirty-one. A column name
was the expensive one because resolving it walked the site's own scope, then the parent's binding, then
that table's columns, each round trip's subject decided by the one before it; on a class-backed type it
was six. Those numbers are published on every capture, per open file.

**The three stages are what make it one statement, and the walk reading nothing is the load-bearing
one.** The pass is now collect, resolve, judge. The walk settles what the tree alone answers and records
a `Finding` for everything else, putting the value it needs resolved into a `Questions`; one
`DiagnosticFacts.of` answers the document; then each finding is judged in the order the walk found it,
so document order survives the split and an editor sees the sequence it always saw. The stage boundary
is the whole correction: a check that emitted straight into the output *had* to resolve its value on the
spot, and that, rather than any individual reader, is what made the count track the file.

**The questions turned out to be independent, which is why one statement was reachable at all.** A
table name, a foreign key, a class, a method, a `@node` reference and a member name are resolved by
relations sharing no key, so no answer decides what to ask next and the arms can be multisets over no
driving table, exactly as the hover blocks are. The one chain that looked like an exception was the
member arm, where a name resolves against whatever the site's scope turns out to be. That is a join
from the coordinate through the scope to the columns, and a join inside one arm is not a second
statement.

**`intent_field_member_name` is not this surface's view, and the reason is worth recording.** The plan
named it as the one new view the recomposition earns. It is not what diagnostics needed, twice over.
`FieldMemberName`, whose arm order the review objected to, answers which member a field's *own* name
reaches, which is singular and whose only consumer is the inlay overlay; the view belongs with that
work. And `intent_field_column_scope`, which already derives a site's table totally and carries a
`basis` column, is not a drop-in here either: its rules demand an unambiguous binding and a scalar leaf,
where this surface deliberately accepts every candidate of an ambiguous binding and stays quiet rather
than reporting a resolution question as a typo. The two scope questions are near neighbours and not the
same question, and both sides of the difference are deliberate, so folding them would have cost one of
them its correctness. What diagnostics needed was a projection, and it got one with no DDL change.

**Two rules moved out of their readers so the projection could apply them instead of restating them.**
`TypeMemberScope.resolve` now takes the populations a caller holds, the binding first and the backing
class only where no binding answers, with the backing arriving as a supplier so the single-type reader
stays lazy in it, that laziness being the precedence expressed as work not done. `CatalogTables` and
`CatalogKeys` expose the match rule itself as a condition, which is what lets the arms ask "which of
these spellings does the census resolve" as one probe per spelling projecting its own literal. The
alternative was to fetch what matched and re-apply the rule in Java, and for keys, two namespaces and a
qualifier that scopes rather than widens, a second copy is exactly the rule an editor must not get
subtly wrong against the generator.

**Three-valued census answers replaced a guard-clause ordering.** Every arm now reads one `Resolution`
per census, so a name a populated census lacks is wrong and an empty census is a consumer mid-build. The
`@node` arm is the interesting one: a graph declaring no `@node` type is deliberately *not* a deferral,
capture writing every one of them, so the census that arm defers on is the store's own presence. That is
a column of the answer rather than a flag beside it, which is honest, it being literally true when the
statement runs, and it means an absent store is answered rather than branched on. No arm carries a case
for the store's absence any more, and the `argMapping` suppression needs none either: no store yields no
overload for any name, which is already one of its three reasons to stay quiet.

`DiagnosticsStatementCountTest` holds eight cases, and the flat-growth one is the point rather than the
bare "assert 1": ten sites and forty sites cost the same, which is the property a future reader breaks
by resolving a value where they find it. Two cases pin the other end, that a document with nothing to
resolve costs no statement at all and that a session before its first build costs none and says nothing
about any value. The ninety existing behavioural cases passed unchanged on the first run, which is the
evidence that the recomposition is a change of shape and not of verdicts.

## Settled in review: the unit of work is the recalculation, and the grain question had three answers

The section above claimed a document's grain is the document. Challenged in review, and rightly: the
language server's grains are the schema file and the graph, and "the values this file happens to
mention" is neither. Naming the three separates them, and it turns out only one of them should own a
read.

* **A file is what an editor is told about.** `publishDiagnostics` is per-URI. That is the LSP protocol
  and not a design choice, and it is the only sense in which a file is a unit here.
* **A graph is what the facts are keyed on.** Every relation diagnostics reads is partitioned by
  `graph_name`, or by `source_name` for the censuses. Nothing it queries is keyed on a file.
* **A recalculation is the unit of work.** It is what a capture triggers, what the recalculate queue
  drains, and therefore where the read belongs.

Keying the arms on one document's coordinate set sat at none of those. It was an artifact of the fix
being one step up from per-site rather than a grain anybody had chosen, and it cost real things: a drain
of N files issued N near-identical statements about one graph, the answer could not be shared across
files even though the facts were the same, and the SQL text varied with the file, the coordinate
disjunction growing with it, so the graph-keyed half was needlessly hostile to a plan cache.

**Measuring the drain found the count was twice what the earlier section said.** `StoreAccess.answering`
resolves a document to its graph through `SourceGraph.of`, which is itself a query, so each file cost one
membership resolution before reading a single fact. The honest per-drain figure was 2N. `SourceGraph.ofAll`
answers a whole set in one query, keyed by source name and never omitting one, so an unread source
answers `Uncaptured` rather than going missing; `SourceGraph.of` reads the arity rule through it, so one
source and forty are resolved by the same three arms.

**Two statements per drain is the floor, and it is a real floor rather than a shortfall.** Which graph
answers for a document and what that graph says are different questions, and the second is keyed on the
first's answer, so this is the one place in the surface where an answer genuinely decides what to ask
next. Folding them would mean joining the facts through `store_graph_source`, which would put the
session's shared-file tiebreak, a policy decision, inside the query. So: one membership resolution, then
one statement per graph the drain touched. For an ordinary single-project session that is two, down from
2N.

**`Diagnostics.Batch` is the unit made explicit, and the stages just compose.** Every queued file is
walked, the set's questions are unioned per graph, one statement answers each graph, then each document
is judged and published on its own. `Workspace.answeringAll` widens the read transaction from a file to
the drain, which is the stronger guarantee: no two files in one publish can be diagnosed from two sides
of a capture. `Diagnostics.compute` survives unchanged as the batch of one, so the ninety behavioural
cases and the four other call sites never learn about any of this.

**A latent lifetime bug fell out, and it would have bitten any batching attempt.** The first batch
implementation held a `FileSnapshot` per document and blew up in `BuildTriggerPublishesDiagnosticsTest`
with `IllegalStateException: Already closed`. A tree-sitter tree, and every node in it, is a native
resource whose lifetime is the file lock `Workspace.withView` holds; the old code never noticed because
everything happened inside that lock. So findings may not carry nodes. Each now carries the `Range` it
will squiggle, computed in the walk, and a document carries its source bytes for the one judgement that
needs byte offsets. The invariant is worth stating plainly: **nothing native crosses a stage boundary.**
The validator's own diagnostics moved into the walk for the same reason, their ranges being re-anchored
through the document's description nodes, and they are appended last, which is where they were emitted
before.

**Not done, and recorded rather than guessed at: the SDL arms could drop their value filters entirely.**
Bound tables, backings, the redirect, slots and `@node` declarations are graph-partitioned and bounded by
the schema, so those arms could take no `IN` filter at all, making the SQL text constant and the answer a
property of the graph that a later pass could memoize per capture and reuse across drains. The censuses
cannot: the classpath is unbounded relative to the schema, and a real catalog can hold thousands of
tables, so `jvm_class`, `jvm_method` and arguably `sql_table` want the authored value set. Dropping the
filters trades payload for plan-caching and there is no measurement either way, so it stays a filter that
only ever narrows. Make it work, then make it fast.

## Settled while building: the overlays are one statement per region, and the promised view cannot be stated without moving a rule the store keeps visible

The inlay surface was the last capability reading a value where it found one, and its grain made it the
worst offender. An editor reissues an inlay request per visible window, on every scroll, so a count that
tracked the region was paid at the cadence of the cursor rather than of a build. Measured first, on a
counting handle:

| One inlay request over | statements |
|---|---|
| a bare `@field` on a table-bound type | 4 |
| a bare `@field` on a class-backed type | 10 |
| a window holding no declaration at all | 6 |
| a type with ten bare `@field` sites | 23 |
| a schema exercising every arm | 15 |

Every one of those is 1 now, and the empty window is 0. The floor of six was the four bulk claim reads
plus the round-trip rule plus the binding, asked whether or not anything on screen needed them; the
growth was the member-name overlay, whose four questions each chose the next one's subject: the site's
own settled column, then the type's binding, then its backing class, then that class's slots. Four
relations sharing no key, so they were always askable together, and the chain was in the reading.

**The region is the only unit this surface has, which is what makes the assertion a flat one.** The
diagnostics work had to sort three grains out, because a recalculation spans the files a capture touched
and the facts are keyed per graph. An inlay request is one file's window and belongs to nothing wider, so
there is no drain to union across and no per-graph floor. `InlayHintStatementCountTest` therefore asserts
one, and the case that keeps the one honest is the one asserting that ten sites and forty sites cost the
same, since a surface can be one-per-request and still fan out per site.

**The shape is `DiagnosticFacts`' again: collect, resolve, render.** All four arms walk first and read
nothing, recording a `Pending` intent beside the question that answers it; `InlayFacts` answers the whole
set in nine arms over no driving table; rendering runs over the intents in walk order, which is the order
the arms produced them in, so what an editor receives is byte-identical. Every intent carries an
already-resolved `Position` rather than the tree-sitter node it came from, the native-lifetime invariant
the diagnostics batch discovered the hard way applying here by construction rather than by accident.

**Two arms return rows nobody reads, and both times the alternative was worse.** The bindings are asked
for every member site's type as well as every type an overlay might name a table for, and the backings
for every visible declaration as well as every member site's type. Narrowing either would mean an arm
asserting a precedence its reader owns: which of a claim and a backing labels a type, and whether a
binding or a class scopes a name, are decided after every arm has returned, by `TypeBackingClass.resolve`
and `TypeMemberScope.resolve` respectively. Rows in a payload, not a round trip.

**`intent_field_member_name` is not being added, and this contradicts the plan above, so here is why.**
The plan named it the one new view the recomposition earns: the column match at the field coordinate
unioned with the member slot re-grained to that coordinate through the backing relation, carrying the
member name and which basis answered, so "the arm order stops being the reader's". The column arm states
cleanly. The class arm cannot, because to re-grain a slot to a field coordinate the view must first know
which single class stands for the parent type, and that means deciding two things in SQL that the DDL
twice says on purpose are not the store's to decide. `intent_type_backing_seed`'s comment: "The
precedence is the reader's and not this relation's, which states only where a backing came from."
`intent_type_backing_class`'s: "Which of those two rows to believe is `intent_type_backing_seed`'s to
tell a reader, not this relation's to decide." Both are there so a contested backing stays observable
instead of being silently resolved, which is the specific defect the closure was built to expose in the
walk. A view answering a member name would have to pick, and would bury the contest one grain further
down than the relation that reports it.

What the objection behind the promised view actually wanted has been had anyway. The arm order is no
longer a sequence of round trips whose order is load-bearing; it is a two-line rule over rows already in
hand, and it has one home, `TypeMemberScope.resolve`, applied identically by the diagnostics projection
and this one. So the cost of not adding the view is nine arms instead of six and a rule stated in Java
rather than in DDL, and the cost of adding it is the store answering a question by discarding a
disagreement. The trade is worth flagging rather than settling silently: if the view is wanted, the
honest way to get it is a prior view that names the one backing class of a type and owns its
cannot-be-a-view argument in a DDL comment, and that is a modelling decision of its own rather than a
step in this recomposition.


## Settled while building: the reference outlives its resolution, and one snapshot gate is now about cost

The declaration-name resolution asked the classification projection which Java method a method-backed
field binds to, and pulled a class and a method name off five variants to answer it. Four of those five
are one directive each: `@service` on a child field, on a query field and on a mutation field, and
`@externalField`, which the walk calls `Computed`. `intent_field_producer_method` already resolved
exactly that pair against the classpath census, so the port looked like a substitution.

It was not, and the reason is worth stating because it is a general shape. That view is an inner join
to `jvm_method`: it exists to say which census method a reference matches, so a reference matching none
has no row. A surface naming the declaration a field binds to needs the reference in exactly that case.
A class the scan skipped is ordinary rather than exotic here, the census excluding the generated jOOQ
package by design and reaching only the entries something read, and the incumbent already handled it,
falling back to a jump by method name at no arity. Substituting the resolution for the projection would
have silently dropped those coordinates.

So the authored reference became its own relation, `intent_field_producer_reference`, and the resolution
is now that relation joined to the census rather than a subquery that happens to contain it. What each
answers is different in kind: the reference is what the author wrote, and it is a fact whether or not
anything on the classpath agrees; the resolution is which method it found, and its absence is
information. Splitting them also gave the `@externalField` omitted-method fallback one home. That
default was written in SQL inside the old view and in the reflective walk that produced the
projection, and now the reader inherits it from the relation instead of knowing it.

The consumer reads one statement: the reference left-joined to its resolution, with the matched
overload's parameter count beside it. Left-joined and not inner is the whole point, so an unresolved
reference arrives as a target with no arity rather than as no target.

**A coordinate two producer directives claim resolves to no method.** Both rows exist in the reference,
neither wins, and the surface refuses rather than picking. Same rule and same reason as the contested
backing in `TypeBackingClass`: the generator rejects such a coordinate, so it binds neither method, and
a jump to one of them would report a binding that does not exist. The store already reports the
disagreement through `intent_authored_claim_conflict`; here the two references simply leave nothing to
name, and the resolution falls through to what the parent type's scope offers, which is what an
unclaimed field gets. This is a rule, so it is pinned rather than left to be inferred from the
implementation.

**One arm stayed with the projection at this point, and it was `@routine`.** What a routine field binds
to is a method on the jOOQ `Routines` class that codegen produced, and at this point neither the class
nor the method name was derivable from the store: `graphitron_routine.routine_ref` holds the routine's
name in the database, and the step to its generated Java name is jOOQ's own. The catalog census had no
routine family at all. It has one now, and the final slice below is where that arm moves.

**The snapshot gate on the declaration surfaces survives this slice, and it changed meaning.** It used
to stand for what the resolution could answer; it now stands for what the resolution costs. Removing it
was the obvious next line of the same edit and the statement-count enforcer caught it: the classification
block is one statement, and the resolution beside it is two to five more, so ungating the overlay turned
a one-statement hover into a three-to-six-statement one on the path where no build has run. Measured on
the enforcer's own fixture: a claimed type 4, a column-matched field 6, a type two producers back
differently 3. The gate is what keeps that off the no-build path today.

Retuning the enforcer to accept the larger number was available and is the wrong trade. The right one is
the recomposition the rest of this item has been doing: `TypeMemberScope.resolve` already takes its
populations as rows in hand, exactly as the diagnostics and inlay projections feed it, so the
declaration capability can become one statement per declaration the same way. Then the gate comes off
because the cost argument for it is gone, and the enforcer keeps asserting one rather than being taught
a bigger number. That is the next slice, and it is the last of the three residue sites bar the
`@reference` overlay's missing view.

## Settled while building: the declaration capability is one statement, and the projection became an input to it

Both declaration surfaces now cost one statement, and the snapshot gate is off both. Goto-definition
jumps in a session that captured its schema and never ran a build, and the declaration hover renders
its description overlay there too, which is the whole point of the surface reading the store.

The recomposition is the one the rest of this item has been doing, with one wrinkle worth stating. A
declaration surface asks two questions in sequence: which declaration does this coordinate bind to,
and what does the java-source family hold about that declaration. The second cannot be keyed until the
first is answered, so it looks like it must be a second round trip. It does not, because the arms can
answer for every declaration the coordinate *could* bind to, and the resolution then picks by name from
rows in hand. That is the trade the inlay projection already states as rows in a payload rather than a
round trip, and at a single coordinate the candidate set is small: the classes a type could be backed
by, the tables it could be bound to, the columns and members its own name reaches.

**The `@routine` arm looked like it forced a second statement, and the fix at this point was to read it
earlier.** That arm's identity came from the classification projection, so the statement's own arms
could admit neither the class nor the method, and the arity behind it was a read of the classpath
census keyed on what the projection said. Reading the projection *before* building the statement
dissolved both: it is a value the session already holds, so naming the pair up front cost nothing, and
once it was named the arity and the parsed declaration were arms like any other. So even the one
coordinate the store could not name on its own was one statement. What stayed open was the modelling
question rather than a cost, and the final slice below closes it.

**The claim block is handed to the caller as arms rather than fetched.** The hover asks the claim
relations and the binding relations in the same breath; both sets are keyed on the same coordinate and
neither depends on the other, so issuing them separately would be a round trip bought with nothing.
Each surface composes its own statement from shared arm builders rather than the two sharing one
statement, and that is deliberate: goto has no use for the claim arms, and folding them in would make
its cost a function of what the hover renders. Sharing the arms is what keeps the two from drifting on
the resolution; sharing the statement would couple them on everything else.

Counted rather than reasoned about, at the coordinates the enforcers hold: the hover is one where
ungating it had made it three to six, and goto is one where it had been up to six (the binding, then
the census row for the table it picked, then that table's columns, then the parsed declaration of the
constant, each round trip's subject decided by the answer before it). Goto had no enforcer at all
before this slice and now has one.

**The parity test got stronger on the way.** Every case now drives from a coordinate an author could
put a cursor on rather than from a hand-built target, which took a few fixture classes and one more
type per Java declaration under assertion, and the snapshot is unavailable throughout. So the test
demonstrates the session this item is for instead of standing one step outside it. One target stays
hand-built and the test says why: a field declaration carrying a doc comment is reachable from no
coordinate, a column constant resolving to the catalog arm and a record component's header comment not
being retained by the parse.

## Settled while building: the discovery is one view the base relation already promised, and the inlay surface stops taking a snapshot

The `@reference` overlay renders the join the generator makes where the author wrote a `@reference` and
no path. Its fact was the last one this item recorded as unbuilt, and it is now
`intent_field_reference_discovery`: for a field whose parent type and named type are each bound to a
table, every foreign key connecting those two tables, in either direction, with the arity beside it.

**Two comments in the DDL had already said where this belonged, which is most of the design.**
`sql_referential_constraint` says implicit-path inference is a derivation over it and not a captured
fact. `intent_field_reference_step_hop` says an omitted path is foreign-key discovery between a parent
and a child type, a resolution it deliberately does not perform, and that its silence must not be read
as the absence of one. So the shape was not a choice between the chain and something new: the chain
resolves what an author wrote, the discovery answers where nothing was written, and the two are
separate relations because they answer different questions about the same field.

**Neither endpoint is derived here, and that is the reuse worth naming.** The arriving table is
`intent_field_column_scope`'s named-type rule, read by its `basis` tag. That rule already reads the
authored type expression through `graphitron_field_synthesis` so a connection field navigates as its
element type, demands an OBJECT named type and an unambiguous binding, and excludes the coordinates an
authored claim, a `@pivot` or an authored path diverts. Restating any of that would be a second
spelling of a navigation whose own comment says it exists because two consumers were deriving it apart
from each other. The departing table is the parent's own binding, demanded unambiguous for the reason
the arriving one is: a discovery between endpoints that are not certain is not the pair the walk would
have had in hand.

**No pair-keyed relation underneath it, deliberately.** "The foreign keys connecting these two tables"
is a rule about a table pair rather than about a coordinate, and the layering instinct this item has
followed twice (a spelling resolution under the bindings that key it) says to put it in a relation of
its own. It would have exactly one reader, and the chain view's own comment already refuses that shape:
the chain has no separate terminal relation because one would be a reduction over it with a single
reader. The hop view's `{table:}` arm is not a third caller either, pinning one endpoint and
discovering the other rather than pinning both.

**The arity is a column and the refusal is the reader's, which is the rule the whole family follows.**
`film` declares two foreign keys to `language`, so a field of `Film` naming `Language` reaches one table
by two routes; the walk rejects that coordinate with "which foreign key did you mean" and joins on
neither key. The view reports both rows and counts them, and the overlay names a key only at
`candidates = 1`. Same shape as the contested backing: an overlay that picked would tell an author a
join was made that was not.

**Two exclusions, both the walk's rather than the view's own taste.** A self-referential pair is
excluded outright, because both endpoints being one table means a connecting key says nothing about
which way the field navigates, which is why the walk asks for it explicitly there; the exclusion is also
what keeps the direction column meaningful. The comparison is on the two table names and nothing else,
so two like-named tables in different schemas are one table to this rule as they are to the walk. The
walk's other element-less arm needs no exclusion at all: where the departing table is a table-valued
function the walk name-matches instead of discovering, and a routine result declares no foreign keys, so
such a coordinate contributes no rows on its own.

**The inlay surface no longer takes a schema snapshot.** This was its last renderer reading a generator
pass's projection, so the parameter came off `InlayHints.compute` and with it the `Built` argument
threaded through the collector interface. What the assertions used to say the signature now says, which
is why `InlayHintsTest` shrank to two cases: the class existed to hold the snapshot-reading renderer and
the cases proving the store-backed arms did not read it, and neither has a subject any more. The
`@reference` pass got a store-driven sibling of its own, `InferredReferenceHintsTest`, in the shape the
`@table` and `@field` passes already had.

**A sealed variant retired with it.** `Pending.Ready` existed for a label the walk had already settled,
which was only ever this overlay's, read at walk time off the projection. Every intent now names a
question and no arm resolves anything while it walks, so the variant had no producer and is gone.

**Counted rather than reasoned about: still one statement per region, now ten arms rather than nine.**
The count case for this arm asserts the overlay renders before it takes the count, so it cannot pass on
an arm that answered nothing; a renderer reading no relation at all would also cost one statement.

**What the overlay renders changed, and it is narrower than the incumbent's.** The projection carried a
list of steps, so the old renderer rendered a list. A discovery is a single hop, discovery never
searching past one, so the overlay is `path: [{key: "..."}]` with one element. The incumbent's list came
from the classification of a *column* reference, whose path is authored rather than discovered; it could
therefore render a path an author had written, at a coordinate where a second `@reference` application
carried no `path:` argument of its own. That is not a case worth keeping: the overlay's contract is that
it shows what the buffer does not say, and the authored path is in the buffer one directive to the left.

**What remained on this item at this point.** `FieldCompletions`'s source-sigil predicate over
`siteContext`, and `DeclTarget`'s `@routine` identity. Both are named above, neither is an inlay
concern, and each is closed by a slice below.

## Settled while building: a carrier is what a mutation returns, and the sigil's admit was wider than its own message

The `$source` sigil's two surfaces asked the projection one question: is this coordinate the data
field of a carrier payload. `intent_carrier_data_field` answers it now, and the projection retired
with them, which left `DeclTarget`'s `@routine` identity as the last thing on this item reading a
generator pass's output.

**Carrier-ness comes from the producing field, and that is the load-bearing part.** A payload type
looks like nothing in particular: one field whose type is table-bound, beside an errors channel. So
does any nesting type in any schema, which is why a relation keyed on the payload's own shape would
call half a schema a carrier. What makes the type a carrier is that a mutation-root write returns it,
and the directive that returns it decides two policies as well, so the producing directive is a
column: `@service` is SERVICE, `@mutation` is DML, `@routine` is ROUTINE, and a payload two of them
return is a row per family rather than a pick.

**The families differ in exactly three places and each is a rejection of the whole payload.** A
directive on a data field that names a different fetcher contract routes the type out of the mold, and
the one difference in that list is `@splitQuery`, which a producer-backed carrier tolerates because
its data field already re-fetches. The other two are the ID element: a routine write has no primary-key
echo, so ROUTINE admits no ID data field at any wrapper, and a DELETE echo cannot have a nullable slot,
so DML refuses `[ID]`. The Connection refusal that sits beside those in the walk is unreachable there,
`@asConnection` being on the forbidden list the scan consults first, so the view does not carry it.

**The arity is a column and the refusal is the reader's, which is the third relation in a row to take
that shape.** A payload declaring two data channels is two rows counting two, which is the coordinate
the generator rejects for having no single data field; a reader demands `data_fields = 1` and
transcribes the refusal without recounting. Element kind is a column on the same argument: which kinds
admit a given surface is that surface's rule, and the sigil's is TABLE and ID, the two the carrier
classification encodes the upstream value onto.

**The move found the admit was wider than the message it prints.** The user manual and the build's own
rejection both say the sigil is valid on the data field of a payload returned by a `@service`-backed
mutation, and the projection admitted DML carriers too, because its map was built from classification
arms a payload-returning write lands on as readily as a producer does. So the editor offered `$source`
on a DELETE echo and said nothing when an author wrote it there, against a documented rule that does not
admit it. The reader demands SERVICE, which is a change of answer rather than a move of one, and it is
pinned on both surfaces: the same payload shape under `@service` and under `@mutation`, one offering
the sigil and one reporting it.

**One shape the store cannot see yet, and the cause is not here.** A two-level carrier wraps a result
type the producer's class stands for, and `intent_type_backing_class` says in its own comment that it
does not apply the walk's carrier fork: the closure backs the wrapper where the walk reaches past it.
So the element resolves to no kind and the payload names nothing. That is the closure's departure to
close and not a second reading of it here, and it is pinned as a case so it cannot be discovered twice.

**The completion seam lost its snapshot parameter.** The sigil arm was the last completion provider
reading a generator pass's projection, so `CompletionRequest` no longer carries one and `Completions`
no longer passes one. The case asserting the column arm answers without a snapshot went with it, the
signature saying now what the assertion said. Diagnostics keeps its snapshot for the directive shapes
and the validator replay, which are other residues on other clocks.

**The diagnostic's silence changed meaning without changing shape.** It used to stay quiet when the
parent type had no entry in the backing projection, standing for "the classifier has not seen this
type". It now stays quiet when the store holds no `graphql_type` row for the parent, which is the same
silence over a fact: a buffer naming a type no capture has read is what that state actually is, and a
verdict there would be a verdict about a shape nothing has resolved.

## Settled while building: what the language server still receives, it no longer reads

The sigil slice left two projections in the language server's hands with nothing asking them
anything. This retires both, so the only projection state a request can still reach is the directive
surface, the freshness axis the validator replay gates on, and the one field classification
`DeclTarget` reads.

**A projection with no reader is not inert, it is a claim.** The `CompletionData` catalog was still
swapped into the workspace on every build round, still handed to `Hovers.compute` and to
`SdlActions.all`, and neither had read it since their arms moved to the store. A parameter that is
passed and ignored reads as a dependency to anyone extending the code, and the next action or hover
arm would have reached for it because it was there. The same for the snapshot's `typesByName`: its
last reader was the sigil's membership guard, which asked the projection whether it had seen a type
at all, and that guard became a `graphql_type` read one slice ago.

**One of the two removals is a capability, so it is worth naming as one.** `typeDefinitionLocations`
projected each type's declaration position for goto-definition to a file no buffer had open. The
store's declaration sites answer that now, and hold every site a type has rather than the one entry a
map keyed by type name can carry. Its own comment claimed a second reader, the MCP schema view, and
that reader had already left; the projection was surviving on a citation rather than on a call. So
this deletes the map, the projection pass behind it, and its position-reduction helper, rather than
parking them.

**The backing projection survives its shipping channel, and that is the distinction the slice
turns on.** `CatalogBuilder.projectTypesByName` is still called, by the walk's backing-class
transcription, which writes the shadow the store-native derivation differs against. So the switch
that says what the walk bound each type to keeps its one home; what retires is the snapshot field
that carried the map to a consumer, and with it `buildSnapshot`'s `CompletionData` parameter, which
existed only to null-guard that map. A projection function and a projection channel are separate
things, and only the second one was dead.

**Two tests were asserting on the channel and now assert on the fact.** The pipeline test asked the
snapshot which class backs each SDL type; it asks the projection directly. Two dev-loop cases used
"the catalog is the same instance it was" as their proof that a source-cadence refresh ran no
generator pass; with no catalog on the workspace they say it of the build output itself, which is
what they meant. The rest of the churn is arity: the snapshot record lost two components, so every
fixture constructing one lost two arguments.

**What was left on this item at this point.** `DeclTarget`'s `@routine` identity, closed by the final
slice below, and the diagnostics residues on their own clocks (the directive surface, the validator
replay's freshness gate). One thing worth recording for whoever picks up the catalog itself: `CompletionData`'s table,
reference and scalar censuses now have no reader but two `DevMojo` log lines, while its
`externalReferences` census is a live input to capture. Retiring the catalog is therefore a question
about what the dev goal reports, not about what the language server reads.

## Settled while building: the walk has one product again, and the projection outlived the readers its own comment named

The workspace held one more projection: `SourceWalker.Index`, the keyed view of Java declaration
positions and Javadoc, refreshed on the source cadence beside the store write. This retires it,
along with the walk's second product and the resolution policy that product carried.

**A comment naming a reader is not a reader.** The field's javadoc said "the MCP code tools are its
readers", and that was true until the sibling item moved every `graphitron-mcp` reader onto the
store and stopped constructing a `Workspace` at all. What was left was a projection whose
documentation was the only thing still pointing at it. That is a worse state than an unused field,
because the sentence tells the next reader the field is load-bearing; the check that catches it is
asking who calls the accessor, not what the accessor's javadoc claims.

**One walk had two sinks, and the second one is what needed defending.** The dev goal parsed each
changed source once and wrote both the store's `java_` family and the index, precisely so the two
could not answer from different reads of the same file. That was the right guard while both had
readers. With one sink there is nothing to keep in step, so the guard goes with the projection and
the walk's product is simply the rows.

**The projection was a resolution policy wearing a data structure's clothes.** Keyed by
`(class, name, arity)`, it could not hold two same-arity overloads, so it grew an `ambiguousMethods`
side-set for the keys it dropped, a never-dropped name-level map as the floor a dropped key falls
back to, and first-declaration-wins merges for classes and fields. Three inventions, all answering
"which declaration did you mean" in advance of anyone asking. The store's row-per-declaration grain
answers it by counting rows, at the moment a reader asks, so deleting the projection deletes the
policy rather than relocating it.

**The walker moves to the capture package, and the deletion is what made that a rename.** `Index`
carried a `CompletionData.SourceLocation`, so while it stood, the parse-only walk could not leave
the package it was retiring alongside. With the projection gone the walker's only main-source reader
is `JavaSourceFacts`, so it now sits beside `ClasspathScanner` where the retirement doctrine says a
capture-side reader belongs.

**The tests moved from the keys to the declarations.** `SourceWalkerTest` asserted on the index's
maps; it now asserts on the walk's `ParsedFile`s. Two cases change meaning rather than syntax. The
overload case used to pin "the colliding key is dropped, so the caller keeps UNKNOWN" and now pins
"both overloads are declarations", which is the fact the store keeps. The two-roots case used to
pin that graphitron's output package cannot collide with the jOOQ table package in a shared method
map; with no map there is no collision to rule out, so it pins what the walk does promise, that both
roots are walked and each file keeps the root it was reached under. `JavaSourceFactsTest` lost its
closing comparison against "the projection beside it", there being nothing beside it, and the
dev-loop cadence case lost its index half and asserts the store row alone.

**One behaviour the projection was hiding, found by asserting on the declarations.** The
unparseable-file case used to read one key out of a map, so it could only say the good file was
still there. Asserting the whole declaration list says more, and the answer was not what the case
assumed: a file with a syntax error still contributes whatever the parse's error recovery reached
before the malformed body, a class header here rather than nothing at all. Partial is the honest
answer for a broken file and every consumer already reads absence as absence, so the case now pins
that instead of a silence the walk never promised.

## Settled while building: the directive surface is one relation, and the two validators were one rule written twice

Diagnostics' three definition-shaped checks move to the store: whether an applied directive is
declared at all, whether each argument written on it exists, and whether each argument its definition
requires was written. They read `graphql_directive` and `graphql_directive_argument`, and
`DirectiveResolution` retires with its three arms.

**The bundled-versus-user fork had nothing left to decide.** Capture parses graphitron's own
`directives.graphqls` like any other schema file, so one relation describes both populations and the
two validators this replaced were one rule written twice. That is the same collapse the argument-name
completion arm made, arriving here through the same door.

**The asymmetry the fork carried went with it, which recovers behaviour.** Only the bundled validator
descended into object literals, because the projection of user directives held argument names and no
input shapes: `@auth(policy: {scpe: "all"})` was unchecked where `@reference(path: [{tabel: "x"}])`
was not. Both descend `graphql_field` now, so an author's own input type nests exactly as
`ReferenceElement` does.

**The freshness gate leaves with the projection, and the flip is deliberate.** What silenced these
arms was the snapshot being `Unavailable` or `Previous`; what silences them now is a graph holding no
directive definitions, which is a graph nothing has captured, the bundled definitions being captured
with every graph. The case that changes is the stale one: a buffer whose schema will not parse used
to silence the whole arm, so a newly broken schema showed nothing at all, and it now reports against
what the graph last captured. That is this item's stated posture for every other arm, and the two
cases that pinned the old silence are replaced by one pinning each half of the new rule.

**The descent looked like a chain and is asked as a set.** The batch's discipline is that no answer
decides what to ask next, and a nested name's type is precisely what the level above answers. Three
things resolve it. The walk flattens each literal into paths of names, which is also the only form a
finding can carry once the tree it was read from is freed. The question is keyed on the names
written, so the arm holds every input object declaring one of them and the descent picks its row by
the type it arrived at. And each row carries the kind of its own named type, so "may a literal
descend here" is answered by the row that produced the type rather than by a second question about
it. The statement count is unchanged.

**Only a path's last name is reported.** A name that fails to resolve is the last name of its own
path, and the deeper paths running through it stop silently, which is how the flattened form
reproduces what the recursive walk did by returning: report once, and stop descending that branch.

**Two cases retire on a premise that cannot arise.** Both pinned bundled-shadows-user precedence, one
asserting the bundled arm still validates when the snapshot carries a same-named shape. A
redeclaration of a bundled directive loses at registry admission before capture sees it, so there is
no shadow for a precedence rule to prefer against, and what the surviving case pins is the ordinary
unknown-argument verdict.

## Settled while building: the directive projection had no reader left, so it goes in the same session

With diagnostics judging against the captured definitions, nothing read the snapshot's directive
surface. This retires it rather than leaving a projection standing on nobody's behalf, which is the
lesson the two slices before this one wrote down.

**What goes.** `LspSchemaSnapshot.Built.directives()` and its `directive(name)` lookup, the
`DirectiveShape` / `InputValueShape` / sealed `TypeShape` records the surface was built from, and
`CatalogBuilder`'s projection of them, which took its formal-argument and type-expression helpers
with it. The registry-only `buildSnapshot` overload goes too: it existed so a unit test of the
directive arm could run without a classifier, and there is no directive arm.

**What the snapshot is now.** Two classification maps and the freshness seal, whose one remaining
reader is the validator's replay of a build's own errors. Its class javadoc says that rather than
describing a side-channel for user-authored directives, and the leaf permits keep a no-argument
convenience constructor for the fixtures whose subject is the freshness axis rather than any
classification.

**A comparator's witness improves by losing the easy one.** The differential bisect aid diffed
directives, fields and types, and the case pinning it used an added directive definition as the
change it had to localise. Directives are gone from both, so the case adds a field coordinate
instead, which is what the comparator is actually for: the classifications are the shadow a
classifier slice can drift in.

**Two invariants move rather than retire.** The snapshot's unit test pinned case-sensitive lookup
and a defensive copy at construction, both on the directive list. The same two properties are true
of the classification maps and are pinned there now, which is where the next reader will look.
`CatalogBuilderSnapshotTest` lost its subject one slice earlier and is renamed
`TypeBackingProjectionTest` after what it actually asserts.

## Settled while building: a build's own findings are rows, and the report was the last thing carrying them in memory

The language server replayed what a build concluded from the `ValidationReport` object the dev goal
handed it. Capture already writes those conclusions down: the walk's rejections, the lint findings,
the advisory warnings, the claim-conflict detection, and the parser's and the schema assembler's own
refusals. The replay reads them from the store now, as one more arm of the statement the diagnostics
batch already issues, and the report leaves the workspace with the freshness gate that silenced it.

**The view was already the surface.** `diagnostic` unions every arm of the diagnostics stratum and is
what the MCP tools read; this is a second reader of a published view rather than a new query shape.
It filters to the schema channel, the compile channel's rows being anchored in generated `.java`
files rather than in a buffer an author has open. Severity comes from the view's own column instead
of a switch over the rejection hierarchy, which is where the build's finality was being restated: an
arm is an error because the build refuses on it, the deferred arm included, and the view says so once
for both readers.

**Two arms arrive that never reached an editor.** A schema that will not parse and one that will not
assemble each produce verdicts, and neither used to reach the author, because the pass that would
have built a report threw before making one. Capture records those refusals before the throw, so a
broken file now shows the parser's own message where the parser found it. That is exactly the case
the freshness gate was worst at: it silenced the whole replay at the moment the schema had just
broken, which is when the author most needs to be told where.

**The claim-conflict arm keeps its place by being read as rows.** Those violations are minted from a
view rather than written to the residue, and the incumbent saw them only because the report fused the
walk's list with the detection's. Re-deriving them here was never on: that derivation is an N+1 walk
enriching each conflict with its claims, and the enrichment serves the classification overlay rather
than any message. The union already publishes the row a diagnostic needs, so the arm arrives with
everything else.

**The re-anchoring is a fact about the document, not about the finding.** graphql-java anchors a
documented definition at its doc block, so a finding on one has to be moved to the name it documents;
the incumbent did that by walking up from the location's node, which needs the tree. Findings are
judged after the file lock is gone, so the walk collects every description's span with the name it
documents and the judgement asks which one covers a location. Same answer, no node held, and the same
discipline every other thing crossing that stage boundary follows.

**Order matters at the writer now.** The swap that publishes a round enqueues every open file for
recalculation, and a recalculation reads what the round wrote, so the dev goal writes its facts
before it swaps. The two lines the other way round would publish the previous round's findings and
wait for a keystroke to correct them.

**The floor is one statement rather than none.** A document with no directive at all still asks what
the build said about it, so the case that pinned "nothing to resolve costs no statement" pins one.
That is the honest count: a file carrying a lint warning and no graphitron directives is not a file
the editor should be silent about, and the alternative is a second statement issued conditionally,
which is worse at both grains.

**What the tests stopped describing and started driving.** Every case here now writes through the
loaders a dev round runs and reads back through the view, so a fixture cannot state a row shape a
build does not produce. The lint-suppression parity case gains the most: it ran the real generator
and then handed the LSP a report object, and it now runs the real generator, loads its findings the
way the dev goal loads them, and reads them as the editor does, which is the whole chain rather than
its two ends. The severity meta-test's premise changes with it. It pinned that the LSP's switch
covered every rejection permit; there is no such switch, so it pins that every permit survives the
round trip, and its sibling pins that a leaf's declared code reaches the wire through the one decode
site the loader now owns.

## Settled while building: the freshness axis had no reader once the replay left

The projection was sealed over two axes. Availability, whether a build has produced one at all, is
still read: the declaration surface asks it for the one thing no relation carries. Freshness, whether
the projection reflects the latest parse or the last one before a regression, was read by exactly one
consumer, and that consumer moved to the store in the slice above. This retires the axis rather than
leaving it to be maintained on nobody's behalf.

**What goes.** The `Built.Current` / `Built.Previous` permits collapse into one `Built` record,
`Workspace.demoteSnapshot()` retires, and the dev goal's three failure arms stop calling it. What each
of them still does is republish diagnostics, which is now the whole of their effect on the workspace,
and it is more useful than it was: the read that refused wrote its own verdict on the way through, so
the republish is what puts the parse error in front of the author.

**A demotion was never what "stale" meant to a reader.** The demoted projection carried exactly the
maps the current one did; what changed was a consumer's willingness to speak. Every consumer that used
to hold back now reads the store, where the same question is answered by what the graph last captured,
so the axis was already carrying no information by the time this removed it. The javadoc says that in
place of describing a distinction the type no longer draws.

**Two cases retire and one changes its name.** The workspace's demotion cases (that demoting fires the
recalculation listener once, and that demoting a snapshot that cannot be demoted fires nothing) had no
subject left. The snapshot's own unit test loses the case asserting the two permits look up
identically, there being one permit. Hover's case that a column hover ignores the projection kept its
subject and lost its stale-versus-current framing, which the type can no longer express.

## Settled while building: the cadence changes, so the fan-out has nothing left to aim

Diagnostics were recomputed per keystroke and fanned across every open file that referenced a type
the edited file declared. Both halves go here. The recalculation queue fills from two events now: a
file being opened, which has nothing published for it yet, and a build swapping what the store says,
which changes the answer for every open file at once. An edit and a close fill it with nothing.

**The fan-out was aiming at a question the store answers.** Its whole job was "which other open
buffers might this edit have changed the verdict for", and it derived that from a tree-sitter pass
over each buffer on every edit. The verdict is the graph's last capture, and a capture judges every
file it read, so when the answer moves it moves for all of them at once, which is what
`markAllForRecalculation` already does on the swap. The fan-out was a cheaper approximation of a
republish nobody was paying for by the file.

**What goes.** `WorkspaceFile.refreshTypeIndex` and the two sets it maintained, so an edit is a
reparse and nothing else; `Workspace.enqueueTouched` and its set-intersection helper; and the
`TypeNames` class those were the only reader of. Its `BUILTIN_SCALARS` constant outlived the
extraction and moved to `IntraSchemaDefinitions`, the one surface that has to recognise a reference
to a type the language itself defines. `didChange` and `didClose` stop going through the
listener-firing funnel, which is now what its name says: the path for a mutation that changes what
has to be published.

**What an author sees.** Between two captures, a buffer shows what the last capture said about it,
which is what every other open buffer is showing, and the buffer being typed in stops being the one
exception. Against the incumbent this is not the loss it sounds like: the incumbent recomputed on
each keystroke against a projection it had demoted the moment the schema broke, so the file being
edited showed nothing at all exactly when it had most to say. What replaces the keystroke is the
save, and the parser's own refusal now arrives on it.

**The compile channel's in-memory slot goes with it.** `Workspace.compileDiagnostics` and its
setter held the last javac round for a reader that had already moved to the store's `diagnostic`
view. With them go the `Workspace` parameters on the dev goal's `reportCompile` and
`maybeStartIncrementalCompiler`, which had nothing left to do with one. A round has two sinks now,
the console and the relation, and the relation is where "a resolved failure is cleared" lives,
since publishing a round replaces the graph's previous one.

**Two guards state the cadence on the wire.** The build-trigger case already pinned that a round's
findings reach the client without a keystroke; its new sibling pins the other half, that an edit
between rounds publishes nothing and the next round clears the squiggle anyway. In the workspace's
own tests, the two cross-file cases become one case that the strongest edit the fan-out existed for,
renaming a declaration another open file references, enqueues nothing, and the listener test splits
in two: the mutators that fire it, and the mutators that change a buffer without changing an answer.

## Settled while building: the call surface is a relation, so the projection has no reader and the workspace stops holding one

The last coordinate reading a generator pass's output was `DeclTarget`'s `@routine` identity: the
generated call a routine read or write binds to. A separate item was filed to capture the routine
family and close it. The capture landed ahead of that item, inside the one that needed the same facts
for a read surface, so the closing half rides here rather than waiting behind a gate of its own, and
the epic finishes instead of leaving a residue.

**What the census already held.** `sql_routine`, keyed like `sql_table` and carrying the generated
`Routines` class and the value-parameter method an emitted FROM clause calls, and
`sql_routine_parameter`, that method's IN parameters in order. Both are read off the resolved
`Table<?>` inside the codegen scope, through the same reflection the classifier uses, so the pair the
relation holds is the pair the projection was carrying. `intent_spelled_table`'s population already
included `@routine(name:)`, on the ground that jOOQ models a function result as a catalog table and
the spelling rule does not vary by site.

**So the resolution is a join, and it is stated once.** `intent_field_routine_method` takes
`graphitron_routine` through the spelling view onto `sql_routine`: the sibling of
`intent_field_producer_method` and the same shape, a use-keyed resolution over a source-keyed census.
Keyed on the application rather than the field, `@routine` being repeatable. No `table_type` filter,
because a spelling naming a stored table resolves on the spelling view and then matches no callable,
which says "not a callable" without restating what `sql_table.table_type` means. Absence has three
causes and the joins separate them: a name matching no catalog object, a name matching an object that
cannot be called, and a callable whose generated model exposes no call surface, which is excluded
because this relation *is* the call surface.

**It is a subquery and not a value, and that is the part the earlier plan had wrong.** The slice
above settled that the projection is read before the statement rather than inside it, and read that
as evidence the store read would substitute at one call site. The ordering is exactly what a store
read cannot inherit: the pair being a value in hand is what let `candidateClasses` and
`candidateMethodNames` widen themselves with a literal, and what let the arity arm key on one. Fetch
the pair first and the request costs two statements, which the two count enforcers pin at one. So the
identity becomes a subquery over the new relation in the same statement, the arity arm becomes an arm
over it returning the pair and its arity together, and `DeclarationFacts.ProjectedMethod` retires as
an input rather than being fed from somewhere else.

**The arity gets better rather than merely relocating.** It was a count over `jvm_method_parameter`
for the projected pair, and that arm's own javadoc recorded what it usually returned: 0, the
consumer's generated sources not ordinarily being a scanned classpath entry. The routine's parameter
list answers without the generated class having been scanned at all, so the overload pick is correct
in the ordinary session rather than falling back to the name-level match. The fixture makes that
visible: two `filmsForActor` overloads on disk, and the jump lands on the two-parameter one.

**With no reader, the projection leaves the workspace entirely.** `DeclTarget.projectedMethod` goes,
`DeclTarget.of` loses its projection parameter, and both consumers lose the `LspSchemaSnapshot`
argument they passed only to produce one, which takes it off `Hovers` and then off the document
service. `Workspace.snapshot` and its accessor follow, and `setBuildOutput` with them: with nothing
to swap, what a completed build does to the workspace is enqueue every open file, which
`markAllForRecalculation` already is and already says. The dev goal calls that instead. The
projection itself stays where it is produced, the classifier deriving its type projection from its
field one and the generator's tests asserting on both; what ends is any language-server read of it.

**Two dev-loop cases were proving "no generator pass ran" by object identity, and now prove it by the
queue.** Both held the workspace's snapshot reference across a source-cadence refresh and asserted it
was the same instance. The queue is the better witness and the one that survives: a round enqueues
every open file, so an open file whose queue entry has been drained and stays drained is the pin, and
it fails if a pass runs where none should.

## Settled at the gate: the outcome numbers, and two corpus cases whose wording overshot what shipped

Three things Acceptance asked for at the end, taken on the tree that goes to review.

**The structural test, one type at a time.** The pom names `graphitron-model` first and `graphitron`
second, and the second is down to five types imported across the language server's main sources,
accounted here one by one. `ValidationReport` for `canonicalUri`, the one spelling on which a
captured `source_name` and an editor's URI meet. `RewriteSchemaLoader.directivesSdl` for the bundled
directive definitions the vocabulary loads. `DeprecationRecognizer` for whether a coordinate is
deprecated. `ScalarTypeResolver.parseDirectiveValue` for the shape of a `@scalar` value.
`InferredDirectiveArgs` for which directive argument inference fills in. None of the five is a
projection: each is a rule or a constant the build tier owns and the language server applies, which
is reuse of a rule rather than a second model.

**The six named projection types, and one miscount.** `DirectiveShape`, `InputValueShape`,
`FieldClassification` and `TypeClassification` are deleted outright, the last two once the census
below answered the question this item had deferred. `TypeBackingShape` stays, and not as a residue:
its producer is `CatalogBuilder.projectTypesByName` and its reader is the walk shadow, which is a
capture-time transcription rather than anything shipped to a consumer. `InferredDirectiveArgs` is
the sixth and it is still imported by the language server, because listing it among the projections
was a miscount at drafting: it is a three-entry constant table naming which argument each inference
rule fills in, rebuilt by nothing, read by both tiers, and `InlayHintRendererCoverageTest` fails the
build when the language server carries no collector for an entry. A query over the claim stratum
cannot replace it, because what it states is which arguments inference fills in, not what any of
them resolves to.

**The line counts, said plainly.** `graphitron-lsp` main is 14,196 lines across 80 files against the
9,119 recorded when the item was filed, with 15,824 more in its tests. `rewrite/catalog` is 1,590
against 4,008, and the seam inside it, the package less `ClasspathScanner` and with `SourceWalker`
moved out to `rewrite/capture`, is 1,051 against 3,232. The store's DDL is 5,246 lines, 3,171 of them
base tables and 1,906 the 41 `intent_` views the derived stratum is made of. Attribution is coarser
than those figures look: the module took other items' work over the same period, and the clone they
were measured in does not reach back to the baseline commit, so these are the totals now against the
totals then rather than this item's own diff.

The two directions are opposite and both are worth saying. The language server grew, and the item
said in advance that saying so was the point: a query answering a capability from relations is more
code than a switch reading a value someone else pre-projected, and the `intent_` stratum is new code
that did not exist before. The largest package in the module is now `facts` at 3,052 lines, and that
package is the seam, queries and the row types they return with nothing behind them. The seam it
replaced went the other way, and further than the baseline anticipated: two thirds of
`rewrite/catalog` is gone, because once the language server stopped reading the classification
projection the census found nobody else reading it either. That is the settled section below.

**Two corpus cases whose wording overshot what shipped.** All five Acceptance cases are pinned, but
two are pinned as properties rather than as written, and the difference is worth stating here rather
than leaving the gate to find it.

The first, "a dirty buffer beside well-formed siblings: `extend type |` completes against the other
files", names a completion trigger the language server does not have and never had. There is no
SDL type-name completion arm anywhere in the inventory. What the example illustrates is the division
of labour: a buffer the parser refuses is the question, not a reason to decline it. That property is
pinned now on a trigger the inventory does carry.
`CompletionStoreWiringTest.aBufferThatWillNotParseCompletesAgainstWhatTheOtherFileDeclares` opens a
captured document, replaces its content with SDL carrying a syntax error, and completes
`@nodeId(typeName:)` against a `@node` type that only the graph's *other* schema file declares. It
asserts the buffer's tree carries an error, so it cannot quietly decay into a well-formed-buffer
case. One boundary the example blurred is worth naming beside it: the store answers for a document
some graph has captured, so a file nobody has saved yet resolves to no graph and gets no answer,
which `StoreAccess` states and `StoreAccessTest.aDocumentNoGraphHasReadAnswersAbsent` pins.

The second, "a type assembled from several files, resolving to all its declaration sites", overshoots
the other way. `IntraSchemaDefinitions` returns one location rather than all of them, and one is the
right answer for a jump. What the flattened `typeDefinitionLocations` map could not do was *choose*:
one entry per type name meant an extension overwrote a definition by whichever the projection pass
reached last. The store carries every site and the resolution is a rule over them, an open buffer's
live span beating a captured one and a base declaration beating its extension.
`IntraSchemaDefinitionTest` pins both preferences and the across-files jump.

Neither divergence changes a shipped behaviour. Both are the Acceptance text catching up with what
the capability inventory actually contains.

**One thing left undone on purpose, for the gate to decide.** The item is an internal re-platforming
and carries no `User documentation (first-client check)` section, which the workflow exempts an
internal refactor from. Two shipped changes are nonetheless visible to a schema author, and neither
is written down in the manual: diagnostics now appear when capture swaps rather than as the author
types, and a session that has never captured completes nothing at all, argument names included. No
manual page claims otherwise today, so nothing there is wrong; the question is whether either
deserves a sentence on a reference page, and it is the reviewer's call rather than a silent scope
widening at the end of the item.

**The principle docs the retirement owed a repointing.** Three named exemplars in
`docs/architecture/explanation/`, repointed rather than deleted. The one-slot provenance exemplar in
`fact-model.adoc` was `CompletionData.NodeMetadata` and is `graphitron_node` now, whose `type_id`
holds the authored value, stays null where nothing was authored, and whose own comment names the
type-name fallback as a derivation. The freshness paragraph beside it described a consumer carrying
an availability axis and a current-versus-previous one; it explains why no consumer carries either
now, two-stage capture having turned both refusals into rows, and what is left being a lag of stated
size rather than a state to switch on. And the projection-seam enforcer, named in both
`fact-model.adoc` and `development-principles.adoc`, keeps `CatalogBuilder.projectFieldClassification`
but stops calling it transitional: its readers are the generator's own, and a consumer reading
relations gets the equivalent guarantee from an exhaustive-partition meta-test over its own sealed
vocabulary, which for the language server is `TriggerDispatchMatrixTest`.

`DocSizeBudgetTest` decided how that last repointing was worded, and it was right to. The principles
document was two words under its own 3,500-word cap, so the first draft of the change, a paragraph
explaining the split, failed the build. The gate's instruction is to displace rather than accrete
and to move a narrative to its audience's reference page, so the principles document names both
gates in one parenthesis and `fact-model.adoc` carries the reasoning. Worth knowing at the gate: that
document has no headroom left, and the next addition to it displaces something.

## Settled at the gate: the deferred census answered "nobody", so the projection deletes here

The item deferred one question rather than answering it: "whether the types themselves also delete
depends on their generator-side readers, which is a separate census". That sentence was written when
the answer was genuinely unknown and the assumption behind it was that generator-side readers
existed. Running the census at the gate says they do not.

`CatalogBuilder.buildSnapshot` ran on every generator pass and built both classification maps. The
result reached exactly one production statement in the reactor, an `instanceof` in the dev goal
asking whether a round had classified at all. Nothing in production read the payload: the only
readers of the maps and of the two lookups beside them were the record's own accessors and four test
files. The generator does not classify through the projection, it classifies into
`GraphitronSchema` and reads that; the projection was always the language server's view of the
classifier, and with the language server gone from it, it was a model with no consumer being rebuilt
per pass.

So it deletes here rather than in a follow-up. Leaving it would have contradicted this item's own
headline: what got smaller is the number of models, and a dead model rebuilt on every build is
exactly the second model the item exists to remove.

**What went, and what stayed.** `FieldClassification`, `TypeClassification` and `LspSchemaSnapshot`
delete outright, with the projection half of `CatalogBuilder`, which takes the package from 3,032
lines to 1,590. `BuildArtifacts` goes with them: with the snapshot gone it carried one component, so
`BuildOutput` names the catalog directly. The dev goal's availability gate becomes the boolean it
always was, `InitialOutput.classified`, true when the round got as far as classifying and false in
the catch that already knew. `TypeBackingShape` and `projectTypesByName` stay, their reader being
the walk shadow rather than a consumer; `CompletionData` stays, its role now capture's input type
for the classpath census rather than a projection anyone reads.

**The coverage gate that went with it, and the one that did not.** `@ProjectionFor` was a
drift-prevention contract: every sealed leaf of `GraphitronField` or `GraphitronType` had to have a
payload-asserting projection test or a documented exemption, enforced by `ProjectionCoverageTest`
over the annotation and by the `LSP_PROJECTION` row in `ExemptionRegistry`. All three retire,
because what they gated was the projection's payload and there is no payload now. Leaf coverage
itself is not what they were pinning and is untouched:
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` still partitions every leaf
by dispatch status, the corpus still owns the verdicts, and `VariantCoverageTest` still holds the
corpus obligations. Nine exemption rows retire with the obligation, each of them an admission that
some leaf had no projection assertion, which is no longer a debt anyone owes.

**The tests that went were tests of the deleted code.** Thirty-six members left
`GraphitronSchemaBuilderTest`, all but a few named `...Projection...` and every one of them
asserting a projection payload rather than a classifier verdict; the file keeps 109 `@Test` methods
and every enum truth table it had. `SchemaReachabilityTest` lost one case, the one that exercised
the snapshot comparator, and keeps all four reachability invariants. Three tests that carried a
`@ProjectionFor` annotation but asserted the classifier's own model kept their bodies and lost the
annotation. Deleted outright: `FieldClassificationProjectionTest`, `TypeClassificationProjectionTest`,
`LspSchemaSnapshotTest`, `ProjectionCoverageTest`, `ConflictedProjectionPipelineTest` and
`ProjectionSnapshotComparator`.

## Settled at the gate: the runtime edge to the generator, and the vocabulary that was holding it open

The census above found `graphitron-lsp`'s main sources reaching into `graphitron` for five types and
called finding them another home a smaller question left open. It was smaller, but it was not
separable: the item's headline is that the language server is a fact-store client, and a client that
cannot be built without the generator on its compile classpath is not one. So the edge is cut here.

Four of the five moved to `graphitron-model`, the module both tiers already read, each of them a
spelling neither side owns: the source-name and document-URI trip in both directions, the split of a
written constant reference on its last period, the sigil literals an author may write in place of a
member name with the message that says where one is admitted, and the inferred-directive-argument
table. Every old call site delegates, so nothing is duplicated and nothing is a second opinion.

The fifth was the vocabulary, and it was the whole difficulty. `LspVocabulary` parsed the bundled
`directives.graphqls` into a graphql-java `TypeDefinitionRegistry` and held it as a field, which is
what made the language server need `RewriteSchemaLoader` to find the file and `DeprecationRecognizer`
to read markers off the parse. That registry is a second reading of a document the store already
holds: capture parses the bundled file like any other schema file, which is exactly why
`SdlDescriptions` can answer what a coordinate means from `graphql_directive.description`.

`DirectiveSurface` replaces it. Which directives are defined, what formal arguments each declares,
and the input-object tree those arguments open onto, read out of `graphql_directive`,
`graphql_directive_argument`, `graphql_type` and `graphql_field`. Wrapping is discarded on the way
in, because `named_type` is what every consumer wanted and the store decoded it at capture, so the
`unwrapToInputTypeName` walk over graphql-java `Type` nodes has nothing left to do.

**Loaded whole, once, rather than queried per question.** This is the one shape constraint that was
not obvious, and it comes from the diagnostics walk: that walk reads nothing by construction, which
is what holds a whole recalculation to one statement per graph however many documents it spans and
however many values each contains. Resolving a cursor to a coordinate is a question about the
vocabulary rather than about the document, so the surface is held and the walk stays free.
`ArgNameCompletions` keeps querying the same relations directly, a completion request being one
cursor paying for one answer.

**The vocabulary became state.** It used to be shape, read once at startup and never invalidated,
which was true of a file shipped in the jar. A graph's captured directives are not that: an author
who defines a directive of their own has changed the vocabulary, and the capture that read their
definition is the event saying so. `Workspace` reads it when the store arrives and again on
`markAllForRecalculation`, which is once per build rather than once per queued file. A session with
no store gets `LspVocabulary.empty()` and resolves no cursor to any coordinate, which is the same
silence every other store-backed surface answers with before the first build.

That also retires a behaviour worth naming. The incumbent's bundled-versus-user split meant a
directive an author defined had arguments and no input-object shape, so nothing nested inside one
could be descended into. Both are rows now, so the descent is the same for both. This is less a
feature added than a distinction the census cannot express.

**The startup drift check moves to a test.** The constructor threw `LspStartupException` when an
overlay coordinate failed to resolve, which was a sound trade while the vocabulary parsed a file
shipped in the jar: the only way to fail was drift. Against a captured graph it has a second cause,
the graph not having been captured yet, and taking the whole editor down for that is wrong.
`DriftDetectionTest` asserts the same invariant where it belongs, over a capture of the shipped file,
and an overlay entry the running session's graph does not back is inert rather than fatal.

**Deprecation left with its consumer.** `deprecatedCoordinates` and `deprecationOf` were vocabulary
methods that no request path ever called: nothing an editor shows is keyed on them, and their one
reader is the test asserting that every registered quick-fix action targets a real marker. Both are
questions about the file graphitron ships, so both moved to test support beside that assertion.
Reading the file with graphql-java there is honest about something a store-shaped reader would have
hidden: a `@deprecated` application on a directive definition's formal argument has no relation to
land in, so two of the three shipped markers are rows and the third is not.

**The edge, finally.** `graphitron` is `<scope>test</scope>` in `graphitron-lsp/pom.xml`, where the
fixtures need it to stand a store up by running real capture rather than by inserting rows.
graphql-java leaves the language server's compile classpath with it. What remains at compile scope is
`graphitron-model`, lsp4j, tree-sitter and its natives, and slf4j.


## Settled at the gate: the self review, and what a stale `{@code}` reference is

Run before the handoff, on the reasoning that a reviewer's fresh context is worth spending on
judgement rather than on rot the implementer could have found with a grep. Four sweeps, five real
findings, all fixed here.

**The retirement sweep, run against the implementer's own list.** Every name in "Retired vocabulary"
greped across code, `.adoc` files and roadmap bodies. Three names still stood in live prose. Two
architecture documents named `CatalogBuilder.projectFieldClassification` as the live enforcer of "one
model, many views", and it had been deleted by the census that ran later in the same item, which is
the hazard of a document naming a symbol and an item settling a question in two passes. Both now say
what is actually true and is a stronger statement of the principle: no projection seam survives, so
each consumer's coverage gate is over its own sealed vocabulary. `ExemptionRegistryTest`'s javadoc
still explained the `LSP_PROJECTION` row and the unit-tier test that asserted it, both deleted.

That sweep also found the finding this item cannot fix, which is item three of "Open at the gate":
fifteen other roadmap items name a type deleted here, three of them premised on one.

**A `{@code}` sweep, which is the one the build cannot do for itself.** The javadoc reference gate
resolves `{@link}` and fails the build on a dangling one, which is why no `{@link}` had rotted. It
cannot see `{@code}`, and that is exactly where the rot was: every capitalised `{@code Identifier}`
in the language server's main sources was checked against the type names that exist. Two named
deleted classes, `InlayHints` pointing at the sealed `AbsentArm` and `InlayFacts` at
`FieldMemberName`, both retired by this item and both invisible to the gate. Worth naming as a
standing hazard rather than as two typos: a reference downgraded from `{@link}` to `{@code}` to
silence the gate is a reference that will rot, which is why the convention says to downgrade only
when the target genuinely is not a resolvable symbol.

**Dead code the item's own last commit left.** `ExemptionRegistry.corpusObligations` and
`obligations` had been reduced to two hand-written literals of the same five constants, with a test
asserting they agreed; the distinction was already declared retired and the code had not caught up.
One list now, and the test that compared them goes, its invariant being structural rather than
asserted. In `DirectiveSurface`, the `orderBy(ORDINAL)` clauses and the `LinkedHashMap` /
`LinkedHashSet` around them were inert: the canonical constructor's `Map.copyOf` and `Set.copyOf`
discard insertion order, so the surface advertised a guarantee it did not keep. The ordering machinery
goes and the javadoc states the contract instead, which is that this is a lookup table and a caller
who wants declaration order asks the store.

**One rule written twice, and the wiring hazard finding it exposed.** `ArgNameCompletions` took an
`LspVocabulary` parameter it never read, and separately ran its own queries for the two lookups
`DirectiveSurface` holds, kind-guard and explanatory javadoc included. Its nesting descent resolves
through the surface now, which is what the unused parameter was for; it keeps its own ordered-name
queries, because a listing is a different question from a lookup and the surface has no ordering to
give.

Making that change turned one test red, and the failure is worth recording because it is a property
of the fixtures rather than of the code. `ArgNameCompletionsTest` paired `BundledVocabulary`, whose
surface is a capture of graphitron's shipped definitions alone, with a handle on its own fixture graph
that declares two directives of an author's own. While the completion queried the store directly, the
mismatch was invisible; reading the held surface made it a wrong answer. Production cannot reach this
state, both halves coming from the one workspace, and the test now loads its vocabulary from the graph
it is asking about. `BundledVocabulary`'s javadoc states the rule it was missing: it is for a fixture
that declares no directives of its own, and a fixture that does reads its vocabulary off its own
store.
