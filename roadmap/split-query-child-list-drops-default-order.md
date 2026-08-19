---
id: R663
title: "@defaultOrder on a @splitQuery child list is dropped at emit"
status: Spec
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-13
last-updated: 2026-08-19
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
`@defaultOrder`. The `@defaultOrder` page says nothing about it either, including after R704's
pass added a constraints bullet to it.

Re-verified against a freshly generated tree after R704's Track A landed: the two rows methods
still carry no sort, and the inline sibling still carries one. Nothing in that item's widening of
`validateListRequiresOrdering` touches this coordinate.

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
  R704's widening of that check has since landed (the `RoutineResolution.Chain` exemption is gone
  and the message forks through `listOrderingDiagnostic` for a function-result terminus), and it
  does not reach this population, for exactly that reason: the signal it keys on is never raised
  here.
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

## Position

A list field's declared order holds wherever the rows are fetched from. `@splitQuery` decides
whether a child's rows ride the parent's statement or a batch of their own; it is a fetch-strategy
directive and has no business changing what the field returns. So the batched arms carry the
ordering their leaf already resolved, and the two questions this item had to settle are settled
here rather than left to the implementer.

**Both batched arms are in scope, the plain one and the lookup-keyed one.** They are two commands
over one leaf type (`ChildField.BatchedTableField`) rendered by one method
(`BatchedRowsFragments.body`), so the render half is shared whether or not the lookup arm is
included. Excluding it would ship a build where `@defaultOrder` works on a batched child unless
the field also carries `@lookupKey`, which is the same class of arbitrary asymmetry this item
exists to remove. `Film.actorsBySplitLookup` in the example schema is the lookup arm's instance and
drops its primary-key order today, verified the same way as the two plain ones. The overlap with
R567 is recorded under "Related": that item keeps the inline lookup arm, the pagination half, and
the production-throw promotion.

**Under `@tenantFanOut` the per-key list is tenant-blocked, and that is the contract.** The fanned
form runs the batch statement once per tenant and merges through the generated
`fanOutBatchRows`, which appends each tenant's rows to the key's bucket in domain order, wraps
each element in a `DataFetcherResult` carrying that tenant as local context, and appends one
shared failure marker per failed tenant. A per-execution `ORDER BY` therefore sorts within a
tenant block and not across blocks. Sorting the merged list afterwards was considered and
rejected: it would unwrap and re-order elements the fan-out deliberately arranged, move the
failure markers, and re-implement SQL collation and null ordering in Java. The rows come from
different databases; a globally sorted merge is a different feature from honouring a declared
order, and nothing here forecloses it.

This is not a new contract. `docs/manual/reference/directives/tenantFanOut.adoc` already publishes
it: results keep each tenant's ordering within that tenant's rows and concatenate tenants in a
stable order, and are not re-sorted globally. The page names `@orderBy` where it should name both
spellings, and the batched child does not currently deliver the promise under either.
`Language.films` in the multitenant fixture is the reachable instance.

## Implementation

The shape to copy is in the tree already, on the arm next door. The batched
discriminated-interface child does exactly what this item asks for the plain batched child:
`LauncherCommands.interfaceChildResultOf` projects `orderingOf(btf.orderBy(), ...)` into its
`ResultShape.RecordList`, and `BatchedRowsFragments.discriminatedBody` declares the sort view
through `OrderingBlock.declareSortView(list.ordering(), baseLocal)` and appends `.orderBy(orderBy)`
to the batch statement when the slot is populated. Its javadoc states the correctness argument
too: one global ORDER BY plus the `__idx__` scatter reproduces the unbatched twin's per-parent
ordering, because the scatter appends rows to their key's bucket in fetch order. That argument is
no longer this item's to establish.

The work is the same pair of edits one arm over.

* **`LauncherCommands.batchedResultOf`** passes `orderingOf(btf.orderBy(), btf.parentTypeName(),
  btf.name(), units)` into the non-connection list arm instead of `null`, the projection its
  connection arm two lines up already performs. The single-record-per-key arm stays unordered,
  where "no ordering" is the honest shape. Its javadoc's "both unordered (the batched
  non-connection emission renders no ordering, a pinned current behaviour)" goes with the change,
  since the sentence is what pinned it.
