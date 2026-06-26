---
id: R389
title: "First-class discriminated joined-table inheritance (participants on their own tables)"
status: Backlog
bucket: feature
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-26
last-updated: 2026-06-26
---

# First-class discriminated joined-table inheritance (participants on their own tables)

graphitron supports two polymorphic shapes: single-table discriminated inheritance
(`TableInterfaceType`: `@table @discriminate`, all participants share one table, distinguished by a
discriminator column) and multi-table polymorphism (`MultiTablePolymorphicEmitter`: wholly independent
PK-bearing participant tables UNION'd together). It does **not** support **joined-table (class-table)
inheritance**: a discriminated base table plus a per-concrete-type detail table joined by FK, where the
participant's distinguishing columns live on its own table.

Today an author who has this model is forced to contort it into the single-table path: declare every
participant `@table(name: "<base>")` and put `@reference(path: [<FK to detail table>])` on every
detail-table field, because the discriminated fetcher is hardwired single-table. Specifically
`TypeFetcherGenerator.buildQueryTableInterfaceFieldFetcher` selects `FROM tableLocal` (the interface
table) and projects **every** participant's `$fields(sel, tableLocal, env)` against that one shared table
(line ~1005); `$fields`'s `table` parameter is typed as the participant's own jOOQ table class
(`TypeClassGenerator` line ~220) and a plain column emits `fields.add(table.<COLUMN>)` (line ~277). So a
participant on its own detail table fails to compile two ways: the `$fields` parameter type
(`<DetailTable>`) cannot accept the `Subjekt` argument, and a detail column projected against the base
table (`subjektTable.NAVN`) names a column the base table does not have. The `@reference`-per-field
workaround is the escape hatch out of that wrong column path. The visible symptom is
`fields.addAll(FeideApplikasjon.$fields(env.getSelectionSet(), subjektTable, env))` for a subtype whose
data does not live on `subjekt`.

Proper support means a participant declares its own `@table`, and the discriminated emitter joins each
participant's detail table to the discriminated base and projects via the participant's own table/alias
(the `MultiTablePolymorphicEmitter` stage-2 per-typename dispatch already threads each participant's own
table into `$fields(env.getSelectionSet(), t, env)` and is the closest existing template). This is a
model-level change: `ParticipantRef.TableBound` would carry its own table and join path, the emitter would
thread per-participant aliases, the validator would mirror the new invariants, and pipeline tests would
pin the shape. It deserves its own Spec rather than being bolted onto a bug fix.

Near-term correctness of the workaround is handled by R388 (qualify the discriminator column across the
three emission sites + reject the discriminator-column-with-`@reference` contradiction + add the missing
execution fixture). R389 is the deeper feature that removes the need for the workaround.
