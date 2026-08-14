---
id: R666
title: "Delivery verdict derives from the store, not from a hand-maintained negative-space switch"
status: Spec
bucket: architecture
priority: 3
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Delivery verdict derives from the store, not from a hand-maintained negative-space switch

## Problem

Delivery (does this coordinate batch through a `DataLoader`, or fetch inline) is decided twice in
Java and zero times in the store. `DeliveryFactRelation.mint` is the production source that
`ProjectionCommands` and `LauncherCommands` read through `GraphitronSchema.deliveryOf`;
`DeliveryFact.leafDerivedOf` is a total switch over the sealed leaf hierarchy, kept as the
comparison side and the walk-less fallback. Neither is relational. The relation is a
`Map<FieldCoordinates, DeliveryFact>` computed by a method that switches on type verdicts, which
makes it a second switch wearing a relation's name.

The cost is concrete and current. `DeliveryFactRelation.singleTableBackedVerdict` encodes delivery
as **negative space**: it returns the batched-capable predicate by enumerating what does *not*
qualify, with `case GraphitronType.TableInterfaceType _ -> false` and a matching exclusion inside
its `ConnectionType` arm, justified in javadoc as "the single-table interface child, whose only
delivery is inline". That is a closed-world claim about the whole shape space, maintained by hand,
with nothing behind it.

What `roadmap/batched-discriminated-interface-child.md` then had to establish is the exact cost.
That item gives the discriminated interface child a batched delivery, and settling what the
hardcoded `false` owed it took a full reading of `mint`'s arm order: the answer turned out to be
that the `false` case stays correct as written, because the new delivery arrives as a positive arm
placed ahead of the `tableAnchoredChild` computation, and only the javadoc rationale quoted above
goes false. Nothing in the code said so. The site's correctness under a new batched shape was
established by a reviewer reading arm order, which is the same as saying it was not established.

The pin does not close this. `DeliveryFactPinTest` compares the two sides, but
`DeliveryFactRelation`'s own javadoc states what the comparison is worth: "this production and the
classifier's batched-leaf arms read the same marker, source-shape and verdict facts on both sides,
pinned for regression rather than independence". Two readers of the same inputs agreeing is not
integrity, it is duplication with a consistency check, and the check is further bounded by the
corpus population: no example carries a list-cardinality discriminated interface child, so even the
regression reading is blind exactly where the next change lands.

## The exemplar is already in the tree

The store already solves this problem for a structurally identical verdict, and the delivery
question is isomorphic to it rung for rung. The demand stratum is:

* `intent_field_demand_rule (graph_name, type_name, rule)`, a `UNION` of one `SELECT` per positive
  rule over base relations (`ROOT_OPERATION` off `graphql_root_operation`, `TABLE_TYPE` off
  `graphitron_table`, `ERROR_TYPE` off `graphitron_error`, `PRODUCER_PAYLOAD` off the producer
  relations).
* `intent_field_exemption_rule (graph_name, type_name, reason)`, the same shape for the negative
  side where the negatives are themselves positive statements about a captured population.
* `intent_resolved_field_demand (graph_name, type_name, field_name, verdict, rule)`, the reduction:
  a two-value verdict plus the winning rule literal, resolved by a declared precedence order, with
  the vocabularies closed and stated in the column comments.

`DeliveryFact` is already that pair: a two-value verdict (`Batched` / `Inline`) plus a
`DeliveryFact.Trigger` naming which rule produced it (`Authored`, `RecordHandedParent`,
`PolymorphicFanIn`). It is the demand stratum's shape, expressed as a sealed hierarchy computed by
a switch instead of as a view over keyed relations.

## What the shape would be

`intent_field_batching_rule (graph_name, type_name, field_name, rule)` as a union of positive arms,
one per trigger; `intent_field_delivery_exemption (graph_name, type_name, field_name, reason)` for
the negatives that are stated rather than absent; and `intent_resolved_field_delivery (graph_name,
type_name, field_name, verdict, rule)` as the reduction under a declared precedence. Most `INLINE`
is the complement, the absence of any arm, rather than an enumerated set of shapes somebody has to
remember to keep current. The exceptions are the two populations below, and they are why the
exemption relation exists rather than being a file-layout choice copied from the sibling.

