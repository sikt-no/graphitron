---
id: R362
title: "MCP catalog tools: catalog.tables / catalog.describe over a build-time CatalogFacts projection (R361 D1)"
status: In Review
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# MCP catalog tools: catalog.tables / catalog.describe over a build-time CatalogFacts projection (R361 D1)

Slice 2 of the R118 MCP-server programme, and the first structured *domain* tool slice on the
seam R361 landed. It ships two tools that let an MCP-aware agent discover the database the schema
wires to: `catalog.tables` (list tables, optional schema/name filter, paged) and `catalog.describe`
(one table: columns with SQL and Java names, SQL types, nullability, PK, unique keys, indexes, and
foreign keys in/out). These anchor the programme's foundational greenfield-onboarding use case:
point graphitron at a large existing database with an empty schema and discover what exists before
authoring anything. SQL names drive discovery; comments surface only when jOOQ codegen captured
them, degrading to name-only otherwise.

## D1 resolved: build-time enrichment via a sibling CatalogFacts projection

R361 deliberately threaded only the live `Workspace` into `GraphitronMcpServer`, *not* a live
`JooqCatalog`, and handed this slice the catalog-data projection problem. The raw `JooqCatalog`
cannot be held as a live handle: it reflects lazily against the `codegenLoader` `URLClassLoader`
that `DevMojo.withCodegenScope` closes at the end of each pass, and it is not part of
`GraphQLRewriteGenerator.BuildArtifacts`. R361 D1 named two options and preferred build-time
enrichment; this Spec commits to it and, with `principles-architect`, settles the sub-fork it
left open.

**Build-time enrichment (A), not the retained-loader lifecycle (B).** This is decisive on three
principles, not a close call:

- **Classification belongs at the parse boundary.** `JooqCatalog` is the sanctioned raw-jOOQ
  holder and `CatalogBuilder` its sanctioned marshaller into a flat wire model. (B) inverts that:
  keeping the loader alive so something downstream of `DevMojo` re-reflects on demand pushes a
  live `Table<?>` / `ForeignKey<?,?>` reflection surface past the build boundary into the MCP
  request path, the exact leak the boundary rule exists to prevent. (A) keeps the MCP module on
  the consuming side of a frozen projection.
- **Stability through simplicity.** (B) means owning a classloader lifecycle that today is scoped
  to one `withCodegenScope` pass and closed deterministically, plus an invalidation story (what
  re-opens it when the catalog-jar content hash changes?). R118's own stability gradient files the
  catalog as *rebuilt on the classpath-change trigger*, not held live; (B) fights that gradient.
- **One model, two views.** (B) would give MCP a second, differently-sourced view (live reflection)
  than the LSP's frozen catalog. (A) keeps one build-time source of truth feeding two thin views.

**A sibling `CatalogFacts` record, not in-place widening of `CompletionData`.** The richer facts
`catalog.describe` wants are PK / unique keys, SQL-vs-Java column names (`CompletionData.Column`
deliberately drops the SQL name today, keeping only the jOOQ Java field name, because LSP
completions suggest the Java form), SQL data types, FK *constraint* names (only the Java `Keys`
constant is kept), and indexes. Hanging these onto `CompletionData.Table` / `Column` / `Reference`
would widen the LSP's own query model ("answers completion / hover / diagnostic / goto-definition
requests") with fields no LSP path reads, dead weight on every completion query, and would erode
the per-component contract that makes each record self-documenting. The SQL-vs-Java split is itself
the signal: `CompletionData.Column.name` is contractually the *Java* name; `catalog.describe` wants
the *SQL* name as the primary discovery key. Those are two primary keys for two consumers. "One
model, two *thin* views" means the shared thing is the live `Workspace` / the one build-time
traversal, and each view carries exactly what its consumer reads, not one god record whose fields
mean different things to different readers.

