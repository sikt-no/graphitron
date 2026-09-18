---
id: R958
title: "The column-scope departures and the input-field and argument walks are gatherer-written rows, not registered views"
status: Backlog
bucket: architecture
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-18
last-updated: 2026-09-18
---

# The column-scope departures and the input-field and argument walks are gatherer-written rows, not registered views

## Goal

Every relation under the `@reference` stratum resolves once per capture, written down by the
gatherer that owns it, where four of them are still refilled by a register and two are still views
re-walked once per driving row. A *register* here is `meta_materialize`, the roster that keeps a
rule in a view and refills a table from it on the capture cadence, and it exists to schedule
refreshes for rules no owner schedules. When this lands the `@nodeId` decode rule grows linearly in
the store's size where today its remaining term grows as the square, the register is four rows
smaller, and the column-scope family's departures are captured facts like the reference family's
already are.

Two halves, filed together because the second reads the first.

The *column-scope departures* are `intent_argument_scope_table`, `intent_field_scope_table`,
`intent_carrier_data_field` and `intent_input_field_resolving_table`, four registrations whose
subtree reaches `intent_type_backing`, `intent_errors_field`, `intent_field_payload_producer`,
`intent_poly_member`, `intent_field_participant_scope_table` and, through the first two, the
hand-written `intent_type_backing_class`, `intent_input_occurrence_path` and
`intent_input_occurrence_path_step`. All bottom out in captured facts; the subtree is simply wider
than the reference family's and belongs to another family.

The *input-field and argument walks* are `intent_input_field_reference_step_target` and
`intent_argument_reference_step_target`, the two walks that are not the field walk. They take the
shape the field walk landed in, two keyed arm tables under a union view written by one statement per
arm over one recursive chain, and they read the column-scope departures, which is why they are here
rather than in a third item.

The cost this closes is measured and small: the input-field walk is 2.66 s of the `@nodeId`
decode-hop rule's 4.124 s on an analysed store, and about 2450 s of 2536 s in the regime a store with
no statistics runs in. That is seconds rather than the failure its predecessor fixed, which is why
this is priority 3 and why the predecessor said so when it split these phases out.

## Implementation

Inherited whole from R954, which wrote the placement argument, the closure and the convert-or-demote
rule for these two rungs and shipped the three below them. Read that item's history rather than
re-deriving any of it; what follows is what its own phase text said, restated only where the tree has
moved since.

The stages go in the derivation stratum of `FactCapture.capture`, after the five hand-written
producers and before `Materializations.refresh`, which is the position `ArgMappingCandidates.derive`
already occupies. After the producers because three of the relations the column-scope subtree reaches
are exactly the tables `InputOccurrencePaths.derive` and `TypeBackingRows.derive` write, so a stage
inside `GraphitronFactCapture` would read the previous capture's rows; before the refresh because
registrations that survive read these rows and have to see this capture's. That is the same invariant
the three shipped rungs satisfy by sitting inside the gatherer: no stage reads a table a later step of
the same pass writes.

What each conversion owes is what the shipped three owed, and R954's own "Shipped at" notes are the
concrete record of it: a graph-scoped `DELETE` before the `INSERT ... SELECT`, a `meta_relation`
declaration with a comment condensed to its grain sentence and its example, a primary key that
matches the declared grain, and the `EXCEPT` oracle run against the retired rule text **before** that
text leaves the DDL.

## Tests

R954's `Tests` section applies unchanged to these two rungs. The two the implementer should expect to
edit rather than merely satisfy are `DerivedReadCostTest`, whose pinned cells and counts move with
every retirement, and `MaterializeRegistryGateTest`, whose register size and refresh depth are pinned
by equality.

## Relation to other items

**R954** shipped the three rungs below these and is where every argument in this body comes from: the
closure over the three walks, the invariant each placement satisfies, the key gate's answer for a
relation whose arms have two natural keys, and the measurement that split these two phases out as a
modelling tidy rather than a fix. Its own re-measurement caveat carries over: every figure quoted
above was taken before R953's statistics levers shipped, and the pass they fix is the one those
figures are relative to, so this item owes its own reading before it claims one.
