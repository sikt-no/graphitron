---
id: R1124
title: "A DELETE whose key matches no row is idempotent by default, and failing on it is the opt-in"
status: In Progress
bucket: feature
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# A DELETE whose key matches no row is idempotent by default, and failing on it is the opt-in

## Goal

A `@mutation(typeName: DELETE)` whose input names rows by key treats a key that matches no row as already deleted, without the author asking for it: the rows that exist are deleted, the call commits, and the response answers every input key, in input order, whether or not its row existed. This holds for the payload-returning DELETE and for the DELETE that returns `ID` or `[ID!]` directly. An author who wants such a key to fail the call writes `failOnMissing: true`; the call then deletes nothing and reports the error. The `missingAsDeleted: Boolean = false` argument, which today makes the idempotent reading the opt-in, is removed. A malformed or wrong-type id fails as it does today under either reading; the opt-in is about a well-formed key whose row is not there.

The minimal pair, from the sakila example schema:

```graphql
input FilmDeleteInput {
    filmId: Int! @field(name: "film_id")
}

type DeletedFilmsIdPayload {
    deletedIds: [ID!]
}

extend type Mutation {
    # Deleting films 1 and 99999 (no such film) deletes film 1, commits, and answers
    # both ids in input order. Today this needs missingAsDeleted: true.
    deleteFilmsIdCarrier(in: [FilmDeleteInput!]!): DeletedFilmsIdPayload
        @mutation(typeName: DELETE, table: "film")

    # The same call fails and deletes nothing. Today this is the default.
    deleteFilmsFailOnMissing(in: [FilmDeleteInput!]!): DeletedFilmsIdPayload
        @mutation(typeName: DELETE, table: "film", failOnMissing: true)
}
```

The idempotent reading is the one consumers want. It is what Graphitron 9 did for every DELETE: `batchDelete(...).execute()` ignored the row counts, and the payload listed every input id that no longer exists. A client that retries after a lost response succeeds, and a row someone else already deleted does not roll back everyone else's rows in the batch. The Graphitron 10 port of sis, the first consumer schema to move over, turned `missingAsDeleted: true` on for all 27 of its DELETE mutations and wants the strict behaviour on none. A default that the one schema ported so far overrides everywhere is the wrong default.

Answering a key whose row is gone builds the answer from the input, so the input must supply everything the answer carries. It must supply the whole primary key, directly or through a node id, and filter on nothing else. Where the response's ids encode node-key columns beyond the primary key, it must supply those columns too, which only a node id does. A DELETE whose input cannot be answered that way fails the build with a message naming its ways out. Three kinds of input fail:

* keyed by a unique key: key the input by the primary key, or write `failOnMissing: true`;
* carrying a filter column beyond the key: the same two ways out;
* keyed by the primary key's plain columns while the response's ids encode more than the primary key, for example `film_id` and `category_id` answering a `FilmCategoryNode` id that also encodes `last_update`: key the input by the node id, or write `failOnMissing: true`.

The third builds today, on the payload-returning and the direct-return DELETE alike, because the extra columns are read back from the deleted row; a row that is gone leaves nothing to read them from. Rewriting such inputs is the one migration step this item asks of an existing schema. The rule covers every DELETE whose input covers a primary key or unique key, with or without `multiRow: true`. The one DELETE outside it is a `multiRow: true` DELETE whose input covers no key, which deletes every row its filter matches: it names no key that could be missing, and it behaves as today.

## What exists today

`missingAsDeleted` shipped as an opt-in, documented in `mutation.adoc` § "When a key matches no row" and in the directive's own description in `directives.graphqls`. Without it, the DELETE shapes behave differently from each other on a key that matches no row:

* **Payload-returning, list input** (`MutationField.MutationBulkDmlRecordField`, emitted by `TypeFetcherGenerator.buildBulkRecordTwoStepFetcher` with `buildBulkRecordPerRowDeleteBody` as its per-row body): one statement per input row inside one transaction; a miss throws "@mutation(typeName: DELETE) bulk row matched zero rows; @lookupKey filter found no target for input row at index …" and rolls back the whole call.
* **Payload-returning, single input** (`MutationField.MutationDmlRecordField`, `buildMutationDmlRecordFetcher`): a miss renders the payload `null`, with no error.
* **Direct `ID` return** (`MutationField.DmlTableField`, `buildMutationDeleteFetcher`): `.fetchOne(...)` answers `null`; a non-null `ID!` turns that into a non-null violation.
* **Direct `[ID!]` return** (same, list input): one `DELETE … WHERE (key) IN (…) RETURNING …` statement through `buildBulkLookupRowIn`; a miss shortens the list, which comes back in `RETURNING` order rather than input order.

With `missingAsDeleted: true` on a payload-returning DELETE the walker produces `DeleteRows.IdempotentByPrimaryKey` instead of `DeleteRows.Identified`, and both payload emitters answer a miss with `TypeFetcherGenerator.missingRowEcho`, a key record built from the values the WHERE compared. Its execution tests are `DmlBulkMutationsExecutionTest`'s `deleteFilmsMissingAsDeleted_*` and siblings. Of the sakila fixture's 16 DELETE fields, 5 set it.