The property worth naming: **every arm is additive**. A new batched delivery is a new `SELECT`
`UNION`ed in, joining the base relations that already witness it. Nothing elsewhere has to be
edited to stay true, which is the difference between this and the `singleTableBackedVerdict` case
that started the item. That is the fact model's stated law ("a capability is added by adding a fact
relation, never a new leaf type") applied to a verdict that never got the treatment.

Integrity moves to the instruments the store already uses:

* `CHECK` on the verdict and rule vocabularies, in the `intent_resolved_field_demand` mould, so the
  closed sets are declared where they are read rather than implied by a switch's arms.
* `FOREIGN KEY` to `graphql_field` at the coordinate grain, which every `graphitron_` marker
  relation already carries, so a rule arm cannot name a coordinate capture never saw.
* Ambiguity as a **detection view** in the `intent_authored_claim_conflict` mould: a coordinate
  matching two arms whose precedence is undeclared is a row to report, not a switch fallthrough to
  guess at. The current model cannot even ask this question.

### Delivery's negative side is not pure complement, and the corpus already proves it

An earlier draft asserted that it was, and left the shape of the view set open on that basis. It is
wrong, in two places, and each has a coordinate in `ClassifiedCorpus` today that would disagree with
a complement-only view. Both are short-circuits at the top of `DeliveryFactRelation.mint`, which is
what makes them easy to read past: they return before the rules they override are ever evaluated.

* **The `@service` claim, reason `SERVICE_CALL`.** `mint` returns `Inline` for a `@service`
  coordinate before any other rule, on the stated ground that the call is the delivery and the
  serviceCall member owns it. That precedence is load-bearing rather than defensive. Take
  `Aggregated.filmsViaService: [Film!]!` from the `service-child-class-backed-parent` example: its
  parent is a class-backed producer payload, so `sourceShape()` is `Record`, and its target `Film`
  is a plain `@table` type, so `tableAnchoredChild` holds. Delete the short-circuit and that
  coordinate mints `Batched(RecordHandedParent)`. A `RecordHandedParent` arm written from the
  captured facts alone matches it for exactly the same two reasons, so a view without the exemption
  reports `BATCHED` where the walk reports `INLINE`.
* **The root coordinate, reason `ROOT_COORDINATE`.** `mint`'s first line returns `Inline` for
  anything that is not a `ChildField`, because nothing arrives at a root so nothing splits. The
  store has no notion of leaf identity, so "is a child" has to be said out loud, and the fan-in arm
  is where it bites: `Query.people(firstName: [String!]): [Person!]!` in the `polymorphic-filter`
  example is a list-valued union root whose members are both `@table`, which is the fan-in arm's
  store-side predicate met in full. Scoping by `intent_type_domain` does not remove it, since the
  domain contains root types by construction and the demand sibling registers their fields under its
  own `ROOT_OPERATION` arm.

Three consequences, and together they settle what the open questions used to ask.

The relation set is the sibling's, rung for rung: a positive rule view, an exemption view whose
negatives are positive statements about a captured population, and a reduction that declares the
precedence between them. That is now a structural conclusion rather than a layout preference.

The alternative, folding each exemption into the arm it overrides as a `NOT EXISTS`, is available
and should be declined. It is negative space inside an arm, which is the pattern this item exists to
kill; it also spreads one rule across every arm it defeats, so the `@service` claim would have to be
restated in each future batching arm, which is the additivity property gone.

And both exemption arms are witnessed on landing, by the two coordinates named above, which is worth
contrasting with the `@tenantFanOut` literal the Implementation section flags as reaching no
coordinate at all. The exemption view cannot ship vacuous.

