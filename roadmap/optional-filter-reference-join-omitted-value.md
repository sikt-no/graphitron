---
id: R888
title: "An optional filter field's @reference join must not constrain the query when the value is absent"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-08-31
last-updated: 2026-08-31
---

# An optional filter field's @reference join must not constrain the query when the value is absent

A `@reference` path on an optional input-type filter field turns into a correlated `EXISTS`
against the referenced table. An `EXISTS` is a semi-join: it keeps only the rows that have at
least one matching row on the far side of the path. When that `EXISTS` is applied while the
filter carries no value, the query stops meaning "the whole collection" and silently starts
meaning "the part of the collection that has the relation at all". Rows without the relation
disappear from the result and from `totalCount`, with no error and no warning, which is the
hardest failure mode for a consumer to detect. The rule the coordinate owes is the one every
implicit filter conjunct already follows: an absent value contributes no conjunct.

## Field report

Reported as [issue 537](https://github.com/sikt-no/graphitron/issues/537) against `10.0.0-RC35`
(tilgangsstyring subgraph in fs-plattform): an optional connection filter field
`navnerom: [ID!] @nodeId(...) @reference(path: [{key}, {key}])`, queried with the filter
*omitted*, silently dropped the one role that had no membership row. Sibling
[issue 536](https://github.com/sikt-no/graphitron/issues/536), same schema and experiment, is
the decoding half: the same coordinate never decoded the node-id wire values, so any non-empty
filter value threw `ClassCastException`.

## Reproduction on trunk: the reported coordinate no longer reproduces, and nothing pins that

Measured at trunk `7584c75` with a scratch execution test against the sakila fixture
(`actorsByFilmFilter`, the exact reported shape: nullable filter input, `[ID!]` field carrying
`@nodeId` plus a two-hop junction `@reference`, no `@condition`), after seeding an actor with no
`film_actor` rows:

* Filter omitted, `filter: {}`, and `filter: { filmIds: null }` all rendered
  `select ... from actor` with **no `EXISTS` at all**, and the relation-less actor came back.
  The generated glue both decodes and guards:
  `if (filmIds != null && !filmIds.isEmpty()) condition = condition.and(DSL.exists(...))`.
  So the two RC35 defects are fixed on current trunk for this coordinate.
* But no test anywhere executes an input-field `@reference` coordinate with the filter argument
  absent. `TranslatedFkTargetFilterExecutionTest.junctionChain_inputFieldForm_returnsTheSameRows`
  always supplies values; its empty-list case exists only for the *argument* surface
  (`actorsByFilmIds(filmIds: [])`). `ConditionGlueRendererTest` pins the `EXISTS` shape but not
  whether it sits under a guard. The exact regression a consumer already hit in a release is one
  unpinned edit away from coming back, and it would come back silently, which is the failure
  mode itself.

## The half that still reproduces: an authored `@condition` on the same coordinate

The sibling coordinate, an optional filter field whose `@reference`-derived reach carries an
authored `@condition`, still applies the `EXISTS` unconditionally. `ConditionGlueRenderer`'s
authored arm emits `condition = condition.and(...)` with no value guard (the generated-term arm
next to it guards via `appendGuardedAnd`). Reproduced at trunk `7584c75`: with a customer row
whose `address_id` is NULL seeded, `customersByAddressDistrict(filter: {})` renders the bare
`exists (select 1 from address ... where address_id = customer.address_id and <authored>)` and
drops that customer. The convention that an author maps an absent value to `noCondition()`
cannot save this coordinate: the `EXISTS` wrapper is emitted outside the author's method, so
`noCondition()` inside it still leaves a semi-join on the path, and the author has no way to opt
out of the row-dropping. `ConditionSqlBaselineTest.fkTargetCoordinate_correlatedExistsOverTheFkHop`
currently pins this unguarded shape as the baseline.

There is a real design fork here rather than an oversight to patch: an authored condition with
`override: true` that deliberately ignores its value (the Alberta-district fixture) arguably
*wants* to fire without a value, and "every authored `@condition` produces SQL" is stated
doctrine in `FieldBuilder`. What the fork has to reconcile is that doctrine with the reporter's
expectation, which this item adopts for the implicit case and treats as the default to argue
from: omitted filter means unfiltered result, and if conditional application is not feasible for
some authored shape, that combination should be an author-error at generate time rather than a
silent semantics change of the unfiltered query.

## What this item ships

1. Pins for the fixed coordinate: an execution-tier case executing the input-field `@reference`
   filter with the argument omitted (and `filter: {}` / null-valued field) against seed data
   containing a relation-less row, and a unit-tier pin that the generated-term `EXISTS` sits
   under the value guard. The connection form with `totalCount` belongs in the pin, since the
   report measured both.
2. The decision and implementation for the authored-`@condition` coordinate: guard, or reject at
   generate time, per the fork above; either way the baseline SQL pins move deliberately rather
   than silently.
3. A reply on issues 536/537 once trunk behavior is pinned, stating which release carries the
   fixes for the reported coordinate.
