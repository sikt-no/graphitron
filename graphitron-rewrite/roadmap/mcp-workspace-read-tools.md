---
id: R368
title: "MCP structured read-tools over the live Workspace: services/conditions/records, schema, diagnostics, directives (R118 slices 3-6)"
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# MCP structured read-tools over the live Workspace: services/conditions/records, schema, diagnostics, directives (R118 slices 3-6)

Collapses R118 slices 3, 4, 5, and 6 into one item. These are the *thin* structured-tool
slices on the seam R361 landed: each reads a projection already held live on the `Workspace`
and exposes it over MCP, mirroring R361's liveness-tool registration shape and R362's (sibling,
Ready) wire conventions. Unlike slice 2 (R362, raw-jOOQ catalog, which carries the D1
catalog-projection fork), none of these needs a retained classloader or the RAG stack; they are
register-one-tool-per-projection reads. Grouping them is a deliberate trade: they share a
mechanical shape, one stable-ID grammar, and the cross-field-consistency caveat (R361 D3), so one
reviewable unit that settles those shared forks once beats four near-identical ones that risk
disagreeing on them. If parallel implementers are wanted, this splits cleanly back into four along
the bullets below, but only *after* the three shared decisions in this Spec (the categorisation
model, the typed degraded-location outcome, and the resource-vs-tool split) are pinned, so the
children inherit settled forks rather than re-litigating them.

The four reads, each over a `Workspace` projection R361 / R349 / R352 already refresh:

- **Slice 3, code tools** (`services` / `conditions` / `records`): the consumer Java the schema
  wires to, read off `Workspace.catalog().externalReferences()` (a flat `CompletionData.ExternalReference`
  list) joined with `Workspace.sourceIndex()` (`SourceWalker.Index`) for source locations. The
  richest of the four; the one that may later grow an optional semantic method-search sibling (R118
  slice 11).
- **Slice 4, schema tool** (`schema`): current types/fields, classifications, backing shapes,
  `@node` metadata, and definition locations off `Workspace.snapshot()` (`LspSchemaSnapshot`). The
  data is "the work itself", least stable, never embedded, always served live and structured.
- **Slice 5, diagnostics tool** (`diagnostics`): current validation errors/warnings off
  `Workspace.validationReport()`, closing the authoring loop (an agent edits, then reads its own
  diagnostics back).
- **Slice 6, directives resource** (`directives`): a directive-vocabulary cheat-sheet (arguments,
  applicable locations, descriptions) from `Workspace.vocabulary()` (`LspVocabulary`) plus
  user-declared directives off the snapshot. Exposed as an MCP *resource* (standing pinned context),
  not a tool.

## The central fork resolved: categorisation lives at the parse boundary, per method

R118 slice 3 phrases the code tools as "categorised by the `ExternalReference` scan's return type
(`Condition` -> condition; record components -> record; else service)". Grounding that against the
real code surfaced the load-bearing design decision of this Spec, and `principles-architect` moved
the recommended direction away from the obvious thin reading.

**The scan is flat and pre-categorises nothing.** `ClasspathScanner.scan` returns every public
top-level non-jOOQ class with its public methods and record components as a flat
`List<CompletionData.ExternalReference>`; the LSP filter is "generous on purpose" and lets the
author pick. So the service/condition/record split is a *derivation the code tools perform*, not a
field already on the model.

**Per method, not per class.** Each `ExternalReference` does not bucket into exactly one of
{service, condition, record}; that collapses two orthogonal axes onto one carrier and misrepresents
the common case (a service interface exposing both a `Condition`-returning method and ordinary
service methods). The two axes already live on two `ExternalReference` fields:

- `records` lists references whose `recordComponents` is non-empty (a Java `record` / POJO backing),
  with their components and types.
- `conditions` lists the *methods* (across all references) whose return type is jOOQ
  `org.jooq.Condition`, keyed by their owning class.
- `services` lists references with their callable (non-condition) methods.

A class can legitimately appear under more than one tool; that overlap is correct, not a bug, and
the Spec states it so a reviewer does not flag it.

**`Condition` detection belongs in `ClasspathScanner`, from the un-erased FQN, carried as a typed
`Method`-level fact (option b), not as a simple-name heuristic in the tool layer (option a).**
`CompletionData.Method.returnType` is today the JVM *simple* display name ("Condition"), because
`ClasspathScanner.readMethods` calls `ClassDesc.displayName()` and erases the package at the
boundary. Detecting a jOOQ-condition method from that projection alone is therefore only a
simple-name match, defeated by a consumer's own type named `Condition`. The scanner already holds
the un-erased descriptor (`desc.returnType()`) one line before it erases it, so the classification
decision ("is this a condition method") can be made exactly, at the one place equipped to make it.
Pushing a simple-name guess into the MCP tool layer relocates a parse-boundary classification past
the boundary into a consumer that no longer has the FQN: the exact inversion the
classification-at-the-parse-boundary rule exists to prevent.

