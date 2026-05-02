---
id: R32
title: "Implement `@service` rows-method body"
status: Backlog
bucket: architecture
priority: 6
theme: service
depends-on: []
---

# Implement `@service` rows-method body

First iteration shipped at `befc156` (alongside R49 Phase A close-out): `buildServiceRowsMethod` is no longer a stub. The body walks `MethodRef.params()` via `ArgCallEmitter.buildMethodBackedCallArgs` (`Sources → keys`, `DslContext → dsl` local, `Arg`/`Context` via the existing extraction path) and shapes as `[DSLContext dsl = ...; ] return ServiceClass.method(<args>);`. Both `ServiceTableField` and `ServiceRecordField` use the same parameterised emitter; the developer's method returns the loader's expected `Map`/`List` shape directly, so no per-record projection step is needed (graphql-java's downstream wiring resolves columns off whatever records the developer returns). End-to-end exercised by `GraphQLQueryTest.films_titleUppercase_resolvesViaServiceRecordFieldDataLoader` (parent `SELECT` + one batched DataLoader round-trip). R32 now tracks the four open follow-ups listed below; the original three-concern spec is preserved under "Original spec".

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
- **The `Row1` follow-up.** `Row1<T>` is jOOQ's SQL-expression type for tuple-IN comparisons,
  not an application-side artifact — it has no value accessor. A developer who signs
  `Set<Row1<Integer>>` cannot extract values from each key. The framework arguably should
  emit `Record1<T>` keys regardless of whether the dev's source-shape choice was `RowN`,
  `RecordN`, or `TableRecord` — `Record1` extends `Row1` (so SQL composition still works) and
  adds `value1()` for application-side reading. Today the workaround is for the dev to sign
  `Set<Record1<Integer>>` (classifies as `MappedRecordKeyed`, framework emits `Record1`).
  Worth a separate roadmap item.
- **Strict return-type validation against `field.elementType()`.** The structural unwrapping
  (V from `Map<KeyType, V>` / `List<V>` per BatchKey + cardinality) lives in this emitter
  and could surface mismatches to the Builder. R49's spec body documents this as a
  follow-up; not yet wired.
- **`ParamSource.Context`'s typed registry.** The emitter consumes `getContextArgument(env,
  name)` with `<T>` inference. Generate-time validation (unknown name, type mismatch)
  lands separately under [`typed-context-value-registry.md`](typed-context-value-registry.md).

## Dependencies

- Built on the four-variant `BatchKey` model shipped under `set-parent-keys-on-service`
  (changelog SHA `eebf881`); the emitter switches on those variants.
- Coordinates with [`typed-context-value-registry.md`](typed-context-value-registry.md): no
  hard dependency, just generate-time-validation polish on top of the working emission.
