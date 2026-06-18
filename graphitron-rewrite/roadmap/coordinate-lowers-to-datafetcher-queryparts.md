---
id: R333
title: "Lower each schema coordinate to a DataFetcher and its QueryParts"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
depends-on: [source-operation-target-pivot]
created: 2026-06-18
last-updated: 2026-06-18
---

# Lower each schema coordinate to a DataFetcher and its QueryParts

The shipped field model (R290/R299/R305/R316) maintains a hidden 1:1 assumption: **one schema
coordinate == one graphitron field** (one `OutputField` leaf). That assumption is the root cause of the
leaf cross-product (`Split` x `Lookup` x `Record` x `Composite` x ...): whenever a single SDL field needs
to contribute more than one thing to the emit, the multiplicity gets welded into a monolithic leaf as a
repeating group or an extra duty. The pivot is to drop the 1:1 and say:

> **A schema coordinate lowers to exactly one `DataFetcher`, and (if table-bound) one or more
> `QueryPart`s.**

That is two rules over two concrete types instead of a leaf zoo, and it is what makes the R314
dissolution fall out structurally rather than leaf by leaf.

This spec is the **model**; consuming it (re-platforming the generator onto the lowering) is R314's
emit re-platforming. It was discovered while researching whether `SplitTableField` should dissolve into
`RecordTableField` (see Lineage); the answer reframed the whole field model.

**Refined in a 2026-06-18 design session** (see "Refinement: the SQL emit target is a method-call-graph"
below). The headline above names the SQL-side unit one level too fine. Read "QueryPart" throughout the
foundational sections as *the method that emits QueryParts*: the emit target is a referentially-closed
graph of emitted Java methods, and the `org.jooq.QueryPart` is the per-request runtime value those methods
produce. The foundational framing here (normalization, natural keys, anchors) survives the sharpening
intact; only the SQL-side codegen unit moves.

## The two emit targets

Graphitron's job is to bridge GraphQL-Java and jOOQ, so the two emit-target types are the two libraries'
own:

- **`graphql.schema.DataFetcher`** (the resolve side). **Every** schema coordinate emits exactly one: an
  SDL field has exactly one resolver. Total.
- **`org.jooq.QueryPart`** (the SQL side). **Only table-bound** coordinates emit these, and **one or
  more**: a projected `Field`, a join, a condition, a key projection for a DataLoader, a DML clause. A
  jOOQ `QueryPart` is the base type of every SQL fragment, so the SQL contributions already share a real
  supertype. (Refined below: the codegen unit is the *method* that emits these at runtime, not the
  per-request `QueryPart` itself.)

The "graphitron field" (`OutputField` leaf) conflated the *identity* of an SDL field with its *emit*.
The lowering separates them: the schema coordinate is the identity, the DataFetcher + QueryParts are the
emit.

## Normalization: the leaf zoo is a denormalized view

The leaf model is denormalized in textbook ways, and the pivot is its normalization:

- `CompositeColumnField` / `CompositeColumnReferenceField` carry an arity-`N` `columns` list, a
  **repeating group** (a 1NF violation). Normalizing to atomic rows yields `N` single-column QueryParts
  under one coordinate. This is why composite "simplifies immensely": arity stops being a leaf dimension
  and becomes the count of projected QueryParts.
- The split leaf welds on the parent-key projection, a fact that **functionally depends on the parent's
  query, not on the child coordinate** (a 3NF-style transitive dependency). Normalizing moves that
  QueryPart to the query it depends on.

### Two levels of natural key

The normalized relations are joined on two key levels, and naming both is the commonality that ties the
model together:

- **The model's natural key is the schema coordinate** `(parentType, fieldName)`: the glue that says
  "this DataFetcher and these QueryParts are one SDL field." R316's leaf-reconstruction table already
  files `parentTypeName / name / location` as "field identity, the envelope, not a dimension"; this pivot
  makes that seam structural.
- **The data's natural keys (columns, PK, FK) are the join graph.** A column identity links a projecting
  QueryPart to the DataFetcher that reads it back; a PK/FK correlation links a split child's QueryParts to
  the parent query; a re-fetch's PK self-correlation (`source.pk = target.pk`) is the degenerate case of
  that same join. Composite's `N` columns, split's FK, and re-fetch's PK are therefore the **same thing**,
  database natural keys doing the linking, which is why those three leaves felt like one disease.

