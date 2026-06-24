---
id: R367
title: "Single-cardinality polymorphic child on a record-backed parent (resolve the dangling deferred-rejection doc)"
status: Spec
bucket: feature
priority: 6
theme: interface-union
depends-on: []
created: 2026-06-24
last-updated: 2026-06-24
---

# Single-cardinality polymorphic child on a record-backed parent (resolve the dangling deferred-rejection doc)

## Problem

A single-cardinality polymorphic child field on a record-backed (Pojo / JavaRecord) parent is rejected
at codegen as "not yet supported", and the rejection points at a roadmap doc that did not exist. This
item closes the capability gap and resolves the dangling pointer. Two related things, one slug:

1. **The capability gap.** A single-cardinality polymorphic child field on a record-backed (Pojo /
   JavaRecord) parent is rejected at codegen with a deferred-rejection:
   `single-cardinality polymorphic child field '<f>' on a record-backed (Pojo / JavaRecord) parent is
   not yet supported; the single-cardinality multi-table polymorphic fetcher reads parent context as a
   jOOQ Record and has no Pojo arm ...` (`FieldBuilder.java:5285-5295`, the `!fieldIsList` arm). The
   list cardinality of the same field routes through `buildBatchedListFetcher` (which has a
   record-parent path, though with its own MANY-dispatch bug, see R366); the single cardinality has no
   record-parent arm at all.
2. **The dangling pointer.** That rejection is produced via
   `Rejection.deferred(..., planSlug: "polymorphic-child-record-parent-single-cardinality")`
   (`FieldBuilder.java:5295`), and `Rejection.java:350-352` renders it as
   "see graphitron-rewrite/roadmap/polymorphic-child-record-parent-single-cardinality.md", a file that
   did not exist. The generator's own diagnostic was a dead link. Creating this item (at exactly that
   slug) resolves the dangling pointer; the code work below closes the capability gap.

## Mechanism

`MultiTablePolymorphicEmitter.buildScalarPerParentFetcher`, the single-cardinality fetcher, reads
parent context as a jOOQ `Record` and has no Pojo / record arm, so `FieldBuilder` defers rather than
emit against a record parent.

## Plan

The reject site spells out the fix (`FieldBuilder.java:5292-5295`): "widen
`MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` to consume parentKey + parentResultType
analogously to the list arm."

1. **Give the single-cardinality fetcher a record-parent arm.** `buildScalarPerParentFetcher`
   (`MultiTablePolymorphicEmitter.java:331`) reads parent context as
   `Record parentRecord = (Record) env.getSource()` and ignores `parentKey` / `parentResultType`, so a
   Pojo / JavaRecord parent would `ClassCastException`. Thread `parentSourceKey` + `parentResultType`
   into it (as `buildBatchedListFetcher` already receives them) and extract the key via
   `GeneratorUtils.buildRecordParentKeyExtraction` for the `ONE`-cardinality readers
   (`buildFkRowKey`, `buildAccessorKeySingle`, ...) instead of casting the source to `Record`.
2. **Remove the deferred-rejection arm.** Once the emitter handles the record parent, delete the
   `!fieldIsList` reject in `FieldBuilder` (`FieldBuilder.java:5284-5296`) so the classifier produces
   the now-supported shape; the `derivePolymorphicHubSource` call the list arm already uses
   (line 5297) becomes the single-cardinality path too.
3. **The dangling pointer is resolved by this file existing** (slug
   `polymorphic-child-record-parent-single-cardinality`), so the generator's "see
   graphitron-rewrite/roadmap/...md" link now lands. No message change needed; when the item ships and
   the file is deleted, swap the `Rejection.deferred` slug for a changelog reference at that time.

Coordinate with R366 (shared `GeneratorUtils` record-parent key-extraction path). Re-bucket to `bug`
if 9.3 parity confirms this shape worked there.

## Feature-equivalence flag

Treated as a feature gap because the generator explicitly defers it, but if this single-cardinality
shape worked in graphitron 9.3 (as the polymorphic `@service` return shape in R365 did), it is a
regression under the feature-equivalence goal and should be re-bucketed `bug`. Check 9.3 parity at
Spec.

## Cross-links

Sibling of R366 (list cardinality of the same field); enables R365 shape (b); shares
`MultiTablePolymorphicEmitter` with R363.
