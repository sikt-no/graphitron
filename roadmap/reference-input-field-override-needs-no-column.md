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

When this lands the field builds as written, and the generated condition is a correlated `EXISTS` over `DELTAKER` and `PERSON` that calls `personErDodFilterCondition` with the `PERSON` alias and the field's value, and nothing else. Adding `@field(name: "STATUS_DOD")` changes nothing in the generated code. Leaving out `override: true` (with the column named) makes the same `EXISTS` also compare `PERSON.STATUS_DOD` to the value. The comparison and the method then hold on the same `PERSON` row.

Two neighbouring shapes also change:
* A `@nodeId` input field without override that carries its own `@condition` gets the same single `EXISTS`. On a to-many path, the comparison and the method must now hold on the same row. Today each has an `EXISTS` of its own.
* A `@condition` on an element field of a list of input objects, reached over a `@reference` path, is refused at build time rather than left with its method uncalled. The exception is a shape the check under the emitter rule finds working on trunk today.

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

**Store: the conditions a field's filter applies are a stored fact, the sibling of its filter terms.** Three places ask which condition calls apply for a field's rows on a table: the `calls` and `callTargets` multisets in `ConditionQuery.read`, and the `applied` CTE of `graphitron_occurrence_condition_signature_rule`. Each spells it out for itself. Per `docs/architecture/explanation/fact-model.adoc` § "Every relation has a grain; derived facts are stored, and a view stays trivial", a derivation with a second reader gets a relation, and no view reads another view. So the answer is stored once, by the stage that owns the condition calls, and all three read it.

* **The relation:** `graphitron_occurrence_filter_condition`.
  * **Grain:** *a row input whose own `@condition` the field's filter applies, per table the field's rows are on, at the table that row input's path need from there lands on.* A *row input* is an argument or input field read under a field's rows (`graphitron_occurrence_row_input`).
  * **Precedent:** it is the condition sibling of `graphitron_occurrence_filter_term`, which hangs off the same row-input key.
  * **Key:** `(graph_name, coordinate, table_source_name, table_schema, table_name, bound)`. The coordinate is the row input's, the three table columns are the row table, and `bound` is the table the call is applied at, as `graphitron_occurrence_condition` spells it. `bound` is in the key because a node id can land on more than one node table from one row table.
  * **`target_type`:** NOT NULL. It names the need the call is reached over.
  * **Foreign keys,** each `ON DELETE CASCADE`:
    * to `graphitron_occurrence_row_input` on the row input and the row table;
    * to `graphitron_occurrence_condition` on `(graph_name, coordinate, bound)`;
    * to `graphitron_path_need_table` on `(graph_name, coordinate, target_type)`.
  * **What it does not restate:** the join. The steps stay in `graphitron_join_path_step`, read by need as `ConditionQuery`'s `steps` already does.
* **Why a new relation:** no existing relation can take this fact as a column at its grain. `graphitron_occurrence_condition` is keyed per occurrence and applied table, with no row-table axis. `graphitron_path_need_table` is keyed per occurrence and target type, with no applied-table axis. `graphitron_occurrence_filter_term` has no applied table. Several row tables can land on one `bound`, and one row table can reach several node tables.
* **The rule:** `graphitron_occurrence_filter_condition_rule` reads stored relations only. Its predicate is one equality: a row input's call is applied for a row table where the occurrence's need starting on that row table lands on the call's `bound`. The landing is `graphitron_join_path`'s target for a need that writes a path, else the need's own landing.
  * `PathNeeds` gives every row input a need per target type from its start table. For a row input with no path, that need lands on its start, so a call bound at the row's own table and a call bound where a `@reference` path lands are the same rule. Neither needs a disjunct.
  * The node-id case dissolves into this rule. Today's `cb.KIND = 'NODE_ID'` disjunct and the `graphitron_occurrence_node_id` landing match in `callTargets` both become the landing equality.
  * The field's own `@condition` is not a row input. It stays the key equality it already is: `cc.coordinate = rb.coordinate AND cc.bound = rb.bound`.
* **Owner:**
  * **Stage:** a new step of `OccurrenceBindings`, after `OccurrenceConditions`' write and sweep and before `OccurrenceConditionSignatures.derive`. The condition calls are this stage's already.
  * **Mark and sweep:** by `touched_at`, like its neighbours.
  * **Registration:** declared in `meta_relation`, and registered in `RelationRegistrationGateTest` as the rule view's derived arm.
