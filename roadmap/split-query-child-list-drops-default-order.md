---
id: R663
title: "@defaultOrder on a @splitQuery child list is dropped at emit"
status: In Progress
bucket: bug
priority: 3
theme: codegen-correctness
depends-on: []
created: 2026-08-13
last-updated: 2026-09-01
---

# @defaultOrder on a @splitQuery child list is dropped at emit

A child list field carrying `@splitQuery` and `@defaultOrder` resolves the directive into an
`OrderBySpec.Fixed` on the leaf and then emits a batch query with no `ORDER BY`. The declared
sorting contract is discarded between the model and the generated SQL, and nothing warns.

Found while reviewing the root-routine ordering drop, fixed at the root `@routine` chain by R704
(`routine-composition-surface-from-facts`, shipped; see `roadmap/changelog.md`). This is the
sibling instance at the batched child
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

**Sequencing: R682 goes first, and that is the better order.** An earlier draft of this section
argued the opposite, that fixing the drop first would hand the pivot four arms that agree. That
argument ignored what R682's gate is. R682 holds generated output *byte-identical* across every
increment, asserted against checked-in pipeline-tier expectations, and that invariant is the whole
reason a five-thousand-line conversion is reviewable. This item deliberately changes generated
output, and `BatchedChildSqlBaselineTest` freezes whole rendered statements for precisely the
family R682 converts. Landing a deliberate SQL change into that window would move the one
instrument the conversion is measured with, leaving its reviewer unable to tell "the SQL moved
because the conversion broke something" from "the SQL moved because another item intended it to".
Waiting keeps that instrument clean, and buys this item a base where the fix is written once in the
final vocabulary instead of as leaf-reading code the pivot then converts.

Two consequences follow, and neither is a problem.

R682 cannot absorb or obscure this bug. Byte-identical output is forbidden from fixing it, so the
drop survives the conversion by construction, and the diagnosis above stays true whatever the plan
tier ends up reading. Nothing needs re-checking for correctness.

The plan-half anchors will move, and the implementer re-derives them at pickup. The three bullets
above describe the tree before the batched-child family converts:
`batchedResultOf` / `batchedLookupRow` reading `btf.orderBy()` through `orderingOf` is
pre-conversion vocabulary. What the fix *is* does not move with them, and that is what the Ready
sign-off covers: the two batched arms project the coordinate's ordering rather than passing `null`,
and the renderer renders it off the command row. The render half needs no re-derivation at all,
since R682's recipe moves emitters toward the shape that fragment already has.

One reopen trigger, stated so the judgment is not left implicit. If R682's conversion of this
family lands the ordering as a join over the command relation rather than as a per-arm projection,
the "two arms, two edits" framing dissolves and the shape of the work changes rather than its
anchors. That is a Ready to Spec reopen at pickup, not a plan edit. If the arms survive as arms,
re-anchoring is a plan edit and the sign-off stands.

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
The same requirement covers the other two assertions: `film_actor` seeds with ascending actor ids
per film, so the lookup-arm assertion over `Film.actorsBySplitLookup` passes vacuously the same
way, and the fanned assertion over `Language.films` needs the within-tenant check to bite against
a tenant database whose row order disagrees with the sort.

**`BatchedChildSqlBaselineTest` will fail, and that is correct.** It freezes whole rendered
statements for this family, `SplitParent.tags` among them, and its javadoc says editing an expected
string is a defect being papered over. That sentence guards the launcher fold, whose promise was
that shape may move and SQL may not. This item is a deliberate SQL change, so the expected strings
gain their `ORDER BY` and the guard stays as written; say so in the commit message so the edit
reads as the intended change rather than as the thing the test exists to catch.

Asserting on the generated `.orderBy(...)` string in a generated *method body* is banned by
`docs/architecture/principles/development-principles.adoc` and would prove nothing about the rows;
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

## Delivery notes

Two departures from the plan above, both recorded here rather than left for a reviewer to
discover in the diff.

