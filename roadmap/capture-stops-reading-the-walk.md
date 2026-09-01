---
id: R870
title: "Capture stops reading the classification walk"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-09-01
---

# Capture stops reading the classification walk

## Goal

Capture writes nothing that comes from the code above it.

One relation breaks that today. `walk_type_backing_class` records, per SDL type, the Java class that
the *classification walk* resolved as that type's backing, the walk being the retiring reflective
pass the generator still emits from. `FactCapture.detect` writes those rows from a value the
generator hands down, so the pass that fills the fact store reads its own consumer. R682's
destination sentence says each tier reads only the tier below it. This is the one place in capture
where that is false, and the only edge from the generator into capture that exists at all.

The relation exists to serve one comparison. `intent_type_backing_class` is the store-native closure
replacing the walk, and it is checked against the walk's answer by a *differential*: the predecessor
writes facts, and the check compares two relations instead of asserting equality in Java. That
comparison does not need a stored copy of the walk's answer, because it already holds that answer in
memory at the moment it runs.

So the goal is to delete the relation rather than invert the edge. When this item is done:

* No main source in any module projects a `GraphitronSchema` into capture, and `FactCapture.detect`
  is detections-only.
* The `walk_` family is gone. R743 drained its other relations, so this is the last resident and the
  name has no referent once it goes.
* The backing differential still runs, still over its four fixtures, and still pins two deliberate
  disagreements by direction. It compares the projected map against the derivation's query in one
  JVM, with no store round-trip.

That third bullet is part of the goal, not a detail of the plan. What this item removes is a stored
copy, never the check.

## Plan

**1. Delete the relation and its family from `graphitron-model.sql`.** The `CREATE TABLE`, its four
`COMMENT ON` statements, the family header comment above it, the `walk_` row in `meta_family`, and
the `walk_type_backing_class` row in `meta_family_headline`. Also its line in
`graphitron-model/src/test/resources/no/sikt/graphitron/model/undeclared-relations.txt`:
`MetaDeclarationGateTest` closes that frozen roster against the observed relations in both
directions, so a retired relation's leftover line fails the gate. `meta_relation_family` needs no
edit, deriving its rows from the engine's catalog by prefix match.

**2. Cut the write edge in capture.** `TypeBackingClassRows` goes, having nothing left to write.
`ClassifiedRun.of(GraphitronSchema)` and the `backingClasses` component of `ClassifiedRun.Present`
go with it, so `GraphQLRewriteGenerator` hands the discriminator directly instead of projecting it
from a schema. `FactCapture.detect` loses its write and becomes detections-only, and the javadoc on
`detect` and on `GraphQLRewriteGenerator`'s capture entry point stop describing a walk-side write.

**3. Rewrite the differential in the test's own JVM.** `TypeBackingShadowTest.withBothSides` builds
the walk's answer, writes it to the store, and reads it back three lines later. Drop the round-trip
and compare the projected map against the derivation's query directly. The comparison keeps its
shape exactly: four named fixtures, agreement asserted where the two are meant to agree, two cases
asserting deliberate disagreement and naming its direction. Reading "compare in memory" as licence
for one corpus-wide equality assertion fails this item whether or not it is green.

**4. Move the walk-answer projection to test sources, with its test.** The reduction inside
`TypeBackingClasses`, the one over `CatalogBuilder.projectTypesByName` that keeps the class arms and
drops the table and unbacked arms, is what step 3 builds the walk's side with, so it survives the
deletion of its main-source home and lands beside its one remaining caller. `TypeBackingClassesTest`
moves with it: it pins the projection's reach against a walked model, and a silent emptiness there
would make the rewritten comparison pass vacuously. Whether the reduction becomes a private helper
on the comparison or a small test-source class, and whether it keeps its name, are implementation
calls.

**5. Drop the guards the relation leaves behind.** `FactCaptureAgreementTest`'s two walk-binding
cases, their helper, and the relation's row in that test's registration roster; the `ORACLE` arm
keeps nine other residents, so it stays. `StoreClientBoundaryTest.noWalkRelationIsRead` goes
outright rather than being left standing over an empty relation list, which would pass for the wrong
reason.

**6. Sweep the three places that cite the family.** Two are in
`docs/architecture/explanation/fact-model.adoc`: the oracle corollary, which names `walk_` as the
shipped case of the shape it recommends and so needs a replacement exemplar or a rewrite that states
the shape without one; and the "No stratum, scaffolding" bucket, which pairs `walk_` with
`rejection_` and ends on "the clock `walk_` keeps", a clock that will not exist. `rejection_`'s own
DDL charter carries the same clock and repoints the same way, at the walk itself. The third is
`build_warning_`'s charter in `meta_family`, which defends its name by contrast with `walk_`; it
repoints at the walk or the clause goes. R876's landed slice 5 amended the lever order on the same
`fact-model.adoc` page without touching these paragraphs; read that edit before writing this one
rather than assuming a clean merge.

## What this deliberately leaves alone

