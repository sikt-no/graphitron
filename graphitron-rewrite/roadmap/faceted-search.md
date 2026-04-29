---
id: R13
title: "Faceted search on `@asConnection`"
status: Spec
priority: 7
theme: pagination
depends-on: []
---

# Faceted search on `@asConnection`: `@asFacet` directive

> Add a `@asFacet` directive for filter-input fields. The `@asConnection`
> emit-time synthesis pipeline grows a facet arm: each marked input field
> becomes an entry on a synthesized `XConnectionFacets` object that is
> attached as `facets` on the generated Connection type. The classifier
> carries a `FacetSpec` list on `FieldWrapper.Connection`; the fetcher
> emits one `UNION ALL` aggregate query per Connection request, with
> each arm computing one facet's counts under its filter-minus-self
> predicate. Phase 1 spike confirmed this shape over `GROUPING SETS`
> (see Phase 1 Outcome below). Delivers the
> "filter ↔ facet" contract the admissions UX needs without nested
> queries.

## Overview

Covers GG-335 ("Legge til støtte for fasettering av filter") and resolves
SOPP-141 ("Utbedre filtrering, sortering og paginering"), which was closed with
the explicit deferral *"Denne er avsluttet da graphitron vil håndtere dette for
oss via GG-335."*

A schema author marks fields inside a `@asConnection` field's filter input with
`@asFacet`:

```graphql
type Query {
    filmer(filter: FilmFilter): [Film!]! @asConnection
}

input FilmFilter {
    rating:   [MpaaRating!] @field(name: "RATING")        @asFacet
    category: [String!]     @field(name: "CATEGORY_NAME") @asFacet
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
> during Spec → Ready review.

At runtime, any selection under `facets` triggers **one extra SQL
statement** — a `UNION ALL` of per-facet `GROUP BY` arms, one arm per
selected facet. Each arm applies the full Connection filter *minus
that facet's own predicate*, so a selected facet value still shows
its siblings' counts. Postgres plans each arm independently (bitmap
index scans on selective filters) and executes arms concurrently via
`Parallel Append`. See *SQL emission strategy* below. Results merge
into a single `ConnectionResult` carrier.

## Current State

- Rewrite's `@asConnection` emit-time synthesis (`ConnectionSynthesis`
  in `graphitron-rewrite`) expands directive-driven list fields into
  `XConnection` / `XEdge` / `PageInfo` TypeSpecs and rewrites the
  carrier field's return type via `ObjectTypeGenerator`. Nothing there
  knows about facets yet.
- The rewrite classifier's `FieldBuilder.buildWrapper` produces
  `FieldWrapper.Connection` carrying `defaultPageSize` and
  `connectionName` only; the record sits in `model/FieldWrapper.java`
  with one regular constructor + one 2-arg convenience constructor for
  structural detection.
- `TypeFetcherGenerator.buildQueryConnectionFetcher` emits a single
  keyset-paginated SELECT wrapped in `ConnectionResult`. No secondary
  aggregation queries.
- The synthesised `<ConnName>Type.registerFetchers(GraphQLCodeRegistry.Builder)`
  body wires `edges` / `nodes` / `pageInfo` against `ConnectionHelper`
  via `codeRegistry.dataFetcher(FieldCoordinates.coordinates(...), ...)`.
- Filter-input types classify through `TypeBuilder.buildInputField`
  into `InputField` sealed subclasses (`ColumnField`,
  `ColumnReferenceField`, `PlatformIdField`, `NestingField`). None of
  them carries a facet flag. `InputField.IdReferenceField` is a
  pending sibling from [plan-id-reference-input-field.md]; if it
  lands first, Phase 3's `@asFacet` rejection list must rule on it (see
  Non-goals).
- `BuildContext` lists every directive the rewrite reads in its
  `DIR_*` constant block; there is no `DIR_FACET`.
- No execution-test fixture combines `@asConnection` with a filter input
  today — the test-spec `schema.graphqls` has `filmsConnection` + variants
  but only scalar filter args at argument level, not a `@table`-backed
  filter input.
- Filter-input conditions are emitted via `WhereFilter` (sealed into
  `GeneratedConditionFilter` / `ConditionFilter`, one per
  `@condition`-bound method). `TypeFetcherGenerator.buildConditionCall`
  iterates filters, emitting one
  `condition = condition.and(Filters.method(table, args...))` per
  filter. The filter method itself ANDs all its fields internally — so
  the fetcher cannot surgically drop a single input-field's predicate
  by passing a skip name; the filter-class generator owns that
  assembly. This shapes Phase 4's condition-minus-self strategy (see
  below).
- **Builds on shipped fetcher-quality primitives**: pagination boilerplate
  lives in `ConnectionHelper.pageRequest(...)`, condition orchestration in
  generated `QueryConditions` classes, the local jOOQ-table variable is
  named `<entity>Table`, and emitted code is `var`-free (see "Generated-fetcher
  quality pass" in roadmap Done). Phase 4 below is written against this
  post-quality shape and notes the coordination points inline.

## Desired End State

- New `@asFacet` directive declared in rewrite's own directive resource
  (`graphitron-rewrite/src/main/resources/directives.graphqls`).
- The `@asConnection` emit-time synthesis pipeline grows a facet arm:
  for each `@asConnection` field whose filter input has `@asFacet`-marked
  fields, the synthesis Plan records a `FacetSpec` list, and emit-time
  produces one `<ConnName>FacetsType` per Connection plus one reusable
  `<Scalar>FacetValueType` per distinct value scalar. The Connection
  type's `ObjectTypeGenerator` rewrite gains a `facets` field;
  `GraphitronSchemaClassGenerator` adds the synthesised types via
  `.additionalType(...)` alongside the existing Connection / Edge
  types.
- `FieldWrapper.Connection` carries a `FacetSpec` describing each facet
  (input-field name → column + value-scalar type).
- `TypeFetcherGenerator` emits **one** `UNION ALL` aggregate query per
  Connection request, one arm per selected facet. Each arm's `WHERE`
  applies the full Connection filter *minus that facet's own
  predicate*, so a selected facet value still shows its siblings'
  counts. Each arm can use per-facet indexes; `Parallel Append`
  executes arms concurrently.
- `ConnectionResult` carries the facet results; a new
  `ConnectionHelper.facets` static assembles them; the synthesised
  `<ConnName>Type.registerFetchers` body wires the `facets` field.
- Execution tests against Sakila confirm counts match plain SQL aggregates,
  including when a facet's own predicate is active.

### Verification

1. New pipeline test in `GraphitronSchemaBuilderTest` classifies a schema with
   `@asFacet` into a `FieldWrapper.Connection` whose `facets()` is non-empty.
2. New execution test in `graphitron-test` asserts facet counts
   match a hand-written jOOQ aggregate over the same filter.
3. Existing `filmsConnection*` tests unchanged (no `@asFacet` in their filters).

## What We're NOT Doing (v1)

- **Hierarchical / tree facets** — deferred to Phase 6 below. v1 ships
  flat facets only. Emitter and model must *leave room* for the
  extension (see Phase 6); they must not foreclose it.
- **`selected: Boolean!` on facet values.** SOPP-141 mentioned it; GG-335
  omits it. We follow GG-335 in v1.
- **Facets on non-`@asConnection` list fields.** Connection-only; the whole
  filter-↔-facets contract assumes a projectable aggregate shape.
- **Facets on `@asFacet` fields bound to `@reference` paths, `@condition` joins,
  or composite/`[ID!]` reference fields.** Classifier rejects these at
  validate time; loosening is a follow-up. If `InputField.IdReferenceField`
  (pending in [plan-id-reference-input-field.md]) lands before this plan,
  Phase 3's rejection list must add it too — the v1 SQL emitter only
  understands direct-column facet values, and a join-mediated ID-reference
  field needs a different aggregation shape. Out of scope for v1; tracked
  as a follow-up alongside the other reference-path cases.
- **Cross-facet independence semantics.** v1 applies "all filters except this
  facet's own predicate" per facet (conventional UX expectation). Alternative
  semantics (AND-all, OR-all) are follow-ups if a real use case surfaces.

## Key Discoveries

- **Reuse the `@asConnection` emit-time synthesis pipeline.**
  `ConnectionSynthesis.buildPlan()` already scans the assembled
  `GraphQLSchema` for `@asConnection` and produces a `Plan` consumed
  by `emitSupportingTypes()` (which writes `<ConnName>Type` /
  `<ConnName>EdgeType` TypeSpecs) and by
  `ObjectTypeGenerator.buildFieldDefinition()` (which rewrites the
  directive-driven field's return type and arguments). Facets ride
  the same plan: extend `ConnectionDef` with a `List<FacetSpec>` read
  from `@asFacet` on the filter input, emit one
  `<ConnName>FacetsType` per Connection plus one
  `<Scalar>FacetValueType` per distinct value scalar, and have
  `ObjectTypeGenerator` append a `facets` field to the rewritten
  Connection. `GraphitronSchemaClassGenerator.generate()` adds the
  new TypeSpecs via `.additionalType(...)` next to the existing
  Connection / Edge types.
- **Single directive-declaration file.** `@asFacet` is declared in
  rewrite's own `directives.graphqls`. The schema loader auto-injects
  it before classification.
- **`FieldWrapper.Connection` is a record** with no public builders;
  adding a `facets` member means every construction site — the
  directive-driven `@asConnection` path and the structural-detection
  fallback in `FieldBuilder.buildWrapper` — must pass the new argument.
- **Per-facet self-predicate stripping** needs the `Condition` to be built
  compositionally. `buildConditionCall` in `TypeFetcherGenerator` currently
  folds all argument conditions into one — we'll need per-column conjuncts
  kept addressable so one can be dropped when emitting each facet query.
- **Facet value types are cross-schema reusable.** `StringFacetValue`,
  `BooleanFacetValue`, `IntFacetValue`, `<Enum>FacetValue` — one per
  value scalar encountered across the whole schema, not per connection.
  Synthesize-once via a single `FacetNaming.facetValueTypeName(scalar)`
  helper used by both the synthesis pass and the classifier.

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
| 1 | hand-written SQL (complete) | Spike — benchmarked SQL strategies against Sakila; confirmed shape C as v1 default; resolved NULL + ordering Open Questions. Outcome captured in Phase 1 Outcome below |
| 2 | `graphitron-rewrite` (directive + synthesis) | `@asFacet` directive definition; the `@asConnection` synthesis pipeline grows a facet arm that emits `*Facets` / `*FacetValue` TypeSpecs and adds the `facets` field on the rewritten Connection |
| 3 | `graphitron-rewrite` (classifier) | `FieldWrapper.Connection` carries `FacetSpec`; validator rejects misuse |
| 4 | `graphitron-rewrite` (emitter) | Fetcher emits the spike-chosen aggregate shape; helper + wiring expose the new field |
| 5 | `graphitron-test` | Execution tests against Sakila |
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

Phase 1 spike (see Phase 1 Outcome below) measured this
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
| **F. Conditional aggregation on known values** (`COUNT(*) FILTER (WHERE col = 'G')` etc.) | 2 | 1 (parallel) | Yes | PostgreSQL `FILTER` / SQL:2003 | Post-v1 optimisation — 2–3× faster than C at 5M rows when all facets are bounded-domain. Falls back to C when any facet is open-ended. See Open Question #2. |

**Why shape C wins over shape A.** Shape C's arms are independent
queries; each one's WHERE lets the planner pick a bitmap index scan
when filters are selective, and Postgres parallelises arms via
`Parallel Append`. Shape A's HashAggregate over N grouping keys runs
single-threaded, so its CPU cost grows worst with facet count. On the
spike data (see Phase 1 Outcome below for details):

- 200 000-row warm-cache S3 (multi-filter): C 27 ms vs A 38 ms.
- 200 000-row warm-cache S5 (open-ended prefix): C 27 ms vs A 51 ms.
- 5M-row warm-cache multi-filter, 2 facets: A 1 247 ms vs C 1 614 ms
  (A slightly ahead at low facet count).
- 5M-row warm-cache multi-filter, 8 facets: C 1 804 ms vs A 3 683 ms
  (C wins by 2× once Parallel Append amortises).

Cold reads are within 3% between A and C at 5M rows (both ~1 × table).
The v2 re-measurement did not overturn v1's choice: C parallelises
at the facet counts we expect in production, the emitter is simpler,
and A's constant-read advantage never materialises into wall-clock
wins beyond 2 facets. See Phase 1 Outcome and Open Question #2 for
the bounded-domain optimisation path (shape F) that is 2–3× faster
than C where applicable.

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
multi-filter, open-ended prefix, NULL-bearing), then re-measured at
5 000 000 rows (heap 444 MB, ~3.5× `shared_buffers`) with per-facet
fan-out (2 / 5 / 8 facets) and cold-cache top-level Buffers. Headline
findings folded into this section; raw EXPLAIN plans and per-scenario
timing tables live in git history (`git log -- graphitron-rewrite/roadmap/faceted-search-sql.md`).

**Decision: v1 default is shape C (`UNION ALL` of per-facet
`GROUP BY`s).**

Key findings:

- The plan's original shape A form (`GROUPING()` inside `FILTER`) is
  invalid Postgres syntax (`ERROR: grouping operations are not
  allowed in FILTER`). The CASE-dispatched workaround parses.
- At 5M rows, A and C are within 3% on cold reads (both ~1 × table);
  C's cross-arm buffer retention prevents N × table growth at tested
  scale. A's HashAggregate over N grouping keys runs single-threaded,
  so its wall-clock scales badly with facet count (8-facet A = 3.7 s
  warm; 8-facet C = 1.8 s). At 2 facets A beats C by 30% on warm
  wall-clock; C wins from 5 facets up via `Parallel Append`.
- Correctness: all measured shapes produce identical counts vs
  shape B reference.
- NULL-bearing facet columns emit a NULL group key automatically
  under plain `GROUP BY` (resolves OQ #4).
- `ORDER BY facet, cnt DESC, value` costs ≈ 0.4 ms at 200 000 rows
  (resolves OQ #5).
- **Shape F (conditional aggregation on known values) emerged as the
  optimisation path.** Single parallel seq scan + one `count(*)
  FILTER` aggregate per (facet, value) pair. At 5M rows F is 2.7×
  faster than A and 1.8–3.5× faster than C on warm wall-clock, with
  identical cold reads to A (1 × table). Constraint: every facet
  value must be known at emit time (enums ✓, small FKs ✓ via
  `@asFacet(values:)` or catalog pre-query, open-ended text ✗). Not
  adopted for v1 because it doesn't generalise; kept as a post-v1
  emitter-internal swap when every selected facet is bounded-domain.
  (Spike report labels this shape E; plan's strategy comparison
  table keeps F for historical continuity.)
- **Unmeasured scaling caveat.** At 10–30× larger tables,
  C's cross-arm cache retention degrades (`shared_buffers` shrinks
  relative to working set). If real deployments land with 50M+ rows
  in a faceted connection, Phase 5 should re-measure and the
  bounded-domain hybrid above becomes more attractive.

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

The spike completed as the first phase of this plan. Phase 1's
completion does not by itself transition plan state; the plan sits at
Spec until the workflow Spec → Ready review signs off. When Phase 5
ships, the plan goes In Review; the spike report file is deleted
together with the plan on Done.

---

## Phase 2 — Directive declaration + facet-synthesis pass

### Overview

Declare `@asFacet` in rewrite's own directives resource and extend the
existing `@asConnection` emit-time synthesis pipeline so each
`@asConnection` field's `@asFacet`-bearing filter inputs produce a
`facets` field on the rewritten Connection type, one
`<ConnName>FacetsType` per Connection, and one reusable
`<Scalar>FacetValueType` per distinct value scalar.

### Changes

#### `graphitron-rewrite/src/main/resources/directives.graphqls`

Add:

```graphql
"""
Marks a filter-input field as a facet on the enclosing `@asConnection`
field's generated Connection type. The Connection type gains a
`facets: XConnectionFacets` field; each `@asFacet`-marked input field
becomes an entry there, returning `[XFacetValue!]!` with per-value
counts.

Only valid on fields of an input type used as the filter input of an
`@asConnection`-bearing field. The input field must be bound to a
column via `@field(name:)` (reference / condition / composite-key
bindings are rejected in v1).
"""
directive @asFacet on INPUT_FIELD_DEFINITION
```

#### Extend the `@asConnection` synthesis pipeline

The existing pipeline (shipped under "Rewrite owns `@asConnection` via
emit-time synthesis"; see changelog) is the natural seam:

- `ConnectionSynthesis.buildPlan()` scans the assembled
  `GraphQLSchema` and produces a `Plan` of `ConnectionDef` entries.
  Extend `ConnectionDef` with a `List<FacetSpec>` populated by
  reading `@asFacet` on the wrapped field's filter-input argument.
- `ConnectionSynthesis.emitSupportingTypes()` produces the existing
  `<ConnName>Type` / `<ConnName>EdgeType` TypeSpecs. Extend it to
  also emit `<ConnName>FacetsType` (one per Connection that has
  facets) and `<Scalar>FacetValueType` (one per distinct value
  scalar across the whole schema, deduped by name via the shared
  `FacetNaming.facetValueTypeName(scalar)` helper).
- `ObjectTypeGenerator.buildFieldDefinition()` already rewrites the
  directive-driven Connection field's return type and arguments;
  append a `facets: <ConnName>Facets` field to that rewritten type
  when the plan entry has a non-empty `FacetSpec` list.
- `GraphitronSchemaClassGenerator.generate()` already wires
  synthesised Connection / Edge / PageInfo types via
  `.additionalType(...)`; thread the new `*Facets` and
  `*FacetValue` TypeSpecs through the same call site.

For each field annotated `@asConnection`, the synthesis pass walks
the filter-input argument and, for every input field carrying
`@asFacet`:

1. Resolve the value scalar (the GraphQL type of the input field,
   stripped of list/non-null). For scalar/enum leaves, this is the
   facet value type.
2. Record a `<Scalar>FacetValue` entry on the Plan, deduped by the
   derived type name. `value` carries the **same scalar as the
   filter-input field**, preserving round-trip symmetry:
   ```graphql
   type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
   type StringFacetValue     { value: String!     count: Int! }
   type BooleanFacetValue    { value: Boolean!    count: Int! }
   type IntFacetValue        { value: Int!        count: Int! }
   ```
   A client feeds `facetValue.value` straight back into the filter
   input with no conversion. Custom scalars synthesize
   `<CustomScalar>FacetValue` on demand the same way. The shared
   helper `FacetNaming.facetValueTypeName(scalar)` is the source of
   truth for the derived type name, shared between this pass and the
   classifier (Phase 3).
3. Record one `{ConnectionName}Facets` Plan entry with one non-null
   list field per `@asFacet` input, field name matching the input
   field name.
4. Mark the Connection's plan entry as carrying `facets`, so
   `ObjectTypeGenerator` appends the `facets: {ConnectionName}Facets`
   field when it rewrites the directive-driven Connection field.

`emitSupportingTypes()` then turns the Plan into TypeSpecs:
`<ConnName>FacetsType` and each `<Scalar>FacetValueType` join the
sorted list emitted to the schema sub-package. If the wrapped field
has no filter input, or the filter input has no `@asFacet` fields, no
facet entries land on the plan and the Connection is emitted exactly
as today. No error, no warning.

### Success Criteria

- [ ] `mvn test -pl :graphitron-rewrite -Pquick` — new
      `ConnectionSynthesisTest` cases cover an SDL with `@asFacet` and
      assert the Plan carries a non-empty `FacetSpec` list, the
      emitted TypeSpecs include `<ConnName>FacetsType` (with one list
      field per `@asFacet`) and each `<Scalar>FacetValueType` (with
      `value` + `count`), and the rewritten Connection field has a
      `facets` member.
- [ ] Existing Connection-synthesis fixtures unchanged.
- [ ] Classifier tolerates the synthesized types at this phase: they
      appear as `UnclassifiedType` since nothing reads
      `FieldWrapper.Connection.facets` yet. Validator won't flag them
      because they're not reached from a classified field.

> **Note on classifier tolerance.** If `UnclassifiedType` on the synthesized
> facets types does trigger a validator error in isolation, add an allowlist
> entry keyed on the `FacetValue` / `Facets` suffix pattern until Phase 3
> supplies real classification. Verify during Phase 2 implementation.

---

## Phase 3 — Classifier: `FacetSpec` on `FieldWrapper.Connection`

### Overview

The rewrite classifier currently flattens `@asConnection` into a
`FieldWrapper.Connection` with only pagination metadata. Phase 3 teaches
it to *also* read the filter input's `@asFacet` directives and carry the
resulting specs on the wrapper, so the emitter (Phase 4) has everything
it needs without re-parsing SDL.

### Changes

#### `BuildContext` — new directive constant

Add to the `DIR_*` constant block:

```java
static final String DIR_FACET = "asFacet";
```

#### `model/FieldWrapper.java`

Extend the `Connection` record with a facets list:

```java
record Connection(
    boolean connectionNullable,
    boolean itemNullable,
    int defaultPageSize,
    String connectionName,
    java.util.List<FacetSpec> facets   // empty when no @asFacet fields
) implements FieldWrapper { ... }
```

Keep both existing constructors; have them forward `List.of()` for the
new parameter. Both Connection construction sites in
`FieldBuilder.buildWrapper` — the directive-driven `@asConnection` path
and the structural-detection fallback — get an extra argument.

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
`@asFacet`-marked fields:

1. Each `@asFacet` field must also carry `@field(name:)` (rejected
   otherwise with `UnclassifiedField` + a message naming the field).
2. Each `@asFacet` field's GraphQL leaf scalar/enum is its `valueTypeName`.
3. Derive `facetValueTypeName` via the shared
   `FacetNaming.facetValueTypeName(scalar)` helper introduced in Phase 2.
   Both the synthesis pass and the classifier call through the same
   helper — no two-module sync worry.

Reject at classify time:

- `@asFacet` on a non-`@field`-bound input field (reference path,
  condition, nesting) → `UnclassifiedField`.
- `@asFacet` on a field whose enclosing input type is not reached via an
  `@asConnection` field → `UnclassifiedField` (the expanded `facets`
  field is dead schema otherwise).

#### `GraphitronSchemaValidator`

No new validator rule in Phase 3 — the classifier's rejections above
propagate naturally. If Phase 2's note about `UnclassifiedType` allowlisting
was needed, remove the allowlist here: the synthesized facet types are
now reachable from a classified field.

### Success Criteria

- [ ] `mvn test -pl :graphitron-rewrite -Pquick` — existing tests pass.
- [ ] New pipeline test: schema with two `@asFacet` inputs on a filter →
      classified `Connection.facets()` has two entries with correct
      column names and value types.
- [ ] New pipeline test: `@asFacet` on a `@reference`-bound input field
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
accessor; the synthesised `<ConnName>Type.registerFetchers` body adds
a `facets` dataFetcher.

### Changes

#### `ConnectionResult` (generated carrier)

Add a `Map<String, List<FacetValueRow>>` field keyed on input-field name,
plus a nested `FacetValueRow(Object value, int count)` record. Update the
constructor and `trimmedResult()` accordingly. `ConnectionResult` lives in
`<outputPackage>.rewrite` alongside `ConnectionHelper`; package unaffected
by the recent `*Fetchers` / `*Conditions` package split.

#### `ConnectionHelperClassGenerator`

Add a `facets(ConnectionResult, env)` static that returns a
`Map<String, List<Map<String, Object>>>` shaped for GraphQL-Java. Each
inner map is `{"value": <typed>, "count": <int>}`. The synthesised
`<Scalar>FacetValueType` TypeSpecs need no extra wiring — graphql-java's
default property fetcher exposes `value` and `count` from the inner
maps by name.

#### `TypeFetcherGenerator.buildQueryConnectionFetcher`

Per the *SQL emission strategy* section above: one `UNION ALL` of
per-facet `GROUP BY` arms. Each arm applies the full Connection
filter *minus that facet's own predicate*. The paginated `edges` /
`nodes` query is unchanged.

**Builds on shipped fetcher-quality primitives.** The "Generated-fetcher
quality pass" entry in roadmap Done already extracted pagination
boilerplate into `ConnectionHelper.pageRequest(...)`, condition
orchestration into generated `QueryConditions` classes, and the
`table` → `<entity>Table` rename. This phase reads: "call
`ConnectionHelper.pageRequest(...)` for the pagination block, add an
`applyNonFacet` method to `QueryConditions` alongside the existing
`applyFull`, and refer to the jOOQ table through the `<entity>Table`
local." Everything below is written against this post-quality shape.

After the main SELECT is emitted, determine the set of facets
present in the GraphQL selection set (a facet whose field is not
selected contributes nothing):

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
     emit a second method on the per-query `QueryConditions` class —
     `applyNonFacet(table, filter)` — that skips every `@asFacet`-marked
     input field when building `condition`. The existing
     `applyFull(...)` method continues to back the edges/nodes
     query. (Pre-quality-plan variant: teach `TypeConditionsGenerator`
     to emit a second overload on the existing generated filter class.
     Same shape, different home.) Adds a generator touch-point but
     keeps facet knowledge out of the filter method's body.
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

2. **Per-facet arms.** For each `f` in `selectedFacets`, emit one arm
   (post-quality-plan, the jOOQ table local is `<entity>Table`; pre,
   it's `table` — adjust to whatever the surrounding method uses):
   ```java
   SelectSelectStep<Record3<String, String, Integer>> armFor(FacetSpec f) {
       Field<?> col = filmTable.field(f.columnName());
       return DSL
           .select(
               DSL.val(f.inputFieldName()).as("facet"),
               col.cast(String.class).as("value"),
               DSL.count().as("cnt"))
           .from(filmTable)
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

#### `<ConnName>Type.registerFetchers`

The synthesised Connection type's emit-time `registerFetchers` method
already registers `edges` / `nodes` / `pageInfo` against
`ConnectionHelper`. Append a `facets` registration that calls
`ConnectionHelper.facets(...)`. The `*FacetValue` types need no
explicit fetcher wiring — `value` and `count` are record properties
that graphql-java's default property fetcher handles.

### Success Criteria

- [ ] `mvn verify -Pquick` on the whole tree.
- [ ] Schemas *without* `@asFacet` emit unchanged fetchers (structural
      diff test: classify pre- and post-patch SDL with no `@asFacet`,
      assert identical `TypeSpec` for the fetcher method).
- [ ] Wiring test: a Connection with `@asFacet` fields registers a
      `facets` dataFetcher in its `<ConnName>Type.registerFetchers`
      body; the `*FacetValue` TypeSpecs are loadable.

---

## Phase 5 — Execution tests

### Overview

Add a Sakila-backed execution fixture combining `@asConnection` with a
`@asFacet`-bearing filter input. Prove per-facet counts match direct jOOQ
aggregates and that selecting one facet value leaves other facet counts
unchanged.

### Changes

#### `graphitron-rewrite/graphitron-test/.../graphql/schema.graphqls`

Add (alongside existing `filmsConnection`):

```graphql
type Query {
    # ... existing ...
    filmsFaceted(filter: FilmFacetFilter, first: Int, after: String): [Film!]!
        @asConnection @defaultOrder(primaryKey: true)
}

input FilmFacetFilter @table(name: "film") {
    rating:       [MpaaRating!] @field(name: "RATING")          @asFacet
    languageName: [String!]     @field(name: "LANGUAGE_NAME")   @asFacet
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
- [ ] `(cd graphitron-rewrite && mvn verify -Plocal-db)`
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
   `@asFacet(parent: "<otherFacetField>")` arg or by inferring from the
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
- **Pipeline (synthesis):** new `ConnectionSynthesisTest` cases cover
  expansion of `@asFacet` into `*Facets` / `*FacetValue` TypeSpecs +
  the `facets` field on the rewritten Connection, and no-op when no
  `@asFacet` is present.
- **Pipeline (classifier):** two new `GraphitronSchemaBuilderTest` cases —
  `@asFacet` classification success and `@asFacet` rejection on non-`@field`
  bindings.
- **Wiring:** assert the synthesised `<ConnName>Type.registerFetchers`
  body wires a `facets` dataFetcher and the `*FacetValue` TypeSpecs
  are loadable.
- **Execution:** three Sakila cases as above.
- **Regression:** existing `filmsConnection*` tests unchanged; structural
  diff confirms fetcher output is byte-identical when `@asFacet` is absent.

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
  during Spec → Ready review.
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
  emits NULL as a distinct key automatically; Phase 1's NULL-bearing
  scenario confirmed all three measured shapes pass NULL through unchanged.
  v1 emits no `IS NOT NULL` scrubbing; `*FacetValue.value` is
  **nullable** on the schema side to accommodate. Consumers that
  want to hide NULL can apply `IS NOT NULL` as a regular filter or
  drop the row client-side.
- **Facet-value ordering — count-desc with stable tiebreaker.** v1
  emits `ORDER BY facet, cnt DESC, value` at the top of the UNION.
  Spike measured ~0.4 ms overhead at 200× Sakila scale (27.3 →
  27.7 ms median on shape C) — negligible, and the deterministic
  tiebreaker on `value` means test assertions stay stable.

## Open Questions

1. **Aggregate-query cost at high facet counts.** v1 emits one
   `UNION ALL` arm per selected facet. Cardinality scales with the
   sum of distinct-value counts across selected facet columns (each
   facet contributes one row per distinct value) — typically small
   for enum/Boolean facets, potentially larger for open-ended string
   facets. Phase 1 spike v2 re-measurement covered 2 / 5 / 8 facets
   at 5M rows; Phase 5's execution tests re-check at full-integration
   scale. If a pathological case emerges (e.g. a high-cardinality
   string facet combined with several others), the fallback is to
   issue one query per facet arm (shape B) — which the spike showed
   wins under heavy filtering anyway. That remains an emitter-side
   choice guarded by real profiling data.

2. **Shape F (conditional aggregation) as post-v1 optimisation.**
   When every facet on a request is bounded-domain (enum-backed
   scalar, small FK, Boolean), the emitter could swap the UNION ALL
   chain for a single `count(*) FILTER` aggregate per (facet, value)
   pair against one parallel seq scan. Spike v2 measured 2–3×
   warm-clock speedup at 5M rows with identical cold-read cost
   (see Phase 1 Outcome's v2 re-measurement). Requires value
   enumeration per facet — achievable from the jOOQ catalog for enum
   columns and from an optional `@asFacet(values: [...])` argument or a
   compile-time query on the referenced table for small FKs. Design
   constraint for v1: keep `FacetSpec` + `FieldWrapper.Connection`
   permissive enough that the C-vs-F choice lives entirely inside
   `TypeFetcherGenerator`; no wire-format or type-surface impact.
   Decide in Phase 5 based on profiling: ship F if any Sikt
   connection exceeds the measured 5-facet threshold or if tables
   routinely exceed `shared_buffers` by >10×.

3. **Facets on columns reached through FK joins.** v1 rejects
   `@asFacet` on `@reference`-bound input fields. GG-335's Studieprogram
   hierarchical example implies faceting over a joined parent
   (Fakultet → Institutt). Lifting this restriction is entangled with
   Phase 6; confirm it can stay rejected until then.

## References

- Jira: [GG-335](https://sikt.atlassian.net/browse/GG-335) — Graphitron
  ticket with the target SDL shape.
- Jira: [SOPP-141](https://sikt.atlassian.net/browse/SOPP-141) —
  admissions initiative; closed in favour of GG-335.
- `graphitron-rewrite/.../ConnectionSynthesis` — Phase 2 extension
  point: `buildPlan()` + `emitSupportingTypes()` grow facet entries.
- `graphitron-rewrite/.../ObjectTypeGenerator.buildFieldDefinition` —
  appends the `facets` field on the rewritten Connection field.
- `graphitron-rewrite/.../GraphitronSchemaClassGenerator.generate` —
  threads new `*Facets` / `*FacetValue` TypeSpecs through
  `.additionalType(...)`.
- `graphitron-rewrite/src/main/resources/directives.graphqls` — target
  for the `@asFacet` directive declaration.
- `graphitron-rewrite/.../FieldBuilder.buildWrapper` —
  `FieldWrapper.Connection` construction sites (both arms).
- `graphitron-rewrite/.../TypeFetcherGenerator.buildQueryConnectionFetcher` —
  Phase 4 emitter target.
- `graphitron-rewrite/.../BuildContext` — `DIR_*` constants.
- "Generated-fetcher quality pass" (roadmap Done) — Phase 4 builds on
  the shipped `QueryConditions` extraction, `<entity>Table` rename, and
  `ConnectionHelper.pageRequest` primitives.
