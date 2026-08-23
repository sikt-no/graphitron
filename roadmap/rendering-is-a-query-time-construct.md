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
  under the same key, where until R803 a serialized twin sat beside the count. The wrong
  answer that twin produced is the cost stated concretely: the MCP diagnostics surface
  filtered the conflict set with exact set equality, so asking for conflicts involving one
  directive silently returned only the conflicts whose entire set was that directive; it is
  now a join that asks membership. This is also what makes the gate's denylist coherent:
  `ARRAY_AGG` is no delimited string, but it is a collection in a scalar.
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
one key), the membership-versus-set-equality cost, and one cross-reference to the
provenance section's minted-message sentence for the order half. Do not enumerate the
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

* Prose-only change. The verification build covers the AsciiDoctor render, and the
  widened `AdocXrefAnchorCheck` fails on any xref path or anchor the edit gets wrong.
* No retired column name appears as a live citation; the failing exemplars are past
  tense. Live names used: `intent_type_backing_conflict.candidates`,
  `intent_type_backing`, `diagnostic.coordinate`.
* No mechanical test pins prose content; the Spec → Ready and In Review → Done gates are
  the review. The one build-checked claim is the enforcer line naming a test that exists.

## Not in scope

* development-principles.adoc: its preamble already delegates the store's modeling
  discipline to fact-model.adoc, and its word budget is gated.
* The DDL comments: they already carry the vocabulary and the per-column arguments; this
  item adds the page-level rule they instantiate.
* Any further schema or consumer change; R803 finished those.

(The handoff notes R803's implementer appended here are folded into the deliverables
above: the gate's name and its disclosed gap into Deliverable 1's enforcer line, the
one-relation-two-verdicts exemplar into Deliverable 2, the membership-versus-set-equality
cost into the discriminator bullet, and the `ClaimFacts` past-tense correction into the
citations paragraph.)
