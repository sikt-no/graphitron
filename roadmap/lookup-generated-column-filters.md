---
id: R613
title: "Generated column filters compose beside the lookup VALUES join"
status: Backlog
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

## What is left to decide at Spec

1. **Fixture coverage.** The three emit shapes each want a pin. Root wants a
   `ConditionSqlBaselineTest` SQL baseline plus an execution assertion on the returned rows (the
   spike asserted the statement, not the result set). Inline and batched child want pipeline-tier
   pins at minimum, and an execution-tier one if the sakila schema can carry the coordinate cheaply.
2. **`SINGLE_RETURN_LIST_ARG` is a compound case.** That row pins two rejections at once, a
   cardinality mismatch and the filter deferral. It needs splitting rather than flipping, so the
   surviving cardinality verdict keeps its coverage.
3. **Interaction with a `@lookupKey` that is also `@field`-bound.** Several sakila lookups carry
   both (`language_id: [Int] @lookupKey @field(name: "language_id")`). The exclusion branch keys off
   `isLookupKey`, so the key does not double as a predicate, but a fixture should pin that rather
   than leave it to reading.
4. **Scope fence.** The sibling deferral in the same validator method, any filter on a single-table
   interface child coordinate, is a genuinely unfolded filter list and stays. R567 covers the other
   unrealized lookup co-member payloads (orderBy and paginate); this item is the filter axis of the
   same audit and should not absorb them.
5. **Whether the empty-key-list semantics need stating in the user manual**, since the rewrite's
   short-circuit (empty input returns the empty result) differs from legacy's `noCondition()`
   degradation to every row, and a migrating consumer's queries may have relied on the latter.
