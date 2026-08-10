---
id: R617
title: "Lookup misses drop rows instead of holding their position"
status: In Review
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-09
last-updated: 2026-08-10
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
first settling what a *missed* key looks like. That item recorded the divergence and left the emit
alone, because which side is wrong is a design decision with consumer-visible consequences.

## The decision

The user settled it directly: `filmById(film_id: ["1", "999999", "2"])` must return three items,
and that is critical. The documented contract is the intended one, so the generator is what changes.

## What the contract forces

The positional reading is not a free choice of representation, it forces the schema's hand. A slot
holding a miss holds `null`, and `[Film!]!` cannot carry one: GraphQL propagates the null out of the
list, so one unmatched key nulls the whole field and discards every matched row with it. Measured
before the change rather than reasoned about: every root lookup in the example schema failed exactly
that way, with `NonNullableFieldWasNullError` at `/filmById[1]` and `data: null`.

So the element type of a list lookup must be nullable, `[Film]!` rather than `[Film!]!`, and this
item makes that a build-time rejection rather than a runtime surprise. That is a consumer-visible
schema requirement: every existing lookup coordinate declaring non-null elements has to drop the
inner `!`. It is not a behaviour regression (those schemas were already returning short lists that
silently misaligned with their inputs) but it is a required edit, and a consumer that does not make
it gets a build error naming the field and the remedy.

## What shipped

`RootLauncherRenderer.lookupBody`'s list arm carries the derived table's `idx` out as `__idx__` and
scatters through a new `scatterLookupByIdx` helper, one slot per input key. The scatter is also what
carries input order, so the arm no longer emits `ORDER BY`, and the launcher's value type becomes
`List<Record>` because a jOOQ `Result` cannot hold a null element. Duplicate keys keep the
documented answer, first row wins, rather than the `scatterSingleByIdx` throw: that helper serves a
primary-key join where a second row per key is a misconfiguration, while a lookup joins on author-
declared columns the schema never required to be unique.

`GraphitronSchemaValidator.validateRootLookup` gains the nullable-element rejection. The single-key
arm needs none of this and now says so: one key has one slot, and `fetchOne` already returns null.

Five execution-tier tests asserted the old drop-the-miss behaviour and now assert slots; the SQL
baselines absorbed the `__idx__` column and the dropped `ORDER BY`. The manual's positional-contract
statements, which the previous item had rewritten to describe measured behaviour, are restored and
extended with the nullable-element requirement.

## Not in scope

Child coordinates. A lookup-keyed child list is a set narrowing per parent, correctly documented as
such, and stays a plain list. The `@asConnection` rejection's stated rationale now holds again,
since the positional contract it appeals to exists.

## Process note

Implementation ran straight from Backlog on the user's explicit direction, so this item never took a
Spec to Ready sign-off; the design question the gate exists to settle is the one the user answered.
The independent review happens once, at the Done gate.

## The framing the manual was missing

Reviewing the restored pages surfaced a second defect, conceptual rather than factual. The manual
described `@lookupKey` as a "batch lookup" and never named what it actually is: a Relay
https://graphql.org/learn/global-object-identification/[plural identifying root field]. That framing
is what makes the positional contract follow rather than seem like an implementation detail, and its
absence let two claims drift.

The first is the "batch" word itself, which invites the reading that the generator resolves a *key
set*. It does not, and never did: the `VALUES` table carries one row per input position, so repeated
keys are answered as many times as they were asked and caller order is never normalised. Measured,
not assumed: `filmById(film_id: ["1", "1", "1", "1", "1"])` returns five copies, and
`languageByKey(language_id: [2, 1, 2, 99, 1])` answers `2, 1, 2, null, 1`. Both are now pinned at the
execution tier, since a per-key contract that only prose asserts is one an optimisation can quietly
take away.

The second is the uniqueness bullet, which read "keys should uniquely identify a row; if multiple
rows match the same key, only one is returned" and conflated two unrelated things under one word.
Uniqueness is a property of the *columns* a key binds to, not of the values a caller sends: binding
a key to a non-unique column is a schema mistake, while repeating a value is ordinary and supported.
The bullet now separates them on both pages.
