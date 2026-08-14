---
id: R666
title: "Delivery verdict derives from the store, not from a hand-maintained negative-space switch"
status: Backlog
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

## What this retires

`DeliveryFact.leafDerivedOf` becomes a consumer of the view instead of a parallel computation, and
then retires with the leaf zoo under `roadmap/coordinate-lowers-to-datafetcher-queryparts.md`.
`DeliveryFactPinTest` changes meaning in the process, and this is the point rather than a side
effect: today it pins two readers of the same inputs against each other, and afterwards there is
one derivation with constraints behind it, so the pin either becomes a store-versus-consumer
agreement or goes away with the crosswalk it compares. Whether the in-memory
`DeliveryFactRelation` survives as a cache over the view or dissolves is a Spec-time question; the
defect is the hand-maintained switch, not the map.

## Relationship to items already open

* `roadmap/coordinate-lowers-to-datafetcher-queryparts.md` (R333, Ready) is the umbrella that owns
  the leaf zoo's normalization. This item is one axis of it taken early, chosen because delivery is
  the axis where the two-site duplication has already produced a live defect. It should ride R333's
  vocabulary rather than invent its own, and R333's author should say whether this is a slice of
  that item or a dependent of it.
* `roadmap/batched-discriminated-interface-child.md` (R661, Spec) must not wait for this. It fixes
  the hardcoding in place, in the shape the relation has today, because an N+1 defect should not
  block on a structural item. This item is why R661's implementer should add the arm and stop
  there rather than trying to fix the pattern around it.
* `roadmap/split-query-marker-sweep.md` (R557, Backlog) wants a completeness enforcer for
  `@splitQuery`: every marker consumed, inert-by-construction, or rejected. Its spec proposes a
  total switch over the classified leaf. If delivery becomes a view, that sweep is an anti-join
  instead (`graphitron_split_query` rows with no `intent_field_batching_rule` row and no stated
  inert reason), which is both simpler and the same instrument the demand stratum's future gate
  already plans to use. Worth cross-referencing at Spec time; it may collapse into this item.

## Coverage

The shipped derived views each carry a hand-written anchor test that the view cannot produce by
construction (`AuthoredClaimConflictsTest`, `ColumnMatchClaimTest`, `DemandShadowTest`,
`InputOccurrenceShadowTest` in `rewrite/derive`), plus registration under
`FactCaptureAgreementTest`. This item follows that pattern rather than inventing one, and the
corpus gap the R661 review surfaced (no list-cardinality discriminated interface child) belongs in
the shadow-agreement population here as well as in `DeliveryFactPinTest`.

## Provenance

Surfaced in the Spec review of `roadmap/batched-discriminated-interface-child.md`, where the
missing update to `singleTableBackedVerdict` was the review's blocking finding. The item exists
because the finding is a symptom: a delivery rule change had to be applied by hand at a second site
whose encoding is a negative-space enumeration, and nothing but a reviewer's reading stood between
that and a silent disagreement.
