---
id: R333
title: "Lower each schema coordinate to a DataFetcher and its QueryParts"
status: Spec
bucket: structural
priority: 3
theme: structural-refactor
created: 2026-06-18
last-updated: 2026-06-22
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

## Seam worklist (living table)

This is the working surface for the spec's central open decision: **which seams (named method-call edges)
exist in the target lowering.** Each row is one candidate node; the table is iterated as decisions land,
and it is the denormalized roll-up of three things defined downstream in this doc, so read the columns
against them: the **naming regime** is thread J (R1 = name minted once and read on both ends, R2 = formula
reconstructed at each end), the **seam verdict** applies the seam-placement rule of thread K (a seam belongs
where a unit is (a) chosen by a runtime strategy/dispatch, (b) reused across more than one caller, or (c)
something we want to assert independently in the corpus or tests; inline only a linear, single-use,
non-varying construction), and "folds into X" means the row is an arm-renderer that is part of node X, not a
node of its own (thread K's pair partition). The acceptance test for the finished table is thread I's
bidirectional closure invariant.

Rows 1 to 9 are the seams the generator already cuts (the migration baseline; full detail in *Current seam
topology* below). Rows 10 to 11 are the decided new seams (the 2026-06-19 target topology of thread K). Rows
12 to 16 are the open surface: fragments inlined today whose promotion-or-inline verdict is what we iterate.
This table is the back-half view of the coordinate's `operation` relation; *Operations are realized by seams*
below draws the member-to-seam crosswalk that wires the two together.

| # | Candidate node | Today's emitter | Granularity | Regime (J) | Seam verdict (rule a/b/c) | Naming target / open issue |
|---|---|---|---|---|---|---|
| 1 | `<Type>Fetchers.<field>(env)` | `FetcherEmitter`, `DataLoaderFetcherEmitter` | field, 1:1, total | class R2, method R1 | seam (a): picks root / child / service strategy | lift class name to R1 |
| 2 | `<Type>.$fields(sel, table, env)` | `TypeClassGenerator` | type-bound fold | R2 | seam (b, c): reused + assertable | lift to R1 (the `$$fields` literal at ~8 sites) |
| 3 | `rows<X>` / `load<X>` | `SplitRowsMethodEmitter`, `MultiTablePolymorphicEmitter` | anchor (SELECT launcher) | R1 | seam (a, b): batched/direct dispatch + reuse | settled (`rowsMethodName`) |
| 4 | `scatter*ByIdx` | `SplitRowsMethodEmitter` | dedup-by-class | R2 | seam (b): class-level reuse | lift to R1 |
| 5 | `<field>Condition(...)` | `TypeConditionsGenerator`, `QueryConditionsGenerator` | field / method | R1 + R2 (half-migrated) | seam (c): assertable | finish lift (`QueryConditionsGenerator` end) |
| 6 | join-path helper | `JoinPathEmitter` | per join path | R1 | seam (b): reused | settled (`MethodRef`) |
| 7 | `<field>InputRows` | `LookupValuesJoinEmitter` | per lookup field | R2 | seam (c): assertable | lift to R1 |
| 8 | `create<Bean>` / `create<Record>` / `decode<Record>` | `InputBeanInstantiationEmitter`, `JooqRecordInstantiationEmitter` | dedup-by-class | R2 | seam (b): class-level reuse | lift to R1 |
| 9 | `<field>OrderBy` | `OrderByResultClassGenerator` | per orderable field | R2 | seam (c): assertable | lift to R1 |
| 10 | Root Query unit (the root `rows<X>`-equivalent) | inlined in `SelectMethodBody` today | anchor | new | seam (b): one query unit shared by root and child | new R1 edge; the decided 2026-06-19 target (closes the root/child asymmetry) |
| 11 | Service-call unit | inlined via `ServiceMethodCallEmitter` today | per service-backed field | new | seam (a): service vs query strategy | new R1 edge; the service-backed arm of the same delegation |
| 12 | Inline column-reference arm | `InlineColumnReferenceFieldEmitter` | arm of `$fields` | n/a | folds into Projection (row 2); promote under looser-(c)? | OPEN: assert separately vs inline (linear, single-use projection) |
| 13 | Inline table-field arm | `InlineTableFieldEmitter` | arm of `$fields` | n/a | folds into Projection; emits the `Y.$fields` edge | edge name lift tracked under row 2 |
| 14 | Inline lookup table-field arm | `InlineLookupTableFieldEmitter` | arm of `$fields` | n/a | folds into Projection | same as row 13 |
| 15 | Channel catch / early-return arms | `ChannelCatchArmEmitter`, `ChannelEarlyReturnEmitter` | arm of fetcher body | n/a | folds into Fetcher (row 1) | OPEN: assert the error channel independently? |
| 16 | `ArgCall` fragment | `ArgCallEmitter` | fragment | n/a | folds into Condition / Query unit | inline (linear, single-use) unless a caller reuses it |

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

## The normalized schema: the coordinate and its facts

R316's `(source, operation, target)` is common vocabulary that carries well, but it is a per-coordinate
**summary row**: a denormalized join over several independent facts, with `operation` crammed into a single
slot. The entity is the schema coordinate `(parentType, fieldName)`; the model is the coordinate together
with its facts, each its own functional dependency:

- **`coordinate -> source`, total, 1:1.** The arrival fact: whether a source object arrives and how many,
  plus the parent shape. Read off the **parent and the edge into the field**, not off the field itself.
- **`coordinate -> target`, total, 1:1.** The output fact: the field's own output cardinality (the wrapper)
  and shape. Read off **`field.getType()`**.
- **`coordinate -> operation`, 0..N.** The operation set (the QueryPart-emitting methods, thread E's
  SQL-side commands). `operation` is not one forced verb but a **set**: a single field can `select` and
  `join` and `condition` and `paginate` and `orderBy` at once.
- **`coordinate -> reference`, 0..1.** The cross-table fact: present exactly when the field's value lives
  off the parent's own table (a different table, or a column in a different table). Authored (`@reference`)
  or inferred from the unique foreign key; it lowers to a `join` operation, with `joinPath` as its resolved
  form. Detailed below in *The `reference` fact*.
- **`coordinate -> referencedTable`, 0..1.** The reference's **destination** table, named in its own right.
  Present exactly when `reference` is. Not the same as `source.table` (a self-referential FK makes them
  coincide while a `join` is still minted). Detailed below in *The `reference` fact*.
- **`coordinate -> resolvedTable`, derived, 0..1.** The catalog table the `@field` resolves against (a
  column's owning table, or a nested field's rooted table). A priority coalesce over three facts:
  `referencedTable ?? source.table ?? target.table`. Present for every field that touches a table, absent
  for record / service fields. Detailed below in *The resolved table*.
- **The same facts apply to input fields**, keyed `(coordinate, path)` (the dotted path to the input field),
  relative to the consuming output coordinate. Their facts roll up into the output coordinate's operation
  set. Detailed below in *Input coordinates*.
- **Two further facts are *read-side*.** Every fact above is build-side (it constructs the query or
  operation) ; these read a value back out of the object at `env.getSource()`: a type-level **source object**
  fact (the cast-target record shape) and a field-level **accessor** fact (a locator paired with a
  transform). Detailed below in *Reading the source object*.

`source` and `target` are two different facts, derived by two different walks (parent/edge versus the
field's type), and they vary independently ; the same `target` `List(Table)` sits under a `Root` source or
a `Child` source. Earlier drafts bundled them into one `(coordinate, source, target)` row and named it "the
DataFetcher relation"; that let the DataFetcher, which is a **view** (a node kind, per *the two library
types are node kinds, not the top level*), masquerade as the top-level relation. The honest form keeps the
coordinate as the entity and `source` / `target` / `operation` as its facts. **The DataFetcher is the view
that joins `source` and `target`** (and dispatches the operation set); the QueryPart-emitting methods are
views over operations.

**The three splits do not all have the same forcing function, and the spec should not pretend they do.**
`operation` *had* to split out: it is multi-valued, so one slot was a genuine 1NF / repeating-group fault.
`source` and `target` are each single-valued and 1:1, so co-storing them was never a normalization
violation; separating them is fact-independence (two FDs, two walks) and refusing to let a view name a
relation, not a normal-form fix. Both separations are right; only `operation`'s is forced by a normal form.

The corpus directive sits on the coordinate (the natural key), so it already annotates the right thing; its
verdict generalizes from one triple to a `source` fact, a `target` fact, and a *set* of operation rows,
each independently assertable.

**`operation` is a set because its members are triggered by separate, walkable facts.** Most are
*input-triggered*, fired by an independent SDL or argument fact: a table-bound return type mints `select`,
pagination args / a `Connection` mint `paginate`, `@condition` or filter inputs mint `condition`,
`@orderBy` mints `orderBy`, `@service` mints `serviceCall`. One is *relational*: `join` is minted by the
`reference` fact, that is, by the relationship between `source` and the value's table rather than by an
independent input (see *The `reference` fact*). The operation set is the **union** of these triggers. This
is the schema-walk reading of the whole thesis: the leaf cross-product (`Split x Lookup x Composite x ...`)
is what you get from collapsing independent operations into one slot, so they *multiply* into leaf variants;
as a set they merely co-occur, and the cross-product dissolves **additively**. Composite falls out the same
way ; `N` columns are `N` (or one `N`-ary) `select` operations, arity gone as a coordinate dimension.

Normalization assigns each R316 axis to the fact that owns it (nothing in R316 is wrong, it just gets
distributed); the **read-by** column names the view that consumes the fact:

| R316 axis | Owning fact | Read by | Notes |
|---|---|---|---|
| `source` (Root / OnlyChild / Child) | `coordinate -> source` | DataFetcher | invocation cardinality; `Child` dispatches through a DataLoader |
| `target` wrapper (Single / List) | `coordinate -> target` | DataFetcher | output cardinality; the resolve-side contract |
| `target` shape `Column` / `Table` | `coordinate -> target` | a `select` operation | projection kind (a jOOQ `Field`, a table projection); the fact lives on `target`, the operation reads it |
| `target` shape `Record` / `Field` | `coordinate -> target` | DataFetcher only | read off a Java object; **zero operation rows** |
| `operation` `Fetch` / `Paginate` / `Lookup` / `Count` / `Facet` / DML | `coordinate -> operation` | the operation's own method | the SQL verbs, now set members |
| `operation` `ServiceCall` | `coordinate -> operation` | DataFetcher delegates to it | `serviceCall`; no SQL |
| `operation` `Nest` | (none: empty set) | DataFetcher only | a regroup with no SQL; the DataFetcher's existence is the fact |
| composite arity | `coordinate -> operation` | the `select` operation | the number of projected columns, not a coordinate dimension |
| split / re-fetch / new-query | operation `address` | the addressed anchor's query | which anchor's SELECT the operation lands in |

Two refinements the flat triple hid. First, **`target` is consumed by two views**: its wrapper (Single /
List, the output cardinality) is read by the DataFetcher, its shape by the `select` operation. The fact
itself stays whole on `coordinate -> target` (it is just the field's type); the two views read the parts
they need rather than the shape being duplicated onto the operation. Watch the embeddable-`Column`-as-
`Record` case: a target shape becoming a child's source shape is a coordinate-to-coordinate edge (the
wrapper algebra), not an operation. Second, **operation multiplicity is 0..N, not 1..N**: a field that
reads off an in-memory record (`target` shape `Record` / `Field`), and `Nest`, have a `source` and a
`target` fact and an empty operation set; the DataFetcher's existence is the fact, and a no-op operation
row would buy nothing.

R316 slices 1-4 are the denormalized, singleton-row view (one coordinate, its `source` and `target` facts,
at most one operation's worth of facts) and stay valid as that projection ; they are the empty-or-one case
of the 0..N operation set.

### The `reference` fact

A field whose value lives off the parent's own table, reaching either a **different table** (a nested table
field) or a **column in a different table** (a column-reference field), carries a `reference` fact. Same-table
fields (a plain `ColumnField`) carry none. So `reference` present is exactly the condition that a `join`
operation exists: the value's read-table differs from `source.table`.

Naming the fact resolves the "alters the source / alters the path" puzzle, because it alters **neither
`source` nor `target`**. The model's `source` is the *arrival* (the parent that reaches the resolver), and
`@reference` never changes that ; an A-row still arrives. What it relocates is the table the value is *drawn
from* (the read-table), which defaults to the parent's table. The puzzle was two senses of "source": the
arrival (the model's `source`, untouched) versus the value's read-table (what `reference` moves).

A foreign-key traversal needs a **destination table** and a **path**; `reference` always supplies the
traversal, and the two field kinds differ only in which the field's other facts had already pinned:

- **Column target** (`ColumnReferenceField`): a scalar names no table, so `reference` supplies both ; it
  moves the read-table off the parent onto the destination. This is what reads as "altering the source".
- **Table target** (`TableField` and kin): the destination is already pinned by the nested type's `@table`,
  so `reference` supplies only the path, disambiguating which FK route reaches it. This is what reads as
  "altering the path".

Both are one edge-alteration seen against different fixed endpoints: `reference` parametrizes the edge
between the enclosing query and the value, never the endpoints.

The shape axis (Column vs Table) is independent of a second axis the reference also carries: **direction**.
A reference is **to-one** when the foreign key sits on the parent's side (a *parent reference*: each parent
row points at one destination row) or **to-many** when it sits on the child's side (a *child reference*:
many destination rows point back at one parent). Direction sets the `target` **wrapper** by the same
wrapper-from-direction rule table fields obey: to-one yields `Single`, to-many yields `List`. The two axes
are orthogonal, a 2x2:

