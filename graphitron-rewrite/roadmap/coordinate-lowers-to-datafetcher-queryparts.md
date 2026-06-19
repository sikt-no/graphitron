---
id: R333
title: "Lower each schema coordinate to a DataFetcher and its QueryParts"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
created: 2026-06-18
last-updated: 2026-06-19
---

# Lower each schema coordinate to a DataFetcher and its QueryParts

The shipped field model (R290/R299/R305/R316) carries a hidden 1:1 assumption: **one schema coordinate
== one graphitron field** (one `OutputField` leaf). That assumption is the root cause of the leaf
cross-product (`Split` x `Lookup` x `Record` x `Composite` x ...): whenever a single SDL field has to
contribute more than one thing to the emit, the multiplicity gets welded into a monolithic leaf as a
repeating group or an extra duty. The pivot drops the 1:1, but the unit it drops *to* is the heart of
this spec, and it is neither the field nor either library type. It is the **emitted Java method**:

> **A schema coordinate is the input key to a lowering whose output is a referentially-closed graph of
> emitted Java methods.** Each node is a method we generate; each edge is one method calling another by
> name. The leaf zoo, the per-field "graphitron field", and the two library types (`DataFetcher`,
> `QueryPart`) are all denormalized or partial views of that one graph.

This spec is the **model**; consuming it (re-platforming the generator onto the lowering) is R314's emit
re-platforming. The graph framing was reached by walking the actual emitters; the **Discovery** section
below records that chain, each step pinned to a current emitter with line numbers. It superseded an
earlier, narrower headline ("a coordinate lowers to one `DataFetcher` plus one or more `org.jooq.QueryPart`s")
that named the SQL-side unit a level too fine. That earlier framing is kept in Discovery as the path in,
not as the model.

## The method call graph is the granularity

The reason the field is the wrong unit is **granularity**: the methods we emit do not all sit at field
granularity, so no single per-field model can source them. The method graph is the model precisely
because it lets each node sit at its own granularity. Reading the current output bottom-up:

- **Field-granular (1:1).** `<Type>Fetchers.<field>(env)`: one coordinate, one `DataFetcher`, total.
  `FetcherEmitter` binds exactly this. The resolve side genuinely *is* field-granular, so the R316 field
  model is correct here and stays.
- **Argument/input-granular (finer than, and driven by something other than, the field).** A condition
  method is the clean example. `TypeConditionsGenerator` emits one `<Type>Conditions` class per type, with
  one method per `GeneratedConditionFilter`; each is a pure function of the field's *typed argument
  values*, returning a jOOQ `Condition`, its body shape (`eq` / `in` / `row(...).eq` for composite) driven
  by the input surface, not by the field. One coordinate mints a method whose identity and body are a
  function of the inputs the field merely carries. No per-field model can express that; the method can.
- **Type-granular (a fold).** `<Type>.$fields(sel, table, env)`: one method per table-bound type that
  folds in its own scalar/inline fields, recurses through nested types in the same method, and opts in the
  columns Split children need projected. Many coordinates, one method.
- **Anchor-granular.** `lookup<X>` / `load<X>` rows-methods: one per SELECT-launching coordinate.
- **Dedup-by-class / boundary helpers.** `createBean` / `createRecord` / `scatterByIdx` / `<field>OrderBy`
  / `<field>InputRows`: emitted once per class, or per boundary they serve.

Granularity is heterogeneous *on purpose*; that heterogeneity is the content of the model. The leaf zoo
is what you get from forcing one field-granular model to source all of these kinds at once. (Full table
with code coordinates: Discovery thread E.)

## The two library types are node kinds, not the top level

Graphitron bridges GraphQL-Java and jOOQ, and the two libraries' own types still name the two sides of the
graph, but as **node-kind attributes**, not as the top-level structure:

- **`graphql.schema.DataFetcher`** is the resolve-side node kind, and it is the field-granular one above:
  every coordinate emits exactly one, total. The "graphitron field" identity lives here, and only here.
- **`org.jooq.QueryPart`** is *not* an emit target at codegen. The SQL projection is assembled at runtime
  from the client's `DataFetchingFieldSelectionSet` inside `$fields`; the `QueryPart` is the per-request
  value those methods produce, not a thing we generate. The SQL-side node kinds are the **methods that
  emit QueryParts** (`$fields`, the rows-methods, the condition methods), at the granularities above.

So the model is one graph; the resolve/SQL split and the two library types are how its nodes are *typed*,
not two parallel emit targets. (Why the `QueryPart` is runtime-only, with the emitter evidence: Discovery
thread D.)

## Normalization: the leaf zoo is a denormalized view

