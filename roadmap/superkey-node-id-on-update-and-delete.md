---
id: R1093
title: "UPDATE and DELETE accept a node id that encodes the primary key plus extra columns"
status: Backlog
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-23
last-updated: 2026-10-23
---

# UPDATE and DELETE accept a node id that encodes the primary key plus extra columns

## Goal

A type whose node id encodes its whole primary key plus extra non-key columns can be updated and deleted by that id, the way Graphitron 9 allowed: the mutation filters on the primary-key columns the id carries and ignores the rest. Today an UPDATE taking such an id is refused at build time, and a DELETE taking one filters on every encoded column, so an id whose extra column has gone stale deletes nothing. sis needs this to keep the node ids its clients and sibling subgraphs already store.

A *node id* is the opaque `ID` Graphitron encodes from a fixed list of columns (the node key, `__NODE_KEY_COLUMNS` on the jOOQ table) and decodes back into those columns. Nothing requires the node key to be a declared primary or unique key, so a node key that is a strict superset of the primary key (a *superkey*) is already accepted for encoding, decoding, `node` and `_entities`. Only the two DML walkers stop on it.

The case from sis, whose table `POLITIATTESTGODKJENT` has the four-column primary key `(INSTITUSJONSNR_EIER, PLNR, STUDIEPROGRAMKODE, LISENSTYPEKODE)` and a node key of those four plus `DATO_FORSTE_GYLDIGE`:

```graphql
type GodkjentPolitiattest implements Node @table(name: "POLITIATTESTGODKJENT") @key(fields: "id") {
  id: ID!
}

input EndreGodkjentePolitiattesterInput @table(name: "POLITIATTESTGODKJENT") {
  id: ID!
  fraDato: Date @field(name: "DATO_FORSTE_GYLDIGE")
  tilDato: Date
  merknad: String
}

input SlettGodkjentePolitiattester @table(name: "POLITIATTESTGODKJENT") {
  id: ID!
}

type Mutation {
  endreGodkjentePolitiattester(input: [EndreGodkjentePolitiattesterInput!]!): [GodkjentPolitiattest]
    @mutation(typeName: UPDATE, table: "POLITIATTESTGODKJENT")
  slettGodkjentePolitiattester(input: [SlettGodkjentePolitiattester!]!): SlettGodkjentePolitiattesterPayload
    @mutation(typeName: DELETE, table: "POLITIATTESTGODKJENT")
}
```

After this lands, `endreGodkjentePolitiattester` updates the row the id's four primary-key columns name; the id's date is neither a WHERE filter nor a SET value, and `fraDato` is the only writer of `DATO_FORSTE_GYLDIGE`. `slettGodkjentePolitiattester` deletes by the same four columns. Reads are unchanged: `node` and `_entities` keep filtering on all five columns, so a stale id resolves to null there. The id an UPDATE returns is encoded from the re-read row, so after a `fraDato` change it carries the new date.

## Scope

The rule applies to an *own-columns carrier*: an input field, here the `@nodeId` `id`, whose decoded columns all belong to the mutated table itself rather than reaching it through a foreign key. When that carrier's in-key columns cover the whole *matched key* (the primary or unique key the walker picks as the row's identity, `MatchedKeys.firstCovered`, primary key first), its out-of-key columns are dropped from both partitions. Everything else keeps today's verdict:

* Partial coverage, an own-columns carrier with three of the four primary-key columns plus extras, stays `UpdateRowsError.MixedCarrierKeyMembership`: writing part of the identity moves the row, which is the reason that rejection exists.
* Cross-table FK straddlers, self-FK carriers and plain `@field` inputs keep their current partitioning.
* A `@nodeId @reference` from another table to a superkey node, refused unless the FK covers every node-key column (the coverage check in `BuildContext`), is out of scope; sis has none.

Ignoring rather than writing the extra columns is deliberate. Routing the id's date to SET would put it in the same SET group as `fraDato` with a decode present, and the agreement preamble `TypeFetcherGenerator.emitSetAgreementPreamble` would throw at runtime in exactly the change-the-date case.

## Known sites

Anchored on symbols; verify each at pickup.

* `UpdateRowsWalker`, Stage 6, the `CarrierRole.OwnColumns` arm of the straddle switch: today unconditionally `MixedCarrierKeyMembership`. Needs a whole-key-covered branch that routes the in-key columns to WHERE with their decode slots and drops the rest. `keyGroupsOf` already carries each `KeyColumn.decodeSlot` and its javadoc allows non-contiguous slices, so a dropped slot should need no emitter change there. `AgreementObligation` (Stage 6a) and `PlainColumnCollision` (Stage 6b) should not fire on the id/`fraDato` overlap once the id's date is out of both partitions; confirm.
* `DeleteRowsWalker.walk`: every decoded column of every contribution becomes a WHERE `KeyColumn`. Needs the same filter to the matched key's columns. Both exits consume `whereColumns`, `DeleteRows.Identified` and the `missingAsDeleted` path (`idempotentByPrimaryKey`), so both need it.
* The bulk UPDATE duplicate-lookup-key guard in `TypeFetcherGenerator` (the `seenKeys` set, comment "the encoded NodeId string is a stable identity for the decoded tuple") keys rows on the raw encoded id. Once the date leaves the WHERE, two ids differing only in the date pass as distinct and hit the same row: the silently-lost-row hazard the guard exists to stop. It must compare the decoded matched-key values instead. Check whether the bulk DELETE path has an equivalent guard or relies on its per-row "matched zero rows" check.
* DELETE payload ids. The payload-returning DELETE arms read back `RETURNING` over the primary-key fields (`buildPkFieldList`), and `missingRowEcho` builds the `missingAsDeleted` echo from the primary-key values the client sent. For a superkey node neither record holds the extra node-key column, so encoding `ider` from it needs either a wider `RETURNING` or a different source. Settle at Spec what the returned id carries, in particular for an echoed missing row, where only the client's own (possibly stale) id knows the date.

## Tests

No fixture in `graphitron-sakila-db` appears to have a node key that is a strict superkey of its primary key; node-key metadata comes from `NodeIdFixtureGenerator` in `graphitron-fixtures-codegen`, so one likely needs adding. Coverage should pin: UPDATE by a stale-date id updates the row and returns the re-read id; DELETE by a stale-date id deletes the row (single and bulk, with and without `missingAsDeleted`); two bulk rows whose ids differ only in the extra column are rejected as duplicates; partial key coverage is still `MixedCarrierKeyMembership`.

## Other solutions we've considered

* **Filter on every decoded column**, which is DELETE's behaviour today and would make UPDATE behave the same. It turns the extra column into an optimistic-concurrency check, so an id minted before a `fraDato` change silently matches nothing. That breaks every id stored outside sis, which is the problem this item exists to solve, and it contradicts Graphitron 9, where the extra columns were marked unchanged and only the primary key reached the WHERE.
* **Drop the date from the node id.** sis did this on its branch to get past the generator; it invalidates every id already stored by clients and other subgraphs.

## Provenance

Requested 2026-10-23 by the sis Graphitron 10 port session, with the user's approval on the sis side. The Graphitron 9 behaviour it targets: the id decoded into all five columns, all five were marked jOOQ-unchanged, and `batchUpdate`/`batchDelete` put the primary-key columns in the WHERE.
