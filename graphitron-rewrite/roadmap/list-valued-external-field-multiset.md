---
id: R109
title: "How-to recipe and Sakila fixture for grouped collections via Field<Result<R>> @externalField + multiset"
status: Spec
depends-on: []
---

# How-to recipe and Sakila fixture for grouped collections via `Field<Result<R>>` `@externalField` + multiset

## Problem

A common service shape is "child rows grouped by a category, exposed on the parent as a list of synthetic bucket types": e.g. `Opptak.grupperteOpptakshendelser: [GrupperteOpptakshendelser!]` where each bucket carries a category id, a category name, and the events in that category. Users today reach for `@service` and hand-write a `Map<ParentRecord, List<Bucket>>` batch loader: build a key set, issue the join query, bucket rows in Java, reconstruct the group records. The grouping is not declarative, the inner records are constructed outside Graphitron scope so sub-resolvers on them lose the framework's batching path, and the same pattern recurs across every service that has "events grouped by category", "lines grouped by status", "items grouped by tag".

A code trace through the rewrite suggests the existing `@externalField` machinery already supports the obvious alternative, a static method returning `Field<Result<R>>` produced by jOOQ `multiset(...) GROUP BY ...`, with one small classifier tweak. The natural shape pushes the bucket Java record's inner-list accessor to be `Result<RentalRecord>` (jOOQ multiset's native materialisation), not `List<RentalRecord>`. The classifier currently rejects `Result<R>` accessors because `ServiceCatalog.peelContainer` (ServiceCatalog.java:816) does raw-class equality on `List.class` / `Set.class`. R109 widens that helper, validates the path end-to-end with a Sakila fixture, and writes the recipe.

## What we believe is true (and want to prove)

The structural argument that the path is mostly already wired:

- `ServiceCatalog.reflectExternalField` (ServiceCatalog.java:569) only requires the return to be a parameterised `org.jooq.Field<...>`; it does not constrain the type argument. `Field<Result<R>>` and any other parameterised `Field<X>` pass.
- `ExternalFieldDirectiveResolver.resolve` has no list-cardinality rejection.
- `FieldBuilder` classifies `@externalField` into `ComputedField` regardless of the GraphQL field's wrapper (FieldBuilder.java:3231-3241); there is no `wrapper().isList()` guard on the `@externalField` arm.
- `FetcherEmitter` wires `ComputedField` as `new ColumnFetcher<>(DSL.field(name))` with no list branching (FetcherEmitter.java:139-144); jOOQ multiset round-trips through the parent's result Record under that alias, and graphql-java iterates whatever list is found.
- `deriveBatchKeyFromTypedAccessor` (FieldBuilder.java:3033, load-bearing classifier check `accessor-rowkey-cardinality-matches-field` at FieldBuilder.java:3025) auto-derives `AccessorKeyedMany` on the second hop from a list-axis typed-`TableRecord` accessor on a Java-record parent, where the element class is a jOOQ `TableRecord` subtype.
- `GraphitronSchemaValidator.validateComputedField` (GraphitronSchemaValidator.java:828) only rejects `ComputedField` carrying a join path (the deferred condition-join lift form), not list cardinality.

The one structural gap: `ServiceCatalog.peelContainer` (ServiceCatalog.java:816) performs raw-class equality (`rawCls == List.class`, `rawCls == Set.class`) when classifying the container axis of an accessor's return type. `Result<R>` extends `List<R>` (jOOQ's `Result` interface, `R extends Record`) but the equality check rejects it; the accessor falls through to `AccessorDerivation.None` and the second-hop lift never fires. Recommending `List<R>` as the bucket-record accessor shape would force users to call `.convertFrom(...)` to coerce the multiset result and is contrary to jOOQ's idiomatic multiset usage. Widening `peelContainer` to accept any `List` / `Set` subtype is the natural fix and is the load-bearing change R109 absorbs.

The empirical question is whether jOOQ's multiset round-trips cleanly through `ColumnFetcher`'s `record.get(DSL.field(name))` lookup on the parent select with the bucket Java record materialised correctly under that alias. Either it works as the trace predicts (modulo the `peelContainer` widening), or a small concrete gap surfaces, at which point R109 forks the gap into a follow-up plan and ships only what works.

## Classifier extension

