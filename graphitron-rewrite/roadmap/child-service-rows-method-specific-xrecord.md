---
id: R177
title: "Child @service rows-method preserves specific XRecord type for TableBoundReturnType"
status: Backlog
bucket: bug
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Child @service rows-method preserves specific XRecord type for TableBoundReturnType

> A child `@service` field whose SDL return type is backed by a jOOQ table
> (a `TableBoundReturnType`) forces the developer's rows-batching service
> method to widen its result map's value type to raw `org.jooq.Record`,
> erasing the specific `XRecord` the service actually returns. Author code
> that naturally types `Map<KeyRecord, FooRecord>` is rejected with an
> "Author error: must return `Map<KeyRecord, Record>` … got
> `Map<KeyRecord, FooRecord>`" diagnostic. The widening is purely
> generator-imposed (Java generics are invariant, so the developer can't
> simply assign without an unsafe cast), buys nothing at runtime, and
> hides the type information jOOQ already carries.

The strictness lives in two paired sites that must move together:

- `RowsMethodShape.strictPerKeyType` (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RowsMethodShape.java:56) returns the raw
  `org.jooq.Record` constant for `TableBoundReturnType`.
- `TypeFetcherGenerator` (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:369) passes the same raw `RECORD` constant
  as `perKeyType` into `buildServiceRowsMethod` for the
  `ChildField.ServiceTableField` branch, so the generated rows method's
  declared return type matches what the validator demands.

The classifier check
`service-directive-resolver-strict-child-service-return` pins the two
together; the `ServiceRecordField` branch already threads the specific
element type through (`srf.elementType()`), so the asymmetry is just
between the `Table-` and `Record-` arms.

The fix is to thread the table's `recordClass()` through both sites for
the `TableBoundReturnType` arm: `strictPerKeyType` returns
`tb.table().recordClass()` instead of `RECORD`, and the
`ServiceTableField` case in `TypeFetcherGenerator` passes that same
record class instead of the constant `RECORD`. The
`buildServiceDataFetcher` call on the same line uses `RECORD` for a
different purpose (the fetcher's downcast container) and may need to
stay or move depending on what the wrap-side cast can tolerate; the Spec
should confirm by walking `buildServiceDataFetcher` and the generated
code's downstream consumers (env-source casts, container-element
unwraps).

## Reference example

```graphql
vitnemalUtregning: VitnemalUtregning
    @splitQuery
    @service(service: {
        className: "no.sikt.fs.opptak.saksbehandling.VitnemalUtregningService"
        method: "vitnemalUtregningForPoeng"
    })
    @override(from: "admissio")
```

```java
public Map<SakPoengRecord, VitnemalUtregningRecord> vitnemalUtregningForPoeng(Set<SakPoengRecord> keys)
```

Currently rejected with:

```
Author error: Field 'Poeng.vitnemalUtregning': method 'vitnemalUtregningForPoeng'
in class 'no.sikt.fs.opptak.saksbehandling.VitnemalUtregningService' must return
'Map<SakPoengRecord, Record>' to match the field's declared return type — got
'Map<SakPoengRecord, VitnemalUtregningRecord>'
```

## Spec checklist

- Confirm whether the `ChildField.ServiceTableField` branch's
  `buildServiceDataFetcher` call (same line as the rows method) can
  thread the specific record class as well, or whether the data-fetcher
  side has a separate reason to want raw `Record` (env-source casts,
  variance against wrapping containers).
- Add a generator-correctness test that pins the new emitted shape
  `Map<KeyRecord, FooRecord>` (sibling to whatever currently pins
  `Map<KeyRecord, Record>`), and a validator-rejection test that flips
  to accept the specific-record case (covers the new
  `LoadBearingClassifierCheck` description text too).
- Sweep for fixture SDL/service pairs that exploit the raw-`Record`
  widening (any service today returning `Map<K, Record>` whose values
  are heterogeneous record classes); those would now fail under the
  stricter shape, so the Spec must rule on whether to keep the looser
  shape behind an opt-out or to migrate the fixtures.
