---
id: R638
title: "The LSP is a fact-store client"
status: Spec
bucket: architecture
priority: 2
theme: lsp
depends-on: []
created: 2026-08-12
last-updated: 2026-08-12
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

## The experiment

The claim under test is that a fact-based architecture makes the language server *substantially*
simpler. Scope is all of `graphitron-lsp`; every feature moves.

The baseline is recorded now so the measure cannot be chosen afterwards to flatter the result:
`graphitron-lsp` is **9,119** main lines, the `rewrite/catalog` seam is **4,008**. Report both at
the end, plus branch points per feature entry point counted the same way on each side, and the SQL
added. Net out what stays in `rewrite/catalog` (`SourceWalker`, `ClasspathScanner`) so lines that
exist for MCP and codegen are not credited to the LSP. A result showing no simplification is a
finding and gets reported as one.

The work lands additive-then-cutover, the workflow's shape for structural pivots on widely-pinned
types: substrate first, then features arm by arm, the cutover that deletes the projections last,
so the acceptance holds at every intermediate commit. The additive phase is the experiment's
vessel, not scaffolding to hurry past: with both implementations live side by side, the hypothesis
gets a paired test that neither endpoint can give. Each migrated capability has an incumbent arm
and a fact-based arm answering the same requests, so lines, branch points and latency are compared
per feature, like against like. Deleting a hand-written projection layer shrinks the module totals
whether or not the design is better, so those totals are reported after the cutover as the
outcome, but the paired comparison during coexistence is the test, and the cutover is gated on it:
if the comparison does not favour the fact-based arms, or a migrated request is slower than the
incumbent's linear scan on Sakila, the cutover does not happen, the incumbents stay, and the
finding is the report.

**The reuse is of facts, not of code.** The seam between the language server and the store is a
query and its rows, nothing else. The LSP states its data need, fetches exactly that, and reads the
result as jOOQ's `Result` and `RecordN` tuples off the generated model; a hand-written type appears
only where it carries something the rows do not, and it is the LSP's own. No `graphitron`
projection type crosses the seam, and none gets rebuilt store-side under a new name: an arm list
that exists because a Java projection had those arms is the shape under test, not a requirement on
the query. The structural test is `graphitron-lsp`'s pom. It names `graphitron` today and imports
twenty-one types from it, six of them `rewrite/catalog` projections beyond `CompletionData` and
`LspSchemaSnapshot`; at the cutover it should name `graphitron-model` for the generated store
tables, with whatever remains of the `graphitron` dependency accounted for one type at a time. A
surviving projection import is a surviving second model, whatever the line count says.

