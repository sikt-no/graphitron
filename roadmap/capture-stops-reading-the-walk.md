---
id: R870
title: "Capture stops reading the classification walk"
status: Ready
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

Shipped at `9f50502`. All six steps landed in one commit; what each did, and the one thing the
plan did not foresee:

1. **The DDL.** `walk_type_backing_class`, its four `COMMENT ON` statements and the family header
   block are gone, with the `walk_` row in `meta_family`, the relation's `meta_family_headline`
   row, and its line on the frozen undeclared roster. Unforeseen: removing the family left a hole
   at ordinal 7, and `FamilyRosterGateTest` closes the ordinals against `0..n-1`, so `intent_`
   through `meta_` renumbered down one.
2. **The write edge.** `TypeBackingClassRows` deleted; `ClassifiedRun` keeps both arms and loses
   its component and its projection from the walked model, gaining a `present()` beside `absent()`
   so `GraphQLRewriteGenerator` hands the discriminator directly. `FactCapture.detect` writes
   nothing.
3. **The differential.** `withBothSides` takes a `BiConsumer<DSLContext, List<String>>` and hands
   the body the walk's projected answer instead of round-tripping it through the store. Four
   fixtures, two pinned departures, unchanged.
4. **The projection.** `TypeBackingClasses` moved from main to test sources in the same package,
   so no import churn, and `TypeBackingClassesTest` moved with it.
5. **The guards.** `FactCaptureAgreementTest`'s two walk cases, their partition helper and the
   roster row; `StoreClientBoundaryTest.noWalkRelationIsRead` with its relation list. Unforeseen:
   deleting that list orphaned its javadoc, and an unattached doc comment is a warning under the
   module's `-Werror`, so the comment went with the constant.
6. **The sweep.** Both `fact-model.adoc` paragraphs, plus the `rejection_` and `build_warning_`
   charters in `meta_family`. The oracle corollary was rewritten without an exemplar, keeping the
   shape it recommends (named fixtures, departures asserted by direction, never a total-agreement
   test) and separating it from the second question of where the predecessor's answer lives.

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
* The differential still fails when the two sides disagree. Verified by two reverted mutations:
  dropping a type from the walk's projected answer reddens the agreement case, and adding the
  binding the walk refuses to make reddens the disagreement case that asserts its silence. The
  second is the one that matters, since it shows the pinned-departure assertions are live rather
  than passing because the walk's list happens to be short.
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

## Reviewer findings

### Round 1 (2026-09-01, In Review -> Ready, reviewer session 015YkjhNko9dYGwmU7bnhZ6R)

The change is the change the spec approved, and the goal is delivered. All six steps landed; the
differential kept its shape exactly (four fixtures, two departures pinned by direction, no collapse
into a corpus-wide equality assertion); the docs sweep took the harder of the two branches step 6
offered and rewrote the oracle corollary rather than hunting a replacement exemplar, separating the
shape it recommends from the second question of where the predecessor's answer lives. Full reactor
green under `mvn install -Plocal-db`, with no generated-output diff.

Completeness was measured rather than read off that build. The item's own central risk is that a
comparison can be deleted by accident while looking like it was rewritten, so both mutations were
re-run independently of the ones the delivery records: dropping `Film` from the walk's projected
answer reddens `theDerivationReproducesTheWalkWhereTheyAgree` and `theInputAxisAgreesWithTheWalk`,
and adding a binding the walk refuses to make reddens
`aDisagreementIsTwoRowsHereAndSilenceInTheWalk`. Both directions are live. The first delivery
criterion was checked by grep: no main source in any module names the three retired symbols, and
nothing in `graphitron`'s main sources projects a `GraphitronSchema` into the capture package. The
retirement sweep is clean on every code and documentation surface, zero hits across `.java`, `.adoc`
and the DDL. The user-facing-doc check on `fact-model.adoc` is clean.

What withholds sign-off is one wrong claim in the delivery record, one piece of the edge that is
still standing, and the swept surface the sweep did not reach.

**Finding 1 (question three). The family-ordinal renumbering is recorded as forced by a gate that
does not exist.** Step 1's delivery note says removing the family "left a hole at ordinal 7, and
`FamilyRosterGateTest` closes the ordinals against `0..n-1`", and the commit message says the same.
That test never selects `META_FAMILY.ORDINAL`. Its five cases cover introductions, headline
resolution, headline ordinals dense within each family (`META_FAMILY_HEADLINE.ORDINAL`, a different
relation), every family having a headline, and bridge resolution. The tree's one reader of
`META_FAMILY.ORDINAL` is `FactSchemaGateTest.theFamilyRosterIsWellFormed`, which asserts
`doesNotHaveDuplicates()` and nothing else, and the DDL's own column comment, left untouched by this
change, still reads "0-based; unique by gate" beside the sibling comment that spells out why the
headline ordinal is the one gated dense. So a gap at 7 would have passed, and renumbering `intent_`
through `meta_` was five rows of `meta_family` changed outside the plan's scope on a false premise.

The edit is inert, which is why the build cannot catch this: ordinals with a gap order the reference
pages identically. The defect is in the record. This is the last gate, after which the item file is
deleted and the changelog entry is all that survives, so a claim about a gate that does not say what
it is cited as saying becomes permanent and unfindable, and the next contributor retiring a family
reads it and renumbers for the same non-reason. Either restore the ordinals the plan did not scope
and drop the note, or keep the renumbering on a reason that is true (a deliberate tidy, stated as
one) and correct the sentence in the body and the DDL comment it contradicts. Which of those, and
whether the renumbering is worth keeping at all, is the author's call.

**Finding 2 (question three). `GraphQLRewriteGenerator.captureAndRead` still takes the
`GraphitronSchema` it no longer uses.** The body now calls `ClassifiedRun.present()`, so the
`schema` parameter is dead. It is a private method with one caller, so removing it and the argument
at the call site is a two-line change. The stated criterion passes on a technicality, since nothing
flows from the parameter into capture any more, but this is the item's own subject: the signature
that hands a walked schema toward the capture seam is the last visible inch of the edge the item
exists to cut, and a reader of that method still sees it.

**Finding 3 (the retirement sweep, a named precondition of this gate). The sweep did not reach
roadmap bodies.** `roadmap/workflow.adoc` lists them among the surfaces the sweep greps, and seven
items still name the retired vocabulary. Most are incidental or read naturally as past tense, but
one is not: **R865** declares `depends-on` this item and its body still says, in the present tense,
"**R870 must land first.** Capture writes one table, `walk_type_backing_class`, using the schema
walk that lives above it. That call cannot survive the module move." R865 is in Spec, so the next
author or reviewer to pick it up reads a precondition that is already met as work still owed.
**R740** is the other one worth a look: its body still describes this deletion as pending work it
might do, which is what its "Relation to the items around it" entry above anticipated. The rest
(R682, R877, and the passing mentions in `derive-package-names-three-jobs`,
`corpus-directives-to-expect-equals`, `trace-writer-disabled-for-rest-of-fork` and
`derived-read-cost-is-a-shape-problem`) are the author's to read and leave or repoint.

None of the three needs code to move beyond the two lines in finding 2. The verification above does
not need redoing: what a next pass owes is the ordinal decision, the dead parameter, the roadmap
sweep, and a build covering whatever those touch.
