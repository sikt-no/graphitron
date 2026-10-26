---
id: R1093
title: "UPDATE and DELETE accept a node id that encodes the primary key plus extra columns"
status: In Review
bucket: feature
priority: 3
theme: nodeid
depends-on: []
created: 2026-10-23
last-updated: 2026-10-26
---

# UPDATE and DELETE accept a node id that encodes the primary key plus extra columns

## Goal

A type whose node id encodes its whole primary key plus extra non-key columns can be updated and deleted by that id, the way Graphitron 9 allowed: the mutation filters on the primary-key columns the id carries and ignores the rest. Today an UPDATE taking such an id is refused at build time. A DELETE taking one filters on every encoded column, so an id whose extra column has gone stale deletes nothing. A `missingAsDeleted` DELETE taking one is refused at build time. A DELETE payload whose ids encode such a type deletes the rows and then fails to encode the ids, after the transaction has committed. sis needs this to keep the node ids its clients and sibling subgraphs already store.

A *node id* is the opaque `ID` Graphitron encodes from a fixed list of columns (the node key, `__NODE_KEY_COLUMNS` on the jOOQ table or `@node(keyColumns:)` in SDL) and decodes back into those columns. Nothing requires the node key to be a declared primary or unique key, so a node key that is a strict superset of the primary key (a *superkey*) is already accepted for encoding, decoding, `node` and `_entities`. Only the two DML walkers and the DELETE payload's `RETURNING` stop on it.

The case from sis, whose table `POLITIATTESTGODKJENT` has the four-column primary key `(INSTITUSJONSNR_EIER, PLNR, STUDIEPROGRAMKODE, LISENSTYPEKODE)` and a node key of those four plus `DATO_FORSTE_GYLDIGE`:

```graphql
# Node key from the jOOQ table's __NODE_KEY_COLUMNS: the four primary-key columns plus DATO_FORSTE_GYLDIGE.
type GodkjentPolitiattest implements Node @table(name: "POLITIATTESTGODKJENT") @node @key(fields: "id") {
  id: ID! @nodeId
}

input EndreGodkjentePolitiattesterInput {
  id: ID! @nodeId(typeName: "GodkjentPolitiattest")
  fraDato: Date @field(name: "DATO_FORSTE_GYLDIGE")
  tilDato: Date @field(name: "DATO_SISTE_GYLDIGE")
  merknad: String @field(name: "MERKNADTEKST")
}

input SlettGodkjentePolitiattester {
  id: ID! @nodeId(typeName: "GodkjentPolitiattest")
}

type EndreGodkjentePolitiattesterPayload {
  godkjentePolitiattester: [GodkjentPolitiattest!]
  errors: [EndreGodkjentePolitiattesterError!]
}

type SlettGodkjentePolitiattesterPayload {
  ider: [ID!]
}

type Mutation {
  endreGodkjentePolitiattester(input: [EndreGodkjentePolitiattesterInput!]!): EndreGodkjentePolitiattesterPayload
    @mutation(typeName: UPDATE, table: "POLITIATTESTGODKJENT")
  slettGodkjentePolitiattester(input: [SlettGodkjentePolitiattester!]!): SlettGodkjentePolitiattesterPayload
    @mutation(typeName: DELETE, table: "POLITIATTESTGODKJENT")
}
```

After this lands:

* **UPDATE.** `endreGodkjentePolitiattester` updates the row named by the id's four primary-key columns. The id's date is neither a WHERE filter nor a SET value, and `fraDato` is the only writer of `DATO_FORSTE_GYLDIGE`. The payload's `godkjentePolitiattester` rows are re-read by primary key after the UPDATE, so their ids encode the stored date, which is the new one after a `fraDato` change.
* **DELETE.** `slettGodkjentePolitiattester` deletes by the same four columns. The payload's `ider` carry the date stored in the deleted row, not the date in the client's input id.
* **Reads are unchanged.** `node` and `_entities` keep filtering on all five columns, so a stale id resolves to null there.

## Scope

The rule applies to an *own-columns carrier*: an input field whose decoded columns all belong to the mutated table itself rather than reaching it through a foreign key. Here that is the `@nodeId` `id`. Any own-columns carrier with more than one column is a same-table `@nodeId`, since `CallSiteExtraction.NodeIdDecodeKeys` is the only extraction above arity 1. The rule applies when that carrier's in-key columns cover the whole *matched key*: the primary or unique key the walker picks as the row's identity (`MatchedKeys.firstCovered`, primary key first). Its out-of-key columns are then dropped. On UPDATE they leave both partitions; on DELETE they leave the WHERE. Everything else keeps today's verdict:

