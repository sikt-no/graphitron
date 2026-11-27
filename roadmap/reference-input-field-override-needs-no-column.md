---
id: R1213
title: "A @condition on an argument or input field with a @reference path is never called, and under override it still demands a column"
status: Spec
bucket: bug
priority: 3
theme: classification-model
depends-on: []
created: 2026-11-27
last-updated: 2026-11-27
---

# A @condition on an argument or input field with a @reference path is never called, and under override it still demands a column

## Goal

An argument or input field that carries a `@reference` path and its own `@condition` has its method called on the table the path reaches, inside a correlated `EXISTS` that ties that table to the row through the path. With `override: true` it needs no column. Today, on trunk, the method is not called at all, whether or not a column is named; under `override: true` the implicit `column = value` comparison the override should suppress is generated in its place. An input field of this shape that names no column is refused at build time, and an argument of this shape that names no column builds with no filter at all. A column miss without the override stays refused as today.

The shape, from the sis port (an input consumed by `Query.evuKursSoknader`, whose table is `KURSSOKNAD`):

```graphql
input QueryEvuKurssoknaderFilterInput {
  personenErDod: Boolean @reference(path: [{table: "DELTAKER"}, {table: "PERSON"}])
    @condition(condition: {className: "no.fellesstudentsystem.kjerneapi_service.conditions.PersonConditions",
                           method: "personErDodFilterCondition"}, override: true)
}
```

```java
public static Condition personErDodFilterCondition(Person person, Boolean personenErDod)
```

When this lands the field builds as written, and the generated condition is a correlated `EXISTS` over `DELTAKER` and `PERSON` that calls `personErDodFilterCondition` with the `PERSON` alias and the field's value, and nothing else. Adding `@field(name: "STATUS_DOD")` changes nothing in the generated code. Leaving out `override: true` makes the same `EXISTS` also compare `PERSON.STATUS_DOD` to the value, inside the same `EXISTS`.

## Reported by the sis port, on RC58 (`f6e16dd53`)

Relayed by the sis porting session on 2026-11-27, verified there by a build:

* The build refuses the field: `Input field 'QueryEvuKurssoknaderFilterInput.personenErDod': no column 'personenErDod' reachable via @reference path; did you mean: PERSONNR, PERSON_ALDER, STATUS_DOD, PLNR, KJONN`.
* Naming any column of `PERSON` through `@field(name:)` passes, and on RC58 the generated code is the same whichever column is named: the method is called on the `PERSON` alias and no named column is read.
* sis carries 101 such placeholder declarations across 8 distinct fields, which mislead: `Boolean` fields name `String` columns (`EMAILADRESSE`, `UTDREGID_SPESIFIKASJON`), `undervisningsenhetErApen` names `STATUS_LUKKET`, and `personErDodFilterCondition` actually reads `STATUS_DOD` on another table.

RC58 predates `f34cef0`, after which the filter conditions are emitted from the *store* (the fact database a build captures the schema into) rather than from the *walk* (the older classifier that turns each schema field into a model carrier). The placeholder workaround that works on RC58 does not work on trunk; see the next section. A sis upgrade past `f34cef0` would silently replace each of the 101 method calls with a comparison against the placeholder column.

## What trunk does (probed at `f0caaec`)

A throwaway pipeline probe captured five shapes on sakila into the store, then read them with `ConditionQuery.read` and rendered them with `ConditionEmitter.emit`, the pair `ConditionUnitRenderer` drives in a real build. Each shape is a field on `Query` returning `[Customer!]!` (table `customer`), with `@reference(path: [{key: "customer_address_id_fkey"}])` and `@condition` calling `InputFieldConditionFixtures.addressDistrictAlberta(Address, Integer)`. The store raised no defect for any of them.

| Shape | Walk | Store-emitted condition |
|---|---|---|
| input field, `@field(name: "address_id")`, override | admitted (`ColumnBackedReferenceField`) | `EXISTS(address … address_id = value)`; the method is not called |
| input field, same, no override | admitted | the same; the method is not called |
| argument, `@field(name: "address_id")`, override | admitted (`ColumnBackedArg`) | the same; the method is not called |
| input field, no column, override | refused, "no column … reachable via @reference path" | no condition at all |
| argument, no column, override | admitted (`ConditionOwnedArg`, no path) | no condition at all: the field is unfiltered |

