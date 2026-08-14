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
  BatchKeyField`, exactly what `BatchedTableField` already is for the plain table child. Three
  things ride on the seal rather than on the record shape, so getting the clause wrong sends the
  edits to the wrong places:
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

  `ParentRowDemand` drops off the clause, and that is deliberate rather than an oversight to
  correct back. The batched arm of `tableTargetContribution` precedes its `ParentRowDemand` arm and
  casts `(BatchKeyField) ttf` to read `sourceKey().columns()`, so the demand accessor is never
  reached on a leaf that is both; the two answer the same column list here anyway, since this arm's
  key *is* the FK hop's source side. `ProjectionMembershipTest`'s minting census takes
  `BatchKeyField` or `ParentRowDemand`, so `BatchKeyField` alone satisfies it. Declaring the
  capability would therefore buy nothing and would falsify the live comment above that arm ("within
  the table-target seal, exactly the twin declares one"), which no gate catches. If a later reason
  to declare it appears, that comment joins the sweep.

  Mirror the component list, not the key
  derivation: `BatchedInterfaceField` keys on the parent's *primary key*, with a `pkCols.isEmpty()`
  rejection, because each participant holds its own FK back to the parent. This arm's key is the
  single FK hop's source side, the columns `TableInterfaceField.parentRowColumns()` already
  reports, which is what `deriveSplitQuerySource` derives for the plain table child's
  `BatchedTableField`. That is also why the sibling's empty-PK rejection has no analogue here.
  `deriveSplitQuerySource` is likely the mint call rather than merely the precedent: it returns the
  `SourceKey`, the `KeyLift` and the `LoaderRegistration` together off a `ParentCorrelation`, and
  both the plain table child and the batched pivot mint through it. That also settles the loader's
  container and dispatch, which this bullet otherwise leaves unstated.
  Whether this is a new record or a delivery slot on the existing one is the implementer's call;
  the sibling family uses separate records, and matching that keeps the sealed switches reading
  uniformly.
* The rows method. `buildTableInterfaceFieldFetcher`'s body is the starting point with the
  correlation re-keyed: today it equates the child's target side against one `parentRecord`, and
  batched it equates against the key set the loader hands in. `MultiTablePolymorphicEmitter`'s
  batched child is the shape to compare against for the loader plumbing, not necessarily for the
  statement, since this arm has one base table where that one stages a union.

  The re-projection itself needs no rework, and that is worth knowing before starting rather than
  discovering. `DiscriminatedTableFragments` says of itself that it "knows nothing about the fetch
  cardinality", and the two things that could have coupled it to a single parent do not: every
  cross-table participant scalar rides the select list as a capped correlated subselect against
  *the base row*, never against the parent, and every join it emits is the joined-detail 1:0..1 hop
  whose FK columns are the detail's own primary key. One base row is one entity regardless of how
  the WHERE was keyed, which is the same property that lets the paginating caller put `.limit()` on
  this step. So widening the correlation from one parent key to a key set cannot disturb the select
  list, and the alias-survival question under Coverage is a confirming test rather than a risk.
  Two concrete points the re-key does run into:
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
* **Tenancy: the loader name has to partition the batch.** This is the one edit on the list whose
  omission is both silent and unsafe, so do it deliberately rather than discovering it.

  A discriminated interface child under a tenant-scoped parent classifies
  `TenantBinding.Inherited` today: `TableInterfaceField.domainReturnType()` is a
  `DomainReturnType.Record` of the base `@table`, so `TenantBindingIndex`'s `reachedTables` sees a
  tenant-scoped table and the inherited arm fires when the parent carries tenant context. Unbatched
  that is correct without anyone thinking about it, because `buildTableInterfaceFieldFetcher`
  resolves the DSL inline per parent (`TenantDslEmitter.resolve`, the `Inherited` arm's
  localContext-divined read) and each parent's fetch runs on its own tenant's connection.

  Batched, the per-parent read is gone and the tenant rides the *loader registration* instead. The
  hazard is stated at the seam itself, in `ConnectionRuntimeClassGenerator.tenantLoaderName`'s
  javadoc: "a batch loader resolves one `DSLContext` from the environment captured at loader
  creation, so a tenant-mixed loader would execute every key against the first key's tenant." One
  batch spanning two tenants therefore serves one tenant's rows to the other. The existing batched
  leaves avoid this by naming their loader through
  `TenantDslEmitter.loaderNameDeclaration`, whose `Inherited` arm returns
  `TenantConnections.tenantLoaderName(env)` rather than the bare path-derived `loaderName(env)`, so
  each batch stays tenant-homogeneous. `TenantBindingIndex` already states the rule in prose for the
  shape this arm is joining: a `@splitQuery` child "partitions per tenant through the loader-name
  seam".

  So: route this leaf's loader registration through `loaderNameDeclaration`, the same argument every
  other batched fetcher builder passes. Note that it is passed per site, not structurally forced, so
  a fresh emission path that spells the loader name itself compiles and passes every census in this
  item. Nothing else here catches it: the leaf-identity rosters check membership, not the name
  expression, and the delivery pin compares verdicts, not emitted SQL. Decide the same question for
  the `@tenantFanOut` half only if the scatter choice below lands on the polymorphic machinery,
  which resolves tenancy differently; on the shared machinery the seam above is the whole answer.
* `warnIfSplitQueryOnRecordParent`'s sibling for this arm, or a generalisation of it: the redundancy
  warning plus the delete fix. Needs its own `LintRule` constant if the existing
  `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` does not fit the wording.
* **`DeliveryFactRelation`, the second delivery-rule site.** Delivery is computed twice: the leaf
  encoding (`DeliveryFact.leafDerivedOf`) and the materialized relation (`DeliveryFactRelation.mint`,
  read through `GraphitronSchema.deliveryOf`), and `DeliveryFactPinTest` requires the two to agree.
  The crosswalk side is compile-forced, its switch being total with no default, so adding the
  batched leaf there cannot be missed; the relation side can. It is also the *only* site that can
  do so *quietly*. The other three leaf-identity rosters are each census-enforced, so a leaf missing
  from them fails the build:
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
  `sourceKey()` contract has no absent arm. The scatter-helper emission gates in
  `TypeFetcherGenerator` also name a leaf by class identity and are not census-enforced, but they
  fail loudly rather than quietly (a rows method calling an unemitted helper does not compile);
  whether they need widening at all is the scatter question under "Open for the implementer". The
  launcher payload dispatch is loud in the same way and is not optional; the bullet below owns it.

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
* **`LauncherCommands`, the second consumer of the `Batched` fact.** Exactly two sites in main
  sources read `DeliveryFact.Batched`. One is `ProjectionCommands.tableTargetContribution`, which
  the seal bullet above already answers. The other is `LauncherCommands.verdictOf`, and this item
  has to answer it too, because the relation edit above reaches it whether or not anything else in
  the plan changes.

  `verdictOf` computes its `anchored` conjunction as a `SELECT` member on a `TargetShape.Table`
  target, and this leaf satisfies both halves already: `OperationMembers` declares `Kind.SELECT`
  required for `TableInterfaceField`, and `ChildField.target()` gives it `TargetShape.Table`, the
  same two facts the relation analysis above turns on. Today the coordinate's delivery is `Inline`,
  so the verdict is `Launch.NONE` and no launcher row is minted. The moment the relation answers
  `Batched`, the verdict becomes `Launch.BATCHED_CHILD_CATALOG` and `batchedChildRow`, whose arms
  are `BatchedTableField` and `BatchedPivotField`, falls through to its `default` and throws
  ("received a batched child catalog launch verdict but has no payload arm here; the membership
  predicate and this payload dispatch have drifted"). The schema-free walk,
  `produceWithoutSchema`, has the same shape and the same throw. Both walks run on every
  generation, from `EmitPlan` and from `TypeFetcherGenerator`. The polymorphic batched pair escapes
  this only because its target shape is `Interface`, which is exactly the exclusion `verdictOf`'s
  javadoc states; the discriminated child, carrying `TargetShape.Table`, lands inside the family
  rather than beside it.

  So the edit is a payload arm plus a `LaunchSource` for it, and which source arm is a fork worth
  settling here rather than at the keyboard:
  * `INVOCATION_BY_SOURCE` maps `LaunchSource.DiscriminatedTable` to `Invocation.Direct`, and the
    map is keyed by source class, so the discriminated root's own arm cannot also carry a batched
    child. That map is census-enforced against `LaunchSource`'s sealed leaves by
    `LauncherMembershipTest.invocationDeterminationIsTotalOverTheSourceArms`, and every produced
    row is checked against the declaration by
    `LauncherAxisPins.assertInvocationMatchesDeclaredDetermination`, so a new arm cannot be added
    without declaring its invocation. This is the one part of the launcher edit that is
    census-caught rather than throw-caught.
  * **Reuse `LaunchSource.CorrelatedChain`,** the plain batched child's arm: the table, the type
    class, the `joinPath` and a `ParentCorrelation`, paired with `Invocation.Batched(sourceKey,
    loaderRegistration)`. This is the cheap route and it is what the shared-scatter branch of the
    open question below implies. It carries a consequence back into the record bullet above:
    `CorrelatedChain` demands a `ParentCorrelation`, a component `BatchedTableField` carries and
    `BatchedInterfaceField` does not, so on this branch "mirror the sibling's component list" is
    not sufficient and the correlation component comes along too.
  * **Mint a discriminated batched source arm of its own.** This keeps the re-projection's assembly
    reachable from the launcher, which is what `LaunchSource.DiscriminatedTable` exists to carry, at
    the cost of a new sealed leaf and the renderer arm that goes with it.

  This revises the rows-method bullet above rather than sitting beside it.
  `buildTableInterfaceFieldFetcher` is one of the two discriminated consumers that
  `DiscriminatedTableFragments` names as not yet migrated onto the launcher seam, so "start from its
  body" reads as staying off that seam. A `Batched` delivery mints a launcher row whichever assembly
  emits the statement, so staying off the seam is no longer free. Settle the source arm first; how
  much of the legacy body survives falls out of it.
* **The author-facing directive page.** `docs/manual/reference/directives/splitQuery.adoc`'s
  Constraints list is where an author learns what the directive does on each shape, and it already
  carries the redundancy population this arm is joining: the class-backed-parent bullet ("redundant
  but not rejected"), and the nesting bullet, which explicitly draws "the line between the
  positionally inert cases on this page" between *redundant and at most warned about* and
  *unimplemented and rejected*. This item's resolution puts the discriminated interface child on the
  redundant side of exactly that line, so it needs a bullet there, phrased against the line the page
  already draws. Nothing renders or gates this file. The item's whole author-visible surface change
  is what `@splitQuery` now means on this arm, so this is the deliverable a schema author actually
  reads, not an afterthought to the javadoc below.

  Also regenerate `docs/manual/_generated/supported-schema-shapes.adoc`, which is keyed per sealed
  leaf and so gains a row for the batched leaf. `roadmap/root-connection-over-discriminated-interface.md`
  names this item as where that row arrives. It regenerates by running
  `roadmap-tool leaf-coverage . --mode=migration` over the classifier traces a prior `mvn verify`
  leaves behind (the `leaf-coverage` profile that emits them is on by default, activated by
  negation on `-Dleaf-coverage.skip`, so no `-P` is needed to get them). Its drift check is a manual
  `--verify`; CI runs only the non-migration variant, so a stale file will not fail anything.
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
* **Which scatter the batched leaf uses.** This one has a consequence outside the item, so settle it
  before writing the rows method. Two machineries exist. The shared one is
  `SplitRowsMethodEmitter`'s `scatterByIdx` / `scatterSingleByIdx` / `scatterConnectionByIdx`
  helpers, whose emission gates in `TypeFetcherGenerator` name `ChildField.BatchedTableField` by
  class identity (the list gate and the connection gate; the single gate reads the
  `emitsSingleRecordPerKey` capability instead). The other is the polymorphic pair's, which inlines
  its own scatter, renders through no launcher row, and is why `BatchedInterfaceField` overrides
  `emitsSingleRecordPerKey()` to a stated `false`. The bullet above points at
  `MultiTablePolymorphicEmitter` for the loader plumbing, which reads as the second;
  `roadmap/root-connection-over-discriminated-interface.md` has already assumed the first, planning
  its connection half around the scatter gate widening "from 'any `BatchedTableField` with a
  Connection wrapper' to include the interface leaf". Pick one and say so. On the shared machinery
  both class-identity gates need widening and `emitsSingleRecordPerKey()` needs a deliberate answer
  rather than an inherited default; neither gate is census-enforced, though a rows method calling a
  helper that was never emitted fails to compile, so this is discoverable at the compile tier rather
  than silent. On the inlined machinery neither edit applies and that item's child half is planned
  against the wrong seam. Settle it together with the launcher source arm above: the launcher's
  batched-child family *is* the shared machinery, so picking `CorrelatedChain` there is close to
  picking the shared scatter here, and picking a discriminated source arm there is close to picking
  the inlined one. Answering the two independently is how they end up contradicting each other.
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
* **The launcher relation.** `LauncherMembershipTest` already gates the census in both directions
  and `LauncherAxisPins` gates the invocation determination, so the list-cardinality coordinate
  added for the delivery pin above carries the launcher edit into those gates for free, provided
  the coordinate reaches the fixtures those tests classify. Check that it does rather than assuming
  the delivery-pin corpus addition is enough; if the two tests read different fixture sets, the
  launcher gate needs its own coordinate.
* **The redundancy warning.** Its own case asserting the `LintFinding`, the rule constant and the
  delete fix, modelled on whatever pins `SPLITQUERY_REDUNDANT_ON_RECORD_PARENT` today.
* **Tenant partitioning.** Assert the emitted loader name, not just that a loader exists: a
  tenant-scoped discriminated interface child registers through `tenantLoaderName`, not the bare
  path-derived name. Cheapest at whatever tier already reads emitted fetcher source for the other
  batched leaves, since the failure is in the name expression rather than in any verdict. This is
  the only listed failure that is both silent and a cross-tenant read, so it should not rest on the
  execution tier alone, where it needs a two-tenant fixture to show up at all.
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
  no `DataLoader`, or that its only delivery is inline. Four live sites, two of them in one file:
  this item's own problem statement; `ClassifiedCorpus`'s polymorphic preamble comment, which calls
  the per-parent query "a known defect" the corpus deliberately does not assert;
  `DeliveryFactRelation.singleTableBackedVerdict`'s javadoc, which names "the single-table interface
  child, whose only delivery is inline" as the discriminating fact for its `false` case; and
  `docs/architecture/reference/code-generation-triggers.adoc` twice over. Its polymorphic-rule
  section parenthesises the child as "`TableInterfaceField`, inline, target shape `Table`", and
  thirty lines of narrative earlier the same rule's prose makes the stronger claim: "it is
  foreign-key-correlatable from the parent, so it inlines rather than opening a new query". That
  second one is already false today (the re-projection is its own statement, per the Problem
  statement above), so it is a correction the change makes rather than a word it deletes. Both need
  the cardinality fork stated. The two code sites sit in code the change touches anyway, so they are
  sweep targets rather than stragglers; the published doc is the one a reader outside the change
  would hit, and nothing renders or drift-guards these two passages, so only the sweep catches them.

  A third passage in that same file is a leaf mapping rather than prose, and needs the same fork:
  the child-field table's row `Return @table+@discriminate interface | TableInterfaceField | Fetcher
  method` names one leaf for a coordinate that now has two. The row immediately above it already
  forks `BatchedTableField` off `@splitQuery` with its own row, so the table's own convention is to
  name the batched leaf separately; follow it rather than widening the existing row.

  `roadmap/root-connection-over-discriminated-interface.md` carries none of this vocabulary and is
  not a sweep target: its child section is already written forward ("R661 gives the child arm
  batched delivery"), and its one use of "the unbatched fetcher" stays true, since that fetcher
  survives for single cardinality.
* Two enumerations the seal edit invalidates, neither of them prose about delivery.
  `docs/architecture/reference/code-generation-triggers.adoc`'s closing note calls `TableTargetField`
  an "intermediate sealed sub-interface of `ChildField` grouping all 4 SQL-generating child field
  variants" and lists them by name; the `permits` edit makes that five. `BatchKeyField`'s class
  javadoc opens "Implemented by all field variants that are DataLoader-backed" and enumerates them,
  with the biconditional named right after, so the new leaf belongs in that list. Neither is
  guarded: the adoc section is hand-written, and the javadoc names only live symbols so the
  reference gate stays quiet.
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
