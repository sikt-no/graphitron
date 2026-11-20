---
id: R1202
title: "A list of node ids bound by argMapping at a field-level @condition is refused, though the same binding by parameter name builds"
status: Spec
bucket: bug
priority: 6
theme: nodeid
depends-on: []
created: 2026-11-20
last-updated: 2026-11-20
---

# A list of node ids bound by argMapping at a field-level @condition is refused, though the same binding by parameter name builds

## Goal

A consumer can hand a list of node ids to a field-level `@condition` through `argMapping` and have the method receive the decoded key column values as a `List`, and every refusal met on the way names a declaration that then builds. Today no `argMapping` spelling of that binding builds: the bare entry is told to declare the column's own type, following that draws two further refusals of which one asks for the `List` again, and the dotted entry is refused as "a list of input objects". The same parameter builds when it is named after the argument and no `argMapping` is written, which a consumer has no way to discover from the messages. Reported in [GitHub issue 583](https://github.com/sikt-no/graphitron/issues/583), where the consumer dropped `@nodeId` and decodes by hand inside the condition, losing the client error a bad id gets at every other `@nodeId` site.

Which spelling is the one that builds, the bare entry or the dotted one, turns on a ruling that is not written down for `@condition`; it is the first section of the plan, and the plan has an arm for each answer.

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

| Site and key | Spelling | Parameter | Outcome |
|---|---|---|---|
| Any of the three condition sites, list, one-column key | by name | `List<Integer>` | Builds. |
| The same | bare `argMapping` | `List<Integer>` | Refused: "whose key column 'language_id' jOOQ binds as Integer, but the parameter it binds to takes List; bind a parameter of the column's own type". |
| Field-level, list, one-column key | bare `argMapping` | `Integer` | Refused twice: "Declare the parameter as 'java.util.List<java.lang.Integer>'", and "opens a list of node ids ... which parameter binding does not emit yet". |
| Field-level, list, one-column key | dotted `argMapping` | `List<Integer>` | Refused twice: the type message of the second row, and "descends into 'languageIds', a list of input objects with no single value below it". |
| Field-level, single id, composite key | by name | `Row2<Integer, Integer>` | Builds. |
| The same | bare `argMapping` | `Row2<Integer, Integer>` | Refused: "whose key is 2 columns, and one binding carries one value; open it with one of them". |
| Input-field `@condition`, single id, one-column key | by name | `Integer` | Builds. |
| The same | bare `argMapping` | `Integer` | Refused: "resolves a key column of 'LanguageNode', which no emitter reads at this site yet". |
| Field-level or argument-level, single id, one-column key | bare `argMapping` | `Integer` | Builds. |

So the reported list case is one face of a wider disagreement: a bare `argMapping` entry onto a `@nodeId` slot at a `@condition` is judged differently from the same binding made by name, at a list, at a composite key and at the input-field site. The last row builds only because the two judgments happen to agree there.

A separate fact the probe turned up, which bounds any workaround: a `@nodeId(typeName:)` argument whose node table is not reachable from the field's table by one foreign key or a written `@reference` path is refused in either spelling, with or without `override: true` ("no foreign key found between tables", or `NODE_ID_LANDS_ON_TABLE`). With a multi-hop `@reference` path written on each argument the by-name spelling builds, with and without `override: true`.

## Who judges the binding, and what each says

Three readers judge the one binding, and they do not agree.

