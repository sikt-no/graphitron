# What the code-reference subject needs from the corpora before it converts

Enumerated 2026-10-03 for the seeding branch's code-reference subject: the classes that seed the
per-site copies of a code reference, which the producer branch subtracts. Three read-only passes
read every class for the shape it fabricates rather than the name, against
`graphitron-sakila-db/src/main/resources/init.sql` and `graphitron-sakila-service`. A converted test
names a real table or class wherever one of the same shape exists, so a gap is a shape no corpus
has. Twenty-six classes, the node-id subject's three left to it.

## What applies to every conversion

* `public.film` has two foreign keys to `language`, so a path leaving `film` for `language` names
  its key.
* `graphitron-sakila-db` generates four jOOQ packages, `public`, `nodeidfixture`, `idreffixture`
  and `multischemafixture`, and a capture reads one. A second graph against another is
  `CapturedStore.andCatalogGraph`.
* Capture writes the raw `graphql_directive_application` row beside every decode, which no seeded
  decode does, so a relation anti-joining the raw rows answers differently once converted.
* None of the subject's intent classes writes a `code_` row: a `@service`, `@externalField` or
  `@condition` is seeded as a directive entry naming nothing real. Every one has a real stand-in in
  `graphitron-sakila-service`.

## Classes with no gap

`ConditionMembershipTest` (both), `ConditionSlotTest`, `ConditionParamDecodeTest`,
`ArgmappingEntryTest`, `ArgmappingMatchTest`, `ArgmappingProjectionDefectTest`,
`MutationPayloadColumnTest`, `MutationPayloadRefusalTest`, `MutationWritePayloadTest`,
`FieldUnlowerableOrderingTest`, `InputOccurrenceOverrideTest`, `SeparateFetchRuleTest`,
`EntryDefectTest`, `AuthoredClaimTest`, `DemandRuleTest`, `CarrierDataFieldPopulationTest`,
`InputFieldFilterRoleTest`. Some cases in them are rewritten, below; none needs a corpus change.

## Gaps

| corpus | shape | class |
|---|---|---|
| catalog | a column named `first`, `last`, `after` or `before` | `ArgumentFilterRoleTest` |
| catalog | a self-referencing foreign key onto the table's own alternate unique key | `InputFieldCarrierRoleTest` |
| catalog | a table with two or more unique keys beside its primary key | `MutationMatchedKeyTest` |
| catalog | a self-referencing foreign key whose column is itself a unique key | `MutationMatchedKeyTest` |
| catalog | a composite unique key, not the primary key, covered by a composite foreign key | `MutationMatchedKeyTest` |
| catalog | one table name in two jOOQ packages | `ColumnMatchClaimTest` |
| harness | two catalog sources in one graph; two schemas of one package may stand in | `ColumnMatchClaimTest` |
| catalog | a table with foreign keys into two same-named tables of different schemas; unsure it is needed | `FieldColumnTableTest` |
| classpath | an overloaded instance accessor, `getX()` beside `getX(Y)` | `AccessorHopTest` |
| classpath | a parameterised instance getter, `getX(String)` | `AccessorHopTest` |
| classpath | an accessor returning `CompletableFuture<List<X>>`, `Map<String, X>`, or an array | `AccessorHopTest` |
| classpath | one slot name on two classes with different element classes | `AccessorHopTest` |
| classpath | a record component named `node` | `AccessorHopTest` |
| classpath | a raw `List` and a generic non-container `Box<X>` accessor; `List<?>` may stand in | `AccessorHopTest` |
| classpath | one class name on two classpath entries with different members | `AccessorHopTest`, `TypeBackingSeedTest` |
| classpath | a parameter with no captured name; every corpus module compiles with `-parameters` | `TypeBackingSeedTest` |
| classpath | a class of `GraphQLScalarType` constants | LSP `StoreFixture` |
| classpath | an extension class declaring non-lifters beside lifters | LSP `StoreFixture` |

Two of the classpath rows are not a class added to `graphitron-sakila-service`: one class name on
two entries wants a second classpath entry, and a parameter with no name wants an entry compiled
without `-parameters`.

## Cases no capture reaches

Rewritten or dropped rather than converted, on the branch's terms.

* `AuthoredClaimTest`: decodes with no raw application row, and ordinals inverted against document
  order in three cases.
* `ArgmappingMatchTest`, `ArgmappingProjectionDefectTest`: a field typed `String` whose occurrence
  step says `ID`.
* `MutationPayloadRefusalTest`: two input fields sharing ordinal 0.
* `MutationWritePayloadTest`: a primary key with no columns.
* `DemandRuleTest`: a query root bound to an interface; an enum and a union given fields.
* `SeparateFetchRuleTest`: `Query` and `Mutation` bound to one type; an input field typed as an
  output object.
* `ColumnMatchClaimTest`: jOOQ names crossed against SQL names, which the default generator does
  not produce; and binding witnesses to tables the catalog lacks.
* `FieldColumnTableTest`, `FieldUnlowerableOrderingTest`: a connection type authored where the
  `@asConnection` expansion mints it.
* `ExternalFieldMethodNarrowingTest` (LSP): a `code_method.result_type` contradicting the census.
