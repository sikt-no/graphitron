---
id: R71
title: "RecordN-key parity for LifterRowKeyed and non-jOOQ-Record record parents"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: [emit-record1-keys-instead-of-row1]
---

# RecordN-key parity for LifterRowKeyed and non-jOOQ-Record record parents

[R61 (`emit-record1-keys-instead-of-row1.md`)](emit-record1-keys-instead-of-row1.md) flips four `BatchKey` arms (`MappedRowKeyed`, `RowKeyed`, `AccessorRowKeyedSingle`, `AccessorRowKeyedMany`) from `RowN<...>` keys to `RecordN<...>` keys at the rows-method boundary. Two arms can't flip in that iteration without out-of-band changes, leaving `BatchKey.keyElementType()` non-uniform across the variant family. This item closes that asymmetry.

The two deferred arms:

- **`LifterRowKeyed`.** `BatchKeyLifterDirectiveResolver` pins the consumer-supplied `@batchKeyLifter` static method's return type to `org.jooq.Row1..Row22` (`BatchKeyLifterDirectiveResolver.java:266-273`); a `Record1..Record22` return is rejected today. Flipping `LifterRowKeyed.keyElementType()` to `RecordN` requires the validator to require (or accept) `RecordN` returns, which is a public API change for every existing consumer lifter. The migration is mechanical (replace `DSL.row(...)` with a `RecordN`-producing builder in lifter implementations) but can't ship as part of R61 without coordinating consumer updates.
- **`RowKeyed` in `RecordParentBatchKey` face with `JavaRecordType` / `PojoResultType` parent.** The `buildFkRowKey` arm reads scalar values via typed Java getters (no jOOQ `Record` in scope at that emit site). Constructing a `RecordN` from scalars requires either a `DSLContext` round-trip (`DSL.using(...).newRecord(table.col).with(table.col, val)`) or a synthetic empty-record builder; both add runtime cost and emission complexity that wasn't worth paying without a fixture exercising the combo. R61 ships a classifier rejection for this combo pointing at this slug; this item lifts the rejection by picking a construction strategy.

## Suggested decision points (resolve in Spec)

1. **`LifterRowKeyed` migration shape.** Three options: (a) require `RecordN` returns and reject `RowN`; (b) accept either and branch `keyElementType()` per-lifter; (c) accept either at the validator and emit a generated wrapper that promotes `RowN` to `RecordN`. Option (a) is simplest but breaks every consumer lifter on upgrade. Option (b) preserves the asymmetry permanently. Option (c) needs a synthetic builder that isn't free.
2. **`JavaRecordType` / `PojoResultType` `RowKeyed` construction.** Two options: (a) inject a `DSLContext` at the emit site (the rows-method already has `graphitronContext(env).getDslContext(env)` available — extend to the parent-fetcher key extraction); (b) ship a runtime helper that builds a `RecordN` from scalar values + `Field<?>` references without a `DSLContext`. (a) adds a per-dispatch DSLContext lookup; (b) requires runtime support code outside the generator's path of least resistance.

## Acceptance criteria

- All seven `BatchKey` variants return `RecordN<...>` from `keyElementType()`; `javaTypeName()` is uniformly `Record`-shaped.
- Validators surface clean migration errors for any consumer lifter / parent combo that needs source change.
- Existing `@batchKeyLifter` fixtures (`TestLifterStub.java`) updated to whichever post-decision shape ships.
- The classifier rejection R61 introduced for the `JavaRecordType` / `PojoResultType` `RowKeyed` combo is removed.

## Roadmap entries (siblings / dependencies)

- **Depends on** [R61 / `emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md). The four-arm flip lands the bulk of the implementation surface; this item closes the remaining asymmetry.