* **Partial coverage.** An own-columns carrier with three of the four primary-key columns plus extras stays `UpdateRowsError.MixedCarrierKeyMembership` on UPDATE: writing part of the identity moves the row, which is the reason that rejection exists. On DELETE such a carrier keeps filtering on every column it decodes.
* **Other carriers.** Cross-table FK straddlers, self-FK carriers and plain `@field` inputs keep their current partitioning. A plain `@field` beside the primary key is still an extra DELETE filter, and is still refused under `missingAsDeleted`.
* **Broadcast DELETE** (`multiRow: true`, no key covered) has no matched key, so nothing is dropped.
* **Out of scope.** A `@nodeId @reference` from another table to a superkey node is refused unless the FK covers every node-key column (the coverage check in `BuildContext`); sis has none.

Ignoring the extra columns, rather than writing them, is deliberate. Routing the id's date to SET would put it in the same SET group as `fraDato` with a decode present. The agreement preamble `TypeFetcherGenerator.emitSetAgreementPreamble` would then throw at runtime in exactly the change-the-date case.

## Implementation

### One membership rule, shared by both walkers

The question "how do this carrier's columns sit against the matched key" is asked once, in a helper beside `MatchedKeys` in the walker package. The helper returns a sealed per-contribution answer, each arm carrying the columns with their decode slots:

* `AllInKey`
* `NoneInKey`
* `CoversKeyPlus(inKey, dropped)`: the carrier holds every matched-key column plus others.
* `Straddles(inKey, outside)`: some matched-key columns and some others, without the whole key.

`CarrierRole` moves out of `UpdateRowsWalker` into the walker package so both walkers classify carriers the same way: `OwnColumns`, `SelfFk` or `CrossTableFk`. Each walker then maps the membership arms to its own verdict for each role. A self-FK is never measured, as today. `CoversKeyPlus` is produced only for `OwnColumns`; for a reference carrier the helper reports it as `Straddles`, so references keep today's partitioning.

### UPDATE: `UpdateRowsWalker`

Stage 6 switches on the helper's answer. For `OwnColumns`:

* `CoversKeyPlus` routes the in-key columns to `keyColumns` at their decode slots and records the rest as dropped.
* `Straddles` stays `MixedCarrierKeyMembership`.

`CrossTableFk` keeps its per-column partition on `Straddles`.

`UpdateRows.Identified` gains `droppedColumns`, a list of `KeyColumn` (field, column, extraction, slot). That restores a statable invariant: every column a carrier decodes is a WHERE column, a SET column, or dropped.

Nothing downstream needs to change:

* **Emitters.** `keyGroupsOf` already carries each `KeyColumn.decodeSlot`, and every emitter reads `value<slot+1>()` off the full decode record. A group that skips a slot (four of five) or holds a single column (`MapBinding` over a two-column decode) needs no emitter change.
* **Stage 6a (`AgreementObligation`).** It skips any column absent from `keyBySqlName`, so the id's dropped date raises no obligation.
* **Stage 6b (`PlainColumnCollision`) and Stage 7a (`CarrierNullRule`).** Both look only at SET columns, which no longer include the id.
* **Stage 7 (`NoSetFields`).** An input with only the id still raises it.

The class javadoc's "What partitions" paragraph and the `CarrierRole.OwnColumns` javadoc ("a straddle means moving the row and rejects") get the whole-key exception stated.

### UPDATE: the bulk VALUES-join duplicate guard

`TypeFetcherGenerator.buildBulkUpdateFetcher` serves a list-input UPDATE that returns its `@table` type or `ID` directly. It emits one `UPDATE … FROM (VALUES …)`, guarded by `seenKeys`, and that guard builds its tuple from the raw wire value for both `DecodedRecordGroup` and `NodeIdDecodeKeys` `MapBinding` groups (the comment there reads "the encoded NodeId string is a stable identity for the decoded tuple"). Once the date leaves the WHERE, two ids differing only in the date pass as distinct and join the same row. That is the silently-lost-row hazard the guard exists to stop.

