---
id: R109
title: "How-to recipe and Sakila fixture for grouped collections via Field<Result<R>> @externalField + multiset"
status: Spec
theme: service
depends-on: []
last-updated: 2026-07-14
---

# How-to recipe and Sakila fixture for grouped collections via `Field<Result<R>>` `@externalField` + multiset

## Problem

A common service shape is "child rows grouped by a category, exposed on the parent as a list of synthetic bucket types": e.g. `Opptak.grupperteOpptakshendelser: [GrupperteOpptakshendelser!]` where each bucket carries a category id, a category name, and the events in that category. Users today reach for `@service` and hand-write a `Map<ParentRecord, List<Bucket>>` batch loader: build a key set, issue the join query, bucket rows in Java, reconstruct the group records. The grouping is not declarative, the inner records are constructed outside Graphitron scope so sub-resolvers on them lose the framework's batching path, and the same pattern recurs across every service that has "events grouped by category", "lines grouped by status", "items grouped by tag".

A code trace through the rewrite suggests the existing `@externalField` machinery already supports the obvious alternative, a static method returning `Field<Result<R>>` produced by jOOQ `multiset(...) GROUP BY ...`, plus a load-bearing classifier addition. The natural shape pushes the bucket Java record's inner-list accessor to be `Result<RentalRecord>` (jOOQ multiset's native materialisation), not `List<RentalRecord>`. The classifier currently rejects `Result<R>` accessors because `ServiceCatalog.peelContainer` (ServiceCatalog.java:1037 at the time of writing) does raw-class equality on `List.class` / `Set.class`. R109 adds `Result.class` to that recognition set as a first-class deliverable, validates the path end-to-end with a Sakila fixture, and writes the recipe.

## What we believe is true (and want to prove)

The structural argument that the path is mostly already wired:

- `ServiceCatalog.reflectExternalField` (ServiceCatalog.java:691 at the time of writing) only requires the return to be a parameterised `org.jooq.Field<...>`; it does not constrain the type argument. `Field<Result<R>>` and any other parameterised `Field<X>` pass.
- `ExternalFieldDirectiveResolver.resolve` has no list-cardinality rejection.
- `FieldBuilder` classifies `@externalField` into `ComputedField` regardless of the GraphQL field's wrapper (the `new ComputedField(...)` construction inside the `externalFieldResolver.resolve(...)` switch); there is no `wrapper().isList()` guard on the `@externalField` arm.
- `FetcherEmitter` wires `ComputedField` through `columnByAlias(field.name(), ...)` with no list branching; jOOQ multiset round-trips through the parent's result Record under that alias, and graphql-java iterates whatever list is found.
- `FieldBuilder.deriveAccessorRecordParentSource` (returning the sealed `AccessorDerivation` with `Ok` / `None` / `Ambiguous` / `CardinalityMismatch` arms) auto-derives `AccessorKeyedMany` on the second hop from a list-axis typed-`TableRecord` accessor on a Java-record parent, where the element class is a jOOQ `TableRecord` subtype.
- `GraphitronSchemaValidator.validateComputedField` only rejects `ComputedField` carrying a join path (the deferred condition-join lift form), not list cardinality.

The one structural gap: `ServiceCatalog.peelContainer` performs raw-class equality (`rawCls == List.class`, `rawCls == Set.class`) when classifying the container axis of an accessor's return type. `Result<R>` extends `List<R>` (jOOQ's `Result` interface, `R extends Record`) but the equality check rejects it; the accessor falls through to `AccessorDerivation.None` and the second-hop lift never fires. Recommending `List<R>` as the bucket-record accessor shape would force users to call `.convertFrom(...)` to coerce the multiset result and is contrary to jOOQ's idiomatic multiset usage. Adding an explicit `Result.class` arm to `peelContainer` is the load-bearing change R109 absorbs.

The empirical question is whether jOOQ's multiset round-trips cleanly through `ColumnFetcher`'s `record.get(DSL.field(name))` lookup on the parent select with the bucket Java record materialised correctly under that alias. Either it works as the trace predicts (modulo the `peelContainer` widening), or a small concrete gap surfaces, at which point R109 forks the gap into a follow-up plan and ships only what works.

## Classifier extension

`ServiceCatalog.peelContainer` adds an explicit `org.jooq.Result.class` arm alongside the existing `List.class` / `Set.class` equality checks:

- `rawCls == java.util.List.class || rawCls == org.jooq.Result.class` selects `ContainerKind.LIST`;
- `rawCls == java.util.Set.class` selects `ContainerKind.SET`;
- otherwise the existing `Optional.empty()` fall-through stands.

