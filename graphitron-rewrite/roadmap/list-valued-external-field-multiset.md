---
id: R109
title: "Validate and document list-valued @externalField via jOOQ multiset for grouped/aggregated child collections"
status: Backlog
depends-on: []
---

# Validate and document list-valued @externalField via jOOQ multiset for grouped/aggregated child collections

Today, when a child collection on a `@table` parent needs to be grouped or otherwise aggregated into a synthetic bucket type (e.g. `Opptak.grupperteOpptakshendelser: [GrupperteOpptakshendelser!]`, where each bucket carries a category id, a category name, and the events in that category), the only available option is `@service`. That forces the user to hand-write a `Map<ParentRecord, List<Bucket>>` batch loader: build a key set, issue the join query, bucket rows in Java, and reconstruct the group records. The grouping is not declarative, sub-resolvers on the inner record type lose their normal batching path because the inner records are constructed by hand outside Graphitron scope, and the same shape is going to recur for every "events grouped by category", "lines grouped by status", "items grouped by tag" field across services.

A code trace suggests `@externalField` may already support this shape with no plumbing changes — the item is to *prove* it end-to-end with a Sakila-shaped fixture, document the pattern, and patch up whatever small issues surface empirically. The relevant existing pieces:

- `ServiceCatalog.reflectExternalField` only requires the return to be a parameterized `org.jooq.Field<...>`. It does not constrain the type argument, so `Field<Result<R>>` and any other parameterized `Field<X>` pass.
- `ExternalFieldDirectiveResolver.resolve` has no list-cardinality rejection.
- `FieldBuilder` classifies `@externalField` into `ComputedField` regardless of the GraphQL field's wrapper; there is no `wrapper().isList()` guard on the `@externalField` arm.
- `FetcherEmitter` wires `ComputedField` as `new ColumnFetcher(DSL.field(name))` with no list branching — jOOQ multiset round-trips through the parent's result Record under that alias and graphql-java iterates whatever list is found.
- `deriveBatchKeyFromTypedAccessor` (load-bearing classifier check `accessor-rowkey-cardinality-matches-field`) already auto-derives `AccessorKeyedMany` on the *second* hop (e.g. `GrupperteOpptakshendelser.hendelser`) from a `List<OpptakshendelseRecord> hendelser()` accessor on the bucket Java record. The inner `TableRecord` lift over a list is the existing path, not new.
- `GraphitronSchemaValidator.validateComputedField` only rejects `ComputedField` carrying a join path (the deferred condition-join lift form), not list cardinality.

The hypothesis is therefore that this is a documentation-and-fixture item, not a feature item. Compared to inventing a dedicated `@groupBy` directive, leaning on `@externalField` keeps the bucket type an ordinary `@record`, reuses an existing directive surface, gives the user the full power of jOOQ for the aggregation expression, and falls naturally onto the existing record-lift path on the inner hop.

Open questions to resolve in Spec:

- Empirical end-to-end: drop a Sakila-shaped grouping fixture into `graphitron-sakila-service` (e.g. `Customer.rentalsByCategory`) with the bucket type a `@record` exposing a typed `List<RentalRecord> rentals()` accessor, and confirm the pipeline + execution tiers pass without code changes. Catch any concrete framework gaps that the structural trace missed.
- Bucket-record mapping: jOOQ needs to materialise `Result<R>` into the bucket Java record type. Confirm whether `multiset(...).convertFrom(...)` is the contract we expect users to write, or whether `into(BucketRecord.class)` is enough; either way nail it down in the user-facing doc.
- SQL portability: `multiset` works on Postgres (our current target) via `JSON_ARRAYAGG`. Confirm and call out non-Postgres backends as out of scope if relevant.
- Interaction with `@override` and `@splitQuery`: the motivating example uses both. Confirm an `@externalField` field can carry the same federation/splitting semantics, or document the gap and treat it as a follow-up item.
- Documentation surface: once the fixture is green, the `@externalField` reference doc and the diataxis user manual should grow a "grouped/aggregated child collections via multiset" recipe so this pattern is discoverable instead of hidden in fixture code.

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

with the extension producing a `Field<Result<...>>` from a `multiset(...) GROUP BY kategori` correlated to the parent `Opptak` row.
