---
id: R1049
title: "@asFacet on a @reference-bound filter field: facet counts over a joined table"
status: Spec
bucket: feature
priority: 4
theme: search
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# @asFacet on a @reference-bound filter field: facet counts over a joined table

## Goal

An author can put `@asFacet` on a filter-input field bound through `@reference`, and the connection's
`facets` field then counts the values of a column on the joined table: cities faceted by country name,
films faceted by actor, cases faceted by status name. Today the build rejects that shape, so a consumer
who wants a facet over related data either gives it up or facets on the foreign-key column of the root
table and resolves display labels client-side, which only works for a single hop. The facet keeps the
contract same-table facets already have: `value` round-trips into the filter unchanged, and each count
is the number of rows the connection returns when that value alone is selected, under the rest of the
filter.

A *facet* is a per-value count the connection returns beside its page, so a UI can show "Comedy (12)"
next to a filter checkbox. The sakila example schema already has a faceted connection with
direct-column facets; this item lets the same filter input carry a facet whose column sits on the
other side of a join, using the `@reference` path the schema already spells for its `cast` field:

```graphql
type Query {
    filmsFaceted(filter: FilmFacetFilter, extra: FilmExtraFilter): [Film!]!
        @asConnection @defaultOrder(primaryKey: true)
}

input FilmFacetFilter {
    rating: [MpaaRating!] @field(name: "RATING") @asFacet          # accepted today
    actor:  [String!]     @field(name: "last_name") @asFacet        # rejected today, served after
            @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
}
```

`facets { actor { value count } }` then returns one bucket per actor last name, counting films, and
`filter: { actor: [bucket.value] }` returns exactly `count` films.

## Who asked

The admissions (opptak) team, extending filtering on their case listing, asked whether facets reach
beyond the root table. Not a blocker for them, but a high-priority piece of their work. Their concrete
paths (case to status, case to institution, or longer) were not collected; rather than wait on them,
this plan serves every path shape a `@reference` filter already accepts on a table-rooted connection
(single hop, multi-hop, junction, a path that fans out, and paths with `condition:` hops), so whichever
paths they need are covered unless they hit one of the three limits named under Scope.

## User documentation (first-client check)

The changes to `docs/manual/reference/directives/asFacet.adoc`, written as they will read.

A new section after "Canonical example":

> == Facets over a related table
>
> A facet field may reach its column through `@reference`, the same way a filter field does. The
> counts group on the column at the end of the path and count rows of the connection, not rows of the
> joined table:
>
> ```graphql
> input CityFacetFilter {
>     country:  [String!] @reference(path: [{table: "country"}]) @field(name: "country") @asFacet
>     district: [String!] @reference(path: [{table: "address"}]) @field(name: "district") @asFacet
> }
> ```
>
> `district` counts cities, not addresses: a city with two addresses in the same district counts once
> in that district's bucket. A city with addresses in two districts counts once in each, so the
> counts of a facet whose path fans out can add up to more than the connection's `totalCount`; each
> count is still exactly the number of cities `filter: { district: [value] }` returns.
>
> A row with no related row at the end of the path appears in no bucket, just as no filter value
> selects it. A related row whose column is NULL follows the nullable-element rule above.

The "Constraints" list changes in three places:

> * The field must be bound to a column with `@field(name:)`, either on the connection's own table or,
>   with `@reference(path:)`, on the table the path ends at. `@condition` / `@nodeId` bindings,
>   `ID`-typed fields, and input-object fields are rejected (node-ID and condition facets are a
>   follow-up).
> * A `@reference` facet counts distinct rows by the connection's primary key, so it needs a connection
>   over a table that has one. A `@routine`-sourced connection, or one over a table with no primary key,
>   rejects a `@reference` facet at build time.
> * A `@reference` facet cannot sit under `@condition(override: true)` on the connection field or its
>   filter argument: the override replaces the generated filter the facet's join is taken from.

The SDL description of `@asFacet` in `directives.graphqls` drops "reference" from its list of rejected
bindings to match.

## Design

