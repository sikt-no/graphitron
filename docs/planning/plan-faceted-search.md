# Faceted search on `@asConnection` — `@facet` directive

> **Status:** Draft
>
> Add a `@facet` directive for filter-input fields. The schema-transform stage
> expands each `@asConnection` field's Connection type with a `facets` object
> whose fields mirror the `@facet`-marked filter inputs; the rewrite classifier
> and fetcher emit GROUP-BY aggregation SQL per facet. Delivers the
> "filter ↔ facet" contract the admissions UX needs without nested queries.

## Overview

Covers GG-335 ("Legge til støtte for fasettering av filter") and resolves
SOPP-141 ("Utbedre filtrering, sortering og paginering"), which was closed with
the explicit deferral *"Denne er avsluttet da graphitron vil håndtere dette for
oss via GG-335."*

A schema author marks fields inside a `@asConnection` field's filter input with
`@facet`:

```graphql
type Query {
    filmer(filter: FilmFilter): [Film!]! @asConnection
}

input FilmFilter {
    rating:   [MpaaRating!] @field(name: "RATING")        @facet
    category: [String!]     @field(name: "CATEGORY_NAME") @facet
    title:    String        @field(name: "TITLE")
}
```

Graphitron expands this to:

```graphql
type QueryFilmerConnection {
    totalCount: Int
    facets: QueryFilmerConnectionFacets
    edges: [QueryFilmerConnectionEdge!]!
    nodes: [Film!]!
    pageInfo: PageInfo!
}

type QueryFilmerConnectionFacets {
    rating:   [MpaaRatingFacetValue!]!
    category: [StringFacetValue!]!
}

# Per-scalar named types. value always matches the filter-input field's
# scalar type — so a client filters by the same value it sees in facets,
# no coercion:  filter: { rating: [facetValue.value] }.
type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
type StringFacetValue     { value: String!     count: Int! }
```

> **Deviation from GG-335.** The ticket's Studieprogram example shows
> `type BooleanFacetValue { value: String count: Int }` — `value`
> literally `String` even for the Boolean case. We read this as ticket
> shorthand rather than a considered design: a stringly-typed API
> forces clients to re-parse values before round-tripping them into the
> filter, and gives up GraphQL's primary safety guarantee. This plan
> uses `value: <same scalar as the filter field>` — e.g.
> `BooleanFacetValue.value: Boolean!`. Confirm with the ticket author
> during Draft → Approved review.

At runtime, any selection under `facets` triggers **one extra SQL
statement** — a single `GROUPING SETS` query with per-aggregate
`FILTER (WHERE …)` clauses, one grouping set per selected facet. This
yields all facet counts in a single table scan while preserving the
per-facet *filter-minus-self* semantics. See *SQL emission strategy*
below. Results merge into a single `ConnectionResult` carrier.

## Current State

- `graphitron-schema-transform/MakeConnections` expands `@asConnection`
  list fields into `XConnection`/`XEdge`/`PageInfo` types; nothing there
  knows about facets (`MakeConnections.java:157` —
  `transformListWrapperToConnection`).
- The rewrite classifier picks up the expanded Connection via
  `FieldBuilder.java:350-354`, producing `FieldWrapper.Connection` carrying
  `defaultPageSize` and `connectionName` only
  (`FieldWrapper.java:64-75`).
- `TypeFetcherGenerator.buildQueryConnectionFetcher`
  (`TypeFetcherGenerator.java:519`) emits a single keyset-paginated SELECT
  wrapped in `ConnectionResult`. No secondary aggregation queries.
- `GraphitronWiringClassGenerator.java:67-79` wires Connection types with
  `edges`/`nodes`/`pageInfo` dataFetchers.
- Filter-input types classify through `TypeBuilder.buildInputField`
  (`TypeBuilder.java:562-624`) into `InputField` sealed subclasses
  (`ColumnField`, `ColumnReferenceField`, `PlatformIdField`, `NestingField`).
  None of them carries a facet flag.
- `BuildContext.java:54-74` lists every directive the rewrite reads;
  there is no `DIR_FACET`.
- No execution-test fixture combines `@asConnection` with a filter input
  today — `schema.graphqls:13-20` has `filmsConnection` + `filmsOrderedConnection`
  but only scalar filter args at argument level, not a `@table`-backed filter
  input.

## Desired End State

- New `@facet` directive declared in
  `graphitron-schema-transform/src/main/resources/schema/directives.graphqls`.
- `MakeConnections` expands `@facet`-marked input fields into a `facets` field
  on the generated Connection type and synthesizes one `*ConnectionFacets`
  type plus one reusable `*FacetValue` type per distinct value scalar
  encountered.