The store's capture is already right. For all five, `graphitron_occurrence_condition` holds the call at `bound = public.address`, the table the path lands on, with `override_written` set where written. The occurrences without a column carry a `TABLE` binding at `public.address`, and the four overrides are in `graphitron_occurrence_overridden`. The loss is in the read:

* `ConditionQuery.read` keeps a call only where `cc.BOUND.eq(rbBound).or(cb.KIND.eq("NODE_ID"))`: bound at the row's own table, or a node id's. It reads a call's join steps (`callTargets`) only for `NODE_ID` calls. A call bound where a `@reference` path lands is neither, so it is dropped.
* The term suppression an override owes is driven by the applied calls (the `overriding` filter in `ConditionQuery.read`), so a dropped call also leaves its occurrence's implicit term standing.
* `ConditionEmitter.emit` reaches over a call's steps only when `onNode`; every other call is handed `table`.
* `graphitron_occurrence_condition_signature_rule` evaluates the same `cc.bound = rb.bound OR cb.kind = 'NODE_ID'` predicate to decide `takes_context` and `takes_session`.

The node-id arm is already the rule this item needs ("a call is applied at the table its occurrence's path lands on, reached over that path"), limited to one binding kind. The fix lifts the limit.

## Implementation

**Store read side: a call is applied where its occurrence's path lands, whatever binds it.** Its grain sentence: *one row per filter occurrence and condition call, applied at the table the occurrence's path from the row's table lands on.* That fact is already stored, keyed by the occurrence (`graphitron_path_need_table`, `graphitron_join_path`), as the ruling in `docs/architecture/explanation/fact-model.adoc` § "Every relation has a grain" requires: a condition on an argument or input field reaches its table along the path its occurrence already states. No new relation is added.

* `ConditionQuery.read`: select a call where it is bound at the row's table, or where it is bound at the table the occurrence's path need from the row's table lands on, for any binding kind (`COLUMN`, `TABLE`, `NODE_ID`). `callTargets` reads that path's steps under the same rule, rather than under `cb.KIND = 'NODE_ID'`. The node-id special case dissolves into the general rule; its additional `graphitron_occurrence_node_id` match on the landing table stays where it is needed to pick between a node id's routes.
* `graphitron_occurrence_condition_signature_rule`: the same predicate, generalised the same way, so a call applied over a path still reports whether it takes the context or the session. The predicate is stated once. Where the SQL view and the Java read cannot share a statement, the view is the owner and `ConditionQuery` joins it rather than evaluating the predicate again, per `docs/architecture/principles/development-principles.adoc` § "Decide once, at capture".
* `ConditionEmitter.emit`: reach over a call's steps whenever it has any, not only `onNode`, and hand the method the last hop's alias. Two rules carry over from the node-id arm and become general:
  * *Presence guard.* A call applied over a path is emitted only where its occurrence carries a value; an omitted field, an explicit `null` and an empty list contribute no predicate, and the method is not called. The `EXISTS` is a semi-join, and an absent filter must not drop the rows with no far-side match.
  * *One `EXISTS` per occurrence.* An occurrence's implicit term (when not overridden) and its own call share one `EXISTS` over one set of hops, under the occurrence's aliases. With two separate `EXISTS` on a to-many path, the term and the method could be satisfied by different far-side rows, and the occurrence states one path, not two.
* Override suppression then works unchanged: the call is applied, so `overriding` removes its occurrence's term.

**Walk: stop refusing, add nothing.** The walk only admits now; it renders no condition code, and its carriers are a surface being drained (`docs/architecture/explanation/pipeline-overview.adoc` § "Classification gathers").