`CatalogFacts` is therefore a frozen, SQL-name-centric projection built in the same
`CatalogBuilder` pass while the loader is open, carried alongside `CompletionData` on
`BuildArtifacts`, and exposed off `Workspace`. It is self-contained for the catalog tools: they read
only `CatalogFacts`, never a split across two projections.

**The load-bearing invariant: `CatalogFacts` holds only resolved immutable values.** Strings,
booleans, enums, `List<record>`, and graphitron-javapoet `ClassName` (loader-independent). It must
never retain a `Table<?>`, `ForeignKey<?,?>`, `org.jooq.Field`, or `Class<?>`, or it silently
becomes (B) in (A)'s clothing: a live reflection handle that `NoClassDefFoundError`s after
`withCodegenScope` closes the loader. `JooqCatalog`'s `ColumnEntry` (javaName + sqlName +
columnClass + nullable) and `KeyEntry` (PK/unique flag + keyName + columns) are already exactly this
shape, so those arms are a mechanical map from accessors `JooqCatalog` already exposes (`allColumnsOf`,
`candidateKeys`, `findIndexColumns` / `Table.getIndexes`). The FK arm is the one exception:
`ForeignKeyRef` carries only (constraint name + `Keys` `ClassName` + constant), *not* the
`columns` / `targetColumns` pairs the `catalog.describe` wire shape promises. So `CatalogFacts`'s FK
record adds explicit column-list fields; it does not re-nest `ForeignKeyRef`. The pairs are pulled
from the live `ForeignKey<?,?>` during the same build pass and reduced to `String` immediately
(`fk.getFields()` → source SQL names, `fk.getKey().getFields()` → target SQL names,
`fk.getKey().getTable().getName()` → target table), all reachable via `getReferences()` /
`findForeignKeyByName` and all resolved-immutable per the invariant.

## The cadence cost, named explicitly

(A) computes PK/unique/index/SQL-type facts on every catalog rebuild even though, until this slice's
tools exist, no LSP feature reads them. That cost is real but bounded and rides the *slow* corpus:

- It refreshes on the **classpath-change trigger** (the catalog-jar swap), not the per-keystroke
  schema-edit trigger. The hot authoring loop pays nothing; `CatalogFacts` rebuilds only when the
  compiled classpath changes, which is exactly R118's slow corpus.
- It is the same order of work `CatalogBuilder.build` already does: the reflection reads are over a
  catalog already fully loaded and already walked by `buildTables` → `buildReferencesFor` (which
  itself runs an O(tables²) inbound-FK scan today). The expensive moves (eager source-walking,
  Javadoc recovery) are already excluded by `build`'s design and stay excluded; `CatalogFacts` is
  build-derivable from the runtime catalog alone.

`buildOutput()` is the single construction site where the loader is open and `BuildArtifacts` is
assembled, so "build-time while loader open" has one home and rides the same dev-loop swap path
R361 already uses for the snapshot. The Spec keeps the projection **eager over all tables** (a
map keyed by schema-qualified SQL name): the cost is bounded by catalog size, the slow corpus, and
`catalog.tables` pages its output anyway (below), so there is no per-request fan-out to amortise.

## Deferred open questions, resolved

- **Result paging (R118 OQ5).** `catalog.tables` accepts an optional `limit` (default chosen to sit
  well under MCP response limits, e.g. 100) and an opaque `cursor`; the result carries the page plus
  a `nextCursor` (absent on the last page). The cursor is an offset into the stable
  schema-qualified-name ordering `CatalogFacts` already fixes, encoded opaquely so the wire contract
  does not promise offset semantics. `catalog.describe` returns one table and does not page; a table
  with pathologically many columns is a non-goal this slice does not pre-optimise.
- **Comment-capture expectation (R118 OQ4).** Table comments come from the runtime catalog
  (`Table.getComment()`, already captured by `commentOf`); column comments from `Field.getComment()`,
  which is populated *only* when jOOQ codegen ran with comments enabled. When absent, the field
  degrades to name-only (omitted, not empty-string-valued). The dependency is surfaced to consumers
  in the tool description and the bundled instructions, so an agent seeing name-only output knows it
  reflects codegen configuration, not a missing comment in the database.