| | to-one (parent reference) | to-many (child reference) |
|---|---|---|
| **Column** | `Single(Column)` ; `film.originalLanguageName` | `List(Column)` ; `film.actorNames: [String]` |
| **Table** | `Single(Table)` ; `film.language` | `List(Table)` ; `actor.films` |

Today's `ColumnReferenceField` is only the top-left corner (`OutputField.single(Column)`,
`ChildField.java:133`); **`List(Column)` is the missing corner**, a list of one scalar drawn from the
to-many child rows. With it, column-ref and table-ref differ *only* in `target.shape`, and `Single` / `List`
differ *only* in direction; `resolvedTable` is the destination table B in every cell. A `List(Column)` child
reference is not a cheap scalar variant: being to-many it needs the same machinery as a to-many table field
(a `Child` source, an anchor, a rows-method, batched or aggregated), projecting one column instead of
`$fields` ; it is "a to-many table field minus the nested projection."

`reference` is **authored or inferred**, and the inference is total with a typed failure. `@reference`
supplies it explicitly; absent that, it is inferred from the foreign keys between `source.table` and the
destination ; **exactly one** FK and the path is inferred, **zero or more than one** and it is not
derivable, which is an `AuthorError` telling the author to supply `@reference` with the information needed
to join (the LSP-surfaced rejection, not a silent guess).