* **`LauncherCommands.batchedLookupRow`** takes the same projection into its `RecordList`. Its
  one-record-per-key production throw and the mirror gap recorded beside it are untouched.
* **`BatchedRowsFragments.body`** renders the two fragments `discriminatedBody` already renders:
  `OrderingBlock.declareSortView(ordering, prelude.terminalAlias())`, then `.orderBy(orderBy)` on
  the select chain before `.fetch()`, both gated on the list arm carrying a populated slot. One
  placement detail is worth stating because getting it wrong compiles: emit the declaration into
  `body` before the `TenantStrategy.Fanned` fork, not inside the lambda. The prelude declares the
  terminal alias outside the lambda and never reassigns it, so one declaration serves the
  single-tenant and fanned forms alike, and the fanned form's per-tenant statement picks it up by
  closure.

`OrderingBlock.declareSortView` is total over both `Ordering` arms, so an argument-driven
`@orderBy` renders for free at this layer. Whether the classifier admits one on a batched child is
a separate question and not this item's.

Two arms of one fragment family disagreeing on this is itself the evidence that the drop is an
oversight rather than a design.

## Relationship to the facts pivot

The two halves of this fix sit on opposite sides of the line R682 is drawing, and that is worth
stating before an implementer wonders whether to write the fix in the new vocabulary.

The render half is already in the target architecture. `no.sikt.graphitron.render` holds no
`GraphitronSchema` and no fact hierarchy and may not import `plan`, structurally guarded by
`PackageImportDirectionTest`, and the fragment this item adds reads the ordering off the command
row's `ResultShape.RecordList`. That is "emitters read commands" exactly, and nothing in the pivot
touches it. `ResultShape` lives in `command`, which the pivot keeps; only the tier that *produces*
the row changes.

The plan half is written in the vocabulary the pivot dissolves. `batchedResultOf` and
`batchedLookupRow` read the ordering off the leaf, and R682 converts `no.sikt.graphitron.plan` to
derive its command rows from the store instead. So this item's two-line projection is leaf-reading
code with a scheduled end. Three reasons that is the right thing to write anyway:

* Every neighbouring arm in the same method family reads the leaf today, the connection arm two
  lines up included. Writing this one arm against the store would leave the file speaking two
  vocabularies for one decision, which is harder to convert than four consistent arms.
* The fact is already captured (`graphitron_default_order`), so the conversion is a change of
  source, not of shape. Post-pivot the same arm projects the same ordering from a fact instead of
  a getter, and the thing this item establishes, that the arm projects rather than passing `null`,
  is unchanged by the move.
* Ordering is a property of the child coordinate's own query, so it survives the normalization
  R333 describes. What moves there is the split leaf's welded-on parent-key projection, which
  depends on the parent's query; the sort does not.

**Sequencing: this lands before R682, not after.** A faithful re-sourcing of the planner reproduces
current behaviour, and current behaviour here is a hardcoded `null` sitting under a javadoc calling
it "a pinned current behaviour". A conversion that preserved it would be doing its job. Fixing the
drop first means the pivot inherits four arms that agree, with one less special case to carry
across the seam, and no behaviour delta at this coordinate to argue about mid-conversion.

## Tests

* **Command tier**: the batched list row carries a populated ordering slot, asserted for both arms
  (`batchedResultOf`'s plain row and `batchedLookupRow`'s) in `LauncherCommandsPipelineTest`. This
  pins the projection, which is where the fact is dropped today.
* **Execution tier**: the visible defect is row order, so this is what closes the item. Existing
  fixtures cover three of the four shapes: `SplitParent.tags` and `ConverterOrg.campuses` (plain
  batched list, primary-key fallback) and `Film.actorsBySplitLookup` (the lookup arm). The field
  report's shape needs a new one, a view-backed target with no primary key ordered by
  `@defaultOrder(fields:)`, and it is the population that cannot opt out, since the
  deterministic-order validator leaves it no alternative to the directive.
* **The fanned contract** gets its own assertion over `Language.films` in the multitenant fixture:
  rows sorted within each tenant's block, blocks still in domain order. Without it the decision
  recorded under "Position" is folklore, and the next reader of a tenant-blocked list will file it
  as a bug.

