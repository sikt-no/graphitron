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

## What the rewrite already has

The gap may be narrower than the message claims, and the item should open with a spike that settles
it rather than assuming emit work. The lookup key argument is already excluded from
`GeneratedConditionFilter` at build time (`FieldBuilder`, the `!ca.isLookupKey()` branch), so a
generated filter on a lookup coordinate is composed purely of the *other* filterable arguments and
never re-states the key. Both lookup emitters already chain a condition beside the join:
`RootLauncherRenderer`'s `KeyedLookup` arm declares the `condition` local from the row's glue call
and `lookupBody` renders `.where(condition)`, and the child arm folds `.and(<glue>)` onto the inner
select. The condition row itself is minted for lookup coordinates today, which is why authored
`@condition` entries are supported. `ConditionGlueRenderer` renders the authored and generated
predicate arms into the same method body against the same table local, with nothing lookup-hostile
in the generated arm. On that reading the work is closer to "prove the generated arm binds and
renders correctly on a lookup coordinate, pin it with a SQL baseline and an execution test, delete
both guards" than to building new emit.

## The decision to take at Spec

1. Whether the spike confirms the above. If a fixture with a lookup key plus a generated filter
   emits correct SQL once the two guards are lifted, this is a pin-and-delete item. If it does not,
   the failure mode names the real emit work.
2. Whether root and child lookups land together. The root launcher and the `LookupMultiset`
   projection arm are separate emitters and may not be in the same state.
3. What happens to the sibling deferrals. `GraphitronSchemaValidator.validateConditionEmitImplemented`
   also rejects any filter on a single-table interface child coordinate; that one is a genuinely
   unfolded filter list and stays. R567 covers the other unrealized lookup co-member payloads
   (orderBy and paginate); this item is the filter axis of the same audit and should not absorb them.
4. Whether the empty-key-list semantics need stating in the user manual, since the rewrite's
   short-circuit (empty input returns the empty result) differs from what a legacy consumer's
   queries may have relied on.
