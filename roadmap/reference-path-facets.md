---
id: R1049
title: "@asFacet on a @reference-bound filter field: facet counts over a joined table"
status: Backlog
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
films by category name, cases by status name. Today the build rejects that shape, so a consumer who
wants a facet over related data either gives it up or facets on the foreign-key column on the root
table and resolves display labels client-side, which only works for a single hop. The facet keeps the
contract same-table facets already have: `value` round-trips into the filter unchanged, and each count
is the number of rows the connection returns when that value alone is selected, under the rest of the
filter.

## Who asked

The admissions (opptak) team, extending filtering on their case listing, asked whether facets reach
beyond the root table. Not a blocker for them, but a high-priority piece of their work; the concrete
paths they need (case to status, case to institution, or longer) should be collected before this
moves to Spec, since a single-hop to-one path and a junction path differ in what the first slice must
cover.

## Sketch

No new syntax. The reference filter that is legal today gains the marker:

```graphql
type Query {
    cities(filter: CityFilter): [City!]! @asConnection @defaultOrder(primaryKey: true)
    films(filter: FilmFilter): [Film!]! @asConnection @defaultOrder(primaryKey: true)
}

input CityFilter {
    country: [String!] @reference(path: [{table: "country"}]) @field(name: "country") @asFacet
}

input FilmFilter {
    category: [String!] @reference(path: [{table: "film_category"}, {table: "category"}])
                        @field(name: "name") @asFacet
}
```

The synthesised surface is unchanged in shape: `facets.country: [StringFacetValue!]`, value type
keyed on the terminal column's scalar and the filter element's nullability, as today.

## What exists and what does not

Most of the filter half is already there. A `@reference` on an input field classifies to
`InputField.ColumnBackedReferenceField`, carrying the `joinPath` and the terminal column, and lowers
to a correlated `EXISTS` on the root table. That makes the facet's own predicate one more root-row
condition, so the filter-minus-self composition (base condition AND every other facet's predicate)
needs no new idea.

The gaps:

- `FacetFieldValidation.definitionKeyedRejection` refuses `@reference` outright ("join-mediated ...
  facets are a follow-up"). That rejection narrows to whatever the first slice does not serve.
- `FacetSpec` carries only `columnName`, and the runtime arm in the generated `ConnectionHelper.facets`
  resolves it by name on the root table (`facetColumn(cr.table(), ...)`) and selects
  `FROM root WHERE ... GROUP BY col`. A reference facet needs its from-clause joined along the path
  and its group key on the terminal table, which a runtime name lookup cannot express; the generated
  fetcher has to supply each facet's joined source and column instead.
- The count. A path through a junction fans out, so the arm counts `COUNT(DISTINCT <root primary
  key>)` rather than `count(*)`. That is also what keeps the round-trip contract: a film in two
  categories counts once in each bucket, and selecting either value (an `EXISTS`) returns exactly that
  count. Per-facet counts can sum past the total, which is the expected reading of a multi-valued
  facet.

## Questions for the Spec pass

- **List-valued reference filters.** Every reference-bound input field in the corpus and the sakila
  schema today takes a single value. A facet is only useful with multi-select, so confirm a
  `[String!] @reference` filter classifies and lowers to `EXISTS ... IN (...)`, or fold that into this
  item.
- **The NULL bucket.** An inner join drops root rows with no related row, which matches the filter
  (it cannot select "no related row" either). A `LEFT JOIN` NULL bucket would show a value the client
  cannot feed back. Inner join is the proposed answer; a NULL in the terminal column itself keeps the
  existing nullable-element / scrub rule.
- **`condition:` hops.** The `EXISTS` lowering already renders them; whether the facet join reuses that
  rendering or the first slice rejects condition-bearing paths is a scoping call.
- **Which layer.** Facet synthesis is mid-migration (the facets container deriving from the store, and
  the planner / emitter split that `FacetPlan` already rides). The spec should name where the join
  path enters, the model's `FacetSpec` or the plan's `FacetPlan.Entry`, so the change does not land in
  code about to be replaced.

Out of scope here, each its own follow-up: facets on non-root (`@splitQuery`) or polymorphic
connections, `@nodeId` and `@condition` facets, hierarchical facets.

## Tests

Execution tier against sakila: a to-one path (`city -> country`) and a junction path
(`film -> film_category -> category`), each asserting counts under a filter that selects the facet's
own value (own predicate dropped) and another facet's value (applied), and that a returned `value` fed
back into the filter returns exactly `count` rows.
