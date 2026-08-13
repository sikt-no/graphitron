---
id: R661
title: "Batch the discriminated table interface child through a DataLoader"
status: Spec
bucket: bug
priority: 3
theme: interface-union
depends-on: []
created: 2026-08-13
last-updated: 2026-08-13
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
nothing here. `DeliveryFactVisitor` mints the `DeliveryFacts.Row` unconditionally so the marker
reaches the fact base, and the `TableInterfaceType` arm of `FieldBuilder`'s
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
  cardinality the way the `InterfaceType` arm does. List and connection wrappers mint the batched
  leaf with a `LoaderRegistration`; single cardinality keeps `TableInterfaceField`. The participant
  precondition the sibling applies (at least one `TableBound` participant) has no analogue here: a
  discriminated interface rejects non-table members at the parse boundary, so every participant is
  table-bound by construction. State that as the reason the guard is absent rather than omitting it
  silently.
* A batched sibling leaf for `ChildField.TableInterfaceField`. It carries what the unbatched leaf
  carries plus the parent `SourceKey`, the `KeyLift`, the parent table and result type, and the
  `LoaderRegistration`, mirroring `BatchedInterfaceField`'s component list. Whether this is a new
  record or a delivery slot on the existing one is the implementer's call; the sibling family uses
  separate records, and matching that keeps the sealed switches reading uniformly.
* The rows method. `buildTableInterfaceFieldFetcher`'s body is the starting point with the
  correlation re-keyed: today it equates the child's target side against one `parentRecord`, and
  batched it equates against the key set the loader hands in. `MultiTablePolymorphicEmitter`'s
  batched child is the shape to compare against for the loader plumbing, not necessarily for the
  statement, since this arm has one base table where that one stages a union.
* `warnIfSplitQueryOnRecordParent`'s sibling for this arm, or a generalisation of it: the redundancy
  warning plus the delete fix. Needs its own `LintRule` constant if the existing
  `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` does not fit the wording.
* `forcesSplitDelivery`'s javadoc: add this arm to the list of `@splitQuery`-half readers.

## Open for the implementer

* Whether single cardinality should batch too. The sibling leaves it inline and this item follows,
  but the argument there is that a single-cardinality inline child folds into the parent statement,
  which is not true on this arm. Worth a look before assuming the sibling's boundary transfers; if
  it does not, single cardinality batching is a strictly larger change and belongs in its own item
  rather than being absorbed here.
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
* **The redundancy warning.** Its own case asserting the `LintFinding`, the rule constant and the
  delete fix, modelled on whatever pins `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` today.
* **Execution tier.** The statement-count claim is the whole point and is invisible to every other
  tier. `GraphQLQueryTest`'s `SQL_LOG` idiom over a discriminated interface child across several
  parents: one child statement, not one per parent. A `ProjectionSqlBaselineTest`-style whole-
  statement pin is worth having for the batched form since the correlation shape changes.
* **Regression.** The existing per-parent behaviour stays pinned for single cardinality, so the fork
  is proven to be a fork rather than a wholesale move.

## Retired vocabulary

* Prose asserting that the discriminated interface child is N+1 by construction, or that it registers
  no `DataLoader`. It appears in `roadmap/root-connection-over-discriminated-interface.md`'s child
  section and in this item's own problem statement, and both go stale on landing.
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
