---
id: R74
title: "Row/Record return shapes for typed accessor batch keys"
status: Spec
bucket: architecture
priority: 7
theme: service
depends-on: []
---

# Row/Record return shapes for typed accessor batch keys

`FieldBuilder.deriveBatchKeyFromTypedAccessor` (`FieldBuilder.java:2817-2955`) currently auto-lifts a batch key from a typed zero-arg accessor on a `@record` parent's backing class only when the accessor returns `X`, `List<X>`, or `Set<X>` for some concrete `X extends org.jooq.TableRecord`. The classifier then projects each element into a `RecordN<...>` key via `__elt.into(Tables.X.PK1, ...)` in `GeneratorUtils.buildAccessorKeySingle`/`buildAccessorKeyMany` (`GeneratorUtils.java:257-316`). Schema authors whose parent backing classes already expose a `Row1<Integer>` / `Record1<Integer>` (or list/set thereof) accessor cannot reach this auto-lift: their only option is `@batchKeyLifter`, which forces a static method even when the parent class already has the right key in hand.

Structurally the contract is identical to the lifter contract enforced for `BatchKey.LifterRowKeyed`: arity equals the target table's PK arity, and per-position type-arg erasure equals each PK column's `columnClass()`. The hop is already constructed from `expectedTable.primaryKeyColumns()` (line 2919); only the classifier's element-type filter and the emitter's projection step need to extend.

## Decision: distinct sealed permits per shape, not an enum discriminator

The first-pass design (a `KeyShape` enum on the existing two permits) was rejected in conversation as a violation of three rewrite principles: "sealed hierarchies over enums for typed information" (the variants carry different data: TableRecord arms need an element class for the `__elt.into(...)` cast; Row/Record arms don't), "generation-thinking" (the enum forces every emitter that forks on shape to switch on the discriminator), and "narrow component types" (`AccessorRef.elementClass` would become semantically dead on the new arms).

The corrected design adds four new permits to `RecordParentBatchKey`, parallel in structure to the existing `AccessorKeyedSingle` / `AccessorKeyedMany` and mirroring the way `RowKeyed` / `RecordKeyed` already sit as siblings on the catalog-FK side:

```
sealed interface RecordParentBatchKey extends BatchKey
    permits RowKeyed, LifterRowKeyed,
            AccessorKeyedSingle,        // existing — TableRecord X,    projects via __elt.into(...)
            AccessorKeyedMany,          // existing — List/Set<X>,      projects via __elt.into(...)
            AccessorRowSingle,          // new      — RowN<...>,         no projection
            AccessorRowMany,            // new      — List/Set<RowN<>>,  no projection
            AccessorRecordSingle,       // new      — RecordN<...>,      no projection
            AccessorRecordMany;         // new      — List/Set<RecordN>, no projection
```

Each new permit carries `JoinStep.LiftedHop hop` plus a separate `AccessorKeyRef` (parent backing class + method name only). The narrower ref type keeps `elementClass` confined to the two TableRecord arms that actually use it.

`keyElementType()`'s switch grows two arms: `AccessorRow* -> rowNType(hop.targetColumns())`, `AccessorRecord* -> recordNType(hop.targetColumns())`. `dispatch()` and `preludeKeyColumns()` follow the same Single/Many pattern the existing arms already establish.

## Classifier

`classifyAccessorReturn` extends to recognize three element-type families:

1. `Class<?>` extending `org.jooq.TableRecord` — existing path, unchanged. Element table resolved via `svc.resolveTableByRecordClass(...)`.
2. `ParameterizedType` whose raw is `org.jooq.Row1..Row22` (or `RowN`) — new. Type-args extracted from the parameterization.
3. `ParameterizedType` whose raw is `org.jooq.Record1..Record22` (or `RecordN`) — new. Type-args extracted similarly.

For families 2 and 3 the element table cannot come from the element class (there is no record class). The field's `@table` return is the only anchor; the structural arity-and-types check then runs against `expectedTable.primaryKeyColumns()`. Mismatches surface as a new `AccessorDerivation.ShapeMismatch` arm with a message naming both sides (accessor's tuple shape vs. PK column shape).

