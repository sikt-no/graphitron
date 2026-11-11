---
id: R1174
title: "Every defect code a rule view emits is owned by the stage that runs it"
status: Backlog
bucket: bug
priority: 6
theme: diagnostics
depends-on: []
created: 2026-11-11
last-updated: 2026-11-11
---

# Every defect code a rule view emits is owned by the stage that runs it

## Goal

Every defect code a stage's rule view emits is owned by that stage in `graphitron_defect_type.owner_stage`. Today `graphitron_entry_defect_rule`, the rule of the `entry-defects` stage, also emits `ROUTE_AMBIGUOUS` and `NO_ROUTE_TO_TARGET` at the declaration of an output field whose need for a table no claim covers, while `graphitron_defect_type` names `occurrence-depths` as their owner. `EntryDefects.derive` writes whatever the view emits, so the rows reach `graphitron_entry_defect`. Nothing is lost today, because both stages belong to one gatherer and share its sweep. But the ownership metadata no longer says who writes a code. An oracle or a sweep scoped to an owner's codes misses these rows: `StageAnswerAgreementTest` had to compare the union of the defect rules rather than each rule with its owner's codes. When this lands, each code has exactly one writing stage and the metadata names it: either the field-need arms move to the stage that owns the route codes, or those arms get codes of their own owned by `entry-defects`. A gate then holds every rule view to emitting only its stage's codes.

## Evidence

* Found on 2026-11-11, when `StageAnswerAgreementTest` began comparing every stage table with its rule view over the whole fact-document population. Its previous fixtures held no defects, so this direction of the comparison had never been exercised.
* Example rows: `defects/ordering-across-participants` lines 30-37 and `defects/fetcher-producers` lines 7 and 9. The table and the rule view agree row for row there, so this is a gap in the metadata and in the oracle, not wrong output.
