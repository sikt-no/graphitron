---
id: R990
title: "A list @nodeId argument at a java.util.List producer parameter is refused by the store although the generator decodes it"
status: Spec
bucket: bug
theme: nodeid
depends-on: []
created: 2026-10-06
last-updated: 2026-10-06
---

# A list @nodeId argument at a java.util.List producer parameter is refused by the store although the generator decodes it

## Goal

A root `@service` whose list-typed `@nodeId` argument lands on a `java.util.List` parameter builds, and each decoded id reaches one element of that list. That holds at any key arity, and for each element type the generator supports: the node type's generated record, its sole key column's Java type, or, where `typeName:` names a union or interface, a record supertype every member's record is. The generator already emits this decode. The *fact store* (the in-memory database a build captures the schema, the jOOQ catalog and the classpath into, and derives refusals from) compares the parameter's container, `java.util.List`, where it should compare the element. So today every such signature fails the build, and the message tells the author to declare the type they already declared. After this item the store judges the element. A list argument whose parameter is not a `java.util.List`, or a single id whose parameter is a multi-valued container, is refused by name, because the generator can emit neither.

The motivating shape, over sakila's composite-key `FilmActor` (key `actor_id, film_id`):

```graphql
type Query {
    filmActorsByIds(ids: [ID!]! @nodeId(typeName: "FilmActor")): [FilmActor!]!
        @service(service: {className: "...FilmActorCarrierService", method: "filmActorsByIds"})
}
```

```java
public static List<FilmActorRecord> filmActorsByIds(List<FilmActorRecord> ids, DSLContext dsl)
```

Today this fails the build with *"argument 'ids' carries the @nodeId(typeName: "FilmActor") and the producer method declares a parameter 'ids' of that name, so the decoded key lands there, but that key is 2 columns and one parameter takes one value; declare 'ids' as the generated record of that node type's own table ..."*. After this item it builds, and the query returns the two film-actor rows the ids name. The contrast is the same argument on `Set<FilmActorRecord> ids`: still refused, because the emitted list decode hands over a `java.util.List`, and now refused with a message that says so and names `List<FilmActorRecord>` as the fix.

## What is true today

Verified against trunk at `8f41aa2` with throwaway store-tier probes beside `NodeIdDecodeDefectTest` and `PolymorphicNodeIdDecodeTest` (seeded parameter `Map.of("", "java.util.List", "0", <element>)`, the census shape `CodeRows` already understands):

- `List<film_categoryRecord>` at the composite `FilmCategory`: `KEY_ARITY_EXCEEDS_SLOT arity 2 ... slot java.util.List`, no row in `intent_node_id_decode`. The reported bug.
- `List<String>` at the one-column `Film`, whose key column the fixture types as `String`, so the element agrees: `KEY_COLUMN_TYPE_DISAGREEMENT arity 1 column film_id java.lang.String slot java.util.List`, no destination. **Wider than the report:** every list-typed named parameter is refused, at any arity, record or not.
- `Set<film_categoryRecord>`: `KEY_ARITY_EXCEEDS_SLOT ... slot java.util.Set`. The refusal is right and the message is wrong.
- `List<org.jooq.UpdatableRecord>` at the `AddressOccupant` union producer slot: `SLOT_NOT_SUPERTYPE_OF_MEMBER` for both `Customer` and `Staff`, no destination. **The polymorphic sibling has the same defect.** `PolymorphicNodeIdSlotPipelineTest.aProducerListParameterTakesTheListVariant` classifies `PublicNodeIdServiceStub.getOccupantsByUpdatableRecords` fine because it never runs the store detections.

Why, by symbol:

- `intent_node_id_decode_slot.java_type` is the root of the parameter's declared type (`code_type.root_class`), deliberately, so it equals `intent_argmapping_bound_parameter_type.java_type`. Its column comment leaves the list reading to consumers ("that a list of node ids is a coherent request ... is a consumer's reading").
- Four readers compare that root with a record or column type and none applies the list reading: the slot arm of `intent_node_id_decode` (`JOOQ_RECORD`, `SINGLE_KEY_COLUMN` and the `POLY_CONTAINER` assignability test), `intent_node_id_decode_defect` (both verdicts), and the `SLOT_NOT_SUPERTYPE_OF_MEMBER` arm of `intent_node_id_polymorphic_decode_defect`.
- The generator peels exactly one `java.util.List`: `ServiceCatalog.takesTheNodeTablesRecord` and `ServiceCatalog.elementTypeName` classify the slot, and `ServiceMethodCallEmitter.isListType` picks the list decode helper (`register(..., isListType(javaType))`, `decodeList`, `decodeContainerList`) for all three `@nodeId` arms. It keys on the raw type `java.util.List` and on nothing else. In particular it never consults the SDL argument's list-ness, and it treats a `Set`, `Collection` or jOOQ `Result` as single-valued.
- The census already holds the peeled type: `code_method_parameter.element_class` and `delivery` (`MANY` where a `List`, `Set`, `Collection` or `Result` was peeled; `CodeCapture` writes them on the parameter row, and the same facts per type are on `code_type_element`). The SDL side holds the argument's shape on `graphql_argument.is_list` / `list_depth`. No new capture is needed.
- No sakila or generator-test schema puts a list `@nodeId` argument on a `@service` field at all, which is why nothing caught this. The only list cases are input-bean members (`assignFilmActorRecordList` and siblings), which are `INPUT_FIELD` sites outside the defect view's population.

The sis migration's six refused services (`List<UndervisningsaktivitetRecord>` / `List<UndervisningsenhetRecord>` over an eight-column key) are this shape exactly. Why the same snapshot validated clean earlier that day is not this item's question. The SQL says a missing `code_method_parameter` row draws no slot row and so no verdict, which is the classpath-capture gap, not this one.

## Design

### The slot relation states the slot's shape and where one decoded value lands

Two columns on `intent_node_id_decode_slot`, beside `java_type`. `java_type` stays the root, so the equality with `intent_argmapping_bound_parameter_type` and that column's argument both stand. Both columns are decided at the `ARGUMENT` site, on both carriers, from three facts. The first is the root argument's `graphql_argument.is_list` / `list_depth`. The second is the parameter's root class, which is `java_type` itself (`code_type.root_class` on the named arm, `intent_argmapping_bound_parameter_type.java_type` on the mapped one). The third is the parameter's peeled `element_class` and `delivery`. On the named arm those are `code_method_parameter.element_class` / `delivery`, which the slot view already joins as `mp`, so no new join is needed. On the mapped arm, `intent_argmapping_bound_parameter_type` gains the same two columns on its classpath arm, read off the `mp` it already joins, and NULL on its routine arm.

- **`slot_shape`**, closed vocabulary `SINGLE` / `LIST` / `MISMATCH`, and NULL where this relation does not judge the shape:
  - `LIST`: the argument is a list at depth one and the parameter's root class is `java.util.List`. Naming the class is not the coincidence `code_type_element.delivery`'s comment warns against, which is answering *cardinality* with a container name. The question here is the identity of the one class the emitter's list helpers build, the same kind of test as `java_type = k.record_class`.
  - `SINGLE`: the argument is not a list and the parameter's delivery is not `MANY` (`DIRECT`, `WRAPPED`, or a root class with no element).
  - `MISMATCH`: every other typed combination. That covers a list argument at a parameter whose root is not `java.util.List` (a `Set`, a `Collection`, a scalar, `Object`, a record), a single id at a `MANY` parameter, and a list nested deeper than one level.
  - NULL: `java_type` is NULL (a primitive, a type variable, no class at the position), at the `INPUT_FIELD` site, and on a mapped `ROUTINE` pair. NULL means readers keep today's reading of `java_type`, so the population edges `NodeIdDecodeDefectTest` pins (`anUntypeableParameterAtACompositeKeyIsStillRefusedOnTheArity`, `anInputFieldSlotDrawsNoVerdict`) do not move.
- **`landing_type`**: the type one decoded value is handed to. The parameter's `element_class` on `LIST`, `java_type` otherwise. It is never compared on `MISMATCH`, and is carried there only so a message can name it.

**Agreement with the emitter is by test, not by construction, and that is a stated limit.** List-ness is decided in Java three times (`ServiceCatalog.takesTheNodeTablesRecord`, `ServiceCatalog.elementTypeName`, `ServiceMethodCallEmitter.isListType`, the last over a `TypeName`, because `CallSiteExtraction` does not carry it), and this item decides it once more in SQL. The enforcers are the pipeline-tier store assertion beside `PolymorphicNodeIdSlotPipelineTest.aProducerListParameterTakesTheListVariant` and the execution-tier pins under Tests, and the `slot_shape` column comment names them. There is one known divergence. `element_class` is the type with *every* container peeled (`CodeCapture.deliveryOf` loops), while the emitter unwraps exactly one `List<…>`. So `List<Optional<Integer>>` or `List<Set<FilmActorRecord>>` reads as `LIST` with the inner class as its landing, while the emitter routes it elsewhere. The store keeps no per-position type arguments, so this cannot be closed from stored facts at bug-fix scale. The `landing_type` comment states it, with javac as the backstop, on the family's own terms for operands it cannot read.

