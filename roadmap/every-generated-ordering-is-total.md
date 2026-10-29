---
id: R1122
title: "every ordering graphitron generates is deterministic and ends in a tiebreaker"
status: Ready
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-10-28
last-updated: 2026-10-29
---

# every ordering graphitron generates is deterministic and ends in a tiebreaker

## Goal

Every ordering graphitron generates is total: two runs of the same query over the same rows return them in the same order, because a declared order that leaves rows tied is completed with the table's row key. Today that holds only for a single-table connection; a plain list root, an inline child list and a `@splitQuery` child list sort on the listed columns and leave tied rows to whatever order the database happens to produce, which can differ between executions and between PostgreSQL versions. When this lands, a consumer and a test can rely on a declared list order the way they rely on connection order. A list that declares no order stays unordered: this item completes an order, it does not invent one.

## Provenance

`SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` failed on trunk CI with the two films that tie on the sort key swapped (`[5, 3, 2, 1, 4]` against `[5, 2, 3, 1, 4]`). The test was right to expect the tiebreaker: the connection variants of the same order append `film_id` and pass, and the plain list is the same order without it. The fault is in the generated SQL, not in the test. It recurred on 2026-10-29 in a full clean reactor install on a heavily loaded machine and passed in three isolated reruns, so it does not reproduce on demand. `pathKey_inlineChildList_ordersInTheSubselect` runs the same fixture and the same expected order through an inline child list, so it has the same exposure and has not failed yet.

## Plan

1. *Where the gap is.* `OrderByResolver.resolve` builds the table's `RowIdentity` only when `singleTable && ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection`; everything that completes an order (`complete`, `completed`, the `@orderBy` single and list compositions, `SequenceCompletion.AppendRowKey`) keys off that value. Drop the `Connection` conjunct so the identity is decided for every single-table list field, and the existing completion applies at each site the order renders: root list, inline child list, `@splitQuery` child list, and the `@orderBy` helper compositions. No new completion path is added.
2. *The key's direction.* A list has no cursor, but the key takes the direction of the last listed column exactly as on a connection, because `complete` is reused unchanged and a runtime `direction:` then flips the key with the rest. An ascending-only rule for lists would be a second branch through `complete` for no consumer-visible gain.
3. *An undeclared order stays undeclared.* `complete` returns early on an empty entry list, so a list with no `@defaultOrder` and no `@orderBy` is left alone. Ordering every such list by its row key would change the SQL of every unordered list in every consumer; that is a separate decision and not this item's.
4. *The audit of every `orderBy` the generators emit.* `OrderByResolver.resolve` has one caller, `FieldBuilder.projectForFilter` (via the table-field component builder), so every order a table-bound field declares reaches the renderers through one `OrderBySpec` and step 1 is one change, not one per site. The emissions that do not take that spec are out of the rule, each for its own reason:
   * *In the rule* (fed by `resolve`, completed once step 1 lands): `RootLauncherRenderer` (`.orderBy(orderBy)`), the inline child list in `ProjectionUnitRenderer`, the `@splitQuery` batched rows in `BatchedRowsFragments` (`.orderBy(orderBy)` and the `rowNumber() over (partition by idx order by ...)` window), and the `@orderBy` helper methods `buildOrderByHelperMethod` emits in `TypeFetcherGenerator`.
   * *Out, restoring input order:* `orderBy(idxCol)`, `orderBy(input.field("idx"))` and `orderBy(projectionInput.field(1, ...))` in `SelectMethodBody`, `ProjectionUnitRenderer`, `ReentryRowsFragments`, `ServiceRowsFragments` and `MultiTablePolymorphicEmitter`. They sort by the batch position of the keys the caller sent, which is already total and is not a declared order.
   * *Out, multitable:* `MultiTablePolymorphicEmitter`'s `page.effectiveOrderBy()` sites. Participant orders are folded into shared slots, `resolve(..., singleTable = false)` stays uncompleted by design, and the emitter already adds the participant key and `__typename`.
   * *Out, no order emitted:* DML `returning` lists: no generator that renders one writes an `orderBy`, and `resolve` is reached only from the table-field component builder. A list with no declared order stays unordered (step 3).
   * *In the rule, including `@routine` chains:* `resolveTableFieldComponents` is also the entry for a `@routine` chain, so a routine-chain list that declares an order is completed like any other. When the chain's terminal is a table-valued function result, the table carries no primary key, so such a list is keyless and takes the step-5 warning; the implementer confirms that with a pipeline case rather than assuming it.
5. *Keyless tables: a sibling rule, and how it is raised.* Add a new `LintRule` constant `LIST_ORDER_NOT_UNIQUE("list-order-not-unique", Source.CLASSIFIER)` beside `CONNECTION_ORDER_NOT_UNIQUE`, not a widening of it. The two are separate because an author's stance differs (a connection's tie loses rows between pages; a list's tie only makes the order unspecified), so a consumer must be able to silence one and keep the other, and because `connection-order-not-unique` is a published `<disabledRules>` name that has to keep meaning what it says. `LintRuleCatalogueTest` pins the new name as it pins the old one. `OrderByResolver.Resolved.Ok` renames its flag from `keylessConnection` to `keyless` (true when the order is non-empty and the identity is `Keyless`, as today), so it states a fact about the table and not about the field kind; the caller, which already holds `ctx.buildWrapper(fieldDef)`, picks the rule. `FieldBuilder.warnConnectionOrderNotUnique` becomes `warnOrderNotUnique`, branching on `FieldWrapper.Connection`: a connection keeps its current message and rule unchanged; a list emits the new rule with its own message (rows that tie on every listed column come back in an order the database chooses, and may differ between executions), the same remedies (declare a primary key, add `@node(keyColumns: ...)`), and `'list-order-not-unique'` as its `<disabledRules>` spelling. A warning, never a rejection.
6. *Cost.* An ordered list gains one trailing `ORDER BY` column. Where the sort column is indexed but the key is not part of that index, the database may have to sort rows it could previously read in index order. Accept this, state it in the breaking-change note. An author who lists the key themselves changes nothing, as on a connection.
7. *Say it where it is promised.* `docs/manual/how-to/sort-results.adoc` (`stable-cursors-and-tie-breakers`) states the rule for connections only; extend it to lists, and add the breaking-change note that a list whose declared order did not identify a row now has a defined tie order which can differ from what a consumer saw before. `docs/manual/explanation/design-decisions.adoc` and `batching-model.adoc` mention connection ordering and are checked for the same claim.

