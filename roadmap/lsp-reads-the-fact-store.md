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

The language server is not fact-based. It answers completion, hover, go-to-definition, inlay hints,
diagnostics and code actions out of `CompletionData` and `LspSchemaSnapshot`: pre-baked in-memory
projections built per pipeline round, carrying what someone decided in advance a consumer would
want. Everything the projections cost follows from that shape. They can only answer questions
somebody pre-projected. Their lookups are linear scans. They accrete a field whenever a consumer
needs something new. They flatten distinctions the underlying facts carry, most visibly
`typeDefinitionLocations`, a `Map<String, SourceLocation>` that cannot represent a type assembled
from several files at all. And because they are rebuilt only by a codegen pass that parses the whole
schema, one unparseable buffer invalidates the workspace's entire knowledge, which the dev loop
works around by hand.

The store holds these facts as relations already, at the grain the facts have, for every graph in
the workspace.

## The experiment

This item is the experiment, and the claim under test is that a fact-based architecture makes the
language server *substantially simpler*, not marginally cheaper.

Scope is the whole of `graphitron-lsp`. Every feature moves. The measurement is stated before the
work starts so it cannot be chosen afterwards to flatter the result:

* `graphitron-lsp` main sources today: **9,119 lines** across eleven packages (`parsing` 18 files,
  `completions` 15, `state` 5, `server` 4, `definition` 4, `code_action` 4, `hover` 3, `inlay` 2,
  `diagnostics` 1, `trace` 1, plus `Descriptions`).
* The `rewrite/catalog` projection seam in `graphitron`: **4,008 lines**.
* Branch points per feature entry point, counted the same way on both sides.

Report all three at the end, whichever way they come out, plus the SQL added. A result that shows
the architecture did not simplify the language server is a finding worth having and must be reported
as one.

**Do not spec this as a port.** The incumbent is the thing being judged, so pinning the new
implementation to its behaviour would import the shape under test. There is no shadow-parity gate
here and no byte-equality on rendered output; those belong to a cutover that must preserve a
derivation, and this is not one.

## The division of labour

Two mechanisms, and the boundary between them is exact.

**Tree-sitter answers where the cursor is.** Buffer position to schema coordinate, over the live and
possibly unparseable buffer. It produces no fact about the schema and writes nothing to the store.
That is its whole job, and it is the only thing in the language server that needs to tolerate broken
syntax.

**The store answers everything else**, for the whole workspace, at the grain the facts have.

The store's coverage is wider than "the last successful build" suggests, and getting this wrong is
what made an earlier draft of this item defensive. A graph is many schema files. When an author
opens a new one and types `extend type |`, that buffer is invalid and every *other* schema file is
well formed, unchanged, and captured. The completion wanted is the type names those other files
declare. The invalid buffer is not an obstacle to answering; it is the question.

So facts divide three ways, by which file they came from:

* Catalog and classpath facts (`sql_`, `jvm_`), untouched by schema editing at all.
* SDL facts from files not being edited, correct because those files have not changed.
* SDL facts from the buffer under the cursor, the only stale ones, and exactly what tree-sitter
  reads live.

There is no gap between those three. The store is authoritative for the whole workspace except the
buffers being edited, and tree-sitter covers precisely those.

## What the store must provide

**Per-file SDL currency.** This is the one genuine substrate gap and it is in scope, because "the
whole LSP" cannot be built on a whole-workspace validity bit. `FactCapture.capture` takes a whole
`TypeDefinitionRegistry`, so one unparseable file means capture does not run and `demoteSnapshot`
marks the entire snapshot stale. Validity is per file. The relations already have the granularity
the capture path lacks: `graphql_type_declaration` carries `source_name` and, in its own comment,
"indexes the incremental-refresh unit ('which types does this file touch')", and `store_source`
stamps each schema file separately. Capture becomes per-file, so a dirty buffer is a local condition
and every other file's rows stay current on their own terms.

**A graph-scoped handle.** The `sql_` family is keyed by `source_name`, not by graph, and a
persisted store spans every module of a workspace. Catalog reads join through `store_graph_source`,
and the handle a consumer receives is `(DSLContext, graphName)`, the shape
`GraphitronMcpServer.StoreHandle` already is, never a bare `DSLContext`. Carrying the graph name in
the handle makes the scoping structural rather than a rule each query site must remember. No new
facts are needed for this: `GraphSourceMembership.note` already records the packages the catalog
walk read, which is a stronger fact than the configured package would be.

**Its own read connection.** `GraphitronModelStore` holds one `Connection`; `DevMojo` hands that
same `dsl()` to the MCP server, safe there only because MCP is turn-based. The language server is
not. It opens an additional connection on the same URL, which both URL shapes admit. Capture runs as
a single transaction, so that reader sees the previous committed state until commit and the new
state after, never a half-written round. That is read-consistency and it is all that is claimed from
it; an H2 isolation default is not an enforcer of anything else.

