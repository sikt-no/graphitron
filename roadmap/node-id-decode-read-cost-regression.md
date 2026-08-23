---
id: R811
title: "The node-id decode read costs fifty seconds and nobody measured it"
status: Backlog
bucket: store
priority: 2
theme: nodeid
depends-on: []
created: 2026-08-23
last-updated: 2026-08-23
---

# The node-id decode read costs fifty seconds and nobody measured it

`intent_node_id_decode` is the fact schema's heaviest derived read, and one read of it
against a real capture now takes about fifty seconds. It took about five and a half a few
days earlier. Nothing about the relation's answer changed: it returns the same 43 rows in
both measurements. What the number costs is not a surface budget yet, no reader on the
critical path having named it, which is exactly why it moved ten times over without any gate
saying so.

## What was measured

A timing probe over the sakila example's own schema captured against the sakila catalog
(`CapturedStore.ofCatalog`), timing `SELECT count(*)` per relation inside one capture. Two
passes per run, and each figure below reproduced across separate runs.

[cols="3,2,2"]
|===
| Tree | `intent_node_id_decode` | `intent_argmapping_projection_defect`

| trunk at `200fd26`
| 5054-6090 ms
| 761-1200 ms

| trunk at `424a0e4`
| 49728-50462 ms
| 107-199 ms

| `424a0e4` plus the step-hop registration (shipped at `37c5814`)
| 13215-13270 ms
| 93-189 ms
|===

Row counts are unchanged at every point: 43 for the decode, 0 for the defect relation. So
this is a cost change and not an answer change, and the second column is there to show that
the same window improved one heavy relation while the first regressed.

## What was not established

Which commit did it. Three commits touch the fact schema DDL between those two trunk points:
`272ef13` (the two diagnostics-drain registrations), `ed424f6` (query relations carry facts
rather than rendered strings) and `42614bd` (the family-page metadata relations).

The registration of `intent_resolved_type_binding` in the first of them is the obvious
suspect, on two grounds that are suggestive and not evidence. It is the relation the decode
family reaches through, thirteen view bodies name it, and materializing it changes what every
one of those namings plans against. And the same two registrations are already on record
moving a second surface by two orders of magnitude in the *other* direction, the inlay-hint
read going from 10205 ms to 65 ms, attributed by a same-fixture control.

An attempt at that control for this relation was run and produced nothing usable: the script
that reverted the registrations corrupted the DDL, the model build failed, and the probe
silently measured the previously installed model instead. The failure is worth recording
because the run *looked* like a result, reproducing the un-reverted tree's figures to within
noise.

## The control to run

Per relation, one candidate at a time, rebuilding `graphitron-model` between each:

. Check out `graphitron-model.sql` at the candidate commit's parent.
. `mvn install -pl :graphitron-model -Plocal-db` and confirm the DDL actually executed;
  a failed model build leaves the previous artifact installed and the probe will read it.
. Time `intent_node_id_decode` against a sakila capture and compare row counts.

Reverting `272ef13` alone also reverts the other registration in the same commit, which is
fine for attribution and not for the fix: the two are independently registrable.

## Why this is not simply reverted

The registrations that are the leading suspect bought a diagnostics drain going from past
seven minutes to 191 ms against a 3 s interactive budget, and an inlay read going from four
times over its budget to inside it. Both are surfaces a developer waits on. If the control
confirms the attribution, the question is which term under `intent_resolved_type_binding`
the decode family reaches that the drain does not, and whether that term wants a registration
of its own, not whether to give the drain its minutes back.

The step-hop registration this item was found alongside already recovers most of the gap
without touching the suspect, which is some evidence that the residual is a distinct term
rather than the registration as such.

## What would close it

A named ceiling. The scan-count surface pins already exist for six reader surfaces and the
rule they carry is that a ceiling is finished when it has been seen to fail, not when it
passes. No pin covers a derived relation's own read cost, which is the gap that let a
ten-times move land unremarked. Whether the answer is a pin over this relation, over the
derived-read stratum, or a rule that a registration states which readers it was measured
against, is the item's to decide.
