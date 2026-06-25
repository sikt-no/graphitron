---
id: R374
title: "MCP cross-reference edges: forward edges + stable node IDs, then a reverse-edge index for impact analysis (R118 slice 7)"
status: Spec
bucket: feature
theme: lsp
depends-on: []
created: 2026-06-25
last-updated: 2026-06-25
---

# MCP cross-reference edges: forward edges + stable node IDs, then a reverse-edge index for impact analysis (R118 slice 7)

Slice 7 of the R118 MCP-server programme: the slice that turns the independent structured tools
into a *traversable graph* without a graph database or a query language. It is the realisation of
R118's binding principle "stable cross-tool node IDs = edges": every tool already emits and accepts
stable identifiers (a schema coordinate `Type.field`, a table name, a method ref
`Class.method/arity`), a result names its neighbours by ID, and the agent traverses by following
those IDs through further tool calls. This slice makes that traversal first-class: it ships the
edges as data, not just as IDs an agent happens to recognise.

The ID-emitting slices it consumes have both landed. **R362** (slice 2, catalog tools, Done) fixed
the table / column ID shape (`CatalogFacts`, keyed by schema-qualified SQL name) and the page-cursor
convention; **R368** (slices 3-6, structured read-tools, Done) fixed the schema-coordinate
(`Type.field`) and method-ref (`Class.method/arity`) grammar in `McpWire`, and pinned the per-method
categorisation the code-side edges walk. Slice 7 builds on a settled ID vocabulary rather than
inventing one.

## What it delivers

A single new agent-facing tool plus the model and index behind it, all in `graphitron-mcp`, with
the R362/R368 wire shapes left frozen:

- A dedicated **`edges`** tool (D-A) that takes one node reference + a direction and returns its
  typed neighbours.
- A sealed **`NodeRef`** node-ID model (D-D) that owns the whole stable-ID grammar (schema
  coordinate, table, column, method ref, class) and composes the wire-string form at the boundary,
  reconciling the bare names the classifier carries onto the qualified IDs the catalog/code tools
  accept.
- An **`EdgeKind`** relationship taxonomy (D-B) derived by an exhaustive no-`default` switch over the
  same `FieldClassification` / `TypeClassification` permits `SchemaView` already switches on, with a
  coverage test pinning the mapping.
- A lazily-built, snapshot-memoised **reverse-edge index** (D-C) for impact analysis, the slice's
  real deliverable.

## Scope, staged (R118 OQ6: ship forward edges first)

R118 is explicit that edges ship in order of proven need. This Spec fully designs stages 1 and 2 and
explicitly defers stage 3:

1. **Forward edges + stable IDs (nearly free).** Each structured result already knows its outbound
   neighbours: a schema field names its backing table / column and its `@service` / `@condition`
   method (its `FieldClassification`); a table names its outbound and inbound FKs (`CatalogFacts`); a
   `@node` type names its table. The `edges` tool computes these per call from the live projections,
   no index. This stage also settles the `NodeRef` grammar and the `EdgeKind` taxonomy the reverse
   stage reuses.
2. **Reverse-edge index (impact analysis).** The high-value direction agents cannot cheaply walk
   forward: "what schema fields break if I touch this column / method?" A built-once reverse index
   keyed by the stable table / column / method IDs, mapping each back to the schema field coordinates
   that bind it. This is the slice's real deliverable; stage 1 is mostly bookkeeping on data already
   in hand.
3. **(deferred) `neighborhood` subgraph tool.** A single call returning the local subgraph around a
   node. **Not built in this slice.** The dedicated-tool choice (D-A) means each traversal hop costs
   one round trip; `neighborhood` is the materialisation that collapses those hops, and R118 OQ6 says
   to build it only once round-trip count proves painful in practice. The dedicated-tool decision is
   *why* that round-trip signal is meaningful and measurable, so D-A and this deferral are the same
   bet, not two independent calls.

## D-A resolved: a dedicated `edges` tool, not edges inline on every result