It lowers to the `join` operation; `joinPath` (`List<JoinStep>`) is its resolved form, never an independent
axis. For a column-reference field it lowers into *two* places at once: the `target`'s column identity (the
destination column) and the `join`'s path, and those must agree (the column's table is the join's
destination). That agreement is a referential-integrity check between the `target` fact and the `join`
operation, the FK-as-join-graph point made concrete.

The worked example is the additive proof, visible in the leaf records. `ColumnField` (`ChildField.java:262`)
and `ColumnReferenceField` (`:288`) are component-identical except the reference variant adds `joinPath`
(and `parentCorrelation`): same `source` (`Table`), same `target` (`Single(Column)`), same column and
compaction. They are one `(source, target)` pair whose operation sets differ by exactly one `join` minted by
the `reference` fact: `{select}` versus `{join, select}`. Not two leaf types ; the same coordinate facts
plus one fact.

The reference's destination has a name of its own: **`referencedTable`**, a 0..1 fact present exactly when
`reference` is, and the table `joinPath` terminates at. It is **not** `source.table`: a self-referential FK
(`employee.manager_id -> employee`) makes the two coincide while `reference` is still present, so a `join` is
still minted. The `join` is minted by `reference` **presence**, never by `referencedTable != source.table` ;
comparing the tables would silently drop self-joins, so the model must not "optimize" the join away that
way.