Element-type extraction continues via `pt.getActualTypeArguments()[0]`. `Result<R>`'s sole type variable `R` flows directly into the `List<R>` supertype, so the existing extraction is correct without any subtype-traversal logic. The arm is deliberately narrow rather than `List.class.isAssignableFrom(rawCls)`: the broader form would silently accept arbitrary `List` / `Set` subclasses (including ones that reorder type variables, such as `class Tagged<K, T> extends ArrayList<T>` where the element axis is not at type-argument zero), falling through to `AccessorDerivation.None` with no diagnostic. Keeping the producer narrow preserves the classifier guarantee that every accepted shape has type-argument zero as the element axis, which is what `deriveAccessorRecordParentSource` and the emitter arms downstream rely on.

Two coordinated updates ride along:

- `peelContainer`'s javadoc names the accepted container raw classes ("returning X, List<X>, or Set<X>"); update it to read "X, List<X>, Set<X>, or Result<X>" so the documentation matches the code.

The classifier's existing cardinality handling (the `AccessorDerivation.CardinalityMismatch` arm of `deriveAccessorRecordParentSource`) continues to cover the cardinality side; no new structural check is needed.

## Deliverables

1. **Classifier extension and pipeline-tier coverage.** Add the `Result.class` arm to `ServiceCatalog.peelContainer`; update its javadoc to include `Result<X>` alongside the existing `X / List<X> / Set<X>` shapes. Add a `ResultPayload` fixture to `no.sikt.graphitron.codereferences.dummyreferences.AccessorPayloads` (a Java record with a `Result<FilmRecord>` accessor, FQN-cited from the SDL fixture). Add an enum arm to `GraphitronSchemaBuilderTest.AccessorDerivedBatchKeyCase` named `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_RESULT_ACCESSOR` whose schema parents `films: [Film!]!` on a `Payload @record` backed by `ResultPayload`, and whose assertions match the existing `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_LIST_ACCESSOR` arm: `RecordTableField` with `BatchKey.AccessorKeyedMany`, accessor method name `films`, hop target-key columns equal to the element table's PK.
2. **Sakila fixture** in `graphitron-sakila-service` proving the path end-to-end. Sakila has the right shape for this without a contrived domain model: rentals belong to inventory, which belongs to a film, which belongs to film-categories. A natural fixture is `Customer.rentalsByCategory: [RentalsByCategory!]` where each `RentalsByCategory` bucket carries the category id, the category name, and the customer's rentals in that category. Concretely:
   - A new `CustomerExtensions.rentalsByCategory(Customer customer)` returning `Field<Result<...>>` from a `multiset` correlated to the parent customer row, grouped by `film_category.category_id`.
   - A `RentalsByCategory` Java record with components `categoryId: Integer`, `categoryName: String`, `rentals: Result<RentalRecord>` so the second-hop classifier auto-derives `AccessorKeyedMany` from the typed `rentals()` accessor via the widened `peelContainer`.
   - Schema additions on `Customer` (`type Customer implements Node @table(name: "customer") @node` in schema.graphqls) and a new `RentalsByCategory` `@record` type whose `rentals` field returns `[Rental!]!`.
3. **Execution-tier test** in `GraphQLQueryTest` (graphitron-sakila-example/src/test/java/no/sikt/graphitron/rewrite/test/querydb/GraphQLQueryTest.java), named in the same `<root>_<field>_<assertion>` style as the existing `inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord` neighbour. The test runs `{ customerById(customer_id: ["1"]) { customerId rentalsByCategory { categoryName rentals { rentalId rentalDate } } } }` (`customer_id` is `[ID]` on `customerById` in schema.graphqls, hence the string literal) and asserts both:
   - The bucket projection populates `categoryName` and the right number of buckets per customer (the multiset GROUP BY is correct).
   - The inner `rentals { rentalDate }` resolves a non-PK column not present on the lifted `RentalRecord`, proving the second-hop `AccessorKeyedMany` lift fires and batch-fetches the full rows by PK.
4. **How-to recipe** in `docs/manual/how-to/computed-fields.adoc` as a fourth subsection alongside the existing "Scalar `Field<T>`", "Lifted `Field<TableRecord<?>>`", and "Lifted `Field<CustomJavaRecord>`" sections. The new subsection ("Lifted `Field<Result<R>>` for grouped collections via `multiset`") covers:
   - The motivating shape (parent → list of bucket records → list of inner table records).
   - The static-method signature and a worked Sakila example matching the fixture above.
   - The bucket Java record shape and why the typed `Result<RentalRecord>` accessor is what makes the inner lift work.
   - An explicit recommendation of `Result<R>` over `List<R>` for the bucket-record accessor: jOOQ multiset materialises into `Result<R>` natively, so `convertFrom` coercion is unnecessary; either shape classifies as `AccessorKeyedMany` after the `peelContainer` arm is added, but `Result<R>` is the idiomatic jOOQ choice.
   - The cross-link to the "Picking a variant" section of `result-types.adoc` for the broader `@record` decision tree.
   - A "constraints" bullet noting Postgres-only `multiset` support if relevant after fixture work.
   - A "see also" pointer back to the existing lift forms.