- **Name normalization (R118 OQ3).** Splitting snake_case / camelCase into readable descriptors is a
  slice-10 (`catalog.search`) concern and is **out of scope here**. `CatalogFacts` carries the raw
  SQL and Java names verbatim; the readable per-table descriptor slice 10 embeds is composed *from*
  this projection later, so the projection stays reusable without this slice building the splitter.

## Tool surface (wire shapes)

Both tools mirror the R361 `statusTool` / `statusResult` registration shape: a `McpSchema.Tool`
with an explicit input schema, a call handler that reads the live `Workspace` projection, and a
`CallToolResult` carrying both a human-readable text summary and `structuredContent`. The two
fields are mapped off `CatalogFacts`; the MCP view never re-derives a fork the build owns.

- **`catalog.tables`** — input `{schema?: string, name?: string, limit?: int, cursor?: string}`.
  `schema` filters to one schema (case-insensitive); `name` is a case-insensitive substring filter
  on the SQL table name. Output: an ordered list of `{schema, name, comment?}` plus `nextCursor?`.
- **`catalog.describe`** — input `{table: string, schema?: string}`. `table` accepts a bare or
  schema-qualified SQL name; `schema` is the alternative to inline qualification. Resolution mirrors
  the `JooqCatalog.TableResolution` *semantics* over a parallel `CatalogFacts`-owned type whose
  `Resolved` arm wraps the frozen `CatalogFacts.Table` (never the live `JooqCatalog.TableEntry`,
  which holds a `Table<?>` and would reintroduce the loader leak): a unique match
  returns the table; an unqualified name carried by two or more schemas returns a structured
  *ambiguous* result naming the candidate schemas (so the agent re-calls qualified) rather than an
  arbitrary pick; an unknown name returns a structured *not-found*. Output for a resolved table:
  - `schema`, `name`, `comment?`
  - `columns`: ordered `[{sqlName, javaName, sqlType, nullable, comment?}]`
  - `primaryKey?`: `{constraintName, columns: [sqlName]}`
  - `uniqueKeys`: `[{constraintName, columns: [sqlName]}]` (PK excluded; dedup-on-column-set as
    `JooqCatalog.candidateKeys` already does)
  - `indexes`: `[{name, columns: [sqlName]}]`
  - `foreignKeys`: `{outgoing: [{constraintName, targetTable, columns, targetColumns}], incoming:
    [{constraintName, sourceTable, columns, targetColumns}]}`

### Stable node IDs (R118 cross-cutting)

The schema-qualified SQL table name is the stable table ID and the FK endpoints (`targetTable` /
`sourceTable`) name neighbours by that same ID; column IDs are the SQL column names within a table.
This is the edge mechanism R118 slice 7 walks and the per-table identity slice 10 embeds: this slice
emits the IDs, later slices traverse and index them. No graph store, no query language.

## What this slice does *not* change

No new classification and no new generator branch: `CatalogFacts` is a read-only projection of
*already-classified* catalog facts for a discovery tool. So **validator-mirrors-classifier does not
apply** here, there is no validate-time rejection to add, and the dispatch/`Rejection` taxonomies are
untouched. Named explicitly so a reviewer does not flag a missing validator arm.

## Implementation

- **`graphitron/src/main/java/no/sikt/graphitron/rewrite/catalog/CatalogFacts.java` (new).** The
  frozen projection: a `CatalogFacts` record holding a map (or ordered list) of `Table` facts keyed
  by schema-qualified SQL name, with nested `Column`, `Key`, `Index`, and `ForeignKey` records, all
  resolved-immutable-value only (see the load-bearing invariant). The `ForeignKey` record carries its
  own `columns` / `targetColumns` SQL-name lists (not a re-nested `ForeignKeyRef`, which lacks them).
  A `TableResolution`-shaped lookup, parallel to `JooqCatalog.TableResolution` but with its `Resolved`
  arm wrapping the frozen `CatalogFacts.Table`, surfaces resolved / ambiguous / not-found so the tool
  maps each to its wire arm.
