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

## The two emit targets

Graphitron's job is to bridge GraphQL-Java and jOOQ, so the two emit-target types are the two libraries'
own:

- **`graphql.schema.DataFetcher`** (the resolve side). **Every** schema coordinate emits exactly one: an
  SDL field has exactly one resolver. Total.
- **`org.jooq.QueryPart`** (the SQL side). **Only table-bound** coordinates emit these, and **one or
  more**: a projected `Field`, a join, a condition, a key projection for a DataLoader, a DML clause. A
  jOOQ `QueryPart` is the base type of every SQL fragment, so the SQL contributions already share a real
  supertype.

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

- **Anchor addressing depth**: does a QueryPart's address name the enclosing anchor coordinate directly,
  and is the up-projection one-hop (immediate parent) or nearest-query-owning-ancestor with inline
  ancestors transparent (a split grandchild under an inline child threading its key to the grandparent's
  SELECT)? This is the one piece derived rather than read from the emit; confirm against
  `TypeFetcherGenerator` / the parent-projection logic.
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

In scope: the model (the DataFetcher + QueryPart lowering, the normalization, the natural keys, the
anchor/address primitive). Out of scope: the emit re-platforming that consumes it (R314), and any rewrite
of R316 slices 1-4 (they are the valid denormalized projection). No code in this item beyond what is
needed to make the model executable as tests (a lowering function and its coverage), if that is split out
at Ready.

## Lineage

Surfaced 2026-06-18 while researching a claim that `SplitTableField` is a variant of `RecordTableField`
that should dissolve. The emit trace refuted the literal "source is a record" reading (split reads a live
catalog row) but confirmed the structural kinship (identical record components, shared batch-load
machinery). Pressing on "what is the real difference" produced the double-duty observation (split also
injects keys into the parent SELECT), then the parent-projection-as-up-flow relation, then the insight
that reifying it as a separate node dissolves the relation, and finally the normalization framing: the
node is a `QueryPart`, the SDL field is the natural key, and the leaf zoo is a denormalized view. The
chain is preserved in the R316 design discussion of the same date.
