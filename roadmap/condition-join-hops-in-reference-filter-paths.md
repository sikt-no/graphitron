---
id: R705
title: "A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships"
status: Backlog
bucket: feature
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-18
last-updated: 2026-08-18
---

# A condition-join hop in a reference filter path is rejected, though the emitter it needs already ships

A `@reference(path:)` on an output field may join through a developer-supplied condition method (the
`{condition: {...}}` path element, `On.Predicate` on the resolved hop). The same path element on a
*filter* carrier, an input field or a query argument reaching a column on a joined table, is
rejected at classify time. The author-facing message is:

> argument 'x': @reference filter path traverses a condition-join (non-foreign-key) hop, which is
> not yet supported; reference filters emit a foreign-key correlated subquery and require every
> hop to resolve to a foreign key

Two sites enforce it: the argument arm in `FieldBuilder` (`classifyScalarArg`'s `@reference` branch,
guarded by an `On.ColumnPairs` test, message shared through
`FieldBuilder.referenceFilterConditionJoinRejection`) and the input-field mirror in
`GraphitronSchemaValidator.validateInputColumnField`. The mechanical cause sits below both:
`ServiceCatalog.terminalTableForReference` returns empty on the first non-`ColumnPairs` hop, so the
terminal column never resolves and the carrier cannot classify.

## Why this is worth reopening

The restriction reads as a cardinality rule but is not one. The justification a reader expects, that
a foreign key targets a unique key so the correlated `EXISTS` matches at most one row where an
authored predicate carries no such guarantee, is already settled the other way in this repo: the
changelog entry for the FK-target `@nodeId` filter work records that `EXISTS` is *"the semantically
right shape rather than a convenient one: no row multiplication when the path is non-unique, and a
NULL FK column fails the correlation instead of duplicating or dropping rows."* The `{key:}` form
also does not constrain FK direction, so a reverse-FK hop in a filter path already produces
`EXISTS`-over-many today. Foreign-key-ness is not buying uniqueness in filter position.

What it does buy is two mechanical facts, and both are already available:

* **A resolved terminal table**, so `@field(name:)` finds a real column.
  `ServiceCatalog.terminalTableForReference`'s javadoc states that *"a condition-only step's target
  table is unknown at build time"*. That is stale. `BuildContext.parsePathElement`'s condition arm
  resolves the target through `ConditionJoinTargetResolution` and stores it as a
  `TableExpr.Catalog`, which `JoinStep.Hop.targetTable()` folds back for the uniform read. The
  terminal is known; only this walker refuses to use it.
* **A correlation predicate for hop 0.** The filter emitter (`ConditionGlueRenderer.reachExists`)
  reads `FkHop.pairs()` directly, and `FkHop.narrow` accepts nothing but `On.ColumnPairs`. The
  sibling projection emitter, `PathFragments.correlationWhere`, already dispatches the full seal and
  has the arm this needs: `case On.Predicate pred -> emitTwoArgMethodCall(pred.condition(),
  parentLocal, firstAlias)`. The correlated `EXISTS` for a condition hop is a two-argument call
  against the parent local and the hop alias, which is the shape `appendHopFilters` also emits for
  per-hop `filter()` methods.

So the deferral is a scope boundary drawn when reference filters first shipped, not a semantic
position. The generated SQL for the condition case is the developer's own predicate inside the same
`EXISTS` the FK case builds, which is exactly what the sibling emitter proves.

## Consumer motivation

This is the last thing blocking a direct port of a v9 filter shape. Under v9 a `{condition:}` hop in
filter position worked, so schemas expressed a filter reaching a joined table's columns without the
join being a declared foreign key. Where the underlying relationship *is* conventionally a foreign
key and merely undeclared, the right consumer fix is to declare it (a jOOQ `<syntheticObjects>`
`<foreignKey>` entry, then swap the path to `{key:}`), and that is strictly better than lifting this
restriction: it makes the relationship a catalog fact once instead of restating the predicate at
every filter field, and it unlocks auto-discovery, `{table:}` paths, multi-hop `@nodeId` chains
(FK-only at every position by `NodeIdLeafResolver`), and LSP completions along with it.

But that remedy only exists when the predicate really is a key equality. The shapes the
`condition:` form was reserved for, date-range overlaps, prefix and computed predicates, anything
with no key to declare, have no synthetic-FK equivalent. For those the only path today is to
hand-write the whole `EXISTS` inside a plain `@condition` method on the input field, which receives
the field's own table and the filter value. That works, and is why nobody is hard-blocked, but it
re-implements per filter field the correlation the generator already owns, and it opts the field out
of the generated filter machinery (`@field` resolution, enum mapping, the override cascade).

The sibling item on per-participant `@nodeId` filter paths records the same failure mode from the
other direction: a filter carrier whose join cannot be stated, whose author fallback is to
reimplement generator-owned plumbing inside a condition method.

## Scope sketch

Not a plan; the Spec pass owns the design. The pieces a plan would have to place:

* Teach `ServiceCatalog.terminalTableForReference` to walk a `On.Predicate` hop using the resolved
  `targetTable()`, and correct the stale javadoc. Decide whether the walk stays one method or the
  FK-only read remains available for the callers that genuinely need pairs.
* Give the filter emitter the correlation dispatch `PathFragments.correlationWhere` already has.
  `FkHop`'s produce-time narrowing is the load-bearing constraint: either the filter reach carries a
  wider hop type, or `FkHop` gains a sibling. Worth checking whether the two emitters should share
  one correlation helper rather than growing a second copy of the same seal switch.
* Bridging joins inside a multi-hop reach (`JoinFragments.emitBridgingJoin`, also pairs-only) for
  paths that mix `{key:}` and `{condition:}` hops. A first cut could accept a condition hop only at
  position 0 and keep a stated deferral for interior ones.
* Decide the `@lookupKey` and write-rail posture explicitly rather than by omission; those rails
  hold their own deferrals for the FK-target `@nodeId` filter shape and this should say which side of
  the line it lands on.
* Retire the FK-only claim in the docs where it is stated as a rule
  (`docs/manual/how-to/join-with-references.adoc`, the "Foreign-key hops only" section), and keep
  the recommendation that a conventionally-foreign-key relationship should be declared as a
  synthetic FK rather than filtered through a condition method.

## Adjacent stale documentation

Independent of this item and cheap to fix separately:
`docs/manual/how-to/join-with-references.adoc`'s "The `condition:` form (classify-only today)"
section states that the condition-join emitter is a runtime-throwing stub and that selecting such a
field *"throws `UnsupportedOperationException` until the emitter ships a real body"*, and the
pitfalls list repeats it. That is no longer true: condition-join references execute against
PostgreSQL in the execution tier for the inline shape, the batched split-rows shape, and the
FK-then-condition bridging shape, backed by the fixtures in `ReferencePathConditionFixtures`. The
advice that production schemas should avoid `condition:`-only references now rests on a false
premise. Either fold the correction into this item or file it as its own docs item.