One trap. **The execution assertion must be able to fail.** `split_parent_tag` and
`converter_campus` are each seeded in key order with two or three rows per parent, so an unordered
fetch returns them already sorted and the assertion passes against the unfixed generator. Either
seed a row whose insertion order contradicts the sort order, or pick a fixture where the two
already disagree, and confirm the new test fails against the current emit before the fix lands.

**`BatchedChildSqlBaselineTest` will fail, and that is correct.** It freezes whole rendered
statements for this family, `SplitParent.tags` among them, and its javadoc says editing an expected
string is a defect being papered over. That sentence guards the launcher fold, whose promise was
that shape may move and SQL may not. This item is a deliberate SQL change, so the expected strings
gain their `ORDER BY` and the guard stays as written; say so in the commit message so the edit
reads as the intended change rather than as the thing the test exists to catch.

Asserting on the generated `.orderBy(...)` string in a generated *method body* is banned by
`docs/architecture/explanation/development-principles.adoc` and would prove nothing about the rows;
the SQL baseline above is a rendered-statement assertion, which is a different thing and is the
idiom that file already uses.

## User documentation (first-client check)

The rule reads in two sentences, which is the check passing: a `@splitQuery` child list is ordered
by the same `@defaultOrder` an inline child list obeys, because how the rows are fetched does not
change what the field returns; under `@tenantFanOut` the rows arrive grouped by tenant in domain
order and sorted within each group, because each tenant's rows come from its own database.

Neither sentence is new, which is the strongest form of this check passing. Both pages already say
these things: `splitQuery.adoc` pairs `@splitQuery` with `@defaultOrder` in a worked example and
never claimed ordering was dropped, and `tenantFanOut.adoc`'s "Ordering" section already promises
within-tenant ordering with tenants concatenated in a stable order, explicitly including
`@splitQuery` children. The generator is what disagrees with the manual, on both counts. So the
documentation work is one word: that section names `@orderBy`, and should name `@defaultOrder`
beside it, since the fixed-order spelling is the one a fanned batched child is most likely to
carry.

If a reviewer wants a sharper signal that this item is a bug fix and not a feature: the delivery
edits no user-facing prose except to widen one directive name.

## Out of scope

* **Argument-driven `@orderBy` on a batched child.** The render layer takes it for free (see the
  `declareSortView` note above), but whether the classifier admits the directive there is a
  separate question with its own rejection surface.
* **Pagination and windowing at child lookup grain**, and **the inline `LookupMultiset` projection
  arm's dropped ordering**: both R567's, and neither shares a seam with the two edits here.
* **The one-record-per-key batched lookup production throw.** R567 proposes promoting it to a
  located validator rejection. This item touches the surrounding method and deliberately leaves
  the throw alone.
* **Enforcing the never-unsorted-list invariant** (R677). Closing this coordinate removes one of
  its leak sites and does not close the class.

## Related

* `roadmap/routine-composition-surface-from-facts.md` (R704): the same symptom at the root
  `@routine` chain, where the model lands `None` and the fix is enforcement plus a classifier
  call. That item's enforcement does not reach this population.
* `roadmap/lookup-unrealized-co-members.md` (R567): filed the `@lookupKey` grain of this drop
  first. The split agreed here: this item takes `batchedLookupRow`'s ordering slot, because it is
  one call site into the renderer this item is already fixing and splitting it would ship the
  asymmetry described under "Position". R567 keeps the rest of its census, the inline
  `LookupMultiset` arm, the pagination and window half, and promoting the one-record-per-key
  production throw to a located validator rejection.
* `roadmap/routine-write-key-capture-unordered.md` (R660): the Mutation routine write path's
  unordered step 2.
* `roadmap/list-ordering-invariant-enforcement.md` (R677): the shared enforcement question this
  item and R704 both pushed out, now filed as R677 and nobody's rider.
* `roadmap/multitable-interface-query-orderby-lowering.md` (R382): the root query field over a
  multitable interface or union, where the arm carries no ordering slot at all. The consumer who
  reported this coordinate reported that one first and reads the two as one bug, so fixing either
  alone leaves their schema unordered at the other end.

The census that used to close this file has moved to R677, which owns it and keeps it current;
this item is one entry there and should not restate it. What stays here is the local
consequence: closing this coordinate removes one leak site and leaves the invariant unenforced,
so the sketch above is worth landing on its own terms and is not a step toward the invariant.