**`ClassifiedRun` the type.** It is the run-mode discriminator, not the walk-write carrier it looks
like from outside: its `Absent` arm means a stage refused the document, and `detect` runs no
detections on that arm. Step 2 empties it, not deletes it. Whether the emptied type keeps its name is
an implementation call.

**`CatalogBuilder.projectTypesByName` and `TypeBackingShape`.** They read the walked model and retire
with it under R682, but they have live callers in `TypeBackingProjectionTest` and in
`graphitron-lsp`'s `R157PipelineTest`.

**`TypeBackingShadowTest`'s name and its asymmetric assertions.** R740 owns the rename away from
"shadow" and the rest of that test's cleanup. This item takes only what cutting the back-edge forces.

## How we will know it is delivered

* No main source in any module names `walk_type_backing_class`, `TypeBackingClasses` or
  `TypeBackingClassRows`, and nothing in `graphitron`'s main sources projects a `GraphitronSchema`
  into capture.
* The differential still fails when the two sides disagree. Reintroduce a known departure, one of the
  two the test already names, and watch the rewritten comparison go red. This is the item's central
  risk: a comparison can be deleted by accident while looking like it was rewritten, and only running
  it against a real disagreement tells the two apart.
* The comparison is still four cases and still asserts two deliberate disagreements. A diff showing
  the fixtures collapsed into one corpus-wide equality assertion fails this item whether or not it is
  green.
* `TypeBackingClassesTest`'s cases pass from test sources, against the same walked models, with no
  case dropped in the move.
* `FactSchemaGateTest`, `MetaDeclarationGateTest` and the family roster gates pass with the `walk_`
  family absent, which is what says the DDL, the two `meta_` rosters, the frozen undeclared roster
  and the generated reference page came out together.
* `fact-model.adoc` cites no retired family, and neither the `rejection_` charter nor the
  `build_warning_` charter names a family that no longer exists.
* The full verification build is green with no generated-output diff in `graphitron-sakila-example`.

## Retired vocabulary

`walk_type_backing_class`, the `walk_` family, `TypeBackingClassRows`, and the phrase "walk-side
write" for the edge they formed. The words "shadow" and "oracle" are not retired here: the
differential survives, and R740 owns the rename of `TypeBackingShadowTest`.

## Relation to the items around it

**R865** names this deletion as the edge its module move must cut first, and depends on it. This item
is split out because it needs none of that move: no module changes, no dependency changes, and the
deletion stands on its own reasoning rather than on where capture ends up living. It also closes the
write-direction half of the defect R865 is named after, which a module boundary cannot, since a
boundary constrains import direction and this is a write.

The capture-only goal R865 adds is the other reason the order matters. That item would otherwise owe
a seam separating `TypeBackingClassRows.write` from the detections inside `detect`, so a capture-only
run could have the write without the detections. The seam disappears here rather than changing: with
the write gone, `detect` is detections-only and there is nothing to separate.

**R740** reaches the same conclusion from the other end and owns what is left of
`TypeBackingShadowTest` afterwards. This item does not wait on it.

**R877** is working `undeclared-relations.txt` family by family. If it reaches the `walk_` family
first, step 1 collides with it on that file.

## Other solutions we've considered

**Invert the edge instead of deleting it: have the derivation or a store-native pass write the
relation, so capture no longer reads upward.** Rejected because it preserves storage nothing needs.
The relation has no production reader: no view selects from it, and no main source in any module
reads it. Its only consumers are the differential, two `FactCaptureAgreementTest` cases that exist
because the writer does, and one `graphitron-mcp` guard list.

**Keep the relation for its reach: two stored relations diff over any corpus a run touches, which an
in-memory comparison cannot.** This is the `walk_` family header's own argument and it is the
strongest one available. It fails on exercise rather than on principle. `TypeBackingShadowTest` is
four hand-written schemas, and no other test and no view compares the pair. The capability has had
its opportunity: a consumer-scale capture exists and is the instrument the 2026-08-28
derived-read-cost audit works from, and no walk-against-derivation diff has been run over it. A
differential nobody runs at the one scale that would make it informative is storage, not an
instrument.

**Delete the comparison along with the relation, on the grounds that execution tests already cover
this.** They do not, and this is the tempting wrong reason to believe the deletion is safe.
Execution-tier tests cover the *walk's* answer, because the generator still emits from
`RecordBindingResolver`. The generator reads neither `intent_type_backing_class` nor the views over
it; its main-source readers are the two tool modules, `graphitron-lsp` and `graphitron-mcp`, the
latter through `intent_type_backing` in `SchemaQueries`, and neither sits on a path an execution-tier
test exercises. Agreement between the class an editor names and the class the generated code uses is
a difference a user can see, and after this item exactly one test pins it.

**Replace the differential with a total-agreement test in Java.** Refused, and the family header
names the reason: it would make the walk normative and pin whatever bugs it has as invariants. Step 3
keeps the pinned-departure shape precisely so this stays refused.