* **The walk's whole-slot rule.** `ConditionResolver.installNodeIdDecode` hands a whole-slot parameter the decoded key: the key column's own type at a one-column key, a jOOQ `RowN` at a composite key, either wrapped in `List` for a list slot. It applies to a bare `argMapping` entry and to a parameter named after the slot alike, and `ProjectedKeyReads.installRailOwns` lets it win at render. `docs/manual/reference/directives/condition.adoc` documents this as the contract.
* **The retiring projection defects.** `ArgmappingProjectionDefects` reads `intent_argmapping_projection_defect` and `intent_resolved_node_key_projection`, views of the `intent_` family that is being dissolved. Those infer a one-column projection for every entry that names no column, bare ones included, and compare the column's type with the parameter's erased class, so `List<Integer>` reads as `List`. Every refusal in the table but the "list of input objects" one comes from here. The by-name spelling writes no entry, so these views have nothing to read, which is the whole reason it builds.
* **The store's occurrence family.** `graphitron_argmapping_landing.receives` already states what an entry hands over: `WHOLE` for a bare entry, `COLUMN` for a dotted one or one descending onto the slot. The probe's rows show it: the bare list entry lands `WHOLE` on a slot with `list_depth = 1`, `slot_shape = LIST`, `landing_class = java.lang.Integer`, identical to the by-name spelling's slot row; the dotted entry lands `COLUMN`. The store's own type judgment for a projected column, `NODE_ID_KEY_COLUMN_TYPE_DISAGREEMENT`, covers `@condition` as well as `@service`, is list-aware, and raised nothing against the dotted `List<Integer>` row above.

The store is where this is going, and it is silent on one thing: a whole key landing on a parameter of the column's own type at a `@condition`. `graphitron_occurrence_node_id_shape` holds no row for the by-name `List<Integer>` and `Integer` parameters above, and a slot with no shape is what the store refuses at a `@service`.

## The ruling this turns on

`docs/architecture/explanation/defects-as-facts.adoc` records (Alf, 2026-11-12): "A whole decoded key lands only on a record or a tuple ... Handing a single-column key's value to a parameter of the column's type without saying so is dropped; an author wanting one column projects it with `argMapping`." At `@service` that has shipped as a breaking change, and `docs/manual/reference/directives/nodeId.adoc` states it: `Integer key` at a one-column key is refused, and the remedies are the node's record, a tuple, or an `argMapping` entry naming the column. The store applies the whole-key judgment at `call_kind = 'SERVICE'` only. Nothing says whether the ruling reaches a whole-slot `@condition` parameter, and the condition manual page promises the opposite.

**Question for Alf or Filip: does the ruling reach `@condition`?**

* **Arm A, it does.** One rule at both call kinds. The spelling that hands a list of ids' key column to a `List<Integer>` is the dotted one, `"p: languageIds.language_id"`. A bare or by-name whole-slot parameter takes the node's record or a tuple (`List` of either for a list slot), and a parameter of the column's own type there is refused with the dotted entry as its remedy.
* **Arm B, it does not.** `@condition` keeps the walk's contract: a whole slot at a one-column key hands over the column's value. The bare entry is then the spelling that builds, and the exception is written into `defects-as-facts.adoc` beside the ruling, since nothing states it today.

Recommendation: arm A. The store already states it (no decode shape for the column-typed whole-slot parameter), `@service` already ships it, and R876's node "A `@condition` and a service pair a parameter with an argument by one rule" wants one rule, not two. Arm B is the smaller change now and leaves a second contract for the store to grow a shape for when conditions flip to it.

The ruling, once given, goes into `defects-as-facts.adoc` as a `docs` commit, and this section collapses to a pointer at it.

## Plan under arm A