### The resolved table

`coordinate -> resolvedTable` is a **derived** fact: the catalog table a `@field` resolves against, the
column's owning table for a column field or the rooted table for a nested field. It is a priority coalesce
over three facts, each arm defined exactly where it fires:

```
resolvedTable = referencedTable ?? source.table ?? target.table
```

- **`referencedTable`** first: the value is reachable only by a join (a column-reference field, or a
  child / nested table field reached by an FK). For a child table field it shadows `source.table` and equals
  `target.table`.
- else **`source.table`**: the value lives on the parent's own row. A plain column field, and a **nesting
  type** ; an object type that is **not** table-bound, whose field inherits the parent's table and shares its
  row(s). That is the `Nest` case: still SQL-backed, no join, no `referencedTable`, and distinct from the
  `Record` / `Field` shapes that read off a Java object.
- else **`target.table`**: a **root table field** (`source = Root`). It has no source to reference from, so
  it carries no `referencedTable` and enters via the FROM clause. `target.table` is defined only when
  `target.shape == Table`, so this arm can fire only for root table fields.

It is present for every field that touches a table and absent only for `Record` / `Field` / `serviceCall`
fields that never do. Root and nesting fields are **mirror fall-outs** of `referencedTable` being 0..1: a
root table field takes `resolvedTable` from the **target** side (no source to reference), a nesting field
from the **source** side (same table as the parent), and in both `referencedTable` is simply absent. For a
column field `target`'s shape (`Column`) names no table, so `resolvedTable` is the only carrier of it ; it is
the generalization of `target.table` to the scalar case, which is why it must be derived rather than read off
one fact.

For a table field `resolvedTable == target.table` **always**, and whenever a `referencedTable` is present it
equals them too ; the three-way `resolvedTable == referencedTable == target.table` is the with-reference
(child / nested) reading, and a root table field simply drops the middle term. Read over present facts,
**that coincidence is an invariant** and a cross-check between two independently-walked facts: the FK route's
destination (`referencedTable`) and the declared output type (`target.table`). They are walked from different
places, the foreign-key graph and the SDL return type, and must agree ; a mismatch is an `AuthorError`
("`@reference` routes to X but the field returns Y"), not a silently accepted mismatch.

Naming it lifts a derivation otherwise recovered three ways (today `source.table` for `ColumnField`, the
`joinPath` terminus for `ColumnReferenceField`, `target.table` for table fields ; `ColumnRef` deliberately
omits the table because this fact owns it). Consumers then read one fact instead of each reconstructing it:

- the `join` operation's **destination** is `resolvedTable` (when `reference` is present);
- the `select` operation **projects from** `resolvedTable`;
- a field's `resolvedTable` is its children's `source.table` ; the table-level form of the wrapper algebra
  (a field's target shape becomes its children's source shape), and the actual carrier of the
  parent-to-child table flow.

### Conditions key on the resolved table

A `@condition` is a predicate, and predicates attach to **relations**, not to projections; the relation a
field reads is its `resolvedTable`. So a `condition` operation keys on `resolvedTable`, not on `target`. The
two choices coincide everywhere a condition is legal today, because `resolvedTable == target.table` for
table fields, so this is the normalized statement of the current behavior (`filters` live on
`TableTargetField` today, `ChildField.java:422`), not a change to it; it merely extends cleanly to the cases
where `target` is a scalar that names no table.

`condition` is a **0..N** relation, owned by the coordinate and *placed* on its `resolvedTable`: the
coordinate fixes which conditions exist (the same table resolved at two coordinates carries different ones),
`resolvedTable` is where each predicate lands. The rows **conjoin** (AND) into the WHERE, or into a
`LEFT JOIN` ON clause for the `Single` value-gating case below. Each row has a **provenance**:

- **authored** ; an `@condition`, an opaque jOOQ predicate the model knows only by method name ;
- **generated** ; minted by an input table binding, the input-coordinate fact lowered (see *Input
  coordinates*), structured as a column of `resolvedTable`, an input source, an operator, and
  presence-gating.

The two provenances mirror the `reference` fact's authored-or-inferred shape: `@condition` is to a generated
condition what `@reference` is to an inferred `join`. The discovery thread's `GeneratedConditionFilter` (the
body shape `eq` / `in` / `row(...).eq` "driven by the input surface, not by the field") is exactly the
generated arm's resolved form, no longer a loose observation.

The condition's **semantic forks on `target.wrapper`, not on `target.shape`**:

- **`List` (to-many)**: row-set filtering ; "which rows of `resolvedTable` contribute." Standard, no
  parent-cardinality hazard (the set is already per-parent, batched or aggregated). True identically for
  `List(Column)` and `List(Table)`. This is why allowing child references makes conditions obviously
  sensible: a `List(Column)` child reference has a real relation to filter.
- **`Single` (to-one)**: value-gating ; "null the value when the predicate fails." Correct only with the
  predicate in the join's **ON clause** under a `LEFT JOIN`, so a failing predicate nulls the value rather
  than dropping the parent row. This subtlety is a property of the `Single` wrapper, shared by
  `Single(Table)` and `Single(Column)` alike ; it was never a column-reference quirk.

So conditions over `resolvedTable` are first-class for every wrapper. The only open semantic is `Single`
value-gating (the ON-clause placement and the parent-cardinality-preserved invariant), and it is owed for
to-one table references regardless, so it is not new debt introduced by allowing column references.