`DeleteRowsWalker` is the step that turns a DELETE's input fields into the `DeleteRows` carrier the emitters read. Three refusals guard the idempotent arm today:

* `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey`: the input must identify rows by exactly the primary key, directly or through a node id. A unique key cannot be echoed for a missing row, and an extra filter column would let the echo claim a deletion that did not happen.
* `DeleteRowsError.MissingAsDeletedEchoUnencodable`: when the response's ids encode node-key columns beyond the primary key, the input has to supply them (key it by the node id).
* `MissingAsDeletedError.UnsupportedReturnShape`: the direct-return DELETE refuses the argument outright, in `FieldBuilder.classifyDeleteTableField`, and the `DmlTableField` compact constructor refuses an `IdempotentByPrimaryKey` carrier. That refusal is an emitter limitation, not a semantic one: the input keys are there to echo.

`MissingAsDeletedError.UnsupportedVerb` refuses the argument on INSERT, UPDATE and UPSERT.

## Design

### The carrier arm is the reading

`DeleteRows` keeps three arms; the flip changes which inputs reach which, so the arm alone says how a miss behaves and no flag travels beside it. The arm today called `Identified` names an identification shape, but after the flip it is reached only under the opt-in, so it is renamed for the reading it carries:

* `IdempotentByPrimaryKey`: the default for an input that binds exactly the primary key. A miss is answered with the input key.
* `FailOnMissing` (today `Identified`): reached only with `failOnMissing: true`, over any covered primary key or unique key, extra filter columns allowed. A miss fails the call.
* `Broadcast`: `multiRow: true` with no covered key, unchanged.

A covered key decides the arm whatever `multiRow` says, as it does today: a `multiRow: true` input that covers the primary key becomes idempotent, and one that covers only a unique key needs `failOnMissing: true` like any other unique-key DELETE.

`DeleteRowsWalker.walk` takes `boolean failOnMissing` in place of `boolean missingAsDeleted` and decides:

```
no key covered:     multiRow && !failOnMissing  -> Broadcast
                    multiRow &&  failOnMissing  -> FailOnMissingNeedsKey
                    otherwise                   -> NoUniqueKeyCoverage   (unchanged)
key covered:        failOnMissing               -> FailOnMissing
                    otherwise                   -> idempotentByPrimaryKey(...)
```

`idempotentByPrimaryKey` keeps today's admission logic unchanged; only its two rejections are renamed and reworded (below). A no-key input without `multiRow` now gets `NoUniqueKeyCoverage` rather than the primary-key rejection `missingAsDeleted: true` gives it today, since that message lists the candidate keys, which is the more useful answer when nothing is covered.

### The direct-return DELETE echoes and fails like the payload one

`FieldBuilder.classifyDeleteTableField` stops refusing up front and passes the parsed `failOnMissing` and real `EchoColumns` to the walker: the node type's key columns beyond the primary key, taken from the `encodeReturn` it already resolves before the walk (`Returning.widenedToNodeKey(writeTarget, encode.paramSignature()).nodeKeyTail()`, the derivation the payload path makes). With real echo columns the direct-return DELETE meets the Goal's third build failure: one keyed by plain primary-key fields on a node whose key reaches beyond the primary key. It builds today, because `emitEncoded` reads the extra columns back through `RETURNING`; it now rejects with `IdempotentDeleteEchoUnencodable` unless it writes `failOnMissing: true`. The `DmlTableField` compact constructor drops its `IdempotentByPrimaryKey` refusal and takes the record carriers' check instead. A Delete arm's return is always encoded (the constructor already requires it), so the leaf answers what its `RETURNING` captures with a `deleteReturning()` accessor: `Returning.widenedToNodeKey(write.table(), encode.paramSignature())`, with `encode` read off its `EncodedSingle` or `EncodedList`, and `Optional.empty()` off the Delete arm. That is the primary key followed by the node key's columns outside it, the same value the record carriers hold as their `returning` component. The constructor passes it to `MutationField.requireEchoMatchesReturning` unchanged, and the emitter reads the same accessor, so the check and the emitted `RETURNING` cannot disagree.

The emitters read the miss behaviour through one projection from the arm, used on all four paths (payload single, payload bulk, direct single, direct bulk) rather than an `instanceof` at each. The policy type is already in the tree: R1123 put `MissPolicy` (`CannotMiss`, `FailFast`, `Echo`, `CountThenThrow`) and the `PerRow` seam into `TypeFetcherGenerator`. `buildBulkRecordPerRowDeleteBody` already answers `PerRow`, choosing `Echo` or `FailFast` with an inline `instanceof`. That `instanceof` becomes the projection: a helper beside `missingRowEcho` that answers `Optional<MissPolicy>` from a `DeleteRows` and the echo expression. It maps `IdempotentByPrimaryKey` to `Echo(<echo>)`, `FailOnMissing` to `FailFast`, and `Broadcast` to empty.

