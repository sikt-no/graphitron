# Faceted search on `@asConnection` — `@facet` directive

> **Status:** Draft
>
> Add a `@facet` directive for filter-input fields. The schema-transform stage
> expands each `@asConnection` field's Connection type with a `facets` object
> whose fields mirror the `@facet`-marked filter inputs; the rewrite classifier
> and fetcher emit one `UNION ALL` aggregate query per Connection request, with
> each arm computing one facet's counts under its filter-minus-self predicate.
> Phase 1 spike confirmed this shape over `GROUPING SETS`
> ([spike report](spike-faceted-search-sql.md)). Delivers the "filter ↔ facet"
> contract the admissions UX needs without nested queries.

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
statement** — a `UNION ALL` of per-facet `GROUP BY` arms, one arm per
selected facet. Each arm applies the full Connection filter *minus
that facet's own predicate*, so a selected facet value still shows
its siblings' counts. Postgres plans each arm independently (bitmap
index scans on selective filters) and executes arms concurrently via
`Parallel Append`. See *SQL emission strategy* below. Results merge
into a single `ConnectionResult` carrier.

## Current State

- `graphitron-schema-transform/MakeConnections` expands `@asConnection`
  list fields into `XConnection`/`XEdge`/`PageInfo` types; nothing there
  knows about facets (`MakeConnections.java:157` —
  `transformListWrapperToConnection`).
- The rewrite classifier picks up the expanded Connection via
  `FieldBuilder.java:356,370`, producing `FieldWrapper.Connection` carrying
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
  today — `schema.graphqls:17-24` has `filmsConnection` + `filmsOrderedConnection`
  but only scalar filter args at argument level, not a `@table`-backed filter
  input.
- Filter-input conditions are emitted via `WhereFilter` (sealed into
  `GeneratedConditionFilter` / `ConditionFilter`, one per
  `@condition`-bound method). `TypeFetcherGenerator.buildConditionCall`
  (`:494-509`) iterates filters, emitting one
  `condition = condition.and(Filters.method(table, args...))` per
  filter. The filter method itself ANDs all its fields internally — so
  the fetcher cannot surgically drop a single input-field's predicate
  by passing a skip name; the filter-class generator owns that
  assembly. This shapes Phase 4's condition-minus-self strategy (see
  below).

## Desired End State

- New `@facet` directive declared in
  `graphitron-schema-transform/src/main/resources/schema/directives.graphqls`.
- `MakeConnections` expands `@facet`-marked input fields into a `facets` field
  on the generated Connection type and synthesizes one `*ConnectionFacets`
  type plus one reusable `*FacetValue` type per distinct value scalar
  encountered.
- `FieldWrapper.Connection` carries a `FacetSpec` describing each facet
  (input-field name → column + value-scalar type).
- `TypeFetcherGenerator` emits **one** `UNION ALL` aggregate query per
  Connection request, one arm per selected facet. Each arm's `WHERE`
  applies the full Connection filter *minus that facet's own
  predicate*, so a selected facet value still shows its siblings'
  counts. Each arm can use per-facet indexes; `Parallel Append`
  executes arms concurrently.
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

- **Hierarchical / tree facets** — deferred to Phase 6 below. v1 ships
  flat facets only. Emitter and model must *leave room* for the
  extension (see Phase 6); they must not foreclose it.
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

Five v1 phases plus Phase 6 deferred, in strict order — each phase
leaves the build green and existing tests passing. No phase adds
user-observable behaviour until Phase 4; Phase 5 is test coverage.
Phase 1 is a measurement spike that validates or redirects the SQL
strategy *before* emitter work begins; its deliverables are a report
plus any plan revisions it motivates. Phase 6 ships hierarchical
facets after v1 lands.

| Phase | Module / artefact | What lands |
|---|---|---|
| 1 | `docs/planning/plan-faceted-search.md` + hand-written SQL | Spike — benchmark SQL strategies against Sakila; confirm or swap v1 default; resolve NULL + ordering Open Questions |
| 2 | `graphitron-schema-transform` | `@facet` directive definition; `MakeConnections` synthesizes facet types + `facets` field on the Connection |
| 3 | `graphitron-rewrite` (classifier) | `FieldWrapper.Connection` carries `FacetSpec`; validator rejects misuse |
| 4 | `graphitron-rewrite` (emitter) | Fetcher emits the spike-chosen aggregate shape; helper + wiring expose the new field |
| 5 | `graphitron-rewrite-test-spec` | Execution tests against Sakila |
| 6 | deferred | Hierarchical facets (`includeChildrenOf` + `parentValue`) |

