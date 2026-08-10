---
id: R613
title: "Generated column filters compose beside the lookup VALUES join"
status: In Review
bucket: feature
priority: 1
theme: legacy-migration
depends-on: []
created: 2026-08-08
last-updated: 2026-08-09
---

# Generated column filters compose beside the lookup VALUES join

A lookup coordinate that also carries a non-key filterable argument is rejected at validate time:
`generated column filters on a lookup coordinate are not emitted: lookup keys ride the VALUES join
and no emitter renders a generated column predicate for a lookup field; use an authored @condition
method, or drop the filter` (`GraphitronSchemaValidator.validateConditionEmitImplemented`, with
`ConditionCommands.requireNoGeneratedFilterOnLookup` as the producer backstop). The mitigation the
message offers is not good enough for the consumer that hit it: the main migrating subgraph relies
on generated conditions beside its lookup keys, and rewriting each one as an authored `@condition`
method is a per-coordinate hand-write of a predicate the generator already knows how to build. The
guard is not a design position anyone took, it is an unimplemented-emit deferral that R563 carried
forward unchanged (its non-goals forbid enabling what a leaf's absence rejected, so slice 6a
re-grained the guard onto the fact and deliberately left the emit alone).

## What legacy did (v9 tip, `74f0bb6c1`; latest legacy tag is v9.3.0, there is no v9.3.1)

Legacy supports the construct, and supports it by not distinguishing the two argument kinds at all.
The fixture is `queries/fetch/lookup/otherNonKeyField`, one `@lookupKey` argument and one plain
`@field`-bound argument on the same coordinate. Generated output, reproduced by running legacy's
`MapOnlyFetchDBClassGenerator` over that schema:

```java
public static Map<String, CustomerTable> queryForQuery(DSLContext _iv_ctx,
        List<String> _mi_firstName, List<String> _mi_lastName, SelectionSet _iv_select) {
    var _a_customer = CUSTOMER.as("customer_2168032777");
    return _iv_ctx
            .select(_a_customer.FIRST_NAME, queryForQuery_customerTable(_mi_firstName, _mi_lastName))
            .from(_a_customer)
            .where(_mi_firstName != null && _mi_firstName.size() > 0 ? _a_customer.FIRST_NAME.in(_mi_firstName) : DSL.noCondition())
            .and(_mi_lastName != null && _mi_lastName.size() > 0 ? _a_customer.LAST_NAME.in(_mi_lastName) : DSL.noCondition())
            .fetchMap(Record2::value1, Record2::value2);
}
```

Legacy has no VALUES join. A lookup there is three things: project the key column as the map key,
restrict with an ordinary `IN` over the key list, and `fetchMap`. The key predicate comes out of the
same argument-to-condition machinery as every other column-backed argument, so the mixed case
needed no special support and got none. Two consequences of that shape are worth naming because
they are exactly what the rewrite's VALUES join buys: a multi-key lookup builds its map key by
inlined string concatenation with a `,` separator (`DSL.concat(DSL.inlined(FIRST_NAME),
DSL.inline(","), DSL.inlined(LAST_NAME))`, so a key value containing a comma is ambiguous), and an
empty or null key list degrades to `DSL.noCondition()`, which returns every row rather than none.
The rewrite's row-typed VALUES join fixes both and additionally preserves input order and
per-key misses. So the migration target is legacy's capability, not legacy's mechanism.

## Spike: the emit already works (measured 2026-08-08)

The rejection is stale. Every emitter this shape reaches already composes the generated predicate
correctly, and the two guards are the only thing standing between the schema and working output.
Measured by making both guards conditional on a system property, then generating and running:

- **Root lookup.** `RootLauncherRenderer`'s `KeyedLookup` arm declares the `condition` local from
  the row's glue call and `lookupBody` renders `.where(condition)`, so the generated predicate lands
  in the WHERE beside the VALUES join with no renderer change.
- **Inline child lookup.** The `LookupMultiset` projection arm folds
  `.and(FilmConditions.actorsCondition(a1, sf.getArguments()))` into the inner select's WHERE,
  beside the FK correlation and the join to the input rows.
- **Batched child lookup.** The `@splitQuery` loader folds the same glue call into its
  `DSL.noCondition().and(...)` WHERE, against the child alias.
- **It compiles and runs.** A sakila fixture (`languagesByKeyGenerated`, the generated-filter twin
  of the existing `languagesByKeyFiltered`) generates, compiles at release 17, and executes against
  PostgreSQL as
  `select "public"."language"."name" from "public"."language" join (values (0, ?), (1, ?)) as
  "languagesbykeygeneratedinput" ("idx", "language_id") using ("language_id") where
  "public"."language"."name" = ? order by "languagesbykeygeneratedinput"."idx"`.
