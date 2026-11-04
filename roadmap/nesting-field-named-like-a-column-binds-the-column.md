---
id: R1150
title: "A nesting field named like a column of its parent binds the column, not its nested fields"
status: Backlog
bucket: bug
priority: 1
theme: classification-model
depends-on: []
created: 2026-11-04
last-updated: 2026-11-04
---

# A nesting field named like a column of its parent binds the column, not its nested fields

## Goal

A field returning a nesting type (an object type with no `@table`, read off its parent's row) stays a
nesting field even when the parent's table has a column whose name matches the field, so the nested
fields keep the bindings their author wrote, an `@externalField` computed in the parent's query
among them. Today the column wins: the field binds the column by name, and the nested fields are
then looked up as accessors on the column's Java type, which refuses a valid schema on both the
pinned RCs and 10-SNAPSHOT.

## Found on

utdanningsregisteret, fs-plattform main `f7815b58be`, built with its own catalog (2026-11-04).
`Kommune.gyldigPeriode: GyldigPeriodeForKommune`, where `GyldigPeriodeForKommune` is a nesting type
whose `gyldigFra` and `gyldigTil` are `@externalField` methods of `KommuneService` returning
`DatoConditions.fraDato(kommune.GYLDIG_PERIODE)` and `tilDato(...)`, and `kodeverk.kommune` has a
column `gyldig_periode` of a date-range domain. The field binds `GYLDIG_PERIODE`, and the nested fields
are refused with "no accessor on `org.jooq.postgres.extensions.types.LocalDateRange` matched SDL field
… no method named `getGyldigFra`". Eight period types refuse alike (`Kommune`, `Fylke`, `URegLand`,
`URegOrganisasjon`, `Utdanningsinstans` and `Utdanningsmulighet` `dager` and `tider`); the two
akkreditering period types, whose tables have no such column, work. The schemas declare these types
with `@table` on the parent's own table today, which 10-SNAPSHOT refuses as a join with no key; Alf
ruled that refusal stays and the nesting pattern is the remedy, so this bug blocks it.