* **The readers:**
  * `graphitron_occurrence_condition_signature_rule`: its `applied` CTE reads the new relation, unioned with the field's own calls.
  * `ConditionQuery.read`: its `calls` multiset drives from the new relation for the row input calls, plus the field's own calls by key. It reads each call's steps by `(coordinate, target_type)`.
  * What goes: the `callTargets` multiset, the `cc.BOUND.eq(rbBound).or(cb.KIND.eq("NODE_ID"))` filter, and the `k.value8().size() == 1` guard that gives a call with several targets no steps. With `target_type` a column of a keyed row, there is always one.
* **Override suppression:** works unchanged. The call is now applied, so the `overriding` filter removes its occurrence's terms.

**Read and emitter: a term and its occurrence's own call over a path share one `EXISTS`, paired by key in the read.**

* **Why one `EXISTS`:** an occurrence that is not overridden contributes both its implicit term and its own call. When it reaches its table over a path, both must hold on the same far-side row. With two `EXISTS` on a to-many path, the term and the method could be satisfied by different rows, and the occurrence states one path, not two.
* **This is new structure.** Today `ConditionEmitter.emit` renders each term in its own `reached(...)` `EXISTS` over its own `hops(...)`, and a node-id call in another over aliases from `fresh(...)`.
* **The pairing is decided in the read, by key:** a term and a call pair where they share the occurrence, the row table and the need (`target_type`), and the need has steps.
  * `ConditionQuery.Term` carries its paired `Call` as an optional component, and a paired call is not repeated in `Row.calls`.
  * Unpaired calls stay in `Row.calls`. These are the field's own calls, calls at the row's own table, and override calls whose term the override removed.
  * The emitter renders a paired term as `reached(steps, hops, and(compare, invoke))`: one set of hops, the method handed the last hop's alias.
* **Node ids follow the same rule.**
  * A node-id term with a paired call is rendered over the need's steps beside its call. The need lands on the node's own table, so the id's key decodes there. It is not decoded onto key columns the row's own table carries (`decodeOnto(...).own()`), which is what that term does today when it has no call.
  * On a to-one path this changes the emitted code and not the rows selected. On a to-many path, a node-id occurrence without an override that carries its own call now selects only rows where the term and the method hold on the same far-side row. That is a deliberate behaviour change, recorded in the docs and pinned by the to-many execution test below. The commit that lands it is a `fix`.
* **A list term** compares with `.in(...)` inside the shared `EXISTS`. Nothing structural changes for it.
* **Presence guard:**
  * A call reached over a path is emitted only where its occurrence carries a value. An omitted field, an explicit `null` and, for a list, an empty list contribute no predicate, and the method is not called. The `EXISTS` is a semi-join, and an absent filter must not drop the rows with no far-side match.
  * A paired call takes its term's guard.
  * An unpaired call over a path is guarded on its own occurrence's value. That is null, plus empty for a list, which today's node-id arm (`!= null` only) lacks. A value declared non-null on every step of its path needs no null check.
  * A call at the row's own table keeps today's behaviour: it is called whatever the value. The method sees the null, and existing methods rely on that.
* **A call on an element of a list of input objects, reached over a path,** is refused at build time with a new store defect, `LIST_ELEMENT_CONDITION_OVER_PATH_UNSUPPORTED`. It has its `defects/` page and a near-miss fact document, per `docs/architecture/explanation/defects-as-facts.adoc` § "Adding a defect".
  * `list(...)` renders an element's comparisons as row values, and an element's own method over a path would need an `EXISTS` per element, which this item does not build.
  * **Check first** whether any such shape, node id or not, builds and selects correctly on trunk today: a fact document or the sakila example. If one does, the defect is narrowed to what does not work, since a defect on a working schema is a regression.

**Walk: stop refusing, add nothing.** The walk only admits now; it renders no condition code, and its carriers are a surface being drained (`docs/architecture/explanation/pipeline-overview.adoc` § "Classification gathers").