Presence-gating is a **third, orthogonal gate**, carried only by the generated arm: an optional input absent
emits nothing (`TRUE`), present emits `column OP value`. It governs *whether* a predicate fires (read from
the input's nullability) ; the wrapper fork governs *how* a fired predicate applies. A single generated
condition on a `Single` field is therefore both presence-gated (does it fire?) and value-gating (if it fires,
it nulls rather than drops). Authored conditions carry no presence-gating ; the author expresses their own.

### Input coordinates

`@reference` and `@condition` apply to **input** fields too, so input fields are fact-bearers on the same
footing as output fields, keyed `(coordinate, path)`: the consuming output coordinate plus the dotted `path`
to the input field, rooted at the field's argument list (`where.title`, `filter.actor.lastName`). They key
on the consuming coordinate, not on the GraphQL input type, for the same reason output facts do, the same
`where` input resolves against `film` at one query and `actor` at another, so its bindings, inferred FKs, and
`referencedTable` all depend on the use site.

An input coordinate carries the same fact vocabulary, `source`, `target` (shape × wrapper), `reference`,
`referencedTable`, `resolvedTable`, and obeys the same nesting algebra: a path-internal input object is
`Table`-shaped and its `resolvedTable` becomes its children's `source.table` ; the leaves are `Column`-shaped
and are the actual predicates. The input tree is a coordinate tree with the same facts, flowing toward a
predicate rather than a projection.

Input facts **roll up into the output coordinate's operation set** (the output coordinate is the query
emitter):

- an input coordinate's `reference` ⇒ a `join` on the output query. This is why input-side `@reference` is in
  scope: a cross-table input filter is just the reference fact doing on the input side what it does on the
  output side ;
- an input coordinate's leaf `target` (a column of its `resolvedTable`) ⇒ a **generated** `condition`, with
  operator from the input `target.wrapper` (`Single` ⇒ `eq`, `List` ⇒ `in`, multi-column path ⇒
  `row(...).eq`) and presence-gating from the input's nullability.

So an input field is a **shared fact source**, and the field-to-operation relation is **many-to-many**, not
1:1. The same field can mint a generated condition **and** be consumed as an argument by an authored
`@condition` ; both are live and conjoin. A flat triple forces each field into a single role and cannot
express this ; the normalized model lets one field carry several facts. The raw relations are:

- `generated_condition(coordinate, path)`, minted by a leaf binding ;
- `authored_condition(coordinate, method, override)`, an `@condition` ;
- `consumes(coordinate, method, path)`, which input fields the authored condition takes as arguments (read
  off its parameter list).

The resolved operation set is **union-then-suppress**, not a plain union. `@condition(override: true)` is a
**suppression edge**: for the path it consumes it blankets that path and its **whole subtree** of generated
operations, the generated conditions and the `join`s that input-side references in the subtree minted to
serve them. (We start by reaping the entire generated subtree and will narrow only if a use case needs the
join to stand for a hand-written predicate.) Authored `@condition` facts are never suppressed ; only
auto-generated scaffolding is:

```
generated_op(c, p) is live iff
  ¬∃ m, P. authored_condition(c, m, override=true) ∧ consumes(c, m, P) ∧ P ⊑ p

conditions = authored_conditions ∪ { live generated conditions }
```

where `P ⊑ p` means `P == p` or `P` is an ancestor of `p` in the dotted-path tree. A generated op is
suppressed iff consumed by **at least one** override condition ; `override: true` on a condition that
consumes nothing is a no-op. The suppression is the same shape of declarative resolution as the
`resolvedTable` coalesce: a function over the raw facts, computed once, not a special case threaded through
emission.

### Reading the source object

Every fact above is **build-side**: it constructs the SELECT, the joins, the conditions, the `operation`. But
every output field is wired to exactly one `DataFetcher`, and a `DataFetcher` ultimately **returns a value to
graphql-java**. Some fetchers obtain that value by *producing* it (running the `operation`: a query, a
`@service` call, a DML write) ; others obtain it by *reading* it out of the object already at
`env.getSource()`. The read is its own fact family, the read-side complement of the build-side schema, and it
is what this section names.

**Two phases, consume then produce.** "Producer" and "reader" are not disjoint field sets ; they are two
phases inside one fetcher, in a fixed order:

- **consume** ; read this field's source object (the object its *parent* deposited at `env.getSource()`) ;
- **produce** ; run the `operation`, depositing a new object ;
- the deposited object is the source object this field's *children* consume.

A pure reader is the degenerate case with no produce phase (the consumed value is the answer) ; a root
producer the other (no source arrives). Everything else does both, and the order is load-bearing: a re-fetch
reads the parent row's foreign key (consume) *before* it launches the child SELECT keyed on it (produce). So
one field touches **two** source-object facts about two different types: its **parent** type's (consumed) and
its **return** type's (produced, which is its children's source object).

This also dissolves a tension from the leaf walk: a bare column read and a bare scalar read both carry
`operation = Fetch`, and the catalog-versus-Java split rode entirely on `sourceShape`. That is because
`sourceShape` is not a sibling of `operation` at all ; it is **read-side**. `operation` is the build-side verb
(how to *produce* a value) ; the source-object shape is the read-side fact (how to *read* one). They sat in
one list but belong to different families.

#### The source object is type-level

