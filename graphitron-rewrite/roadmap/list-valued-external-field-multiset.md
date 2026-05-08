---
id: R109
title: "How-to recipe and Sakila fixture for grouped collections via Field<Result<R>> @externalField + multiset"
status: Spec
depends-on: []
---

# How-to recipe and Sakila fixture for grouped collections via `Field<Result<R>>` `@externalField` + multiset

## Problem

A common service shape is "child rows grouped by a category, exposed on the parent as a list of synthetic bucket types" — e.g. `Opptak.grupperteOpptakshendelser: [GrupperteOpptakshendelser!]` where each bucket carries a category id, a category name, and the events in that category. Users today reach for `@service` and hand-write a `Map<ParentRecord, List<Bucket>>` batch loader: build a key set, issue the join query, bucket rows in Java, reconstruct the group records. The grouping is not declarative, the inner records are constructed outside Graphitron scope so sub-resolvers on them lose the framework's batching path, and the same pattern recurs across every service that has "events grouped by category", "lines grouped by status", "items grouped by tag".

A code trace through the rewrite suggests the existing `@externalField` machinery already supports the obvious alternative — a static method returning `Field<Result<R>>` produced by jOOQ `multiset(...) GROUP BY ...` — without any framework changes. If that's true, the missing piece is purely discoverability: the pattern isn't documented and there's no fixture proving it works end-to-end. Users reach for `@service` because nothing tells them they don't have to.

## What we believe is true (and want to prove)

The structural argument that the path is already wired:

- `ServiceCatalog.reflectExternalField` only requires the return to be a parameterized `org.jooq.Field<...>`. It does not constrain the type argument; `Field<Result<R>>` and any other parameterized `Field<X>` pass.
- `ExternalFieldDirectiveResolver.resolve` has no list-cardinality rejection.
- `FieldBuilder` classifies `@externalField` into `ComputedField` regardless of the GraphQL field's wrapper; there is no `wrapper().isList()` guard on the `@externalField` arm.
- `FetcherEmitter` wires `ComputedField` as `new ColumnFetcher(DSL.field(name))` with no list branching — jOOQ multiset round-trips through the parent's result Record under that alias, and graphql-java iterates whatever list is found.
- `deriveBatchKeyFromTypedAccessor` (load-bearing classifier check `accessor-rowkey-cardinality-matches-field`) already auto-derives `AccessorKeyedMany` on the second hop from a `List<X>` accessor on a Java-record parent, where `X` extends `TableRecord`. The inner `TableRecord` lift over a list is the existing path, not new.
- `GraphitronSchemaValidator.validateComputedField` only rejects `ComputedField` carrying a join path (the deferred condition-join lift form), not list cardinality.

The empirical question is whether jOOQ's multiset round-trips cleanly through `ColumnFetcher`'s `record.get(DSL.field(name))` lookup on the parent select, and whether the bucket Java record is materialised correctly when its components include a typed `List<TableRecord>` accessor that the second-hop classifier expects. Either it works as the trace predicts, or a small concrete gap surfaces — at which point this item forks into a fixture-only deliverable and a follow-up plan for the gap.

## Deliverables

1. **Sakila fixture** in `graphitron-sakila-service` proving the path end-to-end. Sakila has the right shape for this without a contrived domain model: rentals belong to inventory, which belongs to a film, which belongs to film-categories. A natural fixture is `Customer.rentalsByCategory: [RentalsByCategory!]` where each `RentalsByCategory` bucket carries the category id, the category name, and the customer's rentals in that category. Concretely:
   - A new `CustomerExtensions.rentalsByCategory(Customer customer)` returning `Field<Result<...>>` from a `multiset` correlated to the parent customer row, grouped by `film_category.category_id`.
   - A `RentalsByCategory` Java record (or class) with components `categoryId: Integer`, `categoryName: String`, `rentals: List<RentalRecord>` so the second-hop classifier auto-derives `AccessorKeyedMany` from the typed `rentals()` accessor.
   - Schema additions on `Customer` (a `@table` type) and a new `RentalsByCategory` `@record` type whose `rentals` field returns `[Rental!]!`.