The guard moves into the per-row loop that builds `cells`, after `emitLookupKeyDecodeLocals`. Its tuple is built from the same per-binding value expressions `emitLookupKeyCellAdds` writes into `cells`: the decoded value at each binding's slot, and the raw wire value for a direct binding. `emitLookupKeyCellAdds` is factored to return those expressions once, so both consumers read one list and cannot drift. Doing this uniformly, rather than only where a slot is dropped, makes the guard compare exactly what the WHERE joins on. The execution test below is its enforcer; no generated-text assertion is added.

Two paths deliberately get no guard:

* **Payload-returning bulk UPDATE** (`buildCarrierBulkPerRowUpdateBody`, the sis shape) runs one UPDATE per row, so two ids that differ only in the date update the same row in turn and the payload lists it twice. Two identical ids do the same today. This item adds no guard there.
* **Bulk DELETE** runs per row. A second id for an already-deleted row matches zero rows: without `missingAsDeleted` that is the existing "matched zero rows" failure, and with it the slot is echoed. Two identical ids behave the same way today (`deleteFilmsMissingAsDeleted_duplicateKey_echoesBothSlots`).

### DELETE: `DeleteRowsWalker`

`Contribution` gains the shared `CarrierRole`. `walk` computes the matched key before building `whereColumns`. That is safe because the dropped columns are by definition outside the key, so the match is the same either way. It then switches on the helper's answer. `CoversKeyPlus` puts the in-key columns in `whereColumns` and the rest in `droppedColumns`. Every other arm keeps every column a WHERE filter at its slot, `Straddles` included, since DELETE has no row-move hazard.

`DeleteRows.Identified` and `IdempotentByPrimaryKey` gain `droppedColumns`. The `DeleteRows` javadoc invariant becomes "every admitted column is a WHERE filter or dropped", and the walker comment "DELETE never splits a carrier … the rows are contiguous" is restated: dropped slots are now possible, and `keyGroupsOf` already handles them.

`idempotentByPrimaryKey` then admits a superkey id unchanged, because the size check sees four WHERE columns. `missingAsDeleted_pkPlusExtraFilterColumn_rejects` keeps passing, because a plain `@field` extra is not dropped.

### Tenant routing reads the dropped columns

A dropped column leaves the WHERE but is still a decoded value the statement can route on. Under database-per-tenant (one database per tenant, the statement sent to the database its tenant column names), `TenantBindingIndex.collectFromWhereKeys` finds a DML statement's tenant by reading `OperationMember.Write.Dml.whereKeyColumns()` for the tenant column. If it stays the only read, dropping a tenant column turns a DELETE that routes today into a `noTenantBinding` rejection.

The case is not exotic. On a tenant-scoped table the tenancy rule requires the tenant column in the node key: `nodeIdKeyMissingTenantColumnOnTenantScopedTableRejects` pins the rejection, whose remedy reads "Add tenant column 'film_id' to @node(keyColumns:)". When the tenant column is outside the primary key, the node that remedy produces is itself a superkey. An example is `inventory`, with primary key `inventory_id` and tenant column `film_id`, giving `@node(keyColumns: ["inventory_id", "film_id"])`.

* **Model.** `UpdateRows` and `DeleteRows` each gain `droppedColumns()` at the interface (empty on `Broadcast`), and `Dml` gains a sibling default `droppedKeyColumns()` that switches over the verbs like `whereKeyColumns()`. `whereKeyColumns()` keeps answering what the statement filters on.
* **Routing.** `collectFromWhereKeys` reads `whereKeyColumns()` and then `droppedKeyColumns()`. WHERE goes first, so a WHERE slot stays the binding's primary. Both go through the same `accessOf(extraction, …, decodeSlot)`, so the dropped column projects as a `DecodedKeySlot` like any decoded key column.
* **Why routing on it is sound.** Under database-per-tenant a row cannot change tenant; the SET-side decline in the same method states why. So the tenant an id carries is the tenant holding the row, even when another extra column in the same id has gone stale.

### DELETE payload: `RETURNING` the node key

The record-carrier DELETE fetchers return `buildPkFieldList(tableRef.primaryKeyColumns())`, and the payload's `ChildField.SingleRecordIdFieldFromReturning` encodes from the encoder's `paramSignature()` by SQL name. For a superkey node the extra column is not in the record, so the encode throws after the transaction has committed. This holds whatever the input looks like, so it is a live defect for any superkey DELETE payload, not only one keyed by a superkey id. Nothing at build time catches it.

