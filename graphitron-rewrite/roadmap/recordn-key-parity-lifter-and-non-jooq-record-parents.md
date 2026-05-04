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

[R61 (`emit-record1-keys-instead-of-row1.md`)](emit-record1-keys-instead-of-row1.md) added `RecordN<...>` source-shape support alongside the pre-existing `RowN<...>` shape on the `@service` classifier path: developers freely choose either at the source declaration, and **variant identity tracks shape** — `RowKeyed` / `MappedRowKeyed` carry `RowN` keys; `RecordKeyed` / `MappedRecordKeyed` carry `RecordN`. The only consumer-supplied surface left without that symmetry is `@batchKeyLifter`, where `BatchKeyLifterDirectiveResolver` still pins the lifter method's return type to `org.jooq.Row1..Row22` (`BatchKeyLifterDirectiveResolver.java:266-273`); a `Record1..Record22` return is rejected today. This item brings the lifter API to the same Row-or-Record symmetry the source-shape path already has.

`BatchKey.LifterRowKeyed` is the only arm that uses the lifter's declared return type as its key element type. Flipping the validator to accept `RecordN` returns therefore raises a variant-taxonomy question: does `LifterRowKeyed` widen to carry a per-instance shape discriminator, or does the seal split into distinct `LifterRowKeyed` / `LifterRecordKeyed` permits that each declare their shape statically?

R61, R74 ([`accessor-row-record-shapes.md:17`](accessor-row-record-shapes.md)), and R70 ([`service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md)) all decided that question the same way: distinct sealed permits per shape, not enum discriminators or per-instance branching. R74 made the principle explicit by name ("sealed hierarchies over enums for typed information"); R70 applied it on the developer-facing source side. R71 is the third application of the same rule and should follow the same shape rather than reopen the question.

## Suggested decision points (resolve in Spec)

**`@batchKeyLifter` migration shape.** Three options:
1. **Distinct sealed permits per shape (default).** Split `LifterRowKeyed` into `LifterRowKeyed` (RowN return) and `LifterRecordKeyed` (RecordN return). Both permits carry the same `(JoinStep.LiftedHop hop, LifterRef lifter)` data; only `keyElementType()` (`rowNType` vs `recordNType` over `hop.targetColumns()`) and `javaTypeName()` (`"Row"` vs `"Record"`) differ. The `BatchKeyLifterDirectiveResolver` reads the lifter's declared return type once at classify time and constructs the matching permit; downstream `switch(batchKey)` sites fork on variant identity, not on a discriminator field. Mirrors R61's `RowKeyed` / `RecordKeyed` split, R74's `Accessor*` quartet, and R70's new `TableRecordKeyed` / `MappedTableRecordKeyed` permits. No migration forced; existing `Row1..Row22` lifters keep working unchanged.
2. **Require `RecordN` returns and reject `RowN`.** Simplest model but breaks every existing consumer lifter on upgrade.
3. **Accept either at the validator and emit a generated wrapper that promotes `RowN` to `RecordN`** uniformly inside the framework. Removes the variant split but introduces a synthetic wrapper that isn't free.

Option 1 is the principle-aligned default: it's the third application of the variant-identity-tracks-shape rule R61 / R74 / R70 already converged on. Carry it forward in Spec unless a different consideration surfaces.

Note: even though `LifterRowKeyed` and `LifterRecordKeyed` would carry the same data fields, the split is justified on the same grounds R74 cites. Variant identity carries the shape contract end-to-end (so emit-site `switch(batchKey)` sites pattern-match on shape without re-deriving it), and the seal makes the shape exhaustive at every consumer of `BatchKey.RecordParentBatchKey`. This is consistent with how R74's four new accessor permits (Row/Record × Single/Many) also carry identical data shapes but live as distinct permits.

## Acceptance criteria

- `BatchKeyLifterDirectiveResolver` accepts both `Row1..Row22` and `Record1..Record22` lifter return types (subject to the chosen migration option); the rejection message is updated accordingly.
- Under option 1: `BatchKey.RecordParentBatchKey` permits expand to include both `LifterRowKeyed` and `LifterRecordKeyed`; each variant's `keyElementType()` and `javaTypeName()` come out of variant identity rather than a per-instance read of the lifter ref. Under option 2/3: `LifterRowKeyed` widens or migrates per the chosen shape.
- Every `switch(batchKey)` site that currently pattern-matches on `LifterRowKeyed` is walked exhaustively (the seal makes this `javac`-checked); under option 1 each site forks the new arm next to the existing one.
- Existing `@batchKeyLifter` fixtures (`TestLifterStub.java`) updated to whichever post-decision shape ships, with new fixtures covering the alternative shape if option 1 lands.
- L4 pipeline assertions on rows-method signatures and key extraction track the chosen shape.

## Roadmap entries (siblings / dependencies)

- **Depends on** [R61 / `emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md). R61 lands the source-shape symmetry on the `@service` classifier path and establishes variant-identity-tracks-shape as the encoding rule; this item extends the same posture to the consumer-supplied lifter surface.
- **Mirrors design pattern from** [R74 / `accessor-row-record-shapes.md`](accessor-row-record-shapes.md). R74 added four sealed permits (Row/Record × Single/Many) on the auto-lift accessor side rather than folding shapes onto an enum discriminator. Option 1 above is the same move on the lifter side: sibling permits per shape, identical data fields, distinct identity.
- **Mirrors design pattern from** [R70 / `service-rows-tablerecord-key-shape.md`](service-rows-tablerecord-key-shape.md). R70 added `TableRecordKeyed` / `MappedTableRecordKeyed` for the typed-`TableRecord` source shape rather than folding onto `MappedRowKeyed`. Same principle, this time on the developer-facing source side.
