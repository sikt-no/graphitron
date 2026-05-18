---
id: R177
title: Child @service rows-method preserves specific XRecord type for TableBoundReturnType
status: Spec
bucket: bug
depends-on: []
created: 2026-05-18
last-updated: 2026-05-18
---

# Child @service rows-method preserves specific XRecord type for TableBoundReturnType

> `ChildField.ServiceTableField`'s component type already guarantees a
> narrow `ReturnTypeRef.TableBoundReturnType` (ChildField.java:571), and the
> source-side typing pipeline already threads the specific typed
> `TableRecord` class through `SourceKey.Wrap.TableRecord` (SourceKey.java:91,
> consumed at GeneratorUtils.java:419). The target side throws that
> classifier guarantee away: the rows-method's `V` and the matching
> `DataLoader<K, V>` value type are both widened to raw `org.jooq.Record`
> at emit time, so developer `@service` methods are forced to declare
> `Map<KeyRecord, Record>` instead of the `Map<KeyRecord, XRecord>` jOOQ
> naturally produces. Author code that types the result narrowly is
> rejected with an "Author error: must return `Map<KeyRecord, Record>` …
> got `Map<KeyRecord, FooRecord>`" diagnostic, and Java generics
> invariance means the developer cannot widen by assignment. R177 removes
> the emit-site widening so the target side honors the same narrow
> component type the classifier already produces, matching the source
> side.

## The two paired emit sites and the load-bearing classifier check

The widening is bound by the `service-directive-resolver-strict-child-service-return`
`LoadBearingClassifierCheck`:

- **Validator producer:** `ServiceDirectiveResolver.validateChildServiceReturnType`
  (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/ServiceDirectiveResolver.java:372-391),
  which calls `RowsMethodShape.strictPerKeyType` and rejects developer
  methods that don't match.
- **Helper:** `RowsMethodShape.strictPerKeyType`
  (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/model/RowsMethodShape.java:56),
  which returns the raw `org.jooq.Record` constant for
  `TableBoundReturnType`. The docstring at line 46 ("`TableBoundReturnType`
  → raw `org.jooq.Record`") is currently a normative statement; updating
  it is part of R177.

The check has two emit-site consumers on the same line of
`TypeFetcherGenerator` (graphitron-rewrite/graphitron/src/main/java/no/sikt/graphitron/rewrite/generators/TypeFetcherGenerator.java:367-369):

1. `buildServiceRowsMethod(..., RECORD, ...)`: the rows method's
   `Map<K, V>` return type.
2. `buildServiceDataFetcher(..., RECORD, ...)`: the DataLoader-typing
   line.

Both consumers receive the same `perKeyType` and must move together.

## Why both lines must move together (not just the rows method)

Tracing the data fetcher's flow:

- `buildServiceDataFetcher` at TypeFetcherGenerator.java:4262 computes
  `valueType = isList ? List<perKeyType> : perKeyType`.
- That `valueType` becomes `DataLoader<K, valueType>`'s `V` parameter at
  DataLoaderFetcherEmitter.java:105
  (`loaderType = ParameterizedTypeName.get(DATA_LOADER, keyType, loaderValueType)`).
- The BatchLoader lambda is built from the same rows-method name,
  returning `CompletableFuture.completedFuture(rowsXxx(keys, dfe))`
  (RowsMethodCall.java:71).
- `newMappedDataLoader` resolves overloads against the lambda's return
  type, which is the rows-method's declared return.

If the rows method moves to `Map<K, FooRecord>` but the data fetcher
keeps `RECORD`, the generated source declares
`DataLoader<K, Record>` and tries to populate it from
`Map<K, FooRecord>`. Generics are invariant in exactly the way R177
cites as the motivation: the *generated* source then fails to compile.
The `(Record) env.getSource()` casts in `FetcherEmitter`
(lines 124, 127, 147, 150, 163) are narrowing assignments from the
loader's `V`; a narrower `V` only strengthens what they can rely on.
The cast itself is structurally a graphql-java boundary, not a contract
with the loader.

So both lines on TypeFetcherGenerator.java:367-369 thread
`tb.table().recordClass()` through; there is no "data-fetcher side keeps
raw `Record`" option that doesn't break the emitted compile.

## What the change is

1. `RowsMethodShape.strictPerKeyType` returns `tb.table().recordClass()`
   for the `TableBoundReturnType` case (was: the `RECORD` constant). In
   the same commit, update the line-46 docstring from
   `TableBoundReturnType → raw org.jooq.Record` to
   `TableBoundReturnType → tb.table().recordClass()` (the jOOQ-generated
   record class). Splitting the docstring update into a follow-up commit
   leaves a false invariant on the line; bundle them.
2. `TypeFetcherGenerator` line 368 passes `tb.table().recordClass()` as
   the `perKeyType` argument to `buildServiceDataFetcher` (was:
   `RECORD`).
3. `TypeFetcherGenerator` line 369 passes the same record class as the
   `perKeyType` argument to `buildServiceRowsMethod` (was: `RECORD`).
4. Update the `@LoadBearingClassifierCheck` description on
   `validateChildServiceReturnType` to name the DataLoader-typing line
   alongside the rows-method `.returns(...)` line: post-R177 the
   strict-`TypeName.equals` rejection is the *only* thing keeping the
   emitted DataLoader-typing line buildable, not just a structural-
   symmetry nicety. Update the existing `@DependsOnClassifierCheck` at
   TypeFetcherGenerator.java:4302-4310 (the rows-method emitter) to
   reflect the post-R177 wording, *and* attach a new
   `@DependsOnClassifierCheck(key = "service-directive-resolver-strict-child-service-return", reliesOn = …)`
   to `buildServiceDataFetcher` whose `reliesOn` names the
   `DataLoader<K, FooRecord>` typing built in
   `DataLoaderFetcherEmitter.java:105` and the `newMappedDataLoader`
   overload resolution against the rows-method's lambda. Post-R177 the
   data-fetcher site is the more load-bearing of the two consumers and
   today carries no annotation linking it to the classifier check.

## What R177 does *not* change

- `ChildField.ServiceRecordField`'s `elementType()` path
  (ChildField.java:633), which recovers V from a
  `method().returnType()` fallback. The fallback exists because
  `RowsMethodShape.strictPerKeyType` can return null for that variant
  (`ResultReturnType` with unresolved `fqClassName`, custom scalar,
  enum), and `ServiceRecordField` carries the broad `ReturnTypeRef`
  sealed root rather than `TableBoundReturnType`. Post-R177 the
  `TableBoundReturnType` arm of `strictPerKeyType` never returns null,
  so `ServiceTableField` correspondingly does not need an
  `elementType()` accessor or a fallback. R177 does not copy-paste from
  `ServiceRecordField` because the asymmetry is principled, not
  accidental.
- The `SourceKey.Wrap.TableRecord` source-side typing pipeline. R177
  brings the target-side typing into alignment with what the source
  side already does, but the source side itself is unchanged.
- The rows-method's outer container shape (`Map` vs `List`, single vs
  list cardinality). `outerRowsReturnType` continues to wrap whatever
  `perKey` it's handed; only `perKey`'s value changes.