The two record-carrier leaves, `MutationField.MutationDmlRecordField` and `MutationBulkDmlRecordField`, gain `Returning returning` for every verb. It is the columns the statement's `RETURNING` captures, decided by the classifier, and typed `record Returning(List<ColumnRef> primaryKey, List<ColumnRef> nodeKeyTail)` so "primary key first" is structural. The compact constructor checks only that the two lists are disjoint.

* **What each verb gets.** Insert and Update get an empty tail. Delete's `FieldBuilder.classifyDeletePayloadField` `IdElement` arm takes the encoder's node-key columns outside the primary key, in node-key order. For every non-superkey node the tail is empty and the emit is byte-identical. The encoder resolution moves ahead of the walker call, so the walker can be handed the echo columns (below).
* **The leaf as enforcer.** Each leaf's compact constructor, the smallest record holding both `returning` and a Delete arm's `deleteRows`, checks that an `IdempotentByPrimaryKey`'s `echoBinding` columns equal `nodeKeyTail`, in order.
* **The direct-return path is untouched.** `DmlTableField` (DELETE returning `ID` / `[ID!]`) already carries its encoder on `DmlReturnExpression.EncodedSingle` / `EncodedList`, and `emitEncoded` already returns the full node key, so it gets no second copy of that fact.
* **Emitters.** `buildSingleRecordTwoStepFetcher`, `buildBulkRecordTwoStepFetcher` and the per-row bodies read `f.returning()` instead of `tableRef.primaryKeyColumns()`, with no fork on the verb. It drives the `RecordN` / `Result<RecordN>` types, the empty-input short-circuit's `newResult`, the accumulator and `returningResult`. No downstream consumer depends on the record being primary-key shaped, except the echo below: the id child fetcher reads by SQL name, and `ParentSourceBinding` only narrows `Outcome`.
* **Javadocs.** The record-carrier javadocs that state the PK-only `RETURNING` contract (`MutationField.MutationDmlRecordField`, the two skeletons in `TypeFetcherGenerator`) say "primary key, widened to the node key on a DELETE whose payload ids need it".

### DELETE payload: what a `missingAsDeleted` echo carries

An echoed row has no stored row, so the only source for the extra column is the client's input id. **The echo carries the client's value**, so the echoed id is the id the client sent. That is the only value that exists, and it is the answer a client reconciling its stored ids needs for a row someone else already deleted. A deleted row's id still carries the stored date, per the sis decision above.

* **Walker parameter.** `DeleteRowsWalker.walk` takes `List<ColumnRef> echoColumns`: the node-key columns outside the primary key that a missing-row echo must supply. It is empty on the direct-return path, where `missingAsDeleted` is already refused, and for every non-superkey payload.
* **Echo bindings.** `idempotentByPrimaryKey` binds each echo column to an entry of `droppedColumns`, in `echoColumns` order. `DeleteRows.IdempotentByPrimaryKey` gains `List<KeyColumn> echoBinding`, a narrowing of `droppedColumns`. Its compact constructor checks two things: every echo binding is among `droppedColumns`, and every echo binding's extraction is among the `keyBinding` extractions. The second makes the emitter's assumption a model guarantee, namely that the decode local the echo reads is one the WHERE already declares. `whereColumns()` keeps answering `keyBinding` alone.
* **Rejection.** An echo column no carrier decodes is a new typed rejection, `DeleteRowsError.MissingAsDeletedEchoUnencodable`. It carries the field, the table, the payload's node type and the unsupplied columns, under lspCode `graphitron.delete-rows.missing-as-deleted-echo-unencodable`. The case is a plain-`@field` primary-key input under a superkey payload: the echo would have no value to encode. It is a separate permit from `MissingAsDeletedNeedsPrimaryKey` because the fix differs: key the input by the node id rather than narrow it to the primary key.
* **`missingRowEcho`.** It builds `newRecord(<returning fields>)`. The primary-key values come from `LookupWhereChunk.keyValues()` as today, and the echo values come from the identifying carrier's decode local at each echo binding's slot. `buildLookupWhereSingleRow` already declares that local for the WHERE (guaranteed by the extraction check above), so `LookupWhereChunk` exposes it per access path rather than the echo decoding a second time.