---

## SQL emission strategy — one `UNION ALL` facet query per Connection request

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

### v1 default: `UNION ALL` of per-facet `GROUP BY` arms

```sql
SELECT 'rating' AS facet, rating::text AS value, COUNT(*) AS cnt
FROM film
WHERE <non-facet-filters> AND <all-facet-filters-except-rating>
GROUP BY rating
UNION ALL
SELECT 'rental_duration', rental_duration::text, COUNT(*)
FROM film
WHERE <non-facet-filters> AND <all-facet-filters-except-rental>
GROUP BY rental_duration
ORDER BY facet, cnt DESC, value;
```

One arm per facet. Each arm applies every filter *except its own*
(filter-minus-self). Results concatenate into a single shape that the
Java decoder demultiplexes by the `facet` label column; `value::text`
unifies heterogeneous facet column types into one SQL type.

Phase 1 spike ([report](spike-faceted-search-sql.md)) measured this
shape against four alternatives on a 200 000-row dataset. `UNION ALL`
wins or ties every scenario because Postgres plans each arm
independently — selective filters pick per-facet indexes; the
`Parallel Append` executor runs arms concurrently. The originally
proposed `GROUPING SETS + FILTER` form (now "strategy A" below) is
invalid syntax in Postgres (`GROUPING()` disallowed inside `FILTER`);
its CASE-dispatched workaround parses but loses on every measured
scenario — it forces a full table seq scan regardless of filter
selectivity, which is exactly the wrong trade-off for selective UIs.

### Round-trips and scans

Two round-trips per Connection request that selects any facet: one
for edges/nodes, one for the facet aggregate. When no facet field is
in the GraphQL selection set, the aggregate query is skipped entirely
— one round-trip, identical to today.

A selection gate still matters per-arm: a facet whose field isn't
selected contributes no `UNION ALL` arm and no aggregate, shrinking
the single query.

### Strategy comparison

| Strategy | Round-trips | Scans per facet query | Filter-minus-self per facet | Portability | Verdict |
|---|---|---|---|---|---|
| **A. `GROUPING SETS` + per-aggregate `FILTER`** | 2 | 1 full seq scan | Yes (requires CASE-dispatched aggregates — `GROUPING()` is banned inside `FILTER` in Postgres) | PostgreSQL (CASE form only), Oracle ✓ | Rejected by Phase 1 spike — never fastest, loses per-facet indexes |
| **B. One `GROUP BY` per facet** | 1 + N | N (index-capable per arm) | Trivially yes — each query owns its WHERE | All targets | v2 fallback when facet count makes UNION ungainly (~10+) |
| **C. `UNION ALL` of per-facet `GROUP BY`s** | 2 | N (index-capable per arm; Parallel Append runs them concurrently) | Yes — each branch owns its WHERE | All targets | **v1 default** |
| **D. Plain `GROUPING SETS`** (shared outer WHERE) | 2 | 1 | **No** — single WHERE shared across sets | PostgreSQL, Oracle | Rejected — collapses the facet whose filter is active |
| **E. Window fns (`COUNT() OVER (PARTITION BY col)`)** | 2 | 1 per facet column (cartesian issue across facets) | Possible per-facet via `FILTER (WHERE …) OVER (PARTITION BY …)` | All targets | Rejected — multi-facet grid-cartesian-blows-up |
| **F. Conditional aggregation on known values** (`COUNT(*) FILTER (WHERE col = 'G')` etc.) | 2 | 1 | Yes | PostgreSQL `FILTER` / SQL:2003 | Rejected — requires pre-enumerated value domains; fails for open-ended facets |

**Why shape C wins over shape A.** Shape A forces a single seq scan
because GROUPING SETS aggregates depend on every row — no per-facet
WHERE, no per-facet index. Shape C's arms are independent queries;
each one's WHERE lets the planner pick a bitmap index scan when
filters are selective. On the spike dataset:

