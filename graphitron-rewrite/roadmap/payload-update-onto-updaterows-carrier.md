---
id: R258
title: "Payload-returning UPDATE onto the UpdateRows carrier"
status: Backlog
bucket: structural
priority: 4
theme: structural-refactor
depends-on: []
created: 2026-05-29
last-updated: 2026-05-29
---

# Payload-returning UPDATE onto the UpdateRows carrier

R246 migrated only the **direct-`@table`/ID-return** UPDATE leaf onto `UpdateRowsWalker` + the `UpdateRows` carrier; that path partitions SET/WHERE by PK-or-UK matched-key membership and ignores `@value` entirely. The **payload-returning** UPDATE shapes (`MutationDmlRecordField` / `MutationBulkDmlRecordField` reached when the return type is a `ResultReturnType`, e.g. `updateFilmPayload(in: FilmUpdateInput!): FilmPayload`) still classify through `MutationInputResolver.resolveInput`, which still demands `@value` to define the SET clause (`MutationInputResolver.java:509-519`). Because a single `@table` input type is routinely shared between the direct-return and payload-return UPDATE mutations (the sakila example shares `FilmUpdateInput` across `updateFilm`/`updateFilms` and `updateFilmPayload`/`updateFilmsPayload`), the `@value` directive cannot be removed from the schema while the payload path requires it. This slice migrates the payload-returning UPDATE onto the same `UpdateRows` carrier so that no UPDATE path reads `@value`, which is the precondition for R188 retiring the directive entirely.

This is the sibling of R246 under R222's "every DML kind on a walker carrier" trajectory; doing it on the walker (rather than a second, divergent PK-partition swap inside `resolveInput`) keeps a single UPDATE partition implementation with one set of typed `UpdateRowsError` arms and the same PK-or-UK semantics R246 established.

## Open design questions (resolve at Backlog -> Spec)

* **Where the carrier slot lands.** `MutationDmlRecordField` / `MutationBulkDmlRecordField` are shared across INSERT / UPDATE / UPSERT and currently carry a `TableInputArg`. Options: (a) introduce dedicated `MutationUpdatePayloadField` / `MutationBulkUpdatePayloadField` leaves implementing `UpdateRowsField` (mirrors R246's `MutationUpdateTableField`, branches in `FieldBuilder` on `ResultReturnType` + `DmlKind.UPDATE`), or (b) add an `UpdateRows` slot to the shared record only populated for the UPDATE arm. (a) keeps each leaf's slots non-Optional and honest, consistent with R246; (b) avoids a leaf explosion but reintroduces a kind-conditional Optional slot. Lean (a).
* **The payload emitter.** `buildMutationDmlRecordFetcher` reads `tia.setFields()` / `tia.lookupKeyFields()` today; it would read `updateRows().setColumns()` / `keyColumns()` / `matchedKey()` via the same `setGroupsOf` / `keyGroupsOf` projections R246 added, emitting byte-identical SQL. Confirm the payload RETURNING / record-construction shape composes with the carrier the same way the direct-return emitter does.
* **Bulk arm.** `MutationBulkDmlRecordField` is the `[FilmUpdateInput!]!` -> `FilmsPayload` shape; it reuses `inputArg.list()` dispatch exactly as R246's bulk arm does.
* **`@condition` / override on payload-UPDATE input fields.** R246 rejects these with `OverrideConditionNotSupported` / `UnsupportedInputFieldShape` because it cannot emit the filter; confirm the payload path inherits the same rejection (the walker is shared, so it should fall out for free).
* **Semantic shift for the payload path.** Today payload-UPDATE partitions by `@value` (PK-coverage enforced separately); after this slice it partitions by PK-or-UK matched-key membership, the same widening R246 made. Any payload-UPDATE that relied on `@value` marking a *PK* column as SET, or a non-PK column as WHERE, changes behaviour. Audit whether such shapes exist in fixtures.

## Sequencing

* **Depends on R246** (Done): provides `UpdateRowsWalker`, the `UpdateRows` carrier, the `UpdateRowsField` interface, the `UpdateRowsError` sub-seal, and the `setGroupsOf` / `keyGroupsOf` emitter projections this slice reuses.
* **Unblocks R188**: once this lands, no UPDATE path reads `@value`. R188's remaining surface is then DELETE + the `@value` directive declaration / diagnostics / plumbing retirement (R188's body is being re-scoped to that remainder).
* **R257** (`updaterows-walker-sdl-substrate`) is orthogonal; both consume the walker and can land in either order.
