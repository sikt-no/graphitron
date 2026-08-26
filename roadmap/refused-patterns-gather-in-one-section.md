---
id: R842
title: "Refused patterns gather in one section instead of a residue in every table"
status: Spec
bucket: architecture
priority: 4
theme: docs
depends-on: []
created: 2026-08-26
last-updated: 2026-08-26
---
# Refused patterns gather in one section instead of a residue in every table

`reference/code-generation-triggers.adoc` carries a reference table under most of its classification
sections. Those tables are transitional: each row states a rule in prose, and the page's worked
examples replace them as they land, so the tables shrink as the page grows. That draining has now run
far enough to expose what is underneath, and it is the same thing under every table.

About a dozen rows are left across six tables, and nearly all of them describe a pattern the build
refuses: three under the type table, four under Mutation Fields, one under Query Fields, three under
the object-return child table, and a couple of deferrals elsewhere. They cannot drain the way the
others did. The corpus renders patterns that classify *and generate*, and a refusal generates
nothing, so there is no emitted output for a worked example to show. The renderer enforces this: a
fixture that classifies but is rejected renders a two-column block and a note saying so, which is
what stops a promotion from putting a rejection beside a rule that holds.

So the rows are permanent residue under the current arrangement, and they are filed by the wrong
question. Each sits in the section whose classification it belongs to, but a reader arriving at those
sections is asking "what does this pattern generate". A reader who wants "why did my build refuse
this" has nowhere to go, and the rows that would answer them are scattered across six tables sorted
by a criterion they do not care about.

## The direction (for the Spec to own)

Gather the refusal rows into `== When nothing is generated`, so every classification section ends in
worked examples and the refusals have one home. The tables that hold nothing else disappear; the two
or three still holding a live pattern keep only that.

That much is the easy half. Three things make this a design question rather than a move, and the Spec
owns all three.

**The destination is not currently a census, and may not want to be.** `== When nothing is generated`
today explains the *mechanism* of refusal: diagnostics as returned rows rather than thrown
exceptions, and the three-way `AUTHOR_ERROR` / `INVALID_SCHEMA` / `DEFERRED` fork that
`rejection_validation_error` carries under a `CHECK` constraint. It deliberately enumerates no
patterns. Dropping a dozen pattern rows into it changes what the section is. The alternative is a
sibling section that catalogues while that one explains, cross-linked; a third option is to key the
catalogue by the `kind` fork rather than by classification, which would let a reader who has an
`AUTHOR_ERROR` in hand find their case directly. Deciding this is the item's main work.

**The rows are written in a vocabulary that is being dissolved.** Their variant column names
`UnclassifiedField`, `DmlTableField`, `MutationTableArgError.UnsupportedVerb`,
`FieldClassification.Conflicted`. R682 dissolves the walk and the leaf zoo those names come from. So
the text needs re-keying whether or not it moves, and relocating first means transcribing a dozen
rows and then rewriting them in place. Whether this item runs before R682, after it, or as part of
the same sweep is a sequencing call the Spec must make explicitly, not discover halfway.

**The doc collapse changes what "the authored page" means.** R840 renders per-example sections into
generated staging and leaves the authored page holding only narrative and teaching order. A gathered
refusal section is narrative, so it survives that collapse and shrinks what has to be carried across
it, which is an argument for doing this before R840 rather than after. It is not an argument for
doing it before R682.

## Constraints and boundaries

- **No content invention.** Every relocated row keeps the claim it already makes. Two rows retired
  during the drain turned out to be *wrong* rather than redundant, both asserting that graphql-java's
  default fetcher handled a coordinate the generator emits a method for. A row moved here is not
  thereby verified, and the Spec should say plainly that this item relocates unverified claims and
  does not launder them.
- **The input-side table is out of scope.** Its six rows are all live patterns, and it is undrained
  for a different reason than the others: the corpus does not model input fields at all, and
  `VariantCoverageTest` deliberately keeps input-field leaves on the enum truth table. That is a
  scope boundary, not a backlog.
- **The transitional NOTEs are downstream of this.** The `== Type Classification` and
  `== Field Classification` sections carry NOTEs marking the tables as transitional. Whether those
  come off is currently a judgment call precisely because the tables still hold something. Once the
  tables hold only live patterns or vanish, the NOTEs answer themselves, and this item should say so
  rather than leaving the question open a third time.
