---
id: R870
title: "The fact tier reads its consumer: capture writes a relation from the classification walk, and the comparison that relation serves never needed a store-side copy"
status: Spec
bucket: architecture
priority: 1
theme: model-cleanup
depends-on: []
created: 2026-08-28
last-updated: 2026-08-28
---

# The fact tier reads its consumer: capture writes a relation from the classification walk, and the comparison that relation serves never needed a store-side copy

`FactCapture.detect` writes `walk_type_backing_class` from the run's `ClassifiedRun`, which the
generator projects from the classification walk. So the pass that fills the fact store reads the
thing above it, and the fact tier depends on its own consumer. R682's destination sentence says each
tier reads only the tier below it; this is the one place in capture where that is false today, and
it is the only edge from the generator into capture that exists at all.

The relation can go rather than be inverted, because the comparison it was built for does not need
it.

## Vocabulary

The **classification walk** is the retiring reflective pass that resolves, among much else, which
Java class backs each SDL type. The **derivation** is `intent_type_backing_class`, the store-native
closure that replaces it. A **differential** is the shape the fact model prescribes for a migration
that needs to be checked against its predecessor: the predecessor writes facts, and the comparison
is between two relations rather than an equality assertion in Java. `walk_type_backing_class` is the
predecessor's half of one such differential.

## Why the relation can go

**It has no production reader.** No view selects from it. Nothing in any module's main sources reads
it. Its whole consumer set is three tests: `TypeBackingShadowTest`, which is the differential; two
cases in `FactCaptureAgreementTest` that pin the writer and its partition lifecycle, and so exist
only because the writer does; and a guard list in `graphitron-mcp`'s `StoreClientBoundaryTest`.

**The differential already computes both sides in the test's own JVM.**
`TypeBackingShadowTest.withBothSides` builds the walk's answer as `TypeBackingClasses.of(bundle.model())`,
writes it into the store, and reads it back three lines later to compare. The store round-trip adds
nothing: comparing that map against the derivation's query pins exactly the property comparing two
relations pins. The relation is storage for a value the comparison is holding anyway.

## The argument on the other side, and what survives it

The `walk_` family header in `graphitron-model.sql` argues for keeping the relation, and the
argument deserves answering rather than out-voting. It says the differential belongs inside the
store "rather than a total-agreement test in Java", because a total agreement test "makes the walk
normative and pins whatever bugs it has as invariants, which is the shape this relation exists to
avoid."

**That warning is correct and this item does not violate it.** What it forbids is asserting that the
derivation equals the walk, which would install the walk as the specification. The comparison keeps
the shape it has: four named fixtures, agreement asserted where the two are meant to agree, and two
cases that assert deliberate *disagreement* and name its direction. A departure stays a pinned
departure. Nothing in this item licenses a corpus-wide equality assertion, and an implementer who
reads "compare in memory" as permission to write one has built the thing the header warns about.

**The claim that does die is the one about reach.** The header's case for store-side storage is that
two relations "diff over any corpus a run touches". Nothing does that. `TypeBackingShadowTest` is
four hand-written schemas, and no other test and no view compares the pair. The capability is paid
for on every capture and never exercised. R865's survey is the sharper version of the same point:
of fifteen store files a real consumer schema left on disk, every one held zero graphs and zero
fields, so on the corpus where a corpus-wide diff would actually pay, the store it would need is the
thing that cannot be produced.

## What execution tests do and do not cover

Worth stating, because it is the tempting reason to believe this is safe and it is the wrong one.
Execution-tier tests cover the **walk's** answer, because the generator still emits from
`RecordBindingResolver`. The generator reads neither `intent_type_backing_class` nor the views over
it; the only main-source reader of the derivation is `graphitron-lsp`. So execution coverage says
nothing about the derivation and nothing about whether the two agree.

That is what the differential is for, and it is why this item rewrites the comparison instead of
dropping it. Agreement between the class an editor names and the class the generated code uses is a
difference a user can see, and after this item exactly one test still pins it.

## What is deleted

* `walk_type_backing_class`, its four `COMMENT ON` statements, its `meta_family_headline` row, and
  its `meta_relation_family` sample row.
* The `walk_` family itself, its `meta_family` row included. R743 drained the family's other
  relations, so this is its last resident and the family has no referent once it goes.