(The sections from here through "What dissolves" predate the method-graph sharpening and still say
"QueryPart" for the SQL-side unit. Read it as shorthand for *the method that emits that QueryPart at
runtime*, per the node-kinds section above; the normalization, natural-key, and anchor arguments are
unchanged by the sharpening. Folding the wording through is part of the systematic pass.)

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

## Discovery: walking the emitters to the method-call-graph

This section is the derivation of the lead model above, not a refinement bolted onto a different one. The
chain began from the narrower "one DataFetcher + one or more QueryParts" instinct, which is right about the
resolve side but names the SQL unit one level too fine. A 2026-06-18 session walking the actual emitters
sharpened it to the model this spec now leads with: **the codegen command is the emitted Java method, and
the full emit target is a referentially-closed graph of those methods.** The threads below record the
chain; each is a claim grounded in a current emitter, with the code coordinate that pins it.

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

**J. Naming authority is a measured spectrum, and both ends already exist in-tree.** A 2026-06-19 trace
of every emitted call edge sorts them by *where the callee name is derived*, which is what thread F's
closure turns on:

- **Regime 1, model-carried** (one derivation locus; both ends read it). The fetcher to rows-method edge
  reads `BatchKeyField.rowsMethodName()` (`model/BatchKeyField.java:42`, whose javadoc states the contract
  outright: "the fetcher and the rows method agree on this name"); the `$fields` to join/condition edges
  read `MethodRef.methodName()` off a `{className, methodName}` model value (`JoinPathEmitter`); the
  type-condition reads `GeneratedConditionField.methodName()`. This is exactly thread F's "core owns the
  name on both ends," already shipped for these edges. `MethodRef` is the decoupling primitive: a call site
  reads the name blind, knowing neither the producer nor the derivation.
- **Regime 2, formula-reconstructed** (the string retyped at each end, no shared locus). `$fields` is a
  literal at the definer (`TypeClassGenerator.java:216`) and a `$$fields` template literal independently
  retyped at roughly eight call sites (`SelectMethodBody:112`, `InlineTableFieldEmitter:123`,
  `TypeFetcherGenerator:753,765`, `SplitRowsMethodEmitter` in five places); `scatterByIdx` /
  `scatterSingleByIdx` (literal at definer plus three calls); `<Type>Fetchers`, `<field>OrderBy`,
  `<field>InputRows`, `create<Bean>` / `create<Record>` / `decode<Record>` (prefix/suffix formula at both
  ends).
- **The half-migrated seam.** `<field>Condition` is read from the model in `TypeConditionsGenerator`
  (R1 end) but recomputed as `fieldName + "Condition"` in `QueryConditionsGenerator` (R2 end). One name, two
  loci, one of which is the model: the migration is per-edge, and this is what a half-done edge looks like.

The R2 set is the worklist for thread F's closure; the cut is "make every edge look like `MethodRef` /
`rowsMethodName`, none like `$fields`." This makes thread I's invariant grep-able: every `$$fields`,
`scatterByIdx`, `+ "Condition"`, `+ "OrderBy"`, `+ "Fetchers"` outside a single mint point is a current
violation, so the test starts red and the migration drives it green edge by edge.

**K. Seams, not the current emitters, define the target.** The emitter inventory below, and any pair table
read off it, describe the *current* seam topology, which inlines heavily and is therefore not the
destination. A **seam** is a named method call: the one place an edge (and a regime-1 name) can exist.
Inlining is the absence of a seam, producer and consumer welded into one body. So "add a seam," "promote an
inlined fragment to a core-minted node + edge," and "make a new pair possible" are one statement; the seam
topology *is* the node/edge relation of thread H, and designing it is the content of the lowering.

The current resolve side is asymmetric. The child path factors its query into a named unit (child fetcher
to DataLoader to `rows<X>`, the rows-method being the `select` / `from` / `where` / `orderBy` / `$fields`
assembly as a named method). The root path inlines that same assembly into the fetcher body
(`SelectMethodBody`), with no `rows<X>`-equivalent to call. Root and child build the same query two ways;
only child names it. **The decided target** (2026-06-19) closes that seam: both fetcher kinds become thin
entry points delegating to one shared query unit, differing only in invocation strategy (root calls it
directly; child calls it batched through a loader plus scatter). This generalizes the `SplitTableField` =
`RecordTableField` shared-rows-method (the one existing instance of reuse-via-seam) into the organizing
principle. Service-backed is the parallel arm: the fetcher delegates to a named service-call unit instead
of inlining `service.method(...)`. The root path gains a level of indirection not required by runtime (no
batching to justify it); paying it to buy uniformity, testability, and reuse is a deliberate, accepted
trade.

