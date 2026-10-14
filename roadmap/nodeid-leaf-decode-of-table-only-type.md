---
id: R1032
title: "A @nodeId leaf on a DML or filter input decodes a @table-only type from its catalog node metadata"
status: Backlog
bucket: bug
priority: 2
theme: classification-model
depends-on: []
created: 2026-10-14
last-updated: 2026-10-14
---

# A @nodeId leaf on a DML or filter input decodes a @table-only type from its catalog node metadata

## Goal

A `@nodeId(typeName: T)` input field on a `@mutation` (DML) input or a query filter input builds when `T` is a `@table` type without `implements Node` whose table carries catalog node metadata (`__NODE_TYPE_ID` and `__NODE_KEY_COLUMNS`). It decodes the id against that metadata's type id and key columns, the same answer the `@service` record path already gives through `BuildContext.resolveNodeIdRecordDecode`. Today the leaf path, `NodeIdLeafResolver`, rejects with "'T' is not a node type. Annotate 'T' with @node." That fix is closed to a consumer whose `T` is a federation stub owned by another subgraph, because `@node` requires `implements Node`, which the stub's owner publishes, not this subgraph. Graphitron 9 decoded these ids from catalog metadata, and clients still send them, so this is a migration-parity gap.

## Evidence

Reported from sis on 2026-10-14 against graphitron trunk `b9616e463` (R1029 In Review), after R1029 removed the tenant-fold crash. Some code tables moved to the fskode subgraph and are now stubs in sis, for example:

```graphql
type Studieniva @table @key(fields: "kode") {
  kode: String @field(name: "STUDIENIVAKODE") @shareable
}
```

17 `@nodeId` fields still reject at `NodeIdLeafResolver` with "is not a node type", plus 12 roll-up errors for the inputs that contain them. The targets are `Studieniva` (5), `Vektingstype` (3), `Tidsenhet` (2), and one each of `Nusklassifikasjon`, `Studieklassifikasjon`, `Utdanningsomrade`, `Godkjenninginstans`, `Lovgodkjenning`, `Studieprogramjustering` and `Undervisningsorganisering`. The positions:

- `@mutation(typeName: UPDATE, table: "studieprogram")` inputs: `EndreStudieprogramInput`, `EndreStudieprogramGodkjenningOgRapporteringInput`, `EndreStudieprogramVektingInput`, `EndreStudieprogramVarighetInput`.
- `@mutation(typeName: UPDATE)` on `EMNEKOMBINASJON`: `OppdaterStudieoppbygningsdel{Varighet,Minimumsvekting,Maksimumsvekting}Input`, two of them through `@reference(path: [{key: "EMNEKOMBINASJON__KRAV/MAX__VEKTINGSTYPE__FK"}])`.
- `@mutation(typeName: UPDATE)` on `EMNEVALG_I_EMNEKOMBINASJON`: `OppdaterBegrensningerForEmnevalgStudienivaIntervallInput.fra/tilStudienivaId`, through `@reference` on the `FRA`/`TIL` `STUDIENIVA` foreign keys.
- Filter inputs: `StudieprogramV2FilterInput.studienivaer` and `StudieprogramV3FilterInput.studienivaer`, both `[ID!]`.

The sharpest pair: `fraStudienivaId` and `tilStudienivaId`, with the same type and the same `@reference` keys, build in `LeggTilBegrensningerForEmnevalgStudienivaIntervallInput`, which sits under a `@service` mutation and goes through `resolveNodeIdRecordDecode`. In `OppdaterBegrensningerForEmnevalgStudienivaIntervallInput`, under `@mutation(typeName: UPDATE)`, they reject. sis keeps temporary stub commits that bind these fields to plain foreign-key columns until this lands.

## Scope notes for the Spec

- **Where it rejects.** The leaf path's decode is a `HelperRef.Decode` carried on `CallSiteExtraction.NodeIdDecodeKeys`, and `BuildContext.resolveDecodeHelperForType` mints that only from a `NodeType`. The comment at the rejection says a table-keyed fallback used to answer here and was removed with the synthesis shims. The Spec says why that removal is not the precedent: the fallback being asked for keys on the authored type name and reads only the table's catalog metadata, the same second arm `BuildContext.resolveTargetKeys` already takes for the record path. It does not bring back synthesized node types.
- **What gets emitted.** A decode helper per `@table`-only target with node metadata, or one per type id and key shape that does not need a `NodeType`. Either way the encoder must accept the type id the metadata names, which it already does for the record path's `RecordKeyDecode`.
- **The tenant fold.** R1029 taught the fold to read a `@table`-only target from the decode's carrier for record decodes and kept `NodeIdDecodeKeys` rejecting in the classifier. Once that carrier can name a `@table`-only target, the fold's `NodeIdDecodeKeys` arm needs the same treatment, including R1029's answer for a key tuple that carries the tenant column.
- **Not R1027.** The tenant cascade work acts in the tenant fold. These rejections happen earlier, in classification. After R1027 and this item, sis should be left with only the `FeideBruker` rejection.
- **Tests.** The R1029 reproduction `aScalarArgumentOrFilterFieldNamingATableOnlyTypeRejectsInTheClassifier` pins today's rejection and flips under this item. Add a pipeline case per position (DML input with and without `@reference`, filter input as both a scalar and a list) and an execution case that decodes a metadata-encoded id against a `@table`-only target.
