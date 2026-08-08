---
id: R613
title: "Generated column filters compose beside the lookup VALUES join"
status: Spec
bucket: feature
priority: 1
theme: legacy-migration
depends-on: []
created: 2026-08-08
last-updated: 2026-08-08
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

*Re-point the validator rows.* `RootLookupValidationTest` has three rows expecting the rejection.
`COLUMN_ARG_DEFERRED` and `LIST_COLUMN_ARG_DEFERRED` become valid cases and are renamed off the
`_DEFERRED` suffix; their prose loses the "no emitter" claim. `SINGLE_RETURN_LIST_ARG` is compound,
pinning a cardinality mismatch *and* the filter deferral in one expected-errors list, so it keeps
the row and drops only the deferral string. The pinned comment above the pair, which predicted this
flip ("these two pin the rejected state, and flip back to valid when the lookup fold converges onto
glue"), is replaced by the fact rather than deleted.

*Pin the three emit shapes.* One sakila coordinate per shape, each a generated-filter twin of an
existing lookup fixture so the diff reads as a pair:

* Root: `languagesByKeyGenerated`, twin of `languagesByKeyFiltered`. The two together are the point,
  the authored and the generated filter composing the same way beside the same VALUES join.
* Inline child: a non-key filterable argument beside `Film.actors`'s existing `actor_id` key, or a
  sibling coordinate if adding the argument would disturb existing baselines.
* Batched child: the same against `Film.actorsBySplitLookup`.

Coverage per shape: a `ConditionSqlBaselineTest` statement baseline (the spike's measured SQL is the
expected value for the root case) plus an execution assertion on the returned rows, which the spike
did not cover. `ConditionCommandsPipelineTest.lookupCoordinate_authoredFilterProducesARow` gains a
generated twin asserting a `Predicate.Generated` row on a lookup coordinate, so the producer-side
fact is pinned independently of the rendered SQL.

*Pin the key-is-also-`@field`-bound case.* Several sakila lookups carry both
(`language_id: [Int] @lookupKey @field(name: "language_id")`), and the root fixture above is one of
them. Assert that the emitted WHERE carries only the non-key predicate, so the exclusion branch
(`FieldBuilder`'s `!ca.isLookupKey()`) is fenced by a test rather than by reading. The spike's
measured SQL already shows this: one `?` in the WHERE, not two.

### Commit 2: docs

The manual never documented the rejection, so there is nothing to retract, but two pages state
things this change makes incomplete:

* `docs/manual/reference/directives/lookupKey.adoc` says nothing about non-key arguments on a lookup
  coordinate. Its Constraints list gains the positive statement: non-key filterable arguments
  compose as ordinary predicates in the WHERE beside the lookup join.
* `docs/manual/how-to/add-custom-conditions.adoc`'s See-also line says `@lookupKey` "is exempt from
  the implicit-predicate path". True of the *key argument*, misleading now as a statement about the
  coordinate. Narrow it to the argument.

The empty-key-list divergence from legacy belongs here too, in `lookupKey.adoc`: an empty or absent
key list returns no rows, where the legacy generator degraded to an unfiltered read. That is not a
change this item makes, but a migrating consumer reading the page is exactly the audience for it,
and this is the first time the page has reason to discuss the key list's edge cases.

## Acceptance

* The three emit shapes generate, compile at release 17, and execute against PostgreSQL, each with a
  statement baseline and a result assertion.
* `RootLookupValidationTest` is green with two rows flipped to valid and the compound row narrowed.
* No other test changes. The spike measured 3246 generator tests with both guards lifted and exactly
  those three rows failing, so any further churn in this slice means something was missed.
* Emit for every existing coordinate is byte-identical. Nothing here changes a shape that was
  already legal.

## Non-goals

* **The sibling deferral stays.** Any filter on a single-table interface child coordinate remains
  rejected: `ChildField.TableInterfaceField`'s fetcher genuinely folds no filters, so that one is an
  unimplemented emit rather than a stale guard. This item must not delete the method that carries it.
* **R567's axes stay with R567.** Ordering and pagination on a lookup coordinate are the other two
  unrealized co-member payloads from the same audit. This item is the filter axis only.
* **No change to the lookup mechanism.** The VALUES join, the input ordering, and the per-key miss
  semantics are untouched; the migration target is legacy's capability, not its mechanism.
