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

`buildServiceRowsMethod` (`TypeFetcherGenerator.java:1501`) emits a stub that throws
`UnsupportedOperationException`. Fill the body so `@service` batched fields actually
invoke the user's service method and project results back into GraphQL. Three concerns
in one emitter:

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

## Dependencies

- Builds on the four-variant `BatchKey` model shipped under `set-parent-keys-on-service`
  (changelog SHA `eebf881`); the emitter switches on those variants.
- Coordinates with [`typed-context-value-registry.md`](typed-context-value-registry.md):
  this emitter's `ParamSource.Context` arm consumes `getContextArgument(env, name)` whose
  `<T>` inference already gives the right cast at the call site, so no hard dependency on
  the typed registry. When R45 lands, the args walk picks up generate-time validation
  (unknown name, type mismatch) for free.