* `TypeBackingClassRows`, the writer, and `TypeBackingClasses`, the value it reifies.
* `ClassifiedRun.of(GraphitronSchema)`, the factory, and the `backingClasses` component of
  `ClassifiedRun.Present`.
* `FactCaptureAgreementTest`'s two walk-binding cases and their helper, plus the relation's row in
  that test's registration roster.
* `StoreClientBoundaryTest.noWalkRelationIsRead`, whose relation list becomes empty. A guard over an
  empty list passes for the wrong reason, so the arm goes rather than being left standing.

## What is deliberately not deleted

**`ClassifiedRun` survives.** It is not the walk-write carrier it looks like from the outside. It is
the run-mode discriminator: its `Absent` arm means a stage refused the document, and `detect` runs no
detections at all on that arm. Deleting the type would delete that behaviour. What goes is its
component and its projection from the walked model, which is the actual coupling;
`GraphQLRewriteGenerator` then hands the discriminator directly instead of projecting it from a
`GraphitronSchema`. Whether the emptied type keeps its name is an implementation call, not a
decision this item makes.

**`CatalogBuilder.projectTypesByName` and `TypeBackingShape` survive.** They read the walked model
and retire with it under R682, but they have live callers in `TypeBackingProjectionTest` and in
`graphitron-lsp`'s `R157PipelineTest`, and nothing in this item touches them.

## The retirement sweep this owes

The relation is cited as an exemplar in two places that would otherwise be left naming a dead thing.

**`fact-model.adoc` names `walk_` as the shipped case of the sanctioned oracle shape,** in the
corollary that says fidelity to a predecessor is evidence rather than specification, "with its
removal criterion in its own family header." Deleting the family retires the page's only worked
example of the shape it is recommending. The paragraph needs either a replacement exemplar or a
rewrite that states the shape without one, and that rewrite belongs in this item rather than being
left for whoever notices.

**The same page's "No stratum, scaffolding" bucket pairs `walk_` with `rejection_`** and ends "the
charter of `rejection_` already ties its own lifetime to the clock `walk_` keeps." After this item
that clock does not exist. Both the page and `rejection_`'s own DDL charter need repointing at the
walk itself, which is what the clock was always about.

## Retired vocabulary

`walk_type_backing_class`, the `walk_` family, `TypeBackingClasses`, `TypeBackingClassRows`, and the
phrase "walk-side write" for the edge they formed. The words "shadow" and "oracle" are *not* retired
here: the differential survives, and R740 owns the rename of `TypeBackingShadowTest` and the rest of
that test's cleanup.

## Relation to the items around it

**R864** named this deletion as the edge its module move must cut first. It is split out because it
needs none of that move: no module changes, no dependency changes, and the deletion stands on its
own reasoning rather than on where capture ends up living. It is also the piece of R864 that closes
the defect R864 is named after, since a module boundary constrains import direction and this is a
write.

**R865** planned a seam separating `TypeBackingClassRows.write` from the detections inside `detect`,
so a capture-only run could have the write without the detections. That seam disappears here rather
than changing: with the write gone, `detect` is detections-only and there is nothing to separate.
R865 should check this item's state before starting that seam.

**R740** reaches the same conclusion from the other end, and owns what is left of
`TypeBackingShadowTest` afterwards: the rename away from "shadow" and the symmetric assertion. This
item takes only what the back-edge forces and does not wait on it.

## How we will know it is delivered

* No main source in any module names `walk_type_backing_class` or the two feeder classes, and
  `FactCapture` has no import from `no.sikt.graphitron.rewrite.derive` that reads the walked model.
  Nothing in `graphitron`'s main sources projects a `GraphitronSchema` into capture.
* The differential still fails when the two sides disagree. Reintroduce a known departure, one of
  the two the test already names, and watch the rewritten comparison go red. This is the item's
  central risk: a comparison can be deleted by accident while looking like it was rewritten, and
  only running it against a real disagreement tells the two apart.
* The comparison is still four cases and still asserts two deliberate disagreements. A diff showing
  the fixtures collapsed into one corpus-wide equality assertion is the failure mode the DDL header
  warned about, and it fails this item whether or not it is green.
* `FactSchemaGateTest` and the family roster gates pass with the `walk_` family absent, which is
  what says the DDL, the two `meta_` rosters and the generated reference page came out together.
* `fact-model.adoc` cites no retired family, and `rejection_`'s charter names the walk rather than a
  family that no longer exists.
* The full verification build is green with no generated-output diff in `graphitron-sakila-example`.
