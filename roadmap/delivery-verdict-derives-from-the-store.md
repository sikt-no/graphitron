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
with nothing behind it. When `roadmap/batched-discriminated-interface-child.md` gives that very
shape a batched delivery, the `false` does not fail; it silently disagrees with the leaf side, and
the reviewer of that item had to find the site by reading.

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
one per trigger, and `intent_resolved_field_delivery (graph_name, type_name, field_name, verdict,
rule)` as the reduction. `INLINE` becomes the complement, the absence of any arm, rather than an
enumerated set of shapes somebody has to remember to keep current.

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

**Every input is already captured.** This is the finding that makes the item view-only rather than
a capture project. The arms need `graphitron_split_query`, `graphitron_tenant_fan_out`,
`graphitron_pivot`, `graphitron_routine`, `graphitron_record`, `graphitron_discriminate`,
`graphitron_table`, `graphitron_service` / `graphitron_external_field`, `graphql_field.is_list`,
`graphql_implements` and `graphql_union_member`. All exist, all are keyed at the coordinate or type
grain the arms would join on, and the marker relations already carry the `graphql_field` FK. The
one predicate needing care is participant table-boundness, which is `graphql_implements` joined to
`graphitron_table` rather than a stored fact, so confirm it against
`DeliveryFactRelation.anyTableBoundParticipant` at Spec time rather than assuming the join agrees.

## Which consumers can read it, and when

Delivery has two consumer classes, and the pipeline order splits them. `runPipeline` is, in order:
`GraphitronSchemaBuilder.buildBundle` (the classification walk, where `DeliveryFactRelation.compute`
runs), then `captureFactsAndDetect` (where the store is filled), then `validateAndLogErrors`, then
`EmitPlan.produce`. So:

* **Classifier-internal reads happen before capture and cannot use a view.** Which leaf record the
  walk mints (`BatchedInterfaceField` versus `InterfaceField`) is decided while the store is still
  empty. That read is leaf-zoo business and dissolves with the leaf zoo under
  `roadmap/coordinate-lowers-to-datafetcher-queryparts.md`, not here.
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

* The two views in the DDL, in the demand stratum's shape:
  `intent_field_batching_rule (graph_name, type_name, field_name, rule)` as a `UNION` of one arm per
  trigger, and `intent_resolved_field_delivery (graph_name, type_name, field_name, verdict, rule)`
  as the reduction under a declared precedence. `CHECK` on both closed vocabularies, `FOREIGN KEY`
  to `graphql_field`, full comment coverage per `FactSchemaGateTest.commentCoverageIsTotal`.
* Scope the resolved view's domain by joining `intent_type_domain`, the same anchor the demand
  reduction uses. `DeliveryFactRelation`'s domain is the flat classified index and explicitly
  excludes a nesting type's fields; the comparison has to be over one domain or it is measuring the
  boundary rather than the rule.
* `DeliveryResidue` in `DemandResidue`'s mould: a record naming each population the store cannot yet
  express, each with a stated removal criterion. Predicted from reading, to confirm at
  implementation: the nesting-field domain boundary above, and any arm whose predicate depends on
  classifier-internal route resolution (`resolveChildPolymorphicJoinPaths`) rather than on a
  captured fact. Participant table-boundness is the join to check first, since
  `DeliveryFactRelation.anyTableBoundParticipant` reads the walked verdict where the view would read
  `graphql_implements` joined to `graphitron_table`.
* `DeliveryShadowTest` in `DemandShadowTest`'s mould, registered in `FactCaptureAgreementTest` under
  `Arm.DERIVED` for both views. Per that test's stated residue discipline: equality outside the
  named residues, each disagreement direction pinned against a store-derived population rather than
  a Java-side coordinate list, and each residue asserted non-empty on the shapes that create it so
  no pin can go vacuous.
* Corpus population for every arm the view declares. A shadow test over a corpus that does not
  exercise an arm is vacuous in exactly the way the R661 review found `DeliveryFactPinTest` to be,
  so each declared rule needs a coordinate that reaches it. The list-cardinality discriminated
  interface child that review named is one of these.

## The exit criterion, and the successor

The successor slice flips `ProjectionCommands` and `LauncherCommands` onto
`intent_resolved_field_delivery`. Its precondition is checkable rather than a judgement call: **the
shadow residues are empty over the coordinates those two consumers actually read.** Residues
elsewhere (a nesting boundary the planning consumers never ask about) do not block it.

What retires then, and only then: `DeliveryFactRelation`'s hand-maintained production, including
`singleTableBackedVerdict` and its negative space. `DeliveryFact.leafDerivedOf` outlives it as the
walk-less fallback and retires with the leaf zoo under R333.
`DeliveryFactPinTest` changes meaning rather than disappearing, and that is the point: today it
pins two duplicate readers, and afterwards it either becomes a store-versus-consumer agreement or
goes away with the crosswalk it compares.

**One test of whether this item succeeded:** the discriminated interface child must not appear in
`DeliveryResidue` as a standing exemption. If the view cannot express the shape whose hardcoded
`false` started the item, the item has reproduced the defect in a new place.

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
  vocabulary rather than invent its own, and R333's author should say whether this is a slice of
  that item or a dependent of it.
* `roadmap/batched-discriminated-interface-child.md` (R661, Spec) must not wait for this, and there
  is deliberately no dependency in either direction: an N+1 defect should not block on a structural
  item, and this item models the delivery rules as they are rather than as R661 will leave them.
  Either landing order works. If R661 lands first, this item's view gains one more arm to express
  and one more corpus coordinate to cover. If this item lands first, R661's relation-side edit
  becomes additive, a `UNION` arm rather than a negative-space switch case, which is strictly the
  better shape for that implementer. Whoever goes second reads the other's landing note.
* `roadmap/split-query-marker-sweep.md` (R557, Backlog) wants a completeness enforcer for
  `@splitQuery`: every marker consumed, inert-by-construction, or rejected. Its spec proposes a
  total switch over the classified leaf. If delivery becomes a view, that sweep is an anti-join
  instead (`graphitron_split_query` rows with no `intent_field_batching_rule` row and no stated
  inert reason), which is both simpler and the same instrument the demand stratum's future gate
  already plans to use. Worth cross-referencing at Spec time; it may collapse into this item.

## Open for the implementer

* Whether the two views are the right split, or whether delivery is thin enough to be one view.
  The demand stratum splits because demand and exemption are independently interesting populations
  read by different consumers. Delivery's negative side is pure complement, so the exemption-view
  analogue does not exist; whether the rule view still earns separation from the reduction is worth
  deciding on the arm count rather than by copying the sibling's file layout.
* Whether `graphitron_service`'s claim ("the call is the delivery") is a rule arm or a domain
  exclusion. `DeliveryFactRelation.mint` returns `Inline` for a `@service` coordinate before
  reaching any other rule, which reads as precedence, but it may be cleaner as an anti-join in the
  resolved view. Either encodes the same verdict; the precedence form matches the sibling.
* Whether the resolved view needs a stored materialization. `intent_type_domain` is a table rather
  than a view because H2 cannot state a terminating closure over a cyclic type graph. The delivery
  arms look like plain joins with no closure, so a view should hold, but confirm before assuming.

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