So graphitron is the lowering of a set of schema coordinates into a DataFetcher relation and a QueryPart
relation, normalized, joined on the schema coordinate (model key) and on the database's own keys (the
query graph). The leaf zoo is the fully-denormalized materialized view of that.

## How `(source, operation, target)` survives the normalization

R316's triple is common vocabulary that carries well; it is a per-coordinate **summary row**, a
denormalized join of the two entities' attributes. Normalization assigns each axis to the entity that
owns it (nothing in R316 is wrong, it just gets distributed):

| R316 axis | Owning entity | Notes |
|---|---|---|
| `source` (Root / OnlyChild / Child) | DataFetcher | invocation cardinality; `Child` dispatches through a DataLoader |
| `target` wrapper (Single / List) | DataFetcher | output cardinality |
| `target` shape `Column` / `Table` | QueryPart | projection kind (a jOOQ `Field`, a table projection) |
| `target` shape `Record` / `Field` | DataFetcher only | read off a Java object, no QueryPart |
| `operation` `Fetch` / `Paginate` / `Lookup` / `Count` / `Facet` / DML | QueryPart | the SQL verb |
| `operation` `ServiceCall` / `Nest` | DataFetcher only | no SQL |
| composite arity | QueryPart | the number of column QueryParts |
| split / re-fetch / new-query | QueryPart | which query a QueryPart is addressed to |

