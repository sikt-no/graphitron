---
id: R977
title: "A self-FK reference writing the tenant column in an UPDATE SET is agreement-checked, not declined"
status: Backlog
bucket: bug
priority: 3
theme: runtime-connection
depends-on: []
created: 2026-09-25
last-updated: 2026-09-25
---

# A self-FK reference writing the tenant column in an UPDATE SET is agreement-checked, not declined

## Goal

Under database-per-tenant routing (a `<tenantColumn>` build), an `@mutation(typeName: UPDATE)` whose input carries a self-referencing `@nodeId @reference` (a foreign key from the table to itself) whose columns include the tenant column classifies and routes on the row's own tenant, with the referenced row's tenant checked equal to it before any SQL runs, instead of being rejected as an attempt to move the row between tenants. A plain input field writing the tenant column in `SET` stays rejected, since that one really is a move. For Sikt's sis this unblocks two mutations (`endreUndervisningsaktivitetHierarki`, `registrerFellesUndervisningForUndervisningsaktivitet`); low priority, both experimental.

## Observed (verified against the generator source, 2026-09-25)

- **The decline is keyed on the column alone.** `TenantBindingIndex.Fold.collectFromWhereKeys` walks `UpdateRows.setColumns()` and declines ("input field '<name>' writes tenant column '<col>' in the UPDATE's SET clause ... no UPDATE can move a row between tenants") for any `SetColumn` whose `targetColumn` matches the tenant column. It does not look at which carrier produced the column or whether the value is already forced equal to the WHERE side.
- **A self-FK routes wholly to SET.** In `UpdateRowsWalker`, a contribution of `CarrierRole.SelfFk` goes through `addSetColumns` whatever its key membership, since its columns point at a sibling row rather than this row's identity. A self-FK whose child columns include the tenant column (the sis `UNDERVISNINGSAKTIVITET__EIER__UNDERVISNINGSAKTIVITET__FK` and `...__SAMKJOR__...` keys both lead with `INSTITUSJONSNR_EIER` and target the table's own primary key) therefore lands the tenant column in `SET`, and the decline fires.
- **The walker already records the agreement the decline is missing.** Stage 6a of the same walker emits an `AgreementObligation(column, keySide, referenceSide)` for every self-FK column that is also a matched-key column, and the four UPDATE emission arms in `TypeFetcherGenerator` lower each one to `NodeIdEncoder.requireColumnAgreement`, checked before the DML. So for the sis shape the SET-side `INSTITUSJONSNR_EIER` is already required to equal the WHERE-side value decoded from `id`: it is a no-op for well-formed input and a runtime error for a cross-tenant reference. That is exactly the "mixed-tenant call refused" semantics wanted; only the classifier's decline stands in the way.
- **Where a plain field can still reach SET.** A plain own-columns carrier whose columns all sit in the matched key goes to WHERE, not SET. A plain field reaches SET on the tenant column only when the matched key does not cover it (or through the out-of-key half of a cross-table straddler), and there the decline is right: nothing pins the value to the row's tenant.

## Direction (for the Spec author to decide)

- **Discriminator:** do not decline a SET-side tenant column that is the `referenceSide` of an `AgreementObligation` in `UpdateRows.agreementObligations()` (same column and input field). The tenant is divined from the WHERE partition as today, and the obligation already refuses disagreement. This reads a fact the walker has decided rather than re-deriving "decoded carrier versus scalar" in the fold. Every other SET-side tenant column keeps the current decline.
- **Where the check runs relative to acquisition:** the tenant is divined from the WHERE slots and the connection acquired from it; the SET-side value never routes, so a disagreeing reference cannot reach another tenant's database even if the agreement check ran after acquisition. The Spec should still state the order, and whether the check should run before `dslFor` so a refused call opens no connection.
- **Open:** whether to drop the shared column from the emitted `SET` list, since it can only equal the WHERE value. Writing a key column to its own value is harmless in principle, but on some databases it fires update triggers or FK re-validation; if dropped, it should be dropped for every self-FK key overlap, not only the tenant column, and that is a walker change with its own tests.
- **Batched input:** the WHERE `id` slots fold through `divinedTenant` across the list, and each row's obligation checks per row, so a list mixing a cross-tenant `parentId` into one row fails that row's check. The Spec should say whether that is a whole-call refusal (as agreement failures are today) and pin it.

## Tests the Spec should require

- Classification tier: the self-FK shape classifies `ArgumentBound` on the WHERE slot with no rejection; the existing `TenantBindingClassificationTest.tenantColumnInAnUpdateSetPartitionRejects` (plain field on the tenant column in `SET`) keeps rejecting.
- The sakila fixtures need a self-FK whose child columns include a tenant column and target the table's own key. The `catalogue` / `catalogue_item` family in `graphitron-sakila-db`'s `init.sql` is keyed on `tenant_id` but its FK is cross-table; the multitenant example build routes on `film_id`. The Spec picks where the new table lives and which tenant column the test runs under.
- Execution tier: same-tenant `parentId` updates the row; a `parentId` decoding to another tenant than `id` is refused before any SQL.

## Related

- R976 (`service-tenant-binding`) and R975 (`tenant-routed-mount-authorization`), filed from the same sis port, are independent of this item.