* `Echo` carries a `CodeBlock` expression, so it holds either form of echo: the record form (`missingRowEcho`, on the payload paths and the direct bulk loop) and the encoder-call form (the direct single `ID`).
* Empty leaves each path's present answer to a `Broadcast` miss untouched. The bulk loop keeps `FailFast` through `.orElse`, and the single-row paths keep answering `null`. No `MissPolicy` arm means "answer `null`", and that is also every non-DELETE single-row write's answer today, so the absence is the honest encoding rather than a fifth arm the bulk skeleton would have to refuse.
* `DmlChainAndGuards.missingRowEcho`, an `Optional<CodeBlock>` today, becomes `Optional<MissPolicy> onMiss`, empty on every non-DELETE chain as `missingRowEcho` is now. `buildSingleRecordTwoStepFetcher` renders `Echo` as today's `if (payload == null) payload = <echo>;` and `FailFast` as the single-row throw below. `CannotMiss` and `CountThenThrow` are not single-row readings, and the single-row skeleton refuses them with a generator `IllegalStateException`.

Emitters, in `TypeFetcherGenerator`:

* **Single `ID`** (`buildMutationDeleteFetcher`, non-list): after the `fetchOne`, the policy's miss branch. On `Echo` that is `if (payload == null) payload = <encoder>(<echo values>);`, with the values sourced the way `missingRowEcho` sources them (the chunk's `keyValues()` for the primary key, the decode-local slots for `echoBinding()`). Factor that sourcing out of `missingRowEcho` so the record form and the encoder-argument form share it. On `FailFast` it is a throw of the single-row message below. On empty (`Broadcast`) nothing is emitted, as today.
* **List `[ID!]`, key-identified**: moves off the one-statement `IN` onto the per-row loop the payload bulk DELETE uses. The loop is extracted out of `buildBulkRecordTwoStepFetcher` as a helper that emits the `dsl.transactionResult(...)` block declaring the accumulated `Result<RecordN>`. It takes the `PerRow` the body answers, the `RETURNING` column list, the verb and the field coordinate. Since R1123 that block already renders each policy's miss branch, the append and `CountThenThrow`'s post-loop guard, and all of it moves into the helper unchanged. `buildBulkRecordTwoStepFetcher` calls it and keeps its own envelope (empty `Result`, `Outcome` wrap, catch arm). `buildMutationDeleteFetcher`'s list arm calls it inside `buildDmlFetcher`'s existing envelope (its empty-list `List.of()`, its catch arm), with `buildBulkRecordPerRowDeleteBody` as the body and `deleteReturning().columns()` as the `RETURNING`, then encodes the accumulated records in order. That is the column set `emitEncoded` projects today, but in primary-key-then-tail order rather than the encoder's parameter order, because that is the order `missingRowEcho` builds an echo record in: a deleted row and an echoed one are then the same `RecordN` type whatever order the node's `keyColumns` lists. (Typed in the encoder's order instead, a node whose `keyColumns` reorder columns of different Java types would assign one `RecordN` to a differently parameterised one, and the consumer's generated sources would not compile.) The encoding is unaffected, since it reads each column by field reference (`r.get(...)`), as `emitEncoded`'s lambda does today. The single `ID` path needs no such alignment: its echo is the encoder call itself, whose arguments the shared sourcing looks up column by column in `paramSignature()` order. The two envelopes stay separate because their value types differ; only the loop is shared.
* **List `[ID!]`, `Broadcast`**: keeps the one-statement `IN`, as today.

**The seam in `buildDmlFetcher`.** Today `buildDmlFetcher` renders the `payload` declaration itself, through `emitDmlReturnExpression` over the `KeyStatement` it is handed. Neither the single-row miss branch nor the loop has anywhere to go. It gains one parameter, the block that declares `payload`; the existing overloads compute that block as they do today and delegate, so no other caller changes. `buildMutationDeleteFetcher` passes:

* on the single path, `emitEncoded` followed by the miss branch;
* on the key-identified list path, the loop followed by the encode;
* on `Broadcast`, plain `emitEncoded`.

`KeyStatement` is not widened for this. It names the one statement a terminator fetches its keys from, and the loop runs one statement per row.

This costs the direct bulk DELETE one statement per input row where today it runs one statement in all, the cost the payload bulk DELETE already pays. A one-statement form for both shapes is a separate performance question (see Other solutions).

### Strict means failing on every shape

On `FailOnMissing`, a miss throws on every shape. The single-row throw is new for the payload-returning single DELETE, which renders the payload `null` today, and for the direct `ID` return, which answers `null`. Its message keeps the "matched zero rows" substring the bulk message and `@error` handlers already match on:

```
@mutation(typeName: DELETE) row matched zero rows; the key found no target for Mutation.deleteFilmFailOnMissing
```

On the payload path the throw runs inside the fetcher's existing `try`, so `dmlCarrierCatchArm` routes it through the payload's errors field exactly as the bulk throw is routed today, or redacts it when the payload has none. On the direct path it is redacted like any other fetcher exception there. The bulk message is unchanged.

### The argument and its rejections

`directives.graphqls`: `failOnMissing: Boolean = false` replaces `missingAsDeleted`, with the description in the user-docs draft below.

