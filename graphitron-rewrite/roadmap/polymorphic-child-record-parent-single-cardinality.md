---
id: R367
title: "Single-cardinality polymorphic child on a record-backed parent (resolve the dangling deferred-rejection doc)"
status: Ready
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

## Review feedback (In Review -> Ready, 2026-06-24)

Independent reviewer, In Review -> Done gate. **Rework: the build is red.** The canonical
command `mvn -f graphitron-rewrite/pom.xml install -Plocal-db` fails on a clean DB, and CI has
been failing on every commit since `25794eb8` (R367 -> In Review). Reproduced locally and confirmed
in CI run 28104926051.

**Blocker, the execution test references an address that the seed does not contain.**
`AddressOccupantCarrierSingleCardinalityTest.firstOccupant_recordBackedParentWithNoOccupants_resolvesNull`
(`graphitron-sakila-example/.../AddressOccupantCarrierSingleCardinalityTest.java:102,112`) queries
`addressOccupantCarrier(addressId: 4)`, but `init.sql`
(`graphitron-sakila-db/src/main/resources/init.sql:223-226`) seeds only addresses 1, 2, 3. So
`AddressOccupantCarrierService.byId(4)` fetches nothing and returns a null carrier; the field
resolves to null; and the `assertThat(carrier).containsKey("firstOccupant")` at line 112 fails with
"Expecting actual not to be null". The test passed only in the authoring session because earlier
DML execution tests had created an address_id 4 row in that session's persistent native DB (the row
does not exist on a fresh seed, and is order-dependent in the full suite).

Two things to fix on the next pass:
1. **Seed an occupant-free address.** All three seeded addresses already have a Customer or Staff
   (addr 1: customers Mary, Barbara; addr 2: staff Jon, customers Patricia, Elizabeth; addr 3: staff
   Mike, customer Linda), so there is no existing empty hub to point at. Add an address_id 4 row with
   zero customers/staff to `init.sql` and keep the test on it; that makes the build deterministic.
2. **Make the test actually exercise the empty-stage-1 path it documents.** As written, even when
   address 4 exists, the "no occupants" case it claims to cover (hub present, stage 1 empty ->
   `result.length == 0 ? null` in `buildScalarPerParentFetcher`) is the path that matters; confirm
   the carrier is non-null over an occupant-free hub so the assertion covers the empty-payload arm
   rather than the null-carrier short-circuit.

**Minor (fold in):** the SDL comment on `AddressOccupantCarrier`
(`graphitron-sakila-example/.../schema.graphqls`, "returns AddressOccupantCarrierService.Carrier (a
plain Java record...)") names a nested `.Carrier` type that does not exist; the carrier is the
top-level `AddressOccupantCarrier`.

The production diff itself looks sound: the deferral arm and dangling slug are removed, both
cardinalities route through `derivePolymorphicHubSource`, the nested-class hazard is correctly scoped
out to R370, and the two pipeline-tier classification tests pass (10/10). Only the execution-tier
fixture is broken. Once the seed is fixed and the full `install -Plocal-db` is green, this is ready
for another In Review -> Done pass (reviewer session != implementer session applies again).

## Cross-links

Sibling of R366 (list cardinality of the same field); enables R365 shape (b); shares
`MultiTablePolymorphicEmitter` with R363.
