---
id: R1042
title: "What an explicit null on a @nodeId UPDATE input means, and whether it agrees with the INSERT rule"
status: In Progress
bucket: bug
priority: 4
theme: mutation-write
depends-on: []
created: 2026-10-15
last-updated: 2026-10-15
---

# What an explicit null on a @nodeId UPDATE input means, and whether it agrees with the INSERT rule

## Goal

An author of a `@mutation(typeName: UPDATE)` input with a nullable `@nodeId` field can say what an explicit `null` on it does, and the answer agrees with what the same `null` does on an INSERT. The answer this item settles is the same on every write rail: a `@nodeId` names a *reference* (a whole foreign-key tuple), so an explicit `null` means "no reference". The reference's own columns are written `NULL`, and a column it shares with another field on the input takes that field's value. On UPDATE the first half already holds, since R880 made a nullable reference *clear*. The second half does not: a cleared reference that shares a SET column with another field on the input conflicts with that field. Once this lands, the null contributes nothing to the shared column and the update goes through.

The sakila fixture `UpdateEndorsementOverlapInput` has the shape. `filmRef` and `endorsedFilm` both write `film_endorsement.endorsed_film`, which is not part of the matched key (the key the statement finds the row by, here `endorsement_id`):

```graphql
input UpdateEndorsementOverlapInput {
    endorsementId: Int! @field(name: "endorsement_id")
    filmRef: ID @nodeId(typeName: "Film")            # writes endorsed_film
    endorsedFilm: Int @field(name: "endorsed_film")  # writes endorsed_film
}
```

```graphql
# Works today and after: sets endorsed_film to 2
updateEndorsementOverlap(in: {endorsementId: 1, filmRef: "<Film 2>", endorsedFilm: 2})
# Fails today with "Conflicting values supplied for input fields 'filmRef', 'endorsedFilm'";
# sets endorsed_film to 2 once this lands
updateEndorsementOverlap(in: {endorsementId: 1, filmRef: null, endorsedFilm: 2})
# Still fails once this lands: a plain field names a column, so its null is a value for that column
updateEndorsementOverlap(in: {endorsementId: 1, filmRef: "<Film 3>", endorsedFilm: null})
```

What does not change is equally part of the answer: an explicit `null` on a nullable reference does not become "leave unchanged", so sis's `endreVeiledningsforhold`, which sends `null` for references over NOT NULL columns, still gets a refusal. R881 owns making that refusal name the input field instead of surfacing as the database's constraint violation (see Decision).

## Decision

Two questions, both answered here.

**What an explicit `null` on a nullable `@nodeId` UPDATE field means: it clears (unchanged from R880).** The candidates were *clear*, *leave unchanged* (treat the `null` as an omission), and a per-field author choice. Clear wins on four grounds.