Edges ride a single new tool rather than a `neighbours` field retrofitted onto every R362/R368
result.

- **The structured slices' contracts stay frozen.** Inline edges would couple every tool's wire
  shape to the edge model and re-emit forward-edge data on every `schema` / `catalog.describe` call
  whether or not the agent traverses. A dedicated tool keeps the eight existing contracts clean
  ("Separate business logic from API code"; the Backlog's explicit lean against retrofitting the
  R362/R368 wire shapes).
- **One home for the reverse index.** The reverse direction has no natural inline host (a column is
  not a tool result of its own); the dedicated tool is where both directions live behind one
  contract ("Stability through simplicity": one new contract, not eight coupled ones).
- **The cost this accepts.** One extra round trip per hop. That is the exact cost stage 3
  (`neighborhood`) exists to remove, and measuring it is the R118 OQ6 trigger for building stage 3.

## D-D resolved: a sealed `NodeRef` model that owns the ID grammar and reconciles bare names

The node-ID grammar is a sealed hierarchy, not a set of flat strings parsed at the consumer. This is
"generation-thinking" and "wire-format encoding is a boundary concern": the producer already holds
each ID's parts as resolved values, so the model carries them structured and composes the wire
string only at the `McpWire` boundary, exactly as `McpWire.methodRef(className, methodName, arity)`
already composes `fqcn#method/arity` from three structured inputs.

```
sealed interface NodeRef permits TypeNode, FieldNode, TableNode, ColumnNode, MethodNode, ClassNode {
    String wireId();   // the stable string an agent sees and can pass back
    String kind();     // "type" | "field" | "table" | "column" | "method" | "class"
}
record TypeNode(String typeName)                        // "Type"          (schema tool)
record FieldNode(String coordinate)                     // "Type.field"    (schema tool)
record TableNode(String schema, String name)           // "schema.table"  (catalog.describe)
record ColumnNode(TableNode table, String sqlColumn)   // wireId "schema.table:column"
record MethodNode(String fqcn, String method, int arity) // "fqcn#method/arity" (services/conditions)
record ClassNode(String fqcn)                           // "fqcn"          (services/records)
```

**The two reconciliation gaps the Backlog half-names, both resolved here.** The classifier carries
*bare* names; the catalog/code tools accept *qualified* IDs. The edge producer is where the two meet:

- **Table / column (the column ID grammar).** `FieldClassification` carries a bare SQL table name
  (`parentTableName` / `terminalTableName` resolve to `Table.tableName()`, no schema prefix) and a
  bare SQL column name (`column().sqlName()`). The forward-edge producer resolves the bare table name
  to the schema-qualified `CatalogFacts` key via `CatalogFacts.resolve(name, Optional.empty())`,
  reusing its existing `Resolved` / `Ambiguous` / `NotFound` arms, so the edge lands on the exact
  `schema.table` ID `catalog.describe` accepts. A column endpoint is the structured
  `ColumnNode(tableNode, sqlColumn)`; its `wireId()` composes `schema.table:column`. The colon is a
  *wire* separator only (SQL identifiers carry no colon, so it round-trips unambiguously, and the
  table half is directly `catalog.describe`-able by splitting on the last colon), but the model never
  stores the joined string. A `McpWire.columnId(...)` helper composes it next to the existing
  `methodRef(...)`; a one-line note in `McpWire` fixes all three grammar conventions in use (`.` for
  table qualification, `#` + `/` for method refs, `:` for column-of-table) so a later slice does not
  invent a fourth.
- **Method (the arity gap).** `FieldClassification` service / condition / computed arms carry
  `methodClassName` + `methodName` but **no arity**, while the method-ref ID is `fqcn#method/arity`.
  The producer reconciles `(class, name)` to arity-bearing refs by looking the pair up in
  `Workspace.catalog().externalReferences()` (the same projection `services` / `conditions` read) and
  emitting one edge per matching overload. A single overload gives one clean edge; multiple overloads
  fan out, and each arity-distinct `MethodNode` keys the reverse index separately. No new arity is
  invented; the edge names exactly the method refs the code tools already emit.