`ServiceCatalog.peelContainer` widens its container-axis recognition from raw-class equality to assignability:

- `List.class.isAssignableFrom(rawCls)` selects `ContainerKind.LIST`;
- `Set.class.isAssignableFrom(rawCls)` selects `ContainerKind.SET`;
- otherwise the existing `Optional.empty()` fall-through stands.

Element-type extraction continues via `pt.getActualTypeArguments()[0]`, with a stated classifier guarantee that the element-type axis is the parameterised type's first type-argument. `Result<R>` satisfies this: `R` is its sole type variable and flows directly into the `List<R>` supertype. Subclasses that reorder type variables such that the element type is not the first type-argument (e.g. `class Tagged<K, T> extends ArrayList<T>`) are out of scope; they fall through to `AccessorDerivation.None` and require a `@batchKeyLifter` workaround. The existing `@LoadBearingClassifierCheck("accessor-rowkey-cardinality-matches-field")` at FieldBuilder.java:3025 continues to cover the cardinality contract; no new check is added.

## Deliverables

1. **Classifier extension and pipeline-tier coverage.** Widen `ServiceCatalog.peelContainer` per above; update its javadoc to state the first-type-argument element-axis guarantee. Add a `ResultPayload` fixture to `no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads` (a Java record with a `Result<FilmRecord>` accessor, FQN-cited from the SDL fixture). Add an enum arm to `GraphitronSchemaBuilderTest.AccessorDerivedBatchKeyCase` (GraphitronSchemaBuilderTest.java:2290) named `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_RESULT_ACCESSOR` whose schema parents `films: [Film!]!` on a `Payload @record` backed by `ResultPayload`, and whose assertions match the existing `LIST_FIELD_LIST_ACCESSOR` arm at line 2291: `RecordTableField` with `BatchKey.AccessorKeyedMany`, accessor method name `films`, hop target-key columns equal to the element table's PK.
2. **Sakila fixture** in `graphitron-sakila-service` proving the path end-to-end. Sakila has the right shape for this without a contrived domain model: rentals belong to inventory, which belongs to a film, which belongs to film-categories. A natural fixture is `Customer.rentalsByCategory: [RentalsByCategory!]` where each `RentalsByCategory` bucket carries the category id, the category name, and the customer's rentals in that category. Concretely:
   - A new `CustomerExtensions.rentalsByCategory(Customer customer)` returning `Field<Result<...>>` from a `multiset` correlated to the parent customer row, grouped by `film_category.category_id`.
   - A `RentalsByCategory` Java record with components `categoryId: Integer`, `categoryName: String`, `rentals: Result<RentalRecord>` so the second-hop classifier auto-derives `AccessorKeyedMany` from the typed `rentals()` accessor via the widened `peelContainer`.
   - Schema additions on `Customer` (a `@table` type at schema.graphqls:362) and a new `RentalsByCategory` `@record` type whose `rentals` field returns `[Rental!]!`.
3. **Execution-tier test** in `GraphQLQueryTest` (graphitron-rewrite/graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java), named in the same `<root>_<field>_<assertion>` style as the existing `inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord` neighbour at line 333. The test runs `{ customerById(customer_id: ["1"]) { customerId rentalsByCategory { categoryName rentals { rentalId rentalDate } } } }` (`customer_id` is `[ID]` per schema.graphqls:20, hence the string literal) and asserts both:
   - The bucket projection populates `categoryName` and the right number of buckets per customer (the multiset GROUP BY is correct).
   - The inner `rentals { rentalDate }` resolves a non-PK column not present on the lifted `RentalRecord`, proving the second-hop `AccessorKeyedMany` lift fires and batch-fetches the full rows by PK.
