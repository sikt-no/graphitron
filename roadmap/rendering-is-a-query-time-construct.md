---
id: R804
title: "Composed columns are atomic and key-dependent: state the discipline in the explanation articles"
status: Spec
bucket: docs
priority: 5
theme: docs
depends-on: []
created: 2026-08-22
last-updated: 2026-08-23
---

# Composed columns are atomic and key-dependent: state the discipline in the explanation articles

R803 converted the fact schema's serialized-collection columns to rows and landed
`CollectionValuedColumnGateTest`, the gate that denies the constructs in the DDL. That was
the mechanical attack, and it is done. This item is the documentation sibling: state the
principle those changes enforce in the two explanation articles, so the next author who
reaches for `LISTAGG` (or its next of kin: a sentence assembled in SQL, a set serialized
for grouping) has a page that says why, not just a gate to bounce off.

The first framing, "rendering is a query-time construct, not a model construct", proved too
blunt under a principles consult: the store as shipped legitimately holds renders (the
rendered coordinate string, the `_upper` case folds), and the worst offender defended
itself as a grouping key rather than presentation, the retired serialized-directives
column calling itself "the canonical claim render for grouping" in its own comment. The
sharper discriminator is atomicity plus key-dependence, and most of it is already written
on the store's own columns:

* A column may carry an opaque value the store did not compose (a captured message, a
  transcribed docstring), provided nothing joins, groups or filters on it. The DDL
  comments already state this line as "display material, never a dimension"; lift that
  vocabulary rather than minting a new one.
* A column anything joins, groups or filters on must be atomic to the engine and a function
  of its relation's own key. A count or arity passes
  (`intent_type_backing_conflict.candidates`); a collection serialized into a scalar fails,
  because its element grain sits inside the value where no key, constraint or join reaches
  it and only string surgery gets it back. The exemplar pair is that same relation:
  `candidates` stays, and the contesting classes it counts are `intent_type_backing`'s rows
  under the same key, where until R803 a serialized twin sat beside the count. What the
  rows answer and a serialized set cannot is membership: a set in one column answers
  equality of the whole set and nothing else, where the rows answer that and membership
  too, for one join. Both live sites state it in that form already, the relation's own
  comment and `CollectionValuedColumnGateTest`'s javadoc, so the paragraph lifts the
  argument rather than minting it. The concrete wrong answer that argument predicts was
  paid on a different relation and is told as history: the claim-conflict relation's
  retired directives column was a filterable dimension on the MCP diagnostics surface, so
  asking for conflicts involving one directive returned only the conflicts whose entire
  set was exactly that directive; the surface now joins and asks membership. This is also
  what makes the gate's denylist coherent: `ARRAY_AGG` is no delimited string, but it is a
  collection in a scalar.
* A serialized set used as a canonical group key is the case that looks legitimate and is
  not: it answers only equality of the whole set, where the relational form answers that
  and membership too, for one join. Grouping by a set is a query's business; the store owes
  it the rows, not a canonical spelling of them.
* Order is admissible as data (a captured ordinal, a `position` column) and inadmissible as
  a rule copied from a consumer's vocabulary with nothing binding the copy, which is what
  the retired eight-branch `CASE` ladder restating a Java enum's declaration order was.
  R803's own edit to fact-model.adoc already landed this argument where the minted message
  now lives (the provenance section: the naming order "is not a captured fact of any graph
  and so is no view's to express"), so the new paragraph cross-references that sentence
  rather than restating it.

Two citations lift the case past taste, both told in past tense now that R803 closed the
defects: "boundaries decode and encode" (a serialized column is a wire format inside the
interior, and nothing ever decoded it; the joined string travelled intact into a hover)
and the encoding leaking into a consumer's types (in `ClaimFacts`, `contested` was a
`Field<List<String>>` beside `grounded` and `reached`, genuine multiset row lists, but
held a one-element list containing the joined string; it is now one row per contesting
class like its siblings).

## Deliverable 1: the discipline paragraph in fact-model.adoc

