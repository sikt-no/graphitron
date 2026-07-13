---
id: R13
title: "Faceted search on `@asConnection`"
status: Spec
priority: 7
theme: pagination
depends-on: []
last-updated: 2026-07-13
---

# Faceted search on `@asConnection`: `@asFacet` directive

> Add a `@asFacet` directive for filter-input fields. `ConnectionPromoter`
> (the field-first `@asConnection` synthesis pass) grows a facet arm: each
> marked input field becomes an entry on a synthesised `XConnectionFacets`
> object attached as `facets` on the generated Connection type. The facet
> specs ride the first-class `GraphitronType.ConnectionType` entry (not the
> per-site `FieldWrapper.Connection`); the connection fetcher carries a
> facet plan on `ConnectionResult` and a `ConnectionHelper.facets` resolver
> issues one `UNION ALL` aggregate query per request, each arm computing one
> facet's counts under its filter-minus-self predicate. This rides the
> connection machinery as `totalCount` does today (at the `ConnectionResult`
> / `ConnectionHelper` runtime layer), leaving the
> general-dispatch `Operation.Facet` arm unpopulated behind the ConnectionType
> quarantine for R314 to fold in later (see *Contained approach* below).
> Phase 1 spike confirmed the `UNION ALL` shape over `GROUPING SETS`
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
    rating:   [MpaaRatingFacetValue!]
    category: [StringFacetValue!]
}
# Each per-facet field is nullable; the list elements stay non-null (a null
# entry in a facet list is meaningless). A facet is a best-effort aggregate,
# not a structural guarantee: a nullable field firewalls GraphQL non-null
# propagation at the individual facet, so one facet failing or timing out
# nulls only that field, never its siblings and never the connection. This
# also keeps the wire contract stable if facets are later split into one
# query per field that can succeed or fail individually. See "Facet failure
# semantics" below.

# Per-scalar named types. value mirrors the filter-input field's element
# type exactly: same scalar AND same nullability, so a client filters by
# the same value it sees in facets, no coercion: filter: { rating: [facetValue.value] }.
# Both filter fields above use non-null elements ([MpaaRating!] / [String!]),
# so value is non-null here and each arm scrubs its NULL group (... IS NOT NULL).
# A nullable filter element ([MpaaRating]) yields value: MpaaRating and
# preserves the NULL bucket. See "NULL facet buckets" below.
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

### Facet failure semantics

Facets are a best-effort aggregate layered onto the connection, never a
structural guarantee, and the output nullability is chosen to keep them
that way:

- **`facets` (the whole object) is nullable**, and so is **every per-facet
  field under it** (`[<Scalar>FacetValue!]`). The list elements and the
  inner `value` / `count` stay non-null, but each now sits under a nullable
  ancestor one hop up, so GraphQL non-null propagation can never bubble a
  facet failure past its own facet field.
- **A facet query failure or timeout degrades to null, it never escalates
  to a connection-level or request-level error.** The page query
  (`edges` / `nodes` / `pageInfo`) resolves in a separate DataFetcher and is
  unaffected; a client that sets an aggressive statement timeout on facet
  aggregation gets `facets: null` (or a null individual facet field) plus an
  entry in the GraphQL `errors` array, while the page of results still
  returns. The resolver must surface facet failures this way rather than
  letting the exception abort the whole response.
- **v1 blast radius is all-or-nothing per request, by design.** Because v1
  issues one `UNION ALL` for every selected facet, a single slow arm fails
  the whole statement and the resolver returns `facets: null` (not a partial
  map). The per-facet field nullability does not change v1 runtime behaviour;
  it pins the *contract* so that splitting facets into one query per field
  later, so each can succeed or fail individually, is a resolver change with
  no schema or wire-compat impact.

## Current State

The connection pipeline was rebuilt twice since this plan's first draft:
the emit-time `ConnectionSynthesis` pass is gone (R279 slice 5 made it
field-first), and R316 moved per-Connection-type metadata off the field
wrapper onto a first-class `GraphitronType` entry. The anchors below are
the current ones; the retired names appear only to mark what moved.

- **Synthesis is field-first in `ConnectionPromoter`** (`rewrite/ConnectionPromoter.java`),
  not the retired `ConnectionSynthesis.buildPlan()`. `synthesiseForField`
  runs once per visited field during the classification walk: when the
  field is an `@asConnection` (or structural) connection carrier it builds
  the connection / edge / page-info `GraphQLObjectType` schema forms
  (`buildSynthesisedConnection` / `buildSynthesisedEdge`), registers them
  through `ctx.typeRegistry.register`, and notes the synthesised names in a
  `synthesisedNames` set. `rebuildAssembledForConnections` then rewrites
  the carrier field's return type and appends `first` / `after`, and adds
  the synthesised types via `additionalType(...)`. Nothing there knows
  about facets yet.
- **Per-type Connection metadata lives on `GraphitronType.ConnectionType`**
  (`model/GraphitronType.java:510`), a sealed arm implementing
  `EmitsPerTypeFile`, carrying `name`, `elementTypeName`, `edgeTypeName`,
  `itemNullable`, `shareable`, and the `GraphQLObjectType schemaType`.
  `FieldWrapper.Connection` (`model/FieldWrapper.java:73`) is now a slim
  2-arg record `(boolean connectionNullable, int defaultPageSize)` carrying
  only per-carrier-site facts; `connectionName` and `itemNullable` moved to
  `ConnectionType`. **This is the structural reason facet specs belong on
  `ConnectionType`, not the wrapper** (see Phase 3).
- `TypeFetcherGenerator.buildQueryConnectionFetcher` (`:5081`) emits a
  single keyset-paginated SELECT, builds the full `condition` via
  `buildConditionCall(qtf, tableLocal, ...)`, and wraps the result as
  `new ConnectionResult(result, page, tableLocal, condition)`. No secondary
  aggregation queries. The jOOQ table local is `names.tableLocalName()`
  and emitted code is `var`-free.
- **`totalCount` is the template for the contained facet approach.**
  `ConnectionResult` (`generators/util/ConnectionResultClassGenerator.java`)
  already carries the parent field's `Table<?>` and `Condition` (nullable;
  Split-Connection scatter passes null), and its Javadoc names
  "faceted-search aggregates" as the future second reader of those.
  `ConnectionHelper.totalCount(env)` reads them and issues
  `dsl.selectCount().from(cr.table()).where(cr.condition())`, lazy on
  selection. The per-connection `<Conn>Fetchers.totalCount` is a thin
  delegate (`ConnectionFetcherClassGenerator`), and
  `FetcherRegistrationsEmitter.connectionBody` wires it via
  `codeRegistry.dataFetcher(FieldCoordinates.coordinates(...), <Conn>Fetchers::totalCount)`
  behind an SDL-presence gate.