**Sealed resolution outcomes.** The store's keys are honest where the projections' lists were not:
`sql_table` is keyed `(source_name, table_schema, table_name)`, so an unqualified name may genuinely
match several rows, and `jvm_method` is keyed by `descriptor` because erased display names collided
on overloads. Resolution returns `Resolved` / `Ambiguous` / `NotFound` per lookup axis;
`CatalogFacts.resolve` is the in-tree exemplar. An outcome also carries which file a fact would have
come from, because an SDL miss is a real miss *unless* the coordinate belongs to a dirty buffer,
which the language server knows and the store cannot.

**Coordinate-keyed lookups.** The parse hands over a coordinate and the store answers it. `DeclTarget`
is the right seam and nearly the right shape already, a sealed resolution from a declaration
coordinate shared by hover and goto-definition so their parity is structural; its variants carry
`CompletionData` records, and they carry coordinates instead.

## Capture widenings

Three facts the store is missing that readers need. Each is unrecoverable downstream because it is
read off a live handle inside the codegen scope, which is the same containment argument in all three
cases.

* **The jOOQ binding type on `sql_column`.** `ColumnFacts` calls itself "the resolved-immutable
  superset of `ColumnEntry`" and is not one: both read the same `org.jooq.Field`, one taking
  `col.getType().getName()` and the other `col.getDataType().getTypeName()`, each dropping the
  other. Capture writes `ColumnFacts`, so the binding type reaches no relation. A column has both a
  SQL type and a binding type; they are orthogonal axes. Correct that javadoc in the same edit.
* **`sql_constraint.jooq_name`.** `JooqCatalog.fkJavaConstantName` resolves the `Keys` constant by
  reference identity over the generated class's fields, a reflective decode rather than a formula,
  and it is what an author types in `@reference(key:)`. `sql_table` and `sql_column` each already
  carry a `jooq_name`; the foreign key is the odd one out. Capturing it also retires
  `CatalogBuilder`'s `ctx.jooqPackage() + ".Keys"` formula, which `JooqCatalog` already derives
  schema-correctly.
* **The generated class FQNs**, at the grain the concept has rather than the projection's. The table
  class FQN is per table and belongs on `sql_table`. The `Keys` class FQN is per
  `(source_name, table_schema)` and is copied onto every `CompletionData.Reference` today, which is a
  repeating group.

Also confirm `jvm_class`'s filters (public, non-synthetic, top-level, outside the generated jOOQ
package) against what the language server needs, rather than assuming they agree.

## What retires

`CompletionData`, `CatalogFacts`, `LspSchemaSnapshot` and its freshness seal, `CatalogBuilder`'s
projection-building pass, and most of the `rewrite/catalog` seam. `DevMojo.rebuildCatalog`'s
keep-previous-and-demote workaround goes with them: under source-keyed relations, catalog candidates
are not *retained* across a bad parse, they were never invalidated by it.

`SourceWalker` stays. It is not a projection of stored facts but a live index on the `.java` cadence,
and the store's own DDL designs it out deliberately: the `jvm_class` comment says Javadoc and source
positions "deliberately stay out; those live on the LSP's `SourceWalker` cadence and are joined at
request time, so a `.java` edit is seen without a generator rebuild." Capturing it would make it
stale provenance rather than absent provenance.

`CatalogFacts`' non-LSP readers have to move with it: `EdgeProducer`, `EdgesTool`,
`ReverseEdgeIndex`, `NodeRef`, `CatalogDescriptors` and `CatalogSearchIndex` in `graphitron-mcp`,
and `TenantScopes` and `GraphQLRewriteGenerator` inside `graphitron`. That is not LSP work and it is
not optional either; the projection cannot delete while they read it.

## Acceptance

Features are specified against fixtures, not against the incumbent: given this buffer, this cursor
position and these store rows, this is the completion list, this hover, this jump target. That is
the spec-by-example shape the pipeline tier already uses, and it is a better surface than the
current tests regardless of this item, because it states what the feature owes rather than what the
last implementation happened to do.

Fixtures seed store rows directly.
`ColumnMatchClaimTest.siblingGraphsResolveThroughTheirOwnMembership` is the shipped precedent: two
graphs in one store, the same unqualified table name in each, built by inserting `store_graph`,
`store_source`, `store_graph_source` and `sql_table` rows. Test data does not have to come from a
crawler; the crawlers are tested where they are.

Three cases the fixture corpus must carry, because each is a behaviour the current design cannot
express and would otherwise ship untested:

* A dirty buffer beside well-formed siblings: `extend type |` in a new file completes against the
  types the other files declare.
* A type assembled from several files, resolving to its several declaration sites.
* Two graphs in one store, neither seeing the other's tables.

Latency is measured per request against the store, on the Sakila fixture, and stated. The incumbent
is a linear scan, so this is a measurement rather than a prediction either way.

## Open questions for the reviewer

* **Sequencing.** The whole language server is a large single landing. The honest options are one
  item with internal phases, or a substrate item (per-file capture, handle, read surface, capture
  widenings) followed by the features. Sequencing does not change the scope, and the measurement is
  taken across the whole of it either way.
* **`SourceWalker`'s boundary**, if the cadence argument above is judged insufficient.
* **Whether `CatalogFacts`' non-LSP readers land here or alongside**, given that the projection
  cannot delete until they move.

## Roadmap entries

* Per-file SDL capture is in scope here; if it is split out, it is the first thing to land.