One paragraph inside "Derived reads are views, not stored facts", in the opening shape
cluster beside the converse test ("two spellings of one value are two base columns"), not
a new section: the tail of that section is measured cost rules, where a shape rule reads
as a non-sequitur. The paragraph states the two-clause discriminator above, the
`intent_type_backing_conflict` exemplar pair (one exemplar per side, from one relation and
one key), the membership-versus-set-equality argument against that pair, and one
cross-reference to the provenance section's minted-message sentence for the order half.
The concrete wrong answer goes in the same paragraph as one past-tense clause, on the
relation that actually paid it: the claim-conflict relation's retired directives column and
the MCP facet that filtered it. Keep that a clause and not a second exemplar pair. The
exemplar pair is the live one, and the retired column is what the argument predicted,
which is the only weight it has to carry here. Do not enumerate the
sanctioned columns as a roster (an unguarded census); the DDL comments own the per-column
arguments, and seven of them already spell the vocabulary this paragraph lifts ("display
material, never a dimension"). Name only live columns; the removed ones are told as
history, which is the register the page already uses for the retired assignability
closure.

Its enforcer line, written named-and-true: *Enforced by:*
`CollectionValuedColumnGateTest` (graphitron-model) for the named constructs in the DDL's
statement regions; disclosed gap, per that gate's own javadoc: a row-local scalar
expression that discards part of a value trips nothing, because detecting serialization
inside an arbitrary expression is not mechanizable, so this paragraph is the coverage for
that residue. The gap is closable case by case (each such expression is findable in
review) and the removed path-truncation column on `diagnostic` is its exemplar.

## Deliverable 2: two scoping sentences in fact-model.adoc

* In "Name the row, not the question", one forward-linking sentence: a column encoding a
  set means the row asserts something about several things at once, so the one-sentence
  check cannot be finished honestly at the column grain either.
* At the `diagnostic` exemption (the roster's placement exemption in the strata section),
  one sentence scoping it: the exemption covers a relation's name and population and says
  nothing about what a column may hold. The exemplar is one relation, two verdicts:
  `coordinate` passes because its atoms ride the same row (the view's own comment argues
  this), and the path-truncation column failed the same test and was removed. Without the
  sentence the next author reads `diagnostic` as pre-cleared, and the largest offender
  R803 removed sat on exactly that relation.

## Deliverable 3: the sibling half of naming-the-row.adoc's worked example

Extend the existing `intent_bound_table` worked example (the "candidate count is a column
rather than a rule buried inside whichever consumer asked first" punchline) with its
failing sibling, in the page's register and against the shape the reader has just been
taught: a count as a column is the honest fold; the list of the counted things serialized
into a scalar beside it is the dishonest one, because the question an author asks of a
list is membership, and a serialized set can only answer equality of the whole. One short
paragraph, no new example, no roster. If the pantry metaphor stretches naturally (a label
that says how many jars, versus three jars taped together under one label), use it; if it
strains, plain prose.

## Verification

* Prose-only change. The verification build covers the AsciiDoctor render, and the widened
  `AdocXrefAnchorCheck` fails the build on a wrong cross-file xref path or anchor on a page
  authored under `docs/`. Deliverable 1's cross-reference is same-page, which that check
  leaves alone by design (Asciidoctor already reports the same-file forms at INFO), so that
  one reference is the review's to catch rather than the build's.
* No retired column name appears as a live citation; the failing exemplars are past
  tense. Live names used: `intent_type_backing_conflict.candidates`,
  `intent_type_backing`, `diagnostic.coordinate`.
* No mechanical test pins prose content; the Spec → Ready and In Review → Done gates are
  the review. The one build-checked claim is the enforcer line naming a test that exists.

## Not in scope

* development-principles.adoc: its preamble already delegates the store's modeling
  discipline to fact-model.adoc by name, so the rule belongs on the page it delegates to,
  and a second copy here would be the drift smell that same preamble warns about.
* The DDL comments: they already carry the vocabulary and the per-column arguments; this
  item adds the page-level rule they instantiate.
* Any further schema or consumer change; R803 finished those.

(The handoff notes R803's implementer appended here are folded into the deliverables
above: the gate's name and its disclosed gap into Deliverable 1's enforcer line, the
one-relation-two-verdicts exemplar into Deliverable 2, the membership-versus-set-equality
cost into the discriminator bullet, and the `ClaimFacts` past-tense correction into the
citations paragraph.)

## Reviewer findings (Spec → Ready gate, 2026-08-23)

Independent reviewer session, status stays `Spec`. One finding, on question 1: the
spec's claims about code that exists are checkable, and one of them does not hold.
Everything else checked out, and the list of what was verified is in the commit message.

1. **The membership-versus-set-equality cost is attached to the wrong retired column, and
   Deliverable 1 carries that misattribution into the paragraph the implementer writes.**
   The second discriminator bullet says the exemplar pair is `intent_type_backing_conflict`,
   that "until R803 a serialized twin sat beside the count", and then that "the wrong answer
   *that twin* produced" was the MCP diagnostics surface filtering the conflict set with
   exact set equality, "so asking for conflicts involving one directive silently returned
   only the conflicts whose entire set was that directive". Those are two different columns
   on two different relations. The twin beside `candidates` was
   `intent_type_backing_conflict.class_names`, which held *classes*, not directives, and
   which no consumer ever filtered: pre-R803 its only two readers projected it,
   `ClaimFacts` into the LSP hover and `SchemaQueries` into the MCP schema read. The
   set-equality filter was `DIAGNOSTIC.DIRECTIVES`, a filterable `DiagnosticFacets.Dimension`
   over `diagnostic.directives`, whose source column
   `intent_authored_claim_conflict.directives` is the one whose own comment called itself
   "the canonical claim render for grouping" (the comment this spec quotes two paragraphs
   earlier, correctly, against that relation). The bullet is internally inconsistent as it
   stands, a set of classes cannot be filtered by a directive, and the cost as stated is
   simply not what `class_names` did.

   This blocks rather than being a correction the reviewer takes, because Deliverable 1
   requires the paragraph to state both "the `intent_type_backing_conflict` exemplar pair
   (one exemplar per side, from one relation and one key)" *and* "the
   membership-versus-set-equality cost", so an implementer working from the deliverable
   writes the false attribution onto a published page. Which way to resolve it is a
   design choice the author owns, and both ways are open:

   * State the cost abstractly against `intent_type_backing_conflict`, which is how both
     live sites already state it and is one relation and one key as the deliverable asks.
     `CollectionValuedColumnGateTest`'s javadoc: "the same set joined into one string
     would fail ... A serialized set answers set equality and nothing else, where the rows
     answer that and membership, for one join." The relation's own DDL comment: "a set
     serialized into one column here would have answered set equality and nothing else."
     This drops the concrete wrong answer and keeps the exemplar pair.
   * Keep the concrete wrong answer and tell it in past tense against the relation that
     produced it, the retired directives column and the MCP facet that filtered it. That
     is admissible under the deliverable's own history register, but it puts a second
     relation in the paragraph, so the "one relation and one key" instruction needs
     restating with it.

   Either resolution satisfies the finding; the spec needs to say which, so the paragraph
   the implementer writes is the one that was reviewed.

   **Author's resolution (2026-08-23).** Taken, as a hybrid of the two, and the body now
   says so. The exemplar pair stays live and stays on one relation and one key, and the
   membership argument is now stated structurally against it, in the form both live sites
   already use. The concrete wrong answer stays, because this spec's own framing is that
   the citations lift the case past taste and an abstract capability difference does not,
   but it is now attributed to the relation that paid it and told as history in one clause,
   with Deliverable 1 saying explicitly that it is a clause and not a second exemplar pair.
   The retired directives column could not have been the exemplar pair itself: both its
   sides are gone, so it can show no live pass, and Deliverable 1 requires live names there.
   Both non-blocking notes are also taken: the word-budget claim is replaced with the
   delegation-and-drift argument, which is the true one, and the Verification section now
   scopes the `AdocXrefAnchorCheck` claim to the cross-file case and says the same-page
   reference is the review's to catch.

### Non-blocking

* "Not in scope" justifies excluding development-principles.adoc partly on "its word
  budget is gated". There is no mechanical gate on that page's length; nothing in
  `roadmap-tool` or the poms measures it. The delegation half of the sentence is true and
  carries the exclusion on its own: the preamble does hand the store's modeling discipline
  to fact-model.adoc, in the sentence at development-principles.adoc line 30. Bears on
  nothing the implementer builds, so it is noted rather than counted.
* Deliverable 1's cross-reference to the provenance section is same-page, so
  `AdocXrefAnchorCheck` will not see it (it scans cross-file `xref:` only, by design, since
  Asciidoctor already reports same-file forms at INFO). The Verification section's claim
  that the widened check "fails on any xref path or anchor the edit gets wrong" holds for
  the cross-file case and for a page authored under `docs/`, which is where this edit lands;
  it just does not cover this particular reference. No action needed unless the edit ends up
  linking across files.
