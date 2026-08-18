---
id: R663
title: "@defaultOrder on a @splitQuery child list is dropped at emit"
status: Backlog
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
---

# @defaultOrder on a @splitQuery child list is dropped at emit

A child list field carrying `@splitQuery` and `@defaultOrder` resolves the directive into an
`OrderBySpec.Fixed` on the leaf and then emits a batch query with no `ORDER BY`. The declared
sorting contract is discarded between the model and the generated SQL, and nothing warns.

Found while reviewing the root-routine ordering drop, now owned by
`roadmap/routine-composition-surface-from-facts.md` (R704), which fixes
the same symptom at the root `@routine` chain. This is the sibling instance at the batched child
coordinate, and it is the harder one: the leaf carries a populated ordering slot, so every
build-time check sees an ordered field.

## Evidence

Two fixtures in `graphitron-sakila-example` declare the shape today: `ConverterOrg.campuses` and
`SplitParent.tags`, both `@splitQuery` + `@reference` + `@defaultOrder(primaryKey: true)`. Their
generated rows methods (`ConverterOrgFetchers.rowsCampuses`, `SplitParentFetchers.rowsTags`)
select, join the parent-input VALUES table, `where`, `fetch()`, and scatter by `__idx__`, with no
`orderBy` anywhere. The inline sibling shape is ordered: `Category.children` carries the same
`@defaultOrder(primaryKey: true)` without `@splitQuery` and its multiset projection emits
`.orderBy(c0.CATEGORY_ID.asc())`. So the same directive on the same kind of field means one thing
inline and nothing at all under `@splitQuery`, which is not a documented difference:
`docs/manual/reference/directives/splitQuery.adoc` lists what `@splitQuery` composes with and
says nothing about ordering being dropped, and its own worked example pairs `@splitQuery` with
`@defaultOrder`.

## Field report

A consumer hit this shape on 10.0.0-RC30 and reported it (github.com/sikt-no/graphitron/issues/523,
the follow-up comment, whose first half is the separate root-query-field case). Their field is a
`@splitQuery @reference` child list carrying `@defaultOrder(fields: [{name: "ROLLEKODE"}])` over a
**view-backed** target: no primary key, and the second reference hop is a synthetic FK. The rows
come back in raw view order with no warning.

That report sharpens the validator-versus-generator disagreement past what the bullets below say.
On a target with no primary key, `validateListRequiresOrdering` does not merely fail to catch the
drop, it *compels* the directive that gets dropped: without `@defaultOrder` the build fails with
"list fields must have a deterministic order. Add a primary key to the target table, or use
@defaultOrder or @orderBy", and a view has no primary key to add. So the author is required to
declare an ordering contract that emit then discards. The build states the invariant and breaks it
in the same run.

**Their diagnosis is that the loss is specific to multitable parents. That attribution looks
wrong and should not steer the fix.** The two fixtures named above are plain object types, not
interface implementations, and they lose their ordering the same way, so the axis is `@splitQuery`
versus inline, not the parent's polymorphism. Their contrast case (an `orderBy` argument working
on a single-table parent path) varies two things at once, argument-driven ordering instead of
`@defaultOrder` and a likely-inline path instead of a split one, so it does not isolate the
multitable factor. The multitable parent in their schema is incidental to this coordinate; the
root-query-field ordering gap they reported separately is the one that genuinely is multitable.

## Diagnosis

`LauncherCommands.batchedResultOf` projects `btf.orderBy()` through `orderingOf` on the
connection arm only. The non-connection arms hand back `ResultShape.SingleRecord` or
`ResultShape.RecordList(null)`, so the resolved spec is dropped at the model-to-command boundary.
`BatchedRowsFragments.body` matches: the connection tail declares both ordering views through
`OrderingBlock.declareBothViews`, while the plain batched tail renders no sort at all. The
`RecordList(null)` literal there reads as a statement that batched lists are unordered by nature;
`batchedResultOf`'s javadoc calls it "a pinned current behaviour", which records the emit's shape
without reconciling it against the authored directive.