* `BuildContext.classifyInputField`, `@reference` arm: on a column miss, read the field's own `@condition` as the plain arm below it already does. With `override: true` return `InputField.ConditionOwnedField(cond, Optional.empty())`; otherwise keep today's `Rejection.unknownColumn` with today's message. The carrier carries no path; the store holds it.
* `FieldBuilder`'s argument arm already returns `ConditionOwnedArg` here and needs no change.
* Before relying on the carriers being inert, establish that no walk-side consumer still renders a condition from `ConditionOwnedField` or `ConditionOwnedArg` for a reference-path occurrence (`TypeFetcherGenerator`, `ParentSourceBinding`, the plan's `ConditionCommands`). If one does, it hands the method the row's table, and the item routes it to the store's rendering rather than teaching the walk the path.

**Not in this item.** The walk's `FkTargetConditionFilter` wrap and the `@reference` + override + column-hit case still minting `ColumnBackedReferenceField` are left alone. Neither affects emitted code once the store applies the call, and retiring them belongs to the walk's drain.

## Tests

* **Store scenario.** Extend `graphitron-model/src/test/resources/facts/scenarios/condition-filter.graphqls` with the five shapes above, plus a two-hop path (`customer` → `address` → `city`) with no column under override (the sis shape), and a to-many path from `customer` (a reverse key, for example into `rental`) both with and without override. Claim the `graphitron_occurrence_filter_condition` rows: for each occurrence, the row table, the `bound` it is applied at and the `target_type` it is reached over. That pins the pairing key at the pipeline tier. Claim too that a plain argument's `@condition` with no path has its row there, applied at the row's own table.
* **Near miss.** `facts/defects/filter-column-unresolved.graphqls` gains an input field with the path, its own `@condition` without override and no column; it still draws `COLUMN_UNRESOLVED`. A walk test (`GraphitronSchemaBuilderTest`, beside `plainInput_overrideFalseWithoutMatchingColumn_stillFailsTheBuild`) pins today's "no column … reachable via @reference path" message for the same shape. A second near-miss document holds a list of input objects whose element field carries a `@reference` path and its own `@condition`, and draws `LIST_ELEMENT_CONDITION_OVER_PATH_UNSUPPORTED`. Its passing sibling is the same element field without the path.
* **Walk admission.** `GraphitronSchemaBuilderTest`, beside `plainInput_overrideTrueWithoutMatchingColumn_classifiesAsConditionOwnedField`: the `@reference` input field with no column under its own override classifies as `ConditionOwnedField`.
* **Emitted code runs.** `ConditionEmitterExecutionTest` (it reads the `condition-filter` scenario) compiles the emitted conditions and selects through them against PostgreSQL. Each shape is checked with a value, with `null` and with the field omitted. With a value, the customers selected are those whose address (or city) the method accepts. With `null` or omitted, every customer is selected. The non-override shape selects the customers that satisfy both the term and the method on the same address. The to-many shape is the one that tells one `EXISTS` from two, since on the to-one paths above both select the same rows. Seed data where one far-side row satisfies the term and another satisfies the method, and assert that the customer is *not* selected. Add a node-id occurrence without override, with its own call, on a to-many path, with the same check. New fixture methods in `InputFieldConditionFixtures` (`graphitron-sakila-service`) cover a typed `City` parameter for the two-hop case, and a `Table<?>` parameter whose predicate only holds on the landing table, so a method handed the row's table fails the test rather than compiling and passing.
* **Existing node-id coverage.** `NodeIdOverrideConditionFkTargetPipelineTest` and the sakila example's `ConditionExecutionTest` stay green in what they select. Where one of them pins the emitted code of a node-id term with its own call, its expectation changes to the single `EXISTS`, and that change is reviewed as the deliberate one named under the emitter rule above, not regenerated in bulk.
* **Store relation hygiene.** The new relation passes the store's existing gates for a stored relation (registration, grain sentence, owner), with no new gate added.

## Documentation

* `docs/manual/reference/directives/condition.adoc`: generalise the `@nodeId(typeName:)` bullet ("binds the condition method's table parameter to the FK-*target* table") to any argument or input field with a `@reference` path: the method's table parameter is the table the path reaches, the predicate is a correlated `EXISTS` over the path, and the presence rule applies. Extend the argument-level `override: true` bullet, which says the argument then needs no column, to input fields and to `@reference` paths, the path still choosing the table. State the one-`EXISTS` rule: an input field's implicit comparison and its own method, reached over one path, must hold on the same row the path reaches. Say on the `@nodeId` bullet that this now holds for node ids too. Say that a method on an element of a list of input objects cannot be reached over a path.
* `docs/manual/how-to/condition-cascade.adoc` (two places) and `docs/architecture/reference/argument-resolution.adoc` say a field that resolves on no column of a consumer's table fails the build. Each gains the exemption: unless the field's own `@condition(override: true)` owns its predicate.
* `docs/manual/reference/directives/reference.adoc`: check its statement of what a path on an argument or input field does and align it with the above.

## Neighbours

* R1139 admits an argument that matches no column under a *field-level* override, and R1205 drops the route demand for an unrouted `@nodeId` under a field-level override. Both read "an override at or above the occurrence owes no column or route" from `graphitron_occurrence_overridden`, as `COLUMN_UNRESOLVED` already does. That half is shared, and the store side of it already holds for this item's shape. This item adds the other half, which they lack: where the leaf's own method is applied when the leaf has a path. The items stay separate.
* R1092 is the non-override sibling: an input field's `@condition` without override drops the implicit predicate it demands a column for. After this item, the `@reference` case of R1092 is covered by the one-`EXISTS` rule above; R1092's non-path case is untouched.

## Other solutions we've considered

* **Mint `ColumnBackedReferenceField` without a column** (the reporter's suggestion). Its compact constructor requires a column, and it is also a lookup key and a write column, so a column-less instance breaks invariants other consumers rely on. It also fixes nothing on trunk, where the walk renders no condition.
* **Give `ConditionOwnedField` and `ConditionOwnedArg` a join path** so the walk carries the path to its filter assembly. This extends two retiring carriers with a fact the store already holds for each occurrence, and, the walk no longer rendering conditions, delivers nothing.
* **Re-spell the applied-call predicate in each reader**, generalised alike in `ConditionQuery` and the signature rule and pinned together by a test. This is the two-spellings shape `fact-model.adoc` § "Every relation has a grain; derived facts are stored, and a view stays trivial" names: a test notices drift only after it happens.
* **A shared view both readers read.** The signature rule would then read a view, and no view reads another view (same section).
* **Pair a term with its call in the emitter**, grouping by coordinate and comparing steps. That decides in the emitter something the store states by key.
* **Keep node ids on two `EXISTS`.** That would keep a node-id special case in the read or the emitter, which is the special case this item removes. Separately, the to-many semantics of two `EXISTS` is the defect this item fixes for every other binding.
* **Split the column-hit regression into its own item.** It has the same root cause and the same fix site as the column-miss case, and fixing the read for one fixes it for the other. One item, with the column-hit shapes in its tests, is the honest scope.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01FY5UvsCPZH787jJjRijbDG, 2026-11-27)

Question 1 passes. The goal reads on its own: a `@condition` on an argument or input field that carries a `@reference` path gets its method called on the table the path reaches, inside an `EXISTS` over that path. Under `override: true` the field needs no column. Without the override, the implicit comparison and the call share one `EXISTS`. Today the method is not called on trunk, and an argument with no column builds with no filter. The outcome is reachable. Every class, method, relation, test, fixture, doc page and section the spec names exists under that name. The read-side diagnosis matches the code: the `calls` filter `cc.BOUND.eq(rbBound).or(cb.KIND.eq("NODE_ID"))` and `callTargets`' `cb.KIND.eq("NODE_ID")` in `ConditionQuery.read`, the `onNode` gate in `ConditionEmitter.emit`, the same predicate in the `applied` CTE of `graphitron_occurrence_condition_signature_rule`, and the `@reference` arm's `Rejection.unknownColumn` in `BuildContext.classifyInputFieldInternal`.

Question 2 fails on two points. Each is a decision the implementer would have to make mid-implementation, and the spec either states it in a form that cannot be followed or attributes it to existing code that does not have it.

1. **"The predicate is stated once" cannot be followed as written. The fallback it names does not exist, and the other ways out contradict "No new relation is added" (question 2).** The spec says that where the SQL view and the Java read cannot share a statement, the view is the owner and `ConditionQuery` joins it. They cannot share one. The view's output, and the stored `graphitron_occurrence_condition_signature` that `ConditionQuery.read` already joins, are keyed per field occurrence and row table (`graph_name, coordinate, bound`). The `applied` set is per call, and it exists only as a CTE inside the view. So `ConditionQuery` has nothing per call to join to choose its calls or their `callTargets`. That leaves three ways forward, and the spec rules out or leaves out each one:
   - Store the applied-call fact as a relation. The spec's own grain sentence ("one row per filter occurrence and condition call, applied at the table the occurrence's path from the row's table lands on") describes that relation, and it owes an owner, a mark and a sweep. "No new relation is added" rules this out, and so does the architect's advice the author adopted.
   - Lift the CTE into a view that both readers read. Under `fact-model.adoc` § "Every relation has a grain; derived facts are stored, and a view stays trivial", that view would itself need justifying.
   - Generalise the predicate separately in Java and SQL, which is what the tree does today. This contradicts "stated once".

   To satisfy this, pick one, say why, and drop the conditional. If you pick the relation, add its grain, owner and stage to the Implementation section. If you pick two statements, say that the predicate stays stated twice, and name the test that pins the two together.

   *Author response:* Option A, a stored relation, chosen on the architect's advice and on `fact-model.adoc` § "Every relation has a grain; derived facts are stored, and a view stays trivial". That section says a derivation with a second reader gets a relation, and no view reads another view, which rules out the shared view.
   - The relation is `graphitron_occurrence_filter_condition`, the condition sibling of `graphitron_occurrence_filter_term`, keyed on the row input, the row table and `bound`.
   - The Implementation section now gives its grain, foreign keys, NOT NULL `target_type`, rule, owning stage step, and the one sentence on why no existing relation can take it as a column.
   - The `NODE_ID` disjunct disappears into one landing equality. That holds because `PathNeeds` gives every row input a need from its start table.
   - Both readers join the relation; `callTargets` and the single-target guard go.
   - "No new relation is added" is gone. The architect's earlier "no new relation" advice assumed the fact was joinable from what is already stored, which this finding showed it is not.

2. **"One `EXISTS` per occurrence" does not carry over from the node-id arm. It is new emitter structure, and the plan does not say where the pairing is decided (question 2, with a factual correction under question 1).** In `ConditionEmitter.emit` the terms and the calls are rendered in separate loops. Each term builds its own `reached(...)` `EXISTS` over its own `hops(...)` aliases. A node-id call builds another `EXISTS` over aliases from `fresh(...)`. So a node-id occurrence with a term and its own call gets two `EXISTS` today. That is the to-many hazard the spec describes, already present in the arm it calls the model. To make term and call share one `EXISTS`, something must pair a term with its occurrence's call: the emitter grouping by coordinate, or the read nesting the call on its term. That pairing does not exist yet.

   To satisfy this:
   - State the rule as new, and say which side pairs them.
   - Say whether the rule also applies to node-id occurrences that are not overridden. If it does, their emitted shape changes, and "existing node-id coverage stays green" then needs a named check rather than following automatically.
   - Say how it meets the list arms: a list term compares with `.in(...)`, and list elements go through `list(...)`.
   
   The presence guard also carries over only in part. The node-id call arm guards `!= null` and nothing else. The empty-list half the spec requires, which `condition.adoc` already documents for node ids, is not in that arm, so the Tests section's checks with `null` and omitted values should include an empty list wherever a list shape is covered.

   *Author response:* The rule is now stated as new structure, and the pairing is decided in the read, by key: a term and a call pair where they share the occurrence, the row table and the need, and the need has steps. `Term` carries its paired `Call`, and the emitter renders both in one `reached(...)`.
   - **Node ids:** merged under the same rule, named as a deliberate behaviour change. It appears in the Goal and in `condition.adoc`, and is pinned by a to-many execution test that the existing to-one tests could not provide. The "stays green" claim now covers what the existing tests select, not the code they pin.
   - **List terms:** a list term is another comparison inside the shared `EXISTS`.
   - **List elements:** a call on a list element reached over a path is refused with a new `LIST_ELEMENT_CONDITION_OVER_PATH_UNSUPPORTED`. That comes after a check that no such shape works on trunk today.
   - **Presence guard:** now spelled out per case, including the empty-list half, and the Tests section covers it.
   - **Open for Alf or Filip:** no `docs/architecture/` page says whether the old two-`EXISTS` node-id semantics was intended. The architect flagged that as theirs to rule on, and the spec takes the merge.

The store-capture claims ("for all five, `graphitron_occurrence_condition` holds the call at `bound = public.address`") rest on the author's probe. This review did not rerun it. What the review checked is that `OccurrenceConditions` writes a condition for each table its occurrence binds, which is consistent with the probe.