- **Nothing else depends on the rejection.** The generator suite runs 3246 tests with both guards
  lifted and fails 3, all of them `RootLookupValidationTest` enum rows that pin the rejection text
  itself (`COLUMN_ARG_DEFERRED`, `LIST_COLUMN_ARG_DEFERRED`, `SINGLE_RETURN_LIST_ARG`).

Why it already works: the lookup key argument is excluded from `GeneratedConditionFilter` at build
time (`FieldBuilder`, the `!ca.isLookupKey()` branch), so the generated filter on a lookup
coordinate is composed purely of the *other* filterable arguments and never re-states the key; the
condition row is minted for lookup coordinates today, which is why authored `@condition` is
supported; and `ConditionGlueRenderer` renders the authored and generated predicate arms into the
same method body against the same table local. The guard predates that convergence. The comment on
the pinned validator cases said as much, that they "flip back to valid when the lookup fold
converges onto glue"; the fold converged and the guard was never revisited.

So this is a pin-and-delete item, not an emit item: delete
`ConditionCommands.requireNoGeneratedFilterOnLookup` and the lookup arm of
`GraphitronSchemaValidator.validateConditionEmitImplemented`, re-point the three
`RootLookupValidationTest` rows from rejected to valid, and land fixtures that pin the three emit
shapes so the capability cannot regress silently.

## Plan

One slice, two commits. The capability commit is small enough to review as a unit and the
acceptance is the fixture coverage, not the deletion.

### Commit 1: the shape becomes legal, pinned at every tier

*Delete the two guards.* `ConditionCommands.requireNoGeneratedFilterOnLookup` and its call site go,
along with the `lookup` boolean that exists only to reach it and the class javadoc's mention of the
backstop (the sibling interface-child backstop stays, so the javadoc keeps one of its two clauses).
In `GraphitronSchemaValidator.validateConditionEmitImplemented`, the lookup arm and its `<li>` in
the method javadoc go; the method survives for the interface-child deferral.

*Re-grain the lookup cardinality read, which is the same stale guard one method over.*
`GraphitronSchemaValidator.validateRootLookup` decides `anyKeyIsList` by OR-ing
`LookupMapping.ColumnMapping.hasListArg()` with the list-ness of any `GeneratedConditionFilter`
body param, under a comment saying "list-ness may come from `LookupMapping` or from a
filter-carried list arg, and both paths are covered". That second disjunct is a pre-convergence
leftover from the same era as the guard this item deletes: since lookup keys are excluded from
`GeneratedConditionFilter` upstream, it can now only read a *non-key* argument's list-ness, splicing
the filter axis into a fact that belongs to the key axis alone. It is inert today only because the
guard makes the co-presence unreachable. Delete the guard without touching it and it becomes an
active false rejection. Measured:
`filmById(film_id: Int @lookupKey @field(name: "film_id"), title: [String] @field(name: "title")): Film`
currently reports *both* the deferral and "result type does not match input cardinality"; remove the
first and the second survives alone against a shape that emits fine.

So `anyKeyIsList` collapses to `keyed.mapping().hasListArg()`, making the mapping the single
asserted key-cardinality fact. `LookupMapping.ColumnMapping.hasListArg()` currently has exactly one
reader, this one, and its javadoc says it "drives the row-count loop in the emitter", so the
accessor is not being trusted as the source of the fact it names. This is the re-grain the item
owes; the guard deletion is the easy half.