The lift is therefore: `ClasspathScanner` computes `desc.returnType()` against `org.jooq.Condition`
before erasing, and `CompletionData.Method` carries that as a typed fact (a `returnsCondition`
boolean, or a small `ReturnKind` if a third arm ever appears). The code tools and any future LSP
`@condition` completion/diagnostic arm then read the same pre-classified value instead of
re-deriving a fragile predicate.

**Why R362's "do not widen `CompletionData`" warning does not bind here.** R362 refused to hang
PK / SQL-name / index facts onto `CompletionData` because those are a *different consumer's primary
keys*, foreign data no LSP path reads, dead weight on every completion query. The condition fact is
the opposite: it is a classification *of the very method the record already describes*, derived from
data the scanner already has, and an LSP `@condition` validator is a plausible near-term second
reader. This is shared-classification-on-the-shared-record (the two-consumers-evaluate-the-same-
predicate case the model-field rule wants computed once), not the god-record case R362 rejected. The
distinction is stated here so the Spec does not appear to contradict its sibling.

**Blast-radius note, owned not hidden.** This is the one place R368 touches the shared LSP model
(`CompletionData.Method` + `ClasspathScanner`), so slice 3 is *not* purely additive / MCP-only the
way R362 was. The change is small (one typed field, one boundary computation) and existing LSP
readers are untouched (they keep reading `name` / `returnType` / `parameters`), but the Spec names
the wider blast radius rather than implying MCP-only isolation.

## Cross-cutting concerns, resolved

### Cross-field consistency (R361 D3): single-projection reads, two distinct stories

`setBuildOutput` swaps the `Workspace`'s `snapshot` / `validationReport` / `catalog` fields
non-atomically; `volatile` gives per-field visibility but no consistent multi-field read. Per-tool
single-projection (eventually-consistent) reads are sufficient; a snapshot-consistency mechanism is
real complexity bought to defend a window that closes on the next dev-loop tick, and these are
*discovery* reads for an agent, not a transactional API. But the Spec separates two consistency
stories the slices conflate, because they have different shapes:

1. **`schema` (snapshot) vs `diagnostics` (validationReport): same cadence.** `setBuildOutput`
   writes both in one call on the same trigger; the worst case is "diagnostics one build-tick behind
   schema", and each tool reads exactly one field. No correlation mechanism, no lock.
2. **Code tools join `catalog()` (build cadence) with `sourceIndex()` (`.java` source cadence):
   different triggers by design.** The two fields are refreshed by `setBuildOutput` vs
   `setSourceIndex` (the R349 / R352 decoupling). A method present in the scan can legitimately have
   *no* entry in a not-yet-rewalked `sourceIndex`, or vice versa. This is the designed cadence split,
   not a race to close; the LSP goto-definition path already lives with it.

**The source-location join is a typed left join, never a silent drop and never a hard failure.** The
per-method lookup against `sourceIndex` surfaces a small sealed/`Optional` outcome mirroring the LSP
`DefinitionTarget`: a resolved location, a *not-yet-indexed* degraded state, or an *overload-ambiguous*
outcome (`SourceWalker.Index.ambiguousMethods` already models the last). The wire shape carries
`location` as absent (not error) for the degraded arms, and the tool description names the
source-cadence dependency so an agent reading a location-less method knows it reflects an un-rewalked
`.java`, not a missing method.

### Stable cross-tool node IDs (R118 binding principle)

Every tool emits and accepts stable IDs; slice 7 (cross-reference edges) walks them, so the grammar
is settled here rather than retrofitted. The grammar, aligned with the projections' existing keys:

- **Type ID** = the SDL type name (the `typesByName` / `typeClassificationsByName` key).
- **Field ID** = the schema coordinate `Type.field` (the `fieldClassificationsByCoord` key already
  uses exactly this spelling).
- **Method ref ID** = `fqcn#method/arity` (carrying the `SourceWalker.Index.MethodKey`
  `(className, methodName, paramCount)` triple the join already uses; the `/arity` suffix is what
  disambiguates overloads, and the ambiguous arm names that the join key could not resolve).
- **Table ID / column ID** = the R362 schema-qualified SQL table name and SQL column name (the code
  tools reference tables by that same ID where a backing or condition target names one).

No graph store, no query language: a result names its neighbours by ID and the agent traverses by
following IDs through tool calls.

### Tool vs resource split: `directives` is a resource

