---
id: R71
title: "@batchKeyLifter Record return-type symmetry"
status: Backlog
bucket: architecture
priority: 7
theme: service
depends-on: [decompose-sourcekey]
last-updated: 2026-07-13
---

# @batchKeyLifter Record return-type symmetry

(Mechanism section re-anchored 2026-07-13: the original write-up named `BatchKeyLifterDirectiveResolver` and a `BatchKey.LifterRowKeyed` permit, both long deleted. The substance below is unchanged and re-verified; the live surface is `LifterRef` / `SourceKey.Reader.SourceRowsCall` / `Wrap`.)

R61 (shipped, see the changelog) added `RecordN<...>` source-shape support alongside the pre-existing `RowN<...>` shape on the `@service` classifier path: developers freely choose either at the source declaration, and variant identity tracks shape. The only consumer-supplied surface left without that symmetry is the batch-key lifter, where a `Record1..Record22` return is still rejected today. This item brings the lifter API to the same Row-or-Record symmetry the source-shape path already has.

## The live Row-only asymmetry

Two coupled sites pin the lifter to `RowN`:

- `SourceRowDirectiveResolver` (`SourceRowDirectiveResolver.java:239-255`) validates the lifter method's return type against `org.jooq.Row1..Row22` and rejects anything else, including `RecordN`.
- `SourceKey`'s compact constructor (`model/SourceKey.java:124-128`) pins `Reader.SourceRowsCall -> Wrap.Row` and throws otherwise, with the message "lifter contract pins output to RowN<...>". The sibling pin `Reader.AccessorCall -> Wrap.Record` shows the shape channel already exists; the lifter arm just never got the Record side.

The classified carrier is `SourceKey.Reader.SourceRowsCall(LifterRef)` (`model/SourceKey.java:288`), produced from `@sourceRow`'s lifter resolution (see the `@sourceRow`/`@tableMethod` complementarity note around `FieldBuilder.java:5602`).

## Shape of the fix

The design question the original write-up framed as "split `LifterRowKeyed` into per-shape permits" maps onto today's model as: let `SourceRowsCall` construct with the `Wrap` matching the lifter's declared return shape (`Wrap.Row` for `RowN`, `Wrap.Record` for `RecordN`), selected once at classify time, and relax the `SourceRowDirectiveResolver` gate to accept both families. That preserves the principle the R61/R74/R70 trio converged on (variant identity carries the shape contract; downstream switches fork on typed identity, not a discriminator field): `Wrap` is already the sealed shape carrier, so no new permits are needed on the reader side unless emit sites turn out to re-derive shape.

No migration is forced: existing `Row1..Row22` lifters keep working unchanged.

**Sequencing:** this item rides on `SourceKey`, which R431 (`decompose-sourcekey`) plans to decompose; the `SourceRowsCall -> Wrap.Row` pin is one of the coupled facts R431 will relocate. Do the symmetry work after (or as part of) R431 rather than against the current conflated record; hence the depends-on edge.

## Acceptance criteria

- The lifter resolver accepts both `Row1..Row22` and `Record1..Record22` return types; the rejection message is updated accordingly.
- The `SourceRowsCall`/`Wrap` pairing is selected from the declared return shape at classify time; the compact-constructor pin becomes a both-shapes invariant rather than Row-only.
- Emit sites that read the wrap (rows-method signatures, key extraction) handle both shapes; pipeline assertions track the chosen shape.
- Existing `@sourceRow` lifter fixtures updated; new fixtures cover the `RecordN` shape.
