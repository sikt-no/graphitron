---
id: R1124
title: "A DELETE whose key matches no row is idempotent by default, and failing on it is the opt-in"
status: Spec
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

Answering a key whose row is gone takes one thing from the input: it must supply the whole primary key, directly or through a node id, and filter on nothing else, because the answer for a missing row is built from the input. A DELETE whose input cannot be answered that way (keyed by a unique key, or carrying an extra filter column) fails the build with a message naming both ways out: key the input by the primary key, or write `failOnMissing: true`. That is the one migration step this item asks of an existing schema. The rule covers every DELETE whose input covers a primary key or unique key, with or without `multiRow: true`. The one DELETE outside it is a `multiRow: true` DELETE whose input covers no key, which deletes every row its filter matches: it names no key that could be missing, and it behaves as today.

## What exists today

`missingAsDeleted` shipped as an opt-in, documented in `mutation.adoc` § "When a key matches no row" and in the directive's own description in `directives.graphqls`. Without it, the DELETE shapes behave differently from each other on a key that matches no row:

* **Payload-returning, list input** (`MutationField.MutationBulkDmlRecordField`, emitted by `TypeFetcherGenerator.buildBulkRecordTwoStepFetcher` with `buildBulkRecordPerRowDeleteBody` as its per-row body): one statement per input row inside one transaction; a miss throws "@mutation(typeName: DELETE) bulk row matched zero rows; @lookupKey filter found no target for input row at index …" and rolls back the whole call.
* **Payload-returning, single input** (`MutationField.MutationDmlRecordField`, `buildMutationDmlRecordFetcher`): a miss renders the payload `null`, with no error.
* **Direct `ID` return** (`MutationField.DmlTableField`, `buildMutationDeleteFetcher`): `.fetchOne(...)` answers `null`; a non-null `ID!` turns that into a non-null violation.
* **Direct `[ID!]` return** (same, list input): one `DELETE … WHERE (key) IN (…) RETURNING …` statement through `buildBulkLookupRowIn`; a miss shortens the list, which comes back in `RETURNING` order rather than input order.

With `missingAsDeleted: true` on a payload-returning DELETE the walker produces `DeleteRows.IdempotentByPrimaryKey` instead of `DeleteRows.Identified`, and both payload emitters answer a miss with `TypeFetcherGenerator.missingRowEcho`, a key record built from the values the WHERE compared. Its execution tests are `DmlBulkMutationsExecutionTest`'s `deleteFilmsMissingAsDeleted_*` and siblings. Of the sakila fixture's 16 DELETE fields, 7 set it.

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

`FieldBuilder.classifyDeleteTableField` stops refusing up front and passes the parsed `failOnMissing` and real `EchoColumns` to the walker: the node type's key columns beyond the primary key, taken from the `encodeReturn` it already resolves before the walk (`Returning.widenedToNodeKey(writeTarget, encode.paramSignature()).nodeKeyTail()`, the derivation the payload path makes). The `DmlTableField` compact constructor drops its `IdempotentByPrimaryKey` refusal and takes the record carriers' check instead: the echo binds exactly the columns the encoder reads beyond the primary key, in the same order (`MutationField.requireEchoMatchesReturning`, generalised to take the column list).

The emitters read the miss behaviour through one projection from the arm, `IdempotentByPrimaryKey` to echo and `FailOnMissing` to fail fast, used on all four paths (payload single, payload bulk, direct single, direct bulk) rather than an `instanceof` at each. That projection is R1123's `MissPolicy` (`Echo`, `FailFast`); whichever item lands first introduces it and the other extends it. `DmlChainAndGuards.missingRowEcho`, an `Optional<CodeBlock>` today, becomes that policy, since it now has three outcomes (echo, throw, and nothing to do on `Broadcast`).

Emitters, in `TypeFetcherGenerator`:

* **Single `ID`** (`buildMutationDeleteFetcher`, non-list): after the `fetchOne`, on `IdempotentByPrimaryKey` emit `if (payload == null) payload = <encoder>(<echo values>);`, with the values sourced the way `missingRowEcho` sources them (the chunk's `keyValues()` for the primary key, the decode-local slots for `echoBinding()`). Factor that sourcing out of `missingRowEcho` so the record form and the encoder-argument form share it. On `FailOnMissing` emit a throw of the single-row message below.
* **List `[ID!]`, key-identified**: moves off the one-statement `IN` onto the per-row loop the payload bulk DELETE uses. The loop is extracted out of `buildBulkRecordTwoStepFetcher` as a helper that emits the `dsl.transactionResult(...)` block declaring the accumulated `Result<RecordN>`, taking the per-row body and the `RETURNING` column list as parameters; it is where the miss policy is applied. `buildBulkRecordTwoStepFetcher` calls it and keeps its own envelope (empty `Result`, `Outcome` wrap, catch arm). `buildMutationDeleteFetcher`'s list arm calls it inside `buildDmlFetcher`'s existing envelope (its empty-list `List.of()`, its catch arm), with `buildBulkRecordPerRowDeleteBody` as the body and the encoder's `paramSignature()` as the `RETURNING` columns, exactly the columns `emitEncoded` projects today, then encodes the accumulated records in order. The two envelopes stay separate because their value types differ; only the loop is shared.
* **List `[ID!]`, `Broadcast`**: keeps the one-statement `IN`, as today.

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

* **A miss count for the strict DELETE.** R1123 gives the payload bulk UPDATE a typed exception counting every missed row, and makes it verb-neutral so the bulk DELETE can adopt it by switching its policy. That switch is not part of this item: after the flip the strict DELETE is an explicit opt-in that sis does not use, so the count has no consumer on DELETE yet (sis tracks the DELETE side as its row C34). The two items touch the same skeleton and neither depends on the other; whichever lands second rebases onto the first. If R1123 lands first, the direct bulk DELETE picks up its `PerRow` seam with the rest of the skeleton.
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
* `graphitron/.../rewrite/model/DeleteRows.java`, `MutationField.java`: `Identified` renamed to `FailOnMissing`, javadoc for the arms as readings; drop the `DmlTableField` refusal, add the echo-matches-encoder check.
* `graphitron/.../rewrite/generators/TypeFetcherGenerator.java`: the arm-to-policy projection, the single-row echo and throw on both direct and payload paths, the extracted per-row loop and the direct bulk DELETE on it, the shared echo-value sourcing; rewrite the "Empty-match semantics" javadoc on `buildMutationDeleteFetcher`.
* `graphitron-sakila-example/src/main/resources/graphql/schema.graphqls`: the fixture moves listed under Tests.
* Docs listed under User documentation.

## Tests

**Unit tier** (`DeleteRowsWalkerTest`): an exact-primary-key input without the flag yields `IdempotentByPrimaryKey`; with `failOnMissing` it yields `FailOnMissing`; a unique-key input and a primary key plus a filter column each reject with `IdempotentDeleteNeedsPrimaryKey` without the flag and yield `FailOnMissing` with it; a `multiRow` input covering the primary key yields `IdempotentByPrimaryKey` and one covering only a unique key rejects with `IdempotentDeleteNeedsPrimaryKey`; a superkey node id with unsupplied echo columns rejects with `IdempotentDeleteEchoUnencodable`; `multiRow` with no key yields `Broadcast` without the flag and `FailOnMissingNeedsKey` with it; no key without `multiRow` yields `NoUniqueKeyCoverage` either way.

**Pipeline tier** (`GraphitronSchemaBuilderTest`): `failOnMissing` on INSERT, UPDATE and UPSERT rejects with `UnsupportedVerb`; a direct-return DELETE classifies to `DmlTableField` carrying `IdempotentByPrimaryKey`; a direct-return DELETE keyed by a unique key rejects with the renamed arm. The LSP and fact tests that name the old codes and column (`RejectionSeverityCoverageTest`, `RejectionResidueDrainageTest`, `TriggerFactPopulationPinTest`, `HierarchyKindRegistryTest`, `SuperkeyNodeIdDmlClassificationTest`, `RecordCarrierEchoReturningInvariantTest`, `GraphitronMcpServerTest`, the `field-site-payloads.graphqls` fact fixture and `EntryFamilyFixture`) follow the rename.

**Execution tier.** The sakila fixture moves so each reading keeps its proof:

* `deleteFilmsMissingAsDeleted`, `deleteFilmCategoryNodesMissingAsDeleted`: removed; their tests retarget `deleteFilmsIdCarrier` and `deleteFilmCategoryNodes`, which are now the same declarations without the argument.
* `deleteFilmMissingAsDeleted`, `deleteFilmActorsMissingAsDeleted`, `deleteSplitKeyChildrenMissingAsDeleted`: lose the argument and the suffix (`deleteFilmIdCarrier`, `deleteFilmActorsWithErrors`, `deleteSplitKeyChildren`); tests renamed to match.
* New `deleteFilmsFailOnMissing` (list, payload) carries `deleteFilmsIdCarrier_zeroRowRow_reachesTheConfiguredLogWithoutTheInputRow`, renamed; new `deleteFilmFailOnMissing` (single, payload) pins that a miss now reports an error where today it renders `null`.
* `deleteStorageBinByCode` and `deleteStorageBinByCodePayload` (unique key) take `failOnMissing: true`; a new case on each pins that a code matching no bin fails.
* Direct return: `deleteFilms` with an existing and a missing id, in non-key order, answers both ids in input order and deletes the existing one; `deleteFilmActorsByNodeId` does the same through a composite node id; a new `deleteFilmsDirectFailOnMissing(in: [FilmDeleteInput!]!): [ID!]!` with `failOnMissing: true` rolls back the existing row when another key misses; `deleteFilmsByReleaseYear` (`Broadcast`) stays green unchanged.

The goal is demonstrated by the retargeted `deleteFilmsIdCarrier` cases (no argument, missing key echoed, call commits), the direct `deleteFilms` case (the same for a direct return), and the two `*FailOnMissing` cases (the opt-in fails on both shapes).

## User documentation (first-client draft)

The argument table row in `mutation.adoc`:

> `failOnMissing` | `Boolean` | `false` | On a `DELETE`, makes a key that matches no row fail the call instead of counting as already deleted. Rejected on `INSERT`, `UPDATE` and `UPSERT`, and on a `multiRow` `DELETE` whose input covers no key. See <<when-a-key-matches-no-row>>.

§ "When a key matches no row", replacing today's:

> A `DELETE` treats a key that matches no row as already deleted. The rows that exist are deleted, the call commits, and the response holds every input key in input order, whether or not its row existed. A retried call, or two clients deleting the same row, then succeed, and the response does not tell the caller which rows existed. This holds whether the field returns a payload or `ID` / `[ID!]` directly.
>
> To answer a key whose row is gone, Graphitron builds its id from the input, so the input must identify rows by exactly the table's primary key, directly or through a node id. A node id whose key columns go beyond the primary key qualifies; a key that matches no row is answered with the id the client sent. An input keyed by a unique key, or one with a filter column beyond the primary key, fails the build: a unique key cannot be turned into the primary key for a row that is not there, and an extra filter column would let a row that survives the filter be reported as deleted.
>
> Set `failOnMissing: true` to fail the call on such a key instead. Nothing is deleted, and the field reports an error: a typed one if a handler in the payload's errors field matches, otherwise a redacted one. A single-row `DELETE` fails the same way. `failOnMissing` admits any input that covers a primary key or unique key, extra filter columns included.
>
> ```graphql
> deleteStorageBinByCodePayload(in: StorageBinDeleteByCodeInput!): DeletedStorageBinPayload
>     @mutation(typeName: DELETE, table: "storage_bin", failOnMissing: true)
> ```
>
> A `multiRow: true` `DELETE` whose input covers a primary key or unique key follows the same rule. One whose input covers no key deletes whatever its filter matches and names no key, so neither reading applies to it, and `failOnMissing` is rejected there.

The directive description in `directives.graphqls` carries the first paragraph and the admission sentence. Also updated: the payload-returning section's "unless the field sets `missingAsDeleted: true`" sentences and the errors-field bullet in `mutation.adoc`; `migrating-from-legacy.adoc` (Graphitron 9's DELETE behaviour is now the default; a Graphitron 9 schema with a unique-key DELETE input adds `failOnMissing: true` or keys by the primary key); `typed-rejection.adoc` and `code-generation-triggers.adoc` where they name the old arms; the regenerated `supported-directives.adoc`.