**This is not a port.** The incumbent is what is being judged, so there is no shadow-parity gate and
no byte-equality on rendered output; pinning the new implementation to the old behaviour would
import the shape under test. What replaces the gate is an enforcer, not care: a meta-test in the
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` mould asserting the
(trigger × surface) matrix is an exhaustive partition over answered / declared-no-answer /
unimplemented, each surface's dispatch a compile-checked exhaustive switch. The axis is trigger,
not `Behavior` arm: roughly half the inventory below keys on something else (all four inlay-hint
rows, both code-action branches, four of the five diagnostic sources, the four non-coordinate hover
rows, the two SDL-keyed definition providers), and that is the half this item changes most. A
matrix over `Behavior` alone would leave it ungated, which is the position declining the
shadow-parity gate was meant to avoid. The `†` gaps below stop being silently empty arms and become
declared facts the test pins, and the inventory becomes a rendered view of the matrix rather than
prose that rots.

## The division of labour

**Tree-sitter extracts intent**: buffer position to schema coordinate, over the live and possibly
unparseable buffer, and the reverse when a store position must land in a buffer that has drifted
since capture. Positions in, positions out; it produces no fact, judges nothing, and writes nothing
to the store. That is its whole job, and it is the only part that must tolerate broken syntax.

**The store answers everything else**, for the whole workspace: completion lists, hover bodies,
definition targets, hint values, diagnostic judgements. The incumbent already leans this way; a
source survey found no tree-sitter syntax diagnostics and no workspace scan to retire (trees exist
only for open buffers, and syntax validity ships via the `ValidationReport` replay). But it exceeds
the line in three places this item pulls back: `IntraSchemaDefinitions` treats every open buffer's
tree as authoritative over the projection, `WorkspaceFile` re-derives a declared/referenced type
index from the tree on every keystroke to aim the diagnostic fan-out, and the recalculation queue
re-runs full-tree validation per keystroke across every dependent open file.

The gate between the two is the stamp, not the open-buffer set. A buffer whose content matches
`store_source.stamp` is answered wholly from the store, open or not; only a buffer the store has
not caught up with (unsaved, or saved with capture still pending or failed) holds live state the
store lacks. The first iteration keeps even that shadow minimal, simple and correct over clever:
the stale buffer's live tree supplies the coordinate under the cursor and re-anchors positions,
never facts. A type declared only in an uncaptured buffer joins the answer set at its next capture;
until then the currency seam answers `Indeterminate` rather than guessing. Widening the shadow so
live declarations feed answers before capture is a later iteration, taken only if the paired
measurement shows the wait hurts.

A graph is many schema files, so validity is per file, not per workspace. An author typing
`extend type |` in a new file has one invalid buffer and a workspace of well-formed captured ones;
the completion wanted is what those other files declare. The invalid buffer is not an obstacle to
answering, it is the question: tree-sitter names the coordinate, the store supplies the list.

## What the store must provide

**Per-file SDL currency.** The one real substrate gap, and in scope, because the whole LSP cannot
rest on a workspace-wide validity bit. Two gates, not one: `FactCapture.capture` takes a whole
`TypeDefinitionRegistry`, and its production call site sits inside
`GraphQLRewriteGenerator.buildOutput` downstream of `GraphitronSchemaBuilder.buildBundle`, so a
classification throw means no capture just as a parse failure does. Both move, or
diagnostics-on-the-capture-cadence inherits the incumbent's blackout for every failure that is not
a parse error. Capture itself needs no classified model, the
`FactCapture.capture(DSLContext, GraphIdentity, TypeDefinitionRegistry)` overload being the
witness; only the detection wrapper's `ClaimDomain` does, so the call site splits ahead of it.
Per-file is the currency and invalidation unit, not the capture unit: several facts exist only over
the merged registry (`merge_ordinal`, the reachability and input-occurrence rows), so parsing is per
file, the parseable files merge into one registry, and capture stays whole-graph in its one
transaction. A file that fails to parse keeps its previous partition, and the failure lands as a
located violation row that reaches the diagnostic view like every other violation. The relations
already carry the granularity: `graphql_type_declaration` indexes "which types does this file touch"
by its own comment, and `store_source` stamps each file.

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
the writer's process; once the LSP's whole answer surface is the store, the degraded mode must be
named, and it is: capture demoted, or no store directory configured, answers `Indeterminate`, the
same arm a stale source gets below.

**Sealed resolution outcomes.** The store's keys are honest where the projections' lists were not:
an unqualified table name may match several rows, and `jvm_method` keys on `descriptor` because
erased display names collided on overloads. Resolved / ambiguous / not-found needs no type: it is
how many rows the query returned, and a `Result` says that already. The one outcome the rows cannot
carry is the arm that replaces `LspSchemaSnapshot`'s freshness seal, `Indeterminate(sourceName)`,
meaning the answer would come from a source whose stamp no longer matches the buffer. So the type
is two-armed, the rows and that arm, and the compile-time force lands exactly where the information
is not in the data. One seam owns the join of `store_source.stamp` against the open-buffer set and
decides currency once, as a variant rather than a field, so no surface re-derives "is that file
dirty?" for itself: an SDL miss is a real miss or it is `Indeterminate`, and every consumer's switch
breaks until it says which. A multi-row result at an author-written coordinate surfaces as a
diagnostic, never a silent first match.

**Coordinate-keyed lookups.** The parse hands over a coordinate; the store answers it, and what
comes back is rows. `DeclTarget`'s six variants are not a shape to preserve: they are the projection
era's dispatch vocabulary, and re-pointing them at the store would carry that shape across the seam
under a new payload. What a bound field binds to lives on `graphitron_service`,
`graphitron_external_field` and `graphitron_routine`, which carry the class and the method and, by
being three relations, say which directive bound it. Not `graphitron_field_binding`: that relation
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
  today.

Confirm `jvm_class`'s filters (public, non-synthetic, top-level, outside the generated package)
against what the LSP needs rather than assuming they agree.

## What retires

`CompletionData`, `CatalogFacts`, `LspSchemaSnapshot` and its freshness seal, `CatalogBuilder`'s
projection pass, and `DevMojo`'s keep-previous-and-demote workaround at all three of its
`demoteSnapshot()` sites, not only `rebuildCatalog`'s — catalog candidates are not retained across
a bad parse, they were never invalidated by it.

"Most of `rewrite/catalog`" is too vague to sweep, so the projection types the LSP imports are named
here: `FieldClassification`, `TypeClassification`, `TypeBackingShape`, `DirectiveShape`,
`InputValueShape` and `InferredDirectiveArgs`. These are the ones the inventory below hides behind
the word "classification", and they are the ones a port would keep. Each leaves the LSP as a query
over the classification stratum, not as a store-side rebuild of its arms. Whether the types
themselves also delete depends on their generator-side readers, which is a separate census; what
this item owns is that no LSP surface dispatches on them.

With them go the LSP's own tree-derived facts: `WorkspaceFile.refreshTypeIndex` and the
declared/referenced type sets it re-derives per keystroke, whose one consumer is the cross-file
diagnostic fan-out. "Which files touch this type" is a read over `graphql_type_declaration`, and
the only file that relation cannot speak for is the one stale buffer.

`CatalogFacts` has non-LSP readers that must move with it: `GraphitronMcpServer` (the
`catalog.tables` and `catalog.describe` tools), `EdgeProducer`, `EdgesTool`, `ReverseEdgeIndex`,
`NodeRef`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`, plus
`GraphQLRewriteGenerator` in `graphitron`, whose output record carries the projection. Not LSP
work, not optional; the projection cannot delete while they read it. `TenantScopes` and `McpWire`
cite it only in javadoc, so they repoint rather than migrate; the `{@link}` gate keeps them from
being forgotten.