Rewrite the `java_type` column comment's last sentence: the list reading now lives on `landing_type`, because the four readers below need the same one. Add comments for the new columns, on the slot view and on `intent_argmapping_bound_parameter_type`, in the file's register.

### The readers compare `landing_type`, and each defect view states its own shape verdict

- **`intent_node_id_decode`, slot arm**: every `s.java_type` in the destination `CASE` and the admission predicate becomes `s.landing_type`, including the `sql_table.record_class_fqn` exclusion and the `intent_record_slot_assignable.slot_type_name` test. Add `s.slot_shape IS DISTINCT FROM 'MISMATCH'`: a mismatched slot has no destination. The destination vocabulary is unchanged. A `LIST` slot resolves `JOOQ_RECORD`, `SINGLE_KEY_COLUMN` or `POLYMORPHIC_RECORD` exactly as its element would, and list-ness is carried to emission the way it is today, by the slot's `TypeName`.
- **`intent_node_id_decode_defect`** (node types, `NAMED_PARAMETER`, unchanged population):
  - The two existing verdicts read `landing_type` where they read `java_type` and require `slot_shape IS DISTINCT FROM 'MISMATCH'`.
  - A third verdict, **`SLOT_SHAPE_MISMATCH`**, where `slot_shape = 'MISMATCH'`. It keeps the inner join to `intent_resolved_node_key_shape`, so containers still miss this view by construction, the disjointness its comment argues for, and `arity` stays NOT NULL. Since a `CASE` over the row now picks among three verdicts with shape first, it stays one pass. The comment's "decided by the arity alone" becomes "by the shape, then the arity".
  - New columns: `slot_shape`, `record_class` (from `k.record_class`), and `landing_java_type` beside `slot_java_type`, which keeps the root. Those let a message name the fix.
- **`intent_node_id_polymorphic_decode_defect`** (containers, existing population, both carriers):
  - The `slotted` CTE carries `s.landing_type` as `java_type`, and `slot_shape` alongside.
  - A sixth verdict, **`SLOT_SHAPE_MISMATCH`**, as one more arm over `slotted` `WHERE slot_shape = 'MISMATCH'`.
  - `SLOT_NOT_SUPERTYPE_OF_MEMBER` adds `slot_shape IS DISTINCT FROM 'MISMATCH'`, a precedence stated inside the one view that has both arms.
  - The three verdicts about the container itself (`SINGLE_TABLE_CONTAINER`, `NO_TABLE_MEMBERS`, `MEMBER_NOT_NODE_TYPE`) are not about the slot and still fire. A slot that is both mismatched and names a container with no table members draws both errors, and each is its own fix.
  - The verdict name recurs across the two vocabularies, as each family's consumer composes its own prose for its own population.

### Messages (`NodeIdDecodeDefects`, `NodeIdPolymorphicDecodeDefects`)

- `NodeIdDecodeDefects.Verdict.SLOT_SHAPE_MISMATCH` and its `rejectionOf` arm, `Rejection.structural`, both directions sharing the `lead(...)` clause:
  - List argument: *"..., and the argument is a list, so the decoded ids are handed over as a java.util.List, but 'ids' takes Set; declare 'ids' as List<FilmActorRecord>"*. Above arity one the suggested element is the record's simple name. At arity one it is the sole column's Java type, with the record named as the alternative.
  - Single id: *"..., but 'id' takes List, which a list argument fills with one decoded value per id; declare 'id' as <element>, or make the argument a list"*.
- `KEY_ARITY_EXCEEDS_SLOT` names the record instead of describing it, and wraps it at `LIST` shape: *"declare 'ids' as List<FilmActorRecord>"* / *"declare 'key' as InventoryRecord"*. This is the Backlog item's open point 3. The `argMapping` alternative stays.
- `KEY_COLUMN_TYPE_DISAGREEMENT` quotes the landing type and wraps the remedy the same way at `LIST`.
- `NodeIdPolymorphicDecodeDefects` reads the landing type into its existing `slotJavaType` operand, so `SLOT_NOT_SUPERTYPE_OF_MEMBER`'s prose names the element it compared. It also gains `Verdict.SLOT_SHAPE_MISMATCH`, whose prose suggests "a java.util.List of a type every implementation's record is (UpdatableRecord<?>, ...)" for the list direction, in that class's existing remedy vocabulary.

