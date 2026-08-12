---
id: R638
title: "The LSP is a fact-store client"
status: Ready
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

**This is not a port.** The incumbent is what is being judged, so there is no shadow-parity gate and
no byte-equality on rendered output; pinning the new implementation to the old behaviour would
import the shape under test. What replaces the gate is an enforcer, not care: a meta-test in the
`GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` mould asserting the
(`Behavior` arm × surface) matrix is an exhaustive partition over answered / declared-no-answer /
unimplemented, each surface's dispatch a compile-checked exhaustive switch. The `†` gaps below stop
being silently empty arms and become declared facts the test pins, and the inventory becomes a
rendered view of the matrix rather than prose that rots.

## The division of labour

**Tree-sitter answers where the cursor is**: buffer position to schema coordinate, over the live and
possibly unparseable buffer. It produces no fact and writes nothing to the store. That is its whole
job, and it is the only part that must tolerate broken syntax.

**The store answers everything else**, for the whole workspace.

A graph is many schema files, so validity is per file, not per workspace. An author typing
`extend type |` in a new file has one invalid buffer and a workspace of well-formed captured ones;
the completion wanted is what those other files declare. The invalid buffer is not an obstacle to
answering, it is the question. Facts divide three ways by which file they came from:

* Catalog and classpath facts (`sql_`, `jvm_`), untouched by schema editing.
* SDL facts from files not being edited, correct because those files have not changed.
* SDL facts from the buffer under the cursor, the only stale ones, and exactly what tree-sitter
  reads live.

There is no gap between them: the store is authoritative for everything except the buffers being
edited, and tree-sitter covers precisely those.

## What the store must provide

**Per-file SDL currency.** The one real substrate gap, and in scope, because the whole LSP cannot
rest on a workspace-wide validity bit. `FactCapture.capture` takes a whole `TypeDefinitionRegistry`,
so one bad file means no capture and a wholly demoted snapshot. Per-file is the currency and
invalidation unit, not the capture unit: several facts exist only over the merged registry
(`merge_ordinal`, the reachability and input-occurrence rows), so parsing is per file, the
parseable files merge into one registry, and capture stays whole-graph in its one transaction. A
file that fails to parse keeps its previous partition, and the failure lands as a located violation
row that reaches the diagnostic view like every other violation. The relations already carry the
granularity: `graphql_type_declaration` indexes "which types does this file touch" by its own
comment, and `store_source` stamps each file.

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
erased display names collided on overloads. Resolution returns `Resolved` / `Ambiguous` /
`NotFound` per axis, as `CatalogFacts.resolve` already does, plus the arm that replaces
`LspSchemaSnapshot`'s freshness seal: `Indeterminate(sourceName)`, meaning the answer would come
from a source whose stamp no longer matches the buffer. One seam owns the join of
`store_source.stamp` against the open-buffer set and decides currency once, as a variant rather
than a field, so no surface re-derives "is that file dirty?" for itself: an SDL miss is a real miss
or it is `Indeterminate`, and every consumer's switch breaks until it says which. `Ambiguous` at an
author-written coordinate surfaces as a diagnostic, never a silent first match.

**Coordinate-keyed lookups.** The parse hands over a coordinate; the store answers it. `DeclTarget`
is the right seam already, a sealed resolution shared by hover and goto-definition so their parity
is structural. Its variants carry `CompletionData` records today and carry coordinates instead.
Re-sourcing is also the moment its method-backedness check stops being an `instanceof` list behind
a `default` arm and becomes a read over `graphitron_field_binding`.

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
projection pass, most of `rewrite/catalog`, and `DevMojo.rebuildCatalog`'s keep-previous-and-demote
workaround — catalog candidates are not retained across a bad parse, they were never invalidated by
it.

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

Five request capabilities are registered (`GraphitronLanguageServer.initialize`): hover, completion,
definition, code action, inlay hint. Diagnostics are pushed. Document sync is incremental.

**Completion.** `Completions.at` resolves the coordinate once, resolves its `Behavior`, and runs the
providers registered for that arm in order, first non-empty wins. Two providers on one arm is a
projection-era artifact (two projections answered `MethodNameBinding`); in fact terms an arm is one
query with its ordering stated in the view, which is where the simplicity claim should show first.

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
| `IntraSchemaDefinitions` | Type reference to its declaring SDL site | open buffers first, then `graphql_type_declaration` |
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

Compile diagnostics (javac output against generated sources) sit on `Workspace` beside these but
publish through the MCP diagnostics tool, not the LSP push; they move with the workspace state,
not with this table.

**Lifecycle and state.** `didOpen` / `didChange` (incremental) / `didClose` / `didSave`;
`didChangeConfiguration` plus a `workspace/configuration` pull after `initialize` for the three inlay
toggles; `didChangeWatchedFiles` is a no-op today. Per-file recalculation bookkeeping and the
open-buffer set live in `Workspace` / `WorkspaceFile`.

## Resolved questions

Three questions an earlier draft left to the reviewer, since answered against the workflow and the
fact model; the reviewer confirms rather than decides.

* **Sequencing** is additive-then-cutover with the abandon condition in "The experiment"; the
  workflow's rule for structural pivots on widely-pinned types leaves no taste call here. Whether
  the phases stay one item or split into a substrate item plus feature items is the Ready
  reviewer's call; scope, gates and measurement are identical either way.
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
`typeDefinitionLocations`, `CatalogBuilder`'s projection pass, and `DevMojo.rebuildCatalog`'s
keep-previous-and-demote path.