- `FieldWrapper.Connection` carries a `FacetSpec` describing each facet
  (input-field name → column + value-scalar type).
- `TypeFetcherGenerator` emits **one** `GROUPING SETS` aggregate query
  covering all selected facets in a single table scan. Each grouping
  set's count column uses a `FILTER (WHERE …)` clause that applies the
  full Connection filter *minus that facet's own predicate*, so a
  selected facet value still shows its siblings' counts.
- `ConnectionResult` carries the facet results; a new `ConnectionHelper.facets`
  static assembles them; `GraphitronWiringClassGenerator` wires the `facets`
  field on each Connection type.
- Execution tests against Sakila confirm counts match plain SQL aggregates,
  including when a facet's own predicate is active.

### Verification

1. New pipeline test in `GraphitronSchemaBuilderTest` classifies a schema with
   `@facet` into a `FieldWrapper.Connection` whose `facets()` is non-empty.
2. New execution test in `graphitron-rewrite-test-spec` asserts facet counts
   match a hand-written jOOQ aggregate over the same filter.
3. Existing `filmsConnection*` tests unchanged (no `@facet` in their filters).

## What We're NOT Doing (v1)

- **Hierarchical / tree facets** — deferred to Phase 5 below. v1 ships
  flat facets only. Emitter and model must *leave room* for the
  extension (see Phase 5); they must not foreclose it.
- **`selected: Boolean!` on facet values.** SOPP-141 mentioned it; GG-335
  omits it. We follow GG-335 in v1.
- **Facets on non-`@asConnection` list fields.** Connection-only; the whole
  filter-↔-facets contract assumes a projectable aggregate shape.
- **Facets on `@facet` fields bound to `@reference` paths, `@condition` joins,
  or composite/`[ID!]` reference fields.** Classifier rejects these at
  validate time; loosening is a follow-up.
- **Cross-facet independence semantics.** v1 applies "all filters except this
  facet's own predicate" per facet (conventional UX expectation). Alternative
  semantics (AND-all, OR-all) are follow-ups if a real use case surfaces.

## Key Discoveries

- **Two-module pipeline.** `graphitron-schema-transform` runs *before*
  `graphitron-rewrite`. `MakeConnections.transform(TypeDefinitionRegistry)`
  is the right place to inject `facets: XFacets` into the generated
  Connection type: by the time the rewrite classifier sees the SDL, the
  facet types already exist and fall out of the regular type-classification
  pass. This avoids having to synthesize SDL inside the rewrite module.
- **`directives.graphqls` lives in schema-transform** but the rewrite reads
  it — `BuildContext.java:74` lists every directive name used by the
  classifier. Adding `DIR_FACET` there is mechanical.
- **`FieldWrapper.Connection` is a record** with no public builders; adding
  a `facets` member means every construction site (`FieldBuilder.java:350`
  and the structural-detection fallback) must pass the new argument.
- **Per-facet self-predicate stripping** needs the `Condition` to be built
  compositionally. `buildConditionCall` in `TypeFetcherGenerator` currently
  folds all argument conditions into one — we'll need per-column conjuncts
  kept addressable so one can be dropped when emitting each facet query.
- **Facet value types are cross-schema reusable.** `StringFacetValue`,
  `BooleanFacetValue`, `IntFacetValue`, `<Enum>FacetValue` — one per
  value scalar encountered across the whole schema, not per connection.
  Synthesize-once with a registry keyed on the value type name.

## Implementation Approach

Four v1 phases plus Phase 5 deferred, in strict order — each phase
leaves the build green and existing tests passing. No phase adds
user-observable behaviour until Phase 3; Phase 4 is test coverage.
Phase 5 ships hierarchical facets after v1 lands.

| Phase | Module | What lands |
|---|---|---|
| 1 | `graphitron-schema-transform` | `@facet` directive definition; `MakeConnections` synthesizes facet types + `facets` field on the Connection |
| 2 | `graphitron-rewrite` (classifier) | `FieldWrapper.Connection` carries `FacetSpec`; validator rejects misuse |
| 3 | `graphitron-rewrite` (emitter) | Fetcher emits per-facet `GROUP BY`; helper + wiring expose the new field |
| 4 | `graphitron-rewrite-test-spec` | Execution tests against Sakila |
| 5 | deferred | Hierarchical facets (`includeChildrenOf` + `parentValue`) |

---

## SQL emission strategy — one `GROUPING SETS` query per Connection request

The facet aggregate is a **separate** query from the paginated
edges/nodes — it joins no rows into that query and shares no WHERE
clause with it. This decoupling is what makes a single-scan, multi-facet
aggregate viable: the facet query is free to compute per-facet counts
under per-facet predicates without perturbing pagination.

