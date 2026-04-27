---
title: "NodeId emission from JSON-encoded row identity"
status: Backlog
bucket: architecture
priority: 12
---

# NodeId emission from JSON-encoded row identity

Produce a relay nodeId for a row whose identity arrives as a JSON
representation (typeName + key columns) rather than as a fully materialised
jOOQ `TableRecord`. The shape mirrors what the legacy multi-table interface
fetcher emits: `DSL.jsonbArray(DSL.inline("Customer"), CUSTOMER.CUSTOMER_ID)`
produces a `JSONB` value whose first element is the GraphQL type name and whose
remaining elements are the primary-key columns of the row's source table.

The new functionality lifts that JSON value into the corresponding
`TableRecord<?>` (using the typeName to pick the record class, and the
remaining JSON elements as positional column values), then runs the lifted
record through `NodeIdEncoder.encode(typeId, pkValues...)`. End result: a
fully-formed relay nodeId that round-trips back through `Query.node` to the
same row.

## Why this is needed

The rewrite's nodeId path
(`generators/util/NodeIdEncoderClassGenerator.java`,
`generators/util/QueryNodeFetcherClassGenerator.java`) currently assumes the
caller already has `(typeId, pkColumnValues...)` in hand. That works when the
fetcher reads concrete columns off a known table at SQL time. It does not
cover the cases where row identity is intentionally polymorphic at SQL time:

- **Multi-table interface / union fetchers** (Track B of
  [`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md)).
  Legacy `FetchMultiTableDBMethodGenerator` emits
  `DSL.jsonbArray(DSL.inline("Customer"), _a_customer.CUSTOMER_ID).as("$pkFields")`
  in the union sub-selects so the outer join can match rows across heterogeneous
  participant tables on a single JSONB key. The same JSONB value already
  carries everything `NodeIdEncoder.encode` needs; today there is no rewrite-side
  glue to turn it back into an ID.
- **Apollo Federation entity resolution** ([`federation-via-federation-jvm.md`](federation-via-federation-jvm.md)).
  `_entities` over a `@node` type wants a nodeId for each row it returns; when
  the entity batch is heterogeneous, the JSON-keyed shape is the natural carrier.
- **Anywhere a developer-supplied `@service` returns a polymorphic result.**
  Without this, the service has to know about `NodeIdEncoder` directly, which
  pierces the generator's "developers do not import generated utility classes"
  contract.

## Sketch of the path

A new helper on the generated `NodeIdEncoder` (or a sibling class) accepts the
JSONB value and returns the encoded ID:

```java
// pseudo-shape; exact API decided when the spec is written
public static String encodeFromJsonRow(JSONB jsonRow) {
    var arr = parseJsonArray(jsonRow);                  // ["Customer", "42"]
    String typeName = arr.getFirst();                   // "Customer"
    Class<? extends TableRecord<?>> recordClass = registry.recordClassFor(typeName);
    TableRecord<?> rec = lift(recordClass, arr.tail()); // populate PK columns positionally
    return encode(typeIdFor(typeName), pkValuesOf(rec));
}
```

The `registry` here is a generated lookup table built once per code-generation
run, populated from every `NodeType` in the schema. Lifting is positional
(JSON element `i+1` → record's `i`-th `nodeKeyColumns()` entry) so the wire
format is symmetric with what `DSL.jsonbArray` emits on the SQL side.

## Open questions for the spec phase

- **Where does the registry live?** Either inline static fields on
  `NodeIdEncoder` (one more arm next to `encode`/`peekTypeId`/`hasIds`), or a
  separate generated class (e.g. `NodeIdJsonRowLifter`) so the encoder stays
  focused on the wire format. The latter keeps `NodeIdEncoder` re-usable in
  contexts that have no jOOQ-record dependency.
- **Type-name vs. type-id in the JSON head.** Legacy uses the GraphQL type name
  (`"Customer"`); the rewrite's `NodeIdEncoder.encode` takes the `typeId`
  (`__NODE_TYPE_ID`). The lifter has to translate. Confirm the chosen direction
  with the federation plan before locking it in (federation may want type-name
  in the JSON for cross-service portability).
- **Null-safety contract.** `NodeIdEncoder.encode` returns `null` when any
  value is null. The lifter should match: a JSON null in any PK slot ->
  `null` ID, no exception. The `Query.node(id:)` contract treats unknown IDs
  as `null` results, so this composes cleanly.
- **Composite-key correctness.** Verify the lifter handles tables whose
  `nodeKeyColumns()` are not a single column (already supported by
  `NodeIdEncoder.encode`'s varargs, but the JSON-array-tail mapping has to
  preserve column order).

## Related

- [`stub-interface-union-fetchers.md`](stub-interface-union-fetchers.md) — Track B's multi-table polymorphic path is the primary consumer.
- [`federation-via-federation-jvm.md`](federation-via-federation-jvm.md) — `_entities` over `@node` types routes through the same encoder; this item is a building block for the polymorphic-batch case.
- [`auto-nodes-relay-resolver.md`](auto-nodes-relay-resolver.md) — sister item for the resolver-side relay composition (`nodes` from `node`); this one covers the encoder-side polymorphic-row case.
- Legacy reference shape: `FetchMultiTableDBMethodGenerator.getPrimaryKeyFieldsArray` — `graphitron-codegen-parent/.../db/FetchMultiTableDBMethodGenerator.java:411`.