### Rejection and docs plumbing

* **New permit.** `MissingAsDeletedEchoUnencodable` lands in `DeleteRowsError` (in `graphitron-model`) with its own `lspCode()` override, since the LSP projector reads the code off the record. It also needs a paragraph in `docs/architecture/explanation/typed-rejection.adoc` (`SealedHierarchyDocCoverageTest`), its sample in `RejectionSeverityCoverageTest`, and its leaf name in `RejectionResidueDrainageTest.RESIDUE_LEAVES`. That set is the full reflective leaf walk, so it fails until the leaf is declared.
* **Narrowed arm.** In `typed-rejection.adoc`, the `MixedCarrierKeyMembership` paragraph narrows to partial coverage. The `DeleteRowsError` paragraph's "every admitted input column is a WHERE filter" gains the whole-key exception. So does `MissingAsDeletedNeedsPrimaryKey.message()`, which tells the author to bind "each primary-key column once and no other column"; a superkey id binds other columns and is admitted.
* **User docs.** The manual changes are drafted below.

## User documentation (first-client draft)

`docs/manual/reference/directives/mutation.adoc`, the paragraph after the cross-table reference paragraphs (today: "A field carrying the row's *own* columns is still rejected when it straddles …"):

> A field carrying the row's *own* columns is still rejected when it straddles part of the matched key, because writing only some of a row's key columns would move the row rather than update it. The exception is a node id whose key columns are the whole matched key plus more, for example a type declared `@node(keyColumns: ["inventory_id", "store_id"])` over a table whose primary key is `inventory_id`. Such an id identifies the row by the matched key alone: its other columns are neither filtered on nor written. Another field may write them; a `storeId: Int @field(name: "store_id")` beside the id moves the item to another store, and the payload's ids encode the new value. An id minted before that change still updates the row.

The `DELETE` paragraph ("`DELETE` identifies rows the same way …"), added after "Extra input columns beyond the matched key add further `AND`ed predicates":

> A node id whose key columns are the whole matched key plus more is the exception: it filters on the matched key only, so an id whose extra columns have since changed still deletes its row.

In "Echoing the deleted primary key", after "The response contains exactly the IDs of rows the DML actually removed":

> When the node type's key columns go beyond the primary key, the extra columns are read back from the deleted row, so each id encodes the row as it was stored rather than as the input id described it.

In "When a key matches no row", after "The input must identify rows by exactly the table's primary key, directly or through a node id":

> A node id whose key columns go beyond the primary key qualifies; a key that matches no row is echoed as the id the client sent. If the payload's ids need such columns and the input supplies only the primary key, the build fails, because there is nothing to encode a missing row's id from. Key the input by the node id instead.

And in "What you can't return", the first bullet becomes "`RETURNING` is narrowed to primary-key columns, plus the node key's other columns where the payload's ids encode them."

## Tests

No DML fixture has a superkey node today. `FilmActorNote` in the multitenant schema declares its key in SDL, but that key equals its primary key. Superkey precedents (`Inventory @node(keyColumns: ["inventory_id","store_id"])`) exist only in pipeline-tier tests such as `NodeIdProducerSlotDecodePipelineTest`. sis's path is jOOQ metadata (`__NODE_KEY_COLUMNS`), which `TypeBuilder.buildNodeType` folds into the same `NodeType.nodeKeyColumns()` the SDL path produces. So an SDL-declared superkey exercises the same model, with no `NodeIdFixtureGenerator` entry or `init.sql` change.

**Unit tier.**

* The membership helper: one case per arm, including a reference carrier covering the key plus extras reporting `Straddles`.
* `UpdateRowsWalkerTest`: a `compositeColumnField` over `film_actor`'s `(actor_id, film_id, last_update)` beside a plain SET field. It routes the two key columns to WHERE at slots 0 and 1, records slot 2 in `droppedColumns` and in neither partition, and raises no obligation. The same carrier beside a plain `@field` on `last_update` raises no `PlainColumnCollision`. `ownColumnsStraddleKey_stillRejectsWithMixedCarrierKeyMembership` keeps pinning partial coverage.
* `DeleteRowsWalkerTest`:
  * The same carrier moves slot 2 from `whereColumns` to `droppedColumns`.
  * Under `missingAsDeleted` with `echoColumns = [last_update]` it yields `IdempotentByPrimaryKey` with an echo binding at slot 2, and with empty `echoColumns` it yields one with no echo binding.
  * A plain-field PK input with `echoColumns = [last_update]` is `MissingAsDeletedEchoUnencodable`.
  * A cross-table `Local` reference covering the key plus extras keeps every column.
