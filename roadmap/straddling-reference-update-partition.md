---
id: R784
title: "Partition a straddling cross-table @nodeId reference per column on UPDATE instead of rejecting it"
status: Backlog
bucket: feature
priority: 2
theme: mutation-write
depends-on: []
created: 2026-08-21
last-updated: 2026-08-21
---

# Partition a straddling cross-table @nodeId reference per column on UPDATE instead of rejecting it

A generated UPDATE has two halves. `UpdateRowsWalker` asks which input fields cover a primary or unique key of the write target; the matched key's columns become the WHERE clause and every remaining input field populates the SET clause. A `@nodeId` input field is not one column: on a cross-table reference it expands into the foreign key's child columns on the write target. So a single input field can expand into columns that fall on both sides of the WHERE and SET boundary. Stage 6 of the walker calls that a *straddle* and rejects it with `UpdateRowsError.MixedCarrierKeyMembership`. There are real schemas whose only sensible spelling straddles, and for those the rejection has no workaround that preserves the global ID.

The shape, in the terms of a consumer schema found while migrating from Graphitron 9 to 10. The write target has a two-column primary key `(INSTITUSJONSNR_EIER, PLNR)`: an institution number plus a person number, because a person number is only unique within one institution. The same table has a foreign key to a library whose child columns are `(INSTITUSJONSNR_EIER, BIBSYSBESTSTEDKODE)`: the library code, qualified by the same institution number, because a student's library must belong to the student's own institution. An UPDATE input carries `id: ID!` (the row's own global ID, covering the primary key) and `bibliotekId: ID! @nodeId(typeName: "Bibliotek")` (the library to point at). `id` covers the key, so both its columns are the WHERE. `bibliotekId` then expands into one column already in the WHERE and one that is not, and the walker rejects.

The overlap is benign, and the statement the schema asks for is unambiguous. The two contributors to `INSTITUSJONSNR_EIER` cannot disagree for well-formed input, because the foreign key constraint forces them equal. The wanted statement is `UPDATE t SET BIBSYSBESTSTEDKODE = ? WHERE INSTITUSJONSNR_EIER = ? AND PLNR = ?`: the shared column is read as identity and never written, and only the non-key half of the foreign key is set. Graphitron 9 generated exactly this and checked agreement at runtime.

**Decided behaviour.** A cross-table reference carrier whose lifted columns straddle the matched key partitions per column rather than per field. Its in-key columns contribute to the WHERE side only, its out-of-key columns become SET writes, and where an in-key column already has another contributor the two decoded values are agreement-checked before any DML runs. Disagreement is a runtime error and can only be a runtime error, since both values arrive on the wire.

## Why this is not a from-scratch capability

Two pieces of the machinery are already in the tree. The walker already tolerates this overlap for a *self*-referencing foreign key: a self-FK carrier routes its columns wholly to SET regardless of key membership, on the stated rationale that its columns point at a sibling row and are never this row's identity, so a shared key column it writes is an ordinary SET write. Only the table-identity test (`containingTable` equals the FK's target table) separates that admitted case from this rejected one, and the rationale transfers: a cross-table FK is equally a pointer rather than an identity.

The cross-partition agreement check also already exists. `TypeFetcherGenerator.emitKeySetAgreementPreamble` re-decodes each side of a column that appears in both the WHERE and the SET partition, gathers the present values, and pairwise-checks them through `NodeIdEncoder.requireColumnAgreement`, throwing a `GraphqlErrorException` that names both contributing input fields before the DML runs. Its own worked example is this same shape on the self-FK side. The bulk (list-input) arm has the per-row analogue in `emitBulkKeySetAgreement`.

## The two costs the shape actually carries

**The decode-slot invariant has to become explicit first, and this is the bulk of the work.** Every emit site that reads a decoded composite `@nodeId` infers the record slot from the column's *position in its partition group*. `SetGroup.columns()` is documented as being in decode-record slot order; `keyGroupsOf` assigns `InputColumnBinding.RecordBinding.index()` positionally from the group it just built; there are six hardcoded `.value1()` reads that encode "a one-column group is arity 1, so slot 1", plus about a dozen positional `value<n>()` reads. All of that rests on one unstated invariant: a carrier's columns land in one partition, whole, in decode order. Partitioning per column breaks the invariant. In the consumer schema above the positional read is accidentally correct, because `INSTITUSJONSNR_EIER` happens to be the first child column of the foreign key; reverse the constraint's column order and the same code silently writes the decoded institution number into the library-code column. So the item is not "delete the reject and teach the preamble one more case". It is "carry the decode slot as an explicit datum on `SetColumn` and `KeyColumn` and replace every positional inference with it", after which the partition split is close to free. The lift stands on its own merits: it turns a comment-only invariant into a compiler-visible one.