`GraphitronType` is "the authoritative source of source context for all fields defined on it." The source
object is therefore a **type-level** fact: every field on a type reads off the same kind of object, fixed by
the type's classification, not the field. The field-level `sourceShape()` / `domainReturnType()` are
**projections**, cross-checked against the type (`SourceShapeProjectionTest`) so a field cannot diverge from
the type it claims.

Its value is a **record shape**, and **never a table**. A table is a build-side relation ; what arrives at
`env.getSource()` is always a row / record (or a scalar, or a Java object), never a relation. So "Table" is
not one of its values. The arms are the cast targets the read needs:

- a **jOOQ record** source casts to the generic `org.jooq.Record` (reads go through `get(Field<T>)`, so the
  concrete `FilmRecord` is never needed ; the typed-vs-sparse `TableRecord` / `Record` distinction is a
  producer concern) ;
- a **Java** source casts to its backing class (`FilmDto`) ;
- a **scalar** is "already the value," no cast.

`DomainReturnType` (`Record` / `TableRecord` / `Plain`, no `Table` arm) is the carrier ; `SourceShape`
(`Table` / `Record`) is not, because it fuses three things the read keeps apart: record-ness, catalog
provenance, and table-boundness.

**Table-boundness is a separate fact.** For a table-bound type the record shape is *derivable* from the
`TableRef`, but the source-object fact carries it **materialized**, so a reader consumes one fact rather than
walking "table-bound ⟹ jOOQ record ⟹ read by `Field`." This is the `resolvedTable` lift one level up (derive
once at classify time, store it, never re-derive at the read site). The build side keeps the `TableRef` ; the
read side keeps the record shape ; provenance is consulted by neither.

**The uniform-producer axiom.** Different fields can produce the same SDL type (a `@table` type reached by a
SELECT and by a `@service`). We **assert** all producers of a type deposit the **same** shape ; disagreement
is an `AuthorError` (the shipped `validateUniformDomainReturnType` / `MultiProducerDomainTypeDisagreement`
guard). This is the precondition that lets the fact be type-level: one shape per type means the child reads
against one known shape, the cast is unconditional, and the accessor stays monomorphic. Drop it and the source
object becomes a `(type × producer)` fact and every read turns polymorphic. **Deferred:** producer-
polymorphism is named but out of scope, the same "assert the simplifying invariant now" move as
override-suppression-maximal and the `List(Column)` deferral.

(`NestingType` is the transparent exception: it owns no table, inheriting the *embedding* type's row, so the
same nested type under `Film` versus `Actor` sees `FilmRecord` versus `ActorRecord`. The fact stays
type-level, owned by the embedding `TableBackedType` ; the model copes by reading nesting children by *name*
off the generic `org.jooq.Record`, the identity all embedding sites share.)

#### The accessor is field-level

Where the source object keys on the type, the **accessor** keys on the field: each field pulls its own value
out of the (now cast) source object. It is a **sealed family**, each arm carrying only its own facts, gated by
the source object, replacing the nullable `column`-xor-`accessor` slots on today's `RecordField` /
`PropertyField` with arm identity. It decomposes into a **locator** and a **transform**.

**The locator** says *where* the raw value(s) live, one leaf read or `N` for a composite:

- **typed jOOQ field** ; the FQN of the `Field<T>` constant to extract. Provenance-blind: a jOOQ-generated
  `FILM.TITLE` and a graphitron-generated field read identically via `record.get(thatField)`, which collapses
  the present `ColumnField`-read and `ComputedField` / `@externalField`-read into one arm and retires the
  `ColumnRef`-omits-its-table awkwardness (the accessor holds the table-qualified reference, so the read needs
  no table fact).
- **Java record component** / **POJO getter** / **public-field read** ; the resolved Java accessor (today's
  `AccessorResolution.Resolved`).
- **by-name jOOQ field** (`DSL.field("title")`) ; the untyped fallback when no constant resolved (the
  nesting-reuse case).
- **whole-object passthrough** (`env -> env.getSource()`) ; the value *is* the source object (the
  `NestingField` identity read).
- **localContext** / **`Outcome.ErrorList` arm** ; where an errors list lives (today's `ErrorsField.Transport`).

**The transform** establishes the **function** applied to the locator's read(s). `Direct` is the identity (a
bare read, no call) ; `NodeIdEncode` establishes the per-type `encode<T>` helper ; `EnumValueOf` /
`JooqConvert` establish those ; `decode` is the input-side mirror. The accessor is uniformly `function(args)`:
the **transform names the function**, the **locator names the arguments**. A composite key is one transform
over an `N`-read locator, dissolving `CompositeColumnField` / `CompositeColumnReferenceField` on the read side
the way the spec dissolves composite on the projection side: the `N`-column repeating group becomes `N`
argument reads under one `encode`, arity gone as a leaf dimension.

This is the shipped shape on both polarities, the corroboration that the locator / transform split is real:

- output ; `ColumnField` carries `column` (locator) and `compaction` (transform) ; `CallSiteCompaction.Direct`
  names no function, `NodeIdEncodeKeys` carries the `HelperRef.Encode`. `CompositeColumnField` is the same
  with an `N`-read locator (`columns`) and one `NodeIdEncodeKeys`.
- input ; `ValueShape.Scalar` carries `sdlPath` (locator) and `leafTransform` (`CallSiteExtraction`: `Direct`
  / `EnumValueOf` / `JooqConvert` / `NodeIdDecodeKeys`, the function). Same shape, other direction.

**Deferred:** the composition is **depth-1** (a function over leaf reads), which covers nodeId including the
composite case. Full recursion (`f(g(read))`, a transform whose arguments are themselves transformed
accessors) is named but unbuilt ; model the flat form now, nest only if a use case needs it.