`SourceWalker` stays, and the boundary is shipped doctrine rather than this item's argument: the
fact model's cadence rule (location is a fact about an entity, joined rather than stored;
`docs/architecture/explanation/fact-model.adoc`) and the `jvm_class` DDL comment both design source
positions and Javadoc out of the store so a `.java` edit is seen without a generator rebuild.
Capturing it would turn absent provenance into stale provenance. The SDL positions the store does
carry are the same rule's sanctioned side: SDL is what capture reads, so those positions are on the
capture cadence already.

The retirement also takes named exemplars out of the principle docs, and the sweep must repoint
them, not just delete: `CatalogBuilder.projectFieldClassification` is the transitional exemplar
under "One model, many views" in `docs/architecture/explanation/development-principles.adoc` and
the named enforcer in `fact-model.adoc`; `CompletionData.NodeMetadata` is the one-slot provenance
exemplar and `LspSchemaSnapshot`'s two axes carry the freshness paragraph in the same file. The
replacements are nominated here: the store-side projection seam for the first, the `Indeterminate`
resolution arm for freshness. `FactCaptureAgreementTest`'s agreement arms change meaning for every
relation the LSP starts reading and are revisited at the cutover.

## Acceptance

Features are specified against fixtures, not the incumbent: given this buffer, this cursor and this
store, this answer. The store is stood up by real capture over SDL fixtures wherever capture can
produce the state, so a fixture cannot encode rows capture never writes; direct inserts are
reserved for states one capture call cannot reach, with
`ColumnMatchClaimTest.siblingGraphsResolveThroughTheirOwnMembership` the precedent for exactly that
(two graphs in one store). The crawlers are tested where they are.

Four cases the corpus must carry, each being something the current design cannot express:

* A dirty buffer beside well-formed siblings: `extend type |` completes against the other files.
* A type assembled from several files, resolving to all its declaration sites.
* Two graphs in one store, neither seeing the other's tables.
* One file in two graphs: the request boundary surfaces the multi-graph membership arm, not the
  first row.

Latency measured per request on the Sakila fixture while both implementations coexist, the same
requests answered by each side, and stated against the abandon condition above. The incumbent is a
linear scan, so this is a measurement, not a prediction. A hot path that is slow
as a view already has a sanctioned answer, materialize with the DDL comment owning why, as the
reachability rows do; not an ad-hoc cache.

## Capability inventory

The work list. Every capability the language server serves today, with what triggers it and where a
fact-based implementation gets its answer. `†` marks a capability that returns nothing today, so it
is a gap to close rather than a behaviour to reproduce.

