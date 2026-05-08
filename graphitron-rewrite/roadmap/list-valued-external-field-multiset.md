---
id: R109
title: "List-valued @externalField for grouped/aggregated child collections via jOOQ multiset"
status: Backlog
depends-on: []
---

# List-valued @externalField for grouped/aggregated child collections via jOOQ multiset

Today, when a child collection on a `@table` parent needs to be grouped or otherwise aggregated into a synthetic bucket type (e.g. `Opptak.grupperteOpptakshendelser: [GrupperteOpptakshendelser!]`, where each bucket carries a category id, a category name, and the events in that category), the only available option is `@service`. That forces the user to hand-write a `Map<ParentRecord, List<Bucket>>` batch loader: build a key set, issue the join query, bucket rows in Java, and reconstruct the group records. The grouping is not declarative, sub-resolvers on the inner record type lose their normal batching path because the inner records are constructed by hand outside Graphitron scope, and the same shape is going to recur for every "events grouped by category", "lines grouped by status", "items grouped by tag" field across services.

`@externalField` already covers the structurally similar single-valued case. Its contract — a static method `Field<X> method(ParentTable t)` whose returned jOOQ field is inlined into the parent's projection — composes naturally with jOOQ `multiset()`, which produces a `Field<Result<Record>>` for a per-parent correlated subquery and converts cleanly into `List<CustomRecord>`. The `InventoryExtensions.filmCardData` fixture already demonstrates that an inner `TableRecord` embedded in a custom Java record gets lifted back into Graphitron scope via the `AccessorKeyedSingle` BatchKey, so sub-resolvers on the lifted record keep batching normally.

The hypothesis for this item is that extending `@externalField` to accept `Field<List<X>>` (where `X` is a `@record`-typed Java record holding an inner `TableRecord` accessor, or a nested `multiset` of one) is enough to express the grouped-collection case declaratively, with per-parent batching for free (the aggregate is just another column in the parent select) and the existing record-lift machinery handling sub-resolver fan-out. Compared to a dedicated `@groupBy` directive, this keeps the bucket type an ordinary `@record`, reuses an existing directive surface, and gives the user the full power of jOOQ for the aggregation expression rather than a constrained DSL.

Open questions to resolve in Spec:

- Classifier and BatchKey coverage: today the lift path is `AccessorKeyedSingle`; we need a `…KeyedList` (or equivalent) variant that fans the inner `TableRecord` PKs out of a `List<Bucket>` rather than a single `Bucket`.
- Validation: `@externalField` reference signature checks must accept `Field<List<X>>` / `Field<? extends Collection<X>>` for the list-valued case, and the bucket type `X` must be a `@record` whose components are either scalars/IDs or accessors that the lift machinery already understands.
- SQL portability: `multiset` works on Postgres (our current target) via `JSON_ARRAYAGG`; we should confirm the path we want to commit to before this lands and call out non-Postgres backends as out of scope if relevant.
- Interaction with `@override` and `@splitQuery`: the motivating example uses both; the spec should confirm an `@externalField` field can carry the same federation/splitting semantics or document the gap.
- Fixture placement: the test for this likely lives in `graphitron-sakila-service` next to `FilmExtensions`/`InventoryExtensions`, with a Sakila-shaped grouping case (e.g. `Customer.rentalsByCategory` or similar) covering both single-record-lift and list-of-records-lift through the bucket type.

Motivating example (current, hand-rolled `@service`):

```graphql
type Opptak implements Node @key(fields: "id") @node(typeId: "Opptak", keyColumns: ["opptakstype_kode", "opptak_kode"]) @table(name: "opptak") {
    grupperteOpptakshendelser: [GrupperteOpptakshendelser!]
        @splitQuery
        @service(service: { className: "no.sikt.fs.opptak.service.OpptakService", method: "grupperteOpptakshendelser" })
        @override(from: "admissio")
}

type GrupperteOpptakshendelser @record(record: {className: "no.sikt.fs.opptak.records.GrupperteOpptakshendelser"}) {
    kategoriId: ID!
    kategori: String!
    hendelser: [Opptakshendelse!]!
}
```

Target shape (proposed, list-valued `@externalField`):

```graphql
grupperteOpptakshendelser: [GrupperteOpptakshendelser!]
    @externalField(reference: { className: "...OpptakExtensions", method: "grupperteOpptakshendelser" })
    @override(from: "admissio")
```

with the extension producing a `Field<List<GrupperteOpptakshendelser>>` from a `multiset(...) GROUP BY kategori` correlated to the parent `Opptak` row.