The contract: when a user has filtered `rating: [PG]`, the `rating`
facet must still show counts for *all* ratings (so the user can pivot
their selection). Every *other* facet (`rental_duration`, …) must show
counts for films matching `rating = PG`. Formally: each facet computes a
count grouped on its column under the *full filter minus that facet's
own predicate*. The paginated `edges`/`nodes` query is unaffected and
continues to apply the full filter unchanged.

### v1 default: one `GROUPING SETS` query with per-aggregate `FILTER`

```sql
SELECT
    GROUPING(rating)           AS g_rating,
    GROUPING(rental_duration)  AS g_rental,
    rating,
    rental_duration,
    COUNT(*) FILTER (WHERE <cond_minus_rating>) AS rating_count,
    COUNT(*) FILTER (WHERE <cond_minus_rental>) AS rental_count
FROM film
GROUP BY GROUPING SETS ((rating), (rental_duration));
```

Each grouping set yields one row per distinct value of the facet column
it groups on. Per-aggregate `FILTER (WHERE …)` clauses apply the
corresponding filter-minus-self predicate on the same scan — so the
"one WHERE shared across grouping sets" objection that rules out plain
`GROUPING SETS` no longer applies: there *is* no outer WHERE; the
per-set predicates live on the aggregates.

Rows are demultiplexed client-side via the `GROUPING()` flags: a row
with `g_rating = 0, g_rental = 1` carries a `rating` facet value
(`rating_count` is the count; `rental_count` is meaningless and
ignored). This is a small, mechanical decoder on the Java side.

One table scan covers *all* facets in the Connection request, whether
their value domains are bounded (enums, Booleans) or open-ended
(strings, Ints, custom scalars). No pre-query for DISTINCT values is
needed — `GROUPING SETS` discovers them in the same scan as the counts.

### Round-trips and scans

Two round-trips per Connection request that selects any facet: one
for edges/nodes, one for the facet aggregate. When no facet field is
in the GraphQL selection set, the aggregate query is skipped entirely
— one round-trip, identical to today.

A selection gate still matters, but at the *grouping-set* level: a
facet whose field isn't selected contributes no grouping set and no
aggregate column, shrinking the single query.

### Strategy comparison

| Strategy | Round-trips | Scans per facet query | Filter-minus-self per facet | Portability | Verdict |
|---|---|---|---|---|---|
| **A. `GROUPING SETS` + per-aggregate `FILTER`** | 2 | 1 | Yes — each set's aggregate owns its `FILTER` predicate | PostgreSQL ✓ (Oracle ✓; SQL:2003) | **v1 default** |
| **B. One `GROUP BY` per facet** | 1 + N | N | Trivially yes — each query owns its WHERE | All targets | Fallback if A unavailable; v2 optimization not needed |
| **C. `UNION ALL` of per-facet `GROUP BY`s** | 2 | Up to N (planner-dependent; CTE materialization helps) | Yes — each branch owns its WHERE | All targets | Redundant once A lands; same round-trip, worse scan count |
| **D. Plain `GROUPING SETS`** (shared outer WHERE) | 2 | 1 | **No** — single WHERE shared across sets | PostgreSQL, Oracle | Rejected — collapses the facet whose filter is active |
| **E. Window fns (`COUNT() OVER (PARTITION BY col)`)** | 2 | 1 per facet column (cartesian issue across facets) | Possible per-facet via `FILTER (WHERE …) OVER (PARTITION BY …)` | All targets | Subsumed by A; no advantage and awkward for multi-facet |
| **F. Conditional aggregation on known values** (`COUNT(*) FILTER (WHERE col = 'G')` etc.) | 2 | 1 | Yes | PostgreSQL `FILTER` / SQL:2003 | Rejected — requires pre-enumerated value domains; A subsumes it without that constraint |

**Why plain `GROUPING SETS` (strategy D) still fails.** A single shared
outer WHERE applied before the grouping sets collapses any facet whose
predicate is active: if the WHERE has `rating = 'PG'` then the `rating`
grouping set only sees PG rows and the facet collapses to one bucket.
Strategy A avoids this by dropping the outer WHERE entirely and moving
each set's predicate into the `FILTER` clause of *its* count aggregate.

**Why window functions (strategy E) are subsumed.** A shape like
`SELECT DISTINCT col, COUNT(*) FILTER (WHERE cond_minus_col) OVER
(PARTITION BY col) FROM film` does give you one-scan, filter-minus-self
counts for a *single* facet. But combining multiple facets into one
query runs into cartesian-cardinality issues across distinct facet
columns. `GROUPING SETS` is the natural fit for multi-facet.

