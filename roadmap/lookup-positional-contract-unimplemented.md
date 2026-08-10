---
id: R617
title: "Lookup misses drop rows instead of holding their position"
status: In Progress
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

## Rework from the In Review gate

Build green (`mvn install -Plocal-db`, 511 test classes). The emit change itself reviews clean: the
scatter, the `List<Record>` value type, the dropped `ORDER BY`, the `fetchOne` single arm (which also
quietly repairs a value-type/body mismatch that arm carried before), and the execution-tier coverage
of repeated, unordered and missed keys are all right. Two things block the gate.

### The manual gained a false positional claim on a coordinate that has no positions

`batch-lookups.adoc:82` now reads: "`filmsByNodeIdArg(ids: [<film_2>, "garbage", <film_4>])` returns
two films at positions 0 and 2 and skips position 1 entirely". `filmsByNodeIdArg` is not a lookup.
`FieldBuilder.java:1983` states it outright, "isLookupKey == false when @nodeId targets the field's
own table", and the arg projects to `BodyParam.In` / `RowIn`: a `WHERE film_id IN (...)` filter with
no `VALUES` table, no `idx`, and no scatter. There is nothing positional to describe.

The claimed behaviour is also the opposite of what the field does. `GraphQLQueryTest.java:997`,
`filmsByNodeIdArg_mixedValidAndMalformed_surfacesClientError`, pins the live answer: one malformed id
fails the whole field, error raised, field null, no partial result. Two more give-aways sit in view:
the sentence contradicts its own paragraph four lines up (`batch-lookups.adoc:73`, "A malformed or
wrong-type id fails the field rather than narrowing the result set"), and a two-element list cannot
hold elements at positions 0 and 2.

Half of this predates the item (the "skips the malformed id" reading was already there). What the
item added is the positional framing, which is exactly the thing it exists to make true, in the page
whose truthfulness is its deliverable. That is why it blocks rather than becoming a follow-up.

Two sibling errors in the same subsection are worth clearing in the same pass, since they are why the
wrong sentence looked plausible:

- `batch-lookups.adoc:73` "The classifier synthesises `isLookupKey: true` for this arm" is inverted
  against `FieldBuilder.java:1983`. (Correcting the review's own first reading: the *rejection* the
  same sentence claims is not real either. `FieldBuilder.java:4595` and
  `NodeIdPipelineTest.SAME_TABLE_WITH_EXPLICIT_LOOKUP_KEY` both say an explicit `@lookupKey` beside a
  same-table `@nodeId` is the deliberate opt-in *back into* the lookup shape. The only rejected
  pairing is the FK-target one.)
- `batch-lookups.adoc:129` "The decode failure mode is `Skip` (filter semantics, malformed ids drop
  silently)" names an arm that does not exist. `CallSiteExtraction.java:140` seals
  `NodeIdDecodeKeys permits ThrowOnMismatch` and nothing else.

Worth noting while rewriting: `filmsByNodeIdArg_emptyList_returnsUnfilteredBaseline`
(`GraphQLQueryTest.java:1234`) returns all five films, so the page's empty-input short-circuit bullet
does not describe this coordinate either. The whole subsection presents a filter path as "a flavor of
NodeId-driven lookup"; the honest fix is probably to stop doing that and point at the filter docs,
rather than to patch the one sentence.

### The new build-time rejection is pinned by nothing

`GraphitronSchemaValidator.validateRootLookup`'s nullable-element rejection landed without a test.
`RootLookupValidationTest` is its canonical home and is built as an exhaustive cube over exactly the
wrapper verdicts that method makes, but no cell varies item nullability: `cell()` at
`RootLookupValidationTest.java:60` hardcodes `new FieldWrapper.List(true, true)`. Delete the
rejection and the reactor stays green. The example-schema and plugin-IT edits prove only the accepted
side; the compile and execution tiers never see a rejected schema.

The item's own body calls this "a consumer-visible schema requirement" and "a build-time rejection
rather than a runtime surprise". Today the only thing keeping it honest is that no coordinate in the
tree declares `[Film!]!`. Add the reject cells to the cube, asserted by rejection kind and message
substring per the validator-unit-test convention in `docs/architecture/how-to/testing.adoc`.

### Not blocking, worth doing while here