The corpus directive sits on the schema coordinate (the natural key), so it already annotates the right
thing; its verdict generalizes from one triple to the coordinate's full DataFetcher + QueryPart
decomposition. R316 slices 1-4 are the denormalized, singleton-row view (one coordinate, one DataFetcher,
at most one QueryPart's worth of facts) and stay valid as that projection.

## Query anchors and the two flows

A **query anchor** is a coordinate whose DataFetcher launches a SELECT: a root field, or a
split/new-query field. A query scope's content is "the QueryParts addressed to it." Every QueryPart
carries an **address**: the anchor whose SELECT it lands in. With that, the two cross-field flows from
R316's wrapper algebra become statements about QueryParts and anchors:

- **Cardinality flows down** (transitive): a coordinate's `source.wrapper` is the fold of its ancestors'
  `target.wrapper` (R316's wrapper algebra, unchanged). This governs the DataFetcher's arrival.
- **Key projection flows up** (per-anchor): a new-query coordinate's correlation key is a QueryPart
  addressed to its enclosing anchor's SELECT. This is the parent-key injection, no longer a bespoke
  emit-time relation but a QueryPart with an address.

The address unifies composite and split: composite's column QueryParts are addressed to the coordinate's
**own** anchor (same scope), split's key projection is addressed to an **ancestor** anchor. `address in
{self, enclosing anchor}` covers both.

## What dissolves

- **Composite columns**: one coordinate, `N` column QueryParts. `CompositeColumnField` /
  `CompositeColumnReferenceField` and the arity-as-leaf-property retire.
- **`SplitTableField` vs `RecordTableField`** (the lineage trigger): both project the **same**
  keyed-re-query QueryPart. `SplitTableField` additionally projects a key-projection QueryPart addressed
  to the parent anchor (its enclosing scope is a graphitron-generated SELECT it can impose on);
  `RecordTableField` does not (its enclosing scope is a produced record, the key already rides it). They
  stop being distinct leaves and become the same emit units composed differently. NB: this confirms,
  rather than overturns, R316's `SourceShape.Table` for `SplitTableField`: its source is a live catalog
  row; the kinship with `RecordTableField` is at the keyed-re-query QueryPart, not the source shape.
- **The leaf cross-product**: every "multiplicity-as-a-leaf-variant" modifier becomes QueryPart
  multiplicity (composite), addressing (split / re-fetch), or shape, not a leaf type. `Bulk` was never a
  leaf variant in the first place, it was already the `target` `List` wrapper, which is the tell that
  this is the right cut.

## Refinement: the SQL emit target is a method-call-graph

The "one DataFetcher + one or more QueryParts" headline is the right instinct but names the SQL unit one
level too fine. A 2026-06-18 session walking the actual emitters sharpened it: **the codegen command is
the emitted Java method, and the full emit target is a referentially-closed graph of those methods.** The
threads below record the chain; each is a claim grounded in a current emitter, with the code coordinate
that pins it.

**A. `SplitTableField` and `RecordTableField` are component-identical (measured).** Both records carry
the same eleven components (`parentTypeName, name, location, returnType, joinPath, filters, orderBy,
pagination, sourceKey, loaderRegistration, parentCorrelation`) and both `implements TableTargetField,
BatchKeyField` (`ChildField.java:446` and `:798`). The only divergence is two derived methods:
`emitsSingleRecordPerKey()` (Record adds the `|| dispatch == LOAD_MANY` disjunct) and `sourceShape()`
(Split to `Table`, Record to `Record`, the switch at `ChildField.java:66` vs `:79`). Nothing in the
*data* distinguishes them; the distinction is which methods consume the leaf and what extra it owes.

**B. Functional core / imperative shell; "commands" are the addressed output, not a third concept.** R333
already gives every QueryPart an *address* (the anchor it lands in). That address is the imperative-shell
instruction. Naming the lowered units "commands" adds nothing new to the model; it fixes the boundary:
the core decides the entire emit, the shell renders and never assembles. The law is **commands must be
complete**: the shell makes no decision the core could have made.

**C. The two targets sit at different granularities; the field is right for only one.** The `DataFetcher`
is field-granular: one coordinate, one resolver, 1:1, total. `FetcherEmitter` binds exactly that. The
field model's 1:1 is correct here and stays. The SQL side is not field-granular, and (thread D) is not a
query either.

**D. There is no complete query at codegen.** `TypeClassGenerator` emits one `$fields(sel, table, env)`
method per table-bound type that assembles the SELECT list from a `DataFetchingFieldSelectionSet` at
*runtime*. The projected columns are a per-request value gated by the client's selection set. So the
SQL-side command is not a static SELECT, and it is not an `org.jooq.QueryPart`: a QueryPart is the
per-request runtime value those methods produce. This corrects "The two emit targets" above: the SQL-side
codegen target is **the method that emits QueryParts**, not the QueryPart.

**E. The command granularity is the emitted method.** Reading the output bottom-up, the natural command
unit is the Java method we emit, because a `$fields` *arm* is not independently renderable (it needs the
method scaffold, the switch, the recursion). The minimal renderable unit is the method. The
method-command kinds and their granularities:

| Emitted method | Granularity | Owner |
|---|---|---|
| `<Type>Fetchers.<field>(env)` | field (1:1) | the coordinate |
| `<Type>.$fields(sel, table, env)` | table-bound type (a fold) | the type |
| `lookup<X>` / `load<X>` rows-method | anchor (the SELECT launcher) | the query-launching coordinate |
| `<field>Condition(...)` | field / method | the condition coordinate |
| `createBean` / `createRecord` / `<field>OrderBy` / `<field>InputRows` / `scatterByIdx` | dedup-by-class or per-field helper | the boundary it serves |

`$fields` is type-granular and a *fold*: it absorbs its own scalar and inline fields, recurses through
every `NestingField` into the nested type's fields in the same method (`TypeClassGenerator` NestingField
arm at `:301-303`; nested types get no own class, `:146`), and opts in the `SourceKey` columns that Split
children need projected into this parent SELECT (`collectRequiredProjectionColumns`, `:341`). Granularity
is heterogeneous across command kinds, and that is the point: each command sits at the granularity of the
method it renders. The leaf zoo is what you get from forcing a single field-granular model to source all
of these kinds at once.

**F. Completeness is a graph property, because methods call each other by name.** `$fields` is not
compile-complete in isolation: an inline table-field arm inside `X.$fields` emits `Y.$fields(...)` in its
multiset projection (`InlineTableFieldEmitter:123`); the split rows-methods, the polymorphic path, and the
lookup path all call `<Type>.$fields(...)`; and self-referential types make the graph cyclic (depth-2
self-reference, per `InlineTableFieldEmitter`'s javadoc). So completeness splits in two:

- *Per node:* each method command renders a complete body, no intra-method assembly left to the shell.
- *Per set:* the command set is **closed under reference**. Every method-name a body emits (`Y.$fields`,
  `load<X>`, `scatterByIdx`, a decode helper) resolves to another command in the set, and the core
  assigned that name on both ends. The shell renders nodes; `javac` stitches the edges because the names
  are already fixed.

"Making the code hang together" is exactly the edge-and-name computation, today scattered across the
emitters as naming convention (`rowsMethodName()`, the hardcoded `<NestedType>.$fields`, `scatterByIdx`
emitted once per class). The cut lifts the whole call graph (nodes, edges, and the naming scheme) into the
core; the shell stops knowing any naming convention.

**G. Two seams, do not conflate.** The *static* call graph must be closed: that is compile-time
completeness, the superset of every edge that could fire. The *selection set* prunes which edges actually
fire per request: that is the runtime subgraph, client data, legitimately dynamic. The core owns the
first entirely; the second stays where it is.

**H. Normalization, restated for the graph.** The emit target is two relations: a **node relation**
(method commands keyed by method name) and an **edge relation** (calls, as name references). Closure under
reference is referential integrity on the edge relation. Two keys bracket the function the core *is*: the
input key is the schema coordinate `(parentType, fieldName)` (the model key, unchanged from above); the
output key is the **method name** in the emitted graph. The core is the map from input key to a
referentially-closed `(nodes, edges)` relation. The leaf zoo, the per-field QueryParts, and the
emitter-computed edges are all denormalized or smeared views of that one relation.

**I. Falsifiable invariant (the test this earns).** Bidirectional, in the spirit of
`GeneratorCoverageTest`: every method graphitron emits is the render output of exactly one command, *and*
every method-name reference in every emitted body resolves to a command the core committed to, with no
emitter minting a callee name. If an emitter ever computes a callee name the core did not hand it, the cut
has leaked. This is the test that *proves* "the shell assembles nothing" rather than asserting it.

### Emitter inventory (grounding for E and F)

The twenty `*Emitter` classes divide by what they emit:

- *Resolve side (DataFetcher), field-granular or finer:* `FetcherEmitter` (one field to one DataFetcher),
  `DataLoaderFetcherEmitter` (one DataLoader-backed DataFetcher method), `ServiceMethodCallEmitter` /
  `ChannelCatchArmEmitter` / `ChannelEarlyReturnEmitter` (fragments inside a fetcher body),
  `InputBeanInstantiationEmitter` / `JooqRecordInstantiationEmitter` (boundary helpers).
- *SQL projection arms (pieces of `$fields`):* `InlineColumnReferenceFieldEmitter`,
  `InlineTableFieldEmitter`, `InlineLookupTableFieldEmitter`.
- *SQL sub-SELECT fragments (shared):* `JoinPathEmitter`, `ArgCallEmitter`, `LookupValuesJoinEmitter`.
- *SQL anchors (launch a SELECT, call `$fields`):* `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter`
  (plus the root and lookup paths in `TypeFetcherGenerator` and `SelectMethodBody`).
- *Schema side (SDL and wiring):* `AppliedDirectiveEmitter`, `DirectiveDefinitionEmitter`,
  `FetcherRegistrationsEmitter`, `GraphQLValueEmitter`, `SchemaSdlEmitter`.

The projection-arm emitters are pieces of one node (`$fields`); the anchor emitters are nodes with
outbound `$fields` edges. That split is the evidence for E and F.

### First slice (the beachhead)

`SplitTableField` / `RecordTableField` is the cheapest honest demonstration of the cut. Both child sides
lower to the same `load<X>` rows-method and the same fetcher; Split's only extra is the key projection,
which relocates to the parent type's `$fields` (where `collectRequiredProjectionColumns` already puts it).
Collapsing the two with zero residue, gated on `sourceShape`, retires one cross-product axis with no
generator rewrite and produces the lowering's first executable proof. It is the smallest instance of
cross-anchor key relocation, so it exercises the address-as-name-resolution machinery on exactly one
contribution.

## Relationships

- **R316** (source-operation-target-pivot): the triple this normalizes. R316's leaf-reconstruction table
  already separated field identity (the schema coordinate) from the dimensional content; this makes that
  seam structural and reframes `leafReconstructsFromCoordinate` as "lower the coordinate to its
  DataFetcher + QueryParts" (the leaf zoo being the denormalized form). R316 stays the stepping stone;
  this does not reopen its slices.
- **R314** (dissolve-reentry-leaves-dimensional-emit): this is the structural enabler. R314's dissolution
  becomes "lower every coordinate to its DataFetcher + QueryParts" rather than leaf-by-leaf surgery. This
  spec likely reframes R314's plan or feeds it directly; sequence to be decided.

## Open questions (to settle before / during Ready)

- **Node-relation granularity** (the open fork from the session): is the node "one command per emitted
  method", or something coarser that groups a method family under one command? Settle before sizing the
  lowering, because it decides what the core enumerates.
- **Edge inventory and naming authority** (the next read-only step): enumerate the distinct by-name call
  patterns in the current output (`$fields` to `$fields`, fetcher to rows-method, rows-method to
  `scatterByIdx`, `$fields` to decode-helper, ...) and, for each, find where the callee name is minted
  today. That measures how much naming authority has to move into the core for thread F's closure to hold.
- **Anchor addressing depth**: does a QueryPart's address name the enclosing anchor coordinate directly,
  and is the up-projection one-hop (immediate parent) or nearest-query-owning-ancestor with inline
  ancestors transparent (a split grandchild under an inline child threading its key to the grandparent's
  SELECT)? **Partly resolved by threads F/H:** addressing is core-side name resolution, and the
  parent-key projection is already implemented as "opt these columns into the parent type's `$fields`"
  (`collectRequiredProjectionColumns`, `TypeClassGenerator:341`). The open residue is the
  grandchild-through-inline-ancestor threading, not the primitive.
- **Re-query unification**: do `SplitTableField`'s and `RecordTableField`'s keyed-re-query QueryParts
  fully merge into one primitive (the child reads a *laundered* key, decoupled from parent backing), or
  share a primitive while keeping distinct source shapes (the child reads the raw parent)? Picking this
  decides whether the two collapse to one emit unit or two that share machinery.
- **DataFetcher totality vs synthetic nodes**: every coordinate has exactly one DataFetcher (an SDL field
  has one resolver), so there is no "synthetic DataFetcher". Confirm there is likewise no synthetic
  *coordinate*: the synthetic key projection is a QueryPart, owned by the splitting coordinate and
  addressed elsewhere, never a fabricated SDL field.
- **Corpus assertion shape**: the `@classified` verdict generalizes from one triple to the
  `(DataFetcher, QueryPart*)` decomposition. Settle how the directive expresses a set (per-QueryPart
  rows, or a value triple plus addressed supporting rows) before touching the R281 corpus again.

## Scope

In scope: the model (the lowering to a referentially-closed method-call-graph, the normalization, the
natural keys, the anchor/address primitive, the node and edge relations). Out of scope: the emit
re-platforming that consumes it (R314), and any rewrite of R316 slices 1-4 (they are the valid
denormalized projection). No code in this item beyond what is needed to make the model executable as
tests, if that is split out at Ready: the lowering function and its coverage, plus thread I's bidirectional
graph-closure invariant (every emitted method is one command's output; every callee name resolves to a
committed command).

## Lineage

Surfaced 2026-06-18 while researching a claim that `SplitTableField` is a variant of `RecordTableField`
that should dissolve. The emit trace refuted the literal "source is a record" reading (split reads a live
catalog row) but confirmed the structural kinship (identical record components, shared batch-load
machinery). Pressing on "what is the real difference" produced the double-duty observation (split also
injects keys into the parent SELECT), then the parent-projection-as-up-flow relation, then the insight
that reifying it as a separate node dissolves the relation, and finally the normalization framing: the
node is a `QueryPart`, the SDL field is the natural key, and the leaf zoo is a denormalized view. The
chain is preserved in the R316 design discussion of the same date.