**The sequencing was not observed.** "Relationship to the facts pivot" argues that R682 should
land first, so that a deliberate SQL change does not move the instrument that conversion is
measured with. R682 is a long-running architecture item whose slices ship as separate items, and
the user directed this one be picked up now. The reopen trigger that section states did not fire:
`batchedResultOf` and `batchedLookupRow` were still reading the leaf's ordering slot at pickup, so
the "two arms, two edits" framing held and the anchors needed no re-derivation. What the
conversion inherits instead is a baseline whose three list-arm strings changed once, deliberately,
with the reason written into the file's own javadoc; that is a documented step, not an
unexplained diff, and the remaining strings still hold the fold's promise.

**The keyless-target fixture is a table, not a view.** The field report's target was
view-backed, and the plan's test section names a view. What makes that population unable to opt
out is the *missing primary key*: with no key to fall back on, `validateListRequiresOrdering`
compels the very `@defaultOrder` the emission discarded. A view is one way to have no primary
key; a table declared without one is another, and it reproduces the case exactly while costing no
jOOQ synthetic-key codegen configuration and introducing no unrelated question about whether
view-backed targets are supported elsewhere. The fixture (`role_holder` / `role_assignment`,
reached through `RoleHolder.roles` and `RoleHolder.rolesByCode`) states this in its own comment.

**The fanned assertion re-shaped its fixture rather than adding one.** Proving the tenant-blocking
contract needs one parent with rows in more than one tenant, and needs the tenants' sort keys to
interleave, or a globally re-sorted merge would produce the same answer as the contract. The
multi-tenant fan-out fixture's films now share one language across both tenants with interleaved
ids, so storage order, a global re-sort and the published contract are three distinguishable
answers. The existing `filmsEverywhere` pins were unaffected: they assert on titles, and the
within-tenant sort order of those titles did not move.

Every assertion the delivery added or tightened was confirmed red against the unfixed generator
before the fix was restored, which is what the plan's "the execution assertion must be able to
fail" trap asks for. The fanned one fails on the within-tenant sort, the two keyless-target ones
on the declared sort, and the three baseline strings on the added `ORDER BY`.

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

* R704 (`routine-composition-surface-from-facts`, shipped; entry in `roadmap/changelog.md`): the
  same symptom at the root `@routine` chain, where the model lands `None` and the fix was
  enforcement plus a classifier call. That item's enforcement does not reach this population.
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


## Reviewer findings

### Round 1 (2026-09-01, In Review -> Done, reviewer session 016r4si4JkqLgswbafZcLiyw)

Verdict: withhold; status back to `Ready`. One blocking finding on the third gate question (is the
implementation correct *and* the change the spec approved), plus two observations that are not
blocking.

The delivery is the change this plan approved, and the parts it names are right. `batchedResultOf`
and `batchedLookupRow` project the coordinate's ordering where they passed `null`;
`BatchedRowsFragments.body` declares the sort view and appends `.orderBy(orderBy)`, with the
declaration emitted before the `TenantStrategy.Fanned` fork so one declaration serves both tenancy
shapes by closure, exactly as "Implementation" specified. The single-record-per-key arm stays
unordered. The command-tier assertion pins both list arms; the keyless-target execution fixture is
seeded so an unfixed emission returns `ZETA, ALFA, MIKE` and the assertion rejects it; the fanned
assertion's seed distinguishes storage order, a global re-sort and the published contract, which is
what makes it a proof of the contract rather than a restatement. Six SQL baseline strings moved
deliberately with the reason written into `BatchedChildSqlBaselineTest`'s own javadoc. Both
departures under "Delivery notes" are sound: the sequencing was the user's call and the plan's
reopen trigger genuinely did not fire (both arms were still reading the leaf at pickup), and a
keyless *table* reproduces the reported population exactly, since the missing primary key is what
compels the directive, not the view-ness. A full `mvn install -Plocal-db` is green on the delivered
tree; I ran it.

**Finding 1 (blocking). The projection is total over `OrderBySpec`, but the helper emission is
not, so an `@orderBy` argument on a batched child list now emits a call to a method that is never
generated.**