**Why `UNION ALL` (strategy C) is redundant once A lands.** It has the
same round-trip count as A (one aggregate query), but PostgreSQL will
typically scan the base table once per branch — the CTE-materialization
path that would deduplicate scans has its own cost. A is strictly at
least as good on scans, with a cleaner result shape.

### Typed-value shape inside one query

With `value: <filter scalar>` (this plan's choice), each facet's value
column has its own Java/JDBC type — `MpaaRating`, `Boolean`, `Integer`,
`String`. Because strategy A keeps each facet's value in its *own*
column position (`rating`, `rental_duration`, …), no cross-facet type
unification is needed. jOOQ's `Field<T>` on each column preserves the
native Java type; decoding reads only the column corresponding to the
row's active grouping set.

### Fallback to B

If a target dialect later added to Graphitron lacks `GROUPING SETS`
support (or its `FILTER` clause), the emitter falls back to strategy B
— one `GROUP BY` query per facet — at no cost to the classifier or
transform. The choice lives inside the fetcher.

---

## Phase 1 — Directive + schema-transform expansion

### Overview

Declare `@facet` and teach `MakeConnections` to synthesize a `facets` field
on Connection types plus the supporting `*Facets` and `*FacetValue` types.

### Changes

#### `graphitron-schema-transform/src/main/resources/schema/directives.graphqls`

Add:

```graphql
"""
Marks a filter-input field as a facet on the enclosing `@asConnection`
field's generated Connection type. The Connection type gains a
`facets: XConnectionFacets` field; each `@facet`-marked input field
becomes an entry there, returning `[XFacetValue!]!` with per-value
counts.

Only valid on fields of an input type used as the filter input of an
`@asConnection`-bearing field. The input field must be bound to a
column via `@field(name:)` (reference / condition / composite-key
bindings are rejected in v1).
"""
directive @facet on INPUT_FIELD_DEFINITION
```

#### `MakeConnections.java`

In `transformListWrapperToConnection` (line 157), after the Connection
type is built, scan the wrapped field's arguments for a filter input.
For every input field carrying `@facet`:

1. Resolve the value scalar (the GraphQL type of the input field, stripped
   of list/non-null). For scalar/enum leaves, this is the facet value type.
2. Ensure a `{Scalar}FacetValue` type exists in the registry; add it if
   not. `value` carries the **same scalar as the filter-input field**,
   preserving round-trip symmetry:
   ```graphql
   type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
   type StringFacetValue     { value: String!     count: Int! }
   type BooleanFacetValue    { value: Boolean!    count: Int! }
   type IntFacetValue        { value: Int!        count: Int! }
   ```
   A client feeds `facetValue.value` straight back into the filter
   input with no conversion. Custom scalars synthesize
   `<CustomScalar>FacetValue` on demand the same way.
3. Build a `{ConnectionName}Facets` type with one non-null list field per
   `@facet` input, field name matching the input field name.
4. Add `facets: {ConnectionName}Facets` to the Connection type.

Keep the existing `totalCountFieldInConnectionsEnabled` /
`nodesFieldInConnectionsEnabled` feature-flag plumbing; `facets` is
unconditional — its presence on a given Connection is driven by whether
the filter input has any `@facet` fields.

If the wrapped field has no filter input, or the filter input has no
`@facet` fields, emit the Connection unchanged. No error, no warning.

### Success Criteria

- [ ] `mvn test -pl :graphitron-schema-transform` — new
      `MakeConnectionsFacetTest` pipes an SDL with `@facet` through
      `MakeConnections.transform()` and asserts: Connection type has a
      `facets` field, `{Name}Facets` type exists with one list field per
      `@facet`, one `{Scalar}FacetValue` type per distinct value scalar,
      each with `value` + `count` fields.
- [ ] Existing `asConnectionRewriterTest` fixtures unchanged.
- [ ] `mvn compile -pl :graphitron-rewrite -Pquick` — the rewrite
      still builds against the expanded SDL (facets types are just
      unclassified-but-tolerated object types at this phase; the classifier
      leaves them as `UnclassifiedType`, which validate-mojo won't flag
      until they're *referenced* from a classified field — and they aren't
      yet, because nothing reads `FieldWrapper.Connection.facets`).

> **Note on classifier tolerance.** If `UnclassifiedType` on the synthesized
> facets types does trigger a validator error in isolation, add an allowlist
> entry keyed on the `FacetValue` / `Facets` suffix pattern until Phase 2
> supplies real classification. Verify during Phase 1 implementation.

---

## Phase 2 — Classifier: `FacetSpec` on `FieldWrapper.Connection`

### Overview

The rewrite classifier currently flattens `@asConnection` into a
`FieldWrapper.Connection` with only pagination metadata. Phase 2 teaches
it to *also* read the filter input's `@facet` directives and carry the
resulting specs on the wrapper, so the emitter (Phase 3) has everything
it needs without re-parsing SDL.

### Changes

#### `BuildContext.java:54-74` — new directive constant

```java
static final String DIR_FACET = "facet";
```

#### `model/FieldWrapper.java`

Extend the `Connection` record with a facets list:

```java
record Connection(
    boolean connectionNullable,
    boolean itemNullable,
    int defaultPageSize,
    String connectionName,
    java.util.List<FacetSpec> facets   // empty when no @facet fields
) implements FieldWrapper { ... }
```

Keep both existing constructors; have them forward `List.of()` for the
new parameter. Both Connection construction sites in `FieldBuilder`
(`:350-354` and the structural-detection fallback) get an extra argument.

#### New `model/FacetSpec.java`

```java
public record FacetSpec(
    String inputFieldName,    // e.g. "rating"
    String columnName,        // e.g. "RATING"
    String valueTypeName,     // e.g. "MpaaRating"
    String facetValueTypeName // e.g. "MpaaRatingFacetValue"
) {}
```

Carries exactly what the emitter needs: which column to `GROUP BY`, what
GraphQL type the scalar value has (for wiring the `value` field), and
what `*FacetValue` object type to instantiate.

#### `FieldBuilder` — populate `facets`

When building a `FieldWrapper.Connection`, walk the wrapped field's
arguments; for each argument whose type is an input type containing
`@facet`-marked fields:

1. Each `@facet` field must also carry `@field(name:)` (rejected
   otherwise with `UnclassifiedField` + a message naming the field).
2. Each `@facet` field's GraphQL leaf scalar/enum is its `valueTypeName`.
3. Derive `facetValueTypeName` as `{Scalar}FacetValue` — this must match
   the name `MakeConnections` produced in Phase 1 (single source of
   truth: a shared `FacetNaming.facetValueTypeName(scalar)` helper placed
   in a module both can reach; or duplicated + asserted equal by a
   cross-module test — see Open Questions).

Reject at classify time:

- `@facet` on a non-`@field`-bound input field (reference path,
  condition, nesting) → `UnclassifiedField`.
- `@facet` on a field whose enclosing input type is not reached via an
  `@asConnection` field → `UnclassifiedField` (the expanded `facets`
  field is dead schema otherwise).

#### `GraphitronSchemaValidator`

No new validator rule in Phase 2 — the classifier's rejections above
propagate naturally. If Phase 1's note about `UnclassifiedType` allowlisting
was needed, remove the allowlist here: the synthesized facet types are
now reachable from a classified field.

### Success Criteria

- [ ] `mvn test -pl :graphitron-rewrite -Pquick` — existing tests pass.
- [ ] New pipeline test: schema with two `@facet` inputs on a filter →
      classified `Connection.facets()` has two entries with correct
      column names and value types.
- [ ] New pipeline test: `@facet` on a `@reference`-bound input field
      → `UnclassifiedField` with a specific error message.
- [ ] `VariantCoverageTest` still passes — no new sealed leaf added
      (this phase only extends an existing record).

---

## Phase 3 — Emitter: single `GROUPING SETS` aggregate + wiring

### Overview

`TypeFetcherGenerator.buildQueryConnectionFetcher` (`:519`) emits one
extra `GROUPING SETS` SELECT that covers every selected facet in a
single table scan, with per-aggregate `FILTER` clauses carrying each
facet's filter-minus-self predicate. Results are demultiplexed via
`GROUPING()` flags and packaged into an extended `ConnectionResult`;
`ConnectionHelper` gets a `facets` accessor;
`GraphitronWiringClassGenerator` adds a `facets` dataFetcher.

### Changes

#### `ConnectionResult` (generated carrier)

Add a `Map<String, List<FacetValueRow>>` field keyed on input-field name,
plus a nested `FacetValueRow(Object value, int count)` record. Update the
constructor and `trimmedResult()` accordingly.

#### `ConnectionHelperClassGenerator`

Add a `facets(ConnectionResult, env)` static that returns a
`Map<String, List<Map<String, Object>>>` shaped for GraphQL-Java. Each
inner map is `{"value": <typed>, "count": <int>}`. The wiring for the
concrete `*FacetValue` types — one TypeRuntimeWiring per value scalar —
trivially exposes `value` and `count` by property name.

#### `TypeFetcherGenerator.buildQueryConnectionFetcher`

Per the *SQL emission strategy* section above: one `GROUPING SETS`
query with per-aggregate `FILTER` clauses carrying filter-minus-self
for each selected facet. The paginated `edges`/`nodes` query is
unchanged.

After the main SELECT is emitted (`:519–`), determine the set of
facets present in the GraphQL selection set (a facet whose field is
not selected contributes nothing):

- If the selected-facets set is empty — or if `conn.facets()` is
  empty — emit no aggregate query. The fetcher stays byte-identical
  to today's output in that case.

Otherwise, emit one aggregate query. Let `selectedFacets` be the
subset of `conn.facets()` that the client actually asked for.

1. **Per-facet conditions.** For each facet `f` in `selectedFacets`,
   build `cond_minus_f` — the full argument-derived Condition with
   `f`'s own predicate omitted. This requires `buildConditionCall` to
   either (a) expose per-argument conjuncts or (b) accept a
   `skip: String` parameter naming the input-field to omit. Option (b)
   is the smaller edit; take it unless implementation finds Phase 2
   already has the per-conjunct data on the wrapper.

2. **Grouping sets and aggregate columns.** Emit (one single-column
   grouping set per selected facet, one `filterWhere` count per
   selected facet):
   ```java
   Field<?>[] facetCols = selectedFacets.stream()
       .map(f -> table.field(f.columnName()))
       .toArray(Field[]::new);

   Field<Integer>[] groupingFlags = Arrays.stream(facetCols)
       .map(DSL::grouping)
       .toArray(Field[]::new);

   Field<Integer>[] counts = selectedFacets.stream()
       .map(f -> DSL.count().filterWhere(condMinusSelf(f))
                     .as(f.inputFieldName() + "_count"))
       .toArray(Field[]::new);

   Field<?>[][] groupingSets = Arrays.stream(facetCols)
       .map(c -> new Field<?>[]{ c })
       .toArray(Field[][]::new);

   var facetRows = dsl
       .select(concat(groupingFlags, facetCols, counts))
       .from(table)
       .groupBy(DSL.groupingSets(groupingSets))
       .fetch();
   ```
   No outer `WHERE` on this query — each facet's predicate lives in
   its own `filterWhere` on the count aggregate. No SQL-layer
   coercion on value columns: each `facetCol` keeps its native type
   (enum, Boolean, Int, String); jOOQ's generated `Field<T>` carries
   the Java type corresponding to the filter-input field's scalar.
   The existing `graphql-java` scalar coercers serialize to the wire
   — enums as enum name, Booleans as boolean, ints as int —
   identical to how these columns surface on the Connection's
   `nodes` path.

3. **Demultiplex rows into the facets map.** For each row, find the
   single facet whose `GROUPING()` flag is `0` (its column carries
   the value). Read the value column and the corresponding
   `_count` column; append `(value, count)` to the facets map
   under that input-field name.

   Sketch:
   ```java
   Map<String, List<FacetValueRow>> facets = new HashMap<>();
   for (Record row : facetRows) {
       for (FacetSpec f : selectedFacets) {
           if (row.get(groupingFlag(f)) == 0) {
               Object value = row.get(table.field(f.columnName()));
               int count = row.get(f.inputFieldName() + "_count", Integer.class);
               facets.computeIfAbsent(f.inputFieldName(), k -> new ArrayList<>())
                     .add(new FacetValueRow(value, count));
               break;  // only one grouping set is active per row
           }
       }
   }
   ```

4. Attach the facets map to the `ConnectionResult`.

**Dialect fallback.** If the target dialect does not support
`GROUPING SETS` + `FILTER` (Graphitron targets PostgreSQL today, where
both are supported), fall back to strategy B from the SQL section —
one `GROUP BY` query per selected facet. The fallback is an emitter
decision only; model and classifier are unaffected. Defer actually
writing the fallback until a dialect that needs it is added.

#### `GraphitronWiringClassGenerator.java:67-79`

Where the Connection's edges/nodes/pageInfo wiring is built, append a
`facets` dataFetcher that calls `ConnectionHelper.facets(...)`. Also
emit one `TypeRuntimeWiring` per `*FacetValue` type encountered —
dataFetcher for `value` and `count` are property-name default fetchers;
only the type registration is required so GraphQL-Java knows the type
exists.

### Success Criteria

- [ ] `mvn verify -Pquick` on the whole tree.
- [ ] Schemas *without* `@facet` emit unchanged fetchers (structural
      diff test: classify pre- and post-patch SDL with no `@facet`,
      assert identical `TypeSpec` for the fetcher method).
- [ ] Wiring test: a Connection with `@facet` fields has a `facets`
      dataFetcher registered; the `*FacetValue` types exist in the
      wiring's known type set.

---

## Phase 4 — Execution tests

### Overview

Add a Sakila-backed execution fixture combining `@asConnection` with a
`@facet`-bearing filter input. Prove per-facet counts match direct jOOQ
aggregates and that selecting one facet value leaves other facet counts
unchanged.

### Changes

#### `graphitron-rewrite-test-spec/.../graphql/schema.graphqls`

Add (alongside existing `filmsConnection`):

```graphql
type Query {
    # ... existing ...
    filmsFaceted(filter: FilmFacetFilter, first: Int, after: String): [Film!]!
        @asConnection @defaultOrder(primaryKey: true)
}

input FilmFacetFilter @table(name: "film") {
    rating:       [MpaaRating!] @field(name: "RATING")          @facet
    languageName: [String!]     @field(name: "LANGUAGE_NAME")   @facet
}
```

`LANGUAGE_NAME` doesn't exist as a plain column on `film` — use a column
that does: pick `RATING` + a second scalar like `RENTAL_DURATION`
(Integer) so both an enum-scalar facet and an Integer-scalar facet are
exercised. Values surface as native types over the wire — enum values
deserialize as `MpaaRating.PG`, integers as `3`. Assertions compare
typed values; this is also the test that pins the round-trip property
(`filter: { rating: [facetValue.value] }` works with no coercion).
Final column choice finalized during implementation.

#### Execution tests

Three cases, each running through a real Sakila database:

1. **No filter, facets populated.** Assert `facets.rating` counts match
   `SELECT rating, COUNT(*) FROM film GROUP BY rating`.
2. **Filter on one facet, other facet unchanged.** Set `rating: [PG]`.
   Assert `facets.rating` still shows all ratings with their global
   counts (facet-independence), and `facets.rentalDuration` counts
   equal `SELECT rental_duration, COUNT(*) FROM film WHERE rating='PG'`.
3. **Multiple facets filtered.** Confirm each facet's counts ignore
   only its own predicate.

Round-trip assertions: one query for edges/nodes, one aggregate query
for all selected facets. Two round-trips total, regardless of how many
facets are selected — lock this number in to catch regressions that
would re-introduce per-facet round-trips. When no facet field is in
the selection set, the aggregate is skipped: one round-trip.

### Success Criteria

- [ ] All three execution cases pass against PostgreSQL Sakila.
- [ ] `mvn verify -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`
      clean.
- [ ] JDBC round-trip count matches the expected value per case: 2
      when any facet is selected (edges + single aggregate), 1 when
      none is.

---

## Phase 5 — Hierarchical facets (deferred, scoped here)

### Overview

GG-335 is explicit about the tree-facet UX (the Studieprogram example:
Fakultet → Institutt → Gruppe). The ticket rules out nested query
shapes in favour of a flat response + argument-driven expansion:

```graphql
# Initial page — only top-level facets.
query OpenFacetRoot {
    studieprogram {
        nodes { ... }
        facets { studieprogramkoder { value count parentValue } }
    }
}

# User expands "Fakultet for yyyy" (value 2).
query OpenFacet2 {
    studieprogram {
        facets(includeChildrenOf: [2]) { ... }
    }
}

# User then expands "Institutt y" (value 4, parent 2).
query OpenFacet4 {
    studieprogram {
        facets(includeChildrenOf: [2, 4]) { ... }
    }
}
```

Flat response with `parentValue` pointers — no nested query structure
under `facets`. This is a **hard design constraint** from the ticket:
*"Jeg tror det er viktig at vi unngår nøstede spørringsstrukturer under
`facets`, men at vi heller tar inn argumenter for hva som skal
inkluderes og gir flate resultat."*

### Why this is Phase 5, not v1

1. Requires modelling a facet's parent relation — either via a new
   `@facet(parent: "<otherFacetField>")` arg or by inferring from the
   referenced column's FK path. Both call for schema-design alignment
   with the supergraph team (ticket explicitly notes this).
2. Requires the `*FacetValue` shape to grow
   `parentValue: <same scalar as value>` (nullable, NULL at root) and
   the per-facet field to accept `facets(includeChildrenOf: [<that
   scalar>])`. v1's shape must leave room: each `*FacetValue` is an
   independent type so Phase 5 can add `parentValue` additively
   without breaking wire compat. Argument name `includeChildrenOf` is
   reserved now so existing queries don't collide later.
3. SQL: each requested level adds one grouping set to the same
   `GROUPING SETS` query, with `parent_id IN includeChildrenOf` in
   its `FILTER` clause — still the same v1 shape. No new SQL
   strategy needed; ROLLUP remains wrong for the same
   filter-minus-self reason.

### What Phase 1–3 must preserve

- `*FacetValue` types are *not sealed* — Phase 5 adds `parentValue` as a
  nullable field without breaking wire compat.
- `*ConnectionFacets` field uses position (by input-field name) so
  Phase 5's `includeChildrenOf` argument can attach without renaming.
- `FacetSpec` (model) has room for `parentFacet: Optional<FacetSpec>`
  without changing the constructor signature every downstream record
  uses. Consider keeping it a sealed interface over `FlatFacetSpec` /
  `HierarchicalFacetSpec` — but only add that split in Phase 5; v1
  uses the flat record.

### Success Criteria

Phase 5 is deferred — no v1 success criteria. Carved out here so
reviewers can confirm the v1 design does not foreclose it.

---

## Testing Strategy

- **Unit:** none required — no new reflection / catalog probes.
- **Pipeline (schema-transform):** `MakeConnectionsFacetTest` covers
  expansion of `@facet` into `Facets` + `FacetValue` types, and no-op
  when no `@facet` is present.
- **Pipeline (rewrite):** two new `GraphitronSchemaBuilderTest` cases —
  `@facet` classification success and `@facet` rejection on non-`@field`
  bindings.
- **Wiring:** assert `facets` dataFetcher and `*FacetValue` type
  registrations in the generated wiring class.
- **Execution:** three Sakila cases as above.
- **Regression:** existing `filmsConnection*` tests unchanged; structural
  diff confirms fetcher output is byte-identical when `@facet` is absent.

## Resolved design decisions

- **Facet-value shape — per-scalar typed, matching the filter field.**
  `MpaaRatingFacetValue.value: MpaaRating!`,
  `BooleanFacetValue.value: Boolean!`, etc. Rationale: a facet value
  is a candidate filter value; typing them the same preserves
  round-trip symmetry (`filter: { x: [facetValue.value] }` with no
  coercion) and keeps GraphQL's type-safety guarantee. **This
  overrides the literal GG-335 text** (which shows
  `BooleanFacetValue.value: String` — read as ticket-writing
  shorthand rather than considered design). Flag for confirmation
  during Draft → Approved review.
- **Hierarchical shape (Phase 5).** Flat response +
  `includeChildrenOf: [<parent value type>]` argument +
  `parentValue` pointer typed to match. No nested query structures
  under `facets`. GG-335 is explicit on the no-nesting rule.
  Implementation deferred to Phase 5; v1 types must not foreclose it.
- **Per-facet independence semantics.** Every facet's counts reflect
  the base filter *minus that facet's own predicate* — enabling a
  user to change their selection within the same facet without
  collapsing siblings. Ticket's user-interaction walkthrough assumes
  it; the SQL strategy section above builds on it.
- **No nested `facets { parent { children { ... } } }` structure.**
  Hard constraint from ticket: performance + query-shape driver.

## Open Questions

1. **Phase 2 naming-collision between transform and rewrite.** Both
   modules must agree on `{Scalar}FacetValue` / `{Connection}Facets`
   name derivation. Options: (a) extract a small `facet-naming` module
   depended on by both, (b) duplicate + cross-module assertion test.
   (a) is cleaner; (b) is faster. Decide during Phase 1 review.

2. **Aggregate-query cost at high facet counts.** v1 packs all
   selected facets into one `GROUPING SETS` query. Cardinality scales
   with the union of distinct-value counts across selected facet
   columns (each facet contributes one row per distinct value) —
   typically small for enum/Boolean facets, potentially larger for
   open-ended string facets. Measure during Phase 4 on the Sakila
   fixture. If a pathological case emerges (e.g. a high-cardinality
   string facet combined with several others), an optimization path
   is to split into two aggregate queries — bounded-domain facets in
   one, each open-ended facet in its own — but only if real profiling
   data justifies the emitter complexity.

3. **Facets on columns reached through FK joins.** v1 rejects
   `@facet` on `@reference`-bound input fields. GG-335's Studieprogram
   hierarchical example implies faceting over a joined parent
   (Fakultet → Institutt). Lifting this restriction is entangled with
   Phase 5; confirm it can stay rejected until then.

## References

- Jira: [GG-335](https://sikt.atlassian.net/browse/GG-335) — Graphitron
  ticket with the target SDL shape.
- Jira: [SOPP-141](https://sikt.atlassian.net/browse/SOPP-141) —
  admissions initiative; closed in favour of GG-335.
- `docs/paginated-fields.md` — current `@asConnection` machinery.
- `graphitron-schema-transform/.../MakeConnections.java:157` —
  `transformListWrapperToConnection` hook point.
- `graphitron-rewrite/.../FieldBuilder.java:350-354` —
  `FieldWrapper.Connection` construction site.
- `graphitron-rewrite/.../TypeFetcherGenerator.java:519` —
  `buildQueryConnectionFetcher`.
- `graphitron-rewrite/.../GraphitronWiringClassGenerator.java:67-79` —
  Connection wiring emission.
- `graphitron-rewrite/.../BuildContext.java:54-74` — directive
  constants.
