---
id: R32
title: "Implement `@service` rows-method body"
status: In Review
bucket: architecture
priority: 6
theme: service
depends-on: []
---

# Implement `@service` rows-method body

Three iterations have shipped:

1. **Body emission.** `buildServiceRowsMethod` walks `MethodRef.params()` via
   `ArgCallEmitter.buildMethodBackedCallArgs` (`Sources → keys`, `DslContext → dsl` local,
   `Arg`/`Context` via the existing extraction path) and shapes the body as
   `[DSLContext dsl = ...; ] return ServiceClass.method(<args>);`. Both `ServiceTableField`
   and `ServiceRecordField` use the same parameterised emitter; the developer's method
   returns the loader's expected `Map`/`List` shape directly, so no per-record projection
   step is needed (graphql-java's downstream wiring resolves columns off whatever records
   the developer returns). End-to-end exercised by
   `GraphQLQueryTest.films_titleUppercase_resolvesViaServiceRecordFieldDataLoader` (parent
   `SELECT` + one batched DataLoader round-trip).
2. **Strict child-service return-type validation.**
   `ServiceDirectiveResolver.validateChildServiceReturnType` rejects developer methods whose
   declared return type doesn't match the rows-method's outer shape (`Map<K, V>` /
   `List<List<V>>` / `List<V>` per the `(returnType, batchKey)` cross-product). Mirrors the
   existing root-only `ServiceCatalog.reflectServiceMethod` strict check; carries the
   `service-directive-resolver-strict-child-service-return` `@LoadBearingClassifierCheck`
   key, with the corresponding `@DependsOnClassifierCheck` on `buildServiceRowsMethod`.
3. **Lifted the resolved rows-method shape onto the model.** The validator and the emitter
   each used to reconstruct `Map<K, V> / List<List<V>> / List<V>` from `(returnType, batchKey)`
   independently, the per-key `V` derivation lived a third time on
   `ChildField.ServiceRecordField.elementType()` with a deliberately-divergent fallback, and
   `GeneratorUtils.keyElementType` had been bumped to `public` so the classifier-tier
   validator could import from the generators package. The shared form lives in two new
   model-package surfaces: `BatchKey.keyElementType()` (a `default` accessor on the sealed
   root, replacing the static helper in `GeneratorUtils`) and `RowsMethodShape.{strictPerKeyType,
   outerRowsReturnType,standardScalarJavaType}` (the per-key `V` decision and the
   `(isMapped, isList)` outer-shape construction). Validator and emitter now both call
   `RowsMethodShape.outerRowsReturnType(perKey, returnType, batchKey)`; only the `perKey`
   input differs (validator: `RowsMethodShape.strictPerKeyType` and skip on null; emitter:
   the field-known `V` from the literal `RECORD` constant or `srf.elementType()`). The
   `LoadBearingClassifierCheck` / `DependsOnClassifierCheck` pair still holds the contract
   at audit time, but the construction can no longer drift across sites.

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

§1 shipped (the parameterised arg-assembly walk above). §3 turned out unnecessary: the
developer's return value is handed back to graphql-java directly. §2 is the largest
remaining piece of the original spec, captured under "Open follow-ups" below.

## Recovering element-shape

`BatchKey` carries container × key-shape but not element-shape (`RowN` vs `TableRecord`
vs `RecordN`). Two options at implementation time:

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
- **`ParamSource.Context`'s typed registry.** The emitter consumes `getContextArgument(env,
  name)` with `<T>` inference. Generate-time validation (unknown name, type mismatch)
  lands separately under [`typed-context-value-registry.md`](typed-context-value-registry.md).

## Tests

- `GraphitronSchemaBuilderTest.UnclassifiedFieldCase.CHILD_SERVICE_TABLE_BOUND_WRONG_RETURN_REJECTED`
  pins the `TableBoundReturnType` rejection arm (declared `LanguageRecord` instead of
  `List<Record>`).
- `GraphitronSchemaBuilderTest.UnclassifiedFieldCase.CHILD_SERVICE_SCALAR_WRONG_VALUE_TYPE_REJECTED`
  pins the `ScalarReturnType` rejection arm (declared `Map<Record1<Integer>, Integer>` for
  a `String`-valued field).
- The two negative cells (one `TableBoundReturnType` × positional, one `ScalarReturnType` ×
  mapped) cover the helper's branches; the validator and the emitter share a single
  `RowsMethodShape.outerRowsReturnType` call so structural drift between them is no longer
  reachable, which is what the dropped positive cell would have pinned.
- Execution-tier coverage:
  `GraphQLQueryTest.films_titleUppercase_resolvesViaServiceRecordFieldDataLoader` continues to
  exercise the end-to-end positive path against PostgreSQL (`Set<Record1<Integer>>` /
  `Map<Record1<Integer>, String>`, `MappedRecordKeyed` × scalar single).

## Dependencies

- Built on the four-variant `BatchKey` model (`RowKeyed` / `RecordKeyed` /
  `MappedRowKeyed` / `MappedRecordKeyed`); the emitter and the validator both switch on those
  variants.
- Coordinates with [`typed-context-value-registry.md`](typed-context-value-registry.md): no
  hard dependency, just generate-time-validation polish on top of the working emission.