**Every input is already captured.** This is the finding that makes the item view-only rather than
a capture project, and it survives a relation-by-relation check, but the inventory is wider than a
first read suggests. The arms need `graphitron_split_query`, `graphitron_tenant_fan_out`,
`graphitron_pivot`, `graphitron_routine`, `graphitron_discriminate`, `graphitron_table` (through
`intent_bound_table`, see below), `graphitron_service` / `graphitron_external_field` /
`graphitron_mutation`, `graphitron_connection`, `graphql_field.is_list`, `graphql_type.kind`,
`graphql_implements`, `graphql_union_member`, and `graphql_root_operation` (the root exemption arm,
keyed the way the demand sibling's `ROOT_OPERATION` arm already keys it, by the binding rather than
by the conventional names). All exist, all are keyed
at the coordinate or type grain the arms would join on, and the marker relations already carry the
`graphql_field` FK. The `sql_` catalog family is deliberately absent; the second predicate below is
where that is established.

One predicate needs real care. A second looks like it does and does not, and the reason it does not
is worth stating, because the obvious reading sends the arm at the catalog for nothing.

**The fan-in arm's gate is non-discrimination, not participant boundness.** The tempting reading is
that the arm has to tell `ParticipantRef.TableBound` from `JoinedTableBound`, because
`DeliveryFactRelation.anyTableBoundParticipant` tests `TableBound` specifically rather than the
`TableBacked` supertype. That narrowing cannot change a verdict, and the trace is short. The arm is
only entered when `unwrapped instanceof TargetShape.Interface || TargetShape.Union`, and
`ChildField.target()` gives `TableInterfaceField` a `TargetShape.Table`, so no discriminated
interface child, single-table or joined-table, reaches the arm at all. Underneath,
`anyTableBoundParticipant` reads participants only from `GraphitronType.InterfaceType` and
`UnionType`; a `TableInterfaceType` falls to its `default -> List.of()`. And `JoinedTableBound` is
minted at one site in `TypeBuilder`, behind an `interfaceTable != null` guard that only the
`TableInterfaceType` construction satisfies (the `InterfaceType` and `UnionType` constructions both
pass null). So a `JoinedTableBound` participant cannot reach the predicate that would reject it.
`roadmap/batched-discriminated-interface-child.md` states the same participant invariant
independently, as the reason it must not copy this guard's shape.

Two consequences. The arm's store-side gate is that the target is an interface or union that is
*not* `@discriminate`-bearing, with at least one table-bound participant: `graphql_implements` /
`graphql_union_member` joined to `graphitron_table`, anti-joined against `graphitron_discriminate`
on the target type. Every input is in the inventory above and every hop is single. The arm stays
unmasked against the root exemption, which is the sibling's discipline (its rule views let
overlapping readings survive as rows and give the reduction the meet), so this arm does carry
`Query.people` and the reduction is where that row becomes `INLINE`. And the `sql_`
catalog family is not needed by any arm, which is why it is absent from that inventory: no arm's
predicate reaches a foreign key or a primary key. If an implementer finds one that does, that is a
finding worth recording rather than a gap to fill quietly.

**Record-handedness is not `@record`.** `graphitron_record` captures the deprecated, ignored
`@record` directive and is the wrong base relation for the `RecordHandedParent` trigger.
`ChildField.sourceShape` states what the trigger actually reads: a projection of the *parent type's
backing*, `Record` exactly when the parent hands a producer-handed domain record rather than a
catalog row. The captured population for that is the producer payload set,
`graphitron_service` / `graphitron_external_field` rows whose class decoded plus every
`graphitron_mutation` payload, which `intent_field_demand_rule`'s `PRODUCER_PAYLOAD` arm already
assembles in exactly that shape and is the arm to copy. The pivot-slot record parent is the one
member of the population that arm does not cover, since its source is the pivot subselect's built
record rather than a producer's. `SourceShapeProjectionTest` already states the parent-backing
predicate independently of the leaf identities, so it is the cross-check to read before writing the
arm, and the residue candidate if the store side cannot reach the pivot slot.

## Which consumers can read it, and when

Delivery has two consumer classes, and the pipeline order splits them. `runPipeline` is, in order:
`GraphitronSchemaBuilder.buildBundle` (the classification walk, where `DeliveryFactRelation.compute`
runs), then `captureFactsAndDetect` (where the store is filled), then `validateAndLogErrors`, then
`EmitPlan.produce`. So:

* **Classifier-internal reads happen before capture and cannot use a view.** Which leaf record the
  walk mints (`BatchedInterfaceField` versus `InterfaceField`) is decided while the store is still
  empty. That read is leaf-zoo business and dissolves with the leaf zoo under
  `roadmap/coordinate-lowers-to-datafetcher-queryparts.md`, not here. This is a fact about today's
  stage order rather than about delivery, but as of 2026-08-14 no open item proposes changing it: the
  item that specified the reorder was repointed onto the emit plan (R667, below) when its owner found
  the reorder had no consumer, and that item now states it does not touch the pipeline order. So the
  ordering constraint is load-bearing for this item's scope, not a temporary accident to route
  around; re-check it at pickup rather than assuming either way.
* **Planning-stage reads happen after capture and are eligible today.** `ProjectionCommands` and
  `LauncherCommands` reach delivery through `GraphitronSchema.deliveryOf`, and both run inside
  `EmitPlan.produce`, downstream of the capture transaction.

Two things follow, and they are the reason this item is worth doing now rather than waiting. It is
**not blocked on R333**: the planning consumers are reachable without touching the classification
walk. And a flip, if one ever happens, is bounded to those two consumers; nothing in this item can
or should reach the walk's own mint decision.

## Scope: the view and its shadow, not the flip

**This item lands the view and its shadow agreement. It changes no production read.** That is the
whole delivery, and the scope is deliberate rather than timid.

A flip requires the shadow residues to be empty over the consumed population, and nobody knows what
those residues are until the view exists. Committing to the flip inside this item means one of two
bad outcomes: the item stalls on a residue nobody predicted, or the flip ships with a residue
papered over, which is the same negative-space move the item exists to kill. The demand stratum
settled this already and is the precedent to copy: it shipped, registered under
`FactCaptureAgreementTest`, and is still in shadow, with `intent_resolved_field_demand`'s own
comment stating "nothing gates on it in shadow".

**The integrity gain does not wait for the flip, and this is the point.** Today's
`DeliveryFactPinTest` compares two readers of the same in-memory inputs, which the relation's
javadoc concedes is "pinned for regression rather than independence". A store-derived view is a
genuinely independent second derivation: it reads captured base relations, not the walk's verdicts.
So the shadow agreement catches exactly the class this item was filed over. A batched delivery
added on the leaf side with no corresponding rule arm surfaces as a new residue row and fails the
pin, with no consumer moving and no reviewer required to notice. That enforcer is what the current
two-switch model structurally cannot have.

The deliverable is therefore not "the store also knows about delivery". It is **the delivery rule
set acquiring an enforcer that is not another switch.**

## Implementation

* The three views in the DDL, in the demand stratum's shape:
  `intent_field_batching_rule (graph_name, type_name, field_name, rule)` as a `UNION` of one arm per
  trigger, `intent_field_delivery_exemption (graph_name, type_name, field_name, reason)` with the
  two arms established above, and `intent_resolved_field_delivery (graph_name, type_name,
  field_name, verdict, rule)` as the reduction. `CHECK` on all three closed vocabularies,
  `FOREIGN KEY` to `graphql_field`, full comment coverage per
  `FactSchemaGateTest.commentCoverageIsTotal`.
* The reduction's precedence runs exemption before rule, which is the opposite of the demand
  sibling's and has to be stated as such in the view comment rather than inherited by analogy.
  `intent_resolved_field_demand` lets demand beat exemption because a `@table` type shaped like a
  connection still classifies its fields; here `mint` returns on the exemption before the rules run,
  so a coordinate carrying both readings is `INLINE`. Getting this backwards makes both witnessed
  coordinates above disagree, which the shadow test will catch, but the reason it is inverted
  belongs in the comment where the next reader meets it.
* Scope the resolved view's domain by joining `intent_type_domain`, the same anchor the demand
  reduction uses. `DeliveryFactRelation`'s domain is the flat classified index and explicitly
  excludes a nesting type's fields; the comparison has to be over one domain or it is measuring the
  boundary rather than the rule.
