---
id: R1059
title: "A list-of-input filter whose element fields share a @reference path renders as one correlated EXISTS"
status: Backlog
bucket: feature
depends-on: [filter-list-of-input-field-silently-dropped]
created: 2026-10-17
last-updated: 2026-10-17
---

# A list-of-input filter whose element fields share a @reference path renders as one correlated EXISTS

## Goal

A list-of-input filter field whose element fields all reach their columns through the same `@reference` path builds and filters, instead of being the build error R1057 makes it. The list renders as one correlated `EXISTS` over that path. Its `WHERE` correlates to the outer row and compares the row of reached columns against the element rows, for example `EXISTS (SELECT 1 FROM vurderingsenhet ve JOIN vurderingstid vt ON ... WHERE <correlation> AND row(vt.ARSTALL_GJELDER_I, vt.TERMINKODE_GJELDER_I).in(<rows>))`. It follows R1057's rules for an absent, null or empty list and for null elements. This is the list form of the FK-target `EXISTS` a single-valued nested input with `@reference` fields already generates.

## Context

Follow-up from the sis port (trunk `145ef1ae5`), after R1057 landed its row-`IN` with `@nodeId`/`@reference` element fields rejected (R1057 § What is rejected at build time). Of the five sis filters R1057 rejects, three were fixed in the schema. Two had a pointless `@reference` to a column on the table itself, and one had a nullable element field. The remaining two need the join, and sis cannot build until they are supported:

```graphql
# beta, Query.vurderingsmeldinger(filter: ...) -> [Vurderingsmelding] on VURDERINGSMELDING
input QueryVurderingsmeldingerUndervisningsterminFilterInput {
    arstall: Int! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}]) @field(name: "ARSTALL_GJELDER_I")
    betegnelse: Undervisningsterminbetegnelse! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}]) @field(name: "TERMINKODE_GJELDER_I")
}
# filter field: undervisningsterminkoder: [QueryVurderingsmeldingerUndervisningsterminFilterInput]

# experimental, Query.resultater -> [Resultat] on STUDENTVURDKOMBPROTOKOLL
input ResultaterFilterTerminInput {
  arstall: Int! @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}, {table: "TERMIN"}])
  betegnelse: String! @field(name: "TERMINKODE") @reference(path: [{table: "VURDERINGSENHET"}, {key: "VURDERINGSENHET__HAR__VURDERINGSTID__FK"}, {table: "TERMIN"}])
}
# filter field: terminer: [ResultaterFilterTerminInput!]
```

Today's diagnostic: "input field 'arstall' ... is an element field of list-of-input filter field 'undervisningsterminkoder', whose elements are compared as rows: it carries @nodeId or @reference, whose column is reached through a key or a join a row of plain values cannot express".

Scope for Spec to settle:
- Every element field on the same path is the case sis needs and the core of the item.
- Element fields with different paths, or a mix of own columns and a path, could stay a build error, or be served by joining every path in the one `EXISTS`. The reporter leaves it to us. Keeping the rejection is the smaller step, and nobody has shown a consumer who needs the mixed form.
- Element `@nodeId` fields stay rejected unless Spec finds they fall out for free.
- Why a separate item rather than R1057 rework: R1057 scoped the rejection deliberately and is In Review against that scope. This item lifts one bullet of that rejection list and reuses R1057's row-`IN` carrier and the existing FK-target `EXISTS` path rendering.