- Synthesised types are emitted as per-type `<Name>Type` schema classes;
  `GraphitronSchemaClassGenerator.generate()` registers each via
  `schemaBuilder.additionalType(<Name>Type.type())`. Any new
  `GraphitronType` arm implementing `EmitsPerTypeFile` rides this path.
- Filter-input types classify into `InputField` sealed subclasses
  (`model/InputField.java`): `ColumnField`, `ColumnReferenceField`,
  `CompositeColumnField`, `CompositeColumnReferenceField`, `NestingField`,
  `UnboundField`, plus the `LookupKeyField` / `SetField` sub-seals. None
  carries a facet flag. The `[ID!] @nodeId(typeName: T)` reference shape
  surfaces as `ColumnReferenceField` / `CompositeColumnReferenceField`
  carrying `extraction = CallSiteExtraction.NodeIdDecodeKeys`; Phase 3's
  `@asFacet` rejection list must rule on those carriers (see Non-goals).
  (`PlatformIdField`, named in the old draft, is gone; `UnboundField` is
  new.)
- `BuildContext` lists every directive the rewrite reads in its `DIR_*`
  constant block (`:102`); there is no `DIR_FACET`. `DIR_AS_CONNECTION`,
  `ARG_CONNECTION_NAME`, `ARG_DEFAULT_FIRST_VALUE` are present.
- Conditions are generated into per-query `QueryConditions` /
  `MutationConditions` classes (`QueryConditionsGenerator`), one
  `<field>Condition(table, env)` method per query field, composing the
  filter-input predicates into one jOOQ `Condition`. The method ANDs all
  its fields internally, so the fetcher cannot ask it to "skip facet field
  X"; this shapes Phase 4's condition-minus-self strategy (see below).
- No execution-test fixture combines `@asConnection` with a `@table`-backed
  filter input today; the test-spec `schema.graphqls` has connection
  variants but only argument-level scalar filters.

## Desired End State

- New `@asFacet` directive declared in rewrite's own directive resource
  (`graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`).
- `ConnectionPromoter` grows a facet arm: for each `@asConnection` field
  whose filter input has `@asFacet`-marked fields, `synthesiseForField`
  derives a `List<FacetSpec>`, appends a `facets: <ConnName>Facets` field to
  the connection's `GraphQLObjectType`, and registers one
  `<ConnName>FacetsType` per Connection plus one reusable
  `<Scalar>FacetValueType` per distinct value scalar through
  `ctx.typeRegistry.register` / `synthesisedNames`. Those types ride the
  existing `additionalType(...)` + per-type `<Name>Type` emit path.
- `GraphitronType.ConnectionType` carries the `List<FacetSpec>`; new
  `EmitsPerTypeFile` arms (`FacetsType`, `FacetValueType`) carry the
  synthesised facet object types.