5. **Cross-link addition** from the "Picking a variant: a quick decision tree" section of `docs/manual/how-to/result-types.adoc` and its "See also" block to the new `computed-fields.adoc` subsection, so a reader on the result-types path discovers the multiset bucket pattern.

## Tasks

In order:

1. Add the `Result.class` arm to `ServiceCatalog.peelContainer`; update its javadoc and any container-shape prose on `FieldBuilder.deriveAccessorRecordParentSource` / `AccessorDerivation` in the same change.
2. Add the `ResultPayload` fixture and the new `ACCESSOR_ROWKEYED_MANY_LIST_FIELD_RESULT_ACCESSOR` enum arm; confirm the pipeline-tier test passes via `mvn install -Plocal-db`. The classifier extension is independently load-bearing on the recipe's `Result<R>` recommendation and ships at this point regardless of what happens downstream.
3. Add the schema, extension method, and Java record for the Sakila grouping fixture; confirm it builds.
4. Add the execution-tier test with the assertions above; confirm it passes. **Gating checkpoint:** if the test fails because `ColumnFetcher`'s `record.get(DSL.field(name))` lookup against a multiset-projected alias misbehaves (the empirical risk the trace did not eliminate), do not paper over it. Surface the concrete error, file a sibling Backlog item under a different `R<n>` for the `ColumnFetcher` gap, and ship R109 as classifier-only with the recipe and Sakila fixture deferred to that sibling.
5. If any other trace assumption turns out wrong (bucket-record mapping needs jOOQ-side help, an unrelated classifier path rejects the shape), apply the same fork-then-narrow rule. Do not expand framework changes under R109 beyond the `peelContainer` arm and the description update.
6. Write the `computed-fields.adoc` subsection from the working fixture.
7. Add the cross-link from `result-types.adoc`'s "Picking a variant" section and its "See also" block.
8. Confirm the docs site renders cleanly: `mvn install -Plocal-db` from the repo root (no `-P!docs`) so the AsciiDoctor render exercises the new content.

## Done means

- `peelContainer` widening, `ResultPayload` fixture, and pipeline-tier classifier-arm test merged on trunk.
- Sakila fixture and execution test merged on trunk.
- New "Lifted `Field<Result<R>>`" subsection visible in the rendered how-to (`computed-fields.html`), with a working code example matching the fixture and the `Result<R>` recommendation.
- A reader who arrives at "I want to expose grouped child collections" can find the recipe from either `external-code.adoc`, `computed-fields.adoc`, or `result-types.adoc` without bouncing off the `@service` recipe.

## Out of scope

- Arbitrary `List` / `Set` subclasses beyond `Result`. The classifier extension is deliberately a narrow `Result.class` arm rather than open-ended subtype-assignability; user types that subclass `List` or `Set` continue to fall through to `AccessorDerivation.None` and require a `@batchKeyLifter` workaround. Adding further arms is fine in a follow-up if a concrete demand surfaces.
- `Result<Record>` (untyped jOOQ `Record`) accessors. Element resolution requires the element class to be a `TableRecord` subtype via `svc.resolveTableByRecordClass`; this constraint is unchanged and continues to apply.
- Multi-database `multiset` portability. Postgres-only via `JSON_ARRAYAGG` for now; documentation footnote, not a blocker.
- Interaction with `@override` and `@splitQuery` from the motivating Opptak example. The trace did not exercise those compositions; the recipe should not claim they compose. Filing a follow-up if downstream demand surfaces is fine.
- `ColumnFetcher` value-by-name lookup behaviour against multiset-projected aliases. If it fails, fork into a sibling rather than expanding R109.

## Open risks

- `ColumnFetcher`'s `record.get(DSL.field(name))` lookup against a multiset alias might need an explicit `Class<?>` argument the way `FetcherEmitter`'s `ParticipantColumnReferenceField` arm passes one. Containment plan: surface the concrete error when the fixture fails, file the gap as a separate item, document only the part that works.
- `multiset` SQL portability is Postgres-only via `JSON_ARRAYAGG`. If we ever broaden the supported DB matrix, the recipe needs a portability note. For now Postgres is the only target, so this is a documentation footnote, not a blocker.
- Interaction with `@override` and `@splitQuery` from the motivating Opptak example was not exercised by the structural trace. If the recipe is going to claim those compose, the fixture should cover them; otherwise the doc should call out the gap and link a follow-up item.