**Where the join path enters: the plan, not the model.** A facet today is two things. `FacetSpec` is the
definition-level fact keyed on the input field (filter argument, input field, the authored
`@field(name:)` value, value type, nullability). It has two producers whose emitted surface
`EmittedRegistryAgreementTest` pins equal: `ConnectionPromoter.facetSpecsFor` from the SDL, and the
store's `graphitron_facet_binding` view. Neither resolves anything against the catalog. `FacetPlan` is
the planner's use-site row, keyed on (carrier, facet): the base glue call and one entry per facet. The
join path and the group column are catalog-resolved, use-site facts, so they enter `FacetPlan`.
`FacetSpec` gains only one definition-keyed fact (below), and the facets surface (container and
`*FacetValue` types) is unchanged, because a reference facet's surface depends only on the value type
and nullability that both producers already compute. `FacetSpec.columnName` stays strictly the authored
`@field(name:)` value; which column on which table the counts group on is said only by the plan.

**Borrowed from the facet's own filter term, not re-derived.** The condition planner already lowers a
`@reference` filter field (classified as `InputField.ColumnBackedReferenceField`, binding
`FilterBinding.Remote` when the column is on a joined table) to a `ColumnTerm` whose `reach` is the
narrowed hop path and whose `columns` is the terminal column, and `ConditionCommands.fragments` already
isolates each facet's own term into that facet's fragment. `LauncherCommands.facetPlanOf`, which already
reads the condition row's fragments, takes the facet's own term from there: a non-empty reach makes a
reference facet whose join is `term.reach()` and whose group column is `term.columns()`. One narrowing
site (`ReachPath.narrow`) serves both the `EXISTS` and the facet join, so the two cannot disagree on the
path. To make that read a join on the facet key rather than a search, `FacetFragment` splits into
`FacetFragment.Base` (the base fragment, as today) and `FacetFragment.Own`, which carries the facet's
input field name and its own term as `Optional<ColumnTerm>`; "one own term at most" becomes a
constructor invariant on the condition side, where the term is built, rather than a launcher backstop.

**One runtime facet shape.** Every facet entry in the plan carries a `Grouping`:
`Grouping.CarrierColumn(ColumnRef column)` for a facet on the carrier's own table, and
`Grouping.Reached(ReachPath reach, ColumnRef column)` for a facet across a join. The runtime
`ConnectionResult.FacetSpec` the launcher binds becomes uniform: label, source table, group column,
value nullability, and the count key, always set. A carrier-column facet binds the carrier's own table
as its source and an empty count key (`count(*)`); a reached facet binds the joined source and the
carrier's primary key. The helper's arm reads them without branching on the facet kind, and the runtime
name lookup (`ConnectionHelper.facetColumn`, which tries `Table.field(String)` and then a
case-insensitive scan) retires: the carrier-column facet's column is resolved at plan time against the
condition row's `TableRef`, by the same case-insensitive rule, and a miss is a production failure (the
classifier has already resolved the `@field` column, so it is a backstop). Direct-facet output changes
shape (a typed column reference in place of the lookup call) but not SQL.

