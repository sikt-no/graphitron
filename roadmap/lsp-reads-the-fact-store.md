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
added. A result showing no simplification is a finding and gets reported as one.

**This is not a port.** The incumbent is what is being judged, so there is no shadow-parity gate and
no byte-equality on rendered output; pinning the new implementation to the old behaviour would
import the shape under test.

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
so one bad file means no capture and a wholly demoted snapshot. The relations already have the
granularity the capture path lacks: `graphql_type_declaration` is keyed by `source_name` and indexes
"which types does this file touch" by its own comment, and `store_source` stamps each file. Capture
becomes per-file.

**A graph-scoped handle.** `sql_` is keyed by `source_name` and a persisted store spans every module
of a workspace, so catalog reads join through `store_graph_source` and the handle is
`(DSLContext, graphName)` — the shape `StoreHandle` already is — never a bare `DSLContext`. That
makes the scoping structural rather than a rule each query site remembers. No new facts are needed:
`GraphSourceMembership.note` already records the packages the catalog walk read, which beats the
configured package.

**Its own read connection.** `GraphitronModelStore` holds one `Connection`, shared with the MCP
server, which is safe only because MCP is turn-based. The LSP opens a second connection on the same
URL, which both URL shapes admit. Capture is a single transaction, so that reader never sees a
half-written round. Read-consistency is all that is claimed from it; an H2 isolation default
enforces nothing else.

**Sealed resolution outcomes.** The store's keys are honest where the projections' lists were not:
an unqualified table name may match several rows, and `jvm_method` keys on `descriptor` because
erased display names collided on overloads. Resolution returns `Resolved` / `Ambiguous` /
`NotFound` per axis, as `CatalogFacts.resolve` already does. An outcome also carries which file a
fact would have come from: an SDL miss is a real miss *unless* the coordinate is in a dirty buffer,
which the LSP knows and the store cannot.

**Coordinate-keyed lookups.** The parse hands over a coordinate; the store answers it. `DeclTarget`
is the right seam already, a sealed resolution shared by hover and goto-definition so their parity
is structural. Its variants carry `CompletionData` records today and carry coordinates instead.

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

`CatalogFacts` has non-LSP readers that must move with it: `EdgeProducer`, `EdgesTool`,
`ReverseEdgeIndex`, `NodeRef`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`,
plus `TenantScopes` and `GraphQLRewriteGenerator` in `graphitron`. Not LSP work, not optional; the
projection cannot delete while they read it.

`SourceWalker` stays. It is a live index on the `.java` cadence rather than a projection of stored
facts, and the `jvm_class` comment designs it out deliberately so a `.java` edit is seen without a
generator rebuild. Capturing it would turn absent provenance into stale provenance.

## Acceptance

Features are specified against fixtures, not the incumbent: given this buffer, this cursor and these
store rows, this answer. Fixtures seed rows directly —
`ColumnMatchClaimTest.siblingGraphsResolveThroughTheirOwnMembership` is the precedent, standing up
two graphs in one store by insert. The crawlers are tested where they are.

Three cases the corpus must carry, each being something the current design cannot express:

* A dirty buffer beside well-formed siblings: `extend type |` completes against the other files.
* A type assembled from several files, resolving to all its declaration sites.
* Two graphs in one store, neither seeing the other's tables.

Latency measured per request on the Sakila fixture and stated. The incumbent is a linear scan, so
this is a measurement, not a prediction.

## Capability inventory

The work list. Every capability the language server serves today, with what triggers it and where a
fact-based implementation gets its answer. `†` marks a capability that returns nothing today, so it
is a gap to close rather than a behaviour to reproduce.

Five request capabilities are registered (`GraphitronLanguageServer.initialize`): hover, completion,
definition, code action, inlay hint. Diagnostics are pushed. Document sync is incremental.

**Completion.** `Completions.at` resolves the coordinate once, resolves its `Behavior`, and runs the
providers registered for that arm in order, first non-empty wins.

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
| `CatalogColumnBinding` | Type, nullability, comment | `sql_column` (needs binding type) |
| `CatalogFkBinding` | FK direction and endpoints | `sql_referential_constraint` |
| `NodeTypeBinding` | `typeId`, key columns and their types | `graphitron_node`, `graphitron_node_key_column` |
| `ArgMappingBinding`, `ScalarTypeBinding` † | nothing | — |
| Any coordinate, no richer arm | SDL docstring | bundled vocabulary |
| User-declared directive arg | Arg docstring | `graphql_directive_argument` |
| SDL declaration name (`hoverClassification` toggle) | `DeclarationHovers`: classification block + Javadoc | classification + `SourceWalker` |

**Definition.** Three providers chained with `.or()`, keyed on disjoint syntax.

| Provider | Trigger | Fact source |
|---|---|---|
| `Definitions` | Directive arg: `ClassName`, `MethodName`, `CatalogTable`, `CatalogColumn`, `CatalogFk` | `jvm_`/`sql_` + `SourceWalker` positions |
| `Definitions` † | `ArgMapping`, `ScalarType`, `NodeType` return empty | — |
| `DeclarationDefinitions` | SDL declaration name to its bound Java | `jvm_class`, `jvm_record_component` + `SourceWalker` |
| `IntraSchemaDefinitions` | Type reference to its declaring SDL site | open buffers first, then `graphql_type_declaration` |

**Inlay hints.** Three independent toggles, all default off (`InlayHintConfig`); two collectors.

| Toggle | Collector | Fact source |
|---|---|---|
| `classification` | `collectClassificationHints` | classification |
| `inferredDirectives` | `collectInferredDirectiveHints`, renderers for `@table`, `@field`, `@reference` | `graphitron_table`, `graphitron_field_binding`, `graphitron_field_reference*` |
| `inferredDirectives` | `collectAbsentDirectiveHints` | same, absence arm |
| `hoverClassification` | gates `DeclarationHovers` (see hover) | classification |

**Code actions.** Two branches, deliberately not sharing a path.

| Branch | Trigger | Fact source |
|---|---|---|
| `LintQuickFixes` | A fix-bearing lint finding in the report, `Built.Current` only | `lint_finding` + the rule's own `LintFix` |
| `SdlActions` † | Detector re-scan per document; registry is empty today | — |

Each `SdlActions` fix offers three scopes: per site, whole file, whole workspace.

**Diagnostics.** Pushed on change and save, from five sources.

| Source | What it reports |
|---|---|
| Coordinate validation | Dispatches all eight `Behavior` arms; unresolved values against the catalog and classpath |
| Unknown args | Directive args not declared, bundled and user-declared paths separately |
| Required args | Declared-required args absent |
| Unknown directive | Skipping the GraphQL spec built-ins |
| `ValidationReport` replay | Build errors and warnings for URIs the report covers |
| Compile diagnostics | javac output against generated sources |

**Lifecycle and state.** `didOpen` / `didChange` (incremental) / `didClose` / `didSave`;
`didChangeConfiguration` plus a `workspace/configuration` pull after `initialize` for the three inlay
toggles; `didChangeWatchedFiles` is a no-op today. Per-file recalculation bookkeeping and the
open-buffer set live in `Workspace` / `WorkspaceFile`.

## Open questions for the reviewer

* **Sequencing.** One item with phases, or a substrate item (per-file capture, handle, read surface,
  capture widenings) then the features. Scope and measurement are unchanged either way.
* **`SourceWalker`'s boundary**, if the cadence argument is judged insufficient.
* **Whether `CatalogFacts`' non-LSP readers land here or alongside.**