- S3 (multi-filter, both facets indexed): C 27 ms vs A 38 ms.
- S5 (open-ended prefix): C 27 ms vs A 51 ms.
- S1 (no filter): C 29 ms vs A 32 ms — roughly tied.

Shape C never loses. Shape A never wins.

**Why plain `GROUPING SETS` (strategy D) still fails.** A single shared
outer WHERE applied before the grouping sets collapses any facet whose
predicate is active: if the WHERE has `rating = 'PG'` then the `rating`
grouping set only sees PG rows and the facet collapses to one bucket.
This is the reason the plan originally reached for A's per-aggregate
FILTER workaround — but A's CASE-dispatched form pays the full-scan
cost without giving anything back, so we skip to C.

**Why window functions (strategy E) are subsumed.** A shape like
`SELECT DISTINCT col, COUNT(*) FILTER (WHERE cond_minus_col) OVER
(PARTITION BY col) FROM film` gives one-scan filter-minus-self counts
for a *single* facet, but combining multiple facets grids to N₁ × N₂
× … output rows per input row. `UNION ALL` is the natural fit for
multi-facet.

### Typed-value shape

Each facet's value column has its own Java/JDBC type on the schema side
— `MpaaRating`, `Boolean`, `Integer`, `String`. At SQL time, shape C
requires all arms of the UNION to share a type in each column
position, so the emitter casts `value` to `TEXT`:
`rating::text AS value`, `rental_duration::text AS value`, etc. The
Java decoder reads the `facet` label column and parses `value` back
to the native Java type from the corresponding `FacetSpec`.

This is a small mechanical decode. The alternative — wide unified rows
with one column per facet — was tested in the spike's shape A; it's
more awkward to assemble in jOOQ and wins on nothing.

### NULL facet buckets

Postgres emits a NULL group key automatically when the facet column
has NULL values. Phase 1 scenario 7 confirmed this: a rating facet
under a 200 000-row table with 10 000 NULLs produces a NULL bucket
with count 10 000 and no cast or special handling. v1 preserves NULL
as its own facet bucket. The `*FacetValue.value` schema field is
therefore nullable; the emitter does not inject `IS NOT NULL` around
facet columns.

### Facet-value ordering

v1 emits `ORDER BY facet, cnt DESC, value` at the outer level. Spike
measurement: cost is ≈ 0.4 ms on top of the 27 ms base at 200 000
rows — essentially free because the output set is tiny (≤ a few
hundred rows per facet). Consumers needing a different ordering can
re-sort client-side.

### Fallback to B

If a Connection field grows past ~10 facets, shape C's UNION becomes
unwieldy and emitter readability suffers. At that threshold, the
fetcher issues N separate jOOQ queries and assembles in Java —
structurally identical to shape B. Decision lives entirely inside the
fetcher; the GraphQL surface is unchanged.

If a target dialect later added to Graphitron lacks `UNION ALL` with
mixed types in the value column (unlikely), the same B fallback
applies.

---

## Phase 1 — SQL strategy spike *(complete)*

### Outcome

Five SQL shapes measured against a 200 000-row synthetic Sakila-shaped
`film_scaled` table across five scenarios (no filter, one filter,
multi-filter, open-ended prefix, NULL-bearing). Wall-clock medians
over 10 warm-cache runs + `EXPLAIN (ANALYZE, BUFFERS, VERBOSE)` per
pair. Full details in [`spike-faceted-search-sql.md`](spike-faceted-search-sql.md).

**Decision: v1 default moves from shape A (`GROUPING SETS`) to
shape C (`UNION ALL` of per-facet `GROUP BY`s).**

Key findings:

- The plan's original shape A form (`GROUPING()` inside `FILTER`) is
  invalid Postgres syntax (`ERROR: grouping operations are not
  allowed in FILTER`). The CASE-dispatched workaround parses but
  loses on every measured scenario.
- Shape A forces a full table seq scan; shape C's independent arms
  let the planner pick per-facet bitmap index scans when filters are
  selective, and Postgres parallelises arms via `Parallel Append`.
- Medians (ms): S1 A=32 C=29; S3 A=38 C=27; S5 A=51 C=27.
- Correctness: all measured shapes produce identical counts vs
  shape B reference.