4. **How-to recipe** in `docs/manual/how-to/computed-fields.adoc` as a fourth subsection alongside the existing "Scalar `Field<T>`" (line 68), "Lifted `Field<TableRecord<?>>`" (line 72), and "Lifted `Field<CustomJavaRecord>`" (line 104) sections. The new subsection ("Lifted `Field<Result<R>>` for grouped collections via `multiset`") covers:
   - The motivating shape (parent → list of bucket records → list of inner table records).
   - The static-method signature and a worked Sakila example matching the fixture above.
   - The bucket Java record shape and why the typed `Result<RentalRecord>` accessor is what makes the inner lift work.
   - An explicit recommendation of `Result<R>` over `List<R>` for the bucket-record accessor: jOOQ multiset materialises into `Result<R>` natively, so `convertFrom` coercion is unnecessary; either shape classifies as `AccessorKeyedMany` after the `peelContainer` widening, but `Result<R>` is the idiomatic jOOQ choice.
   - The cross-link to `result-types.adoc:133` for the broader `@record` decision tree.
   - A "constraints" bullet noting Postgres-only `multiset` support if relevant after fixture work.
   - A "see also" pointer back to the existing lift forms.
5. **Cross-link addition** from the "Picking a variant: a quick decision tree" section of `docs/manual/how-to/result-types.adoc` (line 133) and its "See also" block (line 149) to the new `computed-fields.adoc` subsection, so a reader on the result-types path discovers the multiset bucket pattern.

## Tasks

In order:

1. Widen `ServiceCatalog.peelContainer` to accept `List` / `Set` subtypes; update its javadoc to state the first-type-argument element-axis guarantee.
2. Add the `ResultPayload` fixture and the new `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_RESULT_ACCESSOR` enum arm; confirm the pipeline-tier test passes via `mvn install -Plocal-db`.
3. Add the schema, extension method, and Java record for the Sakila grouping fixture; confirm it builds.
4. Add the execution-tier test with the assertions above; confirm it passes.
5. If anything else in the trace turns out to be wrong (multiset round-trip fails through `ColumnFetcher`, bucket-record mapping needs jOOQ-side help, or another classifier path actually does reject the shape), capture the concrete gap as a follow-up Backlog item under a different `R<n>` and narrow this item's scope to the parts that succeed. Do not silently expand framework changes under R109 beyond the `peelContainer` widening above.
6. Write the `computed-fields.adoc` subsection from the working fixture.
7. Add the cross-link from `result-types.adoc:133` and its "See also" block at line 149.
8. Confirm the docs site renders cleanly: `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` (no `-P!docs`) so the AsciiDoctor render exercises the new content.

## Done means

- `peelContainer` widening, `ResultPayload` fixture, and pipeline-tier classifier-arm test merged on trunk.
- Sakila fixture and execution test merged on trunk.
- New "Lifted `Field<Result<R>>`" subsection visible in the rendered how-to (`computed-fields.html`), with a working code example matching the fixture and the `Result<R>` recommendation.
- A reader who arrives at "I want to expose grouped child collections" can find the recipe from either `external-code.adoc`, `computed-fields.adoc`, or `result-types.adoc` without bouncing off the `@service` recipe.

## Out of scope

- Subclasses of `List` / `Set` that reorder type variables such that the element type is not the parameterised type's first type-argument. The classifier falls through silently; users can reach for `@batchKeyLifter` instead.
- `Result<Record>` (untyped jOOQ `Record`) accessors. Element resolution requires the element class to be a `TableRecord` subtype via `svc.resolveTableByRecordClass`; this constraint is unchanged and continues to apply.
- Multi-database `multiset` portability. Postgres-only via `JSON_ARRAYAGG` for now; documentation footnote, not a blocker.
- Interaction with `@override` and `@splitQuery` from the motivating Opptak example. The trace did not exercise those compositions; the recipe should not claim they compose. Filing a follow-up if downstream demand surfaces is fine.
- `ColumnFetcher` value-by-name lookup behaviour against multiset-projected aliases. If it fails, fork into a sibling rather than expanding R109.

## Open risks

- `ColumnFetcher`'s `record.get(DSL.field(name))` lookup against a multiset alias might need an explicit `Class<?>` argument the way `ParticipantColumnReferenceField` does (FetcherEmitter.java:152-155). Containment plan: surface the concrete error when the fixture fails, file the gap as a separate item, document only the part that works.
- `multiset` SQL portability is Postgres-only via `JSON_ARRAYAGG`. If we ever broaden the supported DB matrix, the recipe needs a portability note. For now Postgres is the only target, so this is a documentation footnote, not a blocker.
- Interaction with `@override` and `@splitQuery` from the motivating Opptak example was not exercised by the structural trace. If the recipe is going to claim those compose, the fixture should cover them; otherwise the doc should call out the gap and link a follow-up item.