1. **The dotted list projection emits at `@condition`.** `ArgmappingProjectionDefects.listDeferred` stops deferring at the condition sites, and the condition glue reads the column off each decoded id. This is the `@condition` slice of R735 (the list projection, filed for `@routine`); that item keeps the routine half. Whether the read is added to the walk-era `ProjectedKeyReads` or lands with the store-emitted condition call is the design fork to take to the architect when this arm is chosen: R876's node "A `@condition`'s calls, candidates and fills are written per depth" still leaves a condition taking a decoded key unemitted (`AUTHORED_NODE_ID` in `ConditionEmitter`).
2. **One type judgment per projected entry at `@condition`.** `ArgmappingProjectionDefects.authorDefects` already stands aside for `KEY_COLUMN_TYPE_MISMATCH` at `@service` because the occurrence build judges it; the condition sites join that stand-aside, along with any other verdict the store raises for a `COLUMN` landing (`ARGMAPPING_KEY_COLUMN_UNRESOLVED` covers the unknown column and the composite key with none named). What is deleted is a duplicate, and the list-blind message goes with it.
3. **The walk's "list of input objects" refusal no longer fires on a path that opens a list of node ids with a key column.** Find its owner (`ServiceCatalog` is the likely one) at implementation.
4. **A bare entry's refusal names the dotted spelling.** A `WHOLE` landing on a parameter of the column's type is refused with the remedy "name the column", read off `graphitron_argmapping_landing.receives`, not re-derived from the written path. Whether the *by-name* spelling is refused in this item or when conditions flip to the store is a breaking-change decision to make with the ruling; this plan assumes it is not this item's, and says so in the manual.
5. **Docs.** `condition.adoc`'s whole-slot bullet is rewritten to the ruling, and the projection bullet gains the list form.

## Plan under arm B

1. `ArgmappingProjectionDefects` reports nothing for an entry whose landing `receives = 'WHOLE'` at a condition site: the three verdicts the store already raises for `COLUMN` landings stand aside at conditions as at `@service`, and `primitiveParameters` and `unemittableProjections`, which have no store counterpart, read `receives`. The condition sites are a named set beside `EMITTING_SITES`, being a fact about which emitter serves a whole landing.
2. `NodeIdDecodeCoverageFacts` narrows on the same set, so the census does not count a whole-slot condition binding as a projected-key decode; checked against `NodeIdDecodeCoverageRatchetTest`.
3. The exception is written into `defects-as-facts.adoc`, and the dotted list projection stays deferred with R735.

## Tests

* `graphitron-model/src/test/resources/facts/scenarios/argmapping-key-column.graphqls` is the keystone: it already pins `receives` for a by-name, a named and a descending entry at a `@condition`. It gains the bare entry (`WHOLE`), a list slot and a composite key, with the claims the chosen arm makes true.
* `facts/defects/argmapping-projection-defect.graphqls` loses the claims for verdicts that stand aside at conditions, and each store counterpart is pinned by a document showing the row.
* One execution case in `graphitron-sakila-example` for the reported shape, a list of ids and a single id read by one field-level condition, including a malformed id in the list returning the client error.
* The nine rows of the table above are the acceptance list: each spelling either builds or is refused with a remedy that builds.

## How this sits against other items

* **R876**, node "The argMapping readers read the landing, and the candidate and match go": this item reads `graphitron_argmapping_landing` from `ArgmappingProjectionDefects` ahead of that node and deletes condition-site duplicates, so it advances the node and does not wait on it. No `depends-on`.
* **R735**: keeps the list projection at `@routine`. Under arm A this item takes the `@condition` slice; under arm B nothing moves.
* **R1099** stored the landing this item reads; no dependency remains.
* Open, not this item's: the landing says `WHOLE` at a `@routine` parameter for a bare entry while the routine emitter projects the one key column, so the store and an emitter disagree there too. Recorded here for the R876 landing-readers node.

## Architect consult

Consulted at take-up, with the stand-aside of arm B as the proposed design. It raised: the 2026-11-12 ruling and its silence on `@condition` (now the plan's first section and the reason for two arms); that a filter adds to a retiring reader where deleting the duplicated verdicts would not (taken, step 2 of arm A and step 1 of arm B); that the whole-or-column predicate has three readers, the coverage census among them (taken as arm B step 2; under arm A the census is checked at implementation); and the `@routine` disagreement above (recorded, left out of scope).

## Workaround today

Name the condition method's parameters after the GraphQL arguments and drop `argMapping`; the ids are then decoded before the method runs. Each `@nodeId(typeName:)` argument must reach its node's table from the field's table, by a single foreign key or a written `@reference` path, and `override: true` keeps those paths from adding predicates of their own. Under arm A this spelling is the one a later change refuses, so it is a stopgap.