### What this item does not change

- **The generator.** It already decodes every shape this item admits. The asymmetry left is that it classifies a `MISMATCH` slot (a list argument at a `Set`, a single id at a `List`) without complaint and would emit code that fails at runtime or at javac. The store now refuses that by name before emission. That is the existing division of labour: `ServiceCatalog.nodeIdSlotExtraction`'s javadoc leaves arity and type to the store, and shape joins them.
- **The `INPUT_FIELD` site** (Backlog open point 4, confirmed). The defect view draws no row there by its stated population edge, and `slot_shape` is NULL there. A nested `@nodeId` member reaching a list of records is a bean-member decode (`InputBeanResolver`; `NodeIdRecordInputBeanPipelineTest` covers `List<FilmActorRecord>`), not this bug.
- **Which family judges a mapped node-type slot.** The node-type view stays `NAMED_PARAMETER`-only, and a mapped pair is `intent_argmapping_projection_defect`'s to refuse. `slot_shape` is computed on the mapped arm all the same, because the polymorphic view's supertype arm and the decode destinations read that arm. Whether the argMapping family's `BARE_NODE_ID` admits a record or list-of-record parameter at a composite key is unverified. It is not this item's, and if it bites it gets its own Backlog item.
- **Which containers are supported** (Backlog open point 2). `java.util.List` only, because that is what the emitter builds. Supporting `Set` would mean a generator change and a store change together, and nothing has asked for it. It is refused by name instead.

## Tests

Store tier, `graphitron-model`:

- `CodeRows.parameter` writes `element_class` / `delivery` on the `code_method_parameter` row it seeds, from the same `deliveredBy` it already uses for `code_type_element`. Today it leaves them NULL, which real capture never does, and the slot view now reads them.
- `NodeIdDecodeDefectTest`, beside `aRecordOfTheNodeTypesOwnTableIsNoDefectAtAnyArity` and its siblings, a `seedListProducer` helper (`Map.of("", "java.util.List", "0", element)`, plus the root argument seeded as a list):
  - `aListOfTheNodeTypesOwnRecordIsNoDefectAtAnyArity`: no row, destination `JOOQ_RECORD 2`.
  - `aListOfTheSoleKeyColumnsTypeIsNoDefect`: no row, `SINGLE_KEY_COLUMN 1`.
  - `aListWhoseElementDisagreesIsRefusedOnTheElement`: `KEY_COLUMN_TYPE_DISAGREEMENT` with `landing_java_type` the element and `slot_java_type` `java.util.List`.
  - `aSetOfTheRecordIsAShapeMismatch` and `aSingleIdAtAListParameterIsAShapeMismatch`: `SLOT_SHAPE_MISMATCH`, no destination.
  - The existing cases unchanged. `seedArgumentNodeId` seeds its argument through `seedArgument`, which writes a non-list `graphql_argument` row, so they are already `SINGLE`. The list cases seed the argument list-typed first (a `SeededStore.seedArgument` overload taking the list shape), which `seedArgumentNodeId` then leaves alone.
- `NodeIdDecodeDestinationTest` asserts on `slots(dsl)`. Extend its rendering with `slot_shape` / `landing_type` only if a case there needs them, and leave the existing expectations alone.
- `PolymorphicNodeIdDecodeTest`: `aListOfARecordSupertypeIsAssignableFromEveryMembersRecord` (no defect, two `POLYMORPHIC_RECORD` destinations), the same through an `argMapping` pair (the mapped carrier this view also reads), and a `Set` case drawing the polymorphic view's own `SLOT_SHAPE_MISMATCH` and no `SLOT_NOT_SUPERTYPE_OF_MEMBER`, with no row for it in `intent_node_id_decode_defect`, so the two views stay disjoint.

