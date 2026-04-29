---
id: R50
title: "Lift NodeId out of the model"
status: Backlog
bucket: architecture
priority: 3
theme: nodeid
depends-on: []
---

# Lift NodeId out of the model

`@nodeId` is a wire-format encoding (base64 over `typeId` + composite key), but the classifier model and emitted query builders carry the wire shape into layers that only need column refs and key tuples. Encode and decode belong at the DataFetcher boundary; everything below it should see decoded values. The smoking gun is `BodyParam.NodeIdIn`'s body, which emits `NodeIdEncoder.hasIds("typeId", arg, table.col1, ..., table.colN)` from inside a generated condition method, reaching the encoder across the DataFetcher / query-builder boundary; jOOQ should see decoded key tuples and a standard row-IN predicate.

## Wire-shape leaks to retire

- `InputField.NodeIdField`, `NodeIdReferenceField`, `NodeIdInFilterField`, `IdReferenceField`
- `ChildField.NodeIdField`, `ChildField.NodeIdReferenceField`
- `BodyParam.NodeIdIn`
- `LookupMapping.NodeIdMapping`
- `ArgumentRef.ScalarArg.NodeIdArg`

Each is the same wire-shape leak in a different position.

## What R50 must land

1. **Boundary extractions.** `CallSiteExtraction.NodeIdDecodeKeys(typeId)` for inputs (decodes `String` / `List<String>` to a key tuple / list of key tuples; validates `typeId` prefix; short-circuits empty / null / typeid-mismatched input). `NodeIdEncodeKeys(typeId)` (or equivalent projection-side hook) for outputs. Both are first-class members of `CallSiteExtraction`'s sealed hierarchy with full arms in `ArgCallEmitter.buildArgExtraction` and the projection-side counterpart, including the top-level-argument case (today `NestedInputField` is the only nested-Map traversal; the new variant is needed at top-level args too).

2. **Composite-key column carriers.** The column-shaped variants today are single-column (`InputField.ColumnField`, `ArgumentRef.ScalarArg.ColumnArg`, `BodyParam.ColumnEq`, `LookupMapping`'s key-list lives on the mapping itself). The replacements for the wire-shape variants need to carry a composite key: a multi-column field / arg / body param / mapping shape with row-IN body emission (`DSL.row(c1, ..., cN).in(rows)`), degenerating to single-column `c.in(...)` for arity-1. Either generalise the existing carriers to a `List<ColumnRef>` shape or introduce explicit composite siblings; the principle is "stay column-shaped, drop NodeId vocabulary".

3. **Variant-by-variant collapse.**
   - `InputField.NodeIdField` (output side, scalar) → column projection with `NodeIdEncodeKeys`.
   - `InputField.NodeIdReferenceField` → `ColumnReferenceField` with FK path; encoding lifts to projection with `NodeIdEncodeKeys`.
   - `InputField.NodeIdInFilterField` → composite `ColumnField` (or `ColumnInField` analog) with `NodeIdDecodeKeys` extraction; body is row-IN.
   - `InputField.IdReferenceField` → `ColumnReferenceField` over the FK columns with `NodeIdDecodeKeys` extraction; body is single FK-eq or row-IN over the FK columns. Subsumes R20.
   - `ChildField.NodeIdField` / `NodeIdReferenceField` → column projection forms with `NodeIdEncodeKeys`.
   - `BodyParam.NodeIdIn` → composite `BodyParam.ColumnEq` (or row-IN body param). `NodeIdEncoder.hasIds` deletes.
   - `LookupMapping.NodeIdMapping` → regular `LookupMapping` over the PK columns; `LookupValuesJoinEmitter` decodes via `NodeIdDecodeKeys` at the call site.
   - `ArgumentRef.ScalarArg.NodeIdArg` → `ColumnArg` (or composite analog) with `NodeIdDecodeKeys` extraction. Stale "Step 5 (the reference variant)" docstring and the matching `FieldBuilder.projectFilters` line 1211 "Step 4 follow-up" comment delete with the variant.

4. **`NodeIdEncoder` API.** `hasIds` deletes (it is a query-builder helper that does not belong in the encoder). `encode` / `decodeKeys` / `peekTypeId` / `canonicalize` stay (boundary helpers consumed by DataFetcher emitters and by the new extractions).

5. **Validator + dispatch coverage.** Every retired variant comes off `TypeFetcherGenerator.NOT_DISPATCHED_LEAVES` and `GraphitronSchemaValidator`'s no-op arms. The new extractions get arms in the validator's coverage tests.

## What stays

`GraphitronType.NodeType` stays. It carries `(typeId, keyColumns)`, which is type-level identity: the schema author declared this type as a Node and these are its key columns. Identity is a model concern; the wire format derived from that identity is not.

## Coupling

- **R40 (argument-level `@nodeId` support)** depends on R50. R40 is shaped as a small classifier-only follow-on once R50's foundation is in place: extend `FieldBuilder.classifyArgument` to read `@nodeId(typeName: T)` on an argument, validate same-table, build a column-shaped argument with `NodeIdDecodeKeys`. R40 does not land any new model variants and adds nothing to the wire-shape debt.
- **R20 (`IdReferenceField` code generation)** is in Spec on the legacy variant. The right shape under R50's framing emits standard FK-equality (single FK) or row-IN (composite FK) through a column-shaped variant, not a `has<Qualifier>` method call. R20's emission shape dissolves into R50; flip R20 back to Backlog when R50 moves to Spec, or fold its execution-tier coverage into R50's test surface.
- **R24 (`NodeIdReferenceField` JOIN-projection form)** also dissolves: the multi-hop / non-mirroring FK case becomes a column projection with `NodeIdEncodeKeys` once `NodeIdReferenceField` retires.

## Test surface (sketch; refine before Ready)

- Pipeline-tier: every retired variant's existing classification cases re-pointed at the column-shaped successor; assert no `NodeId*Field` / `NodeIdArg` / `BodyParam.NodeIdIn` / `LookupMapping.NodeIdMapping` instance survives in any classified model.
- Compilation-tier: `mvn compile -pl :graphitron-test -Plocal-db` against the `nodeidfixture` and `idreffixture` catalogs after the collapse.
- Execution-tier: every existing `@nodeId` execution test (`Query.node(id:)`, federated entities, `Query.nodes(ids:)`, same-table filter, FK-mirror reference) continues to round-trip; SQL inspection via `ExecuteListener` confirms `hasIds` is gone from emitted bodies and replaced with row-IN / column-eq.