## Reference example (downstream consumer report)

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

## Test plan

- **Unit tier:** `TypeFetcherGeneratorTest.java:324-325, 357-359, 363-365`
  currently has string-pinned assertions on `java.util.List<org.jooq.Record>`
  / `Map<…, Record>`-shaped emit; flip those to the specific record class
  for the `ServiceTableField` case. Do not grow new unit assertions; the
  existing ones already cover the structural axis.
- **Compile tier (regression backstop):** add one child-`@service`-with-
  `TableBoundReturnType` fixture in `graphitron-sakila-example` so
  `mvn compile -pl :graphitron-sakila-example` becomes the load-bearing
  guarantee. There is no Sakila or rewrite production fixture exploiting
  the raw-`Record` widening today (audit: no `Map<KRecord, Record>`
  signatures across `graphitron-sakila-service` or the rewrite test
  tree), so adding a positive fixture costs nothing and locks the
  emit-site contract via `javac` rather than string-matching.
- **Validator-rejection test:** add a `ValidatorDiagnosticsTest` /
  sibling pinning that flips the migration case and pins the
  cross-record case as a regression backstop. These are two
  architecturally different rejections that share a diagnostic wording;
  keep them as separate test cases so a future regression points at the
  right axis:
  - *Acceptance arm (was-rejected/now-accepted):* `Map<K, FooRecord>`
    where `FooRecord = tb.table().recordClass()` is the load-bearing
    case R177 flips. Must be accepted post-R177.
  - *Migration arm (was-accepted/now-rejected):* `Map<K, Record>` (raw
    jOOQ Record) was accepted pre-R177 and must be rejected post-R177
    with the new expected-type name in the diagnostic. This is the
    load-bearing rejection test for R177.
  - *Cross-record regression arm (was-rejected/stays-rejected):*
    `Map<K, BarRecord>` where `BarRecord` is the wrong record class.
    Already rejected by the same `TypeName.equals`; pin to lock the
    diagnostic-wording change without re-litigating the axis.

## Fixture-sweep finding

The architect audit (read-only, 2026-05-18) found **no production
fixture** in `graphitron-sakila-service` or under
`graphitron-rewrite/**/test` that exploits the raw-`Record` widening: all
existing child-`@service` Sakila fixtures return scalar `String`. The
reference example in this item is a downstream-consumer report, not a
Sakila fixture. Consequence: R177 has no migration cost on the
fixtures, only the unit-bookkeeping update enumerated above.

## Principles alignment

- **Narrow component types over broad interfaces.** R177 is the
  canonical application of this principle: the classifier already
  guarantees the narrow `TableBoundReturnType` component on this branch;
  the emitter's widening to `Record` was throwing that guarantee away.
- **Classifier guarantees shape emitter assumptions.** The
  `service-directive-resolver-strict-child-service-return` annotation
  pair becomes more truthful, not just descriptively updated: the
  "no-defensive-cast, no-wildcard" claim moves from vacuously true to
  load-bearing for the DataLoader-typing line.
- **Structural symmetry of `strictPerKeyType` arms.** Post-R177 the
  `TableBoundReturnType` arm reads its answer off
  `tb.table().recordClass()` and the `ResultReturnType` arm reads its
  answer off `r.fqClassName()`; every non-null branch now reads
  directly off the classifier-resolved carrier, none synthesise
  constants. R177 is a regression-toward-the-classifier-resolved shape,
  not just a widening fix.
- **Sealed hierarchies / sub-taxonomies for resolution outcomes.** No
  new variants; the change consumes an existing narrow component.
- **No defensive casts in emitted code.** Strengthened; see above.