Nothing catches the drop:

* `GraphitronSchemaValidator.validateListRequiresOrdering` fires on `OrderBySpec.None`. These
  fields carry `Fixed`, so the check passes, correctly, and the loss happens two layers later.
  R704's enforcement widening cannot reach this population for the same reason.
* The primary-key fallback in `OrderByResolver.resolveDefaultOrderSpec` means a batched child
  list over a table *with* a primary key silently acquires a `Fixed` spec even with no directive
  authored, so the population that loses ordering at emit is every list-shaped batched child, not
  only the ones that wrote the directive.
* The idx scatter does not stand in for the sort. `scatterByIdx` groups rows by their
  `__idx__` cell in encounter order, which fixes *which* parent receives which rows and leaves the
  order *within* each parent's list to whatever the un-ordered `fetch()` returned. The
  `requiresReFetch()` exemption on the deterministic-order validator is justified in its javadoc
  by exactly that idx correspondence, which holds for a one-row-per-key re-fetch and does not hold
  for a many-rows-per-key list.

## Sketch

Pass the ordering into the non-connection list arm (`orderingOf(btf.orderBy(), ...)` in
`batchedResultOf`, the projection the connection arm already performs) and render it in
`BatchedRowsFragments.body` before the `fetch()`. No ordering on `parentInput`'s idx is needed to
keep the groups intact, since the scatter is a per-row dispatch into per-parent lists rather than
a run-length walk; the sort therefore only has to state the within-group order the author asked
for. The single-record-per-key arm stays unordered, where "no ordering" is the honest shape.

Whether the emitted sort should also carry the `Ordering.Helper` arm (an argument-driven
`@orderBy` on a batched child) is a separate question and probably a separate item; the fixed
spec is the reported gap.

## Tests

The visible defect is row order, so the closing assertion is execution-tier: `ConverterOrg` needs
more than one campus per org in the seed (check before assuming) and an exact-order assertion on
the child list, plus the same over `SplitParent.tags`. A command-tier assertion that the batched
list row carries a populated ordering slot pins the projection so the fix cannot regress silently.
Add a no-primary-key target to the closing set: that is the population the field report hit and the
one that cannot opt out, since the deterministic-order validator leaves it no alternative to
`@defaultOrder`.
Asserting on the generated `.orderBy(...)` string is banned by
`docs/architecture/explanation/development-principles.adoc` and would prove nothing about the rows.

## Related

* `roadmap/routine-composition-surface-from-facts.md` (R704): the same symptom at the root
  `@routine` chain, where the model lands `None` and the fix is enforcement plus a classifier
  call. That item's enforcement does not reach this population.
* `roadmap/lookup-unrealized-co-members.md` (R567): the `@lookupKey` grain of the same drop
  (`LauncherCommands.batchedLookupRow`'s empty ordering slot), already filed. This item is the
  non-lookup batched population.
* `roadmap/routine-write-key-capture-unordered.md` (R660): the Mutation routine write path's
  unordered step 2.
* `roadmap/list-ordering-invariant-enforcement.md` (R677): the shared enforcement question this
  item and R704 both pushed out, now filed as R677 and nobody's rider.
* `roadmap/multitable-interface-query-orderby-lowering.md` (R382): the root query field over a
  multitable interface or union, where the arm carries no ordering slot at all. The consumer who
  reported this coordinate reported that one first and reads the two as one bug, so fixing either
  alone leaves their schema unordered at the other end.

Taken together with the first three, the "a list result is never unsorted" invariant currently has
one enforcer (`validateListRequiresOrdering`) keyed on a signal (`OrderBySpec.None`) that three
of the four known leak sites do not produce. Whether the enforcement should re-source off the
launcher relation's ordering slot, where every one of them is visible as `RecordList` with an
absent `Ordering`, is worth deciding once rather than per item; `roadmap/root-family-validator-mirror-gaps.md`
(R558) already proposes that re-sourcing for its own bullet.

