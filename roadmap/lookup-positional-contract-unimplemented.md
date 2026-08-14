---
id: R617
title: "Lookup misses drop rows instead of holding their position"
status: Ready
bucket: bug
priority: 1
theme: codegen-correctness
depends-on: []
created: 2026-08-09
last-updated: 2026-08-14
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
- The second Done gate's blocking finding and all three of its non-blocking notes shipped in the
  pass recorded below.

## The second In Review gate, and what it changed

The gate found everything the first one held closed and verified: `RootLookupValidationTest`'s six
new nullability cells make the rejection load-bearing (delete it and the cube goes red);
`ScatterLookupByIdxTest` pins the first-wins tie-break that PostgreSQL cannot observe on
uniquely-keyed fixtures; the scatter alias runs through `ReservedAliases.IDX` at writer and reader
alike; and the NodeId section splits on whether an arm is a lookup at all, with
`filmsByNodeIdArgWithLookupKey` a real fixture pinned at the execution tier. The emit itself
reviewed clean. One finding blocked, and it is the one this pass answers.

### The manual stated the positional contract, and its build rejection, as universal over `@lookupKey`

Three claims added by this item quantified over every `@lookupKey` shape, but described only the root
arm:

- `batch-lookups.adoc:9` — "Every `@lookupKey` shape compiles to the same generator pattern ... The
  output list is the same length as the input key list; unmatched keys produce `null` at their input
  position ... a lookup field declaring `[Film!]!` is rejected at build time."
- `batch-lookups.adoc:146` — "A lookup field's list elements must be nullable. `[Film]!` and `[Film]`
  are accepted, `[Film!]!` and `[Film!]` are rejected with a build error."
- `lookupKey.adoc`'s matching constraint bullet, "The list elements must be nullable ... `[Film!]!`
  and `[Film!]` are rejected with a build error."

None of that is true of a child lookup coordinate. The validator's only `itemNullable` check sits
inside `GraphitronSchemaValidator.validateRootLookup`, whose single caller is
`validateQueryTableField`, so a child `@lookupKey` field never reaches it. The example
schema declares five child lookups with non-null elements and the full build is green with all of
them: `schema.graphqls:1434` (`FilmDetails.actorsByLookup`), `:1604` (`Film.actors`), `:1619`
(`Film.actorsBySplitLookup`), `:1850` (`FilmInfo.castByKey`), `:1877` (`Film.actorsByKey`), every one
`[Actor!]!`. The behaviour differs too, not just the validation:
`GraphQLQueryTest.splitLookupTableField_filterExcludesActorsNotInFilm` pins
`actorsBySplitLookup(actor_id: [3])` returning `[]` for film 1 rather than one slot holding null, and
`ProjectionUnitRenderer`'s inline child arm still emits `.orderBy(input.field("idx"))` with no
scatter. So the "same generator pattern" clause was false in the mechanism it named as well as in its
consequence.

The page said the right thing 137 lines later, in the `@splitQuery` section: "absence of an actor in a
film yields no row (not `null`) at the child position, since this is a list output, not a positional
one." And this item's own scope section says it: child coordinates stay a plain list, correctly
documented as such.