`orderingOf` maps `OrderBySpec.Fixed` to `Ordering.Columns` and `OrderBySpec.Argument` to
`Ordering.Helper`. `OrderingBlock.declareSortView` is total over both arms, which is what "Out of
scope" means by the render layer taking argument-driven ordering "for free": the fragment renders
`List<SortField<?>> orderBy = <field>OrderBy(env, <alias>).sortFields();`. But
`TypeFetcherGenerator` emits that helper for a `ChildField.BatchedTableField` only when the
wrapper is `FieldWrapper.Connection`. The list-shaped arms this item just wired have no such
emission, and nothing rejects the directive there: `validateBatchedTableField` guards only the
Connection-shaped empty-ordering case and the lookup-connection verdict, and the leaf's ctor bars
`Argument` only on routine-node paths. The generated fetchers class therefore does not compile.

Verified rather than reasoned. A schema with
`actorsOrdered(order: [ActorOrderBy] @orderBy): [Actor!]! @splitQuery @defaultOrder(primaryKey:
true) @reference(...)` generates a `FilmFetchers` whose `rowsActorsOrdered` calls
`actorsOrderedOrderBy(env, a1)` while the class holds no method of that name; the `@lookupKey`
sibling behaves identically. Against `LauncherCommands` as it stood before this delivery, the same
schema emitted no `orderBy` at all. So this is a new failure mode introduced here, not a
pre-existing one surfaced.

The "Out of scope" bullet does not cover it. It defers *whether the classifier admits the
directive*, having already asserted the render layer takes it for free. The classifier does admit
it, which is what makes the free-ness claim false: the render layer takes it for free only where
the helper exists. The combination is not an exotic one either. `orderBy.adoc` places no
restriction on which fields may carry the argument, `defaultOrder.adoc` publishes the
`@defaultOrder`-plus-`@orderBy` coexistence on the same field, and `splitQuery.adoc` pairs
`@splitQuery` with `@defaultOrder` in its own worked example.

The tree already treats this exact situation as a defect to close rather than a gap to record. The
comment beside the interface-root arm in the same loop says a prior state of the code "spelled the
call inline while nothing emitted the helper, so an `@orderBy` argument on this coordinate produced
uncompilable output. Emitting it here closes that gap." The batched discriminated child's arm
below it was added for the same reason.

What would satisfy this: drop the `wrapper() instanceof FieldWrapper.Connection` conjunct from that
`else if`, so a `BatchedTableField` carrying an `Argument` ordering gets its helper whatever its
wrapper. I confirmed the one-conjunct removal makes both helpers appear for the schema above. Then
add a fixture carrying the combination to the example schema, because the compilation tier is what
should have caught this and no coordinate in the tree pairs `@splitQuery` with an `@orderBy`
argument at list cardinality: every existing `@orderBy` on a split child sits on a connection.
Rejecting the combination with a located validator verdict is the other coherent arm, but it costs
more than the widening and takes away a combination the manual currently permits.

**Observation 1 (not blocking). The fan-out fixture reshaping cost one axis of the routing
proof.** Moving `T2 Gamma` to language 901 leaves `FanOutLangB` with no films at all, so the test
that once showed two distinct parents each receiving rows from a distinct database now shows one
populated parent and one empty bucket. Mis-routing is still caught (a stray row makes `LangB`
non-empty), so this is a weakening rather than a hole, and the interleave the tenant-blocking
assertion needs is real. Seeding one additional tenant-2 film under 902 would restore the
two-populated-parents discrimination without disturbing the interleave.

**Observation 2 (not blocking), for the changelog entry at Done.** Because
`OrderByResolver.resolveDefaultOrderSpec` falls back to the primary key, this change adds an
`ORDER BY` to *every* list-shaped batched child statement in every consumer build, not only to
coordinates that authored a directive; the two `ConditionSqlBaselineTest` strings that moved are
that population, not authored ones. "Diagnosis" names the population, so this is the approved
change and not a scope question, but the SQL that consumers see changes more widely than the
item's title suggests and the entry should say so.
