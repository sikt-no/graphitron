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

type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
type StringFacetValue     { value: String!     count: Int! }
```

At runtime, a selection of `facets.rating` triggers an extra
`GROUP BY rating` query against the same table + the same non-`rating`
predicates as the main Connection query. Results merge into a single
`ConnectionResult` carrier.

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
- `TypeFetcherGenerator` emits one `GROUP BY` query per requested facet,
  reusing the main query's `Condition` with the self-predicate removed (so a
  selected facet value still shows its siblings' counts).
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

- **Hierarchical / tree facets.** GG-335 sketches
  `facets(includeChildrenOf: [Int]) { ...  parentValue }` for parent/child
  categories. Defer — see Open Questions. v1 ships flat facets only.
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

Four phases, in strict order — each phase leaves the build green and
existing tests passing. No phase adds user-observable behaviour until
Phase 3; Phase 4 is test coverage.

| Phase | Module | What lands |
|---|---|---|
| 1 | `graphitron-schema-transform` | `@facet` directive definition; `MakeConnections` synthesizes facet types + `facets` field on the Connection |
| 2 | `graphitron-rewrite` (classifier) | `FieldWrapper.Connection` carries `FacetSpec`; validator rejects misuse |
| 3 | `graphitron-rewrite` (emitter) | Fetcher emits per-facet `GROUP BY`; helper + wiring expose the new field |
| 4 | `graphitron-rewrite-test-spec` | Execution tests against Sakila |

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
   not. Schema:
   ```graphql
   type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
   ```
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

## Phase 3 — Emitter: per-facet GROUP BY + wiring

### Overview

`TypeFetcherGenerator.buildQueryConnectionFetcher` (`:519`) emits one
extra `GROUP BY` SELECT per facet, reusing the main query's table and
condition-minus-self. Results are packaged into an extended
`ConnectionResult`; `ConnectionHelper` gets a `facets` accessor;
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

After the main SELECT is emitted (`:519–`), for each `FacetSpec` on the
field's `FieldWrapper.Connection`:

1. Build a Condition by ANDing every argument-derived predicate **except**
   the one sourced from the facet's own input field. This requires
   `buildConditionCall` to either (a) expose per-argument conjuncts or
   (b) accept a `skip: String` parameter naming the input-field to omit.
   Option (b) is the smaller edit; take it unless implementation finds
   Phase 2 already has the per-conjunct data on the wrapper.
2. Emit:
   ```java
   var {facet}Rows = dsl
       .select(table.field("{COL}"), DSL.count())
       .from(table)
       .where({facetCondition})
       .groupBy(table.field("{COL}"))
       .fetch();
   ```
3. Collect each result into the `ConnectionResult`'s facets map under
   the input-field name.

Gate the whole block on `conn.facets().isEmpty()` — when no facets are
declared, the fetcher stays byte-identical to today's output.

**Selection-awareness** (v1.1, not blocking): these aggregate queries
run unconditionally. A selection check against
`env.getSelectionSet().contains("facets/...")` skips unselected facets;
keep this out of v1 for simplicity and add in a follow-up once the
unselected-facet overhead is measurable.

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
(Integer) so both a String-scalar facet and an Integer-scalar facet are
exercised. Final column choice finalized during implementation.

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

Round-trip assertions: one query for edges, one per facet. For the case
with two facets that's three round-trips total — lock this number in
to catch accidental selection-set regressions.

### Success Criteria

- [ ] All three execution cases pass against PostgreSQL Sakila.
- [ ] `mvn verify -pl :graphitron-rewrite-test,:graphitron-rewrite-test-fixtures,:graphitron-rewrite-test-spec -Plocal-db`
      clean.
- [ ] JDBC round-trip count matches the expected value per case.

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

## Open Questions

1. **Facet-value shape: per-scalar typed (`MpaaRatingFacetValue`) vs.
   single generic (`FacetValue { value: String, count: Int }`).**
   GG-335 proposes per-scalar; the user's original sketch in the chat
   was a flat `{name, count, value}`. Per-scalar preserves GraphQL
   type-safety (an `MpaaRating` value round-trips as an enum, not a
   string) at the cost of more synthesized types. Plan assumes
   per-scalar; confirm before Phase 1 starts.

2. **Hierarchical facets.** GG-335's `includeChildrenOf: [Int]` with a
   flat `parentValue`-carrying list is out of scope for v1. Before v1
   ships, confirm this deferral is acceptable for the admissions UX
   (SOPP-141 didn't require hierarchies in its Product example).

3. **`selected: Boolean!` on `*FacetValue`.** SOPP-141 had it; GG-335
   doesn't. Frontend convenience vs. redundancy (client already knows
   what it filtered on). Default: omit. Revisit if a consumer asks.

4. **Phase 2 naming-collision between transform and rewrite.** Both
   modules must agree on `{Scalar}FacetValue` / `{Connection}Facets`
   name derivation. Options: (a) extract a small `facet-naming` module
   depended on by both, (b) duplicate + cross-module assertion test.
   (a) is cleaner; (b) is faster. Decide during Phase 1 review.

5. **Wiring for enum-valued facets.** Enum scalar round-trips through
   GraphQL-Java's enum coercer — `Object` in Java, `"PG"` over the
   wire. Verify during Phase 3 that no extra coercer setup is needed
   for `*FacetValue.value` fields whose type is an enum.

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