The existing ambiguity rule (multiple matching accessors → `AccessorDerivation.Ambiguous`) generalizes naturally; the rejection message is updated to mention all three accepted shapes.

Classifier rejections to preserve:

- Raw `Row` / `Record` (no type-args) — reject as today's wildcard handling does.
- Wildcard type-args (`Row1<? extends Integer>`) — reject; only invariant element types are supported.
- Same-type-arg PK swap that an arity-and-erasure check cannot detect (e.g. `Row2<Integer, Integer>` against PK `(actor_id Integer, film_id Integer)` declared in the opposite order) — undetectable structurally; flag as a known limitation in a doc note next to the lifter contract's identical caveat.

## Emitter

`buildRecordParentKeyExtraction`'s sealed switch grows four new arms. Each is leaner than the existing two — no `__elt.into(...)`, just direct assignment:

```
RowN<...>    key = ((Backing) env.getSource()).accessor();          // AccessorRowSingle
RecordN<...> key = ((Backing) env.getSource()).accessor();          // AccessorRecordSingle
```

The list arms iterate the accessor's `Iterable` and add each element to `keys` directly, with no per-element projection. Each new emitter arm carries its own `@LoadBearingClassifierCheck` / `@DependsOnClassifierCheck` pair so `LoadBearingGuaranteeAuditTest` covers the new shape contracts.

## Validator dispatch coverage

`TypeFetcherGenerator`'s dispatch partition (`IMPLEMENTED_LEAVES` etc.) extends to include the four new arms in `IMPLEMENTED_LEAVES`. `GeneratorCoverageTest.everyGraphitronFieldLeafHasAKnownDispatchStatus` enforces exhaustive partitioning, so any missed arm fails the build.

## Acceptance criteria

- `BatchKey` adds `AccessorRowSingle`, `AccessorRowMany`, `AccessorRecordSingle`, `AccessorRecordMany` as new permits of `RecordParentBatchKey`. Each carries `JoinStep.LiftedHop` plus a new `AccessorKeyRef` (no `elementClass`). `keyElementType()`, `preludeKeyColumns()`, and `dispatch()` switches are exhaustive across all eight `RecordParentBatchKey` permits.
- `FieldBuilder.deriveBatchKeyFromTypedAccessor` produces the new variants when the parent backing class's accessor returns `Row1..Row22<...>`, `Record1..Record22<...>`, or list/set wrappers thereof, with type-args matching the field's `@table` PK by arity and per-position erasure. Mismatched shapes produce a typed `AccessorDerivation.ShapeMismatch` rejection.
- `GeneratorUtils.buildRecordParentKeyExtraction` has matching emitter arms for all four new permits; arms skip the `__elt.into(...)` projection and assign / iterate directly.
- `LoadBearingGuaranteeAuditTest` is satisfied: producer / consumer annotation pairs cover the new arms.
- Pipeline tests cover both single-PK (`film`) and composite-PK (`film_actor`) tables for all four new shape × cardinality combinations. Compilation tier (`mvn compile -pl :graphitron-sakila-example -Plocal-db`) passes with fixtures hitting each new arm.
- Classifier unit tests cover the structural rejections: arity mismatch, per-position type mismatch, raw `Row` / `Record`, wildcard type-args.
- Doc note in `BatchKey`'s class-level Javadoc (and a sibling note in the `@batchKeyLifter` section of `rewrite-design-principles.adoc`) flags the same-type PK-swap limitation that any structural-tuple contract carries.

## Roadmap entries (siblings / dependencies)

- Sibling of [R71 / `recordn-key-parity-lifter-and-non-jooq-record-parents.md`](recordn-key-parity-lifter-and-non-jooq-record-parents.md), which extends `@batchKeyLifter` to accept `RecordN` returns. Both items broaden the existing TableRecord-only auto-lift and Row-only lifter surfaces so the consumer can pick the shape they already have in hand. R74 covers the auto-lift path; R71 covers the explicit-lifter path. They are independent and can ship in either order.
- Sibling of [R61 / `emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md), which added `RecordN<...>` source-shape support alongside `RowN<...>` on the `@service` classifier path. R74 brings the same shape symmetry to the `@record`-parent accessor path.
