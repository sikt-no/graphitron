---
id: R877
title: "The graphitron-model house cleaning party: relation descriptions are argument transcripts, so nobody reads them and the same fact gets a second relation"
status: Backlog
bucket: cleanup
priority: 2
theme: model-cleanup
depends-on: []
created: 2026-08-29
last-updated: 2026-08-29
---

# The graphitron-model house cleaning party: relation descriptions are argument transcripts, so nobody reads them and the same fact gets a second relation

A relation's comment is supposed to tell you what one of its rows is. Most of them argue a case
instead. The reader who wanted a definition gets an essay, stops reading, and adds a new relation for
a fact the store already holds.

## The measurements

Taken over the 2563 `COMMENT ON` statements in `graphitron-model.sql`.

**The first sentence is fine.** A relation comment's opening sentence is its grain statement, and
`GrainSentence` already extracts it as one. Those run a median of 9 words. One of 276 is longer than
40 words. Whatever is wrong here, it is not the opening line.

**The rest is not.** Relation comments run a median of 117 words, a 90th percentile of 630, and a
maximum of 1431. The eight longest are between 900 and 1431 words each.

**There are almost no examples.** Four comments out of 2563 contain the words "for example" or "e.g.".

**Length does not track difficulty.** Comparing each relation's comment length against the size of
the statement it expands into, the correlation is weak, and the exceptions break it open.
`intent_field_scope_table` has the longest comment in the store, 1431 words, over one of the smaller
structures. `intent_condition_membership` describes a structure thirty-seven times larger in two
thirds the words.

## What the long comments actually contain

Not descriptions. Transcripts of arguments. `intent_field_scope_table`'s comment carries three essays
on shapes that were tried and rejected, timing figures from a fixture, a defence against a projection
two earlier authors argued for, and a clause disambiguating it from a relation with a similar name.
Every one of those is useful to somebody. None of them answers "what is one row of this".

## Why that produces extra relations

The chain is short. A description nobody finishes reading is a description nobody consults. A store
whose descriptions nobody consults cannot tell an author that the fact they are about to capture is
already captured somewhere else. So they capture it again, under a slightly different name.

The store has worked examples of the result:

- Six relations spell a reference to a table as `table_ref`. A seventh, `graphitron_routine`, spells
  the identical fact `routine_ref`. The mechanical detector that hunts for this defect missed that
  seventh relation for that reason alone.
- Ten views reconstruct a fact by unioning an argument-site relation with its field-site twin, for
  example `graphitron_argument_condition` with `graphitron_field_condition`. Each pair is one fact
  written at two coordinates with no relation naming it once. `SupertypeSignatureGateTest` counts
  them.
- Names diverge inside a single set. The four reference-step relations are
  `graphitron_field_reference_step`, `graphitron_argument_reference_step`,
  `graphitron_reference_for_step` and `graphitron_argument_reference_for_step`. Two carry a site
  prefix and two do not, which hides the fact that they are four spellings of one thing.

## Why the transcripts ended up in the DDL

This is an incentive, not a habit, and naming it matters because the fix has to remove the incentive
rather than ask people to try harder.

A roadmap item is deleted when it reaches Done. An author who has just spent a week establishing why
a shape is right has nowhere durable to record that reasoning except the relation comment, because
the item that holds it is scheduled for deletion. So the comment absorbs the argument. The
architecture docs under `docs/architecture/` are the durable home for rationale and are not being
used for it.

## What finishing this would look like

Not decided here. A Backlog item states the problem. Sketched only so the next reader knows the
shape:

- A relation comment defines the row and gives one example of one, before it argues anything.
- Rationale, measurements and rejected alternatives move to `docs/architecture/`, which outlives the
  item that produced them.
- Names inside one subtype set agree with each other.
- Whatever survives is gated, on the pattern the model tier already uses: "carries an example" is
  checkable, and today's answer is 4 of 2563.

## Two cautions for whoever picks this up

**Do not delete the arguments.** They are the record of decisions that cost real work to reach, and
several of them are the only place a rejected shape is written down. They move; they do not go.

**The grain sentences are already good.** This item is not a rewrite of every comment in the store.
The opening sentences work and the mechanism that extracts them works. What needs doing starts at the
second sentence.

