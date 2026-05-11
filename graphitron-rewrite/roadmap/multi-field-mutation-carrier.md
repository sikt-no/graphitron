---
id: R128
title: "Multi-field mutation carriers via localContext"
status: Backlog
bucket: architecture
priority: 8
theme: mutations-errors
depends-on: []
---

# Multi-field mutation carriers via localContext

R75 admits single-data-field DML mutation carriers (`type FooPayload { foo: [Foo!] }`).
This item extends admission to multi-field carriers where one field is the
`@table`-element (or, post-R75 Phase 2, `@record`-element) data field and the rest are
non-data slot fields carrying state via graphql-java's `DataFetcherResult.localContext`.

The work is split out of R75 because R75 in its current shape would ship a slot-field
permit and a `DataFetcherResult` wrap on day 1 with no populator wired in — dead
infrastructure that hides the missing populator behind silent `null` slot renders.
Multi-field admission earns its place alongside the first real populator (errors is the
canonical candidate, tracked separately); this item is its model-side prerequisite.

## Key insight: `PropertyField.Reader` sub-axis

R75's draft Phase 2 introduced a new `ChildField.PassthroughSlotField` permit whose
entire content was `(name, returnType)` and whose fetcher emit was a one-line
`env -> env.getLocalContext().get(name)`. That permit is paper-thin and overlaps
structurally with the existing `ChildField.PropertyField`, which already expresses
"read a named property off the parent" via nullable `(columnName, column, accessor)`
slots that today encode the reader axis implicitly.

The cleaner shape is to lift the reader axis on `PropertyField` into a sealed
sub-interface mirroring R38's `SourceKey.Reader` pattern:

```java
record PropertyField(
    String parentTypeName,
    String name,
    SourceLocation location,
    Reader reader
) implements ChildField {
    sealed interface Reader {
        record ColumnRead(String columnName, ColumnRef column) implements Reader {}
        record AccessorCall(AccessorResolution.Resolved accessor) implements Reader {}
        record LocalContextRead() implements Reader {}
    }
}
```

`ColumnRead` covers jOOQ-record parents; `AccessorCall` covers Java-record / POJO
parents; the new `LocalContextRead` covers single-record carrier slot fields. The
classifier picks the arm based on parent type and the SDL field's directive load; the
fetcher emit dispatches on the variant. No new `ChildField` permit is needed for slots.

The three implicit-nullable slots fold in, lifting the "column XOR accessor XOR
neither" constraint out of prose and into the type system. Aligned with the
"Sealed hierarchies over enums for typed information" principle.

## Scope

- `PropertyField` gains the sealed `Reader` sub-axis; existing producer / consumer
  sites migrate to the new shape.
- R75's `tryResolveSingleRecordCarrier` trigger extends to admit multi-field shapes
  (one `@table`-element data field plus zero or more non-`@table`-element slot fields).
  Multi-`@table`-element payloads stay rejected (compound mutations are R122 territory).
- R75's `MutationField.MutationDmlRecordField` gains a `slots: List<SlotDescriptor>`
  component; the mutation fetcher wraps in `DataFetcherResult.<...>newResult().data(rows)
  .localContext(emptyMap()).build()` when `slots` is non-empty.
- Slot fields classify as `PropertyField` with `Reader.LocalContextRead`.
- Service-mutation parity: the `@service` consumer constructs `DataFetcherResult`
  directly; slot fetchers read from the consumer-populated `localContext`.

## Out of scope

- Slot populators (per-slot family, in their own roadmap items). This item ships the
  carriage; the first populator activates it.
- `@record`-element data on multi-field carriers (folds in once R75 Phase 2 lands
  `@record`-element single-data carriers).
- Compound mutations with multiple `@table`-element data fields (R122).

## Notes for the Spec author

- The `PropertyField.Reader` extension is a model refactor with broad reach: every
  existing `PropertyField` producer and consumer migrates. Audit `FieldBuilder`
  producers, `FetcherEmitter.dataFetcherValue`, `GraphitronSchemaValidator
  .validatePropertyField`, and `TypeFetcherGenerator`'s `PROJECTED_LEAVES` dispatch arm.
- `ChildField.ErrorsField` is adjacent — currently a separate permit reading via
  graphql-java's default property fetcher. Whether it folds into `PropertyField` with
  a fourth `Reader.DefaultProperty` arm or stays separate is a side question; the
  errors-channel populator's needs likely decide.
- The single-record-carrier classifier guarantee (every `PropertyField` on a
  `SingleRecordTableField` parent has `Reader.LocalContextRead`) is load-bearing for
  the fetcher emit; record it as `@LoadBearingClassifierCheck`.