## Tests

The sakila fixtures carry the list shapes they need only partly: the root list (`filmsByOriginalLanguageList`) and the inline child list (`filmsInlineByOriginalLanguage`) exist; the split child list and the two `@orderBy` list forms exist only as connections. The item adds to `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`, each with the same original-language sort key so films 2 and 3 tie:

* `Language.filmsListByOriginalLanguage: [Film!]!` with `@splitQuery`, the same `@reference` and `@defaultOrder` as `filmsByOriginalLanguage`.
* `Query.filmsByLanguagesList(order: FilmLanguageOrderBy @orderBy): [Film!]!` with the same `@defaultOrder` as `filmsByLanguages` (the single-valued form).
* `Query.filmsByLanguagesComposedList(order: [FilmLanguageOrderBy!] @orderBy): [Film!]!` with `@defaultOrder(primaryKey: true)` (the list-valued form).

Then:

* *Pipeline, one case per site in step 1,* over a table whose order does not identify a row, asserting the resolved `OrderBySpec` ends in the row key, the way `ConnectionOrderCompletionCase` pins connections. These assert the model, not the emitted source: no generated-body string assertions.
* *Execution, one walk per site in step 1* (root list, inline child list, split child list, single-valued and list-valued `@orderBy` at a list), each over the tied films 2 and 3 and each demonstrated to fail with the completion disabled for that site, as `ConnectionTiebreakerExecutionTest`'s walks were. `SortKeyOrderExecutionTest.pathKey_plainListRoot_ordersByTheRelatedColumn` and `pathKey_inlineChildList_ordersInTheSubselect` are the first two and need no change.
* *Keyless:* a pipeline case that `list-order-not-unique` fires on a keyless list that declares an order, does not fire on a keyed one, and that `connection-order-not-unique` still fires, with its message unchanged, on a keyless connection; `LintRuleCatalogueTest` pins the new name.
* A repeated-run loop is not evidence: the flake does not reproduce on demand, which is why the walks assert a specific tie order rather than stability across runs.

## Reviewer findings

### Round 1 (Spec → Ready review, session_01WkW2JEG4rUdXwE1fc1y7Bt): request revisions

Question 1 (goal and viability) passes. The goal is clear and reachable: `OrderByResolver.resolve` gates `RowIdentity` on `singleTable && ctx.buildWrapper(fieldDef) instanceof FieldWrapper.Connection`, `complete` and `SequenceCompletion.AppendRowKey` already exist, and the Provenance failure is explained by that gate. Question 2 (would an implementer build this as-is) fails on two open forks that the plan itself says must be closed before Ready:

1. *Step 4's audit is not done.* The plan says the `orderBy` emissions in `TypeFetcherGenerator` (routine chains, DML `returning` lists, the keyed lookup in `SelectMethodBody`) are checked and "the ones that stay out are listed here with a reason before the item moves to Ready". None is listed. What would satisfy it: for each of those sites, a line saying whether it is in the rule (and which `resolve` call feeds it) or out, with the reason. Without it the implementer decides the scope, and the answer changes which fields get a new trailing `ORDER BY` column.
2. *Step 5's sibling lint is unspecified.* The plan says its name and disable path are "settled here before Ready"; neither is. This is not a detail: `LintRule` is a public `<disabledRules>` vocabulary (`CONNECTION_ORDER_NOT_UNIQUE` is `Source.CLASSIFIER`, pinned in `LintRuleCatalogueTest`), and the signal today is `Resolved.Ok.keylessConnection`, a connection-named flag set only when `identity` is `Keyless`, which after step 1 will also be true for lists. What would satisfy it: the rule's name; whether it is a new enum constant or a widening of the existing one (and why); how `Resolved.Ok` tells the caller which of the two to raise (a second flag, or the flag renamed to cover both); and the `FieldBuilder` warn site named, since `warnConnectionOrderNotUnique` is connection-worded.

Also settle in the same revision, because it changes what gets built: the Tests section requires execution walks for a `@splitQuery` child list and for a single-valued and a list-valued `@orderBy` at a list over the tied films, but the sakila fixtures only carry those as connections (`filmsByOriginalLanguage` split connection, `filmsByLanguages`, `filmsByLanguagesComposed`); only the root list and inline child list exist. Say that the new list fields are added to `schema.graphqls`, or name the existing ones.

Verdict: stays in Spec. Everything else (the `complete` reuse, direction rule, empty-order early return, multi-table exclusion, cost note, docs target `sort-results.adoc` which states the connections-only rule at the "Plain lists are not completed" sentence) checked out against the tree.
