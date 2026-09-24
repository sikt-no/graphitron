---
id: R683
title: "Does capture still need to expand @asFacet the way it expands @asConnection?"
status: Backlog
bucket: architecture
priority: 6
theme: classification-model
depends-on: []
created: 2026-08-14
last-updated: 2026-08-14
---

# Does capture still need to expand `@asFacet` the way it expands `@asConnection`?

## Answered, and the answer is yes

This was decided by building it rather than by argument, so the evidence is what to read rather than
the reasoning below it. The expansion landed as part of the emitted-population work: `@asFacet` is
anchored beside `@asConnection`, and the facet types are minted by the same derivation that mints the
connection, its edge and its page info.

The consumer the item could not name in August exists now, and it is not a store-reading classifier.
It is `EmittedRegistry`, which derives the emitted schema out of the store and is what a run reads
its schema from. With the facet arms absent the emitted schema simply lacks
`QueryFilmsConnectionFacets` and its value types, and the two producers of the emitted schema
disagree on the `faceted-connection` corpus document. That divergence is what found it.

So the first of the two possibilities below is closed: the need was not gone, it had moved. The
exit criterion named at the time, pinning the implementation against `ConnectionPromoter`'s facet
arm, is met in the form the agreement test takes, which compares the two producers over the whole
corpus rather than one subtraction.

What remains of this item is nothing; it is kept only until someone confirms the above and discards
it. The paragraphs that follow are the question as it stood, left unedited.


This item exists to re-decide a question, not to carry a decision forward. It was filed once as R678,
absorbed into R667 as a deliverable, and then silently dropped when R667 was repointed onto a
different target. Rather than re-inherit it into R667's successor, the question is restated here so
the answer is re-derived against what is true now.

## What was originally argued

Capture expands `@asConnection` into the synthesized types the classification walk later sees
(`MacroCapture.expand`, whose carriers are minted after the walk, with provenance on
`graphitron_minted_type` and its per-carrier `graphitron_minted_type_site`). `@asFacet` had no equivalent expansion. The argument for
adding one was specific: a classified-model builder that holds no `GraphQLSchema` cannot mint the
facet types that only a schema rebuild produces, so capture would have to expand them first. On that
reasoning the facet expansion was a strict dependency of the store-reading classification walk, which
is why it was folded in as a deliverable rather than kept separate.

The twin to pin an implementation against was named at the time: `ConnectionPromoter`'s facet arm.
The stated exit criterion was that the named facet subtraction leaves `DemandShadowTest`'s reach
equality intact.

## Why it needs re-deciding rather than resuming

The premise lapsed. R667 was repointed away from the store-reading classification walk after the owner
observed that the walk is being drained from the consumer end instead, so a store-reading classifier
is scaffolding for something being demolished. The facet expansion's entire justification was serving
that classifier. With no classifier to serve, the expansion has no stated consumer.

That does not make it wrong, only unmotivated. There are two live possibilities and this item is
filed to tell them apart:

* **The need is genuinely gone.** Nothing else wants facet types minted at capture time, and the
  expansion would be a relation nobody reads, which is the failure mode the original absorption
  reasoning itself warned about.
* **The need moved rather than vanished.** `roadmap/planners-read-facts-emitters-read-commands.md`
  converts the plan to read the store, and the plan does deal with facets: `FacetSpec` and
  `FacetPlan` are in the command vocabulary, and connection synthesis is one of the four
  relation-shaped folds that item has to give a home. If the faceted carrier's decode data has to be
  store-derived for a converted producer to build its rows, the expansion acquires a consumer again,
  at a different tier than the one it was filed for.

## What answering it looks like

Check whether any converted producer in that item's planner half needs facet types or facet decode
data the store does not carry. If yes, this becomes a dependency of that item and should be specced
against the plan's actual read, not against the retired classifier argument. If no, discard this item
and correct R678's changelog entry, which currently claims R667 carries the template.

## Provenance

Filed as R678, absorbed into R667 (then `capture-precedes-the-classification-walk`) as deliverable 3
within the session that filed it and before any implementation. R667's repoint (`456ee94`) rewrote the
body onto the emit plan and dropped the deliverable without recording the drop, so R678's changelog
entry has claimed since then that nothing was lost and that R667 carries the template, both of which
stopped being true at that commit. Surfaced when R667 was absorbed into R682 and its content audited
for what had to carry over.