- It is the only way to remove a reference. R880 measured sis's UPDATE inputs: of 31 optional references, 29 sit over nullable columns where `NULL` is the live encoding of "no reference" (no campus, no exam room, no bound on a validity window). Under *leave unchanged* none of them can be removed, and `ID!` is not an alternative, because in a GraphQL input it also makes the field mandatory on every call.
- It is what a `null` already means everywhere else on the input. A plain nullable `@field` on the same UPDATE writes `NULL` (R1040's Goal states this too: "An explicit `null` on a plain nullable column is a write of NULL"). A `@service` jOOQ-record input follows jOOQ's changed-flag contract, where an explicit `null` writes `NULL`. And R1039 makes a `null` on an INSERT reference write `NULL` to its own columns. *Leave unchanged* would give a reference on UPDATE alone a different meaning from all of these.
- It is what Graphitron 9 generated. At `v9.3.0`, the UPDATE record mapper guards each leaf only on presence (`ArgumentPresence.hasField`). A present `@nodeId` reference calls `NodeIdStrategy.setReferenceId(record, id, …)`, which on a `null` id loads `NULL` into every foreign-key column through `record.from` and marks them changed, so `batchUpdate` writes them `NULL`. Plain fields behave the same way. Only the Java-record (`@record`) mapper used by service inputs skips a `null` id.
- *Leave unchanged* drops a request without saying so. A caller who sends `null` is asking for no reference; succeeding while leaving the old reference in place is the silent-discard failure the agreement checks exist to prevent.
- A per-field choice is not a `@nodeId` question. A nullable `String @field` over a NOT NULL column has exactly sis's problem: GraphQL has no spelling for "may be omitted but never null". A per-field null policy therefore belongs to every write carrier or to none, and R881 already owns column nullability across write carriers. Choosing the meaning of `null` by reading the column's NOT NULL quietly would make the same SDL behave differently depending on DDL the author cannot see in the schema.

For sis's `endreVeiledningsforhold`: `veilederkategoriId` and `internstatusId` write NOT NULL columns, so a `null` on them can never be honoured. The caller omits the field to leave it unchanged. The failing integration test changes to omit the field, or to expect an error. Today that error is an Oracle `DataAccessException`, which is the poor message R881 exists to improve. Nothing in this item reads column nullability.

**What a cleared reference contributes to a SET column it shares with another writer: nothing (reverses R880).** Applies R1039's principle to the UPDATE SET clause. Per call, for a column written by more than one field on the input:

- A reference sent as a value takes part in agreement with its decoded value, unchanged.
- A reference sent as `null` contributes nothing. The column takes the agreed value of the writers that carry one.
- A plain `@field` sent as `null` still contributes `NULL`, since a plain field names a column. So it conflicts with a reference sent as a value, unchanged.
- If at least one writer is present and none carries a value, the column is written `NULL`. If no writer is present, the column is left out of SET, unchanged.

The cross-partition case already works this way and is untouched. That is a reference that shares a matched-key column with the field identifying the row, like the tenant column on the `shelve*` fixture: the cleared side has dropped out of the check since R880 (`emitKeySetAgreementPreamble`, `emitBulkKeySetAgreement`). Only the within-SET agreement, where both writers assign the column, used R880's other reading.

## Implementation

`TypeFetcherGenerator`, the four `UpdateRows` emit consumers. Two code paths serve them: `emitSetMapPuts` for the single-row arms (direct `buildMutationUpdateFetcher`, payload `buildCarrierUpdateChainSingle` and `buildCarrierBulkPerRowUpdateBody`), and `emitBulkSetDecodeLocals` / `emitSetBulkCellAdds` for the direct bulk VALUES-join arm (`buildBulkUpdateFetcher`).

- `appendAgreementValue`: retire the `nullAgrees` parameter and its eight-argument overload. Every caller then uses the seven-argument form, where a null decode local drops out. Rewrite the surviving javadoc to give the one reading at every site: a null decode local is either a cleared reference, which contributes nothing, or a present-but-mismatched id, whose throw the decode-local site surfaces. Either way the side drops out. `emitSetAgreementPreamble` and the shared arm of `emitSetBulkCellAdds` stop passing `requireNullRule(...) instanceof OnExplicitNull.Clears`.
- `emitSetMapPuts`: a cleared `@nodeId` carrier must not overwrite a SET column it shares within SET. Today `Map.put` order is harmless only because agreement guarantees every present writer, a `null` included, carries the same value; after the flip, a cleared writer emitted after a valued one would put `NULL` over the agreed value. For a `Clears` carrier's column that `ColumnOverlap.groupByColumn(setGroupWriters(setFields))` reports `shared()`, emit the `NULL` put only when the column is not already in the map: `if (<rec> != null) sets.put(col, <value>) else if (!sets.containsKey(col)) sets.put(col, NULL)`. A valued writer emitted later still overwrites a `NULL` left by an earlier cleared one. The outcome then does not depend on emission order, and the column comes out `NULL` only when no present writer carries a value. A column only that carrier writes keeps today's `<rec> == null ? null : <rec>.value<n>()` put, byte-identical. The guard lives here rather than off the preamble's agreement list because the two payload arms emit no within-SET preamble (R821), and the guard keeps a cleared writer from overwriting a value on those arms too. That R821 gap is unchanged: two valued writers on a payload arm still overwrite each other.
- `emitSetBulkCellAdds`, shared arm: the presence gate still guarantees a present writer but no longer a value, so the coalesced cell becomes `DSL.val(<list>.isEmpty() ? null : <list>.get(0), col.getDataType())`. Membership in `v` is still decided by first-row presence, and the uniform-key-set guard still holds every row of a batch to the same writers, so no cell position moves. Correct the method javadoc's sentence about the gate. This mirrors the cell R1039 specifies for the INSERT single-row path.
- `CarrierNullRule.OnExplicitNull.Clears` javadoc: "contributes no value to any cross-partition agreement check" becomes "contributes no value to any agreement check". Add that a SET column the carrier shares with another writer takes that writer's value and is written `NULL` only when no present writer carries one. `UpdateRowsWalker` is unchanged: the walker still states `Clears` for exactly the carriers it states it for today. Only what the emitters do with the rule moves.

No derived-fact change: the store's `on_explicit_null` column was removed when nothing read it, and no relation states the within-SET agreement.

## Tests

Execution tier, `graphitron-sakila-example` against PostgreSQL. These tests are the evidence the goal is delivered; none asserts on generated code.

- `NodeIdValueAgreementExecutionTest`, UPDATE section, over the existing `updateEndorsementOverlap`, where the cleared writer `filmRef` comes before the plain one: `filmRef: null` with `endorsedFilm: 2` sets `endorsed_film` to 2, which fails on trunk with "Conflicting values". A further case sends `filmRef` set with `endorsedFilm: null` and still conflicts, leaving the row unchanged, which pins that a plain field's `null` keeps its column meaning. The existing agree and disagree cases stay.
- New fixture over `film_bookmark`, whose shared column `bookmarked_film` is nullable (`film_endorsement.endorsed_film` is NOT NULL, so a lone clear there is a constraint violation, not a row). In `schema.graphqls`, beside `FilmBookmarkOverlapInput`:

  ```graphql
  input UpdateBookmarkOverlapInput {
      bookmarkId: Int! @field(name: "bookmark_id")
      bookmarkedFilm: Int @field(name: "bookmarked_film")
      filmRef: ID @nodeId(typeName: "Film")       # after the plain writer: the order a missing guard breaks
  }
  type FilmBookmarkPayload { bookmark: FilmBookmark }
  # on Mutation
  updateBookmarkOverlap(in: UpdateBookmarkOverlapInput!): FilmBookmark @mutation(typeName: UPDATE)
  updateBookmarksOverlap(in: [UpdateBookmarkOverlapInput!]!): [FilmBookmark!]! @mutation(typeName: UPDATE)
  updateBookmarkOverlapPayload(in: UpdateBookmarkOverlapInput!): FilmBookmarkPayload @mutation(typeName: UPDATE)
  ```

  `film_bookmark` has no seed rows, so each test inserts its own. Cases:
  - Single-row, `NodeIdValueAgreementExecutionTest`: `bookmarkedFilm: 2, filmRef: null` sets 2. `filmRef: null` alone, on a row holding 5, sets `NULL`. Agreeing values set the value. The first case is the `emitSetMapPuts` ordering hazard: confirm it fails with the guard reverted before trusting it.
  - Payload single-row, same class: `bookmarkedFilm: 2, filmRef: null` through `updateBookmarkOverlapPayload` sets 2. This arm runs no within-SET preamble, so the guard alone makes it correct.
  - Bulk, `BulkUpdateSetAgreementExecutionTest`: one batch where every row sends both fields, `[{filmRef: null, bookmarkedFilm: 2}, {filmRef: <Film 3>, bookmarkedFilm: 3}]`, sets 2 and 3. A second batch where every row sends only `filmRef`, `[{filmRef: null}, {filmRef: <Film 4>}]`, on rows holding 5, sets `NULL` and 4. That second batch is the empty-list arm of the coalesced cell: on trunk it fails the agreement check rather than reaching `get(0)`, and with the flip but no `isEmpty()` guard it throws `IndexOutOfBoundsException`.
- `wrongTypeShelfId_throwsRatherThanClearing` and its bulk sibling in `StraddlingReferenceUpdateExecutionTest` stay as they are. They pin that the drop-out reading has not widened into "a malformed id clears".

The payload bulk-per-row arm shares `emitSetMapPuts` with the payload single-row arm, so it gets no separate fixture.

## User documentation (first-client check)

`docs/manual/reference/directives/nodeId.adoc`, the UPDATE paragraph that begins "On an `@mutation(typeName: UPDATE)` input those child columns can overlap", gains one sentence after "leaving the identity columns as the rest of the input set them":

> A column the reference shares with another field that the statement also writes takes that field's value, so clearing one reference never conflicts with a value sent for the same column. Sending `null` never means "leave unchanged": to leave a reference alone, omit the field.

Whichever of this item and R1039 lands second merges the INSERT and UPDATE sentences into one statement of the rule: a `null` on a reference means no reference, on every write rail.

`docs/manual/reference/directives/mutation.adoc`, the paragraph beginning "A reference in that shape must be non-null (`ID!`), and the build rejects the nullable spelling", is stale since R880. It contradicts `nodeId.adoc`, which states the narrower rejection that shipped. Rewrite it to match: a nullable straddling reference is admitted, and an explicit `null` clears its out-of-key half. The build rejects it only when it is the sole supplier of a matched-key column.

## Other solutions we've considered

- **Treat an explicit `null` on an UPDATE reference as omitted.** This matches sis's `endreVeiledningsforhold` tests. It costs the 29 references R880 measured the ability to be cleared at all, and gives a reference a meaning different from a plain field's on the same input. See Decision.
- **Choose by column nullability: clear over a nullable column, ignore over a NOT NULL one.** This also matches sis. But it reads the DDL to decide what the schema means, so two identically spelled fields behave differently for a reason the author cannot see. It also does not extend to plain fields, which have the same hazard. If column nullability becomes a write-path axis, R881 states it for every carrier.
- **Key the shared SET put off the preamble's agreement list instead of guarding the cleared writer's put.** This keeps one source for the shared column's value. But the payload arms build no agreement list (R821), so it would either widen this item into R821 or leave those arms overwriting values with `NULL`. If R821 makes the walker state within-SET overlaps on the carrier, that is the natural point to move the put onto the agreed value.

## Retired vocabulary

- `nullAgrees` (the `appendAgreementValue` parameter, and the "a cleared writer is a contribution and not an absence" reading in its javadoc). R1039's plan cites it ("`appendAgreementValue` with `nullAgrees` false") for the INSERT gather; if R1039 lands second, its implementer reads that as the seven-argument form.

## Provenance

Filed from R1039's Spec. R1039 kept the INSERT-family rails in scope and moved the UPDATE contract here, because changing the UPDATE contract retracts a reviewed R880 decision rather than fixing an emitter. Two pressures prompted it. First, sis's Graphitron 9 tests expect an explicit `null` on a `@nodeId` UPDATE field to be ignored: `endreVeiledningsforhold` sends `null` for `veilederkategoriId` / `internstatusId`, whose columns are NOT NULL foreign keys, and the statement fails in Oracle with a `DataAccessException`. Graphitron 9's own `@nodeId` UPDATE path clears (see Decision). R880's census found sis's references bound to `kjerneapi-codegen` record accessors rather than to columns, so the ignoring sis's tests expect comes from that accessor layer, or from the `@record` mapper's `null` guard. The implementer of the sis-side change confirms which at pickup; it does not change the decision. Second, R1039's rule for INSERT disagreed with R880's within-SET agreement on UPDATE.

## Reviewer findings
