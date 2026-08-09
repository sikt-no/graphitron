---
id: R617
title: "Lookup misses drop rows instead of holding their position"
status: Backlog
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-09
last-updated: 2026-08-09
---

# Lookup misses drop rows instead of holding their position

The user manual documented a positional contract for `@lookupKey` that the generator does not
implement, and the two disagreed for as long as both have shipped. `batch-lookups.adoc` carried a
section titled "The positional contract" asserting "the output list is the same length as the input
key list; unmatched keys produce `null` at their input position", `lookupKey.adoc` said the same in
four places, and `@asConnection` is rejected on a lookup field *because* "pagination would shift
positions and break the positional contract". What the root lookup actually emits is an inner join
against the `VALUES` table ordered by `idx`, returned straight from `.fetch()` with no scatter step,
so a key matching no row simply contributes no element. Measured on the shipped sakila fixture:
`filmById(film_id: ["1", "999999", "2"])` returns two films, not three positions with a `null` in
the middle.

The contradiction was found while landing the generated-column-filter work on lookup coordinates,
which had to state what a filtered-out key looks like on the wire and could not do so without
first settling what a *missed* key looks like. That item corrected the manual to describe measured
behaviour, because a manual that lies today is worse than one that describes a weaker contract. It
deliberately did not touch the emit: which side is wrong is a design decision with consumer-visible
consequences, and it is not that item's to make.

So this item's question is which of the two the project wants. Making the code match the docs means
a scatter step on the root lookup arm (the machinery already exists for the `@splitQuery`
single-cardinality path, `scatterSingleByIdx`) plus a nullable element type on every lookup return,
since a `[Film!]!` cannot hold a `null` at a position. Making the docs the truth is already done,
and this item then reduces to deciding that the join semantics are the intended contract and
re-checking the `@asConnection` rejection's stated rationale, which appeals to a positional contract
that does not exist. A third possibility worth pricing: the positional reading may be what the
migrating subgraph expects, in which case the answer is driven by consumer need rather than taste.

Note the child coordinates are not in question. A lookup-keyed child list is a set narrowing per
parent and is documented as such, correctly.