- NULL-bearing facet columns emit a NULL group key automatically
  under plain `GROUP BY` (resolves OQ #4).
- `ORDER BY facet, cnt DESC, value` costs ≈ 0.4 ms at 200 000 rows
  (resolves OQ #5).

The "SQL emission strategy" section above, the Phase 4 emitter
sketch, and the "Resolved design decisions" / "Open Questions"
sections have all been updated to reflect the swap.

### Carried forward to Phase 2+

- `FacetSpec` carries the facet column and its (Java, SQL) type, as
  before — no change from the pre-spike design.
- `value` is emitted as `TEXT` in SQL; Java decodes per facet's
  `FacetSpec` back to the native type. This is a small change from
  the pre-spike plan, which kept each facet's value in its own
  column position across grouping sets.
- Phase 4 jOOQ surface: `DSL.select(...).from(...).where(...).groupBy(col)`
  per arm plus `.unionAll(...)` to assemble. No `DSL.groupingSets(...)`
  or `DSL.grouping(...)`.

### Spike-vs-plan accounting

The spike completed as the first phase of this plan. The plan stays
`Draft` until approved; Phase 1's completion does not transition the
state. When Phase 5 ships, the plan goes `Pending Review`; the spike
report file is deleted together with the plan on Done.

---

## Phase 2 — Directive + schema-transform expansion

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
> entry keyed on the `FacetValue` / `Facets` suffix pattern until Phase 3
> supplies real classification. Verify during Phase 2 implementation.

---

## Phase 3 — Classifier: `FacetSpec` on `FieldWrapper.Connection`

### Overview

The rewrite classifier currently flattens `@asConnection` into a
`FieldWrapper.Connection` with only pagination metadata. Phase 3 teaches
it to *also* read the filter input's `@facet` directives and carry the
resulting specs on the wrapper, so the emitter (Phase 4) has everything
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
   the name `MakeConnections` produced in Phase 2 (single source of
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

No new validator rule in Phase 3 — the classifier's rejections above
propagate naturally. If Phase 2's note about `UnclassifiedType` allowlisting
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

## Phase 4 — Emitter: `UNION ALL` aggregate + wiring

### Overview

`TypeFetcherGenerator.buildQueryConnectionFetcher` (`:519`) emits one
extra SELECT formed as a `UNION ALL` of per-facet `GROUP BY` arms, one
arm per selected facet. Each arm applies filter-minus-self in its own
`WHERE`; each arm's value column is cast to `TEXT` to unify UNION arm
types. Results carry a `facet` label column used by the Java decoder;
decoded values parse back to each facet's native Java type via the
`FacetSpec` carried on `FieldWrapper.Connection`. Results are packaged
into an extended `ConnectionResult`; `ConnectionHelper` gets a `facets`
accessor; `GraphitronWiringClassGenerator` adds a `facets` dataFetcher.

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

Per the *SQL emission strategy* section above: one `UNION ALL` of
per-facet `GROUP BY` arms. Each arm applies the full Connection
filter *minus that facet's own predicate*. The paginated `edges` /
`nodes` query is unchanged.

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
   `f`'s own predicate omitted. The current filter class bundles all
   its input-field predicates into one generated method (see *Current
   State*), so the fetcher cannot ask the filter to "skip field X".
   Instead, reconstruct facet predicates inline in the fetcher using
   `FacetSpec` data (which Phase 3 places on `FieldWrapper.Connection`):

   - Build a **base condition** equal to the full filter's condition
     applied to *every non-facet field*. The cleanest route is to
     teach `TypeConditionsGenerator` to emit a second overload —
     `applyNonFacet(table, filter)` — that skips every `@facet`-marked
     input field when building `condition`. The existing
     `applyFull(...)` overload continues to back the edges/nodes
     query. Adds a generator touch-point but keeps facet knowledge
     out of the filter method's body.
   - For each facet `g`, its own predicate is the column-equality /
     `IN` implied by `FacetSpec.columnName` and the value(s) the
     client passed at `env.getArgument("filter").get(g.inputFieldName())`.
     The fetcher emits this inline via jOOQ:
     `DSL.field(g.columnName(), g.jooqType()).in(values)` (or `.eq`
     for a scalar-valued facet). Gate on null/empty — absent input
     contributes no conjunct.
   - `cond_minus_f = baseCondition AND (⋀ g ≠ f of g's inline predicate)`.

   This leaves the filter-class generation with one additive change
   (a second overload) and puts facet-predicate reconstruction in the
   one place that already has `FacetSpec`: the fetcher.

2. **Per-facet arms.** For each `f` in `selectedFacets`, emit one arm:
   ```java
   SelectSelectStep<Record3<String, String, Integer>> armFor(FacetSpec f) {
       Field<?> col = table.field(f.columnName());
       return DSL
           .select(
               DSL.val(f.inputFieldName()).as("facet"),
               col.cast(String.class).as("value"),
               DSL.count().as("cnt"))
           .from(table)
           .where(condMinusSelf(f))
           .groupBy(col);
   }
   ```
   `col.cast(String.class)` aligns the `value` column type across
   arms so `UNION ALL` parses. At decode time the Java side parses
   back to each facet's native type via the `FacetSpec`.

3. **Assemble the UNION.** Glue the arms:
   ```java
   var first = armFor(selectedFacets.get(0));
   Select<Record3<String, String, Integer>> union = first;
   for (int i = 1; i < selectedFacets.size(); i++) {
       union = union.unionAll(armFor(selectedFacets.get(i)));
   }
   var facetRows = dsl
       .select()
       .from(union)
       .orderBy(
           DSL.field("facet", String.class),
           DSL.field("cnt", Integer.class).desc(),
           DSL.field("value", String.class))
       .fetch();
   ```
   No cross-arm sharing; each arm's planner decision is independent.
   Postgres' `Parallel Append` executes arms concurrently.

4. **Decode rows into the facets map.** Each row carries its own
   `facet` label; no GROUPING() bit-flag decoding needed. Parse
   `value` back via each facet's `FacetSpec`:
   ```java
   Map<String, List<FacetValueRow>> facets = new HashMap<>();
   Map<String, FacetSpec> byName = selectedFacets.stream()
       .collect(Collectors.toMap(FacetSpec::inputFieldName, f -> f));
   for (Record row : facetRows) {
       String label = row.get("facet", String.class);
       String raw   = row.get("value", String.class);
       int count    = row.get("cnt",   Integer.class);
       FacetSpec f  = byName.get(label);
       Object typed = f.parseValue(raw);    // null-safe; returns null for NULL bucket
       facets.computeIfAbsent(label, k -> new ArrayList<>())
             .add(new FacetValueRow(typed, count));
   }
   ```

5. Attach the facets map to the `ConnectionResult`.

**N-facet fallback.** When `selectedFacets.size()` exceeds ~10, the
UNION becomes unwieldy and fetcher readability suffers. At that
threshold the fetcher issues N separate jOOQ queries (shape B) and
assembles in Java. Same per-arm SQL structure, just N round-trips
instead of one UNION. The switchover is an emitter-local decision;
no schema or classifier change. Defer actually writing the N-facet
path until a schema crosses the threshold.

**jOOQ API surface (3.20.11):** `DSL.select(...)`, `DSL.val(...)`,
`Field.cast(Class)`, `SelectJoinStep.groupBy(Field)`,
`Select.unionAll(Select)`, `DSL.count()`, `ResultQuery.fetch()`. No
`DSL.groupingSets(...)` or `DSL.grouping(...)`. Surface verified
against the Phase 1 spike's hand-written SQL.

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

## Phase 5 — Execution tests

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

## Phase 6 — Hierarchical facets (deferred, scoped here)

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

### Why this is Phase 6, not v1

1. Requires modelling a facet's parent relation — either via a new
   `@facet(parent: "<otherFacetField>")` arg or by inferring from the
   referenced column's FK path. Both call for schema-design alignment
   with the supergraph team (ticket explicitly notes this).
2. Requires the `*FacetValue` shape to grow
   `parentValue: <same scalar as value>` (nullable, NULL at root) and
   the per-facet field to accept `facets(includeChildrenOf: [<that
   scalar>])`. v1's shape must leave room: each `*FacetValue` is an
   independent type so Phase 6 can add `parentValue` additively
   without breaking wire compat. Argument name `includeChildrenOf` is
   reserved now so existing queries don't collide later.
3. SQL: each requested level adds one arm to the same `UNION ALL`
   chain, with its own `WHERE parent_id IN includeChildrenOf AND
   <base-minus-self>` predicate — still the same v1 shape. No new SQL
   strategy needed; ROLLUP remains wrong for the same
   filter-minus-self reason.

### What Phase 2–4 must preserve

- `*FacetValue` types are *not sealed* — Phase 6 adds `parentValue` as a
  nullable field without breaking wire compat.
- `*ConnectionFacets` field uses position (by input-field name) so
  Phase 6's `includeChildrenOf` argument can attach without renaming.
- `FacetSpec` (model) has room for `parentFacet: Optional<FacetSpec>`
  without changing the constructor signature every downstream record
  uses. Consider keeping it a sealed interface over `FlatFacetSpec` /
  `HierarchicalFacetSpec` — but only add that split in Phase 6; v1
  uses the flat record.

### Success Criteria

Phase 6 is deferred — no v1 success criteria. Carved out here so
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
- **Hierarchical shape (Phase 6).** Flat response +
  `includeChildrenOf: [<parent value type>]` argument +
  `parentValue` pointer typed to match. No nested query structures
  under `facets`. GG-335 is explicit on the no-nesting rule.
  Implementation deferred to Phase 6; v1 types must not foreclose it.
- **Per-facet independence semantics.** Every facet's counts reflect
  the base filter *minus that facet's own predicate* — enabling a
  user to change their selection within the same facet without
  collapsing siblings. Ticket's user-interaction walkthrough assumes
  it; the SQL strategy section above builds on it.
- **No nested `facets { parent { children { ... } } }` structure.**
  Hard constraint from ticket: performance + query-shape driver.
- **NULL facet buckets — preserve as their own group.** `GROUP BY`
  emits NULL as a distinct key automatically; Scenario 7 of the
  spike (`docs/planning/spike-faceted-search-sql.md`, §OQ #4)
  confirmed all three measured shapes pass NULL through unchanged.
  v1 emits no `IS NOT NULL` scrubbing; `*FacetValue.value` is
  **nullable** on the schema side to accommodate. Consumers that
  want to hide NULL can apply `IS NOT NULL` as a regular filter or
  drop the row client-side.
- **Facet-value ordering — count-desc with stable tiebreaker.** v1
  emits `ORDER BY facet, cnt DESC, value` at the top of the UNION.
  Spike measured ~0.4 ms overhead at 200× Sakila scale (27.3 →
  27.7 ms median on shape C) — negligible, and the deterministic
  tiebreaker on `value` means test assertions stay stable. See
  `docs/planning/spike-faceted-search-sql.md` §OQ #5.

## Open Questions

1. **Naming-collision between transform and rewrite.** Both modules
   must agree on `{Scalar}FacetValue` / `{Connection}Facets` name
   derivation. Options: (a) extract a small `facet-naming` module
   depended on by both, (b) duplicate + cross-module assertion test.
   (a) is cleaner; (b) is faster. Decide during Phase 2 review.

2. **Aggregate-query cost at high facet counts.** v1 emits one
   `UNION ALL` arm per selected facet. Cardinality scales with the
   sum of distinct-value counts across selected facet columns (each
   facet contributes one row per distinct value) — typically small
   for enum/Boolean facets, potentially larger for open-ended string
   facets. Phase 1 spike measured 2-facet cases only; Phase 5's
   execution tests re-check at full-integration scale and with more
   facets. If a pathological case emerges (e.g. a high-cardinality
   string facet combined with several others), the fallback is to
   issue one query per facet arm (shape B) — which the spike showed
   wins under heavy filtering anyway. That remains an emitter-side
   choice guarded by real profiling data.

3. **Facets on columns reached through FK joins.** v1 rejects
   `@facet` on `@reference`-bound input fields. GG-335's Studieprogram
   hierarchical example implies faceting over a joined parent
   (Fakultet → Institutt). Lifting this restriction is entangled with
   Phase 6; confirm it can stay rejected until then.

## References

- Jira: [GG-335](https://sikt.atlassian.net/browse/GG-335) — Graphitron
  ticket with the target SDL shape.
- Jira: [SOPP-141](https://sikt.atlassian.net/browse/SOPP-141) —
  admissions initiative; closed in favour of GG-335.
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