**Deduplicating the WHERE side is mandatory rather than cosmetic.** With two contributors to one key column, the single-row arm would emit a redundant `.and()` predicate, which is harmless. The bulk arm builds `UPDATE t SET ... FROM (VALUES ...) AS v(...)`, and two contributors to one column put a duplicate name in the derived-table alias, which does not run.

That raises the model question the item has to settle: is the straddler's in-key column a `KeyColumn` that the emitters deduplicate, or a third kind of contribution? A third partition on the `UpdateRows` carrier, meaning "this carrier contributes this key column only to be agreement-checked, never to filter and never to write", looks preferable. It puts the walker's decision in the carrier instead of hiding it in an emitter's first-contributor-wins map; it lets the preamble reuse its existing two-sided compare rather than learning a general key-against-key intersection walk; and it falls out correctly when the straddler is the *sole* contributor of that key column (primary key `(A, B)`, the straddler covering `A` and `C`, another field covering `B`), where `A` is a genuine WHERE column and there is nothing to check. It also leaves the self-FK path untouched, where the shared column genuinely is written.

## The fork the shape opens: clearing a straddling reference

If the straddling carrier is nullable and a caller sends an explicit null, per-column partitioning emits `SET BIBSYSBESTSTEDKODE = NULL` and leaves the institution number alone, producing a half-null foreign key tuple. PostgreSQL's default `MATCH SIMPLE` treats a partially-null foreign key as satisfied, so the constraint does not catch it and the row keeps a dangling half-key instead of failing.

The coherent rule is that the in-key half of a straddling foreign key *is* the row's identity and is therefore immutable, so such a reference can be re-pointed only within the same key value and can never be cleared. That makes nullability a build-time rejection: a straddling cross-table reference carrier must be non-null. `InputField.ColumnBackedReferenceField` already carries `nonNull`, so the walker only needs to thread it onto its local `Contribution`. The alternative is admitting the nullable shape and throwing at runtime on an explicit null; build-time is preferable because the hazard is knowable from the schema alone.

## Scope notes

`MixedCarrierKeyMembership` survives, narrowed for the second time. It keeps a real subject: a straddling *own-columns* carrier, that is a same-table composite `@nodeId` whose columns span the matched key. Those columns are this row's own identity, so writing half of them means moving the row, which is a different act from re-pointing a sibling reference. The walker's local `Contribution` record currently carries only a `selfReference` flag and needs to distinguish own-columns from self-FK from cross-table FK. No retirement sweep is needed, which is a real saving over retiring the arm.

There is no fixture for this shape. The `email` and `mailbox` pair does not straddle: `email`'s foreign key to `mailbox` is single-column and lies wholly inside `email`'s primary key. This needs a new table pair rather than a new column on `email`, so the existing self-FK execution coverage and the `Email` record shape stay untouched. A minimal pair mirroring the consumer schema is a catalogue table keyed `(tenant_id, catalog_code)` and an item table keyed `(tenant_id, item_no)` carrying a composite foreign key `(tenant_id, catalog_code)` to it.

Route-wholly-to-SET was considered as a cheaper first step and rejected. It is not a smaller version of this change but a different emitted statement: the shared identity column becomes a live SET write with its jOOQ changed flag set, and an explicit null would null the row's own identity. It would need its own doctrine paragraph, its own execution coverage, and then a behaviour change to retire. The `typed-rejection.adoc` paragraph on this arm has already been narrowed once; narrowing it twice more in opposite directions is worse than narrowing it once correctly. A consumer needing an unblock before this lands has a schema-side one: drop `@nodeId` on the slot and bind the non-key foreign-key column directly with `@field`, at the cost of the caller sending a raw code instead of a global ID.

Documentation touched: the WHERE and SET partition rule in `docs/manual/reference/directives/mutation.adoc`, the cross-table FK reference section of the `@nodeId` reference, and the `UpdateRowsError` paragraph in `docs/architecture/explanation/typed-rejection.adoc`.
