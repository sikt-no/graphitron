---
id: R1150
title: "A nesting field named like a column of its parent binds the column, not its nested fields"
status: In Review
bucket: bug
priority: 1
theme: classification-model
depends-on: []
created: 2026-11-04
last-updated: 2026-11-05
---

# A nesting field named like a column of its parent binds the column, not its nested fields

## Goal

A field returning a nesting type (an object type with no `@table`, whose fields are read off the
parent's own row) stays a nesting field even when the parent's table has a column whose name matches
the field, so the nested fields keep the bindings their author wrote, an `@externalField` computed in
the parent's query among them. Today the column wins: its Java type becomes the class the nesting type
is read from, and every nested field is then looked up as a getter on that class and refused. Valid
schemas are refused on both the pinned RCs and 10-SNAPSHOT, and the refusal only bites when the
column's Java type is outside `java.*`, so a `daterange`, `jsonb` or composite-typed column triggers it
while a `date` or `text` column of the same name does not.

The minimal pair, mined from the consumer schema below (`kommune` has a column `gyldig_periode`; the
akkreditering parent's table has no column named like the field; the second parent's type and table
names are abbreviated):

```graphql
type Kommune @table(name: "kommune") {
  gyldigPeriode: GyldigPeriodeForKommune        # refused today: binds column gyldig_periode
}
type GyldigPeriodeForKommune {
  gyldigFra: Date @externalField(reference: {className: "...KommuneService"})
  gyldigTil: Date @externalField(reference: {className: "...KommuneService"})
}

type Organisasjonsakkreditering @table(name: "organisasjonsakkreditering") {
  gyldigPeriode: GyldigPeriodeForOrganisasjonsakkreditering   # works: no such column
}
```

After this item both read the same way: `gyldigPeriode` is a nesting field, and `gyldigFra` and
`gyldigTil` are computed in the parent's query by their `@externalField` methods.

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

## Cause

The column is never bound by the field classifier or by any store relation. It enters through the
record-binding walk, `RecordBindingResolver`, which decides which Java class backs each SDL type
before fields are classified:

1. `groundRootProducers` binds every `@table` object to its jOOQ record class
   (`ProducerBinding.RootTable`): `Kommune` is backed by `KommuneRecord`.
2. `propagateAccessorChains` then walks every bound type's fields, and for each field returning an
   object type with no binding yet, `propagateResultChildren` probes the parent class for an accessor
   named like the field. `KommuneRecord.getGyldigPeriode()` exists, because jOOQ generates a getter per
   column, and returns `LocalDateRange`. `shouldBind` only filters primitives, `java.*` and enums, so
   `GyldigPeriodeForKommune` is bound to `LocalDateRange` as a `ProducerBinding.ParentAccessor`.
3. With a class behind it, the nesting type now also classifies as a record-backed result type
   (`TypeBuilder.lookAheadVerdict` answers a `ResultType`). `TypeBuilder.isNestingEdgeTarget` still
   admits the edge as nesting (the mixed-source arm), but the type's own visit registers the result
   type and its fields are classified on a record parent, where `FieldBuilder.resolveRecordAccessor`
   (through `ClassAccessorResolver.resolve`) is the one producer of the "no accessor on `…` matched SDL
   field" text. Which of the type's visit or the post-walk `reachableSourceShapes` check surfaces it
   first is not pinned down and does not matter to the fix, which removes the binding both read.

The akkreditering types work only because `organisasjonsakkreditering`'s record has no getter named
like the field, so the probe finds nothing and the type stays a pure nesting target.

The hop in step 2 reads a field off a class that is never read through accessors: a `@table` parent's
fields are projected from its row (`FieldBuilder.resolveRecordAccessor` returns early for a
`GraphitronType.JooqRecordCarrier`), so an accessor on its record class is not how any of its fields
is read. The store already states the right rule. `graphitron_type_backing_rule` seeds its accessor
closure from producers (`@service`, `@externalField`) only and never from a `BOUND_TABLE` row, so on
this schema the store gives the nesting type no backing and `graphitron_result` a `ROW`; the walk is the
outlier. The R876 move of `graphitron_field_target_column`'s readers is unrelated: neither that relation
nor `graphitron_occurrence_column` binds an object-typed field, and nothing here reads either.

## Implementation

* `RecordBindingResolver.propagateResultChildren`: skip a parent SDL type that carries `@table`. Its
  fields are read off its row, so no accessor on its record class grounds a child type. The check is
  the same `obj.hasAppliedDirective(DIR_TABLE)` `groundRootProducers` uses, so the two cannot disagree
  on which parents are table-bound. Placed at the top of the method, it also stops
  `recordAccessorGate` recording near-miss reasons off a `@table` parent, which were never a near miss
  of anything the author wrote.
* The skip is on the parent's directive, not on how the parent got its class. A `@table` type that a
  `@service` also returns (bound by `RootService` to the same record class) is still read off its row
  by the field classifier, so it hops no further either. This is where the walk is stricter than the
  store's closure, which would hop off such a type because it was reached by a producer; see Out of
  scope.
* The javadoc on `propagateResultChildren` and the comment above its `resultMemo` early-continue gain
  one sentence each saying why a `@table` parent is not walked.
* No change to `FieldBuilder`, `TypeBuilder` or the store. With the bogus binding gone the nesting type
  is a directiveless nesting target again and the existing `NestingField` arm classifies it, nested
  `@externalField` fields included, exactly as it already does for the akkreditering types.

## Tests

* A scenario fact document, `graphitron-model/src/test/resources/facts/scenarios/nesting-field-named-like-a-column.graphqls`.
  Sakila already has the column it needs: `outbox.payload` is `jsonb`, which jOOQ maps to
  `org.jooq.JSONB`, a non-`java.*` class with no getter named like an SDL field, so no `init.sql`
  change. The document declares `type OutboxMessage @table(name: "outbox")` with
  `payload: OutboxPayload`, where `OutboxPayload` has no `@table` and carries one column-read field
  (`type: String`) and one `@externalField` field computed over `outbox`, plus a second parent field
  whose name matches no column for the contrast. Being a scenario is itself the main assertion:
  `FactExpectationTest` checks the store records no defect, and `GenerateTestSchemas` generates it,
  which is the step that fails today. On top of that it asserts, on the store's behaviour and not on
  generated text: `graphitron_type_backing` holds no row for `OutboxPayload`, `graphitron_result`
  holds `OutboxPayload, ROW`, and `graphitron_reader` reads the `@externalField` field as `EXPRESSION`
  and the column field by whatever `graphitron_reader` records for a nested column read (not `ACCESSOR`).
  Confirm at pickup that `outbox` is in the scenario catalog
  and pick the exact column names off `init.sql`.
* The `@externalField` method goes in a new `OutboxExtensions` class beside `FilmExtensions` in
  `graphitron-sakila-service` (`no.sikt.graphitron.rewrite.test.extensions`), returning a
  `Field<String>` over the `outbox` table.
* Before the fix, run the document once and confirm it fails the way the consumer schema does ("no
  accessor on `org.jooq.JSONB`"), so the test is known to cover the bug and not a neighbour of it.

## Out of scope

* The input axis. `propagateInputChildren` has the same shape, but `@table` on an input object is inert
  and grounds nothing (see the comment in `groundRootProducers`), so an input parent reaches a record
  class only through a producer, such as a `@service` parameter typed as a jOOQ record. That is the
  producer-reached case of the next bullet, on the other axis, and nobody has reported it.
* The store's closure hopping off a `@table` type a producer returns. It is a divergence between walk
  and store in a case nobody has reported, and `graphitron_result` deliberately calls a table record a
  service hands back an `OBJECT`, so aligning it is a store design question of its own, not this bug.
  File a Backlog item if the fixture above, extended with a `@service` returning `OutboxRecord`, shows
  the store and the walk disagreeing on what `OutboxPayload` holds.
* fs-plattform itself. How the schema change from `@table` period types to nesting types reaches the
  teams is Alf's call.

## Reviewer findings

### Round 1: Spec → Ready, signed off (session 01WkW2JF6J1RqPVCmCmtj636, 2026-11-05)

The goal reads on its own and the cause holds against the tree: `propagateResultChildren` probes
every bound parent class, a `@table` parent included, and `shouldBind` lets `LocalDateRange` and
`JSONB` through. The fix narrows an existing walk to the rule the store already states, which is the
architecture-fit answer. Three notes for the implementer, none of which changes the design:

1. The reader assertion can be pinned now: under a ROW, `graphitron_reader.reads` takes `COLUMN` for
   the nested column field and `NESTED` for `OutboxMessage.payload` itself, beside `EXPRESSION` for
   the `@externalField` field.
2. The store assertions already hold before the fix, since the store gives `OutboxPayload` no backing
   today; they pin the walk and the store to agree afterwards. The evidence that the bug is fixed is
   the scenario generating, so the "fails before the fix" run in Tests is the step not to skip.
3. A behaviour change worth one line in the commit message: a column whose Java type is a composite
   (UDT) record, exposed as an object type whose fields are the UDT's attributes, is bound through
   the record getter today and becomes a nesting type afterwards, its fields then looked up as columns
   of the parent. No fixture or reported schema uses that shape and the store already reads it the new
   way, so it does not block; if someone reports it, it is its own item.