Slices 3/4/5 are tools (invoked over volatile state); slice 6 is a resource (pinned standing
context). `LspVocabulary` is "shape, not state": read once at LSP startup, never invalidated, no
setter. That is the textbook MCP *resource* (the directive grammar a client holds pinned), and it
sits at the top of R118's stability gradient ("changes only with a graphitron version"). Collapsing
it to a tool for registration-shape consistency would erase that the MCP capability could carry.
R361 declared only the `prompts` and `tools` capabilities, so slice 6 adds the `resources`
capability and a resource specification; the Spec confirms the resource wiring (URI scheme,
`McpSchema.Resource` / the SDK's sync-resource registration) against the MCP SDK alongside the
`SyncToolSpecification` shape R361 established, the same way R362 confirmed its tool shape.

**The resource is a frozen spine plus a live overlay, named explicitly.** Its content is
`Workspace.vocabulary()` (the frozen bundled grammar) unioned with user-declared directives off
`Workspace.snapshot()` (`LspSchemaSnapshot.Built.directives()`, live). Re-reads reflect the latest
snapshot. The Spec confirms `Built.directives()` exposes `DirectiveShape` carrying args / applicable
locations / descriptions in a shape the resource can render, degrading to the bundled grammar alone
when no build has succeeded.

### Paging

`schema` (types can be many), `services`, `records`, and `diagnostics` (large on a broken schema)
reuse the R362 `limit` / opaque-`cursor` / `nextCursor` convention settled for `catalog.tables`
rather than inventing a second one: an opaque offset into the stable ID ordering, `nextCursor` absent
on the last page. `conditions` rides the same convention if it grows large. The `directives` resource
is bounded and is not paged.

## Tool & resource surface (wire shapes)

All three tools mirror the R361 `statusTool` / `statusResult` registration shape: an
`McpSchema.Tool` with an explicit input schema, a call handler that reads the live `Workspace`
projection on every call, and a `CallToolResult` carrying both a human-readable text summary and
`structuredContent`. The MCP view never re-derives a fork the projection owns; the one classification
this item adds (`Condition` detection) is computed at the parse boundary, not in the handler.

- **`services`** -- input `{name?: string, limit?: int, cursor?: string}`. `name` is a
  case-insensitive substring filter on the class FQN. Output: ordered
  `[{classRef, className, methods: [{methodRef, name, returnType, parameters: [{name?, type, source?}], description?}], description?}]`
  plus `nextCursor?`. Each `methodRef` is the `fqcn#method/arity` ID; each entry carries the class
  source location via the `sourceIndex` join (left join, absent when not-yet-indexed). Methods whose
  return type is `Condition` are included here too (a class is both a service and a condition host);
  the `conditions` tool is the condition-filtered view.
- **`conditions`** -- input `{name?: string, limit?: int, cursor?: string}`. Output: the methods
  whose typed `returnsCondition` fact is set, ordered, `[{methodRef, className, name, parameters,
  description?, location?}]` plus `nextCursor?`. The location join and degraded-arm handling are
  identical to `services`.
- **`records`** -- input `{name?: string, limit?: int, cursor?: string}`. Output: references with a
  non-empty `recordComponents`, `[{classRef, className, components: [{name, displayType}],
  location?}]` plus `nextCursor?`.
- **`schema`** -- input `{type?: string, limit?: int, cursor?: string}`. `type` narrows to one SDL
  type (returns its fields in full); absent, lists types paged. Output per type:
  `{typeRef, typeClassification, backingShape, node?, fields: [{fieldRef, classification, ...}],
  definitionLocation?}` where `typeClassification` / `backingShape` map off `typeClassificationsByName`
  / `typesByName`, `fields` off `fieldClassificationsByCoord` keyed by `Type.field`, `node` off the
  `@node` metadata, and `definitionLocation` off `typeDefinitionLocations`. The mapping is an
  exhaustive `switch` over the `LspSchemaSnapshot` / `TypeBackingShape` / classification sealed
  permits with no `default`, mirroring `statusResult`, so a new arm forces a compile-time choice.
- **`diagnostics`** -- input `{severity?: "error"|"warning", coordinate?: string, limit?: int,
  cursor?: string}`. Output: ordered `[{severity, coordinate?, message, rejectionKind?, location?}]`
  plus `nextCursor?`, mapped off `ValidationReport.errors` (`coordinate` / `rejection.message()` /
  `RejectionKind` / `location`) and `.warnings` (`message` / `location`). The tool reports the
  snapshot's availability/freshness axis alongside, so an agent can tell whether diagnostics are
  current relative to the schema it just read (the benign same-cadence story above), without a
  consistency lock.

Resource:

- **`directives`** -- an MCP resource (e.g. URI `graphitron://directives`) returning the directive
  cheat-sheet: per directive, its arguments (name, type, description), applicable locations, and
  description, composed from `vocabulary()` (frozen) unioned with snapshot user-declared directives
  (live). Pinned, re-readable, not invoked.

## What this item does *not* change

No new generator branch and no new validate-time rejection. Slices 4/5/6 are pure read projections of
*already-classified* data (the snapshot's classifications, the validation report's rejections, the
bundled grammar). **Re-stating the validator-mirrors-classifier carve-out with slice 3's scanner lift
in view:** the one classification this item adds is `Condition` detection in `ClasspathScanner`,
descriptive metadata for a discovery tool. It gates no emission and adds no validate-time arm, so
validator-mirrors-classifier still does not apply; the carve-out is re-derived here rather than
inherited verbatim from R362, because the scanner lift means the claim is no longer trivially true.
Named explicitly so a reviewer does not flag a missing validator arm.

## Implementation

- **`ClasspathScanner` + `CompletionData.Method`.** Compute `desc.returnType()` against
  `org.jooq.Condition` in `readMethods` before `displayName()` erases the package; carry the result
  as a typed `Method`-level fact (`returnsCondition` boolean or a small `ReturnKind`). Existing LSP
  readers are untouched. This is the only shared-model change.
- **`Workspace`.** No new field. `catalog()` / `sourceIndex()` / `snapshot()` / `validationReport()`
  / `vocabulary()` accessors already expose every projection the tools read.
- **`GraphitronMcpServer`.** Register `services` / `conditions` / `records` / `schema` /
  `diagnostics` the way `statusTool` is registered, each reading its `Workspace` projection on every
  call. Add the `resources` capability and a `directives` resource specification (confirm the SDK
  shape). The source-location join is a private helper returning the typed resolved / not-indexed /
  ambiguous outcome; tool handlers map each arm to the wire shape (location present / absent). Tool
  descriptions surface the source-cadence and snapshot-freshness dependencies.

## Tests

Per the tiers in `rewrite-design-principles.adoc`.

- **Pipeline / unit tier (categorisation lift):** `ClasspathScanner` over a fixture classpath
  carrying a class with a `Condition`-returning method, a consumer type named `Condition` that is
  *not* `org.jooq.Condition` (the false-positive the FQN lift defends against), a record class, and a
  plain service class: assert `returnsCondition` is set only for the real jOOQ-condition method, and
  that the consumer `Condition` does not get mis-tagged. This pins the boundary classification where
  it lives.
- **MCP tool handlers (thin view):** drive a real `GraphitronMcpServer` on an ephemeral loopback port
  with the MCP SDK client (mirroring `GraphitronMcpServerTest`): `services` / `conditions` / `records`
  return the categorised structured shapes with method refs and (where indexed) locations, and the
  not-yet-indexed arm yields a location-absent entry rather than an error; `schema` returns types with
  classifications / backing shapes / `@node` / field coordinates and pages; `diagnostics` returns the
  mapped errors/warnings on a schema with a known rejection and reports snapshot freshness alongside;
  the `directives` resource lists the bundled grammar and a user-declared directive from a built
  snapshot. Assert the mapped `structuredContent`, the way `statusResult` is tested, not
  generated-string assertions.
- **Stable-ID round-trip:** a focused assertion that the `methodRef` / field coordinate IDs a tool
  emits are the same IDs the join keys / `fieldClassificationsByCoord` use, pinning the grammar slice
  7 will walk.

## Out of scope

- **Semantic method search** (R118 slice 11) and any embedding / RAG stack; these are structured
  reads only.
- **Cross-reference reverse-edge index / neighborhood tool** (slice 7); this item emits forward
  references and stable IDs only.
- **A snapshot-consistency / multi-field-atomic read mechanism** on `Workspace`; eventual consistency
  is the resolved contract.
- **Catalog tools** (`catalog.tables` / `catalog.describe`, R362) and their `CatalogFacts`
  projection; sibling slice, shipped separately.

## Builds on

- **R361** (Done): the shared-model seam, the live `Workspace` handle, the `tools` capability on
  `GraphitronMcpServer`, and the liveness-tool registration shape every read here mirrors. Landed on
  trunk, so a *Builds on*, not a `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slices 3-6 of the tool surface.
- **R362** (`mcp-catalog-tools.md`): sibling slice 2; shares the page-cursor convention and the
  stable-ID grammar (the table/column IDs the code tools reference).
- Feeds **slice 7** (cross-reference edges), which walks the schema-coordinate and method-ref IDs
  these tools emit.