#### Composition

A read is **cast then access**: the source-object fact emits the unconditional conversion of `env.getSource()`,
the accessor reads off the converted object. The source object **gates** the legal locator arms (a jOOQ-record
source admits the typed-field and by-name arms ; a Java source the component / getter / field arms ;
passthrough is shape-agnostic), and the transform is orthogonal (any locator wrapped by any function, or
none). So the read side is two facts, a type-level **source object** (the cast target) and a field-level
**accessor** (a gated locator paired with a function-naming transform), standing as the read-side complement
of the build-side `source` / `target` / `operation` / `reference` / `resolvedTable` / `condition` family.

## We are data modeling: the relational discipline, not a database engine

Everything above is data modeling, and it has quietly adopted the whole vocabulary of a relational
database: keyed relations, a foreign key (the coordinate), normalization (1NF on both repeating groups, the
composite columns and the `operation` slot; 3NF on the split key-projection), and **referential integrity** (thread I's closure-under-reference is exactly that
constraint on the edge relation). Taken to its end this looks like rebuilding a database, which raises the
question honestly: should the generator just *use* one? The answer is a deliberate split. **Adopt the
relational model as design discipline; do not adopt a relational runtime.** The decision and its reasoning:

- **The vocabulary is the win, and it is free.** Keys, joins, normalization, and referential integrity are
  what make the leaf zoo dissolve; they cost nothing but clear thinking and are already in this doc. Keep
  taking the *modeling* all the way.
- **A query-engine runtime is the wrong tool here, for three reasons.** (1) It inverts the project's
  deepest commitment. `rewrite-design-principles.adoc` is wall-to-wall *compile-time* typing of the model
  (sealed hierarchies over enums, narrow component types, classification pinned at the parse boundary,
  exhaustive switches that turn "added a variant" into a compile error). A SQL or Datalog layer makes the
  model stringly-typed and moves exhaustiveness from `javac` to runtime ; spending the central asset to buy
  what the type system already gives. (2) It buys the wrong thing. A database earns its keep at *scale* and
  on *large recursive fact sets*; a schema has hundreds to low-thousands of coordinates, so the value we
  want is expressiveness and integrity-checking, not throughput, and both are available without a runtime.
  (3) It would freeze a still-discovered model: R222 / R316 / R333 are mid-pivot, and committing an engine
  substrate now pins a schema whose column set is not yet stable. Note also that the relational model only
  ever described the classification (front) half; the emit (back) half is imperative JavaPoet rendering
  that no engine makes easier, so even the maximal version "databases" only half the generator.
- **The chosen materialization is typed relations in the type system.** The coordinate's facts are typed,
  keyed collections of records (`Coordinate -> source` and `Coordinate -> target` as `Map<Coordinate, _>`,
  `Coordinate -> operation*` as a one-to-many), with explicit indexes where a join is hot; the DataFetcher
  and the QueryPart-methods are views computed over them. The sealed-variant field model already *is* a
  denormalized materialized view over these facts; this decision keeps it that way and formalizes the
  relations and the integrity check around it, rather than relocating them onto an external store.