* Compact constructors: `IdempotentByPrimaryKey` refuses an echo binding outside `droppedColumns` or with an extraction no `keyBinding` shares, and the record-carrier leaves refuse an `echoBinding` that differs from `returning.nodeKeyTail()`.

**Pipeline tier.** `MutationDmlNodeIdClassificationTest` or a sibling builds the execution fixture's SDL and asserts on the model, not the generated text:

* a superkey DELETE payload's `returning()` has the extras as its tail;
* a non-superkey payload's tail and every Insert and Update tail are empty;
* a plain-field primary-key input under a superkey `missingAsDeleted` payload is `MissingAsDeletedEchoUnencodable`.

`TenantBindingClassificationTest`, beside `deleteKeyedByCompositeNodeIdDivinesTheDecodedSlot`, pins the routing read. It uses `Inventory @node(keyColumns: ["inventory_id", "film_id"])` on the tenant-scoped `inventory`. A DELETE keyed by that id, and an UPDATE keyed by it beside a `storeId: Int @field(name: "store_id")` writer, are each `ArgumentBound` on the `id` slot, projected as `DecodedKeySlot` 1, with no rejections. The DELETE is the regression guard: it binds today, through the WHERE.

**Execution tier** (`graphitron-sakila-example`, `DmlBulkMutationsExecutionTest` or a sibling class, schema near the `missingAsDeleted` block of `schema.graphqls`). Two new node types over tables no other node type claims, so implicit encoder resolution stays unambiguous:

* **`InventoryUnit implements Node @table(name: "inventory") @node(keyColumns: ["inventory_id", "store_id"])`.** It mirrors sis's UPDATE: a `storeId @field(name: "store_id")` is the writer of the id's extra column, as `fraDato` is. Tests insert their own inventory rows, since `rental` references `inventory`.
  * A single and a payload-returning bulk UPDATE by an id minted before a `storeId` change update the row and return the re-read id carrying the new store.
  * A direct `[InventoryUnit!]` bulk UPDATE with two ids differing only in `store_id` fails on the duplicate guard and writes nothing. This is the guard's enforcer.
  * A single DELETE by a stale id deletes the row and its payload id carries the stored store.
* **`FilmCategoryNode implements Node @table(name: "film_category") @node(keyColumns: ["film_id", "category_id", "last_update"])`.** It mirrors sis's DELETE shape: a multi-column key group with a temporal extra column. Tests make an id stale by updating `last_update` in SQL.
  * A bulk DELETE by stale ids deletes the rows, and the payload ids carry the stored `last_update`.
  * The `missingAsDeleted` variant over an existing and a missing row deletes the first, echoes the second as the exact input id string, and keeps input order.
  * A bulk DELETE without `missingAsDeleted` by an id whose primary key matches no row still fails as "matched zero rows".

Reads are pinned unchanged: `node` on a stale `InventoryUnit` id resolves to null.

## Implementation notes

What landed differs from the plan above in two small ways, neither changing the design:

* **The walker's echo parameter is a record, not a bare column list.** `DeleteRowsWalker.walk` takes `EchoColumns(nodeTypeName, columns)` (with `EchoColumns.NONE` on the direct-return path), because the new rejection names the payload's node type and the walker has no other way to learn it.
* **`MissingAsDeletedEchoUnencodable` carries no field name.** Its components are the table, the payload's node type, the input's columns and the unsupplied columns. The mutation field is already on the `UnclassifiedField` that wraps every walker rejection, which is also how `MissingAsDeletedNeedsPrimaryKey` gets its coordinate.