* Reach a type's table binding through `intent_bound_table` rather than joining `graphitron_table`
  raw. That view already resolves the reference as written through `intent_spelled_table` and
  already ships as a registered `Arm.DERIVED` relation, and it makes binding ambiguity rows instead
  of a silent pick: two candidate tables are two rows, so an arm that needs a settled binding states
  `candidates = 1` the way the column-match classifier does. A raw join re-spells a resolution the
  store owns.
* `DeliveryResidue` in `DemandResidue`'s mould: a record naming each population the store cannot yet
  express, each with a stated removal criterion. Predicted from reading, to confirm at
  implementation: the nesting-field domain boundary above, and any arm whose predicate depends on
  classifier-internal route resolution (`resolveChildPolymorphicJoinPaths`) rather than on a
  captured fact. The one predicate-driven residue candidate is the pivot-slot record parent, the
  single member of the record-handed population no producer relation witnesses. The joined-table
  participant is explicitly *not* a residue candidate, per the fan-in trace above.
* `DeliveryShadowTest` in `DemandShadowTest`'s mould, registered in `FactCaptureAgreementTest` under
  `Arm.DERIVED` for all three views. Per that test's stated residue discipline: equality outside the
  named residues, each disagreement direction pinned against a store-derived population rather than
  a Java-side coordinate list, and each residue asserted non-empty on the shapes that create it so
  no pin can go vacuous.
* Corpus population for every arm the view declares. A shadow test over a corpus that does not
  exercise an arm is vacuous in exactly the way the R661 review found `DeliveryFactPinTest` to be,
  so each declared rule needs a coordinate that reaches it. Two populations are missing today, both
  counted against `ClassifiedCorpus` rather than assumed:
  * The list-cardinality discriminated interface child that review named. The `table-interface`
    example's `Inventory.media` is the only discriminated interface *child* in the corpus and it is
    `target: Single`; `joined-table-interface` and its paginated sibling reach the shape only through
    `Query` roots, and a root mints `Inline` before any child rule is evaluated. So the shape whose
    hardcoded `false` started this item is genuinely unwitnessed.
    `roadmap/batched-discriminated-interface-child.md` reaches the same conclusion and asks for
    **three** coordinates rather than one, because the marker arms read `@splitQuery` independently
    of cardinality: a list child, a list child carrying `@splitQuery`, and a single child carrying
    `@splitQuery`. Take that count, not this bullet's original one.
  * **The `@tenantFanOut` arm has no witness anywhere in the corpus.** `@tenantFanOut` occurs zero
    times in `ClassifiedCorpus` (against eight `@splitQuery`, seven `@routine`, four `@pivot`), and
    `DeliveryFactPinTest`'s own `MARKER_FIXTURE` covers only the split-query half, by its comment
    "an authored split child riding a table parent". This is the arm the open question below
    proposes to promote to its own rule literal, so on the split-literal answer the view would ship
    a vocabulary entry that no coordinate can reach: vacuous on landing, in exactly the class this
    item exists to kill. Fixtures for the marker do exist outside the corpus
    (`TenantFanOutClassificationTest`, `TenantFanOutFetcherPipelineTest`,
    `TriggerFactPopulationPinTest`), so this is a choice to make rather than a blocker: either add a
    corpus example, or carry a beside-the-corpus fixture the way `MARKER_FIXTURE` already does.
    `TriggerFactPopulationPinTest` is the mould for the latter, being pipeline-tier and pinning each
    gather slot's rows by coordinate so that an empty relation fails as loudly as an over-gathering
    one.

## The exit criterion, and the successor

The successor slice flips `ProjectionCommands` and `LauncherCommands` onto
`intent_resolved_field_delivery`. It is filed: `roadmap/emit-plan-reads-the-store.md` (R667, Spec)
declares this item as its dependency, and its measured read surface counts `deliveryOf()` as one of
the accessors it converts, at two call sites, which are exactly the two consumers named above (a
whole-tree check confirms there is no third production reader). So the successor is not a slice to write later but a plan
already specified, and this item's job is to leave that plan a view it can read. Its precondition is
checkable rather than a judgement call: **the shadow residues are empty over the coordinates those
two consumers actually read.** Residues elsewhere (a nesting boundary the planning consumers never
ask about) do not block it.