2. **Execution-tier test** in `GraphQLQueryTest`, named in the same `<root>_<field>_<assertion>` style as the existing `inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord` neighbour. The test runs a query like `{ customerById(customer_id: [1]) { customerId rentalsByCategory { categoryName rentals { rentalId rentalDate } } } }` and asserts both:
   - The bucket projection populates `categoryName` and the right number of buckets per customer (the multiset GROUP BY is correct).
   - The inner `rentals { rentalDate }` resolves a non-PK column not present on the lifted `RentalRecord`, proving the second-hop `AccessorKeyedMany` lift fires and batch-fetches the full rows by PK.
3. **How-to recipe** in `docs/manual/how-to/computed-fields.adoc` as a fourth subsection alongside the existing "Scalar `Field<T>`", "Lifted `Field<TableRecord<?>>`", and "Lifted `Field<CustomJavaRecord>`" sections. The new subsection ("Lifted `Field<Result<R>>` for grouped collections via `multiset`") covers:
   - The motivating shape (parent → list of bucket records → list of inner table records).
   - The static-method signature and a worked Sakila example matching the fixture above.
   - The bucket Java record shape and why the typed `List<TableRecord>` accessor is what makes the inner lift work.
   - The cross-link to `result-types.adoc` for the broader `@record` decision tree.
   - A "constraints" bullet noting Postgres-only `multiset` support if relevant after fixture work.
   - A "see also" pointer back to the existing lift forms.
4. **Cross-link addition** from `docs/manual/how-to/result-types.adoc` (or wherever the `@record` parent decision tree lives, to be confirmed during writing) to the new `computed-fields.adoc` subsection, so a reader on the result-types path discovers the multiset bucket pattern.

## Tasks

In order:

1. Add the schema, extension method, and Java record for the Sakila grouping fixture; confirm it builds via `mvn install -Plocal-db`.
2. Add the execution-tier test with the assertions described above; confirm it passes.
3. If anything in the trace turns out to be wrong (multiset round-trip fails through `ColumnFetcher`, bucket-record mapping needs jOOQ-side help, or a validator/classifier path actually does reject the list cardinality), capture the concrete gap as a follow-up Backlog item under a different `R<n>` and narrow this item's scope to the fixture-and-doc parts that succeed. Do not silently mutate framework code under R109.
4. Write the `computed-fields.adoc` subsection from the working fixture.
5. Add the cross-link from `result-types.adoc`.
6. Confirm the docs site renders cleanly: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` (no `-P!docs`) so the AsciiDoctor render exercises the new content.

## Done means

- Sakila fixture and execution test merged on trunk.
- New "Lifted `Field<Result<R>>`" subsection visible in the rendered how-to (the docs site's `computed-fields.html`), with a working code example matching the fixture.
- A reader who arrives at "I want to expose grouped child collections" can find the recipe from either `external-code.adoc`, `computed-fields.adoc`, or `result-types.adoc` without bouncing off the `@service` recipe.

## Open risks

- The structural trace might miss a load-bearing wrinkle (e.g. `ColumnFetcher`'s value-by-name lookup against a multiset-projected column might need an explicit `Class<?>` argument the way `ParticipantColumnReferenceField` does). Containment plan: surface the concrete error when the fixture fails, file the gap as a separate item, document only the part that works.
- `multiset` SQL portability is Postgres-only via `JSON_ARRAYAGG`. If we ever broaden the supported DB matrix, the recipe needs a portability note. For now Postgres is the only target, so this is a documentation footnote, not a blocker.
- Interaction with `@override` and `@splitQuery` from the motivating Opptak example was not exercised by the structural trace. If the recipe is going to claim those compose, the fixture should cover them; otherwise the doc should call out the gap and link a follow-up item.