- **Referential integrity is a typed check, and it is thread I's test.** "Every method-name an edge
  references resolves to a node in the node relation" is the closure invariant written as integrity
  validation over the in-memory relations. This is the single highest-leverage database feature, it needs
  no database, and it earns its place twice (the model's integrity constraint *is* the falsifiable test).

**Reserved, and explicitly not "a database":** if a pull toward a real engine ever becomes acute it will be
an *incremental, demand-driven memoized query* architecture (the salsa / rust-analyzer model: edit the
schema, recompute only the affected classifications), not a relational store. Its one concrete future
trigger is LSP performance ; the LSP already does incremental parsing and marshals a `CatalogBuilder`
snapshot to the editor, and incremental reclassification is the natural next want. That is a separate,
later question tied to LSP perf, deliberately not conflated with "sit the generator on a database," and out
of scope here.

## Operations are realized by seams: wiring the two halves

The two relational pictures in this spec are one model seen from its two ends, joined by the `operation`
relation. The **front half** (the normalized schema above) keys facts on the coordinate and reads each
operation off a trigger fact. The **back half** (the seam worklist up top, and the method-call graph of
threads E to K) is the emitted side: named methods and the calls between them. A coordinate's `operation` set
*is* the set of QueryPart-emitting seams its query unit composes; the seam worklist is the back-half **view**
of the `operation` relation, the same way the DataFetcher is the view that joins `source` and `target`.

The back-half seams sort into three layers, and only the middle one is the operation relation:

- **The DataFetcher view** (worklist row 1). Reads the `source` and `target` facts and dispatches; it is a
  view over facts, not an operation. The `nest`-only coordinate (empty operation set) bottoms out here: the
  DataFetcher regroups in memory and emits no SQL seam.
- **The dispatch targets**: the **Query unit** (the SELECT launcher; rows 3 and the decided root row 10) and
  the **Service-call unit** (row 11). The Query unit is the host the SQL operation set renders *into*; the
  Service-call unit *is* the `serviceCall` operation realized as a unit.
- **The operation seams** the Query unit composes (one per operation-set member; the crosswalk below), plus
  the **boundary helpers** (scatter row 4, bean/record row 8) that marshal across the resolve/SQL boundary
  and, like the DataFetcher, are views not operations (which is why they carry no trigger fact).

The member-to-seam crosswalk (the column the worklist deferred to here):

| `operation` member | Trigger fact (front half) | Realizing seam (worklist row) | Naming regime |
|---|---|---|---|
| `select` (projection) | table-bound `target` | Projection `$fields` (2) | R2, lift to R1 |
| `select` (launch / FROM) | a `source` anchor | Query unit / rows-method (3; root 10) | R1 child, new for root |
| `paginate` | pagination args / `Connection` | applied within the Query unit (3, 10) | with the query unit |
| `join` | the `reference` fact | join-path helper (6); lookup `InputRows` (7) | R1 (6), R2 (7) |
| `condition` | `@condition` / filter inputs | Condition (5) | R1 + R2 (half-migrated) |
| `orderBy` | `@orderBy` | OrderBy (9) | R2, lift to R1 |
| `serviceCall` | `@service` | Service-call unit (11) | new |
| `nest` (empty set) | non-table nesting | no seam; DataFetcher (1) regroups | n/a |

Two things the crosswalk makes visible. First, `select` lands on **two** seams (the projected column list in
`$fields`, and the FROM/launch in the Query unit), the back-half echo of `target` being read by two views in
the front half (wrapper by the DataFetcher, shape by the `select` operation). Second, the additive
dissolution is now end-to-end: a coordinate's operation set is a *union* of rows, each row is one seam, and
"more facts trigger more operations" is "more seams composed into the one Query unit," never a new leaf
variant. Composite's `N` columns are `N` `select` contributions into the same Projection seam; arity is gone
from both halves at once.

The bridge also closes thread I over both halves: the front half commits the operation set (which seams must
exist), the back half commits the names (regime 1), and referential integrity is that every operation
resolves to a committed seam and every seam traces back to an operation or a view. Thread I's falsifiable
test asserts that round-trip.

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
  `(DataFetcher, QueryPart*)` decomposition. **Resolved by the normalized schema:** the directive asserts
  the coordinate's `source` fact, its `target` fact, and a *set* of `operation` rows, each independently
  assertable (an operation is a regime-1 seam by construction). This is the same set framing the leaf
  cross-product dissolves into; the residue is only the rendering of an operation set in the corpus, not
  whether it is one or many rows.
- **Materialization: discipline vs runtime**. **Resolved (data-modeling section):** adopt the relational
  model as design discipline, materialized as typed keyed relations in the type system, with referential
  integrity as a typed check (thread I's closure invariant); do **not** adopt a query-engine runtime
  (sealed-variant type safety, the model's still-discovered column set, and the small fact count all argue
  against it). An incremental memoized-query engine for the LSP is reserved as a separate question, out of
  scope here.
- **Condition placement and the `Single` value-gating semantic**. **Resolved (the resolved-table section):**
  a `condition` keys on `resolvedTable`, and its semantic forks on `target.wrapper` (`List` = row-set
  filtering, `Single` = value-gating). **Open residue:** the `Single` value-gating semantic itself, the
  predicate's ON-clause placement under a `LEFT JOIN` and the parent-cardinality-preserved invariant. Owed
  for to-one table references regardless, so allowing column-reference conditions adds no new debt here.
- **The `List(Column)` corner**: the to-many child column reference is named but unmodeled (today's
  `ColumnReferenceField` is only `Single(Column)`). Settle whether it lands as a wrapper variant of the
  reference fact reusing the to-many table-field machinery (a `Child` source, an anchor, a rows-method,
  projecting one column instead of `$fields`), or as its own leaf, before it is implemented.
- **Override suppression granularity**. **Started maximal:** `@condition(override: true)` blankets the
  consumed path's entire generated subtree, the generated conditions **and** the `join`s minted to serve
  them. Chosen for simplicity, on the bet that an overriding author owns that branch's SQL. **Open residue:**
  narrow to conditions-only (leaving an input-side reference's `join` standing for a hand-written predicate to
  use) only if a use case requires it ; the per-field, subtree-scoped rule is easy to relax that far.
- **Read-side facts** (the source object and the accessor). **Resolved (the *Reading the source object*
  section):** the read decomposes into a type-level **source object** fact (a cast-target record shape, never a
  table ; table-boundness is a separate build fact) and a field-level **accessor** fact (a locator gated by the
  source object, paired with a transform that names the function it calls). **Open residue / deferred:**
  producer-polymorphism (a type with disagreeing producer shapes) is asserted away by the uniform-producer
  axiom, and deep accessor recursion (`f(g(read))`) is modeled flat at depth-1 ; both are named but unbuilt,
  to revisit only if a use case forces them.

## Scope

In scope: the model (the lowering to a referentially-closed method-call-graph, the normalization, the
natural keys, the anchor/address primitive, the node and edge relations, the coordinate-and-its-facts
normalized schema (`source` / `target` / `operation` as independent functional dependencies, plus the
`reference` / `referencedTable` / derived `resolvedTable` facts and the `(coordinate, path)` input-coordinate
fact family whose facts roll up into the output operation set, the read-side **source object** (type-level
cast target) and **accessor** (field-level locator + transform) facts, the DataFetcher
and QueryPart-methods as views over them), the target seam topology and its placement rule, and the decision to materialize
the relations as typed in-type collections with a referential-integrity check rather than on a query
engine). Out of scope: the emit
re-platforming that consumes it (R314), any rewrite of R316 slices 1-4 (they are the valid
denormalized projection), and any incremental-query engine for the LSP (a separate, later perf question).
No code in this item beyond what is needed to make the model executable as
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