What retires then, and only then: `DeliveryFactRelation`'s hand-maintained production, including
`singleTableBackedVerdict` and its negative space. `DeliveryFact.leafDerivedOf` outlives it as the
walk-less fallback, and its own javadoc claims it outlives the leaf zoo too, on the grounds that
delivery is one of the axes the leaf reconstruction key keeps. Take that claim as the current
position rather than as settled: if the leaf zoo dissolves there is no leaf encoding left to read,
so the crosswalk's survival is R333's call to make, not this item's, and nothing here depends on
which way it goes.
`DeliveryFactPinTest` changes meaning rather than disappearing, and that is the point: today it
pins two duplicate readers, and afterwards it either becomes a store-versus-consumer agreement or
goes away with the crosswalk it compares.

**One test of whether this item succeeded:** the discriminated interface child must not appear in
`DeliveryResidue` as a standing exemption. The reason is not that the hardcoded `false` is wrong;
per the Problem statement it survives its own sibling item intact. The reason is that this shape is
the one whose delivery nobody could settle without reading arm order, so a view that cannot state it
has left the question exactly where it was and reproduced the defect in a new place.

## Out of scope

* **Flipping any consumer.** The successor above, gated on its stated criterion.
* **The classifier's own mint decision.** Ordering-blocked, per the eligibility section, and owned
  by R333.
* **Retiring `DeliveryFact.leafDerivedOf` or `DeliveryFactPinTest`.** Both are the comparison side
  while the window is open.
* **Collapsing R557's sweep into the anti-join.** Cross-referenced below, decided in that item.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333, Ready) is the umbrella that owns
  the leaf zoo's normalization. This item is one axis of it taken early, chosen because delivery is
  the axis where the two-site duplication has already produced a live defect. It should ride R333's
  vocabulary rather than invent its own. On whether that makes it a slice or a dependent, the
  question the earlier draft left to R333's author now has a precedent to follow rather than needing
  a ruling: R667 states plainly that R333 "owns the drain" and that it is a slice of it, while
  carrying its own `depends-on` edge. Read this item the same way, a slice of R333 that is
  independently schedulable, unless R333's author says otherwise.
* `roadmap/emit-plan-reads-the-store.md` (R667, Spec) is the successor named above and the one item
  that declares a dependency on this one. It converts the whole emit plan onto the store, and the
  two delivery consumers this item leaves in place are inside its scope. Two consequences for
  whoever implements this item. The view's column names and rule vocabulary become R667's read
  surface, so pick them for a consumer rather than only for the shadow test. And the exit criterion
  above is checkable against a real population now, because R667's measured read surface says
  exactly which coordinates `deliveryOf()` is asked about.
* `roadmap/batched-discriminated-interface-child.md` (R661, Spec) must not wait for this, and there
  is deliberately no dependency in either direction: an N+1 defect should not block on a structural
  item, and this item models the delivery rules as they are rather than as R661 will leave them.
  Either landing order works. If R661 lands first, this item's view gains one more arm to express
  and the three corpus coordinates that item specifies (its own delivery-agreement bullet enumerates
  them; they subsume the first coordinate this item asks for). If this item lands first, R661's relation-side edit
  becomes additive, a `UNION` arm rather than a negative-space switch case, which is strictly the
  better shape for that implementer. Whoever goes second reads the other's landing note.
