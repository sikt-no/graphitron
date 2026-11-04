---
id: R1148
title: "The generated validation pre-step compiles against jOOQ 3.19"
status: Backlog
bucket: bug
priority: 2
theme: codegen-correctness
depends-on: []
created: 2026-11-02
last-updated: 2026-11-04
---

# The generated validation pre-step compiles against jOOQ 3.19

## Goal

A consumer whose jOOQ is 3.19 compiles the code Graphitron generates for a `@service` with a jOOQ
record parameter again. R747's per-column validation guard calls `Record.touched(Field)`, which jOOQ
added in 3.20; jOOQ 3.19 only has `changed(Field)`, which 3.20 still has, so emitting `changed` works
on both.

## Evidence

sis is on jOOQ 3.19.18 (`org.jooq.pro`, from kjerneapi-codegen). Its build against a `10-SNAPSHOT`
from `6904099c5` (2026-11-02, with R747's `76748cba8`) fails with 152 compile errors in the generated
`MutationFetchers`, all of the form "The method touched(TableField<VitnemalstekstRecord,String>) is
undefined for the type VitnemalstekstRecord". The guard is emitted in
`TypeFetcherGenerator.validateSubject` (`if ($L.touched(...))`, the `WrittenColumns` arm). `javap` on
the jOOQ jars: `touched(Field)` is absent from 3.19.18 and present in 3.20.6 and 3.20.11, and
`changed(Field)` is present in all three. The `jooq-version-lag` rule only warns, so nothing told
sis before javac did. Every Graphitron release after R747 is affected, so sis cannot move its pin
past RC45 until this lands or it upgrades jOOQ.

## For the plan

Emitting `changed(Field)` is the smallest fix; check whether anything else in the generated code
uses a 3.20-only API (a fixture compiled against 3.19 would catch it). The alternative is to state a
minimum jOOQ version and make `jooq-version-lag` an error below it, which moves the cost to every
consumer still on 3.19.

Either way the guard reads the flag jOOQ clears for a NOT NULL column holding `null` when a record
is filled through `fromArray` (`Tools.resetChangedOnNotNull`, in 3.19.18 and 3.20). An explicit
`null` for such a column is therefore neither validated nor written: sis's `endreKull` with
`navn: null` keeps the old name and commits the element's other fields (read from code, not run).
Whether the guard should see that `null` is a separate question from this item.
