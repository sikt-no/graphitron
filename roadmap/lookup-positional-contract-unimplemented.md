---
id: R617
title: "Lookup misses drop rows instead of holding their position"
status: Ready
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
`List<Record>` because a jOOQ `Result` cannot hold a null element. Repeated *keys* need no handling
at all and get none: they arrive as separate `VALUES` rows with separate `idx` values and scatter to
separate slots. What the scatter does decide is the case of two *rows* landing on one key, where it
keeps the first rather than throwing as `scatterSingleByIdx` does: that helper serves a primary-key
join where a second row per key is a misconfiguration, while a lookup joins on author-declared
columns the schema never required to be unique.

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
## What has shipped

Two passes, each green on `mvn install -Plocal-db`:

- The scatter, the `List<Record>` value type, the dropped `ORDER BY`, the `fetchOne` single arm and
  the nullable-element rejection shipped at `1f249c0`; the plural-identifying-root-field framing and
  the repeated/unordered-key execution pins at `bce87ad`.
- The first Done gate's two blocking findings and its three non-blocking notes shipped at `ed79266`,
  with the fixture-warnings line re-pin at `fc93d75`.

## Rework from the second In Review gate

Full reactor build green (`mvn install -Plocal-db`, all 14 modules, exit 0). Everything the first
gate held is closed and verified. `RootLookupValidationTest`'s six new nullability cells make the
rejection load-bearing (delete it and the cube goes red); `ScatterLookupByIdxTest` pins the
first-wins tie-break that PostgreSQL cannot observe on uniquely-keyed fixtures; the scatter alias
runs through `ReservedAliases.IDX` at writer and reader alike; and the NodeId section now splits on
whether an arm is a lookup at all, with `filmsByNodeIdArgWithLookupKey` a real fixture pinned at the
execution tier. The emit itself reviews clean. One finding blocks.

### The manual states the positional contract, and its build rejection, as universal over `@lookupKey`

Three claims added by this item quantify over every `@lookupKey` shape, but describe only the root
arm:

- `batch-lookups.adoc:9` — "Every `@lookupKey` shape compiles to the same generator pattern ... The
  output list is the same length as the input key list; unmatched keys produce `null` at their input
  position ... a lookup field declaring `[Film!]!` is rejected at build time."
- `batch-lookups.adoc:146` — "A lookup field's list elements must be nullable. `[Film]!` and `[Film]`
  are accepted, `[Film!]!` and `[Film!]` are rejected with a build error."
- `lookupKey.adoc`'s matching constraint bullet, "The list elements must be nullable ... `[Film!]!`
  and `[Film!]` are rejected with a build error."

None of that is true of a child lookup coordinate. `GraphitronSchemaValidator.java:719` is the only
`itemNullable` check in the validator; it sits inside `validateRootLookup`, whose single caller is
`validateQueryTableField` at `:682`, so a child `@lookupKey` field never reaches it. The example
schema declares five child lookups with non-null elements and the full build is green with all of
them: `schema.graphqls:1434` (`FilmDetails.actorsByLookup`), `:1604` (`Film.actors`), `:1619`
(`Film.actorsBySplitLookup`), `:1850` (`FilmInfo.castByKey`), `:1877` (`Film.actorsByKey`), every one
`[Actor!]!`. The behaviour differs too, not just the validation: `GraphQLQueryTest.java:2380`,
`splitLookupTableField_filterExcludesActorsNotInFilm`, pins `actorsBySplitLookup(actor_id: [3])`
returning `[]` for film 1 rather than one slot holding null, and `ProjectionUnitRenderer.java:432`
shows the inline child arm still emitting `.orderBy(input.field("idx"))` with no scatter. So the
"same generator pattern" clause is false in the mechanism it names as well as in its consequence.

The page says the right thing 137 lines later, at `batch-lookups.adoc:109`: "absence of an actor in a
film yields no row (not `null`) at the child position, since this is a list output, not a positional
one." And this item's own scope section says it: child coordinates stay a plain list, correctly
documented as such.

This blocks rather than becoming a follow-up for the same reason the first gate's manual finding did.
Making the manual's positional claims true is the deliverable, and the claim is consumer-actionable in
the wrong direction: a consumer with `actorsBySplitLookup: [Actor!]!` reads the bullet, is told to
edit their schema for a build error that will never fire, and the edit hands them a nullable element
type that never holds null. Note also that the pre-change text at `:9` was a *true* universal ("a key
matching no row contributes no element" held on both arms); the delivery replaced it with a false one.

Worth scoping in the same pass, same root cause: the `@asConnection` rationale at `:9` and `:136`
("pagination would shift positions and break the positional contract") is also root-only, since
`:115` extends that rejection to `@splitQuery` children which have no positions to shift.

### Not blocking, worth doing while here

- `RootLauncherSqlBaselineTest.java:261` is named `lookupRoot_valuesJoinKeyedAndInputOrdered` and its
  `.as(...)` at `:264` still says "input-ordered by the derived table's idx column", against a
  baseline the same commit stripped the `ORDER BY` from. Same stale-prose class the delivery already
  swept out of `GraphQLQueryTest`'s composite-PK section header.
- `LookupMapping.java:29` — "ordered by `input.idx` to preserve input ordering" — and
  `ResultShape.java:46` — "unless the source arm entails its own, the lookup's `idx` order" — both
  describe the `ORDER BY` this item removed from the root arm.
- `LookupMapping.java:11` carries the same `Skip`-arm inversion the rework just corrected at
  `batch-lookups.adoc:129`: "carrying a `NodeIdDecodeKeys` arm (Throw on synthesised lookup-key
  paths, Skip on the same-table `@nodeId` filter path)". `CallSiteExtraction.java:140` seals the
  interface to `ThrowOnMismatch` alone, and `LookupMapping.java:147` states it correctly 136 lines
  below. Pre-existing, not introduced here; a follow-up item is fine if it does not fit this pass.

### Reviewed clean, for the record

The user-facing-doc marker check (no roadmap vocabulary, `Phase <n>`, TODO or slug reference in the
item's `docs/` diff). The retirement sweep, which this item skips: it declares no retired vocabulary,
and the un-retirement it made in the generated-column-filter item's body is settled, that item having
reached Done and deleted its file. The code-string ban: the only body-string assertions are in
`RootLauncherRendererTest`, which carried 29 of them before this item and is a renderer arm test, the
species `docs/architecture/how-to/testing.adoc` names as the preferred home for per-arm structural
assertions on command-driven emission. Its disagreement with `development-principles.adoc`'s "banned
at every tier" predates this item and is not its to settle. `RootLookupValidationTest` asserts on
`ValidationError::message` exactly rather than by rejection kind plus substring, which is the file's
own convention throughout rather than a deviation introduced here.