Target topology, uniform across root / child / service:

- **DataFetcher** (thin entry; picks a strategy) delegates across a seam to either
  - the **Query unit** (the SELECT launcher; today's rows-method, generalized), invoked *directly* (root)
    or *batched through a DataLoader plus scatter* (child); or
  - a **service-call unit** (the service-backed arm).
- The **Query unit** composes across further seams into the **query-part units**: Projection (`$fields`),
  Join, Condition, OrderBy, and so on.

**Seam-placement rule.** A seam belongs wherever a unit is (a) chosen by a runtime strategy/dispatch,
(b) reused across more than one caller, or (c) something we want to assert independently in the corpus or
tests. Inline only a linear, single-use, non-varying construction. On the jOOQ side, where jOOQ's own
in-language composition means a `QueryPart` can be an inline expression or a named method, we take the
**looser reading** of (c): seam wherever the corpus might want to assert, accepting the parameter-threading
cost (`env`, `dsl`, `table`, selection set across each seam), rather than reserving assertion for the
query-unit level. The brake against one-method-per-`QueryPart` is that (a)/(b)/(c) must each be a real,
named reason; "it is an expression" is not one. Testability is the through-line: an inlined fragment is
assertable only through the whole query that contains it, a named query-part unit is independently
assertable and is a clean regime-1 edge by construction, so "more seams," "more testable," and "more
decoupled pairs" are the same axis.

### Current seam topology (migration baseline)

The pairs below are the whole-method nodes the current generator already cuts; they are the baseline the
target seam topology is migrated *from*, not the target itself. The R1/R2 column is thread J's naming
regime; the R2 rows plus the missing seams of thread K are the promotion worklist.

| Pair (node) | Node it mints | Granularity | Whole-method emitter today | Outbound edges to pairs | Naming regime |
|---|---|---|---|---|---|
| Fetcher | `<Type>Fetchers.<field>(env)` | field, 1:1, total | `FetcherEmitter`, `DataLoaderFetcherEmitter` (body via `TypeFetcherGenerator`) | Projection (root), Rows-method (child), Bean/Record, OrderBy | class R2, method R1 |
| Projection | `<Type>.$fields(sel, table, env)` | type-bound fold | `TypeClassGenerator` | Projection (recursive; cyclic), Condition/Join | R2 |
| Rows-method | `rows<X>` / `load<X>` | anchor (SELECT launcher) | `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter` | Projection, Scatter, InputRows | R1 |
| Scatter | `scatter*ByIdx` | dedup-by-class | `SplitRowsMethodEmitter` | leaf | R2 |
| Condition | `<field>Condition` | field | `TypeConditionsGenerator`, `QueryConditionsGenerator` | Join | R1 / R2 (half-migrated) |
| Join | join-path helper (`MethodRef` target) | per join path | `JoinPathEmitter` | leaf | R1 |
| InputRows | `<field>InputRows` | per lookup field | `LookupValuesJoinEmitter` | Join | R2 |
| Bean/Record | `create<Bean>` / `create<Record>` / `decode<Record>` | dedup-by-class | `InputBeanInstantiationEmitter`, `JooqRecordInstantiationEmitter` | leaf | R2 |
| OrderBy | `<field>OrderBy` | per orderable field | `OrderByResultClassGenerator` | leaf | R2 |

The cyclic core is three pairs (Fetcher to Projection to Rows-method to Projection), thread F's cycle.
**Pair = whole emitted method.** Emitters that render only an *arm* are sub-renderers that fold into a
pair, not pairs: the three `Inline*` arms of `$fields`; `ServiceMethodCall` / `ChannelCatchArm` /
`ChannelEarlyReturn` in the fetcher body; the `ArgCall` fragments. That partition resolves the
node-relation granularity fork (one pair per emitted-method-kind), and the seam-placement rule of thread K
governs *which* methods exist in the target.

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

- **Node-relation granularity** (the open fork from the session). **Resolved (thread K):** the node is one
  pair per *whole emitted method*; arm-renderers fold into a pair. *Which* methods exist in the target is
  governed by the seam-placement rule, not by a fixed count.
- **Edge inventory and naming authority** (was the next read-only step). **Resolved (threads J/K):** the
  2026-06-19 trace sorted every edge into regime 1 (model-carried, e.g. `rowsMethodName` / `MethodRef`,
  the target pattern) and regime 2 (formula-reconstructed, e.g. `$fields` at roughly eight sites). The R2
  set is the naming-authority worklist; "add a seam" promotes an inlined fragment to a regime-1 edge.
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
natural keys, the anchor/address primitive, the node and edge relations, the target seam topology and its
placement rule). Out of scope: the emit
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