**The count is distinct over the root primary key.** A path that fans out (city to address, film to
`film_actor` to actor) multiplies root rows in the join, so a reached arm counts
`COUNT(DISTINCT <root primary key>)`. That is what keeps the round-trip contract: selecting a value is an
`EXISTS`, which returns each root row once however many related rows match, and the distinct count is
that number. Every reached facet uses it, to-one paths included, so no per-hop cardinality fact is
needed; on a to-one path it returns what `count(*)` would. The key is the carrier's, the same for every
facet, so `FacetPlan` carries it once (`distinctKey`, the condition row's `TableRef.primaryKeyColumns()`)
with the invariant that a plan holding a `Reached` entry has a non-empty key. A composite key counts
distinct over all its columns (`DSL.countDistinct(Field...)`, which jOOQ renders as a row-value distinct
on PostgreSQL).

**Inner join, no "no related row" bucket.** The arm joins along the path with inner joins. A root row
with no related row drops out of every bucket, which matches the filter, since no value selects it. A
`LEFT JOIN` NULL bucket would show a value the client cannot feed back. A NULL in the terminal column
itself keeps the existing rule: preserved as a bucket on a nullable element, scrubbed with
`IS NOT NULL` on a non-null one.

**`condition:` hops are served.** The `EXISTS` lowering already renders both `condition:` forms through
`PathFragments`; the facet join reuses the same fragments in the forward direction
(`PathFragments.emitForwardBridging`, already shared by the projection and routine-chain renderers). A
hop that joins on a condition (`On.Predicate`) renders as its `.on(method(prev, hop))`; a hop's
`{key:, condition:}` filter is ANDed onto that hop's `ON`, which for an inner join is the same predicate
the `EXISTS` puts in its `WHERE`.

**A reference facet that lost its term fails the build, at both ends.** `@condition(override: true)` on
the carrier field or the filter argument suppresses the implicit predicate (`FieldBuilder`'s
`enclosingOverride`), so a facet under it has no own term. For a direct facet that is accepted today
(see Out of scope). For a reference facet there would be no reach to join along, and the planner could
not tell it from a direct facet, so it would group a same-named column on the root table: wrong data,
silently. `FacetSpec` therefore gains `referenceBound`, the definition-keyed fact that the field carries
`@reference` (both producers already read that directive list: the promoter in
`FacetFieldValidation`, the store in `graphitron_facet_binding`'s directive filter). The validator
rejects a reference-bound facet under an override, and `facetPlanOf` throws on a reference-bound facet
whose `Own` fragment has no term, so the rejection has an enforcer in the producer. The store view
carries the same fact as a column, so when the facet misuse checks move to store detections the
override rule has a relation to read.

A `@reference` that resolves on the root table itself (an empty path, `FilterBinding.Local`) produces a
reach-less term and plans as `CarrierColumn`; it was refused only because the gate refused the
directive, not the shape.

**Scope: three build-time limits.** Each is a rejection in the use-keyed facet reduction, so an author
sees a located error rather than a facet that resolves wrong.

- *`@routine`-sourced carriers.* Their FROM is a routine call (possibly joined along a chain), not the
  element table, and a routine result has no primary key, so neither the join anchor nor the distinct
  key has one place to stand. Direct facets on them are unaffected.
- *Element tables without a primary key.* No distinct key to count by.
- *Under `@condition(override: true)`* on the carrier field or the filter argument, per the paragraph
  above.

## Implementation

- `FacetFieldValidation.definitionKeyedRejection`: drop `DIR_REFERENCE` from the first arm; the message
  names `@condition` / `@nodeId` and calls condition and node-ID facets the follow-up. `@reference` +
  `@nodeId` stays rejected through the `@nodeId` test. `@field(name:)` stays required: it names the
  terminal column, and the store's binding view reads it from `graphitron_field_binding_entry`.
- `FacetSpec` gains `boolean referenceBound`; `ConnectionPromoter.facetSpecsFor` sets it from
  `@reference` on the input field.
- `graphitron-model.sql`: in `graphitron_facet_binding`, drop `'reference'` from the excluded-directive
  list (so the store's definition-keyed predicate stays the promoter's) and add a `reference_bound`
  column (whether the application's sibling directives include `@reference`, asked of the same
  application-anchored directive entries the exclusion already reads); carry it through
  `graphitron_connection_facet`. Comment both new columns, reword the view's comment and
  `column_name`'s ("which table it resolves on" becomes the carrier's table, or the `@reference` path's
  terminal table). The mint is untouched: it reads only value type and nullability.
- `GraphitronSchemaBuilder`, the use-keyed facet reduction (beside `unsupportedFacetCarrierReason`):
  for a reference-bound `@asFacet` field, reject when a consuming carrier is `@routine`-sourced, when
  its element table has no primary key, or when the carrier field or the filter argument carries
  `@condition(override: true)`. Messages name the carrier and the reason, in the shape of the existing
  carrier-scope message.
- `FacetFragment` becomes sealed: `Base(method, predicates, lifts)` and `Own(inputFieldName, method,
  Optional<ColumnTerm> term, lifts)`, `Own`'s predicates derived from its term. `ConditionCommands.fragments`
  builds `Own` per facet and fails if a facet owns more than one term; `ConditionCommand`'s partition
  invariant and `ConditionGlueRenderer`'s fragment loop read the shared accessors and are otherwise
  unchanged.
- `FacetPlan`: `Entry` gains `Grouping grouping` (`CarrierColumn(ColumnRef)` /
  `Reached(ReachPath, ColumnRef)`, `Reached` requiring a non-empty reach); the plan gains
  `List<ColumnRef> distinctKey`, required non-empty when any entry is `Reached`.
- `LauncherCommands.facetPlanOf`: per facet, join the row's `Own` fragment on the input field name.
  Term with a non-empty reach: `Reached(term.reach(), term.columns().getFirst())`. Otherwise
  `CarrierColumn` with `spec.columnName()` resolved against `row.table()`. Backstops: a
  reference-bound spec whose `Own` has no term, a reached term with more than one column, and a
  `Reached` entry on a routine-chain launch.
- `ConnectionResultClassGenerator`, nested `FacetSpec`: components become label, `source`
  (`Table<?>`), `column` (`Field<?>`), `valueNullable`, `countKey` (`Field<?>[]`); `columnName` goes.
- `ConnectionHelperClassGenerator`, the `facets` arm: `from(f.source())`, group and decode on
  `f.column()`, count `DSL.countDistinct(f.countKey())` when the key is non-empty and `DSL.count()`
  otherwise, as one statement-form branch. The `IS NOT NULL` scrub, the TEXT cast, the decode through
  `col.getDataType()` and the Java-side ordering are unchanged. `facetColumn` is deleted.
- `RootLauncherRenderer.facetBindings`: a `CarrierColumn` entry binds the carrier's FROM local and the
  column on its table local. A `Reached` entry declares one aliased table local per hop (as the
  routine-chain arm does, under a `facet`-prefixed local name so it cannot shadow the launcher's own
  locals), builds the joined source from the carrier's table local with
  `PathFragments.emitForwardBridging` per hop plus the hop's filter on its `ON`, and binds the terminal
  alias's column and the plan's `distinctKey` on the carrier's table local.
- Docs: `asFacet.adoc` per the draft above; the `@asFacet` description in `directives.graphqls`; the
  generated directive listing if it renders that description.

## Tests

- **Store tier.** `facet-binding-refused.graphqls`: the `viaReference` field (a root-table path) becomes
  a bound row with `reference_bound` true, `rating`'s row false, and the document's description names
  two declines, not three.
- **Pipeline tier, `FacetedConnectionPipelineTest`.** `asFacetOnReferenceBoundField_rejected` becomes
  an acceptance: the carrier synthesises its facets field with a `StringFacetValue` entry. New
  rejections, each asserting the located message: a `@reference` facet on a `@routine` carrier; under
  `@condition(override: true)` on the carrier field; under it on the filter argument. The `@nodeId`
  co-occurrence rejection stays. `ConnectionPromoterTest` asserts `referenceBound` on both kinds.
- **Plan tier.** `ConditionCommandsPipelineTest`: a faceted row's fragments are one `Base` and one `Own`
  per facet, keyed by input field name, the reference facet's `Own` term carrying the reach.
  `LauncherCommandsPipelineTest`: a carrier with one direct and one two-hop reference facet plans
  `CarrierColumn` (the resolved root column) and `Reached` (the same `ReachPath` value as the facet's own
  term, the terminal column), with `distinctKey` the root primary key.
- **Pipeline tier, generated code (`FacetEmitterTest` or a sibling).** A path with a `{key:, condition:}`
  hop renders that hop's condition on the join's `ON`; a path with a `condition:` join hop renders
  `.on(<method>(prev, hop))`; a root with a composite primary key binds a two-field count key; a direct
  facet binds a typed column, and the helper no longer emits `facetColumn`.
- **Execution tier, `GraphQLQueryTest`, against the sakila seed.** Two fixtures in
  `graphitron-sakila-example`'s schema:
  - A new `citiesFaceted(filter: CityFacetFilter): [City!]! @asConnection @defaultOrder(primaryKey:
    true)` with the `CityFacetFilter` from the docs draft. The `district` facet is the distinct-count
    proof: Lethbridge has two Alberta addresses, so `count(*)` would say 2. Unfiltered: `country`
    returns United States 1, Italy 1, Japan 1; `district` returns Alberta 1, Lazio 1, Tasmania 1
    (Tokyo has no address and appears in none). `filter: {country: ["United States"]}`: `country`
    is unchanged (own predicate dropped), `district` is Alberta 1, Tasmania 1. `filter: {district:
    ["Alberta"]}` returns exactly one city, and `country` is United States 1.
  - The `actor` field from the Goal on `FilmFacetFilter` (two-hop junction). Under the existing
    `extra.lengthIs` bound: GUINESS 3, WAHLBERG 2, CHASE 2 (summing past the five films). Under
    `filter: {rating: [G]}`: GUINESS 1, CHASE 1, WAHLBERG 1. `filter: {actor: ["CHASE"]}` returns two
    films, and `rating` is G 1, PG 1.

  The existing `filmsFaceted` tests select only their own facets, so their values are unchanged; they
  also pin that the move off the runtime column lookup kept direct-facet SQL as it was.

## Retired vocabulary

- `ConnectionHelper.facetColumn` and the runtime `FacetSpec.columnName` component: the generator, the
  generated-code tests, and comments in `ConnectionHelperClassGenerator` and
  `ConnectionResultClassGenerator` that describe resolving the facet column by name at runtime.
- "join-mediated ... facets are a follow-up" and "direct-column bindings" as the description of what
  `@asFacet` accepts: in `FacetFieldValidation`'s message, `asFacet.adoc`, the `directives.graphqls`
  description, the `facet-binding-refused.graphqls` description, and the pipeline tests that match on
  the message.

## Out of scope

Each its own follow-up: facets on non-root (`@splitQuery`) or polymorphic connections, `@nodeId` and
`@condition` facets, hierarchical facets, `@reference` facets on `@routine` carriers. One adjacent gap
seen while planning, not fixed here: a direct-column facet under `@condition(override: true)` is
accepted today, yet its own predicate lives inside the author's override method, which the facet
fragments never mask, so its counts do not pivot on its own selection.

## Reviewer findings

### Round 1 (2026-10-15, Spec -> Ready, reviewer session 01QLhMxUpvCRDZUDPFxH1t4D)

Verdict: withhold. One finding, on question two. The goal reads on its own: an author can put
`@asFacet` on a `@reference` filter field and get per-value counts of root rows grouped on a column of
the joined table, with the existing round-trip contract kept. The design extends the facet plan,
the condition fragments and `PathFragments` instead of standing beside them. The claims the plan
makes about the tree check out, and so do the seeded counts in the execution fixtures.

**Finding 1 (question two: architecture fit). The proof for two of the promised path shapes is a
test the project bans.** The "Pipeline tier, generated code (`FacetEmitterTest` or a sibling)"
bullet asserts what the generated launcher body says: that a `{key:, condition:}` hop's condition
lands on the join's `ON`, that a `condition:` join hop renders `.on(<method>(prev, hop))`, that a
composite primary key "binds a two-field count key", and that a direct facet "binds a typed column".
These are code-string assertions on generated method bodies. `development-principles.adoc` § "Behaviour
is pinned at the pipeline tier and above" and `testing.adoc` ban them at every tier, and
`FacetEmitterTest`'s own javadoc names the ban as the reason it stops at method names. Without that
bullet, nothing in the plan proves at a sanctioned tier that `condition:` hops and composite-key roots
are served. The execution fixtures (`citiesFaceted`, the `actor` facet) are FK-only paths over
single-column keys, and the Who-asked section promises the requesters exactly those shapes.

What would satisfy it: move each of these four behaviours to a tier that may assert it. The plan
facts can be asserted structurally at the plan tier: a `Reached` entry whose hops carry
`On.Predicate` or a `filter()`, and a two-column `distinctKey`. Rendering and SQL go in compilation
and execution fixtures on the sakila schema. `ReferencePathConditionFixtures.filmActorJunctionToActor`
already serves a `condition:` hop on the film→actor path, and `film_actor` has a composite key, so
the fixtures cost little. Which fixtures to add, and whether the typed-column check for direct
facets is already covered by the existing `filmsFaceted` execution tests, is the author's call.
"The helper no longer emits `facetColumn`" is a method-surface assertion and may stay where it is.

**Non-blocking.**

- The plan resolves a direct facet's column from `spec.columnName()` by name against `row.table()`,
  even when the facet's own term already holds that `ColumnRef`. This is the one place the plan
  re-derives instead of borrowing ("Borrowed from the facet's own filter term"). By-name lookup is
  needed only under `@condition(override: true)`, where the term is absent. Either form works, but
  note that `TableRef.column`'s javadoc says no generator emits per column off `allColumns`, and that
  hand-built `TableRef` fixtures leave `allColumns` empty, so the new backstop would trip on any test
  that plans a facet over one.
- No reader is named for the view's new `reference_bound` column before the misuse checks move to
  the store. Dropping `'reference'` from the view's exclusion is required to keep the two producers
  equal; the extra column is not.
