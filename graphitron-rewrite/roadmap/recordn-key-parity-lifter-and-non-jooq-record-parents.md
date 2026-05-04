---
id: R71
title: "@batchKeyLifter Record return-type symmetry"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: [emit-record1-keys-instead-of-row1]
---

# @batchKeyLifter Record return-type symmetry

[R61 (`emit-record1-keys-instead-of-row1.md`)](emit-record1-keys-instead-of-row1.md) added `RecordN<...>` source-shape support alongside the pre-existing `RowN<...>` shape on the `@service` classifier path: developers freely choose either at the source declaration. The only consumer-supplied surface left without that symmetry is `@batchKeyLifter`, where `BatchKeyLifterDirectiveResolver` still pins the lifter method's return type to `org.jooq.Row1..Row22` (`BatchKeyLifterDirectiveResolver.java:266-273`); a `Record1..Record22` return is rejected today. This item brings the lifter API to the same Row-or-Record symmetry the source-shape path already has.

`BatchKey.LifterRowKeyed.keyElementType()` is the only arm that uses the lifter's declared return type as its key element type, so flipping the validator to accept `RecordN` returns requires either pinning the variant to one shape (forcing migration) or branching `keyElementType()` per-lifter so both shapes coexist.

## Suggested decision points (resolve in Spec)

**`@batchKeyLifter` migration shape.** Three options:
1. **Accept either, branch `keyElementType()` per-lifter.** Mirrors the source-shape symmetry on the `@service` path: the lifter's declared return type (`RowN` vs `RecordN`) sets the variant's `keyElementType()`. Lifter implementations using `DSL.row(value, ...)` keep producing `RowN` keys; lifters that switch to a `RecordN`-producing builder gain `value<N>()` access on the key. No migration forced.
2. **Require `RecordN` returns and reject `RowN`.** Simplest model but breaks every existing consumer lifter on upgrade.
3. **Accept either at the validator and emit a generated wrapper that promotes `RowN` to `RecordN`** uniformly inside the framework. Removes the per-lifter branch but introduces a synthetic wrapper that isn't free.

Option 1 most directly mirrors what R61 ships on the source-shape path and avoids forcing migration; carry that forward as the default in Spec unless a different consideration surfaces.

## Acceptance criteria

- `BatchKeyLifterDirectiveResolver` accepts both `Row1..Row22` and `Record1..Record22` lifter return types (subject to the chosen migration option); the rejection message is updated accordingly.
- `BatchKey.LifterRowKeyed.keyElementType()` reflects the lifter's declared shape (under option 1) or the post-migration shape (under option 2/3).
- Existing `@batchKeyLifter` fixtures (`TestLifterStub.java`) updated to whichever post-decision shape ships, with new fixtures covering the alternative shape if option 1 lands.
- L4 pipeline assertions on rows-method signatures and key extraction track the chosen shape.

## Roadmap entries (siblings / dependencies)

- **Depends on** [R61 / `emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md). R61 lands the source-shape symmetry; this item brings the lifter API into the same shape-agnostic posture.