`MissingAsDeletedError` becomes `FailOnMissingError` (LSP namespace `graphitron.fail-on-missing.`), keeping `UnsupportedVerb` (code `graphitron.fail-on-missing.unsupported-verb`) and losing `UnsupportedReturnShape`. In `MutationInputResolver`, `parseMissingAsDeleted` becomes `parseFailOnMissing` and `MISSING_AS_DELETED_SUPPORTED_VERBS` becomes `FAIL_ON_MISSING_SUPPORTED_VERBS`. `DeleteRowsError` renames two arms and adds one:

* `MissingAsDeletedNeedsPrimaryKey` becomes `IdempotentDeleteNeedsPrimaryKey` (`graphitron.delete-rows.idempotent-delete-needs-primary-key`). The message no longer quotes an argument the author did not write: "A DELETE answers a key that matches no row as deleted, which needs the input to bind exactly the primary key … Change the input to bind exactly the primary key, or add failOnMissing: true to fail the call on a key that matches no row."
* `MissingAsDeletedEchoUnencodable` becomes `IdempotentDeleteEchoUnencodable` (`graphitron.delete-rows.idempotent-delete-echo-unencodable`), its fix reading "Key the input by the '<Node>' node id instead, or add failOnMissing: true."
* New `FailOnMissingNeedsKey` (`graphitron.delete-rows.fail-on-missing-needs-key`): `failOnMissing: true` on a `multiRow` DELETE that covers no key. A filter that matches nothing deleted nothing; there is no key to report missing. Names the table and the input's columns; the fix is to remove `failOnMissing` or cover a key.

`Rejection.AuthorError`'s permits clause and `RejectionFacts`' code dispatch follow the rename.

The fact store captures the argument as written in two relations: `graphitron_ast_mutation_entry.missing_as_deleted` (written by `GraphitronFieldEntries`) and `graphitron_mutation_entry.missing_as_deleted` (anchored by `GraphitronAnchor`, written through `FactWrites`). Both columns become `fail_on_missing` with the same grain and comment shape: the argument as written, `NULL` where the author wrote none. This is a rename of a captured argument, not a new relation. On the generator side the name is read through `WriteFactVisitor.ARG_MISSING_AS_DELETED` into `WriteFacts`, re-exported as `BuildContext.ARG_MISSING_AS_DELETED`, and checked by `GraphitronSchemaBuilder`'s `assertDirective` over `@mutation`'s arguments; all follow.

### What this leaves to other items

* **A miss count for the strict DELETE.** R1123, now in review on trunk, gave the payload bulk UPDATE a typed exception counting every missed row (`CountThenThrow`, `ZeroRowsMatchedException`). It also made the skeleton verb-neutral, so a bulk DELETE adopts the count by answering `CountThenThrow` in place of `FailFast`. That switch is not part of this item: after the flip the strict DELETE is an explicit opt-in that sis does not use, so the count has no consumer on DELETE yet (sis tracks the DELETE side as its row C34). This item builds on R1123's `MissPolicy` and `PerRow` as they stand on trunk, and the direct bulk DELETE picks them up with the rest of the skeleton.
* **`multiRow: true` on a payload-returning DELETE.** The per-row body's `fetchOne` fits a key-identified row, not a broadcast filter that matches several rows or none. That is a defect of the `Broadcast` arm on the payload path today and is unchanged here.
* **R242** (positional input/output alignment for DML payloads) plans positional `null`s for DELETE misses. Under the flip a key-identified DELETE already answers one entry per input key in input order and a strict one fails, so R242's DELETE half shrinks to rows a row-level-security policy hides from `RETURNING`. Its author revisits that half when this lands.

### Landing with sis

Graphitron 10 has not been released, so the argument is removed outright rather than kept as a deprecated alias. Once it is gone, graphql-java rejects `missingAsDeleted: true` as an unknown directive argument, so sis deletes it from its 27 mutations in the same snapshot bump. Announce the landing commit to the sis port session. sis's DELETE inputs are all keyed by the primary key or a node id (they build with `missingAsDeleted: true` today, which applies the same admission), so none of them meets the new build error.

## Implementation