- The connection fetcher carries a facet plan on `ConnectionResult`
  (the per-facet columns, a base condition over non-facet fields, and each
  facet's own predicate), and a new `ConnectionHelper.facets(env)` resolver
  assembles **one** `UNION ALL` aggregate query per request, one arm per
  selected facet. Each arm's `WHERE` applies the full Connection filter
  *minus that facet's own predicate*, so a selected facet value still shows
  its siblings' counts. Each arm can use per-facet indexes; `Parallel Append`
  executes arms concurrently. This is `totalCount`'s shape, extended.
- `FetcherRegistrationsEmitter.connectionBody` and
  `ConnectionFetcherClassGenerator` wire a `facets` dataFetcher behind a
  has-facets gate (parallel to the `totalCount` SDL-presence gate); the
  `*FacetValue` types need no fetcher wiring (graphql-java's default property
  fetcher reads `value` / `count` from the inner maps).
- Execution tests against Sakila confirm counts match plain SQL aggregates,
  including when a facet's own predicate is active.

## Contained approach: facets ride the connection machinery, like `totalCount`

Faceting sits on the **operation** axis, but the shipped code and the R333
spec now say that in two different vocabularies, and the plan must not
conflate them:

- **Shipped code (R316's operation seal).** `Operation.Facet`
  (`model/Operation.java:93`) is a modeled-but-unpopulated arm, sibling to
  `Operation.Count` (`totalCount`, `:90`) and `Operation.Paginate`; the class
  Javadoc places both connection aggregates "behind the ConnectionType
  quarantine." The quarantine is the fact that connections are not yet
  lowered through the general `(source, operation, target)` dispatch; they
  are emitted as a self-contained unit (`ConnectionPromoter` + the connection
  fetcher + `ConnectionHelper`), and `totalCount` already computes its own
  aggregate *inside* that unit without populating `Operation.Count`.
- **R333's current spec (the fact model).** R333 normalizes R316's operation
  enum away: `coordinate -> operation` is a 0..N *set* whose member kinds are
  `select` / `join` / `paginate` / `condition` / `orderBy` / `serviceCall` /
  DML, and `@asConnection` lowers to `operation: paginate` in its
  directive-coverage audit. `Count` and `Facet` appear in R333 only in its
  R316-axis normalization table (a mechanical mapping of the old enum arms
  onto the set), **not** in the operation-kind catalog or the member-to-seam
  crosswalk: R333's target vocabulary carries no aggregate member at all, for
  facets *or* for `totalCount`. "Quarantine" survives only in
  `Operation.java`'s Javadoc.

This plan deliberately takes the **contained** route: facets ride the same
connection machinery as `totalCount`, and `Operation.Facet` stays
unpopulated. The original justification (not new debt, the same shape as a
shipped feature, dissolving the same way) survives the R333 rewrite and gets
stronger: populating `Operation.Facet` now would populate an arm R333's
normalized operation set *drops*, wiring facets into a vocabulary with a
demolition date. The contained route is also how R333 already implicitly
treats the whole connection-aggregate family: `totalCount` has no fact, no
operation member, and no directive-coverage row (it is not
directive-triggered); the aggregates live inside the connection unit, and
R314 folds them into whatever aggregate operation member the fact model mints
when it lowers connections. The two aggregates move together or not at all.
Until then, facets do not block on R333/R314, and they do not deepen the
quarantine beyond the precedent `totalCount` already set.

Concretely, this rules out two tempting-but-wrong shapes:
- **No `FacetedConnectionType`.** Faceting is an operation fact, not a
  target-type variant; R333's thesis is "a capability adds a fact, not a leaf
  type," and a connection-type subtype would re-fuse the operation axis onto
  the target axis that R316 split apart.
- **No premature `Operation.Facet` population.** Wiring facets through general
  dispatch now would mean dissolving the connection quarantine ahead of R314,
  which is exactly the large structural program the contained route avoids;
  and per the above, the arm itself is not carried forward by R333's target
  vocabulary, so populating it would build onto a condemned axis.

What `ConnectionType.facets()` is, precisely: a **contained view carrier,
not the fact's normalized home**. `totalCount` is precedent at the runtime
layer (`ConnectionResult` / `ConnectionHelper`), but it sets no
`ConnectionType` precedent; it carries nothing on the per-type model entry.
Facets do need per-facet resolved bindings (column, scalar, nullability), and
in R333's terms those are the *use-site resolved* projection of the authored
`@asFacet` fact: the directive is authored at the filter input type's member
coordinate (definition-keyed), while the resolved binding is a derived join
over (input member coordinate, consuming `@asConnection` coordinate), which
is why the promoter resolves one `List<FacetSpec>` per Connection type
against that carrier's table. Landing the resolved list on `ConnectionType`
is acceptable exactly because R333 treats the `GraphitronType` entries as
denormalized views over facts, views that dissolve: when R314 lowers the
connection unit, `facets()` re-sources onto the operation fact with the rest
of the view. Framed as the fact's permanent home it would be the
operation-onto-target re-fusion this section rules out; framed as the view
carrier it is the additive "capability adds a fact" move R333 endorses.

### R333 handshake (synced 2026-07-13)

R13's Ready sign-off (2026-06-26) predates R333's rewrite (last-updated
2026-07-05). Besides the re-grounding above, three R333 commitments bear on
this plan:

- **Directive coverage.** R333 claims closure over *active* directives
  ("every active directive's effect has an owning fact"), and its precedent
  for unshipped directives is `@capability` / `@exemplifies`: reserve the
  slot, join the audit table on ship. `@asFacet` is in the same position; an
  unshipped directive is not a closure gap. When Phase 2 lands, R333's
  *Directive coverage* table gains an `@asFacet` row (a one-line edit to a
  living Spec); the honest owning-fact entry today is the contained
  connection unit (`ConnectionType.facets()` as view carrier), with the
  operation-fact home deliberately undecided until R314 mints the aggregate
  member. Note for that future fold: the facet aggregate is a separate query
  with its own per-arm WHERE, so its operation home is a distinct aggregate
  operation at its own anchor, not a sub-fact of `paginate`.
- **Definition-keyed vs use-keyed rejections.** R333's natural-keys and
  *Input coordinates* sections split input-field facts into the authored fact
  at the input type's member coordinate and the use-site binding as a derived
  join over (input member coordinate, consuming coordinate). Phase 3's
  rejection split follows that axis, not an implementation limitation:
  directive co-occurrence (`@asFacet` alongside `@reference` / `@condition`,
  or missing `@field`) is authored-directive presence, definition-keyed, the
  promoter's job; the binding-kind rejections (composite / `[ID!] @nodeId`
  reference arms) fold in use-site resolution, so their principled home is
  `GraphitronSchemaValidator`, where the classified `InputField` arm is in
  hand; and "not reached via any `@asConnection` field" is the purely
  use-keyed case.
- **The projection seam and method-graph closure.** The new `FacetsType` /
  `FacetValueType` arms must project through `CatalogBuilder`'s exhaustive
  switch, which R333 names as *the* seam feeding the language server and the
  knowledge surface; updating it is what keeps the facet types describable
  outside code generation, not codegen bookkeeping. And Phase 4's generated
  fragments (`<field>FacetBaseCondition`, `<field>Facet_<g>Condition`, the
  `facets` resolver and delegate) form a closed method sub-graph in R333's
  back-half sense: every name called is a method we emit, the fetcher makes
  no decision the generated conditions could have made, and these seams are
  precisely what folds into the eventual facet operation.

### Verification

1. New pipeline test in `GraphitronSchemaBuilderTest` classifies a schema with
   `@asFacet` into a `GraphitronType.ConnectionType` whose `facets()` is non-empty.
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
  or composite/`[ID!]` reference fields (including the post-R50
  `[ID!] @nodeId(typeName: T)` shape carried by
  `InputField.ColumnReferenceField` / `CompositeColumnReferenceField`
  with `extraction = CallSiteExtraction.NodeIdDecodeKeys`).** Classifier rejects these at
  validate time; loosening is a follow-up. The v1 SQL emitter only
  understands direct-column facet values; a join-mediated reference
  field needs a different aggregation shape, tracked as a follow-up
  alongside the other reference-path cases.
- **Cross-facet independence semantics.** v1 applies "all filters except this
  facet's own predicate" per facet (conventional UX expectation). Alternative
  semantics (AND-all, OR-all) are follow-ups if a real use case surfaces.

## Key Discoveries

- **Extend the field-first `ConnectionPromoter`, not a Plan.**
  `ConnectionPromoter.synthesiseForField` already reads the carrier
  field's applied directives and builds the connection / edge
  `GraphQLObjectType` forms (`buildSynthesisedConnection`), registering
  them via `ctx.typeRegistry.register` and noting absent names in
  `synthesisedNames` so `rebuildAssembledForConnections` adds them via
  `additionalType(...)`. Facets ride the same walk: read `@asFacet` off
  the filter-input argument, append a `facets` field in
  `buildSynthesisedConnection`, and register the `FacetsType` /
  `FacetValueType` entries the same way. No separate Plan and no
  `ObjectTypeGenerator` rewrite (that generator is emission-only now;
  the carrier rewrite happens in `rebuildAssembledForConnections`).
- **Facet specs live on `GraphitronType.ConnectionType`, not the wrapper.**
  R316 slimmed `FieldWrapper.Connection` to per-carrier-site facts and
  moved per-type metadata (`connectionName`, `itemNullable`) onto the
  first-class `ConnectionType` entry. `FacetSpec` is per-Connection-type
  metadata (which columns, which value scalars), so it belongs beside
  `elementTypeName` / `edgeTypeName` on `ConnectionType`. The deprecation
  of `@asConnection(connectionName:)` ("each connection field owns its
  own Connection type") means per-type and per-carrier coincide, so there
  is no ambiguity. Under R333 the entry is a *view carrier*, not the
  fact's normalized home; see *Contained approach* for the precise
  framing and the R314 re-sourcing path.
- **Single directive-declaration file.** `@asFacet` is declared in
  rewrite's own `directives.graphqls`. The schema loader auto-injects it
  before classification.
- **New `EmitsPerTypeFile` arms for the facet object types.** `FacetsType`
  and `FacetValueType` join the `GraphitronType` seal; each carries a
  `GraphQLObjectType schemaType` and rides the existing
  `additionalType(<Name>Type.type())` emit. This *does* add sealed leaves
  (unlike the original draft's "extend an existing record" framing), so it
  touches `VariantCoverageTest` and the exhaustive `GraphitronType` switches
  (`CatalogBuilder`, `GraphitronSchemaValidator`, `FetcherRegistrationsEmitter`).
  The `CatalogBuilder` switch is not bookkeeping: R333 names its exhaustive
  projection as the seam feeding the language server and the knowledge
  surface, so the new arms must project through it for hover / describe to
  stay complete. An implementer may collapse the two into one
  synthesised-object arm to hold leaf count down; decide during Phase 2.
- **Per-facet self-predicate stripping** needs the condition built
  compositionally. The generated `QueryConditions.<field>Condition` folds
  all argument predicates into one, so the fetcher cannot ask it to skip a
  facet field. The contained plan reconstructs a base condition (non-facet
  fields) plus each facet's own predicate in the fetcher, carries them on
  `ConnectionResult`, and the `facets` resolver assembles
  `base AND (⋀ g≠f predicate_g)` per arm (see Phase 4).
- **`totalCount` is the working precedent.** `ConnectionResult` already
  carries `(table, condition)` for exactly this kind of self-contained
  secondary aggregate, and `ConnectionHelper.totalCount` shows the
  lazy-on-selection, scatter-returns-null shape facets reuse.
- **Facet value types are cross-schema reusable.** `StringFacetValue`,
  `BooleanFacetValue`, `IntFacetValue`, `<Enum>FacetValue`: one per
  (value scalar, element nullability) encountered across the whole schema,
  not per connection. Synthesise-once via a single
  `FacetNaming.facetValueTypeName(scalar, nullable)` helper used by both
  `ConnectionPromoter` and the classifier; keying on nullability as well as
  scalar means a non-null and a nullable facet over the same scalar get
  distinct names instead of colliding.

## Implementation Approach

Five v1 phases plus Phase 6 deferred, in strict order; each phase
leaves the build green and existing tests passing. No phase adds
user-observable behaviour until Phase 4; Phase 5 is test coverage.
Phases 2 and 3 land as a **single commit**: the `FacetSpec` record and
the `ConnectionType.facets()` component (nominally Phase 3) are the home
Phase 2's synthesis arm writes into and Phase 2's tests read back, so they
must exist together. The Phase 2/3 split below is a narrative ordering
(synthesis first, then rejection/validation), not two shippable increments.
Phase 1 is a measurement spike that validates or redirects the SQL
strategy *before* emitter work begins; its deliverables are a report
plus any plan revisions it motivates. Phase 6 ships hierarchical
facets after v1 lands.

| Phase | Module / artefact | What lands |
|---|---|---|
| 1 | hand-written SQL (complete) | Spike — benchmarked SQL strategies against Sakila; confirmed shape C as v1 default; resolved NULL + ordering Open Questions. Outcome captured in Phase 1 Outcome below |
| 2 | `graphitron-rewrite` (directive + `ConnectionPromoter` + model) | `@asFacet` directive definition; `FacetSpec` record + `ConnectionType.facets()` carrier; `ConnectionPromoter` grows a facet arm that registers `FacetsType` / `FacetValueType` entries and appends the `facets` field on the synthesised Connection |
| 3 | `graphitron-rewrite` (classifier) | classifier / validator rejects misuse (lands with Phase 2 as one commit) |
| 4 | `graphitron-rewrite` (emitter) | Fetcher carries a facet plan on `ConnectionResult`; `ConnectionHelper.facets` emits the spike-chosen `UNION ALL` aggregate; registration wires the new field |
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
with count 10 000 and no cast or special handling.

Whether that NULL bucket surfaces is driven by the annotated filter
field's element nullability, so output mirrors input:

- **Nullable filter element** (`rating: [MpaaRating]`): `value` is
  nullable (`MpaaRating`), the NULL bucket is preserved as its own
  group, and it round-trips (`filter: { rating: [null] }`). The
  emitter injects no `IS NOT NULL`.
- **Non-null filter element** (`rating: [MpaaRating!]`): `value` is
  non-null (`MpaaRating!`), and the facet's arm appends
  `AND <col> IS NOT NULL` so no NULL key reaches a non-null output
  field. Without the scrub a non-null `value` is a latent runtime
  failure on the first NULL-bearing column; the scrub makes the
  non-null output contract one the resolver actually keeps.

`FacetSpec` carries this as a `valueNullable` flag (see Phase 3); it
drives both the `*FacetValue` type name (Phase 2 dedup key) and the
per-arm `IS NOT NULL` emit (Phase 4).

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

Declare `@asFacet` in rewrite's own directives resource and extend
`ConnectionPromoter` so each `@asConnection` field's `@asFacet`-bearing
filter inputs produce a `facets` field on the synthesised Connection type,
one `<ConnName>FacetsType` per Connection, and one reusable
`<Scalar>FacetValueType` per distinct value scalar.

### Changes

#### `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`

Add:

```graphql
"""
Marks a filter-input field as a facet on the enclosing `@asConnection`
field's generated Connection type. The Connection type gains a
`facets: XConnectionFacets` field; each `@asFacet`-marked input field
becomes an entry there, returning `[XFacetValue!]` (nullable list, non-null
elements) with per-value counts.

Only valid on fields of an input type used as the filter input of an
`@asConnection`-bearing field. The input field must be bound to a
column via `@field(name:)` (reference / condition / composite-key
bindings are rejected in v1).
"""
directive @asFacet on INPUT_FIELD_DEFINITION
```

#### Extend `ConnectionPromoter`

`ConnectionPromoter.synthesiseForField` is the natural seam: it already
reads the carrier field's applied directives, builds the connection / edge
schema forms, and registers them. Facets ride the same walk.

- In `promotionFor(...)`, when the carrier carries `@asConnection`, walk
  its filter-input argument and read `@asFacet` off each input field. Carry
  the resulting `List<FacetSpec>` on the `ConnectionPromotion` record so the
  registration step below can place it on `ConnectionType` (Phase 3).
- In `buildSynthesisedConnection(...)`, append a
  `facets: <ConnName>Facets` field (nullable, the whole facets object may be
  absent) to the connection's `GraphQLObjectType` when the facet list is
  non-empty. The field name is `facets`.
- In `synthesiseForField(...)`, register one `FacetsType` per faceted
  Connection plus one `FacetValueType` per distinct (value scalar,
  nullability) pair through
  `registerSynthesised(ctx, name, type, synthesisedNames)` (the same path
  that notes absent names for `rebuildAssembledForConnections` to add via
  `additionalType`). The `<Scalar>FacetValue` name comes from the shared
  `FacetNaming.facetValueTypeName(scalar, nullable)` helper, deduped by name
  across the whole schema.
- `rebuildAssembledForConnections` and
  `GraphitronSchemaClassGenerator.generate()` need no facet-specific change:
  the new `FacetsType` / `FacetValueType` arms implement `EmitsPerTypeFile`,
  so they flow through the existing `additionalType(<Name>Type.type())` emit.

For each `@asConnection` field, the facet walk does, per `@asFacet` input field:

1. Resolve the value scalar (the input field's GraphQL type, stripped of
   list / non-null) and note the **element nullability** (was the list
   element `MpaaRating!` or `MpaaRating`). For scalar / enum leaves the
   scalar is the facet value type; the nullability carries to the output.
2. Register a `FacetValueType` for that (scalar, nullability) pair, deduped
   by the derived type name. `value` mirrors the filter-input field's
   element type exactly, same scalar **and** same nullability, preserving
   round-trip symmetry:
   ```graphql
   # non-null filter elements ([MpaaRating!] / [String!] / [Boolean!] / [Int!])
   type MpaaRatingFacetValue { value: MpaaRating! count: Int! }
   type StringFacetValue     { value: String!     count: Int! }
   type BooleanFacetValue    { value: Boolean!    count: Int! }
   type IntFacetValue        { value: Int!        count: Int! }
   # a nullable filter element ([MpaaRating]) yields a nullable value and a
   # distinct derived name (FacetNaming's to fix, e.g. MpaaRatingFacetValueOrNull)
   type MpaaRatingFacetValueOrNull { value: MpaaRating count: Int! }
   ```
   A client feeds `facetValue.value` straight back into the filter input
   with no conversion. Custom scalars synthesise `<CustomScalar>FacetValue`
   on demand the same way. `FacetNaming.facetValueTypeName(scalar, nullable)`
   is the source of truth for the derived name, keyed on **both** the scalar
   and the element nullability so a non-null and a nullable facet over the
   same scalar never collide on one type; it is shared with the classifier
   (Phase 3).
3. Register one `<ConnName>Facets` `FacetsType` with one nullable list field
   (`[<Scalar>FacetValue!]`, non-null elements) per `@asFacet` input, field
   name matching the input field name. The field is nullable so a single
   facet can fail independently (see "Facet failure semantics"); the list
   element stays non-null because a null entry in a facet list is meaningless.

If the carrier has no filter input, or the filter input has no `@asFacet`
fields, no facet entries are registered and the Connection is synthesised
exactly as today. No error, no warning.

### Success Criteria

- [ ] `mvn test -pl :graphitron-rewrite -Pquick`: new `ConnectionPromoter`
      (or successor) test cases cover an SDL with `@asFacet` and assert:
      `ConnectionType.facets()` carries one `FacetSpec` per marked field;
      the registered types include `<ConnName>Facets` (one list field per
      `@asFacet`) and each `<Scalar>FacetValue` (with `value` + `count`);
      and the synthesised Connection's `schemaType` has a `facets` field.
- [ ] **Facet-field nullability (the firewall) is pinned, not just prose.**
      On the synthesised `<ConnName>Facets` `schemaType`, assert each
      per-facet field is a **nullable** list of non-null elements
      (`[<Scalar>FacetValue!]`, i.e. `GraphQLList` whose wrapped type is the
      `FacetValue` object, *not* wrapped in `GraphQLNonNull` at the field
      level), and that the `facets` field on the Connection is itself
      nullable. This is the assertion that pins "Facet failure semantics":
      without it the firewall claim rests only on prose.
- [ ] Existing connection-synthesis fixtures unchanged.
- [ ] The new facet types classify cleanly. Because they are registered as
      first-class `GraphitronType` arms (not left as `UnclassifiedType`),
      no allowlist shim is needed; confirm `VariantCoverageTest` and the
      exhaustive `GraphitronType` switches are updated for the new arms.

---

## Phase 3: Classifier, `FacetSpec` on `GraphitronType.ConnectionType`

### Overview

Phase 2 reads `@asFacet` during the connection-synthesis walk. Phase 3
gives the specs a typed home and the misuse rejections. The home is
`GraphitronType.ConnectionType`, not `FieldWrapper.Connection`: R316 moved
per-Connection-type metadata onto the first-class type entry, and a facet
list is exactly that. The emitter (Phase 4) reads `ConnectionType.facets()`,
not the SDL.

The `FacetSpec` record and the `ConnectionType.facets()` component land
**with Phase 2 in the same commit** (Phase 2's synthesis arm populates them
and its tests read them back); what is genuinely Phase 3 work is the
classifier/validator rejection logic below.

### Changes

#### `BuildContext` — new directive constant

Add to the `DIR_*` constant block (`:102`):

```java
static final String DIR_FACET = "asFacet";
```

#### New `model/FacetSpec.java`

```java
public record FacetSpec(
    String inputFieldName,    // e.g. "rating"
    String columnName,        // e.g. "RATING"
    String valueTypeName,     // e.g. "MpaaRating"
    boolean valueNullable,    // mirrors the filter field's element nullability
    String facetValueTypeName // e.g. "MpaaRatingFacetValue"
) {}
```

Carries exactly what the emitter needs: which column to `GROUP BY`, what
GraphQL type the scalar value has (for wiring the `value` field), whether
the value is nullable, and what `*FacetValue` object type to instantiate.
`valueNullable` mirrors the annotated filter field's list-element
nullability; it drives the `*FacetValue` type name (via
`FacetNaming.facetValueTypeName(scalar, nullable)`) and the Phase 4 per-arm
scrub: `false` appends `AND <col> IS NOT NULL` so a non-null `value` can
never receive a NULL group key. (Phase 6 keeps room to grow this into a
sealed `FlatFacetSpec` / `HierarchicalFacetSpec`; v1 is the flat record.)

#### `model/GraphitronType.java`: `ConnectionType` carries `List<FacetSpec>`

Add a `List<FacetSpec> facets` component to the `ConnectionType` record
(empty when no `@asFacet` fields), beside `elementTypeName` /
`edgeTypeName`. Both `ConnectionType` construction sites pass it: the
`ConnectionPromoter` synthesis path (the populated list, Phase 2) and the
`TypeRegistry` re-materialisation (`TypeRegistry.java:119`, which rebuilds
the entry on a tag-union merge; forward the existing list). Also add the
new `FacetsType` / `FacetValueType` arms here (see Phase 2 Key Discoveries
on the leaf-count choice).

#### `ConnectionPromoter` / classifier: populate and reject

The Phase 2 facet walk derives each `FacetSpec`:

1. Each `@asFacet` field must also carry `@field(name:)` (the `columnName`).
2. Each `@asFacet` field's GraphQL leaf scalar / enum is its `valueTypeName`;
   the field's list-element nullability is its `valueNullable`.
3. `facetValueTypeName` comes from
   `FacetNaming.facetValueTypeName(scalar, valueNullable)`, the same helper
   Phase 2 uses, so the two never drift.

Reject:

- `@asFacet` on an input field that is not plain-`@field`-bound: it
  co-occurs with `@reference` / `@condition`, has no `@field`, or is a
  composite / `[ID!] @nodeId` reference (`ColumnReferenceField` /
  `CompositeColumnReferenceField` carrying
  `CallSiteExtraction.NodeIdDecodeKeys`). The v1 SQL emitter only
  understands direct-column facets. A shallow directive-level check in the
  promoter catches the co-occurrence cases (definition-keyed:
  authored-directive presence at the input type's member coordinate); the
  binding-kind cases (composite / reference) fold in use-site resolution, so
  their principled home is `GraphitronSchemaValidator`, where the classified
  `InputField` arm is in hand; that placement is prescribed by R333's
  definition-keyed / use-keyed split, not just a workaround for what the
  promoter can see at synthesis time.
- `@asFacet` on a field whose enclosing input type is not reached via *any*
  `@asConnection` field (the `facets` expansion would be dead schema). A
  filter input type shared by a connection consumer and a non-connection
  consumer is fine: `@asFacet` surfaces facets at the connection use sites
  and is inert at the others; the rejection fires only when no use site is
  an `@asConnection` field. (This is R333's definition-keyed / use-keyed
  split: the directive is authored at the input type's member coordinate,
  its validity is a derived join over the consuming coordinates. See the
  *R333 handshake* under Contained approach.)

Surface rejections as a classification error with a message naming the
field (the rewrite's existing `UnclassifiedField` / validator-error
channel; pick whichever the surrounding connection-misuse rejections
already use, so facet errors read consistently with them).

#### `GraphitronSchemaValidator`

`validateConnectionType` (`GraphitronSchemaValidator.java:507`) is the
natural home for any rejection that needs the classified input surface (the
composite / reference binding cases above). Add the rule there if the
promoter-level check cannot reach the binding kind.

### Success Criteria

- [ ] `mvn test -pl :graphitron-rewrite -Pquick` — existing tests pass.
- [ ] New pipeline test: schema with two `@asFacet` inputs on a filter →
      the classified `ConnectionType.facets()` has two entries with correct
      column names and value types.
- [ ] New pipeline test: `@asFacet` on a `@reference`-bound input field →
      classification error with a specific message.
- [ ] `VariantCoverageTest` updated for the new `FacetsType` /
      `FacetValueType` arms (the leaf-count decision from Phase 2).

---

## Phase 4: Emitter, facet plan on `ConnectionResult` + `ConnectionHelper.facets`

### Overview

This is `totalCount`, extended. `totalCount` carries `(table, condition)`
on `ConnectionResult` and lets `ConnectionHelper.totalCount(env)` issue its
own aggregate, lazy on selection. Facets carry a richer **facet plan** on
`ConnectionResult` and let a new `ConnectionHelper.facets(env)` resolver
issue one `UNION ALL` of per-facet `GROUP BY` arms, each under its
filter-minus-self predicate, value column cast to `TEXT` to unify the arm
types, decoded back per column. The per-connection `<Conn>Fetchers.facets`
is a thin delegate, exactly like `<Conn>Fetchers.totalCount`. The paginated
`edges` / `nodes` query is untouched.

The heavy SQL stays in `ConnectionHelper` (one hand-auditable home, the
explicit design intent in its Javadoc); the fetcher only *builds the plan*.

### Changes

#### `ConnectionResultClassGenerator`: carry a facet plan

Beside the existing nullable `(table, condition)`, add a nullable facet
plan: the base condition (non-facet fields), a `Map<String, Condition>` of
each facet's own predicate keyed by facet label, and the `List<FacetSpec>`
(label + `columnName` + `valueNullable`) the resolver needs to build arms
and decode. Each `Condition` is the result of a generated
`<field>FacetBaseCondition` / `<field>Facet_<g>Condition` call, not built
inline, so the binding stays inside the adapter. Add a
nested `FacetValueRow(Object value, int count)` carrier if convenient, or
let the resolver return graphql-java-shaped maps directly. Split-Connection
scatter passes `null` (the `facets` resolver returns `null` there, matching
the `totalCount` scatter contract). `ConnectionResult` is in
`<outputPackage>.util` alongside `ConnectionHelper`.

#### `QueryConditionsGenerator`: a non-facet base condition + per-facet fragments

The generated `<field>Condition(table, env)` ANDs every filter field,
facets included, and backs the page query unchanged. Add two additive
siblings, both riding the existing binding-correct adapter machinery, so
String-delivered enums / IDs coerce through the column's `Converter` per the
"Column value binding" convention and no raw-value handling leaks into the
fetcher:

- `<field>FacetBaseCondition(table, env)` ANDs only the **non**-facet
  fields (skipping every `@asFacet`-marked input field). This is the base
  the resolver builds filter-minus-self from.
- `<field>Facet_<g>Condition(table, env)` per facet `g`: just that facet's
  own predicate (the column-equality / `IN` over `g`'s input values), with
  the same absent-input to no-conjunct gate the main method already applies.
  The resolver composes these; it never reconstructs a predicate from raw
  `env` values.

These are the only generator touch-points; they keep facet knowledge out of
the main condition method's body and keep every value binding inside the
typed `QueryConditions` boundary (the adapter half of the adapter/composer
pair). (Which class owns these follows wherever the connection's filter
condition is generated today; mirror that.)

#### `TypeFetcherGenerator.buildQueryConnectionFetcher` (`:5081`): build the plan

Today the fetcher builds the full `condition` and wraps
`new ConnectionResult(result, page, tableLocal, condition)`. When
`conn`'s `ConnectionType.facets()` is non-empty, additionally call:

- `<field>FacetBaseCondition(tableLocal, env)` for the **base condition**;
- `<field>Facet_<g>Condition(tableLocal, env)` for each facet `g`'s own
  predicate.

Both are generated `QueryConditions` fragments (above), so the fetcher only
*calls* them and collects the results; it does not read `env.getArgument`
or build predicates itself, which keeps value binding inside the adapter and
avoids the enum / ID `String`-coercion trap. Pass the base plus the
`Map<facetLabel, Condition>` of own-predicates to a facet-carrying
`ConnectionResult` constructor. The fetcher does **not** issue the
aggregate; it only assembles the plan, so its output stays byte-identical to
today whenever `facets()` is empty.

#### `ConnectionHelperClassGenerator`: `facets(env)` resolver

A generic static, the facet sibling of `totalCount`:

```text
facets(env):
  cr = env.getSource()
  if (cr.facetPlan() == null) return null            // scatter path
  selected = facets under `facets` in env.getSelectionSet()   // selection gate
  if (selected.isEmpty()) return Map.of()            // no arm, no SQL
  for each selected facet f:
     // base and perFacet[g] are pre-built Condition objects from the
     // generated QueryConditions fragments; composition only ANDs them.
     cond_minus_f = base.and(⋀ g≠f of perFacet[g])
     if (!f.valueNullable) cond_minus_f = cond_minus_f.and(col_f.isNotNull())
     arm_f = SELECT val(label_f) AS facet, col_f.cast(String) AS value,
                    count(*) AS cnt
             FROM cr.table() WHERE cond_minus_f GROUP BY col_f
  union = arm_0.unionAll(arm_1)...                    // one statement
  rows  = dsl.select().from(union)
             .orderBy(field("facet"), field("cnt").desc(), field("value")).fetch()
  decode each row: typed = cr.table().field(columnName).getDataType().convert(raw)
                   (null-safe: a preserved NULL bucket stays null; a non-null
                    facet emits no NULL key thanks to the IS NOT NULL scrub)
  return Map<facetLabel, List<{ "value": typed, "count": cnt }>>
```

`col.cast(String.class)` unifies the `value` column across arms so
`UNION ALL` parses; decode uses the column's own `DataType.convert` (the
same coercion `decodeCursor` already relies on), so no per-scalar parser
table is needed. Returns `Map<String, List<Map<String, Object>>>`;
graphql-java's default property fetcher exposes `value` / `count` from the
inner maps, so the `*FacetValue` types need no wiring. `DSLContext` comes
from the same `graphitronContext(env)` shim `totalCount` uses.

**N-facet fallback.** When `selected.size()` exceeds ~10 the UNION becomes
unwieldy; the resolver can issue N separate queries (shape B) and merge in
Java, same per-arm SQL. Resolver-local decision, no schema or classifier
change; defer writing the N-facet path until a schema crosses the threshold.

**jOOQ API surface (3.20.11):** `DSL.select(...)`, `DSL.val(...)`,
`Field.cast(Class)`, `SelectJoinStep.groupBy(Field)`,
`Select.unionAll(Select)`, `DSL.count()`, `ResultQuery.fetch()`,
`Field.getDataType().convert(...)`. No `DSL.groupingSets(...)` /
`DSL.grouping(...)`. Surface verified against the Phase 1 spike's SQL and
the existing `ConnectionHelper` cursor code.

#### Wiring: `FetcherRegistrationsEmitter` + `ConnectionFetcherClassGenerator`

`connectionBody` (`FetcherRegistrationsEmitter.java:137`) wires `edges` /
`nodes` / `pageInfo` and, behind a gate, `totalCount`. Add a `facets`
registration behind a has-facets gate (`!ct.facets().isEmpty()`), parallel
to the `totalCount` SDL-presence gate. `ConnectionFetcherClassGenerator`
(`:44`) adds a `facets` delegate under the same gate. The `*FacetValue`
types need no explicit fetcher wiring.

### Success Criteria

- [ ] `mvn verify -Pquick` on the whole tree.
- [ ] Schemas *without* `@asFacet` emit unchanged fetchers (structural diff:
      classify pre- and post-patch SDL with no `@asFacet`, assert identical
      `TypeSpec` for the fetcher method and unchanged `ConnectionResult`
      construction).
- [ ] Wiring test: a Connection with `@asFacet` fields registers a `facets`
      dataFetcher in its `connectionBody`, and `<Conn>Fetchers` has a
      `facets` delegate; the `*FacetValue` types are loadable.

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

`LANGUAGE_NAME` doesn't exist as a plain column on `film`; use a column
that does: pick `RATING` + a second scalar like `RENTAL_DURATION`
(Integer) so both an enum-scalar facet and an Integer-scalar facet are
exercised. Both use non-null elements (`[MpaaRating!]` / `[Int!]`), so
`value` is non-null and each arm carries the `IS NOT NULL` scrub; this is
the path execution-tested below. Values surface as native types over the
wire: enum values deserialize as `MpaaRating.PG`, integers as `3`.
Assertions compare typed values; this is also the test that pins the
round-trip property (`filter: { rating: [facetValue.value] }` works with no
coercion). Final column choice finalized during implementation.

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
facets are selected; lock this number in to catch regressions that
would re-introduce per-facet round-trips. When no facet field is in
the selection set, the aggregate is skipped: one round-trip.

The nullability split is pinned where each side is cleanest. The
non-null path (output `value` non-null + per-arm `IS NOT NULL` scrub) is
exercised here, since `rating` / `rental_duration` are non-null facet
elements. The nullable path (output `value` nullable + preserved NULL
bucket, no scrub) is pinned at the pipeline tier in Phase 2/3, asserting
the emitted `*FacetValue.value` nullability and the presence/absence of the
`IS NOT NULL` conjunct keyed on `FacetSpec.valueNullable`; Sakila's `film`
carries no clean NULL-bearing plain scalar column to drive a NULL-bucket
execution case, so the pipeline assertion is the authoritative check.

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

- **Unit:** none required; no new reflection / catalog probes.
- **Pipeline (synthesis):** new `ConnectionPromoter` (or successor) test
  cases cover registration of `<ConnName>Facets` / `<Scalar>FacetValue`
  types and the `facets` field on the synthesised Connection, and the no-op
  when no `@asFacet` is present.
- **Pipeline (classifier):** two new `GraphitronSchemaBuilderTest` cases:
  `@asFacet` populates `ConnectionType.facets()` correctly, and `@asFacet`
  on a non-`@field` binding is rejected with a specific message.
- **Wiring:** assert `connectionBody` wires a `facets` dataFetcher (and
  `<Conn>Fetchers` has the delegate) when the Connection has facets, and the
  `*FacetValue` types are loadable.
- **Execution:** three Sakila cases as above.
- **Regression:** existing connection tests unchanged; structural diff
  confirms fetcher output and `ConnectionResult` construction are
  byte-identical when `@asFacet` is absent.

## Resolved design decisions

- **Facet-value shape — per-(scalar, nullability) typed, mirroring the
  filter field's element type.** `value` matches the annotated filter
  field's list-element type exactly: same scalar and same nullability.
  Non-null element (`[MpaaRating!]`) yields `value: MpaaRating!` with an
  `IS NOT NULL` scrub on that arm; nullable element (`[MpaaRating]`) yields
  `value: MpaaRating` with the NULL bucket preserved. Rationale: a facet
  value is a candidate filter value; mirroring the input type preserves
  round-trip symmetry (`filter: { x: [facetValue.value] }` with no coercion)
  and keeps the non-null *output* contract one the resolver can actually
  keep (a `GROUP BY` can always surface a NULL key). The `FacetNaming`
  derived name keys on (scalar, nullability) so the two cases never collide.
  **This overrides the literal GG-335 text** (which shows
  `BooleanFacetValue.value: String`, read as ticket-writing shorthand
  rather than considered design); the typed-vs-`String` deviation still
  wants ticket-author confirmation (see the Overview deviation note).
- **Facet field nullability — every field under `facets` is nullable.**
  The `facets` object and each per-facet field (`[<Scalar>FacetValue!]`) are
  nullable; only the list elements and inner `value` / `count` stay non-null.
  Rationale: a facet is a best-effort aggregate, so a failure or timeout must
  degrade to a null facet, never bubble through GraphQL non-null propagation
  to abort the connection or the request. Making each per-facet field
  nullable (not just the `facets` object) keeps the wire contract stable for
  a future split into one query per facet field that can succeed or fail
  individually. See "Facet failure semantics".
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
- **NULL facet buckets — author-driven via the filter element nullability.**
  `GROUP BY` emits NULL as a distinct key automatically; Phase 1's
  NULL-bearing scenario confirmed all three measured shapes pass NULL
  through unchanged. When the annotated filter element is **nullable**
  (`[MpaaRating]`), `*FacetValue.value` is nullable and v1 preserves the
  NULL bucket as its own group (it round-trips via `filter: { x: [null] }`).
  When the element is **non-null** (`[MpaaRating!]`), `value` is non-null
  and the facet's arm appends `AND <col> IS NOT NULL`, so no NULL key
  reaches a non-null output field. `FacetSpec.valueNullable` carries the
  choice. Consumers wanting to hide a NULL bucket they would otherwise
  surface can mark the filter element non-null, or drop the row client-side.
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
   constraint for v1: keep `FacetSpec` + the `ConnectionResult` facet
   plan permissive enough that the C-vs-F choice lives entirely inside
   `ConnectionHelper.facets`; no wire-format or type-surface impact.
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
- `rewrite/ConnectionPromoter.java`: Phase 2 extension point.
  `synthesiseForField` / `promotionFor` / `buildSynthesisedConnection`
  grow the facet arm and register the facet types.
- `rewrite/model/GraphitronType.java:510`: `ConnectionType` (carries the
  new `List<FacetSpec>`); new `FacetsType` / `FacetValueType` arms.
- `rewrite/model/FieldWrapper.java:73`: `Connection` is the slim 2-arg
  per-site record; facets do **not** go here (see Phase 3 rationale).
- `rewrite/generators/schema/GraphitronSchemaClassGenerator.java`:
  `additionalType(<Name>Type.type())` carries the facet types (no
  facet-specific change needed once they are `EmitsPerTypeFile` arms).
- `graphitron/src/main/resources/no/sikt/graphitron/rewrite/schema/directives.graphqls`:
  target for the `@asFacet` directive declaration.
- `rewrite/generators/TypeFetcherGenerator.java:5081`
  (`buildQueryConnectionFetcher`): Phase 4 builds the facet plan onto
  `ConnectionResult`.
- `rewrite/generators/util/ConnectionResultClassGenerator.java` /
  `ConnectionHelperClassGenerator.java`: the `(table, condition)` +
  `totalCount` precedent the facet plan + `facets` resolver extend.
- `rewrite/generators/util/ConnectionFetcherClassGenerator.java:44` and
  `rewrite/generators/schema/FetcherRegistrationsEmitter.java:137`: the
  `facets` delegate + registration, behind a has-facets gate.
- `rewrite/generators/QueryConditionsGenerator.java`: the additive
  `<field>FacetBaseCondition` (non-facet base condition).
- `rewrite/BuildContext.java:102`: `DIR_*` constants (add `DIR_FACET`).
- `rewrite/model/Operation.java:93`: `Operation.Facet`, R316's operation-axis
  arm this plan deliberately leaves unpopulated (see *Contained approach*).
  R333 dissolves that enum into the `coordinate -> operation` set (reserving a
  `facet` member in its normalization table); R314 folds the contained emit
  into whatever member the fact model lands on.