*Re-point the validator rows.* `RootLookupValidationTest` has three rows expecting the rejection.
`COLUMN_ARG_DEFERRED` and `LIST_COLUMN_ARG_DEFERRED` become valid cases and are renamed off the
`_DEFERRED` suffix; their prose loses the "no emitter" claim. `SINGLE_RETURN_LIST_ARG` does *not*
merely drop the deferral string: it is the shape the co-read above wrongly rejects, so it flips to
valid, or is re-pointed to pin the opposite invariant (a scalar key with a list non-key filter is
legal on a single return). The lookup rows then become exhaustive over the small cube that now
matters, key list-ness x return list-ness x non-key-filter list-ness, rather than an incidental
sample. The pinned comment above the pair, which predicted this flip ("these two pin the rejected
state, and flip back to valid when the lookup fold converges onto glue"), is replaced by the fact
rather than deleted.

*Pin the three emit shapes.* One sakila coordinate per shape, each a generated-filter twin of an
existing lookup fixture so the diff reads as a pair:

* Root: `languagesByKeyGenerated`, twin of `languagesByKeyFiltered`. The two together are the point,
  the authored and the generated filter composing the same way beside the same VALUES join.
* Inline child: a sibling coordinate beside `Film.actors` rather than an added argument on it. The
  sibling is additive, matches the convention already in that region of the schema
  (`actorsByKey` / `actorsByKeyViaJunctionCondition` are sibling twins), and keeps the
  byte-identical-emit acceptance true by construction rather than by inspection.
* Batched child: the same, beside `Film.actorsBySplitLookup`.

Three fixtures is the right grain, not bloat: these are three genuinely different emitters
(`RootLauncherRenderer.lookupBody`, `ProjectionUnitRenderer.lookupInnerSelect`, and the batched
loader's correlated-lookup arm), and row content is the contract, which lands them at execution.
Weighting, though, is one statement baseline plus a row assertion for the *root* shape only; the two
child shapes assert row content and the presence of the glue call, not whole-SQL text. A full
statement baseline is the closest legal relative of a banned code-string assertion and churns on any
unrelated aliasing change, and all three arms mint runtime-prefixed aliases.

The row assertion is where the one behaviourally novel consequence of this item lives, so name it
rather than leaving it as "a result assertion": a key that matches a row but fails the non-key
predicate now yields `null` at its output index, indistinguishable at the wire from an unmatched
key. Assert keys `[hit, hit-but-filtered, miss]` returning three positions in input order with
`null` at indices 1 and 2. Nothing else in the item changes behaviour; this does.

*Pin the key-not-restated fact at the model, not in SQL text.* Several sakila lookups carry both
directives (`language_id: [Int] @lookupKey @field(name: "language_id")`), and the root fixture is
one of them. The fact is that `FieldBuilder`'s `!ca.isLookupKey()` exclusion holds, and that is a
model fact: the row's `Predicate.Generated` terms contain no lookup-key column. Assert it in
`ConditionCommandsPipelineTest` beside the generated twin of
`lookupCoordinate_authoredFilterProducesARow`, where it also survives an emitter refactor, rather
than by counting placeholders in a SQL string.

### The neighbourhood, measured rather than assumed

The spike proved the happy path. Three adjacent shapes were then measured specifically to find out
whether legalising the coordinate opens anything the plan does not pin. All three are settled and
none needs a fixture:

* **Generated filter reaching a joined table.** A non-key argument carrying `@reference` on a lookup
  coordinate renders its correlated `EXISTS` inside the same glue method and lands in the launcher's
  WHERE beside the VALUES join. Composes; no additional work. Worth one fixture only if the
  migrating subgraph turns out to carry the shape, which is a question for the implementer to ask
  rather than assume.
* **`@lookupKey` on the argument of a `@table` input type.** Every leaf of that input becomes a key
  and rides the VALUES join (`Row4`, `using(FILM_ID, ACTOR_ID, LAST_UPDATE)` when a third field is
  added). There is no non-key sibling to filter on, so the shape mints no generated filter at all
  and this item does not reach it.
* **`@lookupKey` on individual input fields, with plain siblings.** Rejected today on the Query side
  with "move `@lookupKey` to the surrounding ARGUMENT_DEFINITION instead", so the mixed per-field
  shape is unreachable for an unrelated reason.

The last two turned up a documentation defect that is *not* this item's to fix but should not be
lost: `lookupKey.adoc`'s Constraints list claims "`@lookupKey` on an individual input field applies
only to that field; the rest of the input behaves normally", which the Query-side rejection
contradicts. File it separately rather than widening this item.

### Commit 2: docs

The manual never documented the rejection, so there is nothing to retract, but several pages state
things this change makes incomplete:

* `docs/manual/reference/directives/lookupKey.adoc` says nothing about non-key arguments on a lookup
  coordinate. Its Constraints list gains the positive statement: non-key filterable arguments
  compose as ordinary predicates in the WHERE beside the lookup join.
* The "`@lookupKey` is exempt from the implicit-predicate path" phrasing is true of the *key
  argument* and misleading as a statement about the coordinate. It appears in
  `docs/manual/how-to/add-custom-conditions.adoc` and `docs/manual/how-to/condition-cascade.adoc`,
  and `docs/manual/how-to/batch-lookups.adoc` points at the latter as "the `@lookupKey` exemption".
  Narrow all three to the argument. The already-correctly-scoped statements elsewhere on those two
  how-to pages need no change.

Two facts join `lookupKey.adoc` in the same paragraph, because a migrating consumer is exactly the
audience for both: an empty or absent key list returns no rows, where the legacy generator degraded
to an unfiltered read; and a key that matches a row but fails a non-key predicate yields `null` at
its output index, which the page currently frames as meaning "unmatched position" only.

## Acceptance

* The three emit shapes generate, compile at release 17, and execute against PostgreSQL. Root
  carries a statement baseline; all three carry the `[hit, hit-but-filtered, miss]` row assertion.
* `RootLookupValidationTest`'s lookup rows are exhaustive over key list-ness x return list-ness x
  non-key-filter list-ness, and the cardinality read has one source (`hasListArg()`).
* Emit for every existing coordinate is byte-identical, evidenced by the sakila regeneration diff.
  This is the structural acceptance; it is strictly stronger than a suite-wide pass count and,
  unlike one, it does not rot as tests are added. The spike's "3246 tests, 3 failures" measurement
  stays in the Spike section as evidence about *existing* coverage, which is all it can be: a
  change that legalises a shape cannot be measured by a fixture set the guard prevented from
  existing. That blind spot is not hypothetical, it is exactly why the cardinality co-read above
  went unnoticed in the spike (the measured root fixture's key is already list-typed, so the
  co-read was invisible in its SQL).

## Implementation notes

Four divergences from the plan, all measured rather than chosen:

*The row assertion was written twice, because the behaviour it names was missing and then supplied.*
The plan asked for "three positions in input order with `null` at indices 1 and 2". Measured against
the tree at implementation time, the root lookup returned `.fetch()` straight from the join with no
scatter step, so a key matching no row contributed no element and `[hit, hit-but-filtered, miss]`
yielded a one-element list. The assertion first landed in that measured shape, with the plan's
claim recorded as inaccurate.

*It was the plan that was right, and the generator that was wrong.* The positional reading was not
the plan author's invention: `lookupKey.adoc` and `batch-lookups.adoc` both assert it, the latter
under a section titled "The positional contract", and `@asConnection` is rejected on lookup fields
citing that contract as the reason. Told that a lookup must return one slot per key, the user
confirmed the documented contract is the intended one, so the emit grew the scatter it had always
been documented to have. That work is its own item, since it reaches every lookup shape rather than
only the filtered one and carries a consumer-visible schema requirement. With it in place this
item's assertion says what the plan asked for: three slots, `null` at indices 1 and 2, the filtered
key indistinguishable from the missed one.

*The validator rows became the full cube rather than three re-pointed rows.* The plan left
`SINGLE_RETURN_LIST_ARG` with two options. Landing all twelve cells of key list-ness x return
list-ness x non-key-filter list-ness is what makes the acceptance criterion checkable by reading,
and it is what pins the re-grain: two cells (scalar key, list return, list filter; list key, single
return, list filter) are exactly where the deleted co-read used to flip the verdict.

*One unrelated pin moved.* `FixtureWarningsGateTest` asserts the source line of an unrelated
warning; the schema fixtures inserted above it shifted the line from 327 to 335.

## Retired vocabulary

Declared per `roadmap/workflow.adoc` § Item file conventions; the Done-gate reviewer greps prose
surfaces (javadoc, comments, `.adoc`, fixture prose, test names) for these. Finalise the list at
the Done gate if implementation retires more.

- `requireNoGeneratedFilterOnLookup` (the `ConditionCommands` backstop, its call-site `lookup`
  boolean, and the class javadoc's lookup clause).
- The deferral claim in any phrasing: "generated column filters on a lookup coordinate are not
  emitted", "no emitter renders a generated column predicate" (for/beside a lookup field, member,
  or coordinate), and the validator javadoc's lookup `<li>`.
- `COLUMN_ARG_DEFERRED` / `LIST_COLUMN_ARG_DEFERRED` (renamed off the `_DEFERRED` suffix) and the
  test's `GENERATED_FILTER_ON_LOOKUP` message constant, whose text is the deferral claim above.
- The prediction comment "flip back to valid when the lookup fold converges onto glue" (replaced
  by the fact, per the plan).
- The cardinality co-read and its comment: "list-ness may come from LookupMapping or from a
  filter-carried list arg, and both paths are covered" (the filter disjunct of `anyKeyIsList`).
- The coordinate-scoped reading of "`@lookupKey` is exempt from the implicit-predicate path"
  (narrowed to the argument, not deleted; the argument-scoped statement stays true).
Nothing here retires the manual's positional-null vocabulary. That claim was briefly rewritten to
measured behaviour while the emit lacked its scatter, then restored intact when the follow-up item
supplied it; the phrasing on those pages is live again and must not be swept.

## Non-goals

* **The sibling deferral stays.** Any filter on a single-table interface child coordinate remains
  rejected: `ChildField.TableInterfaceField`'s fetcher genuinely folds no filters, so that one is an
  unimplemented emit rather than a stale guard. This item must not delete the method that carries it.
* **R567's axes stay with R567.** Ordering and pagination on a lookup coordinate are the other two
  unrealized co-member payloads from the same audit. This item is the filter axis only.
* **No change to the lookup mechanism.** The VALUES join, the input ordering, and the per-key miss
  semantics are untouched; the migration target is legacy's capability, not its mechanism.