## Retired vocabulary

* `missingAsDeleted` (the `@mutation` argument) and `missing_as_deleted` / `MISSING_AS_DELETED` (the fact-store column).
* `DeleteRows.Identified` (renamed `FailOnMissing`).
* `MissingAsDeletedError`, `MissingAsDeletedError.UnsupportedReturnShape`, `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey`, `DeleteRowsError.MissingAsDeletedEchoUnencodable`, and the codes `graphitron.missing-as-deleted.*`, `graphitron.delete-rows.missing-as-deleted-*`.
* `parseMissingAsDeleted`, `MISSING_AS_DELETED_SUPPORTED_VERBS`, `ARG_MISSING_AS_DELETED`.
* The `*MissingAsDeleted` fixture fields and every test name built on them.
* Prose describing the idempotent reading as an opt-in, or the direct-return DELETE as answering `null` or a shortened list on a miss. R1100 and R1113 mention `missingAsDeleted` in their bodies; the landing commit rewords them.

## Other solutions we've considered

* **Idempotent where the input allows, strict otherwise, silently.** No schema would need a change, but the same directive would mean two things depending on the input's shape, and adding a filter column to an input would silently turn a mutation that tolerates a missing key into one that fails on it.
* **Never fail by default, and answer only the deleted rows where the input cannot be echoed.** The failure behaviour would be uniform, but whether the response lists every input key or only the deleted ones would then depend silently on the input's shape, which is the same problem moved into the response.
* **Leave the direct-return DELETE as it is and refuse `failOnMissing` there.** Smaller, but a direct `[ID!]` DELETE would then neither echo nor fail: it would answer a shortened list in `RETURNING` order, a third reading beside the two this item names.
* **Keep the one-statement `IN` for the direct bulk DELETE and merge the answer in Java** (map `RETURNING` rows by key, then walk the input, taking the deleted row or the echo, and count misses for the strict reading inside a transaction). One round trip instead of one per row, and duplicate keys in a strict call would not fail. It is a second miss-handling mechanism beside the per-row skeleton the payload DELETE uses and R1123 is consolidating; if the per-row cost matters, a one-statement form for both shapes is its own item.
* **Keep `missingAsDeleted` as a deprecated alias.** Nothing is released, its only user is sis, and sis is changing those lines in the same bump.

## Provenance

Raised 2026-10-29 by the user after sis (the Graphitron 10 port) confirmed that all 27 of its DELETE mutations build and pass with `missingAsDeleted: true`, and that none of them wants the strict behaviour.
