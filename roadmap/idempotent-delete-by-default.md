---
id: R1124
title: "A DELETE whose key matches no row is idempotent by default, and failing on it is the opt-in"
status: Backlog
bucket: feature
priority: 3
theme: mutation-write
depends-on: []
created: 2026-10-29
last-updated: 2026-10-29
---

# A DELETE whose key matches no row is idempotent by default, and failing on it is the opt-in

## Goal

A `@mutation(typeName: DELETE)` treats an input key that matches no row as already deleted, without the author asking for it: the rows that exist are deleted, the call commits, and the payload echoes every input key in input order. An author who wants the call to fail on such a key opts in with a directive argument. The `missingAsDeleted: Boolean = false` argument, which today makes the idempotent reading the opt-in, is retired. A malformed or wrong-type id fails as it does today under either reading; the opt-in is about a well-formed key whose row is not there.

The idempotent reading is the one consumers want. It is what Graphitron 9 did for every DELETE: `batchDelete(...).execute()` ignored the row counts, and the payload listed every input id that no longer exists. A client that retries after a lost response succeeds, and a row someone else already deleted does not roll back everyone else's rows in the batch. sis, the first consumer to port, turned `missingAsDeleted: true` on for all 27 of its DELETE mutations and wants the strict behaviour on none. A default that every real schema overrides is the wrong default.

## What exists today

`missingAsDeleted` shipped as an opt-in, documented in `mutation.adoc` § "When a key matches no row" and in the directive's own description in `directives.graphqls`. With it off, a bulk DELETE whose key matches no row throws "bulk row matched zero rows; @lookupKey filter found no target …" and rolls back the whole call. A single-row DELETE renders the payload `null`. With it on, the idempotent reading holds. Its execution tests are `DmlBulkMutationsExecutionTest`'s `deleteFilmsMissingAsDeleted_*` and siblings. Of the sakila fixture's 16 DELETE fields, 7 set it. The model side is `DeleteRows.IdempotentByPrimaryKey`, read through `MutationInputResolver`.

The idempotent reading is not available for every DELETE shape. Three refusals guard it:

* `DeleteRowsError.MissingAsDeletedNeedsPrimaryKey`: the input must identify rows by exactly the primary key, directly or through a node id. A unique key cannot be echoed for a missing row, and an extra filter column would let the echo claim a deletion that did not happen.
* `DeleteRowsError.MissingAsDeletedEchoUnencodable`: when the payload's ids encode node-key columns beyond the primary key, the input has to supply them (key it by the node id).
* A DELETE that returns `ID` or `[ID!]` directly (`MutationField.DmlTableField`) refuses the argument outright; only the payload-returning DELETE echoes.

## The design fork: shapes that cannot be idempotent

Flipping the default means deciding what a DELETE gets when its shape fails one of those three refusals and the author did not opt in to strict.

* **(a) Idempotent where the shape allows, strict otherwise, silently.** No author sees a change, but the same directive then means two different things depending on the input's shape, and a schema edit (adding a filter column to the input) silently flips a mutation from idempotent to strict.
* **(b) A located build error unless the author opts in to strict.** The semantics are one rule everywhere, and an author who cannot have idempotence says so in the schema. It costs a migration step for every such field.
* **(c) Lift the refusals where they are not principled.** The direct `ID` / `[ID!]` return looks like an emitter limitation rather than a semantic one: the input keys are there to echo. The primary-key and encodability refusals are principled and stay.

Recommendation: (b) together with (c) for the direct-return shape. That leaves only the genuinely unechoable inputs needing the strict opt-in. Spec settles it.

## Things to settle in Spec

* **The opt-in's name.** It covers "a well-formed key whose row is not there", not "an incorrect id", which fails either way. Candidates: `failOnMissing: true`, `requireExisting: true`. Spec picks, and makes the rejection messages and the LSP codes of the refusals above name the new argument instead of `missingAsDeleted`.
* **Single-row DELETE.** Today the default renders the payload `null` for a missing key. Under the flip it echoes the input key, as `missingAsDeleted: true` does today (`deleteFilmMissingAsDeleted_singleMissingKey_echoesTheInputRatherThanNull`).
* **Retiring `missingAsDeleted`.** Graphitron 10 has not been released, so remove the argument outright rather than keeping a deprecated alias. sis must delete it from its 27 mutations in the same step. Coordinate the landing with the sis port session.
* **Error-channel interaction.** Under the strict opt-in, the zero-row throw routes through the payload's errors field exactly as today. R1123 (the UPDATE guard's miss count) is the UPDATE-side sibling; check whether a strict DELETE should report a miss count the same way.

## Docs

`mutation.adoc` (the argument table and § "When a key matches no row"), the directive description in `directives.graphqls`, `migrating-from-legacy.adoc` (the Graphitron 9 DELETE behaviour now matches by default), `typed-rejection.adoc` and `code-generation-triggers.adoc` where they name `missingAsDeleted`, and the regenerated `supported-directives.adoc`.

## Retired vocabulary

* `missingAsDeleted` (the `@mutation` argument), and every rejection message, lspCode and test name built on it.

## Provenance

Raised 2026-10-29 by the user after sis (the Graphitron 10 port) confirmed that all 27 of its DELETE mutations build and pass with `missingAsDeleted: true`, and that none of them wants the strict behaviour.