* `roadmap/split-query-marker-sweep.md` (R557, Backlog) wants a completeness enforcer for
  `@splitQuery`: every marker consumed, inert-by-construction, or rejected. Its spec proposes a
  total switch over the classified leaf. If delivery becomes a view, that sweep is an anti-join
  instead (`graphitron_split_query` rows with no `intent_field_batching_rule` row and no stated
  inert reason), which is both simpler and the same instrument the demand stratum's future gate
  already plans to use. The Spec-time question that draft left open, whether R557 collapses into
  this one, resolves to no. R557's deliverable is a validate-time rejection with a stated reason per
  inert position, and this item changes no production read and raises no diagnostic, so folding it
  in would drag a diagnostics surface into a shadow-only item. There is also a shape mismatch worth
  recording, and the negative-side section above sharpens rather than removes it. This item does
  ship an exemption relation carrying reasons, but for exactly two populations that override a
  matching batching rule, which is a different question from the one R557 asks: why a marker that
  matched nothing is nonetheless not an error. So R557 gains its *population* from the anti-join,
  gains the exemption view's shape as a model for stating its own inert reasons positively, and
  still has to author that vocabulary itself. The one real coupling is ordering: R557 should not be
  picked up before this lands, or it writes the total switch this item exists to retire.

## Open for the implementer

One question is genuinely open, and it is narrower than the two the earlier draft carried.

* **Whether the `Authored` trigger keeps one rule literal or becomes two.** The arm count, read off
  `DeliveryFactRelation.mint`: three triggers, but four `SELECT`s, because `Authored` is two
  independent readings (the `@splitQuery` half on a table-anchored child or a `@pivot` chain, and
  `@tenantFanOut` on a table-anchored non-`@routine` child) that mint the same literal. The
  sibling's vocabulary is one literal per arm, which argues for splitting, and against it stands the
  `@tenantFanOut` arm having no coordinate anywhere, so a split ships a literal nothing reaches.
  Two observations for whoever decides. The vacuity is an argument for the fixture rather than
  against the split, since the arm is equally unwitnessed under one literal and merely less visibly
  so. And the decision is cheap now and expensive later: the rule vocabulary becomes R667's read
  surface once that item lands, so splitting a literal afterwards is a consumer change rather than a
  DDL change. That asymmetry, not the arm count, is the thing to weigh.

Settled at review rather than left open, in the order the questions were retired.

**Whether `graphitron_service`'s claim is a rule arm or a domain exclusion: it is neither.** It is an
exemption arm with declared precedence, per the negative-side section above, which also establishes
that the root coordinate is a second one. The complement-only reading the question presupposed is
what turned out to be false, and both alternatives it offered inherited that reading. The same
finding resolves the view-count question: three relations, the sibling's shape, for a structural
reason rather than by copying a file layout.

**The resolved view needs no stored materialization.**
`intent_type_domain` is a table rather than a view because H2 cannot state a terminating closure over
a cyclic type graph. Every arm in `mint` reads its own coordinate's markers, that coordinate's target
type, and that type's participants or bound table, so nothing recurses and no arm needs a closure.
The joined-table anti-join was the one candidate exception, and the fan-in trace above removes it:
with no arm reaching the `sql_` family, every predicate is a single hop and a plain view holds
unconditionally.

## Coverage

The shipped derived views each carry a hand-written anchor test the view cannot produce by
construction (`AuthoredClaimConflictsTest`, `ColumnMatchClaimTest`, `DemandShadowTest`,
`InputOccurrenceShadowTest` in `rewrite/derive`), plus `Arm.DERIVED` registration in
`FactCaptureAgreementTest`, whose driver fails both on an unregistered relation and on a
registration the DDL no longer declares. This item follows that pattern rather than inventing one.
The two additions specific to it are in the Implementation section above: the domain join that
keeps the comparison off the nesting boundary, and the per-arm corpus population without which the
agreement is vacuous.

## Provenance

Surfaced in the Spec review of `roadmap/batched-discriminated-interface-child.md`, where the
missing update to `singleTableBackedVerdict` was the review's blocking finding. The item exists
because the finding is a symptom: a delivery rule change had to be applied by hand at a second site
whose encoding is a negative-space enumeration, and nothing but a reviewer's reading stood between
that and a silent disagreement. Sliced out of R333 at its author's direction, as the delivery axis
of the leaf zoo's normalization taken early, on the grounds that it is the axis where the
two-site duplication has already produced a live defect.