Pipeline tier, `graphitron` (`NodeIdDecodeDefectsTest`, which captures real SDL against the sakila catalog and this module's test classes):

- New stubs on `PublicNodeIdServiceStub`: `getFilmsByInventoryKeys(List<InventoryRecord>)`, `getFilmsByIntegerKeys(List<Integer>)`, `getFilmsByInventoryKeySet(Set<InventoryRecord>)`. `getOccupantsByUpdatableRecords` already exists. The `schema(nodeType, method)` template gains a list-argument sibling.
- `aListOfTheNodeTypesRecordTakesACompositeKey` and `aListOfTheKeyColumnsOwnTypeIsNoDefect`: empty detection.
- `aSetParameterAtAListArgumentIsRefusedNamingTheList`: the exact message.
- `aCompositeKeyAtASingleValuedParameterIsRejectedNamingTheCountAndTheColumns`: expectation updated to the record-naming remedy.
- `PolymorphicNodeIdSlotPipelineTest` gets a store-detection assertion beside `aProducerListParameterTakesTheListVariant`, so the classification test and the store verdict cannot drift apart again.

Execution tier, `graphitron-sakila-example`. This is the evidence the goal is delivered, because it is the motivating SDL building, compiling at Java 17 and running:

- `Query.filmActorsByIds(ids: [ID!]! @nodeId(typeName: "FilmActor"))` on a new `FilmActorCarrierService.filmActorsByIds(List<FilmActorRecord> ids, DSLContext dsl)` that fetches the rows the records key, and a test in `GraphQLQueryTest` (beside the `assignFilmActorRecordList` case) that encodes two film-actor ids and gets those two rows back in order.
- An arity-one sibling, `filmsByNodeIdService(ids: [ID!]! @nodeId(typeName: "Film"))` on a `List<Integer>` parameter, asserting the titles. Both fail the build on trunk today, which makes them the regression pins too.

## Other solutions we've considered

- **Peel in each reader instead of on the slot relation.** Four readers (two arms of `intent_node_id_decode`, and two defect views) would each join `code_type_element` and `graphql_argument` and restate one rule, and they drift. The slot relation's comment left the list reading to its consumers, and none of the four applied it. A reading four relations need is a fact, so it is stated once, on the relation they all read.
- **Peel on `code_type_element.delivery = 'MANY'` instead of `root_class = 'java.util.List'`.** That would read a `Set<XRecord>` as the record and admit a parameter the emitter cannot fill. The store has to agree with what is emitted, not with what the census calls multiplying.
- **Peel on the parameter alone, ignoring the argument's list-ness.** That mirrors the generator more literally, but it turns `id: ID!` on `List<XRecord>`, refused today with the wrong message, into a pass that fails at runtime with a `ClassCastException`. A refusal with the right message is strictly better.

## Reviewer findings

### Round 1 (2026-10-06, Spec -> Ready, reviewer session 01Gu5aQWsqrmC6MHSXDfMzqv)

Verdict: withhold. One finding, on question two. Question one passes: the goal reads without the plan behind it (a list `@nodeId` argument at a `java.util.List` producer parameter stops being refused, at any arity and for each element kind the emitter supports, and the shapes the emitter cannot fill are refused by name with the right remedy), and the claims it rests on hold against the tree. The design extends the slot relation every reader already reads rather than standing a second one beside it, which is the right shape.

**1. The polymorphic pipeline pin is planned against a fixture this spec itself classifies as `MISMATCH`.** `PolymorphicNodeIdSlotPipelineTest.producerSchema` declares `films(key: ID! @nodeId(typeName: "%s"))`, a single id, and `aProducerListParameterTakesTheListVariant` feeds it to `getOccupantsByUpdatableRecords(java.util.List<org.jooq.UpdatableRecord<?>> key)`. Under the Design's own rule that is a single id at a `MANY` parameter: `slot_shape = 'MISMATCH'`, the case "What this item does not change" names as the one the generator emits wrongly and the store will now refuse. So two things in the spec do not hold as written:

- "What is true today" cites this test as the generator classifying the polymorphic list case fine. What it actually pins is the generator emitting `decodeAddressOccupantRecordList` over a single wire string, the runtime-failure shape. The evidence that the generator decodes a *list argument* at a polymorphic list slot is then the helper's existence, not a test; no execution pin under Tests covers the polymorphic list case either.
- Tests says a store-detection assertion goes "beside `aProducerListParameterTakesTheListVariant`, so the classification test and the store verdict cannot drift apart again". On that fixture the store verdict after this item is `SLOT_SHAPE_MISMATCH` while classification succeeds, which is the stated, accepted asymmetry, so the assertion either pins a refusal (and the "cannot drift apart" rationale inverts) or needs a different schema.

What would satisfy it: say which schema the polymorphic pipeline assertion runs on (presumably a list-argument sibling of `producerSchema`, `key: [ID!]!`, with empty store detection), and say what becomes of the existing single-id fixture: kept as the pin that the generator still classifies a mismatched slot while the store now refuses it (asserting `SLOT_SHAPE_MISMATCH`), or moved to the list argument. Correct the "What is true today" bullet to match.