* `BuildContext.classifyInputField`, `@reference` arm: on a column miss, read the field's own `@condition` as the plain arm below it already does. With `override: true` return `InputField.ConditionOwnedField(cond, Optional.empty())`; otherwise keep today's `Rejection.unknownColumn` with today's message. The carrier carries no path; the store holds it.
* `FieldBuilder`'s argument arm already returns `ConditionOwnedArg` here and needs no change.
* Before relying on the carriers being inert, establish that no walk-side consumer still renders a condition from `ConditionOwnedField` or `ConditionOwnedArg` for a reference-path occurrence (`TypeFetcherGenerator`, `ParentSourceBinding`, the plan's `ConditionCommands`). If one does, it hands the method the row's table, and the item routes it to the store's rendering rather than teaching the walk the path.

**Not in this item.** The walk's `FkTargetConditionFilter` wrap and the `@reference` + override + column-hit case still minting `ColumnBackedReferenceField` are left alone. Neither affects emitted code once the store applies the call, and retiring them belongs to the walk's drain.

## Tests

* **Store scenario.** Extend `graphitron-model/src/test/resources/facts/scenarios/condition-filter.graphqls` with the five shapes above, plus a two-hop path (`customer` → `address` → `city`) with no column under override, the sis shape. Claim that each occurrence's call is applied at its landing table. That is the fact the read now honours.
* **Near miss.** `facts/defects/filter-column-unresolved.graphqls` gains an input field with the path, its own `@condition` without override and no column; it still draws `COLUMN_UNRESOLVED`. A walk test (`GraphitronSchemaBuilderTest`, beside `plainInput_overrideFalseWithoutMatchingColumn_stillFailsTheBuild`) pins today's "no column … reachable via @reference path" message for the same shape.
* **Walk admission.** `GraphitronSchemaBuilderTest`, beside `plainInput_overrideTrueWithoutMatchingColumn_classifiesAsConditionOwnedField`: the `@reference` input field with no column under its own override classifies as `ConditionOwnedField`.
* **Emitted code runs.** `ConditionEmitterExecutionTest` (it reads the `condition-filter` scenario) compiles the emitted conditions and selects through them against PostgreSQL. Each shape is checked with a value, with `null` and with the field omitted. With a value, the customers selected are those whose address (or city) the method accepts. With `null` or omitted, every customer is selected. The non-override shape selects the customers that satisfy both the term and the method on the same address. New fixture methods in `InputFieldConditionFixtures` (`graphitron-sakila-service`) cover a typed `City` parameter for the two-hop case, and a `Table<?>` parameter whose predicate only holds on the landing table, so a method handed the row's table fails the test rather than compiling and passing.
* **Existing node-id coverage stays green**, `NodeIdOverrideConditionFkTargetPipelineTest` and the sakila example's `ConditionExecutionTest` among it, since the node-id arm becomes one case of the general rule.

## Documentation

* `docs/manual/reference/directives/condition.adoc`: generalise the `@nodeId(typeName:)` bullet ("binds the condition method's table parameter to the FK-*target* table") to any argument or input field with a `@reference` path: the method's table parameter is the table the path reaches, the predicate is a correlated `EXISTS` over the path, and the presence rule applies. Extend the argument-level `override: true` bullet, which says the argument then needs no column, to input fields and to `@reference` paths, the path still choosing the table.
* `docs/manual/how-to/condition-cascade.adoc` (two places) and `docs/architecture/reference/argument-resolution.adoc` say a field that resolves on no column of a consumer's table fails the build. Each gains the exemption: unless the field's own `@condition(override: true)` owns its predicate.
* `docs/manual/reference/directives/reference.adoc`: check its statement of what a path on an argument or input field does and align it with the above.

## Neighbours

* R1139 admits an argument that matches no column under a *field-level* override, and R1205 drops the route demand for an unrouted `@nodeId` under a field-level override. Both read "an override at or above the occurrence owes no column or route" from `graphitron_occurrence_overridden`, as `COLUMN_UNRESOLVED` already does. That half is shared, and the store side of it already holds for this item's shape. This item adds the other half, which they lack: where the leaf's own method is applied when the leaf has a path. The items stay separate.
* R1092 is the non-override sibling: an input field's `@condition` without override drops the implicit predicate it demands a column for. After this item, the `@reference` case of R1092 is covered by the one-`EXISTS` rule above; R1092's non-path case is untouched.

## Other solutions we've considered

* **Mint `ColumnBackedReferenceField` without a column** (the reporter's suggestion). Its compact constructor requires a column, and it is also a lookup key and a write column, so a column-less instance breaks invariants other consumers rely on. It also fixes nothing on trunk, where the walk renders no condition.
* **Give `ConditionOwnedField` and `ConditionOwnedArg` a join path** so the walk carries the path to its filter assembly. This extends two retiring carriers with a fact the store already holds for each occurrence, and, the walk no longer rendering conditions, delivers nothing.
* **Split the column-hit regression into its own item.** It has the same root cause and the same fix site as the column-miss case, and fixing the read for one fixes it for the other. One item, with the column-hit shapes in its tests, is the honest scope.
