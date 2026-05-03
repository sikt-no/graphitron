---
id: R70
title: "Support TableRecord-keyed Map returns on @service rows methods"
status: Backlog
bucket: feature
priority: 5
theme: service
depends-on: []
---

# Support TableRecord-keyed Map returns on @service rows methods

A child `@service` whose SOURCES parameter is `Set<X>` (X a jOOQ `TableRecord`) classifies as `MappedRowKeyed` today, mirroring the docstring on `BatchKey.MappedRowKeyed` ("both `Set<RowN<...>>` and `Set<TableRecord>` classify here"). The classifier-side coverage is symmetrical only on the parameter, however: `RowsMethodShape.outerRowsReturnType` always builds `Map<RowN<...>, V>` from `BatchKey.keyElementType()`, so the strict `TypeName.equals` check in `ServiceDirectiveResolver.validateChildServiceReturnType` rejects the natural symmetric shape `Map<X, V>` (where the developer reused the same `TableRecord` across the parameter and the map key). Real-world example surfaced from the `regelverk_exp.graphqls` schema:

```java
public Map<KvotesporsmalRecord, OversatteTekster> kvotesporsmalNavn(Set<KvotesporsmalRecord> keys) { ... }
```

is rejected with "method `kvotesporsmalNavn` ... must return `Map<Row3<String, String, String>, OversatteTekster>` ... got `Map<KvotesporsmalRecord, OversatteTekster>`", forcing the developer to either rewrite their service in terms of `Row3` (losing the typed-record ergonomics) or drop down to `Set<RowN<...>>` on the parameter to keep the asymmetry consistent. The emitter side has the same gap: the `buildServiceRowsMethod` body delegates `keys` straight to the developer's method without doing any element-shape conversion, so even today's accepted-but-deferred `Set<TableRecord>` parameter shape miscompiles when the rows method emits `return ServiceClass.method(keys)` against a `Set<RowN<...>>` local. Closing both halves together (validator accepts the `TableRecord` map-key shape; emitter converts `Set<RowN<...>>` → `Set<TableRecord>` before the call and `Map<TableRecord, V>` → `Map<RowN<...>, V>` after the return, using the parent table's row constructor and `record.into(...)`) gives the symmetric typed-record shape end-to-end and lets `RowsMethodShape.strictPerKeyType` keep the `RowN` key as the framework-internal hash type the DataLoader contract requires.
