---
id: R367
title: "Single-cardinality polymorphic child on a record-backed parent (resolve the dangling deferred-rejection doc)"
status: In Progress
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

The reject site spelled out the fix (`FieldBuilder.java:5292-5295`): "widen
`MultiTablePolymorphicEmitter.buildScalarPerParentFetcher` to consume parentKey + parentResultType
analogously to the list arm."

1. **Give the single-cardinality fetcher a record-parent arm.** Shipped.
   `buildScalarPerParentFetcher` now takes `parentSourceKey` and, when the
   parent is record-backed (`Reader.AccessorCall`), binds `parentRecord` to the accessor's returned
   hub `TableRecord` (`((Backing) env.getSource()).<accessor>()`) instead of casting the source to a
   jOOQ `Record`. A null accessor return yields a null payload. The table-backed arm keeps the
   `(Record) env.getSource()` cast. The hub record exposes the FK columns `branchParentFkWhere`
   already reads by name, so the single-cardinality correlation needed no change beyond the parent
   binding (simpler than the list arm's `VALUES`-join, which exists only to batch).
2. **Remove the deferred-rejection arm.** Shipped. The `!fieldIsList` reject in `FieldBuilder` is
   gone; both cardinalities route through `derivePolymorphicHubSource`.
3. **The dangling pointer is resolved.** The `Rejection.deferred(..., planSlug:
   "polymorphic-child-record-parent-single-cardinality")` call was the only reference to the slug and
   was removed with the reject arm, so there is no remaining link to swap for a changelog reference on
   Done.

Tests: the two pipeline-tier deferral assertions
(`RecordParentMultiTablePolymorphicPipelineTest.child{Interface,Union}Field_recordParent_accessorKeyedSingle`)
now assert successful `AccessorKeyedSingle` classification; an execution-tier test
(`AddressOccupantCarrierSingleCardinalityTest`) drives a Pojo carrier holding an `AddressRecord` hub
through `Query.addressOccupantCarrier` and pins that `firstOccupant` resolves the first
`Customer|Staff` by sort order (Staff for a populated address) and null for a hub with no occupants.

Scope: end-to-end support is for **top-level** backing classes (the execution fixture
`AddressOccupantCarrier` is top-level). A *nested* backing class still classifies but emits a
non-compiling `Outer$Nested` cast; that is a pre-existing hazard shared with the list arm (both build
the `AccessorRef` from a binary `fqClassName` via `ClassName.bestGuess`), tracked separately as R370,
not introduced here.

## Feature-equivalence flag

Kept `feature` (not re-bucketed `bug`). The multi-table polymorphic interface/union machinery is
rewrite-era; the generator explicitly deferred this shape rather than regressing a behavior 9.3 had,
so this is a genuine capability gap, not a parity regression. (Contrast R365, whose polymorphic
`@service` return shape did exist in 9.3.) The Spec reviewer signed the item off as `feature`.

## Cross-links

Sibling of R366 (list cardinality of the same field); enables R365 shape (b); shares
`MultiTablePolymorphicEmitter` with R363.