**Input disambiguation (why the tool takes named selectors, not one `node` string).** `Type.field`
(a schema coordinate) and `schema.table` (a catalog table ID) are both a dotted pair, so a single
flat `node` string is ambiguous. The `edges` tool therefore takes named node-selector arguments
(exactly one of `field` / `type` / `table` / `column` (+ `table`) / `method` / `class`); each selector
maps to one `NodeRef` permit. Result endpoints are still the canonical `wireId()` strings (with their
`kind`), so an agent reads a neighbour's ID off one result and re-selects it by kind on the next call.
Argument coercion stays lenient, reusing `McpWire.stringArg`.

## D-B resolved: an `EdgeKind` relationship taxonomy, with direction as a query axis

An edge is a relationship label plus the *other* node, never an untyped adjacency and never a bag of
per-kind nullable endpoint fields:

```
enum EdgeKind { BACKS, TARGETS, REFERENCES, RESOLVES, PARTICIPATES }
record Edge(EdgeKind kind, NodeRef target, List<FieldClassification.FkStep> joinPath) {}
```

- **The endpoint structure lives in `NodeRef`, not in `Edge`.** This is the resolution of the
  sealed-over-enum tension. A naive `EdgeKind` enum next to nullable `targetTable` / `targetColumn` /
  `methodRef` / `participantTypeNames` fields would be exactly the "enum value implies these fields
  are non-null" smell. Collapsing every endpoint into one typed `NodeRef target` removes that bag:
  the varying-shape part (table vs column vs method vs type) is the *node*, and that is where the
  sealed hierarchy sits (`NodeRef`). `EdgeKind` then carries no kind-dependent nullability, so it is
  legitimately an enum (a label), not a sealed hierarchy. `joinPath` is the one relationship-level
  adjunct: the FK hops a `REFERENCES` / `TARGETS` edge traversed, empty for direct edges, reusing the
  `FkStep(targetTableName, fkName)` already on the classifier.
- **Direction is a query axis, not an inverted kind.** The `edges` tool's `direction` (`out` /
  `in` / `both`, default `both`) selects which adjacency to read; the returned `Edge.target` is
  always *the other node relative to the queried node*, and `kind` is the same direction-independent
  relationship. So a forward query on a field returns `BACKS` edges whose `target` is a column; a
  reverse query on that column returns `BACKS` edges whose `target` is the field. The endpoint slot
  never changes meaning with the call direction. This is what makes "what fields break if I touch
  this column" return field coordinates uniformly.
- **The kinds, and the exhaustive-switch drift guard.** The mapping from each classifier arm to its
  edges is an exhaustive `switch` over the `FieldClassification` (and `TypeClassification`) sealed
  permits with **no `default`**, mirroring `SchemaView.mapFieldClassification`. This is the
  validator-mirrors-classifier coverage mechanism applied to edges: a *new* permit fails the edge
  switch to compile (the desired drift guard), and every *existing* permit that produces no edge
  (`Nesting`, `Unclassified`, `SingleRecordIdFromReturning`, `QueryNode`, the `Errors` / `PageInfo`
  structural arms) lands in an explicit no-edge arm, never swept by a `default`. The five kinds and
  their representative source arms:

| `EdgeKind` | meaning | representative `FieldClassification` / source arms |
|---|---|---|
| `BACKS` | field's value comes from this column | `Column`, `CompositeColumn`, `ColumnReference` / `CompositeColumnReference` (terminal column), `ParticipantCrossTable`, the discriminator column on `TableInterface` / `QueryTableInterface` |
| `TARGETS` | field / type binds this table directly | `TableTarget`, `RecordTableTarget`, `QueryTable`, `SingleRecordId`, `DmlMutation`, `DmlRecord`, the `tableBound` arms of `ServiceBacked` / `QueryService` / `MutationService`, plus type-level `TypeClassification.Table` / `Node` and `@node` metadata |
| `REFERENCES` | field / table reaches this table through an FK hop | the `joinPath` of `ColumnReference` / `CompositeColumnReference` / `TableTarget` / `RecordTableTarget`; `CatalogFacts` outgoing / incoming FK (table -> table) |
| `RESOLVES` | field is resolved by this consumer method | `ServiceBacked`, `QueryService`, `MutationService`, `Computed`, `InputUnbound`, `TableMethod`, `QueryTableMethod` |
| `PARTICIPATES` | abstract type's members (forward, stage 1 only) | `TypeClassification.Interface` / `Union` / `TableInterface`, `FieldClassification.Polymorphic` / `QueryPolymorphic` / `TableInterface` / `QueryTableInterface` participant type names |

  The exact arm-to-kind assignment is the implementer's to finalise against the live permit set (the
  `SchemaView` switch is the canonical enumerator); the table is the design intent, the no-`default`
  switch plus the coverage test is the contract.

## D-C resolved: a lazy, snapshot-memoised reverse index, module-local

The reverse index is built lazily on first reverse traversal and memoised against the build it was
derived from, entirely inside `graphitron-mcp`:

- **Lazy, not eager on the build swap.** Building the index on every `setBuildOutput` would do
  reverse-index work on every schema / classpath save whether or not an agent ever traverses, which
  contradicts R118 OQ6 ("materialise reverse edges only for traversals agents actually walk") and the
  "never block the dev loop" posture. Forward edges (stage 1) need no index at all; only the reverse
  direction does, and it builds on first use after a build.
- **No widening of the shared model.** Unlike `CatalogFacts` (R362, an eager build-time projection
  carried on `BuildArtifacts`), the reverse index is a *pure derived function* of projections already
  swapped onto the `Workspace` (`snapshot` for field bindings, `catalogFacts` for FK reverse edges).
  It needs no new `BuildArtifacts` component, no `Workspace` field, no `graphitron`-module change, no
  new dev trigger. It is built where it is read, mirroring how R368's tools are thin live reads.
- **The memo key is the `(snapshot, catalogFacts)` reference pair, not the snapshot alone.** The
  index derives from two distinct `volatile` fields, and `setBuildOutput` writes the four projections
  as separate (non-atomic) volatile writes. Keying the memo on snapshot identity alone would assume
  the `catalogFacts` swap is redundant with the snapshot swap; the two ride different cadences in the
  R118 stability gradient (schema vs classpath), even though one `setBuildOutput` bundles them.
  Keying on the held-reference identity of *both* (a cheap two-field equality check on the cached
  pair) removes the reasoning burden: a new build of either field rebuilds the index. `demoteSnapshot`
  mints a new `Built.Previous` snapshot object, so the freshness demotion is captured by the same
  key.
- **Same-cadence read tolerance, stated not assumed.** The build reads each `volatile` once and
  tolerates the documented benign skew of the non-atomic multi-field swap exactly as R368's
  same-cadence `snapshot` + `catalog().nodeMetadata()` join and `McpWire.writeSnapshotAxes` already
  do. A torn read (a fresh snapshot against a stale `catalogFacts`) self-heals on the next call once
  the second write lands and the memo key misses.
- **What the reverse index covers (and deliberately does not).** It maps the directions agents cannot
  cheaply walk forward: table ID -> binding field coordinates, column ID -> binding field
  coordinates, method ref -> wiring field coordinates, and the inbound FK direction table -> table.
  It does **not** index `PARTICIPATES` (type -> type): that direction is cheaply walkable forward
  through the `schema` tool, so it stays a stage-1 forward-only edge and never enters the index. This
  keeps the index scoped to the impact-analysis question the Backlog names.

## Ownership boundary and the cross-module coupling to name