It blocked rather than becoming a follow-up for the same reason the first gate's manual finding did.
Making the manual's positional claims true is the deliverable, and the claim was consumer-actionable
in the wrong direction: a consumer with `actorsBySplitLookup: [Actor!]!` read the bullet, was told to
edit their schema for a build error that will never fire, and the edit would hand them a nullable
element type that never holds null. The pre-change text at `:9` was a *true* universal ("a key
matching no row contributes no element" held on both arms); the delivery had replaced it with a
false one.

**The fix.** The positional-contract section now says up front that the contract it describes is the
root field's, then gives the child arm its own paragraph: same `VALUES` join, no scatter, an
unmatched key contributing no element rather than a `null`, and a non-null element type that is both
correct and accepted. The two nullable-element bullets are scoped to root lookups and say so; the
`@splitQuery` section closes the loop from the other side, explaining why the `[Actor!]!` in its own
example is right. `lookupKey.adoc` gets the same scoping on its constraint bullet plus a lead
paragraph saying the contract is the root field's, since the page had no other mention that child
coordinates exist; its "only root-level arguments may be keys" bullet was stale on that same point
and now matches `batch-lookups.adoc`. The `@asConnection` rationale, root-only for the same reason,
now states the rejection as uniform and gives the root reason as the root reason.

The rejection message carried the same unqualified claim ("a lookup field's list elements must be
nullable") and is now "a root lookup field's"; `RootLookupValidationTest`'s expected-message
constant moves with it.

### The three non-blocking notes, all taken in this pass

- `RootLauncherSqlBaselineTest`'s lookup baseline was named `..._valuesJoinKeyedAndInputOrdered` with
  an `.as(...)` claiming "input-ordered by the derived table's idx column", against a baseline the
  same commit stripped the `ORDER BY` from. Renamed to `..._valuesJoinKeyedAndIdxProjectedForTheScatter`,
  with a description that names what the SQL actually shows and why there is no `ORDER BY`.
- `LookupMapping.ColumnMapping` — "ordered by `input.idx` to preserve input ordering" — and
  `ResultShape.RecordList` — "unless the source arm entails its own, the lookup's `idx` order" —
  both described the `ORDER BY` this item removed. `ColumnMapping` now splits the two arms (the root
  launcher scatters and emits none, a child coordinate still orders by `input.idx`), and
  `RecordList` says the ordering slot is absent because the scatter carries order, not because a
  sort is entailed. `ColumnMapping`'s doc-path citation was also stale and now points at
  `docs/architecture/reference/code-generation-triggers.adoc`.
- `LookupMapping`'s type-level javadoc carried the same `Skip`-arm inversion the first rework
  corrected in the manual. `CallSiteExtraction.NodeIdDecodeKeys` permits `ThrowOnMismatch` alone, so
  the parenthetical now says the one arm applies to the lookup-key and filter paths alike, which is
  what the nested javadoc 130 lines below already said.

Full reactor build green after the change (`mvn clean install -Plocal-db`, all 14 modules, exit 0).

## The third In Review gate, and what it holds open

The gate re-verified the delivery end to end and found the emit, the tiered coverage and the
root-versus-child doc scoping sound. `mvn install -Plocal-db` is green (all 14 modules, exit 0).
Two findings block, both cheap.

### The `@asConnection` sentence now over-quantifies in the other direction

The rewritten sentence at `docs/manual/how-to/batch-lookups.adoc:15` reads "rejected by the
classifier (`@asConnection on @lookupKey fields is invalid`), on root and child fields alike". The
rejection is indeed universal, but the attributed component and the quoted message are child-only.
Measured, not reasoned about: a root field
`filmById(film_id: [Int!]! @lookupKey, first: Int, after: String): FilmConnection @asConnection`
classifies to `UnclassifiedField` with the reason **`@lookupKey requires a @table-annotated return
type`**, emitted by `LookupKeyDirectiveResolver.resolveAtRoot`, which never inspects the wrapper.
The quoted string comes from `resolveAtChild`
(`LookupKeyDirectiveResolver.java:59`) and only a child coordinate reaches it. The validator's
`lookup fields must not return a connection`
(`GraphitronSchemaValidator.java:810`) is a third message again, and appears unreachable from real
root SDL, since the classifier rejects first on the non-`@table` connection type;
`RootLookupValidationTest`'s `CONNECTION_RETURN` cell reaches it only through a hand-built model.

This is the same species the second gate blocked on, a claim quantified over both arms that
describes one, introduced by the pass whose deliverable was to stop doing that. It is milder than
that one: the consumer-facing advice ("don't combine them"; "use `@splitQuery` + `@asConnection`
for a paginated child list") is correct, and the `@splitQuery` + `@asConnection` remedy is real,
pinned by `GraphitronSchemaBuilderTest`'s `Store.customers` cell. So no consumer is pushed toward a
wrong edit. But a reader who hits the root error and searches the manual for the quoted string
finds nothing that matches, and the sentence names a mechanism that does not fire at the arm it
claims.

**Suggested fix.** State the rejection as universal and stop attributing one message to both arms:
name the child message where it applies, and say that a root field is rejected earlier, on the
`@table` invariant, because the promoted connection type is not `@table`-annotated. Keep the
`@splitQuery` + `@asConnection` remedy as written; it verified clean.

### The pass added code-string body assertions, which the ban does not permit

`RootLauncherRendererTest:303-305` asserts on `render(row).code().toString()`:

    .contains("selectFields.add(input.field(\"idx\").as(\"__idx__\"))")
    .contains("return scatterLookupByIdx(flat, rows.length)")
    .doesNotContain(".orderBy(")

`development-principles.adoc:271` bans code-string assertions on generated method bodies "at every
tier", and records the ban as review-enforced at test-review time, which is this gate. The body's
recorded defence does not hold: `testing.adoc:75-82` calls renderer arm tests the preferred home
for per-arm *structural* assertions, and structural assertions on a `MethodSpec` are not
code-string matching on its rendered body. The neighbouring tier paragraphs
(`testing.adoc:63`, `:95`) ban the practice by name, and the renderer-arm paragraph never lifts it.
So the two documents do not actually conflict here, and there is nothing for this item to decline
to settle.

What makes the remedy cheap is that all three assertions are redundant. Each was checked against
its sanctioned-tier twin and every one is covered:

- the `__idx__` projection and the absent `ORDER BY` are pinned verbatim by
  `RootLauncherSqlBaselineTest.lookupRoot_valuesJoinKeyedAndIdxProjectedForTheScatter`, whose
  baseline SQL is `select … "languagebykeyinput"."idx" as "__idx__" from …` with no `ORDER BY`;
- the scatter's behaviour is pinned by `ScatterLookupByIdxTest`, which invokes the emitted helper
  reflectively and asserts slots, nulls, out-of-order rows and the first-wins tie-break;
- the end-to-end contract is pinned by
  `GraphQLQueryTest.languageByKey_repeatedAndUnorderedKeys_areNeverDeduplicatedOrReordered`
  (`[2, 1, 2, 99, 1]` → `2, 1, 2, null, 1`).

**Suggested fix.** Delete the three assertions; the `.as(...)` description that explains the scatter
can stay on the surviving assertions. Nothing is lost. The file's other ~30 such assertions are
pre-existing and out of scope here, filed separately as `roadmap/renderer-test-code-string-sweep.md`
(R669). Honest limitation on this finding: this branch's history is squashed below `0bf512d`, so the
pre-item diff of that file is not recoverable; the three assertions name mechanisms this item
introduced, which is what places them with this item.

### Two non-blocking notes

- `RootLauncherRendererTest`'s test method is still named
  `keyedLookupSource_valuesJoinInputOrderedAndTheEmptyInputShortCircuit` while asserting
  `.doesNotContain(".orderBy(")`. This is the same stale-name species the second gate's first note
  fixed on the SQL baseline, and its sibling was missed. Defensible as written, since the launcher
  does still deliver input order, just not by ordering; rename if the assertions move anyway.
- The root diagnostic for `@lookupKey` + `@asConnection` is poor on its own terms: the author gets
  "requires a `@table`-annotated return type" pointing at a connection type they never wrote by
  hand. Not this item's contract; filed as `roadmap/root-lookup-connection-diagnostic.md` (R670).

### Reviewed clean, for the record

The record below is the *second* gate's. The third gate re-ran both checks and confirms the
user-facing-doc and retirement-sweep lines; its code-string paragraph is superseded by the blocking
finding above, which reads `testing.adoc` differently and finds no document conflict to decline.

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