- **`CatalogBuilder`.** A `buildCatalogFacts(JooqCatalog)` pass, run from `build` (or alongside it)
  while the loader is open, mapping `allColumnsOf` / `candidateKeys` / index + reference accessors
  into `CatalogFacts`. Independent of the assembled GraphQL schema (raw DB truth), so it takes only
  the `JooqCatalog`.
- **`GraphQLRewriteGenerator.BuildArtifacts`.** Gains a `CatalogFacts` component; `buildOutput`
  populates it from the same `JooqCatalog` it already constructs. Call sites that build a
  `BuildArtifacts` update accordingly (the production site in `buildOutput`; the `graphitron-mcp`
  test that constructs one by hand).
- **`Workspace`.** A `volatile CatalogFacts` field with a `catalogFacts()` accessor, swapped in
  `setBuildOutput` alongside the catalog and snapshot (one atomic-from-the-consumer's-view swap, one
  recalculation, as today). Defaults to an empty `CatalogFacts` until the first build.
- **`GraphitronMcpServer`.** Register `catalog.tables` and `catalog.describe` the way `statusTool`
  is registered, reading `workspace.catalogFacts()` on each call so answers reflect the latest build
  state without a new trigger. Tool descriptions surface the comment-capture dependency.

## Tests

Per the tiers in `rewrite-design-principles.adoc`.

- **Pipeline tier (primary):** SDL + the Sakila jOOQ catalog → `BuildArtifacts.catalogFacts()`
  carries the expected facts for a known table (e.g. `film`): SQL and Java column names, SQL types,
  nullability, the PK, unique key(s), index(es), and outgoing/incoming FK constraint names with
  their column pairs. This pins the fact-capture behaviour where it lives, not in a unit test of the
  builder alone.
- **MCP tool handlers (thin view):** drive a real `GraphitronMcpServer` on an ephemeral loopback
  port with the MCP SDK client (mirroring `GraphitronMcpServerTest`): `catalog.tables` lists and
  filters and pages (a `limit` smaller than the table count yields a `nextCursor`; following it
  reaches the tail with no `nextCursor`); `catalog.describe` returns the structured shape for a
  resolved table, the ambiguous arm for a name two schemas carry, and the not-found arm for an
  unknown name. Assert the mapped `structuredContent`, the way `statusResult` is tested, not
  generated-string assertions.
- **Loader-independence regression:** a focused test that `CatalogFacts` read *after* the codegen
  loader is closed returns the same values (no `NoClassDefFoundError`), pinning the invariant that
  the projection retains no live reflection handle.

## Out of scope

- **Semantic catalog search** (`catalog.search`, slice 10) and the readable-descriptor name
  normalization it needs; `CatalogFacts` carries raw names so slice 10 composes the descriptor later.
- **Cross-reference reverse-edge index / neighborhood tool** (slice 7); this slice emits forward FK
  edges and stable IDs only.
- **Any LSP behaviour change.** `CompletionData` and its consumers are untouched; `CatalogFacts` is
  additive and MCP-only.
- **Non-Postgres dialect specifics**, consistent with the rest of the catalog surface.

## Builds on

- **R361** (Done): the shared-model seam, the live `Workspace` handle and `tools` capability on
  `GraphitronMcpServer`, plus the liveness-tool registration shape these tools mirror. Landed on
  trunk, so a *Builds on*, not a `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 2 of the tool surface.
- Feeds **slice 7** (cross-reference edges, which walk the table IDs this slice emits) and **slice
  10** (`catalog.search`, which embeds the readable per-table descriptor composed from this slice's
  projection).