- `RootLauncherRenderer.java:412` writes the scatter alias as a bare `"__idx__"` literal.
  `ReservedAliases` exists to hold precisely this ("the invariant is cross-package: writer alias
  equals reader alias"); the reader goes through `SplitRowsMethodEmitter.IDX_COLUMN`, and the
  analogous writer at `BatchedRowsFragments.java:281` uses the constant. One-line swap.
- `ReservedAliases.IDX`'s javadoc describes its writer as the batched launcher body. The root lookup
  launcher is now a second writer.
- `scatterLookupByIdx`'s first-row-wins tie-break is an explicit decision (documented in
  `SplitRowsMethodEmitter` and in the manual as "one of them is returned") that nothing pins.
  `ScatterSingleByIdxTest` is the precedent for testing an emitted helper's runtime behaviour
  directly. Fine as a follow-up item if it does not fit this pass.

Reviewed clean, for the record: the user-facing-doc marker check (no roadmap vocabulary on either
manual page); the retirement sweep (this item declares nothing retired, and it correctly *un*-retires
the positional-null vocabulary in `roadmap/lookup-generated-column-filters.md` so that item's own
Done sweep will not strip phrasing that is live again); and the code-string ban, which the delivery
respects everywhere except `RootLauncherRendererTest`, where body-string assertions are the file's
established convention and the renderer-arm paragraph of
`docs/architecture/how-to/testing.adoc` blesses per-arm structural assertions on command-driven
emission. That paragraph and `development-principles.adoc`'s "banned at every tier" do not agree with
each other, but the disagreement predates this item and is not its to settle.

## Rework delivered

Both blocking findings are closed, and all three of the non-blocking notes with them.

*The NodeId subsection now names three arms instead of two, split on whether the arm is a lookup at
all.* The middle one, `@nodeId(typeName: T)` alone with `T` matching the return type, is a filter, and
the page now says so and spells out the three ways it differs: order is whatever the query yields, an
empty list returns the unfiltered table rather than `[]`, and one bad id nulls the whole field instead
of occupying a position. Its `[Film!]!` is correct precisely because it has no slots to fill. The
third arm is the `@lookupKey` opt-in that promotes it back to a lookup, which the constraints bullets
now state in the right direction, along with the fact that no decode path drops an id silently.

*That third arm was documented but unexercised, so it now exists.* Writing the subsection meant
naming a coordinate for the opt-in, and the example schema had only a comment promising one
("see filmsByNodeIdArgWithLookupKey below if exercised"). Rather than name a coordinate no consumer
could look up, `filmsByNodeIdArgWithLookupKey` is now a real fixture beside its filter sibling, the
two differing only by `@lookupKey`. Two execution tests pin the contrast the page draws: three ids in
(one absent, one repeated) answer as three slots with a null and no deduplication, and the empty list
short-circuits to `[]` where the filter sibling returns all five films. This also puts the
NodeId-decoded lookup arm on the execution tier for the first time.

*The nullable-element rejection now has six cells in `RootLookupValidationTest`.* A `listReturn`
helper varies the return wrapper's two nullability slots independently, which the cardinality cube's
`cell` cannot (it fixes both nullable). `[Film!]!` and `[Film!]` reject, `[Film]` accepts, a non-key
filter beside the keys changes nothing, a scalar key with `[Film!]!` raises both wrapper errors
independently, and a non-null *single* return stays valid because that arm has one slot by
construction and `fetchOne` already answers null in it.

*The scatter alias goes through `ReservedAliases.IDX`* at the writer as well as the reader, and that
constant's javadoc now names both of its writers.

*`scatterLookupByIdx` gained direct coverage* in `ScatterLookupByIdxTest`, modelled on its
`scatterSingleByIdx` sibling. The tie-break is what earns a unit test rather than an execution one:
slot-per-key and null-for-a-miss are already pinned against PostgreSQL, but "two rows on one key keeps
the first" is a deliberate divergence from the sibling's throw that the example schema's
uniquely-keyed fixtures will not reliably produce.

*One stale comment swept.* The composite-PK NodeId section header in `GraphQLQueryTest` still asserted
"missing rows are simply absent (positional output is dense, not sparse)", directly contradicting the
tests beneath it that the first pass had already flipped to slot assertions.
