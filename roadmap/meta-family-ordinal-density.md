---
id: R909
title: "Family ordinals are a dense position list or an unordered key, and nothing says which"
status: Backlog
bucket: cleanup
priority: 3
theme: model-cleanup
depends-on: []
created: 2026-09-01
last-updated: 2026-09-01
---

# Family ordinals are a dense position list or an unordered key, and nothing says which

## Goal

`meta_family.ordinal` has one stated meaning and one gated meaning, and they disagree. The column
comment calls it "the family's position in the reference's page order and index roster, 0-based",
which reads as a dense sequence. The only gate over it,
`FactSchemaGateTest.theFamilyRosterIsWellFormed`, asserts uniqueness and nothing more, so any
increasing sequence passes. The sibling `meta_family_headline.ordinal` is gated dense within each
family, which makes the silence here look deliberate without saying so.

The roster now has a gap at 7, where the `walk_` family sat before it retired. That gap is what
exposed the ambiguity: retiring a family left a choice nobody had to make before, and the answer was
guessed once already, wrongly, by renumbering five rows on the belief that a density gate existed.

When this lands, one of the two readings is written down and enforced. Either the ordinals are a
dense position list, in which case a gate closes them against `0..n-1` and family retirement carries
a renumbering step; or they are an ordering key whose values do not matter, in which case the column
comment stops calling them a position and says gaps are expected. What must not survive is the
current state, where the comment implies one answer, the gate implies the other, and the next person
to retire a family guesses.

## Plan

Not planned. The decision comes first and the mechanism follows it in a sentence either way, so this
stays a Backlog stub until someone takes the question.

Worth knowing for whoever does: dense ordinals cost a renumbering on every family retirement, which
is rare, and buy a roster that reads as a list of positions. Sparse ordinals cost nothing and mean
the reference's page order is a sort rather than an index. The generated reference orders by this
column either way, so no reader-visible behaviour turns on the choice.
