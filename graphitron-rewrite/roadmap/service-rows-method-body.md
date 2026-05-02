---
id: R32
title: "Implement `@service` rows-method body"
status: Spec
bucket: architecture
priority: 6
theme: service
depends-on: []
---

# Implement `@service` rows-method body

First iteration shipped at `befc156` (alongside R49 Phase A close-out): `buildServiceRowsMethod` is no longer a stub. The body walks `MethodRef.params()` via `ArgCallEmitter.buildMethodBackedCallArgs` (`Sources → keys`, `DslContext → dsl` local, `Arg`/`Context` via the existing extraction path) and shapes as `[DSLContext dsl = ...; ] return ServiceClass.method(<args>);`. Both `ServiceTableField` and `ServiceRecordField` use the same parameterised emitter; the developer's method returns the loader's expected `Map`/`List` shape directly, so no per-record projection step is needed (graphql-java's downstream wiring resolves columns off whatever records the developer returns). End-to-end exercised by `GraphQLQueryTest.films_titleUppercase_resolvesViaServiceRecordFieldDataLoader` (parent `SELECT` + one batched DataLoader round-trip). R32's remaining in-scope work is the strict return-type validation described under "Plan" below; the other three original-spec follow-ups are tracked elsewhere (R61 for the `Row1` → `Record1` switch, [`typed-context-value-registry.md`](typed-context-value-registry.md) for `ParamSource.Context` validation) or deferred (element-shape conversion until a real schema needs it). The original three-concern spec is preserved under "Original spec".

## Original spec

The spec described the body as three concerns in one emitter:

1. **Argument assembly.** Walk `MethodRef.params()` and emit each parameter:
   `ParamSource.Context` via the existing `graphitronContext(env).getContextArgument(env, name)`
   call (already typed via `<T>` inference; generate-time validation lands separately
   under [`typed-context-value-registry.md`](typed-context-value-registry.md));
   `ParamSource.Arg` via the existing field-arg pattern; `ParamSource.DslContext` via
   `graphitronContext(env).getDslContext(env)`; `ParamSource.Sources` via `keys` (with
   conversion, see below); structural `Table` / `SourceTable` pass through.
2. **Key conversion (element-shape sensitive).** The lambda hands the rows method
   `Set<RowN<...>>` / `List<RowN<...>>` (or `RecordN`). If the user's service signature
   takes `Set<TableRecord>` (or `List<TableRecord>`), convert via
   `keys.stream().map(k -> Tables.FILM.newRecord(...k.value1())).collect(...)`. Pass
   through otherwise.
3. **Projection.** Call `<TargetType>.$fields(sel.getSelectionSet(), rec, env)` on each
   returned record so the response carries only selected columns. The `$fields` helper
   is already emitted on each generated `Type`.

§1 shipped (the parameterised arg-assembly walk above). §3 turned out unnecessary: the developer's return value is handed back to graphql-java directly. §2 is the largest remaining piece of the original spec, captured under "Open follow-ups" below.

## Recovering element-shape

`BatchKey` carries container × key-shape but not element-shape (`RowN` vs `TableRecord`
vs `RecordN`); see the rationale note in
[`set-parent-keys-on-service.md`](set-parent-keys-on-service.md). Two options at
implementation time:

- **Re-reflect** via `Class.forName(MethodRef.className()).getMethod(MethodRef.methodName(), ...)`
  and inspect the SOURCES param's `getParameterizedType()`. Cheap, matches what the
  classifier already does, no model change.
- **Lift** onto `BatchKey` as an `elementShape()` enum (`ROW_N`, `RECORD_N`, `TABLE_RECORD`)
  populated at classification time. Cleaner if multiple body branches end up needing it;
  otherwise premature.

DataLoader keys stay `RowN` / `RecordN` regardless of element-shape: those are thin
tuples with cheap hashing/equality, while `TableRecord` carries dirty-flag state that's
wrong for cache-key use. Element-shape is purely a conversion concern at the
service-call boundary.

## Open follow-ups

- **Element-shape conversion (R32 §2 in the original spec).** The `keys` parameter passes
  through directly today. When the developer's signature uses `Set<TableRecord>` /
  `List<TableRecord>` (which classifies as the same `Mapped/RowKeyed` BatchKey variant as
  `Set<RowN>` / `List<RowN>`), the framework's emitted `Row1<...>` keys flow into the dev's
  method as the wrong static type. Conversion needs re-reflection on the SOURCES param's
  actual generic type at emission time; deferred until a real schema needs it.
- **The `Row1` -> `Record1` switch.** Split out under R61
  ([`emit-record1-keys-instead-of-row1.md`](emit-record1-keys-instead-of-row1.md)). The framework's
  emission of `Row1<T>` keys for the `RowKeyed` / `MappedRowKeyed` variants leaves a developer
  signing `Set<Row1<Integer>>` with no value accessor at the application side. R61 tracks the
  emit-`Record1`-everywhere fix; R32's element-shape conversion (above) builds on top of it.
- **Strict return-type validation against `field.elementType()`** (this item's remaining
  in-scope work; see "Plan" below). The structural unwrapping (V from `Map<KeyType, V>` /
  `List<V>` per `BatchKey` + cardinality) lives in `buildServiceRowsMethod` today and could
  surface mismatches at classify time as `UnclassifiedField` rather than as javac errors on
  the generated `return ServiceClass.method(...)` line.
- **`ParamSource.Context`'s typed registry.** The emitter consumes `getContextArgument(env,
  name)` with `<T>` inference. Generate-time validation (unknown name, type mismatch)
  lands separately under [`typed-context-value-registry.md`](typed-context-value-registry.md).

## Plan

Strict return-type validation for child `@service`. Mirrors the existing root-only check that
`ServiceCatalog.reflectServiceMethod` runs against `ServiceDirectiveResolver.computeExpectedServiceReturnType`.

**Implementation.** In `ServiceDirectiveResolver.resolve`, after `enrichArgExtractions` returns
the resolved `MethodRef`, run a child-only post-reflection check:

1. Extract the `BatchKey.ParentKeyed` from the method's `Param.Sourced` (one helper, mirrors
   `FieldBuilder.extractBatchKey`).
2. Compute the expected outer return type from `(returnType, batchKey)` symmetrically with how
   `TypeFetcherGenerator.buildServiceRowsMethod` constructs the rows-method's declared return
   type today:
   - `keysElementType = GeneratorUtils.keyElementType(batchKey)`
   - `isMapped = batchKey instanceof MappedRowKeyed || MappedRecordKeyed`
   - `isList = returnType.wrapper().isList()`
   - `V = perKeyType` per arm:
     - `TableBoundReturnType` → raw `org.jooq.Record` (matches `RECORD` constant the emitter
       passes for `ServiceTableField`).
     - `ResultReturnType` with non-null `fqClassName` → `ClassName.bestGuess(fqClassName)`
       (matches `ServiceRecordField.elementType()`'s record arm).
     - `ScalarReturnType` with a standard scalar (`String`/`Boolean`/`Int`/`Float`/`ID`) →
       the standard Java type (matches `elementType()`'s scalar arm).
     - `ResultReturnType` with null `fqClassName`, non-standard scalar (custom or enum), or
       `PolymorphicReturnType` → null. Skip the strict check (same shape as the root path's
       null-fqClassName escape).
   - `valuePerKey = isList ? List<V> : V`
   - `expected = isMapped ? Map<KeyType, valuePerKey> : (isList ? List<List<V>> : List<V>)`
3. If `expected != null && !method.returnType().equals(expected)`, return
   `Resolved.Rejected(Rejection.structural("..."))` with the same message shape as the root
   check (names class.method, expected vs actual).

**Refactor.** Move the perKeyType computation into a single helper used by both
`buildServiceRowsMethod` (today) and the new validator (tomorrow), so the structural shape
is named in one place. Candidate location: a static method on `ChildField` or a small
package-private helper on `ServiceDirectiveResolver`. The expected-outer-type construction
itself (the four-arm switch on isMapped/isList) is small enough to inline at both sites if
factoring it adds cost.

**Tests.**

- `ServiceCatalogTest` already covers the root strict-return check. Add a small parallel
  resolver-tier test that constructs a child `@service` field with each of the four
  `BatchKey` variants × the cardinality cross-product, and asserts the resolver rejects on
  a deliberate mismatch (e.g. `Map<Record1<Integer>, Integer>` declared, schema expects
  `Map<Record1<Integer>, String>`).
- `GraphitronSchemaBuilderTest`: one positive case (existing `FilmService.titleUppercase`
  fixture continues to validate) and one negative case (a fixture with a mismatched
  declared return). Lift the negative-case fixture under `graphitron-fixtures-codegen` if
  reflection needs a real class.

**Out of scope.**

- Element-shape conversion (`Set<TableRecord>` developer signatures): tracked above, deferred.
- The `Row1` → `Record1` shift: tracked under R61.
- `ParamSource.Context` typed registry: tracked under
  [`typed-context-value-registry.md`](typed-context-value-registry.md).

## Dependencies

- Built on the four-variant `BatchKey` model shipped under `set-parent-keys-on-service`
  (changelog SHA `eebf881`); the emitter switches on those variants.
- Coordinates with [`typed-context-value-registry.md`](typed-context-value-registry.md): no
  hard dependency, just generate-time-validation polish on top of the working emission.
