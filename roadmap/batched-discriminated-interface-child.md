---
id: R661
title: "Batch the discriminated table interface child through a DataLoader"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-14
---

# Batch the discriminated table interface child through a DataLoader

## Problem

A child field returning a discriminated table interface (`@table` + `@discriminate`) runs one SELECT
per parent row and offers no way to opt out. `TypeFetcherGenerator.buildTableInterfaceFieldFetcher`
opens with `Record parentRecord = (Record) env.getSource()`, declares the correlation from the hop
(`child.<targetSide> = parentRecord.<sourceSide>`), runs the shared discriminated re-projection and
finishes `step.where(condition).orderBy(orderBy).fetch()`. No `DataLoader` is registered and the
statement is not spliced into the parent's, so the leaf is N+1 by construction.

`@splitQuery`, the directive an author reaches for when a child costs a query per parent, does
nothing here. `DeliveryFactVisitor` mints a `DeliveryFacts.Row` for the coordinate the moment a
marker is present (the canonical constructor rejects an all-false row: a markerless coordinate is
the relation's absence, not a row), so the marker does reach the fact base. The
`TableInterfaceType` arm of `FieldBuilder`'s
`classifyObjectReturnChildField` never reads it: not through `forcesSplitDelivery`, not through
`ctx.facts.delivery().splitQuery`. The sibling plain table-backed arm, a few lines earlier in the
same method, opens with `forcesSplitDelivery(fieldDef)` and forks its whole delivery story on it. So
the author writes the directive, gets the per-parent SELECT anyway, and gets no diagnostic.

The swallowed directive is the symptom that surfaced this. The defect is that the arm has no batched
delivery to ask for.

## Which delivery rule this arm follows

The item was filed as "honour or reject `@splitQuery`". Both framings turn out to be wrong, and the
reason is worth stating because it decides the implementation.

`@splitQuery` means "stop projecting this child into its parent's statement and run it as its own
keyed query". On the plain table child that is a real choice: the inline form is a correlated
subquery folded into the parent's SELECT, pinned by
`ProjectionSqlBaselineTest.multisetChild_correlatedSubqueryInTheParentSelectList`. On *this* arm
there is nothing to split. The re-projection is already its own statement, as the emitter above
shows. The directive's semantics is satisfied before it is applied, and what the author actually
wanted, one query for all parents instead of one per parent, is a different axis the directive does
not name.

The multi-table interface child settled the same question already, and settled it on cardinality
rather than on a marker. `FieldBuilder`'s `InterfaceType` arm reads:

> Delivery is leaf identity: list / connection cardinalities with at least one table-bound
> participant batch through a DataLoader; single cardinality, and the degenerate all-unbound
> participant set (nothing to batch), fetch inline per parent.

and mints `BatchedInterfaceField` with a `LoaderRegistration` on that test alone. No `@splitQuery`
involved. The discriminated interface child is that leaf's near twin, differing in how participants
resolve rather than in how the field is delivered, and it is the only member of the family that
never batches at all.

**Resolution: adopt the sibling's rule. List and connection cardinalities batch through a
`DataLoader`; single cardinality keeps the per-parent fetch. `@splitQuery` on the arm becomes
genuinely redundant and is answered as such.**

That last part has its own precedent rather than needing a new shape.
`FieldBuilder.warnIfSplitQueryOnRecordParent` already handles a marker that names something the arm
does anyway: a `BuildWarning.LintFinding` carrying `LintFix.deleteBareAppliedDirective`, saying the
directive is redundant and will be ignored. The same wording fits here once batching is the default,
and the fix is computable for the same reason (the directive takes no arguments, so deleting the
token never touches an SDL reference).

Rejected alternatives, both of which the "honour or reject" framing implied:

* **Reject `@splitQuery` as unsupported,** mirroring the nesting arm's deferred diagnostic. Correct
  today, obsolete the moment batching lands, and it leaves the actual defect in place while making
  it louder. A rejection-then-lift inside one cluster is churn.
* **Gate batching on `@splitQuery`,** mirroring the plain table child. This makes the N+1 the
  default forever and asks authors to opt out of it with a directive whose stated meaning does not
  describe what it would do here. It would also split the interface family across two delivery
  rules for no reason a reader could reconstruct.

`@tenantFanOut`, the other half of `forcesSplitDelivery`, needs nothing: `TenantBindingIndex` already
rejects it on interface- and union-typed fields ("fans out an interface- or union-typed field: the
polymorphic family is rejected in v1"), and that walk covers child coordinates. So this arm reads the
`@splitQuery` half alone, exactly as the nesting arm, the `@pivot` gate and the routine-chain child
already do, and `forcesSplitDelivery`'s javadoc should gain this arm in its list of half-readers.

## Implementation

* `FieldBuilder`, the `TableInterfaceType` arm of `classifyObjectReturnChildField`: fork on
  cardinality the way the `InterfaceType` arm does. List cardinality mints the batched leaf with a
  `LoaderRegistration`; single cardinality keeps `TableInterfaceField`. Mint on `wrapper.isList()` alone rather than
  mirroring the sibling's `wrapper instanceof FieldWrapper.Connection || wrapper.isList()` clause:
  this arm rejects `FieldWrapper.Connection` at its head with the `Rejection.deferred` that
  `roadmap/root-connection-over-discriminated-interface.md` owns lifting, so the connection half of
  the clause would be a present-but-unreachable branch with no enforcer. That item adds the
  connection half in the same commit that lifts the deferral. Do not touch the guard here. The participant precondition the sibling applies (at
  least one `TableBound` participant) has no analogue here: a discriminated interface rejects
  non-table members at the parse boundary (`TypeBuilder.buildParticipantList`, the
  `interfaceTable != null` arm, errors on any classified non-table implementor, and the same arm's
  fall-through errors on a directiveless one), so every participant is
  `ParticipantRef.TableBacked` by construction. **State the invariant at that name, not the
  sibling's.** `TableBacked` has two arms and the sibling's guard reads only one of them:
  a joined-table discriminated interface populates `ParticipantRef.JoinedTableBound` exclusively
  (the `joined-table-interface` corpus shape, where `Individual` and `Company` each carry a detail
  `@table`), so "every participant is `TableBound`" is false exactly there. Porting the sibling's
  `anyMatch(p -> p instanceof ParticipantRef.TableBound)` verbatim would therefore leave the
  joined-table child unbatched, which is the concrete reason the guard is absent rather than
  restated. `GraphitronType.TableInterfaceType`'s own javadoc currently claims the
  opposite ("Unbound participants (e.g. `@error` types) are recorded as `ParticipantRef.Unbound`",
  copied from the `InterfaceType` / `UnionType` siblings); correct it while stating the invariant,
  since it is the first place a reader would check.
* A batched sibling leaf for `ChildField.TableInterfaceField`. It carries what the unbatched leaf
  carries plus the parent `SourceKey`, the `KeyLift`, the parent table and result type, and the
  `LoaderRegistration`, mirroring `BatchedInterfaceField`'s component list.

  **Mirror the polymorphic sibling's components, not its `implements` clause.**
  `BatchedInterfaceField` is `ChildField, BatchKeyField, ParentRowDemand`; this leaf's unbatched
  twin is `TableTargetField, ParentRowDemand`, and the clause to write is `TableTargetField,
  BatchKeyField, ParentRowDemand`, which is what `BatchedTableField` (`TableTargetField,
  BatchKeyField`) already is for the plain table child. Three things ride on the seal rather than
  on the record shape, so getting the clause wrong sends the edits to the wrong places:
  * `ChildField`'s own `permits` list routes the whole table-target family through the
    `TableTargetField` intermediate seal and never names its members, so the sealed edit is
    `TableTargetField`'s `permits` clause, not `ChildField`'s.
  * `TableTargetField extends SqlGeneratingField`, which is what
    `GraphitronSchemaValidator.validatePaginationRequiresOrdering` keys on.
    `roadmap/root-connection-over-discriminated-interface.md` consumes that keying for its child
    half, so a leaf outside the seal escapes the one check its connection form is about to need.
  * `ProjectionCommands.tableTargetContribution` already answers the batched correlation-key arm
    for any `TableTargetField` whose delivery fact is `Batched`, so joining the seal is what makes
    the parent-projection contribution fall out instead of needing a fresh arm beside the
    polymorphic ones.

  Mirror the component list, not the key
  derivation: `BatchedInterfaceField` keys on the parent's *primary key*, with a `pkCols.isEmpty()`
  rejection, because each participant holds its own FK back to the parent. This arm's key is the
  single FK hop's source side, the columns `TableInterfaceField.parentRowColumns()` already
  reports, which is what `deriveSplitQuerySource` derives for the plain table child's
  `BatchedTableField`. That is also why the sibling's empty-PK rejection has no analogue here.
  Whether this is a new record or a delivery slot on the existing one is the implementer's call;
  the sibling family uses separate records, and matching that keeps the sealed switches reading
  uniformly.
* The rows method. `buildTableInterfaceFieldFetcher`'s body is the starting point with the
  correlation re-keyed: today it equates the child's target side against one `parentRecord`, and
  batched it equates against the key set the loader hands in. `MultiTablePolymorphicEmitter`'s
  batched child is the shape to compare against for the loader plumbing, not necessarily for the
  statement, since this arm has one base table where that one stages a union. Two concrete points
  the re-key runs into:
  * **The re-key is a widening, not a translation.** `buildJoinPathCondition` reads
    `fkJoin.slots().get(0)` and correlates on that one slot; the key the bullet above proposes,
    `parentRowColumns()`, is `On.ColumnPairs.sourceSideColumns()`, which is *every* slot. On a
    composite FK the two disagree, and the batched form is the one that is right. Say which it is
    rather than letting it fall out: either the batched arm correlates on the full slot list (and
    the "Regression" line below is then a statement about the unbatched path only, which is fine),
    or composite FKs are declared out of scope here and the single-slot read carries over.
  * **The key columns have to reach the select list.** `buildTableInterfaceReprojection` takes an
    `alwaysProject` parameter that this call site passes `List.of()` for; the launcher's
    discriminated arm is what uses it. That is the hook for projecting the correlation columns the
    scatter groups by, so the batched form should thread the key columns through it rather than
    appending to the assembled select.
* `warnIfSplitQueryOnRecordParent`'s sibling for this arm, or a generalisation of it: the redundancy
  warning plus the delete fix. Needs its own `LintRule` constant if the existing
  `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` does not fit the wording.
* **`DeliveryFactRelation`, the second delivery-rule site.** Delivery is computed twice: the leaf
  encoding (`DeliveryFact.leafDerivedOf`) and the materialized relation (`DeliveryFactRelation.mint`,
  read through `GraphitronSchema.deliveryOf`), and `DeliveryFactPinTest` requires the two to agree.
  The crosswalk side is compile-forced, its switch being total with no default, so adding the
  batched leaf there cannot be missed; the relation side can. It is also the *only* site that can.
  The other three places that name leaves by class identity are each census-enforced, so a leaf
  missing from them fails the build rather than generating quietly:
  `TypeFetcherGenerator.IMPLEMENTED_LEAVES` by
  `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus`,
  `ProjectionCommands.CONTRIBUTION_MINTING_LEAVES` by
  `ProjectionMembershipTest.everyParentRowReadingLeafIsDeclaredMinting` (which derives the
  demanding population from the seal, so a `BatchKeyField` / `ParentRowDemand` leaf cannot sit
  outside it), and `OperationMembers.DECLARED_SHAPES` by
  `OperationMemberProjectionTest.declaredShapesCoverExactlyTheSealedLeaves`. The
  `LoaderRegistration`-iff-`BatchKeyField` biconditional in `ProjectionMembershipTest` is the
  reason the "delivery slot on the existing record" option above is the harder of the two: a
  nullable registration on `TableInterfaceField` puts it inside `BatchKeyField`, whose
  `sourceKey()` contract has no absent arm.

  **The edit here is a new positive arm, not a flip of the existing negative one.** Read `mint`
  before touching it: its only arms that answer `Batched` are the polymorphic fan-in (keyed on
  `TargetShape.Interface` / `TargetShape.Union`), the record-handed arm (keyed on
  `child.sourceShape() == SourceShape.Record`), and the two marker arms. This leaf reaches none of
  them. `ChildField.target()` gives `TableInterfaceField` a `TargetShape.Table`, and
  `ChildField.sourceShape()` gives it `SourceShape.Table`. So flipping
  `singleTableBackedVerdict`'s `TableInterfaceType -> false` to `true` does not make the relation
  answer `Batched` for the plain list coordinate at all: `tableAnchoredChild` is a conjunct, never a
  verdict, and the coordinate falls through to `Inline` exactly as before. What the flip *does*
  reach is the marker arm, `markers.splitQuery() && tableAnchoredChild`, which then answers
  `Batched(Trigger.Authored)` at *both* cardinalities. Three divergences from the one edit: the
  plain list coordinate (relation `Inline`, leaf `Batched`), the `@splitQuery`-marked list
  coordinate (relation `Authored`, leaf `PolymorphicFanIn`), and the `@splitQuery`-marked single
  coordinate (relation `Batched`, leaf `Inline`). The last two are live because this item's own
  resolution makes `@splitQuery` warn-and-ignore rather than reject, so the marked coordinate stays
  author-reachable and both sites compute it.

  Mint the arm the way line-for-line sibling `unwrapped instanceof TargetShape.Interface ||
  TargetShape.Union` is minted: a `TableInterfaceType`-keyed arm on the cardinality rule, placed
  *ahead* of the marker reads so the redundant marker cannot claim the trigger. Both sides then name
  `Trigger.PolymorphicFanIn`, which is what the sibling pair uses for the same
  cardinality-plus-participants rule.

  Leave `singleTableBackedVerdict`'s `ConnectionType` sub-clause alone, for the same reason the
  classifier fork above leaves the `FieldWrapper.Connection` half alone: the connection coordinate
  is rejected at the arm's head today, so the sub-clause is a present-but-unreachable branch with
  no enforcer, and `roadmap/root-connection-over-discriminated-interface.md` states that it verifies
  both delivery sites for the connection coordinate in the commit that lifts the deferral. Whether
  the `TableInterfaceType -> false` case in `singleTableBackedVerdict` needs any edit at all falls
  out of where the new arm lands: if the arm precedes the `tableAnchoredChild` computation, the
  case stays correct as written and only its javadoc rationale ("the single-table interface child,
  whose only delivery is inline") needs rewriting.

  Fix it in place and stop there. That the relation encodes delivery as
  negative space, an enumeration of what does not batch that every new batched shape has to be
  edited into, is a defect in its own right, and
  `roadmap/delivery-verdict-derives-from-the-store.md` owns it. This item is not the place to
  restructure the site; it is one of the two reasons that item exists.
* The javadoc that lists the `@splitQuery`-half readers. There are three sites, not one.
  `DeliveryFacts`' *class* javadoc carries the list in prose (naming the pivot gate and the
  nesting-projection deferral) and `{@link}`s `Row#splitQuery`, which is a bare record component
  with no javadoc of its own; `DeliveryFacts.splitQuery(GraphQLFieldDefinition)`'s one-liner repeats
  it as "(the pivot / nesting-deferral half)"; and `FieldBuilder.forcesSplitDelivery`'s javadoc
  carries the longest and most-read version of the list ("the nesting arm's deferred rejection, the
  `@pivot` batching gate and the routine-chain child read the `@splitQuery` half"). Add this arm to
  all three. Note that its read is the nesting arm's kind exactly: a marker read that feeds a
  diagnostic, not one that gates delivery.

## Open for the implementer

* Whether single cardinality should batch too. The sibling leaves it inline and this item follows,
  but check what that boundary rests on before assuming it transfers, because the obvious
  justification is not available. It is *not* that a single-cardinality inline child folds into its
  parent's statement: `ChildField.InterfaceField`'s javadoc says the inline arm "fetches per parent
  (no DataLoader)", and `ProjectionCommands` gives it a `correlationKeyArm` rather than a
  `Multiset` call, so the sibling's single-cardinality delivery is per-parent exactly as this arm's
  is. Neither leaf records a rationale for the boundary. If there is none, single cardinality
  batching is a strictly larger change and belongs in its own item rather than being absorbed here.
* Whether the ordering the unbatched fetcher applies (`buildOrderByCode` off `tif.orderBy()`)
  survives the loader boundary unchanged, or needs the per-key windowing the batched connection uses.
  Unpaginated batching may be able to order globally and group by key; confirm rather than assume.

## Coverage

* **Classification.** The cardinality fork at the unit tier: a list-returning discriminated interface
  child mints the batched leaf with its `LoaderRegistration`, a single-returning one keeps the
  unbatched leaf. `GraphitronSchemaBuilderTest`'s enum rows are the home its
  `SPLIT_QUERY_ON_NESTING_DEFERRED` sibling uses; a pure-verdict row may instead belong in
  `ClassifiedCorpus` per the `classified-corpus` skill, which the implementer should check rather
  than defaulting to the enum.
* **Delivery-fact agreement.** `DeliveryFactPinTest` is the gate on the two-site change above, but
  only over coordinates its domain actually contains, and the domain has no list-cardinality
  discriminated interface child today: `ClassifiedCorpus`'s `table-interface` example carries
  `media: MediaItem` at single cardinality and `joined-table-interface` exercises a root, not a
  child. Without a list-cardinality child in the corpus (or in the test's own marker fixture) the
  relation can keep answering `Inline` while the leaf says `Batched` and the build stays green.
  Adding that coordinate is what turns the pin into a gate, so it is not optional coverage.
  **Three coordinates, not one**, because the relation's marker arm reads `@splitQuery`
  independently of the cardinality fork and the divergences enumerated above are exactly the
  marked ones: a list-cardinality child, a list-cardinality child *carrying* `@splitQuery`, and a
  single-cardinality child carrying `@splitQuery`. `DeliveryFactPinTest`'s own `MARKER_FIXTURE` is
  the natural home for the two marked ones (it exists for precisely this, marker coverage beside a
  corpus that is thin on markers), and it keeps the redundancy warning out of the corpus, where a
  marked coordinate would have to be reconciled with the `@classified` verdict rows. The test's
  per-trigger floors should gain nothing; `PolymorphicFanIn`'s floor already covers the new arm.
* **The redundancy warning.** Its own case asserting the `LintFinding`, the rule constant and the
  delete fix, modelled on whatever pins `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` today.
* **Execution tier.** The statement-count claim is the whole point and is invisible to every other
  tier. `GraphQLQueryTest`'s `SQL_LOG` idiom over a discriminated interface child across several
  parents: one child statement, not one per parent. A `ProjectionSqlBaselineTest`-style whole-
  statement pin is worth having for the batched form since the correlation shape changes. Give the
  execution case a participant carrying a **cross-table field**. That is what distinguishes this
  arm's statement from the plain batched child's: `buildTableInterfaceReprojection` folds the
  per-participant capped correlated subselects and conditional joins into the select list, and
  `ParticipantColumnReferenceField`'s fetcher reads the values back off the record by alias. Under
  batching that record now arrives from the loader's scatter rather than from a per-parent fetch,
  and nothing in the classification or delivery-fact tiers can see whether the aliases survived.
* **Regression.** The existing per-parent behaviour stays pinned for single cardinality, so the fork
  is proven to be a fork rather than a wholesale move.

## Retired vocabulary

* Prose asserting that the discriminated interface child is N+1 by construction, that it registers
  no `DataLoader`, or that its only delivery is inline. Five live sites:
  `roadmap/root-connection-over-discriminated-interface.md`'s child section; this item's own problem
  statement; `ClassifiedCorpus`'s polymorphic preamble comment, which calls the per-parent query "a
  known defect" the corpus deliberately does not assert;
  `DeliveryFactRelation.singleTableBackedVerdict`'s javadoc, which names "the single-table interface
  child, whose only delivery is inline" as the discriminating fact for its `false` case; and
  `docs/architecture/reference/code-generation-triggers.adoc`, whose polymorphic-rule section
  parenthesises the child as "`TableInterfaceField`, inline, target shape `Table`". The middle two
  sit in code the change touches anyway, so they are sweep targets rather than stragglers; the
  published doc is the one a reader outside the change would hit, and it needs the cardinality fork
  stated rather than the word deleted.
* Nothing in main sources retires by name: `buildTableInterfaceFieldFetcher` survives for single
  cardinality unless the open question above moves it.

## Out of scope

* **Pagination.** `roadmap/root-connection-over-discriminated-interface.md` owns `@asConnection` at
  both coordinates and rides this item's batched delivery for its child half.
* **The root coordinate.** A root field has no parent to batch across; `@splitQuery` there is a
  separate question this item does not open.

## Provenance

Surfaced during the Spec review of `roadmap/root-connection-over-discriminated-interface.md`, which
reopened over whether pagination rides a split query. Filed as the swallowed-directive bug; specing
it found the swallow to be a symptom of the missing delivery rule, so the item is now the delivery
rule and the directive falls out of it as redundant. The child item that first noted the swallow, as
a splittable open question, was discarded into the connection item.
