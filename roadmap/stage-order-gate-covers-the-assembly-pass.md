---
id: R969
title: "The stage-order gate checks the assembly pass stages, and the converted relations lose their retired names in prose"
status: Backlog
bucket: architecture
priority: 5
theme: model-cleanup
depends-on: []
created: 2026-09-23
last-updated: 2026-09-23
---

# The stage-order gate checks the assembly pass stages, and the converted relations lose their retired names in prose

## Goal

The stage-order invariant, that no step of a pass reads a table a later step of the same pass
writes, is checked for every stage stated as a jOOQ statement, not just the ones in the derivation
stratum. `StageOrderGateTest` models the stratum `FactCapture.derive` runs, and it can now derive a
jOOQ stage's read set from the statements the stage executes (`ViewReferences.relationsReadBy` over
a `Query`). The assembly pass, `GraphitronAssemblyCapture.capture`, runs the field-site
`@reference` hop and walk stages (`FieldReferenceStepHops.deriveKeyed`/`deriveKeyless`, then
`FieldReferenceStepTargets.derive`) and nothing checks their order. `FieldReferenceStepTargets`
already exposes `statements(dsl, graphName)`. `FieldReferenceStepHops` exposes none: its inserts
are built and run inside `insertKeyed`/`insertKeyless`. When this lands, a hop or walk stage moved
ahead of what it reads fails the build the way a misplaced stratum stage already does.

The plan that converted the column-scope departures and the argument and input-field walks into
stages meant to cover these stages "on the way past". That did not happen, because they run in a
different pass that the stratum gate does not model. The input-field walk in the stratum reads
`graphitron_field_reference_step_hop`, which the assembly pass writes. That edge is sound today only
because `ModelCapture` calls `GraphitronAssemblyCapture.capture` before `FactCapture.derive`, and
no gate states that ordering.

## Also here: prose that names the retired relations

These surfaces survived the retirement sweep. They go with the first commit that touches each area:

- Live roadmap bodies still spell the six relations by their retired `intent_` names
  (`intent_carrier_data_field`, `intent_field_scope_table`, `intent_argument_scope_table`,
  `intent_input_field_resolving_table`, `intent_argument_reference_step_target`,
  `intent_input_field_reference_step_target`). At the time of filing that was R955, R929, R846,
  R677, R877, R718, R876, R382, R963, R942, R682, R899, R944, R719 and R827. The canonical names
  are now `graphitron_`-prefixed. Correct each body by reading it, not with a blind rename: some
  sentences describe the relation as it was when it was still registered.
- `FactCaptureAgreementTest`'s class javadoc says the argument-site hop and target are "two
  views" whose "arms are textually parallel". The field-site hop has been jOOQ since it became a
  stage, and the argument-site target is two stage-written tables under a union view.
- `ReferenceStepWalk`'s class javadoc says "Two hop views carry the same body arm for arm". There is
  one hop view now. The field-site hop is jOOQ.
- `DetectionReadReachGateTest`'s `ReferenceForParticipantDefects` roster says a relation is "also
  refreshed through `graphitron_field_scope_table_rule`". That rule is evaluated by a stage now,
  not by the refresh.

## Provenance

Found at R958's In Review -> Done gate. Filed rather than absorbed there: the gate part was scope the plan named on a wrong premise, that the hop stages sat in the stratum, and the implementer disclosed it in the item body; the prose part is the residue of that item's retirement sweep.