* `graphitron-model/.../schema/directives.graphqls`: the argument and its description.
* `graphitron-model/.../diagnostics/`: `MissingAsDeletedError.java` renamed to `FailOnMissingError.java` without `UnsupportedReturnShape`; `DeleteRowsError.java` renames and the new arm; `Rejection.java`, `RejectionFacts.java`.
* `graphitron-model/.../graphitron-model.sql` (both columns and their comments), `capture/document/GraphitronFieldEntries.java`, `capture/document/GraphitronAnchor.java`, `sink/FactWrites.java`: the captured columns.
* `graphitron/.../facts/WriteFactVisitor.java`, `WriteFacts.java`, `rewrite/BuildContext.java`, `rewrite/GraphitronSchemaBuilder.java`: the argument constant, flag and directive assertion.
* `graphitron/.../rewrite/MutationInputResolver.java`: the parser and verb set.
* `graphitron/.../rewrite/walker/DeleteRowsWalker.java`: the decision above, the `EchoColumns` javadoc (no longer `NONE` on the direct path).
* `graphitron/.../rewrite/FieldBuilder.java`: `classifyDeleteTableField` (drop the up-front refusal, pass `failOnMissing` and the encoder's echo columns), the payload DELETE path (pass `failOnMissing`), the unsupported-verb guard.
* `graphitron/.../rewrite/model/DeleteRows.java`, `MutationField.java`: `Identified` renamed to `FailOnMissing`, javadoc for the arms as readings; drop the `DmlTableField` refusal, add `deleteReturning()` and pass it to `requireEchoMatchesReturning`.
* `graphitron/.../rewrite/generators/TypeFetcherGenerator.java`: the arm-to-policy projection onto R1123's `MissPolicy`, `DmlChainAndGuards.onMiss`, the single-row echo and throw on both direct and payload paths, the extracted per-row loop and the direct bulk DELETE on it, `buildDmlFetcher`'s payload-declaration parameter, the shared echo-value sourcing; rewrite the "Empty-match semantics" javadoc on `buildMutationDeleteFetcher`.
* `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: the fixture moves listed under Tests.
* Docs listed under User documentation.

## Tests

**Unit tier** (`DeleteRowsWalkerTest`): an exact-primary-key input without the flag yields `IdempotentByPrimaryKey`; with `failOnMissing` it yields `FailOnMissing`; a unique-key input and a primary key plus a filter column each reject with `IdempotentDeleteNeedsPrimaryKey` without the flag and yield `FailOnMissing` with it; a `multiRow` input covering the primary key yields `IdempotentByPrimaryKey` and one covering only a unique key rejects with `IdempotentDeleteNeedsPrimaryKey`; an input binding the primary key's plain columns where the echo columns reach beyond them rejects with `IdempotentDeleteEchoUnencodable` without the flag and yields `FailOnMissing` with it; `multiRow` with no key yields `Broadcast` without the flag and `FailOnMissingNeedsKey` with it; no key without `multiRow` yields `NoUniqueKeyCoverage` either way.

**Pipeline tier** (`GraphitronSchemaBuilderTest`): `failOnMissing` on INSERT, UPDATE and UPSERT rejects with `UnsupportedVerb`; a direct-return DELETE classifies to `DmlTableField` carrying `IdempotentByPrimaryKey`; a direct-return DELETE keyed by a unique key rejects with the renamed arm; a direct-return `ID` DELETE keyed by plain primary-key fields on a node whose key reaches beyond the primary key rejects with `IdempotentDeleteEchoUnencodable`, and with `failOnMissing: true` classifies to `DmlTableField` carrying `FailOnMissing`. `SuperkeyNodeIdDmlClassificationTest` changes in meaning, not only in name: `deleteFilmCategoriesByKey` drops `missingAsDeleted: true` and keeps its `IdempotentDeleteEchoUnencodable` rejection, which is now the default's, and `deleteFilmCategoryNodesMissingAsDeleted` folds into `deleteFilmCategoryNodes`, whose carrier is now `IdempotentByPrimaryKey`. The LSP and fact tests that name the old codes and column (`RejectionSeverityCoverageTest`, `RejectionResidueDrainageTest`, `TriggerFactPopulationPinTest`, `HierarchyKindRegistryTest`, `RecordCarrierEchoReturningInvariantTest`, `GraphitronMcpServerTest`, the `field-site-payloads.graphqls` fact fixture and `EntryFamilyFixture`) follow the rename.

**Execution tier.** The sakila fixture moves so each reading keeps its proof:

* `deleteFilmsMissingAsDeleted`, `deleteFilmCategoryNodesMissingAsDeleted`: removed; their tests retarget `deleteFilmsIdCarrier` and `deleteFilmCategoryNodes`, which are now the same declarations without the argument. `deleteFilmCategoryNodes_aPrimaryKeyMatchingNoRow_stillFailsTheBatch`, which pins today's strict default on `deleteFilmCategoryNodes`, is deleted. Its call now commits and echoes, which the retargeted `missingThenStale` case pins, and its strict reading has nothing specific to a superkey node id (the dropped column is filtered on under neither reading), so `deleteFilmsFailOnMissing` covers it.
* `deleteFilmMissingAsDeleted`, `deleteFilmActorsMissingAsDeleted`, `deleteSplitKeyChildrenMissingAsDeleted`: lose the argument and the suffix (`deleteFilmIdCarrier`, `deleteFilmActorsWithErrors`, `deleteSplitKeyChildren`); tests renamed to match.
* New `deleteFilmsFailOnMissing` (list, payload) carries `deleteFilmsIdCarrier_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow`, renamed; new `deleteFilmFailOnMissing` (single, payload) pins that a miss now reports an error where today it renders `null`.
* `deleteStorageBinByCode` and `deleteStorageBinByCodePayload` (unique key) take `failOnMissing: true`; a new case on each pins that a code matching no bin fails.
* Direct return: `deleteFilms` with an existing and a missing id, in non-key order, answers both ids in input order and deletes the existing one; `deleteFilmActorsByNodeId` does the same through a composite node id; a new `deleteFilmsDirectFailOnMissing(in: [FilmDeleteInput!]!): [ID!]!` with `failOnMissing: true` rolls back the existing row when another key misses; `deleteFilmsByReleaseYear` (`Broadcast`) stays green unchanged.
* Key order: `FilmCategoryNode`'s `keyColumns` move to `["last_update", "film_id", "category_id"]`, a node key that opens with a column outside the primary key and of another Java type, so every DELETE on it runs with the encoder's parameter order differing from primary-key-then-tail order (the `encodeFilmCategoryNode` calls in `DmlBulkMutationsExecutionTest` follow the new parameter order). A new direct-return `deleteFilmCategoryNodesDirect(in: [FilmCategoryNodeDeleteInput!]!): [ID!]!`, given a missing id then a stale one, answers both in input order: the missing one as sent, the deleted one with the stored `last_update`. Compiling `graphitron-sakila-example` pins the shared record type; the execution case pins the encoding.

The goal is demonstrated by the retargeted `deleteFilmsIdCarrier` cases (no argument, missing key echoed, call commits), the direct `deleteFilms` case (the same for a direct return), and the two `*FailOnMissing` cases (the opt-in fails on both shapes).

## User documentation (first-client draft)

The argument table row in `mutation.adoc`:

> `failOnMissing` | `Boolean` | `false` | On a `DELETE`, makes a key that matches no row fail the call instead of counting as already deleted. Rejected on `INSERT`, `UPDATE` and `UPSERT`, and on a `multiRow` `DELETE` whose input covers no key. See <<when-a-key-matches-no-row>>.

§ "When a key matches no row", replacing today's:

> A `DELETE` treats a key that matches no row as already deleted. The rows that exist are deleted, the call commits, and the response holds every input key in input order, whether or not its row existed. A retried call, or two clients deleting the same row, then succeed, and the response does not tell the caller which rows existed. This holds whether the field returns a payload or `ID` / `[ID!]` directly.
>
> To answer a key whose row is gone, Graphitron builds its id from the input, so the input must identify rows by exactly the table's primary key, directly or through a node id. A node id whose key columns go beyond the primary key qualifies; a key that matches no row is answered with the id the client sent. An input keyed by a unique key, or one with a filter column beyond the primary key, fails the build: a unique key cannot be turned into the primary key for a row that is not there, and an extra filter column would let a row that survives the filter be reported as deleted. If the response's ids need columns beyond the primary key and the input supplies only the primary key's plain fields, the build fails too, because nothing in the input says what a missing row's id would have encoded; key the input by the node id instead. Each of these failures also takes `failOnMissing: true` as its way out.
>
> Set `failOnMissing: true` to fail the call on such a key instead. Nothing is deleted, and the field reports an error: a typed one if a handler in the payload's errors field matches, otherwise a redacted one. A single-row `DELETE` fails the same way. `failOnMissing` admits any input that covers a primary key or unique key, extra filter columns included.
>
> ```graphql
> deleteStorageBinByCodePayload(in: StorageBinDeleteByCodeInput!): DeletedStorageBinPayload
>     @mutation(typeName: DELETE, table: "storage_bin", failOnMissing: true)
> ```
>
> A `multiRow: true` `DELETE` whose input covers a primary key or unique key follows the same rule. One whose input covers no key deletes whatever its filter matches and names no key, so neither reading applies to it, and `failOnMissing` is rejected there.

The directive description in `directives.graphqls` carries the first paragraph and the admission sentence. Also updated: the payload-returning section's "unless the field sets `missingAsDeleted: true`" sentences and the errors-field bullet in `mutation.adoc`; `migrating-from-legacy.adoc` (Graphitron 9's DELETE behaviour is now the default; a Graphitron 9 schema with a unique-key DELETE input adds `failOnMissing: true` or keys by the primary key, and one keyed by plain primary-key fields whose ids encode more than the primary key adds `failOnMissing: true` or keys by the node id); `typed-rejection.adoc` and `code-generation-triggers.adoc` where they name the old arms; the regenerated `supported-directives.adoc`.

## Retired vocabulary

* `missingAsDeleted` (the `@mutation` argument) and `missing_as_deleted` / `MISSING_AS_DELETED` (the fact-store column).
* `DeleteRows.Identified` (renamed `FailOnMissing`).
* `MissingAsDeletedError`, `MissingAsDeletedError.UnsupportedReturnShape`, `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey`, `DeleteRowsError.MissingAsDeletedEchoUnencodable`, and the codes `graphitron.missing-as-deleted.*`, `graphitron.delete-rows.missing-as-deleted-*`.
* `parseMissingAsDeleted`, `MISSING_AS_DELETED_SUPPORTED_VERBS`, `ARG_MISSING_AS_DELETED`.
* The `*MissingAsDeleted` fixture fields and every test name built on them.
* Prose describing the idempotent reading as an opt-in, or the direct-return DELETE as answering `null` or a shortened list on a miss. R1100, R1113 and R1123 mention `missingAsDeleted` in their bodies; the landing commit rewords whichever of them is still on the board (a Done item's file is already gone).

## Other solutions we've considered

* **Idempotent where the input allows, strict otherwise, silently.** No schema would need a change, but the same directive would mean two things depending on the input's shape, and adding a filter column to an input would silently turn a mutation that tolerates a missing key into one that fails on it.
* **Never fail by default, and answer only the deleted rows where the input cannot be echoed.** The failure behaviour would be uniform, but whether the response lists every input key or only the deleted ones would then depend silently on the input's shape, which is the same problem moved into the response.
* **Leave the direct-return DELETE as it is and refuse `failOnMissing` there.** Smaller, but a direct `[ID!]` DELETE would then neither echo nor fail: it would answer a shortened list in `RETURNING` order, a third reading beside the two this item names.
* **Keep the one-statement `IN` for the direct bulk DELETE and merge the answer in Java** (map `RETURNING` rows by key, then walk the input, taking the deleted row or the echo, and count misses for the strict reading inside a transaction). One round trip instead of one per row, and duplicate keys in a strict call would not fail. It is a second miss-handling mechanism beside the per-row skeleton the payload DELETE uses and R1123 is consolidating; if the per-row cost matters, a one-statement form for both shapes is its own item.
* **Keep `missingAsDeleted` as a deprecated alias.** Nothing is released, its only user is sis, and sis is changing those lines in the same bump.

## Provenance

Raised 2026-10-29 by the user after sis (the Graphitron 10 port) confirmed that all 27 of its DELETE mutations build and pass with `missingAsDeleted: true`, and that none of them wants the strict behaviour.

## Reviewer findings

### Round 1 (2026-10-29, Spec -> Ready, reviewer session 01FY5Uvuf5y2NoibRpfSpxJZ)

Verdict: withhold. One blocking finding, on question two. Question one passes: once this lands, a DELETE that names rows by key succeeds on a key whose row is gone without the author asking for it. The existing rows are deleted, the call commits, and the response answers every input key in input order, whether the field returns a payload or `ID` / `[ID!]` directly. An author who wants a missing key to fail the call writes `failOnMissing: true`, and a DELETE keyed by a unique key or carrying an extra filter column has to do one of two things: key by the primary key, or say `failOnMissing: true`. Every symbol, file, test and fixture the spec names exists as named; the one stale count (7 fixture fields set the argument, where 5 do) is corrected in this commit. Apart from the finding below, question two passes too: the plan keeps the three `DeleteRows` arms and moves which inputs reach them, reuses the walker's admission and `missingRowEcho`, and shares the payload path's per-row loop rather than standing a second miss mechanism beside it.

**Finding 1 (question two: fit). The direct `[ID!]` DELETE's per-row loop takes its row type from `paramSignature()`, but the echo it reuses builds records in primary-key-then-tail order.**

The plan passes "the encoder's `paramSignature()` as the `RETURNING` columns" to the extracted loop. That list types the loop: `buildBulkRecordTwoStepFetcher` derives `recordRowType` from it (`SourceKey.keyElementType(..., returning.columns())` today), and `buildBulkRecordPerRowDeleteBody` declares `rec` with that type and fills it from `returningResult(<that list>)`. So on the direct path `rec` is a `RecordN` in node-key order. The echo the plan reuses, `missingRowEcho`, builds `newRecord(...)` over `keyBinding()` in primary-key order and then `echoBinding()`, which is `Returning.columns()` order. The two orders agree only when the node's key lists the primary-key columns first, in primary-key order. A node declared with its key in another order, where the reordered columns differ in Java type, gets `rec = txd.newRecord(...)` assigning one `RecordN` to a differently parameterised one, and the consumer's generated sources fail to compile. For example, `@node(keyColumns: ["last_update", "film_id", "category_id"])` on `film_category` types the loop `Record3<LocalDateTime, Integer, Integer>` while the echo is a `Record3<Integer, Integer, LocalDateTime>`. (Where the reordered columns share a type, the code compiles and is correct, since the encoder reads by field.) Nothing planned catches it. The three sakila `keyColumns` nodes all list the primary key first and in order, and the direct-return fixtures (`deleteFilms`, `deleteFilmActorsByNodeId`) use the default key. The generalised `requireEchoMatchesReturning` compares only the tail.

What would satisfy it: name one column order that the direct bulk loop's `RETURNING` and its echo share. One way is to pass `Returning.widenedToNodeKey(writeTarget, encode.paramSignature()).columns()` as the loop's `RETURNING`, since the plan already derives that value for the echo columns and the payload path already uses it, and to encode the accumulated records by field reference, which `emitEncoded`'s `r.get(...)` lambda already does. Another is to make the echo record follow the order of the list it is given. Either way, name a test that pins it: a direct-return `[ID!]` DELETE on a node whose `keyColumns` are not in primary-key order, compiled by `graphitron-sakila-example`.

> Response: took the first way. `DmlTableField` gains `deleteReturning()` (`Returning.widenedToNodeKey(write.table(), encode.paramSignature())`), which both `requireEchoMatchesReturning` and the direct bulk loop's `RETURNING` read, so the loop is typed in primary-key-then-tail order like the echo, and encoding stays by field reference. Tests gain a key-order fixture: `FilmCategoryNode`'s `keyColumns` reordered to open with `last_update`, plus a direct-return `deleteFilmCategoryNodesDirect` over it. Stale-count correction acknowledged.

### Round 2 (2026-10-29, Spec -> Ready, reviewer session 01FY5UvrrMJDMHLHbb8N9C53)

Verdict: withhold. One blocking finding, on question one. Round 1's finding is resolved: `deleteReturning()` gives the direct bulk loop's `RETURNING` and `requireEchoMatchesReturning` one value in primary-key-then-tail order, the same order `missingRowEcho` builds in, and the reordered `FilmCategoryNode` key with `deleteFilmCategoryNodesDirect` pins it at compile and execution time. Question two passes: the walker's decision table, the classifier changes, the `DmlTableField` constructor change and the emitter plan all fit the code as it stands, and the plan reuses the per-row skeleton and the echo instead of standing a second miss mechanism beside them.

**Finding 1 (question one: the consumer-facing change). The spec names two inputs that newly fail the build; the design makes three.**

The Goal says a DELETE whose input cannot be echoed is one "keyed by a unique key, or carrying an extra filter column", and calls keying by the primary key or writing `failOnMissing: true` "the one migration step this item asks of an existing schema". There is a third case. An input that binds exactly the primary key with plain fields, on a DELETE whose response ids encode a node key reaching beyond the primary key, passes the primary-key test but fails the echo test: the default walk now reaches `idempotentByPrimaryKey`, which rejects it with `IdempotentDeleteEchoUnencodable` because nothing in the input supplies the extra columns. Today that DELETE builds and works, reading the extra columns back from the deleted row. `SuperkeyNodeIdDmlClassificationTest`'s `deleteFilmCategoriesByKey` (a `film_id` + `category_id` input answering `FilmCategoryNode` ids, which also encode `last_update`) is exactly this field: it rejects today only because it sets `missingAsDeleted: true`, and after the flip it rejects without the argument. On the direct-return path the case is new as well, since `classifyDeleteTableField` starts passing real `EchoColumns`: a direct `ID` / `[ID!]` DELETE keyed by plain primary-key fields on such a node table builds today and fails after the flip.

The design does name the rejection and its fix, so the build behaviour is not in question. The statement of what changes for a consumer is. The user-docs draft replaces today's § "When a key matches no row", which carries this case ("If the payload's ids need such columns and the input supplies only the primary key, the build fails ... Key the input by the node id instead."), and the draft drops that sentence. A reader of the new section would conclude that a plain primary-key input always qualifies.

What would satisfy it: name the third case wherever the spec lists what fails the build (the Goal's migration paragraph and the user-docs draft), with its two ways out (key the input by the node id, or write `failOnMissing: true`), and say on the direct-return side that it is newly reachable there. The sis claim already holds for it, since `missingAsDeleted: true` applies the same echo admission. The `SuperkeyNodeIdDmlClassificationTest` entry under Tests then reads as a semantic change for `deleteFilmCategoriesByKey` (it keeps its rejection without the argument) rather than a rename.

**Non-blocking.**

* `DmlBulkMutationsExecutionTest.deleteFilmCategoryNodes_aPrimaryKeyMatchingNoRow_stillFailsTheBatch` pins today's strict default on `deleteFilmCategoryNodes`. After the flip it inverts; the fixture moves under Tests say where `deleteFilmCategoryNodesMissingAsDeleted`'s tests go but not where this one goes (deleted as covered by the retargeted echo case, or moved onto a `failOnMissing` twin).
* Retired vocabulary names R1100 and R1113 as bodies to reword; R1123's body also names `missingAsDeleted` (its `Echo` / `FailFast` policies are defined against it). Whichever of the two lands second covers it, which the plan's "whichever lands first" paragraph already implies.
* `buildDmlFetcher` takes a `KeyStatement` and hands it to `emitDmlReturnExpression`, so both the single `ID` echo (after the `fetchOne`) and the direct bulk loop (in place of the one-statement fetch) need a seam there that the plan does not name. An implementer will find it; it does not change the design.

> Response: took it. The Goal now lists three kinds of input that fail the build, the third being plain primary-key fields under a response id that encodes more than the primary key, with its ways out (key by the node id, or `failOnMissing: true`), and says it builds today on both DELETE shapes. The design names where the direct-return path newly reaches it, and the user-docs draft restores the sentence and the matching `migrating-from-legacy.adoc` line. Tests add a pipeline case for the direct-return form and restate `SuperkeyNodeIdDmlClassificationTest` as a change in meaning. Non-blocking notes: `stillFailsTheBatch` is deleted with the reason given under Tests; R1123 joins the retired-vocabulary reword list; `buildDmlFetcher` gains a payload-declaration parameter, named in Design. R1123 landed `MissPolicy` and `PerRow` while this round was open, so the design now builds on them as they stand. The projection answers `Optional<MissPolicy>`, empty on `Broadcast`, because no arm reads "answer `null`", and that remains the single-row answer there. The extracted loop takes `PerRow`, the verb and the coordinate.