Where the plan's tests landed: the membership helper in `KeyMembershipTest`; the walker cases in `UpdateRowsWalkerTest` and `DeleteRowsWalkerTest` (the `IdempotentByPrimaryKey` compact constructor among them); the record-carrier leaves' echo/`RETURNING` refusal in `RecordCarrierEchoReturningInvariantTest`; the model facts in `SuperkeyNodeIdDmlClassificationTest` (a sibling of `MutationDmlNodeIdClassificationTest`, on the execution fixture's SDL); the routing read in `TenantBindingClassificationTest`; and the execution cases in `DmlBulkMutationsExecutionTest` under "Superkey node ids", over the new `InventoryUnit` and `FilmCategoryNode` fixtures. The execution `inventory` rows take explicit ids, since the table's serial lags the seed's explicit ids.

## Reviewer findings

### In Review → Done, round 1: rework (question 2, completeness evidence)

Reviewed on trunk at `61539cc`, against the delivery in `668868d`. The verification build (`mvn install -Plocal-db`) is green: 4584 tests in `graphitron`, 1306 in `graphitron-sakila-example`, including all eight superkey cases in `DmlBulkMutationsExecutionTest`.

**Question 1 (correct, and the change approved): passes.** The walkers, `KeyMembership`, `CarrierRole`, `Returning`, the echo binding, the tenant-routing read, the bulk VALUES-join guard and the rejection plumbing all match the plan. The two deviations are recorded under Implementation notes, and both are sound. The user-manual and `typed-rejection.adoc` changes follow the drafted text and leak no roadmap markers.

**Question 2 (how we know it is complete): one spec-named test is missing.** Tests § Unit tier, "Compact constructors", names two refusals. The `IdempotentByPrimaryKey` half is in `DeleteRowsWalkerTest`. The other half, "the record-carrier leaves refuse an `echoBinding` that differs from `returning.nodeKeyTail()`", has no test. Nothing exercises `MutationField.requireEchoMatchesReturning`'s throw, on either `MutationDmlRecordField` or `MutationBulkDmlRecordField`. The plan names that check as "the leaf as enforcer" of the echo/RETURNING shape agreement. Today the pipeline and execution tests only show that the two sides happen to agree on the shapes the fixtures build. If the check were deleted or inverted, the build would stay green.

**What satisfies it:** a unit-tier test that builds each record-carrier leaf with a Delete arm whose `IdempotentByPrimaryKey.echoBinding` disagrees with the `Returning`'s `nodeKeyTail`. Cover a missing tail column, and either an extra column or a wrong order. Assert `IllegalArgumentException` on both leaves. Add the matching-shape case as the positive control. No production change is needed. The next Done reviewer only has to confirm the test exists and fails if the check is removed.

> **Response.** Added `RecordCarrierEchoReturningInvariantTest` (unit tier, `graphitron/src/test/java/no/sikt/graphitron/rewrite/model/`). Its table has primary key `(a, b)` and node key `(a, b, x, y)`. The test refuses four mismatches on both leaves: a missing tail column, an extra echo column, a reversed order, and no echo under a widened `RETURNING`. Its positive controls are the matching shape, and an `Identified` DELETE with a widened `RETURNING`, which has no echo to hold to the shape. With the `echoed.equals(tail)` throw in `MutationField.requireEchoMatchesReturning` disabled, the four refusal cases fail. No production code changed.

## Other solutions we've considered

* **Filter on every decoded column.** This is DELETE's behaviour today, and would make UPDATE behave the same. It contradicts Graphitron 9, where the extra columns were marked unchanged and only the primary key reached the WHERE. It also turns the extra column into an optimistic-concurrency check: an id minted before a `fraDato` change silently matches nothing. An id stored outside sis therefore goes stale once the row's date changes, though ids minted since keep working.
* **Drop the date from the node id.** sis did this on its branch to get past the generator; it invalidates every id already stored by clients and other subgraphs.
* **Read the payload encoder's columns in the emitter** through `TypeFetcherEmissionContext`'s schema, instead of carrying `returning` on the record-carrier leaves. That rederives a classifier decision at emit time, across two fields, and leaves the echo's build-time check with no home.
* **Carry `returning` on `OperationMember.Write.Delete`.** That would give the direct-return leaf a second copy of the encoder's columns it already holds on `DmlReturnExpression`, and the record-carrier emitters would fork on the verb to choose between `write.returning()` and the primary key.
* **Echo the stored date for a missing row.** No stored row exists to read it from.

## Provenance

Requested 2026-10-23 by the sis Graphitron 10 port session, with the user's approval on the sis side. The Graphitron 9 behaviour it targets: the id decoded into all five columns, all five were marked jOOQ-unchanged, and `batchUpdate`/`batchDelete` put the primary-key columns in the WHERE.