Every fact source below must be a relation or a view. Four rows say "classification", which is not
one: it names `FieldClassification` and `TypeClassification`, the Java projections retired above,
and leaving the word there is how a port smuggles them back in. They resolve against the
classification stratum (`intent_resolved_field_claim` and its siblings; see
`docs/architecture/explanation/fact-model.adoc`), and pinning down which view answers which row is
the first thing the substrate work settles. Which view, not how many queries: four rows projecting
four different things off one view is a fine outcome, and collapsing them because they share a
source would be the same error as sharing a type because they share a subject.

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
| no coordinate, or no value match | `ArgNameCompletions` (fallback) | `graphql_directive_argument`, bundled vocabulary |

**Hover.** `Hovers` dispatches on the same `Behavior` taxonomy, with three non-coordinate arms
around it.

| Trigger | Answers | Fact source |
|---|---|---|
| Directive name token | Directive description | `graphql_directive`, bundled vocabulary |
| `ClassNameBinding` | Class FQN + Javadoc | `jvm_class` + `SourceWalker` |
| `MethodNameBinding` | Signature + Javadoc | `jvm_method`, `jvm_method_parameter` + `SourceWalker` |
| `CatalogTableBinding` | Comment, column and reference counts | `sql_table`, `sql_column`, `sql_constraint` |
| `CatalogColumnBinding` | Column type, nullability, comment; member name and type when the backing is a record or POJO | `sql_column` (needs binding type); `jvm_record_component`, `jvm_method` for the member arms |
| `CatalogFkBinding` | FK direction and endpoints | `sql_referential_constraint` |
| `NodeTypeBinding` | `typeId`, key columns and their types | `graphitron_node`, `graphitron_node_key_column` |
| `ArgMappingBinding`, `ScalarTypeBinding` † | nothing | — |
| Any coordinate, no richer arm | SDL docstring | bundled vocabulary |
| User-declared directive arg | Arg docstring | `graphql_directive_argument` |
| SDL declaration name (`hoverClassification` toggle) | `DeclarationHovers`: classification block + Javadoc | classification + `SourceWalker` |

**Definition.** Three providers chained with `.or()` in this order, keyed on disjoint syntax.

| Provider | Trigger | Fact source |
|---|---|---|
| `Definitions` | Directive arg: `ClassName`, `MethodName`, `CatalogTable`, `CatalogColumn`, `CatalogFk` | `jvm_`/`sql_` + `SourceWalker` positions |
| `Definitions` † | `ArgMapping`, `ScalarType`, `NodeType` return empty | — |
| `IntraSchemaDefinitions` | Type reference to its declaring SDL site | `graphql_type_declaration`; a stamp-mismatched declaring file re-anchors through its live tree |
| `DeclarationDefinitions` | SDL declaration name to its bound Java | `jvm_class`, `jvm_record_component` + `SourceWalker` |

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
buffer being typed in for a single shape everywhere, and it still beats the incumbent, which
silences the whole replay while the snapshot is demoted, so a newly broken schema shows nothing at
all. Keystroke-live validation of the stale buffer is the same later iteration as the wider
shadow, taken only on measured demand.

Compile diagnostics (javac output against generated sources) sit on `Workspace` beside these but
publish through the MCP diagnostics tool, not the LSP push; they move with the workspace state,
not with this table.

**Lifecycle and state.** `didOpen` / `didChange` (incremental) / `didClose` / `didSave`;
`didChangeConfiguration` plus a `workspace/configuration` pull after `initialize` for the three inlay
toggles; `didChangeWatchedFiles` is a no-op today. The open-buffer set stays in `Workspace`; the
tree-derived type index (`refreshTypeIndex`'s declared/referenced sets) and the per-file
recalculation bookkeeping it aims retire with the keystroke cadence (see the diagnostics
paragraph above).

## Resolved questions

Three questions an earlier draft left to the reviewer, since answered against the workflow and the
fact model; the reviewer confirms rather than decides.

* **Sequencing** is additive-then-cutover with the abandon condition in "The experiment"; the
  workflow's rule for structural pivots on widely-pinned types leaves no taste call here. Whether
  the phases stay one item or split into a substrate item plus feature items is the Ready
  reviewer's call; scope, gates and measurement are identical either way. A review pass argued for
  the split, on the abandon condition rather than on size: the substrate (per-file currency, the
  graph-scoped handle, the read connection, the three capture widenings) is wanted whether or not
  the paired comparison favours the fact-based arms, so bundling it into an item whose stated
  abandon outcome is "the incumbents stay" leaves that outcome ambiguous about what reverts.
* **`SourceWalker`'s boundary** is shipped doctrine, cited in "What retires"; nothing to
  re-litigate.
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
`dependsOnDeclarations`.
