---
id: R1202
title: "A list of node ids' key column reaches a List parameter at a @condition through argMapping"
status: Spec
bucket: bug
priority: 6
theme: nodeid
depends-on: []
created: 2026-11-20
last-updated: 2026-11-20
---

# A list of node ids' key column reaches a List parameter at a @condition through argMapping

## Goal

A consumer can hand a list of node ids to a field-level `@condition` through `argMapping` and have the method receive the decoded key column values as a `List`: the entry names the column past the slot, `argMapping: "samlinger: samlingIder.samling_id"`, and the parameter is a `List` of that column's Java type. Every refusal met on the way names that spelling. Today no `argMapping` spelling of the binding builds: the bare entry is told to declare the column's own type, following that draws a refusal asking for the `List` again, and the entry naming the column is refused as "a list of input objects". The same parameter builds when it is named after the argument and no `argMapping` is written, which the messages give a consumer no way to discover and which is a contract that is being withdrawn. Reported in [GitHub issue 583](https://github.com/sikt-no/graphitron/issues/583), where the consumer dropped `@nodeId` and decodes by hand inside the condition, losing the client error a bad id gets at every other `@nodeId` site.

## What happens today

The shape is a root field with a list of node ids and a single node id, read together by one condition because the predicate is an `EXISTS` that needs both:

```graphql
films(
    languageIds: [ID!]! @nodeId(typeName: "LanguageNode") @reference(path: [{key: "film_language_id_fkey"}])
    originalId: ID! @nodeId(typeName: "LanguageNode") @reference(path: [{key: "film_original_language_id_fkey"}])
): [Film!]! @condition(condition: {className: "...", method: "...", argMapping: "..."})
```

Three vocabulary items first. A *whole-slot binding* is a parameter bound to the whole of a `@nodeId` argument or input field, by its name or by an `argMapping` entry that stops on it (`"p: languageIds"`, called *bare* below). A *projection* is an entry that hands over one key column, spelled by naming the column past the slot (`"p: languageIds.language_id"`, *dotted*) or by descending through an input object onto a `@nodeId` input field. The *walk* is the classifier that still emits condition glue; the *store* is the fact store that is replacing it.

Probed through the build's validate pass on trunk at `a66d14f`, against the sakila catalog, with a condition method on the classpath for each row. Only validation was run; no generated query was executed.

| # | Site and key | Spelling | Parameter | Outcome |
|---|---|---|---|---|
| 1 | Any of the three condition sites, list, one-column key | by name | `List<Integer>` | Builds. |
| 2 | The same | bare `argMapping` | `List<Integer>` | Refused: "whose key column 'language_id' jOOQ binds as Integer, but the parameter it binds to takes List; bind a parameter of the column's own type". |
| 3 | Field-level, list, one-column key | bare `argMapping` | `Integer` | Refused twice: "Declare the parameter as 'java.util.List<java.lang.Integer>'", and "opens a list of node ids ... which parameter binding does not emit yet". |
| 4 | Field-level, list, one-column key | dotted `argMapping` | `List<Integer>` | Refused twice: the type message of the second row, and "descends into 'languageIds', a list of input objects with no single value below it". |
| 5 | Field-level, single id, composite key | by name | `Row2<Integer, Integer>` | Builds. |
| 6 | The same | bare `argMapping` | `Row2<Integer, Integer>` | Refused: "whose key is 2 columns, and one binding carries one value; open it with one of them". |
| 7 | Input-field `@condition`, single id, one-column key | by name | `Integer` | Builds. |
| 8 | The same | bare `argMapping` | `Integer` | Refused: "resolves a key column of 'LanguageNode', which no emitter reads at this site yet". |
| 9 | Field-level or argument-level, single id, one-column key | bare `argMapping` | `Integer` | Builds. |

So the reported list case is one face of a wider disagreement: a bare `argMapping` entry onto a `@nodeId` slot at a `@condition` is judged differently from the same binding made by name, at a list, at a composite key and at the input-field site. The last row builds only because the two judgments happen to agree there.

A separate fact the probe turned up, which bounds any workaround: a `@nodeId(typeName:)` argument whose node table is not reachable from the field's table by one foreign key or a written `@reference` path is refused in either spelling, with or without `override: true` ("no foreign key found between tables", or `NODE_ID_LANDS_ON_TABLE`). With a multi-hop `@reference` path written on each argument the by-name spelling builds, with and without `override: true`.

## Who judges the binding, and what each says

Three readers judge the one binding, and they do not agree.

* **The walk's whole-slot rule.** `ConditionResolver.installNodeIdDecode` hands a whole-slot parameter the decoded key: the key column's own type at a one-column key, a jOOQ `RowN` at a composite key, either wrapped in `List` for a list slot. It applies to a bare `argMapping` entry and to a parameter named after the slot alike, and `ProjectedKeyReads.installRailOwns` lets it win at render. `docs/manual/reference/directives/condition.adoc` documents this as the contract.
* **The retiring projection defects.** `ArgmappingProjectionDefects` reads `intent_argmapping_projection_defect` and `intent_resolved_node_key_projection`, views of the `intent_` family that is being dissolved. Those infer a one-column projection for every entry that names no column, bare ones included, and compare the column's type with the parameter's erased class, so `List<Integer>` reads as `List`. Every refusal in the table but the "list of input objects" one comes from here. The by-name spelling writes no entry, so these views have nothing to read, which is the whole reason it builds.
* **The store's occurrence family.** `graphitron_argmapping_landing.receives` already states what an entry hands over: `WHOLE` for a bare entry, `COLUMN` for a dotted one or one descending onto the slot. The probe's rows show it: the bare list entry lands `WHOLE` on a slot with `list_depth = 1`, `slot_shape = LIST`, `landing_class = java.lang.Integer`, identical to the by-name spelling's slot row; the dotted entry lands `COLUMN`. The store's own type judgment for a projected column, `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT`, covers `@condition` as well as `@service`, is list-aware, and raised nothing against the dotted `List<Integer>` row above.

The store is where this is going, and its rows already carry the ruling below: `graphitron_occurrence_node_id_shape` holds no row for the by-name `List<Integer>` and `Integer` parameters above, no decode shape fitting a whole key on a parameter of the column's own type, and a slot with no shape is what the store refuses at a `@service`.

## The ruling

`docs/architecture/explanation/defects-as-facts.adoc` states it: a parameter bound to the whole of a `@nodeId` slot takes the node's record or a tuple of its key, at a `@condition` as at a `@service` (Alf, 2026-11-14; Filip, 2026-11-20), and a key column's value is a projection asked for by naming the column, a `List` of it off a list of ids. The refusal of a whole-slot parameter of the column's own type ships when the walk no longer renders conditions; R876 holds it on branch `wip/r876-condition-arity-at-flip`, because until then the walk defers an input-field `@condition` projecting its own key column and refusing earlier leaves that site no shape that generates.

So the spelling this item makes build is the dotted one, and it adds nothing to the whole-slot contract the walk still serves: no bare or by-name binding that is refused today starts building, and none that builds today is refused here.

## Plan

1. **The dotted list projection is emitted on the live condition glue.** Condition glue is still the walk's (`ConditionGlueRenderer` with `ProjectedKeyReads`); the store's `ConditionEmitter` has no caller in main sources until R876's condition flip, which moves every caller at once and keeps no per-occurrence fallback, so a partial flip is not available. `ProjectedKeyReads` gains one read shape: each id of the list decoded into the node's record and the named column read off each, the list absent or `null` reaching the method as `null` as a single projected id does.
   The rows that read needs come from the store's own projection population, `graphitron_occurrence_node_id_slot_projection` with its slot's `list_depth`, not from `intent_resolved_node_key_projection`: that view drops the pair today (its type predicate is the list-blind one), and it is dissolving, so it takes no list axis and `KeyProjection` gains no component sourced from its `leaf_is_list`. Whether `ResolvedKeyProjections` moves onto the stored population for every condition entry or only gains the list rows from it is the implementer's call inside that constraint; the first advances R876's node "The argMapping readers read the landing, and the candidate and match go".
   The read is walk-tier code added from a stored fact, which `docs/architecture/explanation/pipeline-overview.adoc` allows on the terms that it is deleted when its tier flips. It is named here and is to be named in R876's node "The walk's read path is gone" in the implementing commit.
2. **The refusals standing in front of it go.**
   * `ArgmappingProjectionDefects.listDeferred` no longer defers at the field-level and argument-level condition sites. The input-field site stays as it is, unwired on the walk, and closes at the flip.
   * The `intent_` verdicts stand aside at a condition site where the entry's landing `receives = 'COLUMN'`, as `authorDefects` already does for the type verdict at `@service`: the store judges those entries (`NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT`, list-aware and already covering `@condition`; `ARGMAPPING_KEY_COLUMN_UNRESOLVED` for an unknown column or a composite key with none named). The stand-aside is keyed on the landing, not on the site alone, so a bare entry is still judged where it is today. Before a duplicate goes, the stored defect's message is checked to carry the help the removed one gave; `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT` already says "Declare it List<Integer>".
   * `FieldBuilder.conditionReadThroughList` no longer fires on a path that opens a list of node ids with a key column; a list of input objects with a descent below it is still refused.
3. **A bare entry's type refusal names the projection.** `KEY_COLUMN_TYPE_MISMATCH` on an entry that names no column today ends "bind a parameter of the column's own type", which under the ruling is the wrong remedy at any cardinality and is what makes the list case circular. Its remedy becomes naming the key column past the slot, quoting the spelling (`languageIds.language_id`). One clause of an existing message in `ArgmappingProjectionDefects.rejectionOf`, on the branch it already has for an entry with no trailing name; no new verdict, and no new refusal.
4. **Docs.** `docs/manual/reference/directives/condition.adoc`: the projection bullet gains the list form and says it is the spelling for a key column's value; the whole-slot bullet says a parameter of the column's own type still builds there and is going, with the record or tuple as what stays. `docs/manual/reference/directives/routine.adoc`'s projection section is checked for a statement that a list is not projected. `diagnostics-glossary.adoc` moves with the message step 3 changes.

## What changes per row of the table

| Row | After this item |
|---|---|
| 1, by name, list | Unchanged; builds until the condition flip refuses it. |
| 2, bare, `List<Integer>` | Still refused; the message names `languageIds.language_id`. |
| 3, bare, `Integer` | Refused once, by the whole-slot rule's "declare it `List<Integer>`"; the deferral no longer fires. Following it reaches row 2, whose remedy builds. |
| 4, dotted, `List<Integer>` | Builds. The deliverable. |
| 5 and 6, composite key | Unchanged. The bare refusal already names the key columns. |
| 7 and 8, input-field site | Unchanged; closes at the flip. |
| 9, bare, single id | Unchanged; builds until the flip. |

## Tests

* **Store rows.** `graphitron-model/src/test/resources/facts/scenarios/argmapping-key-column.graphqls` is the keystone: it pins `receives` and the landing key column for a by-name, a named and a descending entry at a `@condition`. It gains a list slot opened with its key column, claiming the `COLUMN` landing, the key column, and the slot's `list_depth` and decode shape.
* **Refusals.** `facts/defects/argmapping-projection-defect.graphqls` loses the claims for verdicts that now stand aside at a condition's `COLUMN` landing, and the stored counterpart of each is pinned by the document that owns that code. The bare list entry's refusal is asserted with its new remedy at the pipeline tier, where a catalog and a classpath are both present.
* **Emission, both emitters.** The reported shape, a list of ids and a single id read by one field-level condition through dotted entries, goes into a `graphitron-sakila-example` schema with a condition method in `graphitron-sakila-service`. An execution test runs the query against PostgreSQL, including a malformed id inside the list returning the usual client error and an omitted list reaching the method as `null`. `EmissionCensusTest.theCensusOfTheExampleSchemasConditions` then states whether the store's `ConditionEmitter` emits the same shape or declines it with a reason; that census is the flip's gate, so the flip is held to this result without a test asserting on emitted text. Reading `ConditionEmitter.decoded` and `NodeIdDecodeEmitter.parameterHelper` suggests it emits; the census settles it, and a reason it reports is recorded on R876's condition node, not closed here.
* **Acceptance.** The table above, row by row.

## How this sits against other items

* **R876.** Advances "The argMapping readers read the landing, and the candidate and match go" (the condition projections read the stored population) and adds one walk-tier read to the list "The walk's read path is gone" deletes. Does not touch the whole-slot refusal held on `wip/r876-condition-arity-at-flip`. No `depends-on`: nothing here waits on a node, and the flip does not wait on this.
* **R735** keeps the list projection at `@routine`; its body predates the `@service` emitter and this item, and wants a line saying which sites remain when this lands.
* **R1099** stored the landing this item reads; no dependency remains.
* Not this item's, recorded for the R876 landing-readers node: the landing says `WHOLE` at a `@routine` parameter for a bare entry while the routine emitter projects the one key column, so the store and an emitter disagree there too.

## Questions for the reviewer

* **Step 3 against the alternative.** The architect's preference was a stored refusal for a bare entry onto a column-typed parameter, raised by `occurrence-builds` under the existing whole-key code, landing with step 2. That refuses row 9, which builds today, while the by-name spelling of the same binding keeps building until the flip: one rule scoped by spelling for a while. This plan instead changes one remedy clause and leaves what builds alone, on the reasoning that the ruling's staging already says when the whole-slot refusal ships. If a spelling-scoped rule ahead of the flip is wanted, that is Alf's or Filip's to say.
* **Step 1's source.** If reading the stored projection population from `ResolvedKeyProjections` turns out not to be a contained change, the fallback is not the `intent_` view; it is to reopen this spec.

## Architect consult

Consulted twice. At take-up, with a stand-aside for whole-slot entries as the proposed design: it raised the whole-key ruling (which redirected the item), that deleting duplicated verdicts beats filtering them (taken, step 2), that the whole-or-column predicate has three readers (taken: the stand-aside reads the landing, and step 1 moves the projection reader toward it), and the `@routine` disagreement (recorded above). After the ruling, on where the list projection is emitted: that a per-call flip to `ConditionEmitter` is ruled out by R876's every-caller-at-once flip (taken, step 1), that a walk-tier read is allowed only from a stored fact and named for deletion (taken, step 1), that Alf's 2026-11-14 ruling and its staging already existed in R876's graph (taken: the architecture page now carries it, and this item leaves the whole-slot refusal to that branch), that the census pins the store side more cheaply than a hand-built emitter test (taken, Tests), that a removed refusal must not lose its help (taken, step 2), and that a bare entry onto a column-typed parameter should be refused from the store now (not taken; first question above).

## Workaround today

Name the condition method's parameters after the GraphQL arguments and drop `argMapping`; the ids are then decoded before the method runs. Each `@nodeId(typeName:)` argument must reach its node's table from the field's table, by a single foreign key or a written `@reference` path, and `override: true` keeps those paths from adding predicates of their own. It is a stopgap: the condition flip refuses this spelling, and the dotted entry is the one that stays.

## Reviewer findings

### Round 1: Spec → Ready, revisions requested (session_01FY5UvtKTEepzj8dsSWvAgv, 2026-11-20)

Question 1 passes. The goal reads on its own: a list of node ids reaches a `@condition` method as a `List` of one key column when the `argMapping` entry names the column past the slot, and each refusal met on the way names that spelling. It is reachable: the store already states the dotted list entry as a `COLUMN` landing with a slot projection and a list-aware type rule that covers `@condition`, the three refusals in front of it are where the spec says they are, and every symbol, relation, page, fixture, R876 node and branch the spec names exists as named. Question 2 passes on the shape (one more read on `ProjectedKeyReads`, fed from a stored fact and named for deletion; a stand-aside of the kind `authorDefects` already has for `@service`; one message clause; the whole-slot refusal left to the flip, as the ruling page stages it) and fails on one point in step 2.

1. **The stand-aside is keyed wider than what the store judges, so a refusal goes with nothing replacing it (question 2).** Step 2 has "the `intent_` verdicts" stand aside wherever a condition entry's landing has `receives = 'COLUMN'`, on the claim that the store judges those entries, and names stored counterparts for three of the five verdicts. `graphitron_argmapping_landing_rule` sets `COLUMN` on a trailing name alone (`s.consumed < s.segments`), whether or not the landing carries a node id, and `facts/defects/argmapping-landing.graphqls` pins that: `c: input.ref.actor_id` lands on an input object with `trailing_name = actor_id` and draws no pair defect. The store's refusal for an unresolved projection, `ARGMAPPING_KEY_COLUMN_UNRESOLVED`, joins `graphitron_occurrence_node_id` at the landing, so it speaks only where a node id binds. That leaves `UNDECLARED_NODE_ID`, a name past a plain `ID`, a `String` or an enum, with a `COLUMN` landing and no stored counterpart. Stood aside as written, such an entry at a field-level or argument-level condition is no longer refused, no projection row claims it, and `ConditionGlueRenderer.nestedExtraction` falls through to the ordinary map descent past a scalar: the silence this family exists to close. `MISSING_TYPE_NAME` is in the same position wherever no node binds at the landing; where the store infers one, the stand-aside turns a refusal into a build, which the spec does not mention (read off the rules, not probed).
   The Tests section would not notice either way. `facts/defects/argmapping-projection-defect.graphqls` holds one condition row, a bare `BARE_NODE_ID`, which is a `WHOLE` landing, so it has no claim to lose and none that pins a survivor; and "the stored counterpart of each is pinned" has nothing to pin for these two.
   Satisfied when step 2 says which verdicts stand aside at the grain the store judges (by name, the three the spec gives counterparts for, or keyed on the stored fact that makes the store the judge rather than on `receives` alone), says what becomes of the two without a counterpart (kept as they are, or given a stored code, which is a larger change than this item states), and the Tests list pins a condition-site entry naming past a plain `ID`, and one past a `@nodeId` with no `typeName`, with the outcome the spec intends for each.

On the two questions for the reviewer, no finding. Step 3 as planned is what the ruling page states: "a refusal whose message changes before the flip names the projection as its remedy", and the whole-slot refusal ships "when the walk no longer renders conditions and not before", so refusing row 9 ahead of the flip would need a new ruling and not a reviewer's preference. The reopen fallback for step 1 is the right stop condition.

Not blocking, for the implementer: `NodeIdDecodeCoverageFacts.projectedInstalls` is a second reader of `intent_resolved_node_key_projection` at the walk's condition sites, and the list pair never has a row there. The walk's ledger appears to cover the argument on its own, so the coverage rule should stay quiet, but it is the reader to look at first if "nothing in this build decodes it" turns up on row 4.