The whole slice is module-local to `graphitron-mcp`: the `edges` tool, `NodeRef`, `EdgeKind`, the
edge producer, and the reverse index. But the edge switch takes a hard *exhaustiveness* dependency on
`FieldClassification` / `TypeClassification`, which live in the `graphitron` module and are sized to
LSP hover-payload shapes, not to edges. The implementer should expect, not be surprised by, two
consequences:

- **A new classifier permit breaks the `graphitron-mcp` build.** That is the drift guard working: the
  edge switch is a *third* projection of the classifier (after the generator's own dispatch and
  `SchemaView`'s hover projection), and the no-`default` switch forces an edge decision for any new
  arm at compile time, across the module boundary.
- **Collapsed distinctions must not be re-invented at the edge layer.** `FieldClassification` already
  discards some generator-side distinctions (which side of a join holds the FK, split-batched vs
  inline). Edges carry only what the classifier still holds; the edge producer must not reach past the
  projection to recover a distinction the projection dropped. The `TableTarget` (carries `joinPath`)
  vs `Column` / `QueryTable` (no path) split is exactly the classifier distinction the
  `TARGETS` / `REFERENCES` split should fall out of, rather than being re-derived.

## The `edges` tool wire shape

```
edges(
  // exactly one node selector:
  field?:  "Type.field"            type?: "Type"
  table?:  "schema.table"          column?: "col"   (column requires table)
  method?: "fqcn#method/arity"     class?:  "fqcn"
  direction?: "out" | "in" | "both"   // default "both"
)
-> {
  node:  { id, kind },                      // the resolved queried node (echoes reconciliation)
  edges: [ { kind, direction, target: { id, kind }, joinPath?: [ { targetTableName, fkName } ] } ],
  // degraded arms, never a hard failure:
  resolution?: "ambiguous" | "notFound",    // when a bare table name resolves to 0 / >1 catalog tables
  schemas?: [ ... ],                         // ambiguous candidates, mirroring catalog.describe
  snapshotAvailability, snapshotFreshness    // via McpWire.writeSnapshotAxes, so a reader knows currency
}
```

The ambiguous / not-found arms reuse `CatalogFacts.resolve`'s sub-taxonomy and the
`catalog.describe` precedent, so an agent that passes a bare table name two schemas carry gets the
candidate schemas back to re-call qualified, never a silent empty edge list.

## Implementation

All new code under `graphitron-mcp/src/main/java/no/sikt/graphitron/mcp/edges/` (a new sub-package
keeping the edge model out of the flat tool package):

- **`NodeRef.java` (new).** The sealed node-ID model of D-D (`TypeNode` / `FieldNode` / `TableNode` /
  `ColumnNode` / `MethodNode` / `ClassNode`), each composing its `wireId()`.
- **`Edge.java` + `EdgeKind.java` (new).** The relationship record and label enum of D-B.
- **`EdgeProducer.java` (new).** Computes forward edges for one `NodeRef` from the live projections:
  the no-`default` switch over `FieldClassification` / `TypeClassification` for field / type nodes;
  `CatalogFacts` FK reads for table nodes; the `(class, name)` -> overload reconciliation against
  `externalReferences()` for method nodes. Owns the bare-name -> `CatalogFacts.resolve` reconciliation.
- **`ReverseEdgeIndex.java` (new).** The lazily-built, `(snapshot, catalogFacts)`-memoised reverse
  index of D-C (table / column / method ID -> binding field coordinates, plus inbound FK), built by
  inverting the same per-field switch the forward producer uses so the two cannot disagree.
- **`McpWire` (widen, additive).** A `columnId(...)` composer beside `methodRef(...)`, and the
  one-line grammar-convention note fixing `.` / `#//` / `:`.
- **`GraphitronMcpServer` (widen, additive).** Register one `edgesTool(workspace)` mirroring the
  `schemaTool` / `catalogTablesTool` shape (live reads off the `Workspace` handle on every call); no
  capability change (the `tools` capability is already declared). The reverse index is held as one
  field constructed lazily on first `in` / `both` call.

## Tests

Per the tiers in `rewrite-design-principles.adoc`, all at the MCP-handler tier in
`GraphitronMcpServerTest` (structured-content assertions, no code-string assertions), plus one unit
coverage pin:

- **Forward edges.** Over the real Sakila fixture: a `Column` field yields a `BACKS` edge whose
  `target` is the `schema.table:column` ID `catalog.describe` accepts; a `ColumnReference` field
  yields `BACKS` + `REFERENCES` with the `joinPath` populated; a `ServiceBacked` field yields a
  `RESOLVES` edge whose `target` method ref matches the `services` / `conditions` tool's emitted ref
  exactly (the round-trip the `methodRefIdsMatchTheSourceIndexJoinKeys` test already pins for the code
  tools); a `@node` type yields a `TARGETS` edge to its table.
- **Reverse edges (the deliverable).** A reverse (`in`) query on a column ID returns the field
  coordinates that bind it (`target.kind == "field"`, `kind == BACKS`); a reverse query on a method
  ref returns the wiring field coordinates; a reverse query on a table returns both its binding fields
  and its inbound-FK table neighbours. Asserts the endpoint slot holds the *field*, not the queried
  column (the direction-as-query-axis contract).
- **Reconciliation arms.** A bare table name two schemas carry returns the `ambiguous` arm with
  candidate schemas; an unknown name returns `notFound`; a method name with two overloads fans out to
  two arity-distinct `RESOLVES` edges.
- **Freshness.** `edges` before the first build reports `snapshotAvailability: Unavailable` with an
  empty edge list; a `setBuildOutput`-driven rebuild is observed on the next reverse query (the memo
  key misses and the index rebuilds).
- **Coverage pin (unit tier).** A test asserting every `FieldClassification` (and
  `TypeClassification`) permit maps to a known edge-or-no-edge decision, the analogue of
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`. This is the live test that
  makes the "drift guard" claim real rather than a false invariant; without it the no-`default` switch
  is the only guard and a reviewer cannot see the no-edge arms were chosen deliberately.

## What this slice does *not* change

No classification, no generator branch, no validator arm: the edge model is a read-only third
projection of the existing classifier, not a pipeline change. **Validator-mirrors-classifier applies
in its coverage sense** (the no-`default` switch + the coverage pin), but introduces no new
`Rejection` and no validate-time arm, because edges are descriptive discovery reads, not a new
authoring constraint, the same posture R368 took for its structured reads. The R362 / R368 wire
shapes and the existing eight tools are untouched (D-A). Named explicitly so a reviewer does not flag
a missing validator arm or a retrofitted result field.

## Out of scope

- **The `neighborhood` subgraph tool (stage 3).** Deferred per R118 OQ6 until round-trip count proves
  painful; the dedicated-tool choice (D-A) is what makes that signal measurable.
- **Indexing the `PARTICIPATES` (type -> type) direction**, cheaply walkable forward via `schema`.
- **Any new classification, validator arm, or generator branch.**
- **The semantic / RAG track** (R372 slice 8 and its consumers); edges are structured, not semantic,
  and need no embedder or store.

## Builds on

- **R362** (Done): catalog tools; the `CatalogFacts` table / column / FK shape, the schema-qualified
  key the catalog-side edges land on, and its `resolve` sub-taxonomy the reconciliation reuses.
- **R368** (Done): structured read-tools; the `McpWire` schema-coordinate and method-ref grammar the
  schema- and code-side edges traverse, the `externalReferences()` projection the method-arity
  reconciliation reads, and the same-cadence read posture D-C reuses.
- **R361** (Done): the shared-model seam and the `Workspace` `volatile` projections the index derives
  from, plus the non-atomic multi-field-swap story D-C tolerates.

All landed on trunk, so *Builds on*, not `depends-on`.

## Related

- **R118** (`graphitron-mcp-server.md`, Backlog programme): slice 7, the traversal layer over the
  structured tools.
- Independent of the RAG track (**R372** slice 8 and its consumers); edges are structured, not
  semantic.
